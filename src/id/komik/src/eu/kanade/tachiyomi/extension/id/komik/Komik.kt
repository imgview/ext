package eu.kanade.tachiyomi.extension.id.komik

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

class Komik : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komikcast02.com"
    override val lang = "id"
    override val supportsLatest = true

    // Client dengan rate limit
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://komikcast02.com")!!

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }
    
    private fun mageUrl(originalUrl: String): String {
        return "http://LayananGambarCover$originalUrl"
    }

    // Request untuk daftar populer
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/daftar-komik/?orderby=update&page=$page", headers)

    // Request untuk update terbaru
    override fun latestUpdatesRequest(page: Int): Request {
        val postfix = if (page > 1) "page/$page/" else ""
        val url = "$baseUrl/daftar-komik/$postfix?orderby=update"
            .toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    // Request pencarian
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    // Selector
    override fun popularMangaSelector() = "div.list-update_item"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("div.komik_info-content-thumbnail img").attr("abs:src")?.let { resizeImageUrl(it) }
        manga.title = element.select("h3.title").text()
                .substringBefore("(").trim()
        element.select("div.list-update_item a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
        }
        return manga
    }

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Parsing khusus untuk update terbaru
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
            typeText.equals("Manhwa", true) || typeText.equals("Manhua", true) ->
                searchMangaFromElement(element) // <-- Ganti di sini
            typeText.equals("Manga", true) -> {
                val titleText = element.selectFirst("h3.title")?.text()?.trim()
                if (titleText != null && allowedManga.any { it.equals(titleText, true) }) {
                    searchMangaFromElement(element) // <-- Ganti di sini
                } else null
            }
            else -> null
        }
    }
    val hasNext = document.select(latestUpdatesNextPageSelector()).firstOrNull() != null
    return MangasPage(mangas, hasNext)
}

    // Parsing detail manga
    override fun mangaDetailsParse(document: Document): SManga {
    val manga = SManga.create()
    val info = document.selectFirst("div.komik_info") ?: return manga.apply { title = "Judul Tidak Diketahui" }

    // Penanganan judul
    val rawTitle = info.selectFirst("h1.komik_info-content-body-title")?.text().orEmpty()
    manga.title = rawTitle
        .replace("bahasa indonesia", "", ignoreCase = true)
        .substringBeforeLast("(").trim()
        .ifEmpty { "Judul Tidak Diketahui" }

    // Penanganan gambar
    manga.thumbnail_url = element.select("div.komik_info-cover-image img").attr("abs:src")?.let { resizeImageUrl(it) }

    // Penanganan author dan artist
    val parts = info.selectFirst("span.komik_info-content-info:has(b:contains(Author))")
        ?.ownText().orEmpty().split(",")
    manga.author = parts.getOrNull(0)?.trim().orEmpty()
    manga.artist = parts.getOrNull(1)?.trim().orEmpty()

    // Penanganan deskripsi
    val synopsis = info.select("div.komik_info-description-sinopsis p").eachText().joinToString("\n\n")
    val altTitle = info.selectFirst("span.komik_info-content-native")?.text().orEmpty().trim()
    manga.description = buildString {
        append(synopsis)
        if (altTitle.isNotEmpty()) append("\n\nAlternative Title: $altTitle")
    }

    // Penanganan genre
    val genres = info.select("span.komik_info-content-genre a.genre-item").eachText().toMutableList()
    info.selectFirst("span.komik_info-content-info-type a")?.text()?.takeIf(String::isNotBlank)?.let { genres.add(it) }
    manga.genre = genres.joinToString(", ")

    // Penanganan status
    val statusText = info.selectFirst("span.komik_info-content-info:has(b:contains(Status))")
        ?.text()?.replaceFirst("Status:", "", true).orEmpty().trim()
    manga.status = when {
        statusText.contains("Ongoing", true) -> SManga.ONGOING
        statusText.contains("Completed", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    return manga
}

    // Parsing daftar chapter
    override fun chapterListSelector() = "div.komik_info-chapters li"
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        name = element.select(".chapter-link-item").text()
        date_upload = parseChapterDate(element.select(".chapter-link-time").text())
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))
    private fun parseChapterDate(date: String): Long = if (date.endsWith("ago")) {
        val v = date.split(' ')[0].toInt()
        Calendar.getInstance().apply {
            when {
                "min" in date  -> add(Calendar.MINUTE, -v)
                "hour" in date -> add(Calendar.HOUR_OF_DAY, -v)
                "day" in date  -> add(Calendar.DATE, -v)
                "week" in date -> add(Calendar.DATE, -v * 7)
                "month" in date-> add(Calendar.MONTH, -v)
                "year" in date -> add(Calendar.YEAR, -v)
            }
        }.timeInMillis
    } else dateFormat.parse(date)?.time ?: 0L

    // page
    override fun pageListParse(document: Document): List<Page> {
    val resizeServiceUrl = getResizeServiceUrl()

    return document.select("div.main-reading-area img.size-full")
        .filter { img -> 
            val src = img.attr("src")
            !src.endsWith("999.jpg", ignoreCase = true) // Kecualikan gambar yang berakhiran 999.jpg
        }
        .mapIndexed { i, img ->
            val imageUrl = img.attr("src")
            Page(i, document.location(), "$resizeServiceUrl$imageUrl")
        }
}

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

    // Pengaturan sumber (Preferences)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = RESIZE_URL_PREF
            title = RESIZE_URL_PREF_TITLE
            dialogTitle = RESIZE_URL_PREF_TITLE
            dialogMessage = "Layanan Resize"
            summary = "Gunakan layanan resize URL gambar"
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
            title = MANGA_WHITELIST_PREF_TITLE
            dialogTitle = MANGA_WHITELIST_PREF_TITLE
            dialogMessage = "Masukkan judul Manga yang mau ditampilkan, dipisah koma"
            summary = "Tetap tampilkan manga ini"
            setDefaultValue("")
        })
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Base URL override"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Override the base URL"
        private const val MANGA_WHITELIST_PREF = "manga_whitelist"
        private const val MANGA_WHITELIST_PREF_TITLE = "Tampilkan Komik"
        private const val RESIZE_URL_PREF = "resize_service_url"
        private const val RESIZE_URL_PREF_TITLE = "Layanan resize"
    }
}