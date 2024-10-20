package eu.kanade.tachiyomi.extension.id.bacakomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
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
import java.util.Locale

class BacaKomik : ParsedHttpSource() {
    override val name = "BacaKomik"
    override val baseUrl = "https://apkomik.cc"
    override val lang = "id"
    override val supportsLatest = true
    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))

    override val id = 4383360263234319058

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?page=$page&order=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?page=$page&order=update", headers)
    }

    override fun popularMangaSelector() = "div.bs"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        thumbnail_url = element.select("img").imgAttr()
        title = element.select("a").attr("title")
        setUrlWithoutDomain(element.select("a").attr("href"))
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val builtUrl = if (page == 1) "$baseUrl/manga/" else "$baseUrl/manga/?page=$page/?order="
        val url = builtUrl.toHttpUrl().newBuilder()
        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infoanime").first()!!
        val descElement = document.select("div.desc > .entry-content.entry-content-single").first()!!
        val manga = SManga.create()
        manga.title = document.select("#breadcrumbs li:last-child span").text()
        manga.author = document.select(".infox .spe span:contains(Author) :not(b)").text()
        manga.artist = document.select(".infox .spe span:contains(Artis) :not(b)").text()
        val genres = mutableListOf<String>()
        infoElement.select(".infox > .genre-info > a, .infox .spe span:contains(Jenis Komik) a").forEach { element ->
            val genre = element.text()
            genres.add(genre)
        }
        manga.genre = genres.joinToString(", ")
        manga.status = parseStatus(document.select(".infox .spe span:contains(Status)").text())
        manga.description = descElement.select("p").text().substringAfter("bercerita tentang ")
        manga.thumbnail_url = document.select(".thumb > img:nth-child(1)").imgAttr()
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.lowercase().contains("berjalan") -> SManga.ONGOING
        element.lowercase().contains("tamat") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapterlist li"

    override fun chapterFromElement(element: Element): SChapter {
    val urlElement = element.select(".eph-num a").firstOrNull() ?: return SChapter.create()
    
    val chapter = SChapter.create()
    chapter.setUrlWithoutDomain(urlElement.attr("href"))

    // Ambil hanya teks dari elemen .chapternum di dalam <a>
    val chapterNumElement = urlElement.select(".chapternum").firstOrNull()
    chapter.name = chapterNumElement?.text() ?: "Unknown Chapter" // Mengambil teks dari <span class="chapternum">

    // Mengambil tanggal dari elemen .chapterdate terpisah
    val dateElement = urlElement.select(".chapterdate").firstOrNull()
    chapter.date_upload = dateElement?.text()?.let { parseChapterDate(it) } ?: 0 // Mengambil tanggal jika ada

    return chapter
}

    // Hapus kode ini jika tidak ingin memparsing tanggal sama sekali
    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0
        } catch (_: Exception) {
            0L
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        if (basic.containsMatchIn(chapter.name)) {
            basic.find(chapter.name)?.let {
                chapter.chapter_number = it.groups[1]?.value!!.toFloat()
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
    // Mencari script yang mengandung data ts_reader
    val scriptContent = document.selectFirst("script:containsData(ts_reader)")?.data()
        ?: return super.pageListParse(document) // Menggunakan implementasi super jika tidak ditemukan

    // Mengambil string JSON dari script
    val jsonString = scriptContent.substringAfter("ts_reader.run(").substringBefore(");")

    // Decode JSON menjadi objek TSReader
    val tsReader = json.decodeFromString<TSReader>(jsonString)

    // Mendapatkan URL gambar dari sumber yang pertama
    val imageUrls = tsReader.sources.firstOrNull()?.images ?: return emptyList()

    // Menambahkan URL resize ke setiap URL gambar
    val resizedImageUrls = imageUrls.map { imageUrl ->
        "https://resize.sardo.work/?width=300&quality=75&imageUrl=$imageUrl"
    }

    // Membuat daftar halaman dari URL gambar yang telah di-resize
    return resizedImageUrls.mapIndexed { index, resizedImageUrl -> 
        Page(index + 1, document.location(), resizedImageUrl) // index + 1 untuk nomor halaman yang benar
    }
}

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun Elements.imgAttr(): String = this.first()!!.imgAttr()
}
