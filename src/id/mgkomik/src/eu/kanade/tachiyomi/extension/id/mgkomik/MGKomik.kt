package eu.kanade.tachiyomi.extension.id.mgkomik

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://mgkomik.id",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false

    override val mangaSubString = "komik"

    override fun headersBuilder() = super.headersBuilder().apply {
    add("User-Agent", "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL Build/QD1A.190805.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
    add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
    add("Accept-Encoding", "gzip, deflate, br")
    add("Accept-Language", "en-US,en;q=0.9")
    add("Referer", "https://mgkomik.id/")
    add("Connection", "keep-alive")
    add("Upgrade-Insecure-Requests", "1")
    add("Sec-Fetch-Dest", "document")
    add("Sec-Fetch-Mode", "navigate")
    add("Sec-Fetch-Site", "same-origin")
    add("Sec-Fetch-User", "?1")
    add("Cache-Control", "max-age=0")
}

override val client = network.cloudflareClient.newBuilder()
    .addInterceptor { chain ->
        val request = chain.request()
        
        // Modifikasi untuk menghapus "X-Requested-With"
        val headers = request.headers.newBuilder().apply {
            removeAll("X-Requested-With")
        }.build()

        chain.proceed(request.newBuilder().headers(headers).build())
    }
    .rateLimit(5, 10)
    .build()

    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    override fun searchMangaNextPageSelector() = "a.page.larger"

    override fun latestUpdatesRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)
        }

    override val chapterUrlSuffix = ""

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('.')
        return List(length) { charPool.random() }.joinToString("")
    }
}