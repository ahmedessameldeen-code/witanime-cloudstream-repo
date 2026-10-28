package com.ahmedessam.witanime

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class WitAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WitAnimeProvider())
    }
}

class WitAnimeProvider : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun docHeaders(referer: String = "https://witanime.site/") = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        )

        fun ajaxHeaders(referer: String = "https://witanime.site/", csrfToken: String? = null): Map<String, String> {
            val map = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
                "Referer" to referer,
                "Origin" to "https://witanime.site",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )
            if (!csrfToken.isNullOrBlank()) {
                map["X-CSRF-TOKEN"] = csrfToken
            }
            return map
        }
    }

    // ============================
    // JSON DATA MODELS
    // ============================

    data class NewEpisodesResponse(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("html") val html: String? = null,
        @JsonProperty("hasOlder") val hasOlder: Boolean? = null
    )

    data class SuggestItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class SuggestResponse(
        @JsonProperty("results") val results: List<SuggestItem>? = null
    )

    data class WatchSourcesResponse(
        @JsonProperty("qualities") val qualities: Map<String, List<QualityServer>>? = null,
        @JsonProperty("downloads") val downloads: Map<String, List<DownloadServer>>? = null
    )

    data class QualityServer(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("token") val token: String? = null
    )

    data class DownloadServer(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class StreamSourceResponse(
        @JsonProperty("mode") val mode: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    // ============================
    // MAIN PAGE SECTIONS (NETFLIX STYLE)
    // ============================

    override val mainPage = mainPageOf(
        "home" to "🔥 الأكثر رواجاً الآن",
        "new-episodes" to "⚡ أحدث الحلقات المضافة",
        "seasonal" to "🌟 أنميات الموسم الحالي",
        "browse|sort=Most+Viewed&status=ongoing" to "📺 الأنميات المستمرة الأكثر مشاهدة",
        "browse|sort=Top+Rated" to "🏆 الأعلى تقييماً على الإطلاق",
        "browse|sort=Most+Viewed" to "💎 الأكثر شعبية ومشاهدة",
        "movies|sort=Most+Viewed" to "🎬 أفلام الأنمي الأكثر مشاهدة",
        "movies|sort=Top+Rated" to "🍿 أفلام الأنمي الأعلى تقييماً",
        "browse|genres%5B0%5D=action&sort=Most+Viewed" to "⚔️ أنميات أكشن وإثارة",
        "browse|genres%5B0%5D=fantasy&sort=Most+Viewed" to "🐉 أنميات خيال وسحر",
        "browse|genres%5B0%5D=adventure&sort=Most+Viewed" to "🧭 أنميات مغامرات وتشويق",
        "browse|genres%5B0%5D=isekai&sort=Most+Viewed" to "🌌 أنميات إيسيكاي وعوالم موازية",
        "browse|genres%5B0%5D=shounen&sort=Most+Viewed" to "🔥 أنميات شونين قتالية",
        "browse|genres%5B0%5D=seinen&sort=Top+Rated" to "🗡️ أنميات سينين ناضجة",
        "browse|genres%5B0%5D=mystery&sort=Top+Rated" to "🕵️ أنميات غموض وتحقيق",
        "browse|genres%5B0%5D=psychological&sort=Top+Rated" to "🧠 أنميات نفسية وتشويق",
        "browse|genres%5B0%5D=romance&sort=Most+Viewed" to "💖 أنميات رومانسية ودراما",
        "browse|genres%5B0%5D=comedy&sort=Most+Viewed" to "😂 أنميات كوميديا خفيفة",
        "browse|genres%5B0%5D=sci-fi&sort=Top+Rated" to "🚀 أنميات خيال علمي",
        "browse|genres%5B0%5D=sports&sort=Top+Rated" to "⚽ أنميات رياضية وحماسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        // 1. Homepage Hero + Featured Rows (Page 1 only)
        if (data == "home") {
            if (page > 1) return newHomePageResponse(emptyList(), false)
            val doc = app.get(mainUrl, headers = docHeaders()).document
            val lists = mutableListOf<HomePageList>()

            // Hero Spotlight Slides
            val heroItems = doc.select("[data-hero-slide]").mapNotNull { slide ->
                val linkEl = slide.selectFirst("h2 a[href]") ?: slide.selectFirst("a[href*='/anime/'], a[href*='/movie/']")
                val href = linkEl?.attr("href")?.let { normalizeContentUrl(fixUrl(it)) } ?: return@mapNotNull null
                val title = linkEl.text().trim().ifEmpty { return@mapNotNull null }
                val backdrop = slide.selectFirst("img[src*='images.witanime.site']")?.attr("src")
                val scoreText = slide.selectFirst("span:has(svg.ic)")?.text()?.trim()
                val tvType = inferTvType(href, slide.text())

                newAnimeSearchResponse(title, href, tvType) {
                    this.posterUrl = backdrop
                    this.score = Score.from10(scoreText)
                    addDubStatus(dubExist = false, subExist = true)
                }
            }.distinctBy { it.url }

            if (heroItems.isNotEmpty()) {
                lists.add(HomePageList(request.name, heroItems, isHorizontalImages = true))
            }

            return newHomePageResponse(lists, hasNext = false)
        }

        // 2. Paginated Latest Episodes via JSON endpoint (/new-episodes?page=N)
        if (data == "new-episodes") {
            val url = "$mainUrl/new-episodes?page=$page"
            val jsonText = app.get(url, headers = ajaxHeaders("$mainUrl/")).text
            val parsed = tryParseJson<NewEpisodesResponse>(jsonText)
            val fragmentDoc = Jsoup.parseBodyFragment(parsed?.html ?: "")
            val items = parseCardsFromContainer(fragmentDoc)
            val hasNext = (parsed?.hasOlder == true) && items.isNotEmpty()
            return newHomePageResponse(
                listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                hasNext = hasNext
            )
        }

        // 3. Seasonal Anime Page (/seasonal)
        if (data == "seasonal") {
            if (page > 1) return newHomePageResponse(emptyList(), false)
            val doc = app.get("$mainUrl/seasonal", headers = docHeaders()).document
            val items = parseCardsFromContainer(doc)
            return newHomePageResponse(
                listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                hasNext = false
            )
        }

        // 4. Paginated Browse & Movies Directories (/browse/page/N?... or /movies/page/N?...)
        if (data.startsWith("browse|") || data.startsWith("movies|")) {
            val parts = data.split("|", limit = 2)
            val baseRoute = parts[0] // "browse" or "movies"
            val query = parts.getOrNull(1) ?: ""
            val path = if (page <= 1) "/$baseRoute" else "/$baseRoute/page/$page"
            val fullUrl = if (query.isNotEmpty()) "$mainUrl$path?$query" else "$mainUrl$path"

            val doc = app.get(fullUrl, headers = docHeaders()).document
            val items = parseCardsFromContainer(doc)
            val hasNext = doc.selectFirst("a[href*='/$baseRoute/page/${page + 1}']") != null && items.isNotEmpty()

            return newHomePageResponse(
                listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                hasNext = hasNext
            )
        }

        return newHomePageResponse(emptyList(), false)
    }

    // ============================
    // CARD PARSING & URL NORMALIZATION
    // ============================

    private fun parseCardsFromContainer(container: Element): List<SearchResponse> {
        return container.select("a[href*='/anime/'], a[href*='/movie/'], a[href*='/watch/']")
            .mapNotNull { parseCardElement(it) }
            .distinctBy { it.url }
    }

    private fun parseCardElement(anchor: Element): SearchResponse? {
        val rawHref = anchor.attr("href").trim().ifEmpty { return null }
        val fullHref = fixUrl(rawHref)

        // Skip non-card navigation links (e.g. buttons without poster images or titles)
        val imgEl = anchor.selectFirst("img[src]") ?: return null
        val poster = imgEl.attr("src").trim().takeIf { it.isNotEmpty() }?.let { fixUrl(it) }

        val title = anchor.selectFirst("h3")?.text()?.trim()
            ?.ifEmpty { null }
            ?: imgEl.attr("alt").trim().ifEmpty { null }
            ?: return null

        // Extract Episode number if this is a /watch/{slug}/{ep} card
        val epFromUrl = Regex("""/watch/(?:movie/)?[^/?#]+/(\d+)""").find(fullHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val epFromBadge = anchor.selectFirst("div:containsOwn(الحلقة), span:containsOwn(الحلقة)")
            ?.text()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val episodeNum = epFromBadge ?: epFromUrl

        // Normalize /watch/{slug}/{ep} -> /anime/{slug} so opening a card loads the full anime details
        // without consuming /watch/ rate-limit quota!
        val normalizedUrl = normalizeContentUrl(fullHref)

        // Extract score badge (e.g. 8.9)
        val scoreText = anchor.selectFirst("span:has(svg.ic)")?.text()?.trim()
            ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }

        val badgeText = anchor.text()
        val tvType = inferTvType(normalizedUrl, badgeText)

        return newAnimeSearchResponse(title, normalizedUrl, tvType) {
            this.posterUrl = poster
            this.score = Score.from10(scoreText)
            addDubStatus(dubExist = false, subExist = true, subEpisodes = episodeNum)
        }
    }

    private fun normalizeContentUrl(url: String): String {
        val clean = url.substringBefore("#").substringBefore("?").trimEnd('/')
        Regex("""^(https?://[^/]+)/watch/movie/([^/?#]+)$""").find(clean)?.let { m ->
            return "${m.groupValues[1]}/movie/${m.groupValues[2]}"
        }
        Regex("""^(https?://[^/]+)/watch/([^/?#]+)/\d+$""").find(clean)?.let { m ->
            return "${m.groupValues[1]}/anime/${m.groupValues[2]}"
        }
        return clean
    }

    private fun inferTvType(url: String, typeHint: String? = null): TvType {
        if (url.contains("/movie/")) return TvType.AnimeMovie
        val hint = typeHint?.uppercase() ?: ""
        return when {
            hint.contains("MOVIE") || hint.contains("فيلم") -> TvType.AnimeMovie
            hint.contains("OVA") || hint.contains("ONA") || hint.contains("SPECIAL") || hint.contains("خاصة") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    // ============================
    // SEARCH & QUICK SEARCH
    // ============================

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search/suggest?q=$encoded"
        val jsonText = app.get(url, headers = ajaxHeaders("$mainUrl/")).text
        val parsed = tryParseJson<SuggestResponse>(jsonText)
        return parsed?.results?.mapNotNull { item ->
            val title = item.title?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val rawUrl = item.url?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val fullUrl = normalizeContentUrl(fixUrl(rawUrl))
            val tvType = inferTvType(fullUrl, item.type)
            newAnimeSearchResponse(title, fullUrl, tvType) {
                this.posterUrl = item.poster?.let { fixUrl(it) }
                this.score = Score.from10(item.rating)
                this.year = item.year?.toIntOrNull()
                addDubStatus(dubExist = false, subExist = true)
            }
        } ?: emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = app.get(searchUrl, headers = docHeaders("$mainUrl/")).document
        val results = parseCardsFromContainer(doc)
        if (results.isNotEmpty()) return results
        return quickSearch(query)
    }

    // ============================
    // LOAD ANIME / MOVIE DETAILS
    // ============================

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = normalizeContentUrl(fixUrl(url))
        val doc = app.get(targetUrl, headers = docHeaders("$mainUrl/")).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.ifEmpty { null }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.substringBefore(" - ")?.trim()
            ?: return null

        val altTitle = doc.selectFirst("h1 + p")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        // High-res portrait poster & widescreen hero banner
        val poster = doc.selectFirst("img.rounded-xl.shadow-2xl[src]")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

        val banner = doc.selectFirst("section.relative.pt-16 div.absolute.inset-0 img[src]")?.attr("src")?.let { fixUrl(it) }
            ?: poster

        // Synopsis / Story
        val plot = doc.selectFirst("p.leading-relaxed")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // Badges: Type, Status, Score
        val typeBadge = doc.selectFirst("span.bg-white.text-black")?.text()?.trim()
        val statusBadge = doc.selectFirst("span.bg-neutral-800")?.text()?.trim()
        val scoreText = doc.selectFirst("span[dir='ltr']:has(svg.ic)")?.text()?.trim()

        val showStatus = when {
            statusBadge?.contains("مكتمل") == true -> ShowStatus.Completed
            statusBadge?.contains("مستمر") == true || statusBadge?.contains("يعرض") == true -> ShowStatus.Ongoing
            else -> null
        }

        // Metadata Grid (Year, Duration, Age Rating, Studio, Season)
        var year: Int? = null
        var durationMinutes: Int? = null
        val extraInfo = mutableListOf<String>()

        doc.select("div.grid.grid-cols-2 > div").forEach { box ->
            val label = box.selectFirst("span")?.text()?.trim() ?: return@forEach
            val value = box.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            when {
                label.contains("السنة") || label.contains("بداية العرض") -> {
                    year = Regex("""(\d{4})""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                label.contains("المدة") -> {
                    durationMinutes = Regex("""(\d+)""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                label.contains("الموسم") || label.contains("الاستوديو") || label.contains("التصنيف العمري") || label.contains("المصدر") -> {
                    extraInfo.add("$label: $value")
                }
            }
        }

        // Genres / Tags
        val tags = doc.select("div.flex.flex-wrap.gap-2 span.rounded-full")
            .mapNotNull { it.text().trim().ifEmpty { null } }
            .distinct()

        // YouTube Trailer
        val trailerUrl = doc.selectFirst("iframe[src*='youtube.com/embed/'], iframe[src*='youtu.be/']")
            ?.attr("src")
            ?.let { src ->
                val videoId = Regex("""/embed/([^/?&#]+)""").find(src)?.groupValues?.getOrNull(1)
                if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else null
            }

        // Recommendations ("أنميات ذات صلة" / "قد يعجبك أيضاً")
        val recommendations = doc.select("section:has(h2) a[href*='/anime/'], section:has(h2) a[href*='/movie/']")
            .mapNotNull { parseCardElement(it) }
            .filter { it.url != targetUrl }
            .distinctBy { it.url }

        val fullPlot = buildString {
            if (!altTitle.isNullOrBlank() && !altTitle.equals(title, ignoreCase = true)) {
                append("الاسم الإنجليزي: $altTitle\n\n")
            }
            if (!plot.isNullOrBlank()) {
                append(plot)
            }
            if (extraInfo.isNotEmpty()) {
                append("\n\n")
                append(extraInfo.joinToString(" • "))
            }
        }.trim().ifEmpty { null }

        val tvType = inferTvType(targetUrl, typeBadge)

        // Episodes extraction
        val episodeAnchors = doc.select("[data-episode-list] a[href*='/watch/']")
        val episodes = episodeAnchors.mapNotNull { epEl ->
            val epHref = epEl.attr("href").trim().ifEmpty { return@mapNotNull null }
            val fullEpUrl = fixUrl(epHref)
            val epNum = epEl.attr("data-episode-number").trim().toIntOrNull()
                ?: epEl.selectFirst("span[dir='ltr']")?.text()?.trim()?.toIntOrNull()
                ?: Regex("""/watch/[^/?#]+/(\d+)""").find(fullEpUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val epThumb = epEl.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) } ?: banner
            val epTitle = if (epNum != null) "الحلقة $epNum" else (epEl.text().trim().ifEmpty { "الحلقة" })

            newEpisode(fullEpUrl) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = epThumb
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        // If it's a Movie page (/movie/{slug}) or has a direct movie watch button
        if (tvType == TvType.AnimeMovie && episodes.isEmpty()) {
            val movieWatchUrl = doc.selectFirst("a[href*='/watch/movie/'], a[href*='/watch/']")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: targetUrl.replace("/movie/", "/watch/movie/")

            return newMovieLoadResponse(title, targetUrl, TvType.AnimeMovie, movieWatchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = fullPlot
                this.year = year
                this.duration = durationMinutes
                this.tags = tags
                this.score = Score.from10(scoreText)
                this.recommendations = recommendations
                addTrailer(trailerUrl)
            }
        }

        // Standard Series / OVA / Movie with episodes
        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes
        } else {
            val fallbackWatch = doc.selectFirst("a[href*='/watch/']")?.attr("href")?.let { fixUrl(it) }
            if (fallbackWatch != null) {
                listOf(
                    newEpisode(fallbackWatch) {
                        this.name = "مشاهدة"
                        this.episode = 1
                        this.posterUrl = banner
                    }
                )
            } else {
                emptyList()
            }
        }

        return newAnimeLoadResponse(title, targetUrl, tvType) {
            this.engName = altTitle
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = fullPlot
            this.year = year
            this.duration = durationMinutes
            this.showStatus = showStatus
            this.tags = tags
            this.score = Score.from10(scoreText)
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, finalEpisodes)
            addTrailer(trailerUrl)
        }
    }

    // ============================
    // STREAM & DOWNLOAD EXTRACTION
    // ============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = fixUrl(data)

        // Step 1: Fetch the watch page and capture session cookies + CSRF token
        val watchResp = app.get(watchUrl, headers = docHeaders("$mainUrl/"))
        val sessionCookies = watchResp.cookies.toMutableMap()
        val doc = watchResp.document

        val csrfToken = doc.selectFirst("meta[name='csrf-token']")?.attr("content")?.trim()
            ?: return false

        val rawSourcesUrl = Regex("""sourcesUrl:\s*'([^']+)'""")
            .find(watchResp.text)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it) }
            ?: "${watchUrl.trimEnd('/')}/sources"

        // Step 2: POST to /watch/.../sources with session cookies and X-CSRF-TOKEN
        val sourcesResp = app.post(
            rawSourcesUrl,
            headers = ajaxHeaders(watchUrl, csrfToken),
            cookies = sessionCookies
        )
        if (sourcesResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(sourcesResp.cookies)
        }

        val sourcesData = tryParseJson<WatchSourcesResponse>(sourcesResp.text) ?: return false

        // Step 3: Prioritize servers carefully to respect witanime.site's 20 req/min rate limit on /watch/*
        // Note: 1 single "yonaplay" token unlocks the entire multi-server, multi-quality hub on mid.yonaplay.net
        // without consuming any additional witanime.site rate-limit requests!
        val allServers = mutableListOf<Pair<String, QualityServer>>()
        val qualityPriority = listOf("4k", "fhd", "hd", "sd")
        for (qKey in qualityPriority) {
            sourcesData.qualities?.get(qKey)?.forEach { srv ->
                if (!srv.token.isNullOrBlank()) {
                    allServers.add(qKey to srv)
                }
            }
        }
        // Also include any other quality keys not in qualityPriority
        sourcesData.qualities?.forEach { (qKey, list) ->
            if (qKey !in qualityPriority) {
                list.forEach { srv ->
                    if (!srv.token.isNullOrBlank()) {
                        allServers.add(qKey to srv)
                    }
                }
            }
        }

        val prioritizedTokens = mutableListOf<Pair<String, QualityServer>>()

        // 3a. Pick ONLY ONE yonaplay token (prefer FHD) because mid.yonaplay.net already contains all qualities/servers
        val yonaCandidate = allServers.firstOrNull { (_, s) ->
            s.name?.contains("yonaplay", ignoreCase = true) == true
        }
        if (yonaCandidate != null) {
            prioritizedTokens.add(yonaCandidate)
        }

        // 3b. Pick up to 4 top reliable direct/embed servers (prefer highest quality per server name)
        val serverOrder = listOf("mp4upload", "ok", "videa", "dailymotion", "hgcloud", "videas", "dood", "streamwish", "vidbom")
        for (srvKey in serverOrder) {
            if (prioritizedTokens.size >= 5) break
            val match = allServers.firstOrNull { (_, s) ->
                val n = s.name?.lowercase() ?: ""
                n.contains(srvKey) && prioritizedTokens.none { it.second.token == s.token }
            }
            if (match != null) {
                prioritizedTokens.add(match)
            }
        }

        // 3c. Fill remaining slots up to 5 tokens if needed
        for (entry in allServers) {
            if (prioritizedTokens.size >= 5) break
            val s = entry.second
            if (s.name?.contains("yonaplay", ignoreCase = true) == true) continue
            if (prioritizedTokens.none { it.second.token == s.token }) {
                prioritizedTokens.add(entry)
            }
        }

        // Step 4: Resolve selected tokens sequentially (keeping session cookies updated)
        for ((qKey, srv) in prioritizedTokens) {
            val token = srv.token ?: continue
            val serverLabel = "${srv.name ?: "Server"} [${qKey.uppercase()}]"
            runCatching {
                val resolvedUrl = resolveWatchToken(token, watchUrl, csrfToken, sessionCookies)
                if (!resolvedUrl.isNullOrBlank()) {
                    dispatchExtractorUrl(resolvedUrl, watchUrl, serverLabel, subtitleCallback, callback)
                }
            }
        }

        // Step 5: Also check direct download links for fast extractable hosts (e.g. mp4upload)
        sourcesData.downloads?.forEach { (qKey, dlList) ->
            dlList.forEach { dl ->
                val dlUrl = dl.url?.trim().orEmpty()
                if (dlUrl.contains("mp4upload.com", ignoreCase = true)) {
                    runCatching {
                        loadExtractor(dlUrl, watchUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun resolveWatchToken(
        token: String,
        watchUrl: String,
        csrfToken: String,
        sessionCookies: MutableMap<String, String>
    ): String? {
        val sourceApiUrl = "$mainUrl/watch/stream-source/$token"
        val srcResp = app.post(
            sourceApiUrl,
            headers = ajaxHeaders(watchUrl, csrfToken),
            cookies = sessionCookies
        )
        if (srcResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(srcResp.cookies)
        }

        val parsed = tryParseJson<StreamSourceResponse>(srcResp.text) ?: return null
        val gateOrDirectUrl = parsed.url?.trim()?.ifEmpty { null } ?: return null

        if (!gateOrDirectUrl.contains("/watch/stream-gate/")) {
            return gateOrDirectUrl
        }

        // Follow stream-gate with allowRedirects = false to capture the Location header
        val gateHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to watchUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Upgrade-Insecure-Requests" to "1"
        )
        val gateResp = app.get(
            fixUrl(gateOrDirectUrl),
            headers = gateHeaders,
            cookies = sessionCookies,
            allowRedirects = false
        )
        if (gateResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(gateResp.cookies)
        }

        val location = gateResp.headers["location"]
            ?: gateResp.headers["Location"]
            ?: Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""").find(gateResp.text)?.groupValues?.getOrNull(1)

        return location?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun dispatchExtractorUrl(
        rawUrl: String,
        referer: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> rawUrl.trim()
        }

        when {
            cleanUrl.contains("mid.yonaplay.net", ignoreCase = true) ||
                cleanUrl.contains("yonaplay.net", ignoreCase = true) ||
                cleanUrl.contains("yonaplay.org", ignoreCase = true) -> {
                extractMidYonaPlay(cleanUrl, referer, subtitleCallback, callback)
            }
            cleanUrl.contains("dotplay.net", ignoreCase = true) -> {
                extractDotPlay(cleanUrl, referer, serverLabel, callback)
            }
            cleanUrl.endsWith(".mp4", ignoreCase = true) || cleanUrl.contains(".mp4?", ignoreCase = true) -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $serverLabel",
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromLabel(serverLabel)
                    }
                )
            }
            cleanUrl.contains(".m3u8", ignoreCase = true) -> {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = cleanUrl,
                    referer = referer,
                    name = "$name - $serverLabel"
                ).forEach(callback)
            }
            else -> {
                loadExtractor(cleanUrl, referer, subtitleCallback, callback)
            }
        }
    }

    // ============================
    // MID.YONAPLAY.NET AES-256-GCM DECRYPTOR
    // ============================

    data class YonaEncryptedResponse(
        @JsonProperty("encrypted_data") val encryptedData: String? = null
    )

    data class YonaDecryptedPayload(
        @JsonProperty("StreamingServers") val streamingServers: List<YonaServerItem>? = null,
        @JsonProperty("DownloadServers") val downloadServers: List<YonaServerItem>? = null
    )

    data class YonaServerItem(
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    private suspend fun extractMidYonaPlay(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""[?&]id=([^&#]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return
        val hostBase = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: "https://mid.yonaplay.net"

        // Fetch embed page once to get session cookie if any
        val embedHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val embedResp = app.get(embedUrl, headers = embedHeaders)
        val yonaCookies = embedResp.cookies

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to hostBase,
            "Referer" to embedUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        val apiResp = app.post(
            "$hostBase/api/api.php",
            headers = apiHeaders,
            cookies = yonaCookies,
            json = mapOf("id" to id)
        )

        val encWrapper = tryParseJson<YonaEncryptedResponse>(apiResp.text) ?: return
        val encryptedBase64 = encWrapper.encryptedData?.takeIf { it.isNotBlank() } ?: return

        val decryptedJson = decryptYonaPlayPayload(encryptedBase64, id) ?: return
        val payload = tryParseJson<YonaDecryptedPayload>(decryptedJson) ?: return

        val servers = payload.streamingServers.orEmpty()

        // Prioritize dotplay.net first (direct archive.org MP4 streams!) + reliable embeds
        val sortedServers = servers.sortedBy { srv ->
            val u = srv.url?.lowercase().orEmpty()
            when {
                u.contains("dotplay.net") -> 0
                u.contains("mp4upload.com") -> 1
                u.contains("ok.ru") -> 2
                u.contains("dailymotion.com") -> 3
                u.contains("videa.hu") -> 4
                else -> 5
            }
        }

        for (srv in sortedServers) {
            val srvUrl = srv.url?.trim()?.ifEmpty { null } ?: continue
            val qLabel = srv.quality?.uppercase() ?: "HD"
            val label = "YonaPlay (${srv.serverName ?: "Server"} - $qLabel)"
            runCatching {
                if (srvUrl.contains("dotplay.net", ignoreCase = true)) {
                    extractDotPlay(srvUrl, embedUrl, label, callback, srv.quality)
                } else {
                    loadExtractor(srvUrl, embedUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private fun decryptYonaPlayPayload(encryptedBase64: String, id: String): String? {
        return runCatching {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            if (raw.size <= 28) return null

            val salt = raw.copyOfRange(0, 16)
            val iv = raw.copyOfRange(16, 28)
            val ciphertextAndTag = raw.copyOfRange(28, raw.size)

            val passphrase = "$id|9c8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f"
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(ciphertextAndTag)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    // ============================
    // DOTPLAY.NET DIRECT MP4 EXTRACTOR
    // ============================

    data class DotPlayResponse(
        @JsonProperty("file") val file: String? = null
    )

    private suspend fun extractDotPlay(
        dotPlayUrl: String,
        referer: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit,
        qualityHint: String? = null
    ) {
        val id = Regex("""/player\d*/([^/?&#]+)""").find(dotPlayUrl)?.groupValues?.getOrNull(1) ?: return
        val hostBase = Regex("""^(https?://[^/]+)""").find(dotPlayUrl)?.groupValues?.getOrNull(1)
            ?: "https://dotplay.net"

        // Visit player page first to establish session cookie
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val pageResp = app.get(dotPlayUrl, headers = pageHeaders)

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Origin" to hostBase,
            "Referer" to dotPlayUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        val apiResp = app.post(
            "$hostBase/api.php",
            headers = apiHeaders,
            cookies = pageResp.cookies,
            data = mapOf("id" to id)
        )

        val parsed = tryParseJson<DotPlayResponse>(apiResp.text) ?: return
        val encodedFile = parsed.file?.trim()?.ifEmpty { null } ?: return

        val decodedUrl = runCatching {
            String(Base64.decode(encodedFile, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return

        val qualityValue = getQualityFromLabel(qualityHint ?: serverLabel)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name - $serverLabel",
                url = decodedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$hostBase/"
                this.quality = qualityValue
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    private fun getQualityFromLabel(label: String): Int {
        val upper = label.uppercase()
        return when {
            upper.contains("4K") || upper.contains("2160") -> Qualities.P2160.value
            upper.contains("FHD") || upper.contains("1080") -> Qualities.P1080.value
            upper.contains("HD") || upper.contains("720") -> Qualities.P720.value
            upper.contains("SD") || upper.contains("480") -> Qualities.P480.value
            upper.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
