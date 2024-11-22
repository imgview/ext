package eu.kanade.tachiyomi.extension.id.kace

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.source.FilterList
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Kace : ParsedHttpSource() {

    override val name = "Kace"
    override val baseUrl = "https://contoh-manga.com"
    override val lang = "id"
    override val supportsLatest = true

    // Pencarian manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Menyusun URL pencarian dengan mempertimbangkan query dan halaman
        val url = "$baseUrl/search?q=$query&page=$page"
        return Request.Builder().url(url).headers(headers).build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.search-item").map { element ->
            parseMangaFromElement(element)
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("h3.title").text()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.thumbnail_url = element.select("img.cover").attr("abs:src")
        return manga
    }

    // Manga terbaru
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/latest?page=$page"
        return Request.Builder().url(url).headers(headers).build()
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.latest-update-item").map { element ->
            parseMangaFromElement(element)
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Detail manga
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl${manga.url}"
        return Request.Builder().url(url).headers(headers).build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()
        manga.title = document.select("h1").text()
        manga.author = document.select("div.author-content").text()
        manga.artist = document.select("div.artist-content").text()
        manga.genre = document.select("div.genres-content a").joinToString { it.text() }
        manga.description = document.select("div.description-summary").text()
        manga.thumbnail_url = document.select("div.thumb img").attr("data-src")
        return manga
    }

    // Daftar chapter
    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl${manga.url}"
        return Request.Builder().url(url).headers(headers).build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("ul.chapters li.chapter").map { element ->
            val chapter = SChapter.create()
            chapter.name = element.select("a").text()
            chapter.setUrlWithoutDomain(element.select("a").attr("href"))
            chapter.date_upload = System.currentTimeMillis() // Ubah sesuai kebutuhan
            chapter
        }
    }

    // Halaman gambar
    override fun pageListRequest(chapter: SChapter): Request {
        val url = "$baseUrl${chapter.url}"
        return Request.Builder().url(url).headers(headers).build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.page img").mapIndexed { i, element ->
            Page(i, "", element.attr("abs:src"))
        }
    }

    // Fungsi untuk parsing dokumen
    private fun Response.asJsoup(): Document {
        return Jsoup.parse(this.body?.string())
    }
}
