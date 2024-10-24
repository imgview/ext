package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class KomikCast : ParsedHttpSource() {
    override val name = "Komik Cast"
    override val baseUrl = "https://komikcast.cz"
    override val lang = "id"
    override val supportsLatest = true

    // Diambil dari MangaThemesia
    private val mangaUrlDirectory = "/daftar-komik"
    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl$mangaUrlDirectory/page/$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.bsx").map { element ->
            SManga.create().apply {
                title = element.select("a").attr("title")
                setUrlWithoutDomain(element.select("a").attr("href"))
                thumbnail_url = element.select("img").attr("src")
            }
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl$mangaUrlDirectory/page/$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        // Similar logic to popularMangaParse
        return popularMangaParse(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select("h1").text()
        manga.description = document.select("div.desc").text()
        manga.genre = document.select("span.genre").joinToString { it.text() }
        manga.status = parseStatus(document.select("span.status").text())
        manga.thumbnail_url = document.select("div.thumb img").attr("src")
        return manga
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div.chapters li").map { element ->
            SChapter.create().apply {
                name = element.select("a").text()
                setUrlWithoutDomain(element.select("a").attr("href"))
                date_upload = dateFormat.parse(element.select("span.date").text())?.time ?: 0L
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#readerarea img").mapIndexed { i, element ->
            Page(i, "", element.attr("src"))
        }
    }

    override fun imageUrlParse(document: Document): String {
        return document.select("div#readerarea img").attr("src")
    }

    private fun parseStatus(status: String): Int {
        return when (status.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}
