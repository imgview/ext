package eu.kanade.tachiyomi.multisrc.mangathemesia

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

// Formerly WPMangaStream & WPMangaReader -> MangaThemesia
abstract class MangaThemesia(
    override val name: String,
    override var baseUrl: String,
    final override val lang: String,
    val mangaUrlDirectory: String = "/manga",
    val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US),
) : ParsedHttpSource() {

    protected open val json: Json by injectLazy()

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    protected val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "es"),
        classLoader = javaClass.classLoader!!,
    )

    open val projectPageString = "/project"

    // SetupPreferenceScreen class
    fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPreference = ListPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Pilih Domain"
            entries = arrayOf("Domain 1", "Domain 2", "Domain 3")
            entryValues = arrayOf("https://domain1.com", "https://domain2.com", "https://domain3.com")
            setDefaultValue("https://domain1.com")
            summary = "%s"
        }
        screen.addPreference(domainPreference)
    }

    // Load domain from preferences
    fun getDomain(preferences: SharedPreferences): String {
        return preferences.getString(DOMAIN_PREF_KEY, baseUrl) ?: baseUrl
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "mangathemesia_domain"
    }
}