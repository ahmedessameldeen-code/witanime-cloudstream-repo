package com.site2cs.yam.engine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * site2cs engine - shared by every generated provider.
 *
 * The provider class only supplies a SiteSpec (selectors + urls). Everything
 * below is generic: it uses the configured selectors first and falls back to
 * JSON-LD / OpenGraph / heuristics when a selector is missing or stops working.
 */

enum class Archetype { GENERIC, MADARA, JSONLD }

data class HomeTab(
    val name: String,
    val path: String,
    val horizontal: Boolean = false,
)

data class SearchCfg(
    val path: String = "?s={query}",
    val method: String = "GET",
    val body: String = "",
)

data class Selectors(
    val cards: String = "",
    val cardLink: String = "",
    val cardTitle: String = "",
    val cardPoster: String = "",
    val nextPage: String = "",
    val detailTitle: String = "",
    val detailPoster: String = "",
    val detailPlot: String = "",
    val detailYear: String = "",
    val detailTags: String = "",
    val episodes: String = "",
    val episodeLink: String = "",
    val episodeTitle: String = "",
    val servers: String = "",
)

open class SiteSpec {
    open val name: String get() = "Site"
    open val baseUrl: String get() = ""
    open val language: String get() = "en"
    open val tvTypes: Set<TvType> get() = setOf(TvType.Movie, TvType.TvSeries)
    open val archetype: Archetype get() = Archetype.GENERIC
    open val home: List<HomeTab> get() = listOf(HomeTab("Latest", "/"))
    open val search: SearchCfg get() = SearchCfg("?s={query}")
    open val selectors: Selectors get() = Selectors()
    open val headers: Map<String, String> get() = mapOf("User-Agent" to UA)
    open val useWebView: Boolean get() = false

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

abstract class SiteEngine : MainAPI() {
    abstract val spec: SiteSpec

    override var name: String
        get() = spec.name
        set(value) { }

    override var mainUrl: String
        get() = spec.baseUrl
        set(value) { }

    override var lang: String
        get() = spec.language
        set(value) { }
    override val supportedTypes: Set<TvType> get() = spec.tvTypes
    override val hasMainPage: Boolean get() = spec.home.isNotEmpty()
    override val usesWebView: Boolean get() = spec.useWebView

    // ------------------------------------------------------------ networking

    private suspend fun fetchHtml(url: String): String? {
        return try {
            val res = app.get(url, headers = spec.headers)
            if (res.code >= 400) null else res.text
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun fetchDoc(url: String): Document? =
        fetchHtml(url)?.let { Jsoup.parse(it, url) }

    // -------------------------------------------------------------- homepage

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val tab = spec.home.firstOrNull { it.name == request.name }
            ?: spec.home.firstOrNull()
            ?: return null

        var target = resolve(tab.path) ?: return null
        var doc = fetchDoc(target) ?: return null

        var current = 1
        while (current < page) {
            val next = nextPage(doc) ?: break
            doc = fetchDoc(next) ?: break
            target = next
            current++
        }

        val items = parseCards(doc, target)
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items, hasNext = nextPage(doc) != null)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val cfg = spec.search
        if (cfg.path.isBlank()) return null
        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query
        }
        val target = resolve(cfg.path.replace("{query}", encoded)) ?: return null

        val doc = if (cfg.method.equals("POST", true)) {
            val body = cfg.body.replace("{query}", encoded).ifBlank { "query=$encoded" }
            val fields = body.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }.toMap()
            try {
                val res = app.post(
                    target,
                    data = fields.ifEmpty { mapOf("query" to encoded) },
                    headers = spec.headers,
                )
                Jsoup.parse(res.text, target)
            } catch (e: Exception) {
                logError(e)
                return null
            }
        } else {
            fetchDoc(target) ?: return null
        }

