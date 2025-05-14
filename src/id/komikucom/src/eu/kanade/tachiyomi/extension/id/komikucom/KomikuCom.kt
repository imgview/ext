package eu.kanade.tachiyomi.extension.id.komikucom

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.source.model.SManga
import uy.kohesive.injekt.api.get
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
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

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(1, 1)
        // Tambahkan CloudflareInterceptor untuk bypass JS challenge secara otomatis
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
                .addHeader("Referer", baseUrl)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.5")
                .addHeader("DNT", "1")
                .addHeader("Connection", "keep-alive")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(request)
        }
        .build()

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override fun pageListParse(document: Document): List<Page> {
        val scriptContent = document.selectFirst("script:containsData(ts_reader)")?.data()
            ?: throw Exception("Script containing 'ts_reader' not found")
        val jsonString = scriptContent
            .substringAfter("ts_reader.run(")
            .substringBefore(");")
        val tsReader = json.decodeFromString<TSReader>(jsonString)

        val imageUrls = tsReader.sources.firstOrNull()?.images
            ?: throw Exception("No images found in ts_reader data")

        // Filter banner atau iklan
        val filteredImageUrls = imageUrls.filterNot { it.contains("/banner/", true) }

        val resizeServiceUrl = getResizeServiceUrl()

        return filteredImageUrls.mapIndexed { index, imageUrl ->
            Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
        }
    }

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
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

        val cookiesPref = EditTextPreference(screen.context).apply {
            key = "cookies"
            title = "Cookies"
            summary = "Masukkan cookies untuk autentikasi."  
            setDefaultValue(null)
            dialogTitle = "Cookies"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("cookies", newValue as String).apply()
                true
            }
        }
        screen.addPreference(cookiesPref)
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }

    @Serializable
    data class TSReader(
        val sources: List<ReaderImageSource>
    )

    @Serializable
    data class ReaderImageSource(
        val source: String,
        val images: List<String>
    )
}
