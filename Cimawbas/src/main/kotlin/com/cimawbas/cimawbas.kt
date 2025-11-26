package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class LodyNet : MainAPI() {
    override var mainUrl = "https://lodynet.watch"
    override var name = "LodyNet"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val searchApi = "$mainUrl/wp-content/themes/Lodynet2020/Api/RequestSearch.php"
    private val embedApi = "$mainUrl/wp-content/themes/Lodynet2020/Api/RequestServerEmbed.php"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Newly Added Sections
        doc.select(".IndexNewlyField").forEach { container ->
            val title = container.select(".IndexFieldTitle a").text().trim()
            val movies = container.select(".ItemNewlyField").mapNotNull { item ->
                val aTag = item.selectFirst("a") ?: return@mapNotNull null
                val name = item.select(".NewlyTitle").text().trim()
                val link = aTag.attr("href")
                val poster = item.select(".NewlyCover").attr("data-src")
                    .ifEmpty { item.select(".NewlyCover").attr("style").substringAfter("url(\"").substringBefore("\")") }
                
                newMovieSearchResponse(name, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        // Pinned Section
        val pinnedMovies = doc.select("#IndexPinned .ItemPinnedField").mapNotNull { item ->
            val aTag = item.selectFirst("a") ?: return@mapNotNull null
            val name = item.select(".NewlyTitle").text().trim()
            val link = aTag.attr("href")
            val poster = item.select(".NewlyCover").attr("data-src")
            
            newMovieSearchResponse(name, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        
        if(pinnedMovies.isNotEmpty()) {
             homePageList.add(0, HomePageList("مثبتات", pinnedMovies))
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$searchApi?value=$query"
        val response = app.get(url).text

        return try {
            val jsonList = tryParseJson<List<Any>>(response)
            if (jsonList == null || jsonList.size < 2) return emptyList()

            val rawResults = jsonList[1]
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonString = mapper.writeValueAsString(rawResults)
            val results = parseJson<List<SearchResultJson>>(jsonString)

            results.amap { item ->
                val fullLink = if(item.url.startsWith("http")) item.url else "$mainUrl/${item.url}"
                val doc = app.get(fullLink).document
                
                var realPoster = doc.select("#CoverSingle").attr("data-src")
                    .ifEmpty { doc.select("#CoverSingle").attr("style").substringAfter("url(\"").substringBefore("\")") }
                
                if (realPoster.isEmpty() || realPoster.contains("url")) {
                    realPoster = doc.select(".ItemNewly .NewlyCover").firstOrNull()?.attr("data-src") 
                        ?: doc.select(".ItemNewly .NewlyCover").firstOrNull()?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")") 
                        ?: item.cover 
                        ?: ""
                }

                newMovieSearchResponse(item.title ?: "", fullLink, TvType.TvSeries) {
                    this.posterUrl = realPoster
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.select("h1#PrimaryTitle").text().trim()
        val description = doc.select("#ContentDetails p").text().trim()
        
        // Poster logic
        var poster = doc.select("#CoverSingle").attr("data-src")
            .ifEmpty { doc.select("#CoverSingle").attr("style").substringAfter("url(\"").substringBefore("\")") }

        if (poster.isEmpty() || poster.contains("url")) {
             poster = doc.select(".ItemNewly .NewlyCover").firstOrNull()?.attr("data-src") 
                ?: doc.select(".ItemNewly .NewlyCover").firstOrNull()?.attr("style")?.substringAfter("url(\"")?.substringBefore("\")") 
                ?: ""
        }

        // FIX 1: Duplicate Tags
        // We select ribbons, trim text, and use .distinct() to remove duplicates
        val ribbonTags = doc.select(".NewlyRibbon").map { it.text().trim() }
        val categoryTags = doc.select("#ListCategories li a").map { it.text().trim() }
        val tags = (categoryTags + ribbonTags).distinct()
                   
        val year = doc.select("#DateDetails").attr("content").take(4).toIntOrNull()

        val episodes = ArrayList<Episode>()

        // Strategy 1: Slider (Inside Episode Page)
        val sliderElements = doc.select("#ListEpisodes .ItemEpisode, #ListEpisodes .CurrentEpisode")
        if (sliderElements.isNotEmpty()) {
            sliderElements.forEach { element ->
                val epLink = element.attr("href")
                val epName = element.text().trim()
                val epNum = element.attr("id").replace("Ep", "").toIntOrNull() 
                    ?: Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                
                episodes.add(newEpisode(epLink) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        } else {
            // Strategy 2: Category Grid (Inside Series/Season Page) - FIX EPISODE NUMBERING
            val gridElements = doc.select("#AreaNewly .ItemNewly a")
            gridElements.forEach { element ->
                val epLink = element.attr("href")
                val titleText = element.select(".NewlyTitle").text().trim()
                
                // FIX 2: Correctly extract episode number from the dedicated div .NewlyEpNumber only
                val numDivText = element.select(".NewlyEpNumber").text().trim() // "حلقة رقم 14"
                val epNum = Regex("(\\d+)").find(numDivText)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epLink) {
                    this.name = titleText
                    this.episode = epNum
                    this.posterUrl = poster // Series poster for all episodes
                })
            }
            // Category pages show newest first, reverse for correct order
            episodes.reverse()
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = doc.select("#RelatedNewly .ItemNewly").mapNotNull { 
                     val recName = it.select(".NewlyTitle").text()
                     val recLink = it.select("a").attr("href")
                     val recPoster = it.select(".NewlyCover").attr("data-src")
                     newTvSeriesSearchResponse(recName, recLink, TvType.TvSeries) { this.posterUrl = recPoster }
                }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = doc.select("#RelatedNewly .ItemNewly").mapNotNull { 
                     val recName = it.select(".NewlyTitle").text()
                     val recLink = it.select("a").attr("href")
                     val recPoster = it.select(".NewlyCover").attr("data-src")
                     newMovieSearchResponse(recName, recLink, TvType.Movie) { this.posterUrl = recPoster }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        val scriptContent = doc.select("script").html()
        val postId = Regex("""SeoData\.Id\s*=\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: return false

        val serverButtons = doc.select("#AllServerWatch button")
        
        serverButtons.forEach { btn ->
            val onClick = btn.attr("onclick")
            val serverId = Regex("""SwitchServer\(this,\s*(\d+)\)""").find(onClick)?.groupValues?.get(1)

            if (serverId != null) {
                try {
                    val formBody = mapOf(
                        "PostID" to postId,
                        "ServerID" to serverId
                    )
                    
                    val apiResponse = app.post(
                        embedApi,
                        data = formBody,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text
                    
                    var embedUrl = apiResponse.trim().replace("\"", "").replace("\\/", "/")
                    if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"

                    if (embedUrl.startsWith("http")) {
                        if (embedUrl.contains("lodynet.watch/embed")) {
                             val embedDoc = app.get(embedUrl).document
                             val realSource = embedDoc.select("iframe").attr("src")
                             if(realSource.isNotEmpty()) {
                                 loadExtractor(realSource, data, subtitleCallback, callback)
                             }
                        } else {
                            loadExtractor(embedUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // pass
                }
            }
        }
        return true
    }

    data class SearchResultJson(
        @JsonProperty("Title") val title: String?,
        @JsonProperty("Url") val url: String,
        @JsonProperty("Category") val category: String?,
        @JsonProperty("Cover") val cover: String?
    )
}
