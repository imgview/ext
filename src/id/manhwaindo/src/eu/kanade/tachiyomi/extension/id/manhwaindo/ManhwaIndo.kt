package eu.kanade.tachiyomi.extension.id.manhwaindo

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.HttpStatusException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class ManhwaIndo : MangaThemesia(
    "ManhwaIndo",
    "https://www.manhwaindo.st",
    "id",
    "/series",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl) ?: super.baseUrl

    override val client = super.client.newBuilder().build()

    override fun searchMangaFromElement(element: Element) = SManga.create().apply {
        title = element.select("a").attr("title")
            .replace(" ID", "")
            .trim()
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)?.attr("title") ?: "Unknown Title"

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
        val imageUrls = tsReader.sources.firstOrNull()?.images
            ?: throw Exception("No images found in ts_reader data")
        val resizeServiceUrl = getResizeServiceUrl()
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
        }
    }

    override fun fetchPageList(document: Document): List<Page> {
        return try {
            pageListParse(document)
        } catch (e: HttpStatusException) {
            throw Exception("Error fetching pages, HTTP status code: ${e.statusCode}")
        } catch (e: IOException) {
            throw Exception("Network error: Unable to fetch page list. Please check your connection.")
        }
    }

    override fun fetchMangaDetails(manga: SManga): SManga {
        return try {
            super.fetchMangaDetails(manga)
        } catch (e: HttpStatusException) {
            throw Exception("Error fetching manga details, HTTP status code: ${e.statusCode}")
        } catch (e: IOException) {
            throw Exception("Network error: Unable to fetch manga details. Please check your connection.")
        }
    }

    override fun fetchChapterList(manga: SManga): List<SChapter> {
        return try {
            super.fetchChapterList(manga)
        } catch (e: HttpStatusException) {
            throw Exception("Error fetching chapter list, HTTP status code: ${e.statusCode}")
        } catch (e: IOException) {
            throw Exception("Network error: Unable to fetch chapter list. Please check your connection.")
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

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
                val newUrl = newValue as? String ?: return@setOnPreferenceChangeListener false
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
