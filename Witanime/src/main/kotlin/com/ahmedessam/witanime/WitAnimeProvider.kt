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
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
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

    data class ServerCandidate(
        val url: String,
        val tabLabel: String,
        val qualityBadge: String,
        val isDownload: Boolean = false
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val PRIVATE_HOST_REGEX = Regex(
            """^(?:localhost|0\.0\.0\.0|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[0-1])\.\d+\.\d+|169\.254\.\d+\.\d+|\[?::1\]?|\[?fe80:.*|\[?fc00:.*|\[?fd00:.*)$""",
            RegexOption.IGNORE_CASE
        )

        fun isSafePublicUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return false
            val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
            return runCatching {
                val uri = URI(normalized)
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme != "http" && scheme != "https") return false
                val host = uri.host?.trim()?.lowercase()?.trim('[', ']') ?: return false
                if (host.isEmpty() || !host.contains('.')) return false
                if (PRIVATE_HOST_REGEX.matches(host) || host.endsWith(".local") || host.endsWith(".internal")) {
                    return false
                }
                true
            }.getOrDefault(false)
        }

        fun normalizeQualityBadge(raw: String?): String {
            val upper = raw?.trim()?.uppercase() ?: ""
            return when {
                upper.contains("4K") || upper.contains("2160") -> "4K"
                upper.contains("FHD") || upper.contains("1080") -> "FHD"
                upper.contains("HD") || upper.contains("720") -> "HD"
                upper.contains("SD") || upper.contains("480") -> "SD"
                upper.contains("360") -> "SD"
                else -> "FHD"
            }
        }

        fun formatServerName(
            tabLabel: String,
            extractorName: String = "",
            host: String = "",
            qualityBadge: String = ""
        ): String {
            val badge = normalizeQualityBadge(qualityBadge.ifBlank { tabLabel })
            val cleanTab = tabLabel
                .replace(Regex("""\bDL\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\[.*?\]|\(.*?\)"""), "")
                .trim()
            val combined = "$cleanTab $extractorName $host".lowercase()

            return when {
                combined.contains("yonaplay") || combined.contains("dotplay") -> {
                    val sub = when {
                        combined.contains("streamwish") || combined.contains("wishembed") || combined.contains("sfastwish") -> "StreamWish"
                        combined.contains("voe") -> "VoeSX"
                        combined.contains("vidhide") || combined.contains("filelions") -> "VidHide"
                        combined.contains("dood") -> "DoodStream"
                        combined.contains("mp4upload") -> "Mp4Upload"
                        else -> "DotPlay"
                    }
                    "YonaPlay ($sub - $badge)"
                }
                combined.contains("soraplay") -> "SoraPlay (Multi - $badge)"
                combined.contains("ok.ru") || combined.contains("odnoklassniki") || combined.contains("okru") || Regex("""\bok\b""").containsMatchIn(combined) -> "OK [$badge]"
                combined.contains("videas") -> "Videas [$badge]"
                combined.contains("videa") -> "Videa [$badge]"
                combined.contains("mp4upload") -> "Mp4Upload [$badge]"
                combined.contains("streamwish") || combined.contains("wishembed") || combined.contains("sfastwish") -> "StreamWish [$badge]"
                combined.contains("dood") || combined.contains("d000") -> "DoodStream [$badge]"
                combined.contains("mega.nz") || Regex("""\bmega\b""").containsMatchIn(combined) -> "Mega [$badge]"
                combined.contains("mediafire") -> "MediaFire [$badge]"
                combined.contains("uqload") -> "Uqload [$badge]"
                combined.contains("voe") -> "VoeSX [$badge]"
                combined.contains("vidbom") || combined.contains("vadbam") -> "VidBom [$badge]"
                combined.contains("filelions") || combined.contains("vidhide") || combined.contains("hgcloud") || combined.contains("hglink") || combined.contains("hanerix") || combined.contains("dhcplay") -> "VidHide [$badge]"
                combined.contains("dailymotion") -> "Dailymotion [$badge]"
                combined.contains("yourupload") -> "YourUpload [$badge]"
                combined.contains("mail.ru") || Regex("""\bmail\b""").containsMatchIn(combined) -> "MailRu [$badge]"
                else -> {
                    val base = cleanTab.ifBlank { extractorName.ifBlank { "Server" } }
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    "$base [$badge]"
                }
            }
        }

        fun decodeBase64Bytes(encoded: String): ByteArray? {
            val clean = encoded.trim()
            if (clean.isEmpty()) return null
            return runCatching {
                java.util.Base64.getMimeDecoder().decode(clean)
            }.recoverCatching {
                Base64.decode(clean, Base64.DEFAULT)
            }.getOrNull()
        }

        fun decodeBase64String(encoded: String): String? {
            val bytes = decodeBase64Bytes(encoded) ?: return null
            return runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrNull()
        }

        fun decodeXorString(encodedBase64: String, key: String): String? {
            if (key.isEmpty()) return null
            val bytes = decodeBase64Bytes(encodedBase64) ?: return null
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) {
                out[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            return runCatching { String(out, Charsets.UTF_8).trim() }.getOrNull()
        }

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
            "Sec-Fetch-Site" to "same-origin",
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
        @JsonProperty("players") val players: Map<String, List<QualityServer>>? = null,
        @JsonProperty("qualities") val qualities: Map<String, List<QualityServer>>? = null,
        @JsonProperty("downloads") val downloads: Map<String, List<QualityServer>>? = null
    )

    data class QualityServer(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("token") val token: String? = null
    ) {
        val serverName: String? get() = label ?: name
    }

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

    private val arabicAnimeAliases = listOf(
        "ون بيس" to "One Piece",
        "ناروتو" to "Naruto",
        "بليتش" to "Bleach",
        "هنتر" to "Hunter x Hunter",
        "القناص" to "Hunter x Hunter",
        "هجوم العمالقة" to "Shingeki no Kyojin",
        "العمالقة" to "Shingeki no Kyojin",
        "بلاك كلوفر" to "Black Clover",
        "قاتل الشياطين" to "Kimetsu no Yaiba",
        "كيميتسو" to "Kimetsu no Yaiba",
        "جوجوتسو" to "Jujutsu Kaisen",
        "سولو ليفلينج" to "Solo Leveling",
        "دراجون بول" to "Dragon Ball",
        "دراغون بول" to "Dragon Ball",
        "كونان" to "Detective Conan",
        "المحق كونان" to "Detective Conan",
        "طوكيو غول" to "Tokyo Ghoul",
        "طوكيو ريفنجرز" to "Tokyo Revengers",
        "مذكرة الموت" to "Death Note",
        "ديث نوت" to "Death Note",
        "ون بنش مان" to "One Punch Man",
        "الكيميائي المعدني" to "Fullmetal Alchemist",
        "فول ميتال" to "Fullmetal Alchemist",
        "ماي هيرو" to "Boku no Hero Academia",
        "أكاديمية بطلي" to "Boku no Hero Academia",
        "بوكونو هيرو" to "Boku no Hero Academia",
        "بلو لوك" to "Blue Lock",
        "هايكيو" to "Haikyuu",
        "تشانسو مان" to "Chainsaw Man",
        "رجل المنشار" to "Chainsaw Man",
        "فينلاند ساغا" to "Vinland Saga",
        "بيرسيرك" to "Berserk",
        "مونستر" to "Monster",
        "فريرن" to "Sousou no Frieren",
        "مشرحة" to "Kusuriya no Hitorigoto",
        "يوميات الصيدلانية" to "Kusuriya no Hitorigoto",
        "أوفرلورد" to "Overlord",
        "ري زيرو" to "Re:Zero",
        "شتاينز غيت" to "Steins;Gate",
        "كود غياس" to "Code Geass",
        "فيري تيل" to "Fairy Tail",
        "جينتاما" to "Gintama",
        "غينتاما" to "Gintama",
        "موب سايكو" to "Mob Psycho",
        "دكتور ستون" to "Dr. Stone",
        "سباي اكس فاميلي" to "Spy x Family",
        "خطايا" to "Nanatsu no Taizai",
        "الخطايا السبع" to "Nanatsu no Taizai",
        "سورد آرت" to "Sword Art Online",
        "باراسايت" to "Kiseijuu",
        "الطفيليات" to "Kiseijuu",
        "نيفرلاند" to "Yakusoku no Neverland",
        "جوجو" to "JoJo",
        "كينغدوم" to "Kingdom",
        "سلام دانك" to "Slam Dunk",
        "كابتن ماجد" to "Captain Tsubasa",
        "انويشا" to "InuYasha",
        "إينوياشا" to "InuYasha",
        "يوغي" to "Yu-Gi-Oh",
        "بوكيمون" to "Pokemon",
        "أبطال الديجيتال" to "Digimon",
        "ديجيمون" to "Digimon"
    )

    private fun resolveSearchQueries(rawQuery: String): List<String> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val queries = mutableListOf(q)
        for ((ar, en) in arabicAnimeAliases) {
            if (q.contains(ar, ignoreCase = true)) {
                queries.add(en)
            }
        }
        return queries.distinct()
    }

    private suspend fun fetchSuggestResults(q: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(q.trim(), "UTF-8")
        val url = "$mainUrl/search/suggest?q=$encoded"
        val jsonText = runCatching { app.get(url, headers = ajaxHeaders("$mainUrl/")).text }.getOrNull()
            ?: return emptyList()
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

    private suspend fun fetchHtmlSearchResults(q: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(q.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = runCatching { app.get(searchUrl, headers = docHeaders("$mainUrl/")).document }.getOrNull()
            ?: return emptyList()
        return parseCardsFromContainer(doc)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        for (q in resolveSearchQueries(query)) {
            val res = fetchSuggestResults(q)
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val combined = LinkedHashMap<String, SearchResponse>()
        for (q in resolveSearchQueries(query)) {
            fetchHtmlSearchResults(q).forEach { combined.putIfAbsent(it.url, it) }
            fetchSuggestResults(q).forEach { combined.putIfAbsent(it.url, it) }
            if (combined.isNotEmpty()) break
        }
        return combined.values.toList()
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

        // Episodes extraction (modern + legacy containers + script arrays)
        val episodes = extractAllEpisodes(doc, banner)

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
            val fallbackWatch = doc.selectFirst("a[href*='/watch/'], a[href*='/episode/']")?.attr("href")?.let { fixUrl(it) }
            if (fallbackWatch != null && isSafePublicUrl(fallbackWatch)) {
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

    internal fun extractAllEpisodes(doc: Document, banner: String? = null): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()

        fun addEp(rawUrl: String, rawTitle: String?, epNumberHint: Int?, thumb: String?) {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("javascript:", ignoreCase = true) || trimmed == "#") return
            val fullUrl = fixUrl(trimmed)
            if (!isSafePublicUrl(fullUrl)) return

            val epNum = epNumberHint
                ?: Regex("""/(?:watch|episode)/[^/?#]+/(\d+)""").find(fullUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(rawTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            val epTitle = if (epNum != null) "الحلقة $epNum" else (rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "الحلقة")

            if (!byUrl.containsKey(fullUrl)) {
                byUrl[fullUrl] = newEpisode(fullUrl) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = thumb ?: banner
                }
            }
        }

        // 1. Modern [data-episode-list] and legacy episode list anchors
        val selectors = listOf(
            "[data-episode-list] a[href*='/watch/']",
            "ul.episodes-list-content li a",
            "div.episodes-card-container a",
            "div.episodes-card a",
            "ul#ULEpisodesList li a"
        ).joinToString(", ")

        doc.select(selectors).forEach { epEl ->
            val onclick = epEl.attr("onclick").trim()
            val onclickB64 = Regex("""openEpisode\(\s*['"]([^'"]+)['"]\s*\)""").find(onclick)?.groupValues?.getOrNull(1)
            val decodedOnclick = onclickB64?.let { decodeBase64String(it) ?: it }
            val href = decodedOnclick?.takeIf { it.isNotEmpty() } ?: epEl.attr("href").trim()
            val epNum = epEl.attr("data-episode-number").trim().toIntOrNull()
                ?: epEl.selectFirst("span[dir='ltr']")?.text()?.trim()?.toIntOrNull()
            val epThumb = epEl.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }
            addEp(href, epEl.text(), epNum, epThumb)
        }

        // 2. Elements with onclick="openEpisode('...')" that are not <a> tags
        doc.select("[onclick*='openEpisode']").forEach { el ->
            val onclick = el.attr("onclick").trim()
            val b64 = Regex("""openEpisode\(\s*['"]([^'"]+)['"]\s*\)""").find(onclick)?.groupValues?.getOrNull(1)
                ?: return@forEach
            val decoded = decodeBase64String(b64) ?: b64
            addEp(decoded, el.text(), null, null)
        }

        // 3. Script Base64 / JSON arrays (processedEpisodeData, encodedEpisodeData, episodesData)
        val html = doc.outerHtml()
        val scriptVarRegex = Regex(
            """(?:processedEpisodeData|encodedEpisodeData|episodesData)\s*=\s*['"]([^'"]+)['"]"""
        )
        scriptVarRegex.findAll(html).forEach { m ->
            val rawVal = m.groupValues[1]
            val decoded = decodeBase64String(rawVal) ?: rawVal
            Regex("""https?://[^\s"'<>\\]+""").findAll(decoded.replace("\\/", "/")).forEach { urlMatch ->
                addEp(urlMatch.value, null, null, null)
            }
        }

        return byUrl.values.sortedBy { it.episode ?: Int.MAX_VALUE }
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
        val candidates = LinkedHashMap<String, ServerCandidate>()

        fun addCandidate(rawUrl: String?, tabLabel: String, qualityBadge: String, isDownload: Boolean = false) {
            val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val cleanUrl = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> fixUrl(trimmed)
                else -> trimmed
            }
            if (!isSafePublicUrl(cleanUrl)) return
            val lower = cleanUrl.lowercase()
            if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
                lower.contains("facebook.com") || lower.contains("twitter.com") ||
                lower.contains("instagram.com") || lower.contains("t.me/")
            ) return

            val key = cleanUrl.removeSuffix("/")
            if (!candidates.containsKey(key)) {
                candidates[key] = ServerCandidate(
                    url = cleanUrl,
                    tabLabel = tabLabel.trim().ifEmpty { "Server" },
                    qualityBadge = normalizeQualityBadge(qualityBadge.ifBlank { tabLabel }),
                    isDownload = isDownload
                )
            }
        }

        val watchResp = runCatching { app.get(watchUrl, headers = docHeaders("$mainUrl/")) }.getOrNull()
            ?: return false
        val sessionCookies = watchResp.cookies.toMutableMap()
        val doc = watchResp.document

        // Path A: Newer / Migrated Series (/watch/{slug}/{ep}/sources AJAX JSON)
        val csrfToken = doc.selectFirst("meta[name='csrf-token']")?.attr("content")?.trim()
        if (!csrfToken.isNullOrBlank()) {
            val rawSourcesUrl = Regex("""sourcesUrl:\s*'([^']+)'""")
                .find(watchResp.text)
                ?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.let { fixUrl(it) }
                ?: "${watchUrl.trimEnd('/')}/sources"

            val sourcesResp = runCatching {
                app.post(
                    rawSourcesUrl,
                    headers = ajaxHeaders(watchUrl, csrfToken),
                    cookies = sessionCookies
                )
            }.getOrNull()

            if (sourcesResp != null) {
                if (sourcesResp.cookies.isNotEmpty()) {
                    sessionCookies.putAll(sourcesResp.cookies)
                }
                if (sourcesResp.code in 200..299) {
                    val sourcesData = tryParseJson<WatchSourcesResponse>(sourcesResp.text)
                    if (sourcesData != null) {
                        val playerMap = sourcesData.players ?: sourcesData.qualities ?: emptyMap()
                        val allServers = mutableListOf<Pair<String, QualityServer>>()
                        val qualityPriority = listOf("4K", "FHD", "HD", "SD", "4k", "fhd", "hd", "sd")

                        for (qKey in qualityPriority) {
                            playerMap[qKey]?.forEach { srv ->
                                if (!srv.token.isNullOrBlank()) {
                                    allServers.add(qKey to srv)
                                }
                            }
                        }
                        playerMap.forEach { (qKey, list) ->
                            if (qKey !in qualityPriority) {
                                list.forEach { srv ->
                                    if (!srv.token.isNullOrBlank()) {
                                        allServers.add(qKey to srv)
                                    }
                                }
                            }
                        }

                        // shortcut: cap token resolutions at 4 per episode, witanime.site rate-limits /watch/stream-gate at 5 req/min
                        val prioritizedTokens = mutableListOf<Pair<String, QualityServer>>()

                        // 1. Pick ONE yonaplay token first (1 token = multi-quality direct MP4 hub on mid.yonaplay.net)
                        val yonaCandidate = allServers.firstOrNull { (_, s) ->
                            s.serverName?.contains("yonaplay", ignoreCase = true) == true
                        }
                        if (yonaCandidate != null) {
                            prioritizedTokens.add(yonaCandidate)
                        }

                        // 2. Pick up to 3 top reliable playable servers
                        val serverOrder = listOf(
                            "videas", "hgcloud", "ok", "mp4upload", "videa", "dailymotion",
                            "dood", "streamwish", "vidbom", "uqload", "voe", "vidhide",
                            "filelions", "soraplay", "yourupload", "mail", "mediafire"
                        )
                        for (srvKey in serverOrder) {
                            if (prioritizedTokens.size >= 4) break
                            val match = allServers.firstOrNull { (_, s) ->
                                val n = s.serverName?.lowercase() ?: ""
                                n.contains(srvKey) && prioritizedTokens.none { it.second.token == s.token }
                            }
                            if (match != null) {
                                prioritizedTokens.add(match)
                            }
                        }

                        // 3. Fill remaining slots up to 4 tokens (skipping non-extractable lockers unless empty)
                        val skippedLockers = listOf("mega", "4shared", "google", "workupload", "wtsrv", "gofile")
                        for (entry in allServers) {
                            if (prioritizedTokens.size >= 4) break
                            val s = entry.second
                            val lowerName = s.serverName?.lowercase() ?: ""
                            if (lowerName.contains("yonaplay")) continue
                            if (skippedLockers.any { lowerName.contains(it) }) continue
                            if (prioritizedTokens.none { it.second.token == s.token }) {
                                prioritizedTokens.add(entry)
                            }
                        }

                        for ((qKey, srv) in prioritizedTokens) {
                            val token = srv.token ?: continue
                            val srvName = srv.serverName ?: "Server"
                            runCatching {
                                val resolvedUrl = resolveWatchToken(token, watchUrl, csrfToken, sessionCookies)
                                if (!resolvedUrl.isNullOrBlank()) {
                                    addCandidate(resolvedUrl, srvName, qKey, isDownload = false)
                                }
                            }
                        }

                        // 4. For classic anime with very few playable player tokens, resolve 1 playable download token
                        if (prioritizedTokens.size < 2) {
                            val dlEntry = (sourcesData.downloads ?: emptyMap()).entries.firstNotNullOfOrNull { (qKey, list) ->
                                list.firstOrNull {
                                    val n = it.serverName?.lowercase() ?: ""
                                    (n.contains("mp4upload") || n.contains("mediafire")) && !it.token.isNullOrBlank()
                                }?.let { qKey to it }
                            }
                            if (dlEntry != null) {
                                val (dlQ, dlSrv) = dlEntry
                                val dlToken = dlSrv.token
                                if (!dlToken.isNullOrBlank()) {
                                    runCatching {
                                        val dlUrl = resolveDownloadToken(dlToken, watchUrl, csrfToken, sessionCookies)
                                        if (!dlUrl.isNullOrBlank()) {
                                            addCandidate(dlUrl, dlSrv.serverName ?: "Mp4Upload", dlQ, isDownload = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Path B: Always execute legacy HTML + obfuscated JS extraction layers (handles 404 on /sources or embedded servers)
        extractLegacyCandidatesFromDocument(doc, watchResp.text).forEach { c ->
            addCandidate(c.url, c.tabLabel, c.qualityBadge, c.isDownload)
        }

        var emittedAny = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emittedAny = true
            callback(link)
        }

        for (candidate in candidates.values) {
            runCatching {
                dispatchExtractorUrl(candidate, watchUrl, subtitleCallback, trackingCallback)
            }
        }

        return emittedAny || candidates.isNotEmpty()
    }

    internal fun extractLegacyCandidatesFromDocument(
        doc: Document,
        rawHtml: String = doc.outerHtml()
    ): List<ServerCandidate> {
        val results = LinkedHashMap<String, ServerCandidate>()

        fun normalizeAndAdd(rawUrl: String?, tabLabel: String, qualityBadge: String, isDownload: Boolean = false) {
            var candidateUrl = rawUrl?.trim()?.replace("\\/", "/") ?: return
            if (candidateUrl.isEmpty() || candidateUrl == "#" || candidateUrl.startsWith("javascript:", ignoreCase = true)) return

            if (!candidateUrl.startsWith("http", ignoreCase = true) && !candidateUrl.startsWith("//")) {
                val decoded = decodeBase64String(candidateUrl)?.replace("\\/", "/")
                if (decoded != null && (decoded.startsWith("http", ignoreCase = true) || decoded.startsWith("//"))) {
                    candidateUrl = decoded
                }
            }
            if (candidateUrl.startsWith("//")) {
                candidateUrl = "https:$candidateUrl"
            } else if (candidateUrl.startsWith("/")) {
                candidateUrl = fixUrl(candidateUrl)
            }
            if (!isSafePublicUrl(candidateUrl)) return
            val lower = candidateUrl.lowercase()
            if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
                lower.contains("facebook.com") || lower.contains("twitter.com")
            ) return

            val key = candidateUrl.removeSuffix("/")
            if (!results.containsKey(key)) {
                results[key] = ServerCandidate(
                    url = candidateUrl,
                    tabLabel = tabLabel.trim().ifEmpty { "Server" },
                    qualityBadge = normalizeQualityBadge(qualityBadge.ifBlank { tabLabel }),
                    isDownload = isDownload
                )
            }
        }

        // Layer 1: Player Iframes (#episode-watch-container, .player-container, div.iter-embed, iframe)
        doc.select("#episode-watch-container iframe, .player-container iframe, div.iter-embed iframe, iframe[src], iframe[data-src]")
            .forEach { iframe ->
                val src = iframe.attr("src").trim().ifEmpty { iframe.attr("data-src").trim() }
                normalizeAndAdd(src, "Player", "FHD", isDownload = false)
            }

        // Layer 2: Obfuscated <script> Arrays (XOR _d + _s, Base64 JSON arrays, inline Base64 URLs)
        val indexedScriptUrls = mutableListOf<String>()

        // 2a. XOR-encoded array: var _d = ["...","..."]; var _s = "key";
        val xorKeyMatch = Regex("""var\s+_s\s*=\s*['"]([^'"]+)['"]""").find(rawHtml)
        val xorArrMatch = Regex("""var\s+_d\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        if (xorKeyMatch != null && xorArrMatch != null) {
            val xorKey = xorKeyMatch.groupValues[1]
            Regex("""['"]([^'"]+)['"]""").findAll(xorArrMatch.groupValues[1]).forEach { itemMatch ->
                val decoded = decodeXorString(itemMatch.groupValues[1], xorKey)?.replace("\\/", "/")
                if (!decoded.isNullOrBlank()) {
                    val extractedUrl = Regex("""https?://[^\s"'<>\\]+|//[^\s"'<>\\]+""").find(decoded)?.value ?: decoded
                    val full = if (extractedUrl.startsWith("//")) "https:$extractedUrl" else extractedUrl
                    if (isSafePublicUrl(full)) {
                        indexedScriptUrls.add(full)
                    }
                }
            }
        }

        // 2b. Base64 JSON / array variables (processedEpisodeData, encodedEpisodeData, resourceData, _zG)
        val b64VarRegex = Regex(
            """(?:processedEpisodeData|encodedEpisodeData|resourceData)\s*=\s*['"]([^'"]+)['"]"""
        )
        b64VarRegex.findAll(rawHtml).forEach { m ->
            val rawVal = m.groupValues[1]
            val decoded = decodeBase64String(rawVal)?.replace("\\/", "/") ?: rawVal
            Regex("""https?://[^\s"'<>\\]+""").findAll(decoded).forEach { u ->
                if (isSafePublicUrl(u.value)) {
                    indexedScriptUrls.add(u.value)
                }
            }
        }

        // 2c. Inline Base64 URLs (aHR0cHM6Ly... / aHR0cDov...) inside <script> blocks only
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        Regex("""['"](aHR0c[A-Za-z0-9+/=]{12,})['"]""").findAll(scriptContent).forEach { m ->
            val decoded = decodeBase64String(m.groupValues[1])?.replace("\\/", "/")
            if (!decoded.isNullOrBlank() && isSafePublicUrl(decoded)) {
                if (decoded !in indexedScriptUrls) {
                    indexedScriptUrls.add(decoded)
                }
            }
        }

        // Layer 3: Server Tabs & Index Mapping (ul#episode-servers li a, div.server-list a, a[data-url], a[data-embed], li.linkserver)
        val tabElements = doc.select(
            "ul#episode-servers li a, ul#episode-servers li:not(:has(a)), div.server-list a, a[data-url], a[data-embed], li.linkserver, [onclick*='selectServer']"
        ).filter { el ->
            el.parents().none { p ->
                p.hasClass("quality-list") || p.hasClass("download-list") || p.hasClass("episode-download-container")
            }
        }
        val pairedScriptIndices = mutableSetOf<Int>()

        tabElements.forEachIndexed { idx, el ->
            val labelText = el.text().trim().ifEmpty { el.attr("title").trim() }.ifEmpty { "Server" }
            val dataUrl = el.attr("data-url").trim()
                .ifEmpty { el.attr("data-embed").trim() }
                .ifEmpty { el.attr("data-src").trim() }
                .ifEmpty { el.attr("data-link").trim() }

            if (dataUrl.isNotEmpty()) {
                normalizeAndAdd(dataUrl, labelText, labelText, isDownload = false)
                return@forEachIndexed
            }

            val onclick = el.attr("onclick").trim()
            val b64InOnclick = Regex("""(?:selectServer|loadServer|openServer)\(\s*['"]([A-Za-z0-9+/=]{12,}|https?:[^'"]+)['"]""")
                .find(onclick)?.groupValues?.getOrNull(1)
            if (!b64InOnclick.isNullOrBlank()) {
                normalizeAndAdd(b64InOnclick, labelText, labelText, isDownload = false)
                return@forEachIndexed
            }

            val explicitIndex = el.attr("data-id").trim().toIntOrNull()
                ?: el.attr("data-index").trim().toIntOrNull()
                ?: el.attr("data-server-id").trim().toIntOrNull()
                ?: Regex("""selectServer\(\s*(\d+)\s*\)""").find(onclick)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: idx

            if (explicitIndex in indexedScriptUrls.indices) {
                pairedScriptIndices.add(explicitIndex)
                normalizeAndAdd(indexedScriptUrls[explicitIndex], labelText, labelText, isDownload = false)
            } else {
                val href = el.attr("href").trim()
                if (href.isNotEmpty() && href != "#") {
                    normalizeAndAdd(href, labelText, labelText, isDownload = false)
                }
            }
        }

        // Add any remaining script URLs that weren't paired to a specific tab index
        indexedScriptUrls.forEachIndexed { idx, url ->
            if (idx !in pairedScriptIndices) {
                normalizeAndAdd(url, "Server", "FHD", isDownload = false)
            }
        }

        // Layer 4: Download Tables (div.quality-list, div.download-list, ul.quality-list, .download-links)
        val qualityContainers = doc.select("div.quality-list, div.download-list, ul.quality-list, div.episode-download-container > div")
        for (block in qualityContainers) {
            val headingText = block.selectFirst("li:first-child, h3, h4, span.quality, .quality-title, strong")?.text()
                ?: block.text()
            val badge = normalizeQualityBadge(headingText)
            block.select("a[href], a[data-url], a[data-href]").forEach { a ->
                val rawHref = a.attr("data-url").trim()
                    .ifEmpty { a.attr("data-href").trim() }
                    .ifEmpty { a.attr("href").trim() }
                val aLabel = a.text().trim().ifEmpty { "Download" }
                normalizeAndAdd(rawHref, aLabel, badge, isDownload = true)
            }
        }

        return results.values.toList()
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
        if (srcResp.code !in 200..299) return null

        val gateUrl = "$mainUrl/watch/stream-gate/$token"
        val gateHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Upgrade-Insecure-Requests" to "1"
        )
        val gateResp = app.get(
            gateUrl,
            headers = gateHeaders,
            cookies = sessionCookies,
            allowRedirects = false
        )
        if (gateResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(gateResp.cookies)
        }

        val location = gateResp.headers["location"]
            ?: gateResp.headers["Location"]
            ?: Regex("""url=['"]?([^'">\s]+)""").find(gateResp.text)?.groupValues?.getOrNull(1)
            ?: Regex("""window\.location\.replace\(['"]([^'"]+)['"]\)""").find(gateResp.text)?.groupValues?.getOrNull(1)

        return location?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveDownloadToken(
        token: String,
        watchUrl: String,
        csrfToken: String,
        sessionCookies: MutableMap<String, String>
    ): String? {
        val dlApiUrl = "$mainUrl/watch/download-source/$token"
        val srcResp = app.post(
            dlApiUrl,
            headers = ajaxHeaders(watchUrl, csrfToken),
            cookies = sessionCookies
        )
        if (srcResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(srcResp.cookies)
        }
        if (srcResp.code !in 200..299) return null

        val gateUrl = "$mainUrl/watch/download-gate/$token"
        val gateHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Upgrade-Insecure-Requests" to "1"
        )
        val gateResp = app.get(
            gateUrl,
            headers = gateHeaders,
            cookies = sessionCookies,
            allowRedirects = false
        )
        if (gateResp.cookies.isNotEmpty()) {
            sessionCookies.putAll(gateResp.cookies)
        }

        val location = gateResp.headers["location"]
            ?: gateResp.headers["Location"]
            ?: Regex("""url=['"]?([^'">\s]+)""").find(gateResp.text)?.groupValues?.getOrNull(1)

        return location?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun wrapExtractorCallback(
        tabLabel: String,
        embedUrl: String,
        qualityBadge: String,
        downstream: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit {
        val host = runCatching { URI(embedUrl).host ?: "" }.getOrDefault("")
        return { link ->
            val standardizedName = formatServerName(
                tabLabel = tabLabel,
                extractorName = link.name,
                host = host,
                qualityBadge = qualityBadge
            )
            val resolvedQuality = if (link.quality != Qualities.Unknown.value) {
                link.quality
            } else {
                getQualityFromLabel(qualityBadge.ifBlank { standardizedName })
            }
            downstream.invoke(
                kotlinx.coroutines.runBlocking {
                    newExtractorLink(
                        source = name,
                        name = standardizedName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = resolvedQuality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                }
            )
        }
    }

    private suspend fun dispatchExtractorUrl(
        candidate: ServerCandidate,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = when {
            candidate.url.startsWith("//") -> "https:${candidate.url}"
            else -> candidate.url.trim()
        }
        if (!isSafePublicUrl(cleanUrl)) return

        val wrappedCallback = wrapExtractorCallback(
            tabLabel = candidate.tabLabel,
            embedUrl = cleanUrl,
            qualityBadge = candidate.qualityBadge,
            downstream = callback
        )
        val displayTitle = formatServerName(
            tabLabel = candidate.tabLabel,
            extractorName = "",
            host = cleanUrl,
            qualityBadge = candidate.qualityBadge
        )

        when {
            cleanUrl.contains("mid.yonaplay.net", ignoreCase = true) ||
                cleanUrl.contains("yonaplay.net", ignoreCase = true) ||
                cleanUrl.contains("yonaplay.org", ignoreCase = true) -> {
                extractMidYonaPlay(cleanUrl, referer, subtitleCallback, callback)
            }
            cleanUrl.contains("dotplay.net", ignoreCase = true) -> {
                extractDotPlay(cleanUrl, referer, displayTitle, callback, candidate.qualityBadge)
            }
            cleanUrl.contains("videas.fr", ignoreCase = true) -> {
                extractVideas(cleanUrl, referer, displayTitle, callback, candidate.qualityBadge)
            }
            cleanUrl.contains("hgcloud.to", ignoreCase = true) ||
                cleanUrl.contains("hglink.to", ignoreCase = true) ||
                cleanUrl.contains("dhcplay.com", ignoreCase = true) ||
                cleanUrl.contains("hanerix.com", ignoreCase = true) ||
                cleanUrl.contains("audinifer.com", ignoreCase = true) ||
                cleanUrl.contains("vibuxer.com", ignoreCase = true) -> {
                extractHgCloud(cleanUrl, referer, displayTitle, subtitleCallback, wrappedCallback)
            }
            cleanUrl.endsWith(".mp4", ignoreCase = true) || cleanUrl.contains(".mp4?", ignoreCase = true) -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = displayTitle,
                        url = cleanUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromLabel(candidate.qualityBadge)
                    }
                )
            }
            cleanUrl.contains(".m3u8", ignoreCase = true) -> {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = cleanUrl,
                    referer = referer,
                    name = displayTitle
                ).forEach(wrappedCallback)
            }
            else -> {
                loadExtractor(cleanUrl, referer, subtitleCallback, wrappedCallback)
            }
        }
    }

    // ============================
    // MID.YONAPLAY.NET AES-256-GCM DECRYPTOR
    // ============================

    data class YonaInitResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("c") val code: String? = null,
        @JsonProperty("t") val token: String? = null,
        @JsonProperty("k") val pageKey: String? = null
    )

    data class YonaSourcesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("qualities") val qualities: Map<String, YonaQualityGroup>? = null
    )

    data class YonaQualityGroup(
        @JsonProperty("servers") val servers: List<YonaServerItem>? = null
    )

    data class YonaServerItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("token") val token: String? = null
    )

    data class YonaApiResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("d") val encryptedData: String? = null,
        @JsonProperty("r") val sendReferer: Int? = null
    )

    private suspend fun extractMidYonaPlay(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hostBase = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: "https://mid.yonaplay.net"

        val embedHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val embedResp = app.get(embedUrl, headers = embedHeaders)
        val yonaCookies = embedResp.cookies.toMutableMap()

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to hostBase,
            "Referer" to embedUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        val initResp = app.post(
            "$hostBase/api/init-session.php",
            headers = apiHeaders,
            cookies = yonaCookies,
            json = emptyMap<String, String>()
        )
        if (initResp.cookies.isNotEmpty()) yonaCookies.putAll(initResp.cookies)

        val initData = tryParseJson<YonaInitResponse>(initResp.text) ?: return
        val code = initData.code?.takeIf { it.isNotBlank() } ?: return
        val pageKey = initData.pageKey?.takeIf { it.isNotBlank() } ?: return

        val sourcesResp = app.post(
            "$hostBase/api/sources.php",
            headers = apiHeaders,
            cookies = yonaCookies,
            json = mapOf("code" to code)
        )
        if (sourcesResp.cookies.isNotEmpty()) yonaCookies.putAll(sourcesResp.cookies)

        val sourcesData = tryParseJson<YonaSourcesResponse>(sourcesResp.text) ?: return
        val qualitiesMap = sourcesData.qualities ?: return

        val qualityOrder = listOf("4k", "fhd", "hd", "sd")
        val orderedKeys = qualityOrder.filter { qualitiesMap.containsKey(it) } +
            qualitiesMap.keys.filter { it !in qualityOrder }

        for (qKey in orderedKeys) {
            val badge = normalizeQualityBadge(qKey)
            val servers = qualitiesMap[qKey]?.servers.orEmpty()
            for (srv in servers) {
                val srvToken = srv.token?.takeIf { it.isNotBlank() } ?: continue
                runCatching {
                    val apiResp = app.post(
                        "$hostBase/api/api.php",
                        headers = apiHeaders,
                        cookies = yonaCookies,
                        json = mapOf("code" to code, "token" to srvToken, "key" to pageKey)
                    )
                    if (apiResp.cookies.isNotEmpty()) yonaCookies.putAll(apiResp.cookies)

                    val parsed = tryParseJson<YonaApiResponse>(apiResp.text) ?: return@runCatching
                    val encryptedBase64 = parsed.encryptedData?.takeIf { it.isNotBlank() } ?: return@runCatching
                    val decryptedUrl = decryptYonaPlayPayload(encryptedBase64, pageKey)
                        ?.trim()
                        ?.takeIf { isSafePublicUrl(it) }
                        ?: return@runCatching

                    val label = formatServerName(
                        tabLabel = "yonaplay ${srv.name ?: "DotPlay"}",
                        extractorName = srv.name ?: "DotPlay",
                        host = decryptedUrl,
                        qualityBadge = badge
                    )
                    if (decryptedUrl.contains("dotplay.net", ignoreCase = true)) {
                        extractDotPlay(decryptedUrl, "$hostBase/", label, callback, badge)
                    } else {
                        val yonaWrapped = wrapExtractorCallback(
                            tabLabel = "yonaplay ${srv.name ?: ""}",
                            embedUrl = decryptedUrl,
                            qualityBadge = badge,
                            downstream = callback
                        )
                        loadExtractor(decryptedUrl, "$hostBase/", subtitleCallback, yonaWrapped)
                    }
                }
            }
        }
    }

    private fun decryptYonaPlayPayload(encryptedBase64: String, pageKey: String): String? {
        return runCatching {
            val raw = decodeBase64Bytes(encryptedBase64) ?: return null
            if (raw.size <= 28) return null

            val iv = raw.copyOfRange(0, 12)
            val tag = raw.copyOfRange(12, 28)
            val ciphertext = raw.copyOfRange(28, raw.size)
            val combined = ciphertext + tag

            val keyBytes = MessageDigest.getInstance("SHA-256").digest(pageKey.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(combined)
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    // ============================
    // DOTPLAY.NET DIRECT MP4 EXTRACTOR
    // ============================

    data class DotPlayResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("video_url") val videoUrl: String? = null,
        @JsonProperty("file") val file: String? = null
    )

    private suspend fun extractDotPlay(
        dotPlayUrl: String,
        referer: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit,
        qualityHint: String? = null
    ) {
        val code = Regex("""/(?:embed|player\d*)/([^/?&#]+)""").find(dotPlayUrl)?.groupValues?.getOrNull(1) ?: return
        val hostBase = Regex("""^(https?://[^/]+)""").find(dotPlayUrl)?.groupValues?.getOrNull(1)
            ?: "https://dotplay.net"

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
            "Accept" to "application/json",
            "Referer" to dotPlayUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        val apiResp = app.get(
            "$hostBase/api.php?code=$code",
            headers = apiHeaders,
            cookies = pageResp.cookies
        )

        val parsed = tryParseJson<DotPlayResponse>(apiResp.text) ?: return
        val encodedFile = (parsed.videoUrl ?: parsed.file)?.trim()?.ifEmpty { null } ?: return

        val decodedUrl = decodeBase64String(encodedFile)
            ?.substringBefore("|")
            ?.trim()
            ?.takeIf { isSafePublicUrl(it) }
            ?: return

        val badge = normalizeQualityBadge(qualityHint ?: serverLabel)
        val cleanLabel = formatServerName(serverLabel, "DotPlay", "dotplay.net", badge)
        val qualityValue = getQualityFromLabel(badge)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = cleanLabel,
                url = decodedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$hostBase/"
                this.quality = qualityValue
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    // ============================
    // APP.VIDEAS.FR DIRECT HLS M3U8 EXTRACTOR
    // ============================

    data class VideasEmbedConfig(
        @JsonProperty("apiBaseUrl") val apiBaseUrl: String? = null,
        @JsonProperty("embedId") val embedId: String? = null,
        @JsonProperty("opening") val opening: String? = null
    )

    data class VideasEmbedResponse(
        @JsonProperty("uid") val uid: String? = null,
        @JsonProperty("playback_token") val playbackToken: String? = null,
        @JsonProperty("session_id") val sessionId: String? = null,
        @JsonProperty("items") val items: List<VideasItem>? = null
    )

    data class VideasItem(
        @JsonProperty("asset") val asset: VideasAsset? = null
    )

    data class VideasAsset(
        @JsonProperty("uid") val uid: String? = null
    )

    data class VideasPlaybackInfo(
        @JsonProperty("hls_url") val hlsUrl: VideasHlsUrl? = null
    )

    data class VideasHlsUrl(
        @JsonProperty("url") val url: String? = null
    )

    private suspend fun extractVideas(
        embedUrl: String,
        referer: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit,
        qualityHint: String? = null
    ) {
        val hostBase = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: "https://app.videas.fr"

        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        val pageResp = app.get(embedUrl, headers = pageHeaders)
        val configJson = pageResp.document.selectFirst("script#videas-embed-config")?.data()?.trim()
            ?: return

        val config = tryParseJson<VideasEmbedConfig>(configJson) ?: return
        val embedId = config.embedId?.takeIf { it.isNotBlank() } ?: return
        val opening = config.opening?.takeIf { it.isNotBlank() } ?: return

        val visitorResp = app.get(
            "$hostBase/api/visitor/embed/$embedId/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Referer" to embedUrl,
                "X-Embed-Opening" to opening
            ),
            cookies = pageResp.cookies
        )
        val visitorData = tryParseJson<VideasEmbedResponse>(visitorResp.text) ?: return
        val playbackToken = visitorData.playbackToken?.takeIf { it.isNotBlank() } ?: return
        val assetId = visitorData.items?.firstOrNull()?.asset?.uid ?: visitorData.uid ?: embedId
        val sessionId = visitorData.sessionId ?: ""

        val infoResp = app.post(
            "$hostBase/api/visitor/playback/info/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Origin" to hostBase,
                "Referer" to embedUrl
            ),
            cookies = pageResp.cookies,
            json = mapOf(
                "asset_id" to assetId,
                "playback_token" to playbackToken,
                "session_id" to sessionId
            )
        )
        val playbackInfo = tryParseJson<VideasPlaybackInfo>(infoResp.text) ?: return
        val rawHls = playbackInfo.hlsUrl?.url?.trim()?.ifEmpty { null } ?: return
        val fullHlsUrl = if (rawHls.startsWith("http")) rawHls else "$hostBase$rawHls"

        val badge = normalizeQualityBadge(qualityHint ?: serverLabel)
        val cleanLabel = formatServerName("videas", "Videas", "videas.fr", badge)

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = fullHlsUrl,
            referer = "$hostBase/",
            headers = mapOf("User-Agent" to USER_AGENT, "Origin" to hostBase),
            name = cleanLabel
        ).forEach(callback)
    }

    // ============================
    // HGCLOUD.TO / HANERIX.COM (VIDHIDE / STREAMHG) DIRECT HLS EXTRACTOR
    // ============================

    private suspend fun extractHgCloud(
        embedUrl: String,
        referer: String,
        displayTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pathAndQuery = Regex("""https?://[^/]+(/.*)$""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return
        val mirrorDomains = listOf("https://hanerix.com", "https://vibuxer.com", "https://audinifer.com")

        for (mirrorBase in mirrorDomains) {
            val targetUrl = "$mirrorBase$pathAndQuery"
            val resp = runCatching {
                app.get(
                    targetUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Referer" to "https://hgcloud.to/"
                    )
                )
            }.getOrNull() ?: continue

            val packedScript = resp.document.select("script").map { it.data() }
                .firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
                ?: continue

            val unpacked = JsUnpacker(packedScript).unpack() ?: continue
            val hls4 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.getOrNull(1)
            val hls2 = Regex(""""hls2"\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.getOrNull(1)
            val rawStream = hls4?.takeIf { it.isNotBlank() } ?: hls2?.takeIf { it.isNotBlank() } ?: continue
            val fullM3u8 = if (rawStream.startsWith("http")) rawStream else "$mirrorBase$rawStream"

            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = fullM3u8,
                referer = "$mirrorBase/",
                headers = mapOf("User-Agent" to USER_AGENT, "Origin" to mirrorBase),
                name = displayTitle
            ).forEach(callback)
            return
        }

        // Fallback to CloudStream built-in extractor if mirror unpack didn't match
        loadExtractor(embedUrl, referer, subtitleCallback, callback)
    }

    private fun getQualityFromLabel(label: String): Int {
        val upper = label.uppercase()
        return when {
            upper.contains("4K") || upper.contains("2160") -> Qualities.P2160.value
            upper.contains("FHD") || upper.contains("1080") -> Qualities.P1080.value
            upper.contains("HD") || upper.contains("720") -> Qualities.P720.value
            upper.contains("SD") || upper.contains("480") -> Qualities.P480.value
            upper.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }
}
