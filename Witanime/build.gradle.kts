apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

version = 8

cloudstream {
    description = "WitAnime Arabic Anime Provider"
    authors = listOf("ahmedessameldeen-code")
    language = "ar"
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=witanime.site&sz=%size%"
}

dependencies {
    compileOnly("com.github.recloudstream.cloudstream:library:-SNAPSHOT")
    compileOnly("com.github.Blatzar:NiceHttp:0.4.11")
    compileOnly("org.jsoup:jsoup:1.18.3")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.13.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
