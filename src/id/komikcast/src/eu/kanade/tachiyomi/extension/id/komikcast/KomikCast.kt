package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class KomikCast : ParsedHttpSource() {

    override val name = "KomikCast"
    override val baseUrl = "https://komikcast.cz" // Ganti sesuai domain terbaru
    override val lang = "id"
    override val supportsLatest = true
        override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun popularMangaRequest(page: Int) = customPageRequest(page, "orderby", "popular")
    override fun latestUpdatesRequest(page: Int) = customPageRequest(page, "sortby", "update")

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""

        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }

    override fun searchMangaSelector() = "div.list-update_item"

        override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        title = element.selectFirst("h3.title")!!.ownText()
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Mendapatkan daftar chapter dari sebuah manga
    override fun chapterListSelector() = "div#chapterlist ul li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.select("a").attr("href"))
        chapter.name = element.select("a span.chapternum").text()
        chapter.date_upload = parseDate(element.select("span.chapterdate").text())
        return chapter
    }

    // Parsing detail manga
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select("h1.entry-title").text()
        manga.author = document.select("div.author a").text()
        manga.artist = document.select("div.artist a").text()
        manga.genre = document.select("div.genres a").joinToString { it.text() }
        manga.description = document.select("div.desc p").text()
        manga.thumbnail_url = document.select("div.thumb img").attr("src")
        manga.status = parseStatus(document.select("div.status").text())
        return manga
    }

    private fun parseStatus(status: String): Int {
        return when {
            status.contains("Ongoing", true) -> SManga.ONGOING
            status.contains("Completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // Parsing halaman konten chapter
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#readerarea img").mapIndexed { i, element ->
            Page(i, "", element.attr("data-src"))
        }
    }

    override fun imageUrlParse(document: Document): String {
        return document.select("div#readerarea img").attr("data-src")
    }

    // Parser tanggal untuk daftar chapter
    private fun parseDate(date: String): Long {
        return try {
            SimpleDateFormat("MMMM dd, yyyy", Locale.US).parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun getFilterList(): FilterList = FilterList()
}