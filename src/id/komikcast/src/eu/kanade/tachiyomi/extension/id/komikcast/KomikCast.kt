package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KomikCast : ParsedHttpSource() {

    override val name = "KomikCast"
    override val baseUrl = "https://komikcast.site" // Ganti sesuai domain terbaru
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    // Mendapatkan daftar manga populer
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-komik/page/$page", headers)
    }

    override fun popularMangaSelector() = "div.list-update > div.bs"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.title = element.select("a > div.tt").text()
        manga.thumbnail_url = element.select("a > div.limit img").attr("src")
        return manga
    }

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"

    // Mendapatkan daftar manga terbaru
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/komik-terbaru/page/$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Filter manga berdasarkan pencarian
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
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
