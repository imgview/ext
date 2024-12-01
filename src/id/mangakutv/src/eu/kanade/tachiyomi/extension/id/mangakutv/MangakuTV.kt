package eu.kanade.tachiyomi.extension.id.mangakutv

import eu.kanade.tachiyomi.multisrc.madara.Madara
import org.jsoup.nodes.Document
import android.util.Base64
import eu.kanade.tachiyomi.source.model.Page
import java.text.SimpleDateFormat
import java.util.Locale

class MangakuTV : Madara(
    "Mangaku.tv",
    "https://mangaku.tv",
    "id",
    dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US),
) {
    // Override mangaSubString dengan nilai yang sesuai
    override val mangaSubString = "manga-tag/warna"

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reading-content img.wp-manga-chapter-img").mapIndexed { i, img ->
            val encodedData = img.attr("data") // Ambil data terenkripsi
            val decodedUrl = decodeImageUrl(encodedData) // Decode URL
            Page(i, "", decodedUrl) // Buat objek Page
        }
    }

    private fun decodeImageUrl(encodedData: String): String {
        val base64Decoded = String(Base64.decode(encodedData, Base64.DEFAULT)) // Decode Base64 pertama
        val rot13Decoded = base64Decoded.map {
            when (it) {
                in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
                in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
                else -> it
            }
        }.joinToString("")
        return String(Base64.decode(rot13Decoded, Base64.DEFAULT)) // Decode Base64 kedua
    }
}