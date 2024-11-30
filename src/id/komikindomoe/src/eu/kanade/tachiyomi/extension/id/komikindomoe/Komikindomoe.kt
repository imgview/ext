package eu.kanade.tachiyomi.extension.id.komikindomoe

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Komikindomoe : ParsedHttpSource() {
    override val name = "Komikindo Moe"
    override val baseUrl = "https://komikindo.moe"
    override val lang = "id"
    override val supportsLatest = true
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers)
    }

    override fun popularMangaSelector() = "div.listupd div.utao div.uta"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a.series").attr("href"))
        manga.title = element.select("div.luf h3").text()
        manga.thumbnail_url = element.select("div.imgu img").imgAttr()
        return manga
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl".toHttpUrl().newBuilder()
        url.addQueryParameter("s", query)
        return GET(url.build(), headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infox").first()!!
        val descElement = document.select("div.entry-content.entry-content-single").first()!!
        val manga = SManga.create()
        manga.title = document.select("h1.entry-title").text()
        manga.author = infoElement.select("span:contains(Pengarang) a").text()
        manga.artist = manga.author // Tidak ada data artis
        manga.genre = infoElement.select("span.mgen a").joinToString(", ") { it.text() }
        manga.status = parseStatus(infoElement.select("span:contains(Status)").text())
        manga.description = descElement.select("p").text()
        manga.thumbnail_url = document.select("div.thumb img").imgAttr()
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.lowercase().contains("ongoing") -> SManga.ONGOING
        element.lowercase().contains("completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.bixbox ul.cl li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()!!
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select("span.dt").first()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toInt()
            when {
                "detik" in date -> Calendar.getInstance().apply {
                    add(Calendar.SECOND, -value)
                }.timeInMillis
                "menit" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, -value)
                }.timeInMillis
                "jam" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, -value)
                }.timeInMillis
                "hari" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, -value)
                }.timeInMillis
                "minggu" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, -value * 7)
                }.timeInMillis
                "bulan" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, -value)
                }.timeInMillis
                "tahun" in date -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, -value)
                }.timeInMillis
                else -> {
                    0L
                }
            }
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    chapter.chapter_number = it.groups[1]?.value!!.toFloat()
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("div.reading-content img").forEachIndexed { i, element ->
            val url = element.imgAttr()
            if (url.isNotEmpty()) {
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Filters are currently disabled."),
        Filter.Separator()
    )

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun Elements.imgAttr(): String = this.first()!!.imgAttr()
}