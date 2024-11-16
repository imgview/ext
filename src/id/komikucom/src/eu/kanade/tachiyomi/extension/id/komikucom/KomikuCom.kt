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
import okhttp3.Request
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

    private fun getPrefCustomUA(): String? {
        return preferences.getString("custom_ua", null)
    }

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", getPrefCustomUA() ?: "Default User-Agent")
            chain.proceed(requestBuilder.build())
        }
        .rateLimit(60, 1)
        .build()

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override fun pageListParse(document: Document): List<Page> {
    val images = document.select(pageSelector)
    val resizeServiceUrl = "https://resize.sardo.work/?width=300&quality=75&imageUrl="

    val pages = images.mapIndexed { index, imgElement ->
        val imgUrl = imgElement.imgAttr()
        val resizedUrl = "$resizeServiceUrl$imgUrl"
        Page(index, document.location(), resizedUrl)
    }

    return pages
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val customUserAgentPref = EditTextPreference(screen.context).apply {
            key = "custom_ua"
            title = "Custom User-Agent"
            summary = "Masukkan custom User-Agent Anda di sini."
            setDefaultValue(null)
            dialogTitle = "Custom User-Agent"
        }

        screen.addPreference(customUserAgentPref)

        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar."
            setDefaultValue(null)
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