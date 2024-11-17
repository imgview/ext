package eu.kanade.tachiyomi.extension.id.monzeekomik

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
        
    override fun mangaDetailsParse(document: Document): SManga {
        return super.mangaDetailsParse(document).apply {
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text()
                ?.replace("ID", "", ignoreCase = true)?.trim().orEmpty()
                }

    override fun pageListParse(document: Document): List<Page> {
        val script = document.select("script[src^=data:text/javascript;base64,]").mapNotNull { element ->
            Base64.decode(
                element.attr("src").substringAfter("base64,"),
                Base64.DEFAULT,
            ).toString(Charsets.UTF_8)
        }.firstOrNull { it.startsWith("ts_reader.run") }
            ?: throw Exception("Couldn't find page script")

        countViews(document)

        val imageListJson = JSON_IMAGE_LIST_REGEX.find(script)?.destructured?.toList()?.get(0).orEmpty()
        val imageList = try {
            json.parseToJsonElement(imageListJson).jsonArray
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

        // Ambil URL layanan resize dari pengaturan pengguna, kosong secara default
        val resizeServiceUrl = getResizeServiceUrl()

        val scriptPages = imageList.mapIndexed { i, jsonEl ->
            val originalUrl = jsonEl.jsonPrimitive.content
            // Jika URL layanan resize tersedia, gunakan; jika tidak, gunakan URL asli
            val resizedUrl = resizeServiceUrl?.let { "$it$originalUrl" } ?: originalUrl
            Page(i, document.location(), resizedUrl)
        }

        return scriptPages
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
}