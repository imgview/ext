package eu.kanade.tachiyomi.extension.id.kace

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kace : ParsedHttpSource() {

    override val name = "Kace"
    override val baseUrl = "https://bacakomik.co"
    override val lang = "id"
    override val supportsLatest = true

    // Pencarian manga
    override fun searchMangaSelector() = "div.bs"

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("a").attr("title")
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.thumbnail_url = element.select("img").attr("data-src")
        return manga
    }

    override fun searchMangaRequest(page: Int, query: String): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    // Manga terbaru
    override fun latestUpdatesSelector() = "div.bs"

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("a").attr("title")
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.thumbnail_url = element.select("img").attr("data-src")
        return manga
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/latest/page/$page", headers)
    }

    // Detail manga
    override fun mangaDetailsParse(document: Document): SManga {
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
    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = element.select("a").text()
        chapter.setUrlWithoutDomain(element.select("a").attr("href"))
        chapter.date_upload = System.currentTimeMillis() // Ubah sesuai kebutuhan
        return chapter
    }

    // Halaman gambar
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.page-break img")
            .mapIndexed { i, element -> Page(i, "", element.attr("data-src")) }
    }

    override fun imageUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }
}