        return parseCards(doc, target)
    }

    // ---------------------------------------------------------------- detail

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url) ?: return null
        val sel = spec.selectors
        val ld = jsonLd(doc)

        val title = pickIn(doc, sel.detailTitle)
            ?: meta(doc, "og:title")
            ?: ld["name"]
            ?: doc.title().ifBlank { null }
            ?: return null

        val poster = resolve(
            pickIn(doc, sel.detailPoster)
                ?: meta(doc, "og:image")
                ?: ld["image"]
                ?: firstImage(doc)
        )
        val plot = pickIn(doc, sel.detailPlot)
            ?: meta(doc, "og:description")
            ?: meta(doc, "description")
            ?: ld["description"]
        val year = (pickIn(doc, sel.detailYear) ?: ld["datePublished"])
            ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val tags = tagsOf(doc, sel.detailTags)
        val episodeList = parseEpisodes(doc, url)

        fun applyCommon(target: LoadResponse) {
            target.posterUrl = poster
            target.plot = plot
            target.year = year
            target.tags = tags
        }

        return if (episodeList.size > 1) {
            if (spec.tvTypes.contains(TvType.Anime)) {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    applyCommon(this)
                    this.episodes = mutableMapOf(DubStatus.Subbed to episodeList)
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                    applyCommon(this)
                }
            }
        } else {
            val type = spec.tvTypes.firstOrNull() ?: TvType.Movie
            newMovieLoadResponse(title, url, type, dataUrl = url) {
                applyCommon(this)
            }
        }
    }

    // ----------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Deep fetch: walk the detail page, its iframes, server tabs and any
        // nested player pages so that every provider we can find is handed to
        // CloudStream's extractor pipeline instead of just the first one.
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(data to data)
        val collected = LinkedHashSet<String>()
        val subtitles = LinkedHashSet<String>()

        while (queue.isNotEmpty() && visited.size < 50) {
            val (current, referer) = queue.removeFirst()
            if (!visited.add(current)) continue
            val html = fetchHtml(current) ?: continue
            val doc = Jsoup.parse(html, current)

            collectEmbeds(doc, html, current).forEach { collected.add(it) }
            collectSubtitles(doc, html, current).forEach { subtitles.add(it) }

            // Server tabs / buttons that carry a provider url in a data-* attr.
            for (el in doc.select(
                "[data-src],[data-url],[data-link],[data-video],[data-player]," +
                    "[data-embed],[data-embed-url],[data-href],[data-file],[data-source]," +
                    "[data-mp4],[data-m3u8],[data-dash]"
            )) {
                val raw = listOf(
                    "data-src", "data-url", "data-link", "data-video", "data-player",
                    "data-embed", "data-embed-url", "data-href", "data-file", "data-source",
                    "data-mp4", "data-m3u8", "data-dash"
                ).mapNotNull { key -> el.attr(key).takeIf { it.isNotBlank() } }
                    .firstOrNull() ?: continue
                resolve(raw)?.let { url ->
                    if (isDirectMedia(url)) collected.add(url)
                    else if (!visited.contains(url)) queue.add(url to current)
                }
            }

            // onclick handlers sometimes hide the real url.
            for (el in doc.select("[onclick]")) {
                for (url in extractUrls(el.attr("onclick"), current)) {
                    if (isDirectMedia(url)) collected.add(url)
                    else if ((url.contains("embed") || url.contains("player") ||
                            url.contains("watch") || url.contains("source") ||
                            url.contains("see") || url.contains("play")) &&
                        !visited.contains(url)
                    ) queue.add(url to current)
                }
            }

            // Follow iframes one (and sometimes two) levels deep.
            for (iframe in doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")) {
                val src = iframe.attr("src").ifBlank {
                    iframe.attr("data-src").ifBlank { iframe.attr("data-lazy-src") }
                }
                resolve(src)?.let { url ->
                    if (isDirectMedia(url)) collected.add(url)
                    else if (!visited.contains(url)) queue.add(url to current)
                }
                val srcdoc = iframe.attr("srcdoc")
                if (srcdoc.isNotBlank()) {
                    extractUrls(srcdoc, current).forEach { url ->
                        if (isDirectMedia(url)) collected.add(url)
                    }
                }
            }

            // Pull every url we can find out of the page's scripts and JSON.
            for (url in extractUrls(html, current)) {
                if (isDirectMedia(url)) collected.add(url)
                else if ((url.contains("embed") || url.contains("player") ||
                        url.contains("watch") || url.contains("source") ||
                        url.contains("stream") || url.contains("see") ||
                        url.contains("play")) && !visited.contains(url) &&
                    visited.size < 40
                ) queue.add(url to current)
            }

            // Last resort: a detail page that has no player yet may link to a
            // local watch/episode page.
            if (current == data && collected.isEmpty()) {
                val hops = doc.select("a[href]")
                    .mapNotNull { resolve(it.attr("href")) }
                    .filter {
                        it.startsWith(mainUrl) && (
                            it.contains("watch") || it.contains("episode") ||
                                it.contains("embed") || it.contains("player") ||
                                it.contains("stream") || it.contains("see") ||
                                it.contains("play")
                            )
                    }
                    .distinct()
                    .take(6)
                hops.forEach { queue.add(it to current) }
            }
        }

        // Emit subtitles first so they are available regardless of providers.
        for (sub in subtitles) {
            runCatching { subtitleCallback(SubtitleFile(hostOf(sub), sub)) }
        }

        val found = emitLinks(collected.toList(), data, subtitleCallback, callback)
        return found || collected.isNotEmpty()
    }

    private fun isDirectMedia(url: String): Boolean = directType(url) != null

    private fun collectSubtitles(doc: Document, html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        doc.select("track[src], track[data-src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src") }
            resolve(raw)?.let { out.add(it) }
        }
        Regex("""https?://[^"'\s<>\\]+?\.(?:vtt|srt|ass|sub)(?:\?[^"'\s<>\\]*)?""")
            .findAll(html).forEach { m -> resolve(m.value)?.let { out.add(it) } }
        return out.toList()
    }

    private fun extractUrls(text: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val add: (String?) -> Unit = { raw ->
            resolve(raw)?.let { url ->
                if (!url.startsWith("about:") && !url.startsWith("data:")) out.add(url)
            }
        }

        // Plain urls.
        Regex("""https?://[^"'\s<>\\]+""").findAll(text)
            .forEach { m -> add(m.value.trimEnd(')', ']', ',', ';')) }
        Regex("""//[^"'\s<>\\]+""").findAll(text)
            .forEach { m -> add(m.value.trimEnd(')', ']', ',', ';')) }

        // JSON-ish key: "value" pairs for common media fields.
        for (key in listOf(
            "file", "source", "src", "url", "href", "video", "hls", "dash",
            "m3u8", "mp4", "stream", "sources", "tracks", "link", "embed"
        )) {
            Regex("""(?i)["']$key["']\s*[:=]\s*["']([^"']+)["']""").findAll(text)
                .forEach { m -> add(m.groupValues[1]) }
            Regex("""(?i)$key\s*[:=]\s*["']([^"']+)["']""").findAll(text)
                .forEach { m -> add(m.groupValues[1]) }
        }

        // sources: [ ... ] blocks.
        Regex("""(?i)sources?\s*[:=]\s*\[[^\]]*\]""").findAll(text).forEach { block ->
            Regex("""https?://[^"'\s<>\\]+""").findAll(block.value).forEach { m -> add(m.value) }
            Regex("""["'](?:file|src|url)["']\s*:\s*["']([^"']+)["']""").findAll(block.value)
                .forEach { m -> add(m.groupValues[1]) }
        }

        // Escaped slashes (common in JSON).
        Regex("""https?:\\/\\/[^"'\s<>\\]+""").findAll(text).forEach { m ->
            add(m.value.replace("\\/", "/"))
        }

        // Base64 payloads (atob / data-*).
        Regex("""atob\(\s*["']([^"']+)["']\s*\)""").findAll(text).forEach { m ->
            try {
                val decoded = String(java.util.Base64.getDecoder().decode(m.groupValues[1]))
                extractUrls(decoded, base).forEach { add(it) }
            } catch (_: Exception) {
            }
        }

        // srcdoc / nested html strings.
        Regex("""(?is)srcdoc\s*=\s*["']([^"']*)["']""").findAll(text).forEach { m ->
            extractUrls(m.groupValues[1].replace("&quot;", "\""), base).forEach { add(it) }
        }

        return out.toList()
    }

    private suspend fun emitLinks(
        links: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        for (link in links) {
            val direct = directType(link)
            if (direct != null) {
                callback(
                    newExtractorLink(spec.name, hostOf(link), link, direct) {
                        this.referer = referer
                        this.headers = spec.headers
                    }
                )
                found = true
            } else {
                val ok = runCatching {
                    loadExtractor(link, referer, subtitleCallback, callback)
                }.getOrDefault(false)
                if (ok) found = true
            }
        }
        return found
    }

    // ------------------------------------------------------------- internals

    private fun parseCards(doc: Document, base: String): List<SearchResponse> {
        val direct = parseCardsFrom(base, cardNodes(doc))
        if (direct.isNotEmpty()) return direct
        // The configured selector may be stale; auto-detection is the safety net.
        return parseCardsFrom(base, autoCards(doc))
    }

    private fun parseCardsFrom(
        base: String,
        cards: List<Element>,
    ): List<SearchResponse> {
        val sel = spec.selectors
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (card in cards) {
            val href = resolve(
                pickIn(card, sel.cardLink) ?: card.selectFirst("a[href]")?.attr("href")
            ) ?: continue
            if (!seen.add(href)) continue

            val title = (
                pickIn(card, sel.cardTitle)
                    ?: card.selectFirst("img[alt]")?.attr("alt")
                    ?: card.selectFirst("h1,h2,h3,h4")?.text()
                    ?: card.selectFirst("a")?.text()
                )?.trim()?.takeIf { it.isNotBlank() } ?: continue

            val poster = resolve(pickIn(card, sel.cardPoster) ?: posterOf(card))
            val type = typeFor(href, title)

            out.add(
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    private fun cardNodes(doc: Document): List<Element> {
        val sel = spec.selectors.cards
        if (sel.isNotBlank()) {
            val direct = runCatching { doc.select(sel) }.getOrDefault(emptyList())
            if (direct.size >= 2) return direct
        }
        if (spec.archetype == Archetype.MADARA) {
            val known = listOf(
                "div.page-item-detail",
                "div.c-tabs-item__content",
                "div.manga",
                "article",
            )
            for (css in known) {
                val els = runCatching {
                    doc.select(css).filter {
                        it.selectFirst("a[href]") != null && it.selectFirst("img") != null
                    }
                }.getOrDefault(emptyList())
                if (els.size >= 4) return els
            }
        }
        return autoCards(doc)
    }

    private fun autoCards(doc: Document): List<Element> {
        var best: List<Element> = emptyList()
        var bestScore = 0
        var scanned = 0
        val parents = doc.select("div, ul, ol, section, article, tbody, main")
        for (parent in parents) {
            if (scanned++ > 2500) break
            val kids = parent.children().filter {
                it.selectFirst("a[href]") != null && it.selectFirst("img") != null
            }
            if (kids.size < 4) continue
            val score = kids.size * 10 +
                kids.count { !it.selectFirst("img[alt]")?.attr("alt").isNullOrBlank() } * 3
            if (score > bestScore) {
                bestScore = score
                best = kids
            }
        }
        return best
    }

    private fun parseEpisodes(doc: Document, pageUrl: String): List<Episode> {
        val sel = spec.selectors
        val nodes = if (sel.episodes.isNotBlank()) {
            runCatching { doc.select(sel.episodes.substringBefore("::")) }.getOrDefault(emptyList())
        } else {
            doc.select("a[href]").filter { isEpisodeLink(it) }
        }

        val out = LinkedHashMap<String, Episode>()
        for (node in nodes) {
            val href = resolve(pickIn(node, sel.episodeLink))
                ?: resolve(node.attr("href"))
                ?: continue

            val text = node.text().trim().ifBlank { null }
            val rawTitle = if (sel.episodeTitle.isBlank()) text else pickIn(node, sel.episodeTitle) ?: text
            val number = Regex("""(\d+)""").findAll(rawTitle ?: "")
                .lastOrNull()?.value?.toIntOrNull()

            if (!out.containsKey(href)) {
                out[href] = newEpisode(href) {
                    this.name = rawTitle
                    this.episode = number
                }
            }
        }
        return out.values.toList()
    }

    private fun isEpisodeLink(a: Element): Boolean {
        val href = a.attr("href").lowercase()
        val text = a.text().lowercase()
        return href.contains("/episode") || href.contains("/ep-") || href.contains("/watch") ||
            href.contains("/season/") ||
            Regex("""episode\s*\d+""").containsMatchIn(text) ||
            Regex("""\d+\s*[x×]\s*\d+""").containsMatchIn(text) ||
            text.contains("الحلقة") || text.contains("حلقة")
    }

    private fun tagsOf(doc: Document, selector: String): List<String>? {
        if (selector.isBlank()) return null
        val css = selector.substringBefore("::")
        val values = runCatching {
            doc.select(css).map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(20)
        }.getOrDefault(emptyList())
        return values.ifEmpty { null }
    }

    private fun collectEmbeds(doc: Document, html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val sel = spec.selectors.servers

        if (sel.isNotBlank()) {
            val css = sel.substringBefore("::")
            runCatching { doc.select(css) }.getOrDefault(emptyList()).forEach { el ->
                val value = pickIn(el, sel) ?: el.attr("src").ifBlank {
                    el.attr("data-src").ifBlank { el.attr("data-embed-url") }
                }
                resolve(value)?.let { out.add(it) }
            }
        }

        // Server tabs that carry the provider url in a data-* attribute
        // (common in phpMelody-style players: <li data-embed-url="...">).
        for (attr in listOf("data-embed-url", "data-embed", "data-src", "data-url", "data-link")) {
            for (el in doc.select("[$attr]")) {
                resolve(el.attr(attr))?.let { out.add(it) }
            }
        }

        doc.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src]")
            .forEach { el ->
                val value = el.attr("src").ifBlank { el.attr("data-src") }
                resolve(value)?.let { if (!it.startsWith("about:")) out.add(it) }
            }

        Regex("""(?:https?:)?//[^"'\s<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'\s<>\\]*)?""")
            .findAll(html)
            .forEach { m -> resolve(m.value)?.let { out.add(it) } }

        return out.toList()
    }

    private fun directType(url: String): ExtractorLinkType? = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        url.contains(".mp4", true) || url.contains(".webm", true) -> ExtractorLinkType.VIDEO
        else -> null
    }

    private fun nextPage(doc: Document): String? {
        val sel = spec.selectors.nextPage
        val css = sel.substringBefore("::").trim()
        val el = (if (css.isNotBlank()) runCatching { doc.selectFirst(css) }.getOrNull() else null)
            ?: doc.selectFirst("a[rel=next]")
            ?: doc.select("a").firstOrNull {
                it.text().trim().lowercase() in
                    setOf("next", "next page", "»", "→", "التالي", "الصفحة التالية")
            }
        return el?.let { resolve(it.attr("href")) }
    }

    private fun pickIn(root: Element, raw: String): String? {
        if (raw.isBlank()) return null
        val idx = raw.indexOf("::")
        val css = if (idx >= 0) raw.substring(0, idx).trim() else raw.trim()
        val mode = if (idx >= 0) raw.substring(idx + 2).trim() else "text"

        val el = runCatching {
            if (css.isEmpty()) root else root.selectFirst(css)
        }.getOrNull() ?: return null

        val value = when {
            mode == "html" -> el.html()
            mode.startsWith("attr(") && mode.endsWith(")") ->
                el.attr(mode.substring(5, mode.length - 1))
            else -> el.text()
        }
        return value.trim().ifBlank { null }
    }

    private fun posterOf(card: Element): String? {
        val img = card.selectFirst("img") ?: return null
        for (attr in listOf("src", "data-src", "data-lazy-src", "data-original", "data-echo")) {
            val value = img.attr(attr).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) return value.split(" ")[0]
        }
        val srcset = img.attr("srcset").trim()
        return if (srcset.isNotEmpty()) srcset.split(",")[0].trim().split(" ")[0] else null
    }

    private fun firstImage(doc: Document): String? {
        for (img in doc.select("img")) {
            for (attr in listOf("src", "data-src", "data-lazy-src")) {
                val value = img.attr(attr).trim()
                if (value.isNotEmpty() && !value.startsWith("data:")) return value
            }
        }
        return null
    }

    private fun meta(doc: Document, key: String): String? =
        doc.selectFirst("meta[property='$key'], meta[name='$key']")
            ?.attr("content")?.trim()?.ifBlank { null }

    private fun typeFor(href: String, title: String): TvType {
        val low = (href + " " + title).lowercase()
        return when {
            spec.tvTypes.contains(TvType.Anime) &&
                (low.contains("anime") || low.contains("انمي") || low.contains("أنمي")) -> TvType.Anime
            low.contains("/series/") || low.contains("/tv/") ||
                low.contains("season") || low.contains("مسلسل") -> TvType.TvSeries
            else -> spec.tvTypes.firstOrNull() ?: TvType.Movie
        }
    }

    private fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        if (value.startsWith("data:") || value.startsWith("javascript:") || value.startsWith("#")) {
            return null
        }
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching {
            java.net.URI(mainUrl).resolve(value).toString()
        }.getOrElse {
            mainUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.replace("www.", "") ?: spec.name

    // ----------------------------------------------------------------- JSON-LD

    private fun jsonLd(doc: Document): Map<String, String> {
        for (script in doc.select("script[type=application/ld+json]").take(8)) {
            val raw = script.data().ifBlank { script.html() }
            try {
                val node = findMediaNode(JSONObject(raw)) ?: continue
                val out = HashMap<String, String>()
                node.optString("name").takeIf { it.isNotBlank() }?.let { out["name"] = it }
                node.optString("description").takeIf { it.isNotBlank() }
                    ?.let { out["description"] = it }
                node.optString("datePublished").takeIf { it.isNotBlank() }
                    ?.let { out["datePublished"] = it }
                imageOf(node)?.let { out["image"] = it }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                // not JSON-LD we understand; try the next block
            }
        }
        return emptyMap()
    }

    private fun findMediaNode(obj: JSONObject): JSONObject? {
        val rawType = obj.opt("@type")
        val types = if (rawType is JSONArray) {
            (0 until rawType.length()).map { rawType.optString(it) }
        } else {
            listOf(rawType?.toString() ?: "")
        }
        if (types.any { it == "Movie" || it == "TVSeries" || it == "VideoObject" }) return obj

        val graph = obj.opt("@graph")
        if (graph is JSONArray) {
            for (i in 0 until graph.length()) {
                val child = graph.optJSONObject(i) ?: continue
                findMediaNode(child)?.let { return it }
            }
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is JSONObject) findMediaNode(value)?.let { return it }
        }
        return null
    }

    private fun imageOf(node: JSONObject): String? {
        return when (val image = node.opt("image")) {
            is String -> image.ifBlank { null }
            is JSONObject -> image.optString("url").ifBlank { null }
            is JSONArray -> if (image.length() > 0) image.optString(0).ifBlank { null } else null
            else -> null
        }
    }
}
