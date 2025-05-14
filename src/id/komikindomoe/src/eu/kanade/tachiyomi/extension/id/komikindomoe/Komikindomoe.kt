package eu.kanade.tachiyomi.extension.id.komikcast02

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Komikcast02 : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komikcast02.com"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://komikcast02.com")!!

    private fun getResizeServiceUrl(): String? =
        preferences.getString("resize_service_url", null)

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

    override fun popularMangaFromElement(element: Element): SManga = element.toSManga()
    override fun latestUpdatesFromElement(element: Element): SManga = element.toSManga()
    override fun searchMangaFromElement(element: Element): SManga = element.toSManga().apply {
        title = element.selectFirst("h3.title")?.ownText() ?: title
    }

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Custom latest parse: hanya Manhwa, Manhua, dan whitelist Manga
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val rawList = preferences.getString(MANGA_WHITELIST_PREF, "")
        val allowedManga = rawList
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val mangas = document.select(latestUpdatesSelector()).mapNotNull { element ->
            val typeText = element.selectFirst("span.type")?.text()?.trim() ?: return@mapNotNull null
            when {
                typeText.equals("Manhwa", ignoreCase = true) || typeText.equals("Manhua", ignoreCase = true) ->
                    element.toSManga()

                typeText.equals("Manga", ignoreCase = true) -> {
                    val titleText = element.selectFirst("h3.title")?.text()?.trim()
                    if (titleText != null && allowedManga.any { it.equals(titleText, ignoreCase = true) }) {
                        element.toSManga()
                    } else null
                }

                else -> null
            }
        }
        val hasNext = document.select(latestUpdatesNextPageSelector()).firstOrNull() != null
        return MangasPage(mangas, hasNext)
    }

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val info = document.selectFirst("div.komik_info")!!

        // Title
        val rawTitle = info
            .selectFirst("h1.komik_info-content-body-title")!!
            .text()
            .trim()
        manga.title = rawTitle
            .replace("bahasa indonesia", "", ignoreCase = true)
            .replace(Regex("[\\p{Punct}\\s]+\$"), "")
            .trim()

        // Cover
        val imgEl = info.selectFirst("div.komik_info-cover-image img")
            ?: throw Exception("Cover image not found: ${manga.title}")
        val rawUrl = imgEl.attr("abs:src")
        manga.thumbnail_url = "https://wsrv.nl/?w=300&q=70&url=$rawUrl"

        // Author & Artist
        val authorArtistText = info
            .selectFirst("span.komik_info-content-info:has(b:contains(Author))")
            ?.ownText().orEmpty()
        val parts = authorArtistText.split(",")
        manga.author = parts.getOrNull(0)?.trim().orEmpty()
        manga.artist = parts.getOrNull(1)?.trim().orEmpty()

        // Description & Alternative Title
        val synopsis = info
            .select("div.komik_info-description-sinopsis p")
            .eachText()
            .joinToString("\n\n") { it.trim() }
        val altTitle = info
            .selectFirst("span.komik_info-content-native")
            ?.text()
            ?.trim()
            .orEmpty()
        manga.description = buildString {
            append(synopsis)
            if (altTitle.isNotEmpty()) {
                append("\n\nAlternative Title: ")
                append(altTitle)
            }
        }

        // Genre + Type
        val genreList = info
            .select("span.komik_info-content-genre a.genre-item")
            .eachText()
            .toMutableList()
        info.selectFirst("span.komik_info-content-info-type a")
            ?.text()
            ?.takeIf(String::isNotBlank)
            ?.let { genreList.add(it) }
        manga.genre = genreList.joinToString(", ")

        // Status
        val statusText = info
            .selectFirst("span.komik_info-content-info:has(b:contains(Status))")
            ?.text()
            ?.replaceFirst("Status:", "", ignoreCase = true)
            ?.trim()
            .orEmpty()
        manga.status = parseStatus(statusText)

        return manga
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Ongoing", ignoreCase = true)   -> SManga.ONGOING
        text.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else                                          -> SManga.UNKNOWN
    }

    // Chapters
    override fun chapterListSelector() = "div.komik_info-chapters li"
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        name = element.select(".chapter-link-item").text()
        date_upload = parseChapterDate2(element.select(".chapter-link-time").text())
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    private fun parseChapterDate2(date: String): Long {
        return if (date.endsWith("ago")) {
            val value = date.split(' ')[0].toInt()
            when {
                "min" in date  -> Calendar.getInstance().apply { add(Calendar.MINUTE, -value) }.timeInMillis
                "hour" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -value) }.timeInMillis
                "day" in date  -> Calendar.getInstance().apply { add(Calendar.DATE, -value) }.timeInMillis
                "week" in date -> Calendar.getInstance().apply { add(Calendar.DATE, -value * 7) }.timeInMillis
                "month" in date-> Calendar.getInstance().apply { add(Calendar.MONTH, -value) }.timeInMillis
                "year" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, -value) }.timeInMillis
                else            -> 0L
            }
        } else {
            try { dateFormat.parse(date)?.time ?: 0L } catch (_: Exception) { 0L }
        }
    }

    // Page list
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reading-content img")
            .mapIndexed { i, imgEl -> Page(i, "", imgEl.attr("abs:src")) }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    // Filters
    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(EditTextPreference(context).apply {
                key = "resize_service_url"
                title = "Resize Service URL"
                summary = "Masukkan URL layanan resize gambar."
                setDefaultValue(null)
                dialogTitle = "Resize Service URL"
            })
            addPreference(EditTextPreference(context).apply {
                key = BASE_URL_PREF
                title = BASE_URL_PREF_TITLE
                summary = BASE_URL_PREF_SUMMARY
                setDefaultValue(baseUrl)
                dialogTitle = BASE_URL_PREF_TITLE
                dialogMessage = "Original: $baseUrl"
                setOnPreferenceChangeListener { _, newValue ->
                    baseUrl = newValue as String
                    preferences.edit().putString(BASE_URL_PREF, baseUrl).apply()
                    summary = "Current domain: $baseUrl"
                    true
                }
            })
            addPreference(EditTextPreference(context).apply {
                key = MANGA_WHITELIST_PREF
                title = "Whitelist Manga"
                summary = "Masukkan judul Manga yang mau ditampilkan, dipisah koma"
                dialogTitle = "Whitelist Manga (comma-separated)"
                setDefaultValue("")
            })
        }
    }

    private fun Element.toSManga(): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(selectFirst("a")!!.attr("href"))
        manga.title = selectFirst("div.tt, h3.title")?.text()?.trim().orEmpty()
        val rawUrl = selectFirst("img")?.attr("abs:src").orEmpty()
        require(rawUrl.isNotBlank()) { "Gagal memuat cover" }
        manga.thumbnail_url = "https://wsrv.nl/?w=300&q=70&url=$rawUrl"
        return manga
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Base URL override"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Override the base URL"
        private const val MANGA_WHITELIST_PREF = "manga_whitelist"
    }
}
