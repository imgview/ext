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

    // Selector untuk Manga Populer, Update Terbaru, dan Pencarian
    override fun popularMangaSelector() = "div.grid div.flex"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    // Request untuk Manga Populer
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
    }

    // Request untuk Update Terbaru
    override fun latestUpdatesRequest(page: Int): Request {
        return popularMangaRequest(page)
    }

    // Request untuk Pencarian Manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page"
        return GET(url, headers)
    }

    // Mengambil data Manga dari Elemen
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst("h2 a")
        manga.title = titleElement?.text()?.trim().orEmpty()
        manga.setUrlWithoutDomain(titleElement?.attr("href").orEmpty())
        manga.thumbnail_url = element.selectFirst("img")?.imgAttr().orEmpty()
        return manga
    }

    // Pagination Selector
    override fun popularMangaNextPageSelector() = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
    val infoElement = document.select("div.mt-4.flex.flex-col.gap-4").first()!!
    val descElement = document.select("div.mt-4.w-full > p").first()!!
    val manga = SManga.create()

    // Mengambil judul dari atribut title pada thumbnail
    manga.title = document.select("div.relative.flex-shrink-0 img").attr("alt")

    // Mengambil author dan artist
    manga.author = infoElement.select("p:contains(Author) + p").text()
    manga.artist = infoElement.select("p:contains(Artist) + p").text()

    // Menampung genre dan type manga
    val genres = mutableListOf<String>()
    val typeManga = mutableListOf<String>()

    // Mengambil Genre dari tautan dengan href mengarah ke genre
    document.select("div.mt-4.w-full a[href*='/tax/genre/']").forEach { element ->
        genres.add(element.text())
    }

    // Mengambil Type Manga dari elemen dengan class bg-red-800
    document.select("div.bg-red-800").forEach { element ->
        typeManga.add(element.text().trim())
    }

    // Kombinasi genre dan type manga, type selalu di akhir
    manga.genre = (genres.distinct() + typeManga).joinToString(", ")

    // Mengambil status dari elemen dengan class bg-green-800
    manga.status = parseStatus(infoElement.select("div.bg-green-800").text())

    // Mengambil deskripsi
    manga.description = descElement.text()

    // Menambahkan alternatif nama jika ada
    val altName = document.selectFirst("b:contains(Alternative Titles) + span")?.text()?.trim()
    altName?.takeIf { it.isNotEmpty() }?.let {
        manga.description += "\n\nAlternative Name: $it"
    }

    // Mengambil URL thumbnail
    manga.thumbnail_url = document.select("div.relative.flex-shrink-0 img").imgAttr()

    return manga
}

    private fun parseStatus(element: String): Int = when {
        element.lowercase().contains("ongoing") -> SManga.ONGOING
        element.lowercase().contains("completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.mt-4.flex.flex-col.gap-4 a"

override fun chapterFromElement(element: Element): SChapter {
    val chapter = SChapter.create()

    // Mengambil URL chapter
    val urlElement = element.selectFirst("a")!!
    chapter.setUrlWithoutDomain(urlElement.attr("href"))

    // Mengambil nama chapter
    chapter.name = urlElement.selectFirst("p")?.text().orEmpty()

    // Menggunakan teks tanggal apa adanya
    val dateText = urlElement.selectFirst("p.text-xs")?.text().orEmpty()
    chapter.date_upload = parseChapterDate(dateText)

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