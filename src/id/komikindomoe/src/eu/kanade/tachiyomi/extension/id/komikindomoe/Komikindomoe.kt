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
import java.text.SimpleDateFormat
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.Locale

class Komikindomoe : ParsedHttpSource() {
    override val name = "Komikindo.moe"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

override fun popularMangaRequest(page: Int): Request {
    return GET("$baseUrl/page/$page", headers)
}

// Request untuk Update Terbaru
override fun latestUpdatesRequest(page: Int): Request {
    return GET("$baseUrl/page/$page", headers)
}

// Selector untuk Manga Populer dan Update Terbaru
// Selector untuk Popular Manga
override fun popularMangaSelector() = "div.flex.overflow-hidden"

// Selector untuk Latest Updates (sama dengan Popular Manga)
override fun latestUpdatesSelector() = popularMangaSelector()

// Selector untuk Pencarian Manga
override fun searchMangaSelector() = popularMangaSelector()

// Fungsi untuk Mengambil Data Manga dari Elemen
override fun popularMangaFromElement(element: Element): SManga {
    println("Debug Popular Manga Element: ${element.outerHtml()}")
    return searchMangaFromElement(element)
}

override fun latestUpdatesFromElement(element: Element): SManga {
    println("Debug Latest Updates Element: ${element.outerHtml()}")
    return searchMangaFromElement(element)
}

override fun searchMangaFromElement(element: Element): SManga {
    println("Debug Search Manga Element: ${element.outerHtml()}")
    
    val manga = SManga.create()
    val linkElement = element.selectFirst("a")
    
    if (linkElement == null) {
        println("Error: Link element not found!")
    } else {
        println("Debug Link Element: ${linkElement.outerHtml()}")
        manga.setUrlWithoutDomain(linkElement.attr("href")) // Mengambil URL manga
    }

    val titleElement = element.selectFirst("h2, h2.hidden.md\\:block")
    if (titleElement == null) {
        println("Error: Title element not found!")
    } else {
        println("Debug Title Element: ${titleElement.outerHtml()}")
        manga.title = titleElement.text()
    }

    val thumbnailElement = element.selectFirst("img")
    if (thumbnailElement == null) {
        println("Error: Thumbnail element not found!")
    } else {
        println("Debug Thumbnail Element: ${thumbnailElement.outerHtml()}")
        manga.thumbnail_url = thumbnailElement.attr("data-src").orEmpty()
    }

    return manga
}

// Request untuk Pencarian Manga
override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
    val url = "$baseUrl/search?s=$query&page=$page".toHttpUrl().newBuilder().build()
    println("Debug Search Manga Request URL: $url")
    return GET(url, headers)
}

// Pagination Selector untuk Manga Populer dan Update Terbaru
override fun popularMangaNextPageSelector() = "a.next.page-numbers"
override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

// Pagination Selector untuk Pencarian Manga
override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.wd-full, div.postbody").first()!!
        val descElement = document.select("div.entry-content.entry-content-single").first()!!
        val manga = SManga.create()
        manga.title = document.select("div.thumb img").attr("title")
        manga.author = infoElement.select("b:contains(Author) + span").text()
        manga.artist = infoElement.select("b:contains(Artist) + span").text()
        val genres = mutableListOf<String>()
        val typeManga = mutableListOf<String>() // List untuk type manga (dari SeriesTypeSelector)

// Mengambil Genre dari span.mgen a
infoElement.select("span.mgen a").forEach { element ->
    genres.add(element.text())
}

infoElement.select(".imptdt a").forEach { element ->
    typeManga.add(element.text())
}

manga.genre = (genres + typeManga).joinToString(", ")
        manga.status = parseStatus(infoElement.select(".imptdt i").text())
        manga.description = descElement.select("p").text()
                // Add alternative name to manga description
    val altName = document.selectFirst("b:contains(Alternative Titles) + span")?.text().takeIf { it.isNullOrBlank().not() }
altName?.let {
    manga.description = manga.description + "\n\nAlternative Name: $it"
}
        manga.thumbnail_url = document.select("div.thumb img").imgAttr()
    return manga
}

    private fun parseStatus(element: String): Int = when {
        element.lowercase().contains("ongoing") -> SManga.ONGOING
        element.lowercase().contains("completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div#chapterlist ul > li"

override fun chapterFromElement(element: Element): SChapter {
    val urlElement = element.select("div.eph-num a").first()!!
    val chapter = SChapter.create()
    chapter.setUrlWithoutDomain(urlElement.attr("href"))
    chapter.name = urlElement.select("span.chapternum").text()
    chapter.date_upload = element.select("span.chapterdate").text()?.let { parseChapterDate(it) } ?: 0L
    return chapter
}

private fun parseChapterDate(date: String): Long {
    return try {
        dateFormat.parse(date)?.time ?: 0L
    } catch (e: Exception) {
        0L
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
        document.select("div#readerarea img").forEachIndexed { i, element ->
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
        Filter.Header("Filter sengaja kosong, di web mereka gak ada filter juga"),
        Filter.Separator()
    )

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun Elements.imgAttr(): String = this.first()!!.imgAttr()
}