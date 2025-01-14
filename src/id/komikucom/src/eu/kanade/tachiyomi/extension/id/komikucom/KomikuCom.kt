package eu.kanade.tachiyomi.extension.id.komikucom

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class KomikuCom : MangaThemesia(
    "Komik",
    "https://komiku.com",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(60, 1)
        .build()

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
    title = element.select("a").attr("title")
        .replace(" ID", "")
        .trim()
}

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
    title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")

    description = document.select(seriesDescriptionSelector)
        .joinToString("\n") { it.text() }
        .trim()
        .substringAfter("berkisah tentang :", "")
}

    override fun pageListParse(document: Document): List<Page> {
    val scriptContent = document.selectFirst("script:containsData(ts_reader)")?.data()
        ?: throw Exception("Script containing 'ts_reader' not found")
    val jsonString = scriptContent.substringAfter("ts_reader.run(").substringBefore(");")
    val tsReader = json.decodeFromString<TSReader>(jsonString)

    // Ambil daftar URL gambar dari `ts_reader`
    val imageUrls = tsReader.sources.firstOrNull()?.images
        ?: throw Exception("No images found in ts_reader data")
    
    // Mengecualikan gambar pertama dan terakhir
    val filteredImageUrls = if (imageUrls.size > 2) {
        imageUrls.drop(1).dropLast(1) // Buang elemen pertama dan terakhir
    } else {
        emptyList() // Jika kurang dari 3 elemen, kosongkan
    }

    // URL layanan resize (jika ada)
    val resizeServiceUrl = getResizeServiceUrl()

    // Kembalikan daftar halaman dengan gambar yang sudah difilter
    return filteredImageUrls.mapIndexed { index, imageUrl -> 
        Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
    }
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar."
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

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
                summary = "Current domain: $newUrl"
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
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
