package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import org.jsoup.nodes.Element
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class SirenKomik :
    MangaThemesia(
    "Siren Komik",
    "https://sirenkomik.my.id",
    "id",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
    ) {
    
        override val id = 8457447675410081142

    override val hasProjectPage = true

    override val seriesTitleSelector = "h1.judul-komik"
    override val seriesThumbnailSelector = ".gambar-kecil img"
    override val seriesAuthorSelector = ".keterangan-komik:contains(author) span"
    override val seriesArtistSelector = ".keterangan-komik:contains(artist) span"

    override fun chapterListSelector() = ".list-chapter a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst(".nomer-chapter")!!.text()
        date_upload = element.selectFirst(".tgl-chapter")?.text().parseChapterDate()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    override fun pageListParse(document: Document): List<Page> {
    val imageElements = document.select("img.ts-main-image") // Selector yang sesuai
    if (imageElements.isEmpty()) return super.pageListParse(document)

    return imageElements.mapIndexed { index, element ->
        val imageUrl = element.attr("src") // Ambil URL gambar
        Page(index, document.location(), imageUrl)
    }
}
