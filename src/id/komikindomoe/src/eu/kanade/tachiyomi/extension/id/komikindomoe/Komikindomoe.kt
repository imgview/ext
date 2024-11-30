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
import java.util.Locale

class Komikindomoe : ParsedHttpSource() {
    override val name = "Komikindo Moe"
    override val baseUrl = "https://komikindo.moe"
    override val lang = "id"
    override val supportsLatest = true

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

    override fun popularMangaNextPageSelector() = "div.hpage a.r"
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
        val url = "$baseUrl/page/$page".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infox").first()!!
        val descElement = document.select("div.entry-content.entry-content-single").first()!!
        val manga = SManga.create()
        manga.title = document.select("div.thumb img").attr("title")
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
        chapter.date_upload = 0
        return chapter
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