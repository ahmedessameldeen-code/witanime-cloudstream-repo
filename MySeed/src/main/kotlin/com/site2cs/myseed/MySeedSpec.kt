package com.site2cs.myseed

import com.lagradost.cloudstream3.TvType
import com.site2cs.myseed.engine.*

object MySeedSpec : SiteSpec() {
    override val name = "‎ماي سيد - MySeed"
    override val baseUrl = "https://m.myseed.tv"
    override val language = "en"
    override val tvTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val archetype = Archetype.GENERIC

    override val home = listOf(
        HomeTab("Latest", "/main/")
    )

    override val search = SearchCfg(
        path = "?s={query}",
        method = "GET",
        body = "",
    )

    override val selectors = Selectors(
        cards = "ul.movie__blocks__ul li",
        cardLink = "a[href^='http']::attr(href)",
        cardTitle = "img::attr(alt)",
        cardPoster = "img::attr(data-src)",
        nextPage = "a.next::attr(href)",
        detailTitle = "h1::text",
        detailPoster = "meta[property='og:image']::attr(content)",
        detailPlot = "meta[property='og:description']::attr(content)",
        episodes = "li a",
        episodeLink = "a::attr(href)",
        episodeTitle = "a::text",
        servers = "iframe::attr(src)",
    )

    override val headers = mapOf(
        "User-Agent" to SiteSpec.UA,
    )
}
