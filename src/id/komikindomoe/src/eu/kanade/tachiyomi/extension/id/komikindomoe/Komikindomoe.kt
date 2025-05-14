package eu.kanade.tachiyomi.extension.id.komikindomoe

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Komikindomoe : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komikcast02.com"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://komikcast02.com")!!

    private fun getResizeServiceUrl(): String? =
        preferences.getString(RESIZE_URL_PREF, null)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TSReader(
        val sources: List<Source> = emptyList()
    ) {
        @Serializable
        data class Source(
            val images: List<String> = emptyList()
        )
    }

    // Requests
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/daftar-komik/?orderby=update&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request {
        val postfix = if (page > 1) "page/$page/" else ""
        val url = "$baseUrl/daftar-komik/$postfix?orderby=update"
            .toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    // Selectors
    override fun popularMangaSelector(): String = "div.list-update_item"
    override fun latestUpdatesSelector(): String = "div.list-update_item"
    override fun searchMangaSelector(): String = "div.list-update_item"

    // Manga parsing
    override fun popularMangaFromElement(element: Element): SManga = element.toSManga()
    override fun latestUpdatesFromElement(element: Element): SManga = element.toSManga()
    override fun searchMangaFromElement(element: Element): SManga = element.toSManga().apply {
        title = element.selectFirst("h3.title")?.ownText() ?: title
    }

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Custom latest parse (omitted for brevity)
    override fun latestUpdatesParse(response: Response): MangasPage = super.latestUpdatesParse(response)

    // Details
    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document)

    // Chapters
    override fun chapterListSelector() = "div.komik_info-chapters li"
    override fun chapterFromElement(element: Element) = super.chapterFromElement(element)

    // Page list using resize service if configured
    override fun pageListParse(document: Document): List<Page> {
        val svc = getResizeServiceUrl()
        return document.select("div.main-reading-area img.size-full")
            .mapIndexed { i, img ->
                val rawUrl = img.attr("abs:src")
                val finalUrl = svc?.let {
                    val encoded = URLEncoder.encode(rawUrl, "UTF-8")
                    if (it.contains("?")) "$it$encoded" else "$it?url=$encoded"
                } ?: rawUrl
                Page(i, "", finalUrl)
            }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    // Filters
    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = RESIZE_URL_PREF
            title = "Resize Service URL"
            summary = ""
            setDefaultValue("")
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"
            setOnPreferenceChangeListener { _, new ->
                baseUrl = new as String
                preferences.edit().putString(BASE_URL_PREF, baseUrl).apply()
                summary = "Current domain: $baseUrl"
                true
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = MANGA_WHITELIST_PREF
            title = "Whitelist Manga"
            summary = "Masukkan judul Manga yang mau ditampilkan, dipisah koma"
            setDefaultValue("")
        })
    }

    private fun Element.toSManga(): SManga = super.toSManga()

    companion object {
        private const val BASE_URL_PREF_TITLE = "Base URL override"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Override the base URL"
        private const val MANGA_WHITELIST_PREF = "manga_whitelist"
        private const val RESIZE_URL_PREF = "resize_service_url"
    }
}
