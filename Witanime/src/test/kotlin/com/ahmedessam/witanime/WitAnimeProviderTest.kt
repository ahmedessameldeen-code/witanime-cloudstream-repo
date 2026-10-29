package com.ahmedessam.witanime

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WitAnimeProviderTest {

    @Test
    fun `isSafePublicUrl validates public hosts offline without DNS resolution`() {
        // Unresolvable / ISP-blocked mirror domain must still pass offline validation
        assertTrue(WitAnimeProvider.isSafePublicUrl("https://mid.yonaplay.net/embed/f0076ea63d6b631f/"))
        assertTrue(WitAnimeProvider.isSafePublicUrl("https://app.videas.fr/embed/media/2ba0303c-1331/"))
        assertTrue(WitAnimeProvider.isSafePublicUrl("https://unresolvable-anime-mirror-host.example.org/watch/1"))
        assertTrue(WitAnimeProvider.isSafePublicUrl("//ok.ru/videoembed/17209350294214"))

        // Private, loopback, link-local, and non-HTTP schemes must be rejected
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://localhost:8080/stream.m3u8"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://127.0.0.1/admin"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://10.0.0.1/video.mp4"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://192.168.1.50/video.mp4"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://172.16.0.5/video.mp4"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://169.254.169.254/latest/meta-data"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("http://[::1]/stream"))
        assertFalse(WitAnimeProvider.isSafePublicUrl("javascript:alert(1)"))
        assertFalse(WitAnimeProvider.isSafePublicUrl(""))
    }

    @Test
    fun `formatServerName normalizes lowercase and DL tags into standardized uppercase badges`() {
        assertEquals(
            "Videas [FHD]",
            WitAnimeProvider.formatServerName("videas", "", "app.videas.fr", "fhd")
        )
        assertEquals(
            "Mp4Upload [FHD]",
            WitAnimeProvider.formatServerName("mp4upload DL [FHD]", "mp4upload", "www.mp4upload.com", "fhd")
        )
        assertEquals(
            "OK [HD]",
            WitAnimeProvider.formatServerName("ok", "Odnoklassniki", "ok.ru", "hd")
        )
        assertEquals(
            "YonaPlay (DotPlay - FHD)",
            WitAnimeProvider.formatServerName("yonaplay", "Dotplay", "dotplay.net", "fhd")
        )
        assertEquals(
            "SoraPlay (Multi - FHD)",
            WitAnimeProvider.formatServerName("soraplay", "", "soraplay.net", "1080p")
        )
        assertEquals(
            "VidHide [FHD]",
            WitAnimeProvider.formatServerName("hgcloud", "Hgcloud", "hgcloud.to", "FHD")
        )
        assertEquals(
            "StreamWish [FHD]",
            WitAnimeProvider.formatServerName("streamwish", "Streamwish", "sfastwish.com", "FHD")
        )
        assertEquals(
            "DoodStream [HD]",
            WitAnimeProvider.formatServerName("dood", "", "d000d.com", "720p")
        )
        assertEquals(
            "MediaFire [FHD]",
            WitAnimeProvider.formatServerName("mediafire", "", "mediafire.com", "FHD")
        )
    }

    @Test
    fun `extractLegacyCandidatesFromDocument extracts all 4 fallback layers when sources API returns 404`() {
        val provider = WitAnimeProvider()
        val b64Videas = Base64.getEncoder()
            .encodeToString("https://app.videas.fr/embed/media/2ba0303c-1331/".toByteArray(Charsets.UTF_8))
        val b64Mp4Upload = Base64.getEncoder()
            .encodeToString("https://www.mp4upload.com/embed-abc12345.html".toByteArray(Charsets.UTF_8))

        val xorKey = "secretKey"
        val rawOkUrl = "https://ok.ru/videoembed/987654321"
        val xorBytes = rawOkUrl.toByteArray(Charsets.UTF_8).mapIndexed { i, b ->
            (b.toInt() xor xorKey[i % xorKey.length].code).toByte()
        }.toByteArray()
        val xorB64 = Base64.getEncoder().encodeToString(xorBytes)

        val html = """
            <html>
            <body>
                <div id="episode-watch-container">
                    <iframe data-src="//mid.yonaplay.net/embed/f0076ea63d6b631f/"></iframe>
                </div>
                <ul id="episode-servers">
                    <li><a data-url="$b64Videas">videas - FHD</a></li>
                    <li><a data-id="0" onclick="selectServer(0)">ok - HD</a></li>
                </ul>
                <script>
                    var _s = "$xorKey";
                    var _d = ["$xorB64"];
                </script>
                <div class="quality-list">
                    <li>FHD 1080p</li>
                    <a data-url="$b64Mp4Upload">mp4upload</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://witanime.site/watch/bleach/1")
        val candidates = provider.extractLegacyCandidatesFromDocument(doc, html)
        val urls = candidates.map { it.url }

        assertTrue(urls.contains("https://mid.yonaplay.net/embed/f0076ea63d6b631f/"))
        assertTrue(urls.contains("https://app.videas.fr/embed/media/2ba0303c-1331/"))
        assertTrue(urls.contains("https://ok.ru/videoembed/987654321"))
        assertTrue(urls.contains("https://www.mp4upload.com/embed-abc12345.html"))

        val okCandidate = candidates.first { it.url == "https://ok.ru/videoembed/987654321" }
        assertEquals("HD", okCandidate.qualityBadge)
        assertEquals(
            "OK [HD]",
            WitAnimeProvider.formatServerName(okCandidate.tabLabel, "", okCandidate.url, okCandidate.qualityBadge)
        )

        val mp4Candidate = candidates.first { it.url == "https://www.mp4upload.com/embed-abc12345.html" }
        assertTrue(mp4Candidate.isDownload)
        assertEquals("FHD", mp4Candidate.qualityBadge)
        assertEquals(
            "Mp4Upload [FHD]",
            WitAnimeProvider.formatServerName(mp4Candidate.tabLabel, "", mp4Candidate.url, mp4Candidate.qualityBadge)
        )
    }
}
