package com.lagradost.cloudstream3.ar.youtube

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import kotlinx.coroutines.coroutineScope
import com.google.gson.Gson
class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    class YouTubeInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )
                .addHeader(
                    "Cookie",
                    "VISITOR_INFO1_LIVE=fzYjM8PCwjw; PREF=f6=40000000&hl=en; CONSENT=YES+fx.456722336;"
                )
                .build()
            return chain.proceed(request)
        }
    }
    @Suppress("PropertyName")
    private data class PlayerRequest(val context: PlayerContext, val videoId: String)

    @Suppress("PropertyName")
    private data class PlayerContext(val client: PlayerClient)

    @Suppress("PropertyName")
    private data class PlayerClient(
        val hl: String = "en",
        val gl: String = "US",
        val clientName: String = "WEB",
        val clientVersion: String,
        val userAgent: String,
        val visitorData: String
    )

    private data class PlayerResponse(@JsonProperty("streamingData") val streamingData: StreamingData?)
    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?)

    private val ytInterceptor = YouTubeInterceptor()

    private val safariUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"

    // session-scoped saved token & visitorData (fallback in case framework doesn't pass request.data)
    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>

    private fun Map<*, *>.getString(key: String): String? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? String

    private fun safeTruncate(s: String?, max: Int = 800): String {
        if (s == null) return "null"
        return if (s.length <= max) s else "${s.take(max)}...[truncated:${s.length}]"
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        Log.d(name, "extractYtInitialData: html length=${html.length}")
        val regex = Regex(
            """(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*\});""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = try { regex.find(html) } catch (e: Exception) {
            Log.e(name, "Regex find on html failed", e); null
        }
        val jsonString = match?.groupValues?.getOrNull(1)
        Log.d(name, "ytInitialData found: ${jsonString != null}")
        if (jsonString != null) {
            Log.d(name, "ytInitialData snippet: ${safeTruncate(jsonString, 800)}")
        } else {
            Log.e(name, "ytInitialData regex did not match.")
        }

        return jsonString?.let {
            try {
                parseJson<Map<String, Any>>(it)
            } catch (e: Exception) {
                Log.e(name, "Failed to parse ytInitialData JSON", e)
                Log.e(name, "Problematic JSON snippet: ${safeTruncate(it, 800)}")
                null
            }
        }
    }

    private fun findConfig(html: String, key: String): String? {
        return try {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)
            val found = m?.groupValues?.getOrNull(1)
            Log.d(name, "findConfig key=$key -> ${found != null}")
            found
        } catch (e: Exception) {
            Log.e(name, "findConfig failed for key=$key", e)
            null
        }
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        try { Log.d(name, "collectFromRenderer called. renderer keys=${renderer.keys.joinToString(","){it.toString()}}") } catch (_: Exception) {}

        // 1) videoRenderer / compactVideoRenderer / gridVideoRenderer (كما كان)
        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")
        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = extractTitle(videoData.getMapKey("title")) ?: "YouTube Video"
                var poster = videoData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                Log.d(name, "collectFromRenderer: videoRenderer -> id=$videoId title='${safeTruncate(title,100)}' poster=$poster")
                return newMovieSearchResponse(title, "$mainUrl/watch?v=$videoId", TvType.Movie) { this.posterUrl = poster }
            }
        }

        // 2) videoWithContextRenderer (ظهَر في الـ logs) — يحتوي غالبًا على videoRenderer داخله أو videoId مباشرة
        val videoWithContext = renderer.getMapKey("videoWithContextRenderer")
        if (videoWithContext != null) {
            val inner = videoWithContext.getMapKey("videoRenderer") ?: videoWithContext.getMapKey("content") ?: videoWithContext
            val videoId = inner.getString("videoId") ?: videoWithContext.getString("videoId")
            ?: videoWithContext.getMapKey("navigationEndpoint")?.getMapKey("watchEndpoint")?.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = extractTitle(inner.getMapKey("title")) ?: extractTitle(videoWithContext.getMapKey("headline")) ?: "YouTube Video"
                var poster = inner.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                    ?: buildThumbnailFromId(videoId)
                Log.d(name, "collectFromRenderer: videoWithContextRenderer -> id=$videoId title='${safeTruncate(title,100)}' poster=$poster")
                return newMovieSearchResponse(title, "$mainUrl/watch?v=$videoId", TvType.Movie) { this.posterUrl = poster }
            }
        }

        // 3) reelItemRenderer (shorts)
        val reelData = renderer.getMapKey("reelItemRenderer")
        if (reelData != null) {
            val videoId = reelData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = extractTitle(reelData.getMapKey("headline")) ?: "YouTube Short"
                var poster = reelData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                Log.d(name, "collectFromRenderer: reelItemRenderer -> id=$videoId title='${safeTruncate(title,100)}' poster=$poster")
                return newMovieSearchResponse("[Shorts] $title", "$mainUrl/shorts/$videoId", TvType.Movie) { this.posterUrl = poster }
            }
        }

        // 4) lockupViewModel (baked-in content blocks)
        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            val vid = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))
            if (!vid.isNullOrBlank() && seenIds.add(vid)) {
                var title: String? = null
                try {
                    title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")?.getMapKey("title")?.getString("content")
                } catch (_: Exception) {}
                if (title.isNullOrBlank()) title = lockup.getMapKey("metadata")?.getMapKey("title")?.getString("simpleText")
                if (title.isNullOrBlank()) title = "YouTube Video"
                var poster = lockup.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                    ?: lockup.getMapKey("content")?.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(vid)
                Log.d(name, "collectFromRenderer: lockupViewModel -> id=$vid title='${safeTruncate(title,100)}' poster=$poster")
                return newMovieSearchResponse(title, "$mainUrl/watch?v=$vid", TvType.Movie) { this.posterUrl = poster }
            }
        }

        // 5) shortsLockup variants (fallbacks)
        val shortsVariant = renderer.getMapKey("shortsLockupViewModel")
            ?: renderer.getMapKey("shortsLockupRenderer")
            ?: renderer.getMapKey("reelItemPreviewRenderer")
        if (shortsVariant != null) {
            val onTap = shortsVariant.getMapKey("onTap")
            val videoId = (onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")?.getString("videoId"))
                ?: shortsVariant.getString("videoId")
                ?: shortsVariant.getMapKey("content")?.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = shortsVariant.getMapKey("overlayMetadata")?.getMapKey("primaryText")?.getString("content")
                    ?: shortsVariant.getMapKey("overlayMetadata")?.getMapKey("primaryText")?.getString("simpleText")
                    ?: "YouTube Short"
                var poster = shortsVariant.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                Log.d(name, "collectFromRenderer: shorts variant -> id=$videoId title='${safeTruncate(title,100)}' poster=$poster")
                return newMovieSearchResponse("[Shorts] $title", "$mainUrl/shorts/$videoId", TvType.Movie) { this.posterUrl = poster }
            }
        }

        Log.d(name, "collectFromRenderer: no known renderer produced a SearchResponse")
        return null
    }


    // =================================================================================
    //  وظائف مساعدة جديدة للبحث العميق (تحاكي منطق Python)
    // =================================================================================


    private fun deepFindRenderers(data: Any?, outList: MutableList<Map<*, *>>) {
        if (data is Map<*, *>) {
            val keys = data.keys
            // الكلمات المفتاحية التي تدل على أن هذا الكائن هو فيديو
            val isContent = keys.any { k ->
                k is String && (
                        k == "videoRenderer" ||
                                k == "compactVideoRenderer" ||
                                k == "gridVideoRenderer" ||
                                k == "reelItemRenderer" ||
                                k == "shortsLockupViewModel" ||
                                k == "lockupViewModel"
                        )
            }

            if (isContent) {
                outList.add(data)
                return
            }

            for (value in data.values) {
                deepFindRenderers(value, outList)
            }
        } else if (data is List<*>) {
            for (item in data) {
                deepFindRenderers(item, outList)
            }
        }
    }

    private fun findContinuationToken(data: Map<*, *>?): String? {
        if (data == null) return null

        // 1. محاولة البحث في Actions (الأكثر شيوعاً في التمرير)
        val actions = data.getListKey("onResponseReceivedActions")
            ?: data.getListKey("onResponseReceivedEndpoints")
            ?: data.getListKey("onResponseReceivedCommands")

        if (actions != null) {
            for (action in actions) {
                val items = action.getMapKey("appendContinuationItemsAction")?.getListKey("continuationItems")
                if (items != null) {
                    val token = items.lastOrNull()
                        ?.getMapKey("continuationItemRenderer")
                        ?.getMapKey("continuationEndpoint")
                        ?.getMapKey("continuationCommand")
                        ?.getString("token")
                    if (!token.isNullOrBlank()) return token
                }
            }
        }

        // 2. محاولة البحث في sectionListContinuation
        val sectionContinuations = data.getMapKey("continuationContents")
            ?.getMapKey("sectionListContinuation")
            ?.getListKey("continuations")

        if (sectionContinuations != null) {
            val token = sectionContinuations.lastOrNull()
                ?.getMapKey("nextContinuationData")
                ?.getString("continuation")
            if (!token.isNullOrBlank()) return token
        }

        // 3. محاولة البحث في richGridContinuation
        val gridItems = data.getMapKey("continuationContents")
            ?.getMapKey("richGridContinuation")
            ?.getListKey("items")

        if (gridItems != null) {
            val token = gridItems.lastOrNull()
                ?.getMapKey("continuationItemRenderer")
                ?.getMapKey("continuationEndpoint")
                ?.getMapKey("continuationCommand")
                ?.getString("token")
            if (!token.isNullOrBlank()) return token
        }

        // 4. بحث يدوي سريع في الصفحة الأولى
        val tokenFromContents = data.getMapKey("contents")
            ?.getMapKey("twoColumnSearchResultsRenderer")
            ?.getMapKey("primaryContents")
            ?.getMapKey("sectionListRenderer")
            ?.getListKey("contents")
            ?.lastOrNull()
            ?.getMapKey("continuationItemRenderer")
            ?.getMapKey("continuationEndpoint")
            ?.getMapKey("continuationCommand")
            ?.getString("token")

        if (!tokenFromContents.isNullOrBlank()) return tokenFromContents

        return null
    }

    private fun extractItems(data: Map<*, *>?): List<Map<*, *>> {
        if (data == null) return emptyList()
        val results = mutableListOf<Map<*, *>>()

        // نعتمد على البحث العميق مباشرة لأنه أضمن طريقة مع يوتيوب
        // Deep Scan: يبحث في كل زاوية في الـ JSON عن videoRenderer وما شابه
        deepFindRenderers(data, results)

        Log.d(name, "extractItems: Deep Scan found ${results.size} raw items")
        return results
    }

    private fun <T> MutableList<T>.addIfNotNull(item: T?) { if (item != null) this.add(item) }

    // =================================================================================
    //  MAIN PAGE & SEARCH
    // =================================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage called. page=$page, request.data='${request.data}'")

        // Treat page>1 as continuation (framework may call page=2 with empty request.data)
        val isContinuation = page > 1
        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null

        try {
            if (!isContinuation) {
                Log.d(name, "getMainPage: fetching initial main page HTML")
                val html = app.get(mainUrl, interceptor = ytInterceptor).text
                Log.d(name, "getMainPage: main page HTML length=${html.length}")

                // save visitorData from page (used later for continuation)
                val v = findConfig(html, "VISITOR_DATA")
                savedVisitorData = v
                Log.d(name, "getMainPage: savedVisitorData found=${!v.isNullOrBlank()} (truncated: ${safeTruncate(v,60)})")

                val initialData = extractYtInitialData(html)
                if (initialData == null) {
                    Log.e(name, "getMainPage: Failed to extract ytInitialData for main page")
                    return newHomePageResponse(request.copy(data = ""), emptyList(), hasNext = false)
                }

                val items = extractItems(initialData)
                Log.d(name, "getMainPage: Initial items count = ${items.size}")

                var idx = 0
                items.forEach { rawItem ->
                    idx++
                    try {
                        if ((rawItem as? Map<*, *>)?.containsKey("richItemRenderer") == true) {
                            val content = rawItem.getMapKey("richItemRenderer")?.getMapKey("content")
                            val res = collectFromRenderer(content as? Map<*, *>, seenIds)
                            if (res != null) { results.add(res); Log.d(name, "getMainPage: added richItemRenderer item #$idx -> ${res.url}") }
                            else Log.d(name, "getMainPage: collectFromRenderer returned null for richItemRenderer item #$idx")
                            return@forEach
                        }

                        if ((rawItem as? Map<*, *>)?.containsKey("richSectionRenderer") == true) {
                            val shelfContents = rawItem.getMapKey("richSectionRenderer")?.getMapKey("content")?.getMapKey("richShelfRenderer")?.getListKey("contents") ?: emptyList()
                            var shelfIdx = 0
                            shelfContents.forEach { shelfIt ->
                                shelfIdx++
                                try {
                                    val content = (shelfIt as? Map<*, *>)?.getMapKey("richItemRenderer")?.getMapKey("content") ?: (shelfIt as? Map<*, *>)
                                    val res = collectFromRenderer(content as? Map<*, *>, seenIds)
                                    if (res != null) { results.add(res); Log.d(name, "getMainPage: added shelf item #$idx.$shelfIdx -> ${res.url}") }
                                    else Log.d(name, "getMainPage: collectFromRenderer returned null for shelf item #$idx.$shelfIdx")
                                } catch (e: Exception) { Log.e(name, "getMainPage: exception processing shelf item #$idx.$shelfIdx", e) }
                            }
                            return@forEach
                        }

                        if ((rawItem as? Map<*, *>)?.containsKey("continuationItemRenderer") == true) {
                            Log.d(name, "getMainPage: encountered continuationItemRenderer at item #$idx (skipping)")
                            return@forEach
                        }

                        val directRes = collectFromRenderer(rawItem as? Map<*, *>, seenIds)
                        if (directRes != null) { results.add(directRes); Log.d(name, "getMainPage: added direct item #$idx -> ${directRes.url}") ; return@forEach }
                        Log.d(name, "getMainPage: unknown item type at #$idx, keys=${(rawItem as? Map<*, *>)?.keys?.joinToString(",") ?: "not-map"}")
                    } catch (e: Exception) { Log.e(name, "getMainPage: exception processing item #$idx", e) }
                }

                nextContinuation = findContinuationToken(initialData)
                Log.d(name, "getMainPage: Initial continuation token = ${safeTruncate(nextContinuation, 200)}")
                if (!nextContinuation.isNullOrBlank()) {
                    savedContinuationToken = nextContinuation
                    Log.d(name, "getMainPage: savedContinuationToken updated (initial).")
                }
            } else {
                Log.d(name, "getMainPage: Fetching continuation. page=$page, request.data='${request.data}'")

                val tokenToUse = if (request.data.isNotBlank()) request.data else savedContinuationToken
                Log.d(name, "getMainPage: tokenToUse (truncated) = ${safeTruncate(tokenToUse,120)}")
                if (tokenToUse.isNullOrBlank()) {
                    Log.e(name, "getMainPage: no continuation token available (request.data empty and no savedContinuationToken). Returning empty.")
                    return newHomePageResponse(request.copy(data = ""), emptyList(), hasNext = false)
                }

                // IMPORTANT CHANGE: use mobile (m.youtube.com) page to extract INNERTUBE_API_KEY & CLIENT_VERSION and use MWEB/MOBILE client
                val mobileMainPageHtml = try {
                    app.get("https://m.youtube.com", interceptor = ytInterceptor).text
                } catch (e: Exception) {
                    Log.e(name, "getMainPage: failed to fetch m.youtube.com for continuation", e)
                    ""
                }

                val apiKey = findConfig(mobileMainPageHtml, "INNERTUBE_API_KEY") ?: findConfig(mainUrl, "INNERTUBE_API_KEY") ?: run {
                    Log.e(name, "getMainPage: INNERTUBE_API_KEY not found; can't fetch continuation"); return newHomePageResponse(request.copy(data = ""), emptyList(), hasNext = false)
                }
                val clientVersion = findConfig(mobileMainPageHtml, "INNERTUBE_CLIENT_VERSION") ?: findConfig(mainUrl, "INNERTUBE_CLIENT_VERSION") ?: "2.20251114.01.00"

                // prefer savedVisitorData if present
                val visitorData = savedVisitorData ?: findConfig(mobileMainPageHtml, "VISITOR_DATA") ?: findConfig(mainUrl, "VISITOR_DATA")
                Log.d(name, "getMainPage: using visitorData present=${!visitorData.isNullOrBlank()} (truncated=${safeTruncate(visitorData,60)})")

                // Build payload oriented to mobile (MWEB) client - this often yields working continuation responses
                val payload = mapOf(
                    "context" to mapOf(
                        "client" to mapOf(
                            "visitorData" to (visitorData ?: ""),
                            "clientName" to "MWEB",
                            "clientVersion" to clientVersion,
                            "platform" to "MOBILE",
                            "userAgent" to mobileUserAgent
                        )
                    ),
                    "continuation" to tokenToUse
                )

                // headers tuned for MWEB continuation
                val postHeaders = mutableMapOf(
                    "Content-Type" to "application/json",
                    "X-Youtube-Client-Name" to "MWEB",
                    "X-Youtube-Client-Version" to clientVersion,
                    "User-Agent" to mobileUserAgent,
                    "Origin" to "https://m.youtube.com",
                    "Referer" to "https://m.youtube.com/"
                )
                if (!visitorData.isNullOrBlank()) {
                    postHeaders["X-Goog-Visitor-Id"] = visitorData
                }

                Log.d(name, "getMainPage: posting continuation payload (token truncated): ${safeTruncate(tokenToUse, 120)}")
                Log.d(name, "getMainPage: continuation POST headers: ${postHeaders.keys.joinToString(",")}")

                val response = try {
                    // prefer m.youtube.com endpoint for continuity
                    app.post("https://m.youtube.com/youtubei/v1/browse?key=$apiKey", json = payload, headers = postHeaders, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                } catch (e: Exception) {
                    Log.e(name, "getMainPage: exception while calling youtubei continuation endpoint", e)
                    null
                }

                if (response == null) {
                    Log.e(name, "getMainPage: Failed parsing continuation response")
                    return newHomePageResponse(request.copy(data = ""), emptyList(), hasNext = false)
                }

                // debug top-level keys
                try { Log.d(name, "getMainPage: continuation response top-level keys: ${response.keys.joinToString(",") { it.toString() }}") } catch (_: Exception) {}

                val items = extractItems(response)
                Log.d(name, "getMainPage: Continuation items count = ${items.size}")

                var idx = 0
                items.forEach { rawItem ->
                    idx++
                    try {
                        if ((rawItem as? Map<*, *>)?.containsKey("richItemRenderer") == true) {
                            val content = rawItem.getMapKey("richItemRenderer")?.getMapKey("content")
                            val res = collectFromRenderer(content as? Map<*, *>, seenIds)
                            if (res != null) { results.add(res); Log.d(name, "getMainPage: (cont) added richItemRenderer item #$idx -> ${res.url}") }
                            else Log.d(name, "getMainPage: (cont) collectFromRenderer returned null for richItemRenderer item #$idx")
                            return@forEach
                        }

                        if ((rawItem as? Map<*, *>)?.containsKey("richSectionRenderer") == true) {
                            val shelfContents = rawItem.getMapKey("richSectionRenderer")?.getMapKey("content")?.getMapKey("richShelfRenderer")?.getListKey("contents") ?: emptyList()
                            var shelfIdx = 0
                            shelfContents.forEach { shelfIt ->
                                shelfIdx++
                                try {
                                    val content = (shelfIt as? Map<*, *>)?.getMapKey("richItemRenderer")?.getMapKey("content") ?: (shelfIt as? Map<*, *>)
                                    val res = collectFromRenderer(content as? Map<*, *>, seenIds)
                                    if (res != null) { results.add(res); Log.d(name, "getMainPage: (cont) added shelf item #$idx.$shelfIdx -> ${res.url}") }
                                    else Log.d(name, "getMainPage: (cont) collectFromRenderer returned null for shelf item #$idx.$shelfIdx")
                                } catch (e: Exception) { Log.e(name, "getMainPage: (cont) exception processing shelf item #$idx.$shelfIdx", e) }
                            }
                            return@forEach
                        }

                        if ((rawItem as? Map<*, *>)?.containsKey("continuationItemRenderer") == true) {
                            Log.d(name, "getMainPage: (cont) encountered continuationItemRenderer at item #$idx (skipping)")
                            return@forEach
                        }

                        val directRes = collectFromRenderer(rawItem as? Map<*, *>, seenIds)
                        if (directRes != null) { results.add(directRes); Log.d(name, "getMainPage: (cont) added direct item #$idx -> ${directRes.url}"); return@forEach }
                        Log.d(name, "getMainPage: (cont) unknown item type at #$idx, keys=${(rawItem as? Map<*, *>)?.keys?.joinToString(",") ?: "not-map"}")
                    } catch (e: Exception) { Log.e(name, "getMainPage: (cont) exception processing item #$idx", e) }
                }

                nextContinuation = findContinuationToken(response)
                Log.d(name, "getMainPage: Next continuation token from continuation response = ${safeTruncate(nextContinuation, 200)}")
                if (!nextContinuation.isNullOrBlank()) {
                    savedContinuationToken = nextContinuation
                    Log.d(name, "getMainPage: savedContinuationToken updated (continuation).")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "getMainPage top-level exception", e)
            logError(e)
        }

        Log.d(name, "getMainPage: Returning ${results.size} items. hasNext=${!nextContinuation.isNullOrBlank()}")

        return newHomePageResponse(
            request.copy(data = nextContinuation ?: ""),
            results,
            hasNext = !nextContinuation.isNullOrBlank()
        )
    }
    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
        Log.d(name, "search(page) START query='$query' page=$page")

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var hasMore = false

        // ==================================================
        // PAGE 1: تهيئة البحث وجلب التوكن الأول
        // ==================================================
        if (page == 1) {
            savedContinuationToken = null
            savedVisitorData = null

            try {
                val encoded = URLEncoder.encode(query, "utf-8")
                // نستخدم رابط النتائج العادي
                val searchUrl = "$mainUrl/results?search_query=$encoded"

                // نطلب الصفحة كأننا موبايل لزيادة فرصة الحصول على JSON نظيف
                val html = app.get(searchUrl, interceptor = ytInterceptor, headers = mapOf("User-Agent" to mobileUserAgent)).text

                savedVisitorData = findConfig(html, "VISITOR_DATA")
                val initialData = extractYtInitialData(html)

                if (initialData != null) {
                    // 1. استخراج الفيديوهات
                    val rawItems = extractItems(initialData)
                    rawItems.forEach { item ->
                        collectFromRenderer(item as? Map<*, *>, seenIds)?.let { results.add(it) }
                    }

                    // 2. البحث عن التوكن للصفحة التالية
                    var nextToken = findContinuationToken(initialData)

                    // Fallback: إذا لم نجد توكن في HTML، نجرب طلب m.youtube.com خفي (مثل Python)
                    if (nextToken.isNullOrBlank()) {
                        Log.d(name, "Page 1: No token in main HTML, probing m.youtube.com...")
                        try {
                            val mobileHtml = app.get("https://m.youtube.com/results?search_query=$encoded", interceptor = ytInterceptor).text
                            val mobileData = extractYtInitialData(mobileHtml)
                            val mobileToken = findContinuationToken(mobileData)
                            if (!mobileToken.isNullOrBlank()) {
                                nextToken = mobileToken
                                if (savedVisitorData.isNullOrBlank()) savedVisitorData = findConfig(mobileHtml, "VISITOR_DATA")
                                Log.d(name, "Page 1: Found token via probe!")
                            }
                        } catch (e: Exception) { Log.w(name, "Probe failed", e) }
                    }

                    savedContinuationToken = nextToken
                    hasMore = !nextToken.isNullOrBlank()
                }

                Log.d(name, "Page 1 Done: ${results.size} items, hasMore=$hasMore")
                return@coroutineScope newSearchResponseList(results, hasMore)

            } catch (e: Exception) {
                Log.e(name, "Error in search page 1", e)
                return@coroutineScope newSearchResponseList(emptyList(), false)
            }
        }

        // ==================================================
        // PAGE > 1: استخدام التوكن المحفوظ (MWEB API)
        // ==================================================
        val token = savedContinuationToken
        if (token.isNullOrBlank()) {
            Log.w(name, "Search Page $page: STOP. No saved token.")
            return@coroutineScope newSearchResponseList(emptyList(), false)
        }

        try {
            val clientVersion = "2.20251114.01.00"

            // Payload يحاكي طلب الموبايل (MWEB) لأنه الأفضل في التعامل مع التوكنات
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "hl" to "ar",
                        "gl" to "IQ",
                        "clientName" to "MWEB",
                        "clientVersion" to clientVersion,
                        "visitorData" to (savedVisitorData ?: ""),
                        "platform" to "MOBILE",
                        "userAgent" to mobileUserAgent
                    )
                ),
                "continuation" to token
            )

            val headers = mutableMapOf(
                "X-Youtube-Client-Name" to "MWEB",
                "X-Youtube-Client-Version" to clientVersion,
                "Origin" to "https://m.youtube.com",
                "Content-Type" to "application/json",
                "User-Agent" to mobileUserAgent
            )
            if (!savedVisitorData.isNullOrBlank()) {
                headers["X-Goog-Visitor-Id"] = savedVisitorData!!
            }

            // استخدام Endpoint البحث
            val url = "https://m.youtube.com/youtubei/v1/search"
            Log.d(name, "Page $page: Requesting continuation...")

            val response = app.post(url, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()

            if (response != null) {
                // استخراج العناصر (Deep Scan)
                val rawItems = extractItems(response)
                Log.d(name, "Page $page: JSON returned ${rawItems.size} raw items")

                rawItems.forEach { item ->
                    collectFromRenderer(item as? Map<*, *>, seenIds)?.let { results.add(it) }
                }

                // تحديث التوكن للصفحة القادمة
                val nextToken = findContinuationToken(response)
                savedContinuationToken = nextToken
                hasMore = !nextToken.isNullOrBlank()
            }

            Log.d(name, "Page $page Done: ${results.size} items, Next Token Available: $hasMore")
            return@coroutineScope newSearchResponseList(results, hasMore)

        } catch (e: Exception) {
            Log.e(name, "Error fetching search page $page", e)
            return@coroutineScope newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(name, "load called for url=$url")
        val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL")
        Log.d(name, "load: extracted videoId=$videoId")
        val doc = app.get(url, interceptor = ytInterceptor).document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "YouTube Video"
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        Log.d(name, "load: title='${safeTruncate(title,200)}' poster=${poster != null}")
        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot
        }
    }



    // ... كل الكود قبل loadLinks يبقى كما هو ...

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Helper to log large text (بدون قطع)
        fun logLarge(tag: String, text: String) {
            var i = 0
            val max = 4000
            while (i < text.length) {
                val end = minOf(i + max, text.length)
                Log.d(tag, text.substring(i, end))
                i = end
            }
        }

        try {
            Log.d(name, "=== loadLinks START ===")
            Log.d(name, "Input data: $data")

            val videoId = data.extractYoutubeId() ?: run {
                if (data.length == 11 && data.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                    data
                } else {
                    Log.e(name, "loadLinks: could not extract videoId from input: $data")
                    return false
                }
            }
            Log.d(name, "loadLinks: extracted videoId = $videoId")

            val safariHeaders = mapOf(
                "User-Agent" to safariUserAgent,
                "Accept-Language" to "en-US,en;q=0.5"
            )
            val watchUrl = "$mainUrl/watch?v=$videoId&hl=en"
            Log.d(name, "loadLinks: requesting watch page: $watchUrl")
            val watchHtml = app.get(watchUrl, headers = safariHeaders).text
            Log.d(name, "loadLinks: watchHtml length=${watchHtml.length}")

            val ytcfgJsonString = try {
                val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""", RegexOption.DOT_MATCHES_ALL)
                val m = regex.find(watchHtml)
                m?.groupValues?.getOrNull(1)
                    ?: watchHtml.substringAfter("ytcfg.set(", "").substringBefore(");").takeIf { it.trim().startsWith("{") }
            } catch (e: Exception) {
                Log.e(name, "loadLinks: regex error while searching ytcfg", e)
                null
            }

            if (ytcfgJsonString.isNullOrBlank()) {
                Log.e(name, "loadLinks: Failed to find ytcfg.set in watch page HTML")
                return false
            }
            Log.d(name, "loadLinks: ytcfg found")

            val apiKey = findConfig(ytcfgJsonString, "INNERTUBE_API_KEY")
            val clientVersion = findConfig(ytcfgJsonString, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
            val visitorData = findConfig(ytcfgJsonString, "VISITOR_DATA")

            if (apiKey.isNullOrBlank() || visitorData.isNullOrBlank()) {
                Log.e(name, "loadLinks: Missing INNERTUBE_API_KEY or VISITOR_DATA")
                return false
            }

            val clientMap = mapOf(
                "hl" to "en",
                "gl" to "US",
                "clientName" to "WEB",
                "clientVersion" to clientVersion,
                "userAgent" to safariUserAgent,
                "visitorData" to visitorData,
                "platform" to "DESKTOP"
            )
            val finalContext = mapOf("client" to clientMap)
            val payload = mapOf("context" to finalContext, "videoId" to videoId)

            val apiUrl = "$mainUrl/youtubei/v1/player?key=$apiKey"
            Log.d(name, "loadLinks: Posting to player API: $apiUrl")

            val postHeaders = mutableMapOf<String, String>()
            postHeaders.putAll(safariHeaders)
            postHeaders["Content-Type"] = "application/json"
            postHeaders["X-Youtube-Client-Name"] = "WEB"
            postHeaders["X-Youtube-Client-Version"] = clientVersion
            if (!visitorData.isNullOrBlank()) postHeaders["X-Goog-Visitor-Id"] = visitorData

            val responseText = app.post(apiUrl, headers = postHeaders, json = payload).text
            logLarge(name, "PLAYER API Response (first 55k chars):\n${responseText.take(55000)}")

            val playerResponse = try {
                parseJson<PlayerResponse>(responseText)
            } catch (e: Exception) {
                Log.e(name, "loadLinks: Failed to parse playerResponse JSON", e)
                null
            }

            if (playerResponse == null) {
                Log.e(name, "loadLinks: playerResponse null after parsing")
                return false
            }

            val hlsUrl = playerResponse.streamingData?.hlsManifestUrl
            if (!hlsUrl.isNullOrBlank()) {
                Log.d(name, "loadLinks: Found Master HLS Manifest URL: $hlsUrl")

                // إضافة الرابط الرئيسي (التلقائي) أولاً
                callback(
                    newExtractorLink(this.name, "M3U AUTO", hlsUrl) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )

                // محاولة تحميل وتحليل الـ M3U8 الرئيسي لاستخراج الروابط الفردية والترجمات
                try {
                    Log.d(name, "Parsing master M3U8 manifest...")
                    val masterM3u8 = app.get(hlsUrl, referer = mainUrl).text
                    val lines = masterM3u8.lines()

                    // ================== [ تعديل: إضافة الترجمات يدوياً ] ==================
                    Log.d(name, "Searching for subtitles...")
                    lines.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=SUBTITLES") }
                        .forEach { line ->
                            val subUri = parseM3u8Tag(line, "URI")
                            val subName = parseM3u8Tag(line, "NAME")
                            val subLang = parseM3u8Tag(line, "LANGUAGE")

                            if (subUri != null) {
                                val displayName = subName ?: subLang ?: "Subtitle"
                                subtitleCallback(SubtitleFile(displayName, subUri))
                                Log.d(name, "Found subtitle: $displayName -> $subUri")
                            }
                        }
                    // ================== [ نهاية التعديل ] ==================

                    lines.forEachIndexed { index, line ->
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val infoLine = line
                            val urlLine = lines.getOrNull(index + 1)?.takeIf { it.startsWith("http") }
                                ?: return@forEachIndexed

                            val resolution = parseM3u8Tag(infoLine, "RESOLUTION")
                            val resolutionHeight = resolution?.substringAfter("x")?.plus("p") ?: ""

                            val audioId = parseM3u8Tag(infoLine, "YT-EXT-AUDIO-CONTENT-ID")
                            val lang = audioId?.substringBefore('.')?.uppercase()

                            val ytTags = parseM3u8Tag(infoLine, "YT-EXT-XTAGS")
                            val audioType = when {
                                ytTags?.contains("dubbed") == true -> "Dubbed"
                                ytTags?.contains("original") == true -> "Original"
                                else -> null
                            }

                            val nameBuilder = StringBuilder()
                            nameBuilder.append(resolutionHeight)
                            if (lang != null) {
                                nameBuilder.append(" ($lang")
                                if (audioType != null) {
                                    nameBuilder.append(" - $audioType")
                                }
                                nameBuilder.append(")")
                            }

                            val streamName = nameBuilder.toString().trim()

                            if (streamName.isNotBlank()) {
                                callback(
                                    newExtractorLink(this.name, streamName, urlLine) {
                                        this.referer = mainUrl
                                        this.quality = getQualityFromName(resolutionHeight)
                                    }
                                )
                                Log.d(name, "Added stream: $streamName -> $urlLine")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Failed to parse individual streams from master M3U8", e)
                }

                return true
            } else {
                Log.e(name, "loadLinks: HLS Manifest URL not present in playerResponse.")
                return false
            }

        } catch (e: Exception) {
            Log.e(name, "loadLinks top-level exception", e)
            logError(e)
            return false
        }
    }
    private fun parseM3u8Tag(tag: String, key: String): String? {
        // Regex للبحث عن المفتاح وقيمته سواء كانت بين علامتي اقتباس أو لا
        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null } // القيمة داخل الاقتباس
            ?: match?.groupValues?.get(3)?.ifBlank { null } // القيمة بدون اقتباس
    }

    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }
}
