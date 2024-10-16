package eu.kanade.tachiyomi.extension.id.tukangkomik

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class TukangKomik :
    MangaThemesia(
        "TukangKomik",
        "https://komiku.com",
        "id",
        "/manga",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
    ),
    ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, super.baseUrl) ?: super.baseUrl

    override val client = super.client.newBuilder()
        .setRandomUserAgent(
            preferences.getPrefUAType(),
            preferences.getPrefCustomUA(),
        )
        .build()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isEmpty()) {
            super.searchMangaRequest(page, query, filters)
        } else {
            GET("$baseUrl/?s=$query&page=$page", headers)
        }
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("alt")
    }

    override fun getFilterList(): FilterList {
        // (Bagian filter yang sama)
    }

    override fun pageListParse(document: Document): List<Page> {
        // (Bagian parsing halaman yang sama)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(super.baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: ${super.baseUrl}"

            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
        addRandomUAPreferenceToScreen(screen)
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF_SUMMARY = "Untuk penggunaan sementara. Memperbarui aplikasi akan menghapus pengaturan"
        private const val RESTART_APP = "Untuk menerapkan perubahan, restart aplikasi."
    }
}
