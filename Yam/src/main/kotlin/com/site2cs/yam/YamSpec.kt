package com.site2cs.yam

import com.lagradost.cloudstream3.TvType
import com.site2cs.yam.engine.*

object YamSpec : SiteSpec() {
    override val name = "اهواك تي في"
    override val baseUrl = "https://yam.ahwaktv.net"
    override val language = "en"
    override val tvTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val archetype = Archetype.GENERIC

    override val home = listOf(
        HomeTab("Latest", "/"),
        HomeTab("مسلسلات", "/moslslat.php"),
        HomeTab("جديد الأفلام", "/newvideos.php")
    )

    override val search = SearchCfg(
        path = "https://yam.ahwaktv.net/search.php?keywords={query}",
        method = "GET",
        body = "",
    )

    override val selectors = Selectors(
        cards = "ul.row li",
        cardLink = "a[href*='watch']::attr(href)",
        cardTitle = "img::attr(alt)",
        cardPoster = "img::attr(src)",
        detailTitle = "h1::text",
        detailPoster = "meta[property='og:image']::attr(content)",
        detailPlot = "meta[property='og:description']::attr(content)",
        episodes = "ul.s a",
        episodeLink = "a::attr(href)",
        episodeTitle = "a::text",
    )

    override val headers = mapOf(
        "User-Agent" to SiteSpec.UA,
    )
}
