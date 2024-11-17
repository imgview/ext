package eu.kanade.tachiyomi.extension.id.manhwaindo

import android.app.Application
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class ManhwaIndo : MangaThemesia(
    "ManhwaIndo",
    "https://www.manhwaindo.st",
    "id",
    "/series",
    dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(59, 1)
        .build()

    override fun pageListParse(document: Document): List<Page> {
    // Langkah 1: Cari elemen script yang berisi data gambar (Base64 atau script langsung)
    val scriptElement = document.selectFirst("script[src^=data:text/javascript;base64], script:containsData(ts_reader.run)")
    val pageList = mutableListOf<Page>()

    if (scriptElement != null) {
        // Langkah 2: Cek apakah script menggunakan Base64
        val scriptContent = if (scriptElement.hasAttr("src") && scriptElement.attr("src").startsWith("data:text/javascript;base64,")) {
            // Decode dari Base64
            val base64Content = scriptElement.attr("src").substringAfter("base64,")
            val decodedContent = String(Base64.decode(base64Content, Base64.DEFAULT))

            // Jika tidak ada ts_reader.run setelah decoding, fallback ke elemen gambar
            if (!decodedContent.contains("ts_reader.run")) {
                return fallbackToImageElements(document)
            }
            decodedContent
        } else {
            // Jika tidak Base64, langsung ambil isi dari elemen script
            scriptElement.data()
        }

        // Langkah 3: Ekstrak JSON dari `ts_reader.run({...})`
        val jsonContent = JSON_TS_READER_REGEX.find(scriptContent)?.groupValues?.get(1)

        if (jsonContent != null) {
            // Parsing JSON untuk mendapatkan daftar URL gambar
            val jsonElement = json.parseToJsonElement(jsonContent)
            val imageUrls = jsonElement.jsonObject["sources"]?.jsonArray
                ?.flatMap { it.jsonObject["images"]!!.jsonArray }
                ?.map { it.jsonPrimitive.content } ?: return fallbackToImageElements(document)

            // Tambahkan URL gambar ke dalam daftar halaman
            pageList.addAll(imageUrls.mapIndexed { i, imageUrl -> Page(i, document.location(), imageUrl) })
        } else {
            // Jika JSON dari `ts_reader.run` tidak ditemukan, fallback ke elemen gambar
            return fallbackToImageElements(document)
        }
    } else {
        // Jika elemen script tidak ditemukan atau `ts_reader.run` tidak ada, fallback ke elemen gambar
        return fallbackToImageElements(document)
    }

    return pageList
}

// Fungsi fallback: Menggunakan elemen gambar di halaman jika `ts_reader.run` tidak ada
fun fallbackToImageElements(document: Document): List<Page> {
    val imageElements = document.select("img[src]")
    return imageElements.mapIndexed { i, element ->
        val imageUrl = element.attr("abs:src")
        Page(i, document.location(), imageUrl)
    }
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar, contoh: https://resize.sardo.work."
            setDefaultValue("") // Nilai default kosong
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

        // Preference untuk mengubah base URL
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                baseUrl = newUrl
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                summary = "Current domain: $newUrl" // Update summary untuk domain yang baru
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
    title = document.selectFirst(seriesThumbnailSelector)!!.attr("alt").removeSuffix(" ID")
}

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
    // Ambil elemen dengan class 'tt' yang berisi judul manga
    val titleElement = element.selectFirst("div.tt")
    
    // Debug apakah elemen judul ditemukan
    if (titleElement == null) {
        println("Error: Elemen dengan class 'tt' tidak ditemukan.")
    } else {
        // Ambil judul dari elemen 'tt'
        val titleText = titleElement.text()
        println("Judul ditemukan: $titleText")
        
        // Hapus suffix " ID" jika ada
        title = titleText.removeSuffix(" ID")
    }
    
    // Log hasil akhir dari title setelah modifikasi
    println("Judul manga setelah removeSuffix: $title")
}

    companion object {
    // Konstanta untuk pengaturan ekstensi
    private const val BASE_URL_PREF_TITLE = "Ubah Domain"
    private const val BASE_URL_PREF = "overrideBaseUrl"
    private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"

    // Regex untuk mengekstrak JSON dari ts_reader.run({...})
    val JSON_TS_READER_REGEX = Regex("""ts_reader\.run\((\{.*\})\);""")
}

    @Serializable
    data class TSReader(
        val sources: List<ReaderImageSource>,
    )

    @Serializable
    data class ReaderImageSource(
        val source: String,
        val images: List<String>,
    )
}
