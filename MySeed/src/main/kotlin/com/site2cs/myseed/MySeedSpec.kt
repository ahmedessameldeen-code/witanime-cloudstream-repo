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
        HomeTab("Latest", "/main/"),
        HomeTab("أفلام نتفليكس", "/category/netflix/netflix-movies/"),
        HomeTab("أفلام أجنبية", "/category/foreign-movies/"),
        HomeTab("أفلام آسيوية", "/category/asian-movies/"),
        HomeTab("أفلام تركية", "/category/turkish-movies/"),
        HomeTab("أفلام عربية", "/category/arabic-movies/"),
        HomeTab("أفلام كلاسيكية", "/category/افلام-كلاسيكيه/"),
        HomeTab("أفلام مدبلجة", "/category/dubbed-movies/"),
        HomeTab("أفلام هندية", "/category/indian-movies/"),
        HomeTab("مسلسلات نتفليكس", "/category/netflix/netflix-series/"),
        HomeTab("مسلسلات أجنبية", "/category/foreign-series/"),
        HomeTab("مسلسلات تركية", "/category/turkish-series/"),
        HomeTab("مسلسلات عربية", "/category/arabic-series/"),
        HomeTab("مسلسلات كرتون", "/category/cartoon-series/"),
        HomeTab("مسلسلات كورية", "/category/مسلسلات-كوريه/"),
        HomeTab("مسلسلات مدبلجة", "/category/dubbed-series/"),
        HomeTab("مسلسلات مصرية", "/category/مسلسلات-مصريه/"),
        HomeTab("مسلسلات هندية", "/category/مسلسلات-هندية/"),
        HomeTab("رمضان", "/category/مسلسلات-رمضان/"),
        HomeTab("رمضان 2026", "/category/مسلسلات-رمضان/ramadan-series-2026/"),
        HomeTab("رمضان 2025", "/category/مسلسلات-رمضان/ramadan-series-2025/"),
        HomeTab("رمضان 2024", "/category/مسلسلات-رمضان/ramadan-series-2024/"),
        HomeTab("رمضان 2023", "/category/مسلسلات-رمضان/ramadan-series-2023/"),
        HomeTab("أفلام أنيميشن", "/category/animation-movies/"),
        HomeTab("مصارعة", "/category/wwe-shows/"),
        HomeTab("أغاني عربية", "/category/arabic-songs/"),
        HomeTab("مسلسلات رمضان 2022", "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2022/"),
        HomeTab("مسلسلات رمضان 2021", "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2021/"),
        HomeTab("مسلسلات رمضان 2020", "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2020-hd/"),
        HomeTab("مسلسلات رمضان 2019", "/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2019/"),
        HomeTab("برامج تلفزيونية", "/category/%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9/"),
        HomeTab("مسرحيات عربية", "/category/%d9%85%d8%b3%d8%b1%d8%ad%d9%8a%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/")
    )

    override val search = SearchCfg(
        path = "?s={query}",
        method = "GET",
        body = "",
    )

    override val selectors = Selectors(
        cards = "ul.movie__blocks__ul li, ul.movies__ul li",
        cardLink = "a.movie__block::attr(href)",
        cardTitle = "img::attr(alt)",
        cardPoster = "img::attr(src)",
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
