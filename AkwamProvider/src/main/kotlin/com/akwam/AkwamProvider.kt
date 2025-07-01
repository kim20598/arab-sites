package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class Akwam : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val usesWebView = true // Enable WebView for CAPTCHA
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)
    override val requestInterval = 5000L // Slow down requests

    // Add proper headers to appear more like a browser
    override fun getHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "Referer" to mainUrl
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("a.box").attr("href") ?: return null
        if (url.contains("/games/") || url.contains("/programs/")) return null
        val poster = select("picture > img")
        val title = poster.attr("alt")
        val posterUrl = poster.attr("data-src")
        val year = select(".badge-secondary").text().toIntOrNull()

        return MovieSearchResponse(
            title,
            url,
            this@Akwam.name,
            TvType.TvSeries,
            posterUrl,
            year,
            null,
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/series?page=" to "Series",
        "$mainUrl/shows?page=" to "Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = try {
            app.get(request.data + page).document
        } catch (e: Exception) {
            throw ErrorException("CAPTCHA detected - Enable WebView in settings")
        }
        val list = doc.select("div.col-lg-auto.col-md-4.col-6.mb-12").mapNotNull { element ->
            element.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            throw ErrorException("CAPTCHA detected - Enable WebView in settings")
        }
        return doc.select("div.col-lg-auto").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toEpisode(): Episode {
        val a = select("a.text-white")
        val url = a.attr("href")
        val title = a.text()
        val thumbUrl = select("picture > img").attr("src")
        val date = select("p.entry-date").text()
        return newEpisode(url) {
            name = title
            episode = title.getIntFromText()
            posterUrl = thumbUrl
            addDate(date)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            throw ErrorException("CAPTCHA detected - Enable WebView in settings")
        }

        // Rest of your load() implementation...
        // Keep all your existing load() code here
        // ...
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = app.get(
                data,
                interceptor = if (usesWebView) WebViewInterceptor() else null,
                timeout = 30
            )
            val doc = response.document

            // Check for CAPTCHA
            if (doc.select("input[name='recaptcha-token'], div.g-recaptcha").isNotEmpty() ||
                doc.text().contains("I'm not a robot") ||
                doc.text().contains("لست برنامج روبروت")) {
                throw ErrorException("CAPTCHA detected - Solve in WebView")
            }

            // Modern link extraction (updated for ak.sv)
            doc.select("div.download-option, div.server-option").forEach { option ->
                try {
                    val quality = option.attr("data-quality").let {
                        when {
                            it.contains("1080") -> Qualities.P1080
                            it.contains("720") -> Qualities.P720
                            it.contains("480") -> Qualities.P480
                            else -> option.text().let { text ->
                                when {
                                    text.contains("1080") -> Qualities.P1080
                                    text.contains("720") -> Qualities.P720
                                    text.contains("480") -> Qualities.P480
                                    else -> Qualities.Unknown
                                }
                            }
                        }
                    }

                    option.select("a[href], button[data-url]").forEach { element ->
                        val url = when {
                            element.hasAttr("data-url") -> element.attr("data-url")
                            element.hasAttr("href") -> element.attr("href")
                            else -> null
                        }?.let { fixUrl(it) }

                        url?.let {
                            callback(ExtractorLink(
                                name,
                                "$name ${quality.name}",
                                it,
                                mainUrl,
                                quality.value,
                                quality = quality
                            ))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Extract subtitles if available
            doc.select("track[kind=subtitles]").forEach { sub ->
                try {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            sub.attr("srclang") ?: "ar",
                            fixUrl(sub.attr("src") ?: "")
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return doc.select("a[href*='download'], button[data-url]").isNotEmpty()
        } catch (e: Exception) {
            when {
                e.message?.contains("CAPTCHA") == true -> throw ErrorException("Solve CAPTCHA in WebView")
                else -> {
                    e.printStackTrace()
                    return false
                }
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }.replace("akwam.to", "ak.sv")
    }
}