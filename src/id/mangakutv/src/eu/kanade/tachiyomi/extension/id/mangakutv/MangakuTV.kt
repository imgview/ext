package eu.kanade.tachiyomi.extension.en.mangakutv

import eu.kanade.tachiyomi.multisrc.madara.Madara
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Base64
import eu.kanade.tachiyomi.source.model.Page
import java.text.SimpleDateFormat
import java.util.Locale

class MangakuTV : Madara(
    "Mangaku.tv",
    "https://mangaku.tv",
    "en",
    dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US),
) {

    override val mangaSubString = "manga-tag/warna" 

    override fun pageListParse(document: Document): List<Page> {
    return document.select("div.reading-content img.wp-manga-chapter-img").mapIndexed { i, img ->
        // Ambil atribut data
        val encodedData = img.attr("data")

        // Proses decoding
        val decodedUrl = decodeImageUrl(encodedData)

        // Return Page dengan URL yang sudah di-decode
        Page(i, "", decodedUrl)
    }
}

// Fungsi untuk decode URL dari data
fun decodeImageUrl(encodedData: String): String {
    // Decode pertama dengan Base64
    val base64Decoded = String(android.util.Base64.decode(encodedData, android.util.Base64.DEFAULT))

    // Decode ROT13
    val rot13Decoded = base64Decoded.map {
        when (it) {
            in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
            in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
            else -> it
        }
    }.joinToString("")

    // Decode kedua dengan Base64
    return String(android.util.Base64.decode(rot13Decoded, android.util.Base64.DEFAULT))
}
}