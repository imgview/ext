package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class SirenKomik : MangaThemesia(
    "Siren Komik",
    "https://sirenkomik.my.id",
    "id",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
) {
    override val id = 8457447675410081142

    override val hasProjectPage = true

    override val seriesTitleSelector = "h1.judul-komik"
    override val seriesThumbnailSelector = ".gambar-kecil img"
    override val seriesGenreSelector = ".genre-komik a"
    override val seriesAuthorSelector = ".keterangan-komik:contains(author) span"
    override val seriesArtistSelector = ".keterangan-komik:contains(artist) span"

    override fun chapterListSelector() = ".list-chapter a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst(".nomer-chapter")!!.text()
        date_upload = element.selectFirst(".tgl-chapter")?.text().parseChapterDate()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    override fun pageListParse(document: Document): List<Page> {
        // Ambil post ID dari URL halaman
        val postId = document.location().substringAfter("posts/").substringBefore("/")

        // Bangun URL API untuk mengambil data JSON
        val apiUrl = "$baseUrl/wp-json/wp/v2/posts/$postId"

        // Panggil API dan parsing JSON
        val response = client.newCall(GET(apiUrl, headers)).execute()
        val jsonResponse = json.parseToJsonElement(response.body!!.string()).jsonObject

        // Ambil properti "content.rendered" yang berisi HTML
        val contentHtml = jsonResponse["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
            ?: throw Exception("Tidak dapat menemukan konten.")

        // Gunakan Jsoup untuk mengekstrak URL gambar dari HTML
        val imageUrls = Jsoup.parse(contentHtml).select("img[src]").map { it.absUrl("src") }

        // Konstruksi daftar halaman
        return imageUrls.mapIndexed { index, url ->
            Page(index, "", url)
        }
    }

    companion object {
        val postIdRegex = """postId.:(\d+)""".toRegex()
    }

    // Override properti json dari superclass
    override val json = Json { ignoreUnknownKeys = true }
}