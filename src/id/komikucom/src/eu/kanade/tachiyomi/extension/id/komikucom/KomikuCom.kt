package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class KomikuCom : HttpSource() {

    override val name = "Komiku.com"
    override val baseUrl = "https://komiku.com"
    override val lang = "id"
    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))

    // Daftar manga menggunakan direktori /manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page", headers)
    }

    // Parsing daftar manga
    override fun popularMangaParse(response: Response): List<SManga> {
        val document = response.asJsoup()
        return document.select("div.kanan a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.select("h3").text()
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
    }

    // Parsing halaman chapter
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div#Daftar_Chapter a").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.text()
                date_upload = parseChapterDate(element.select("span").text())
            }
        }
    }

    // Parsing tanggal chapter
    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Parsing halaman manga
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img[src]").mapIndexed { i, element ->
            val imageUrl = element.attr("abs:src")
            val resizedImageUrl = "https://resize.sardo.work/?width=300&quality=75&imageUrl=$imageUrl"
            Page(i, "", resizedImageUrl)
        }
    }

    // Parsing detail manga
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()
        manga.title = document.select("h1").text()
        manga.author = document.select("span[itemprop=author]").text()
        manga.artist = document.select("span[itemprop=artist]").text()
        manga.description = document.select("div[itemprop=description]").text()
        manga.thumbnail_url = document.select("div[itemprop=image] img").attr("abs:src")
        return manga
    }

    // Pencarian manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/cari/?post_type=manga&s=$query", headers)
    }

    override fun searchMangaParse(response: Response): List<SManga> {
        val document = response.asJsoup()
        return document.select("div.kanan a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.select("h3").text()
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
    }

    // Konversi Response menjadi Jsoup Document
    private fun Response.asJsoup(): Document {
        return Jsoup.parse(body?.string())
    }
}
