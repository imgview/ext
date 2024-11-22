package eu.kanade.tachiyomi.extension.id.namaproject

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NamaProject : ParsedHttpSource() {

    override val name = "Nama Project"
    override val baseUrl = "https://contoh-manga.com"
    override val lang = "id"
    override val supportsLatest = true

    // Selector untuk pencarian manga
    override fun searchMangaSelector(): String = "div.search-item"

    // Parsing hasil pencarian
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("h3.title").text()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.thumbnail_url = element.select("img.cover").attr("abs:src")
        return manga
    }

    // URL untuk pencarian
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?q=$query&page=$page", headers)
    }

    // Selector untuk daftar terbaru
    override fun latestUpdatesSelector(): String = "div.latest-update-item"

    // Parsing daftar terbaru
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("h3.title").text()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.thumbnail_url = element.select("img.cover").attr("abs:src")
        return manga
    }

    // URL untuk daftar terbaru
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest?page=$page", headers)
    }

    // Selector untuk daftar chapter
    override fun chapterListSelector(): String = "ul.chapters li.chapter"

    // Parsing daftar chapter
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = element.select("a").text()
        chapter.setUrlWithoutDomain(element.select("a").attr("href"))
        chapter.date_upload = parseDate(element.select("span.date").text())
        return chapter
    }

    // Parsing halaman gambar
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.page img")
            .mapIndexed { i, element -> Page(i, "", element.attr("abs:src")) }
    }

    // Parsing detail manga
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select("h1.title").text()
        manga.author = document.select("span.author").text()
        manga.genre = document.select("div.genres").joinToString { it.text() }
        manga.description = document.select("div.description").text()
        manga.thumbnail_url = document.select("img.cover").attr("abs:src")
        return manga
    }
    
    private fun parseDate(date: String): Long {
        // Implementasi parsing tanggal
        return System.currentTimeMillis() // Contoh default
    }
}
