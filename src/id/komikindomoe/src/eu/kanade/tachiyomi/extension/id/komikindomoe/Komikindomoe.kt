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
import java.text.SimpleDateFormat
import java.util.Locale

class Komikindomoe : ParsedHttpSource(), ConfigurableSource {
    override val name = "Kiryuu01.com"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://kiryuu01.com")!!

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
        val url = "$baseUrl/daftar-komik/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    // Selectors
    override fun popularMangaSelector(): String = "div.list-update_item"
    override fun latestUpdatesSelector(): String = "div.list-update_item"
    override fun searchMangaSelector(): String = "div.list-update_item"

    override fun popularMangaFromElement(element: Element): SManga = element.toSManga()
    override fun latestUpdatesFromElement(element: Element): SManga = element.toSManga()
    override fun searchMangaFromElement(element: Element): SManga = element.toSManga()

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
        val info = document.selectFirst("div.postbody")!!
        manga.title = info.selectFirst("h1")!!.text()
        val imgEl = info.selectFirst("div.thumb img")
            ?: throw Exception("Cover image not found: ${'$'}{manga.title}")
        val rawUrl = imgEl.attr("abs:src").takeIf(String::isNotBlank)
            ?: throw Exception("Cover URL blank: ${'$'}{manga.title}")
        manga.thumbnail_url = "https://wsrv.nl/?w=100&h=100&url=$rawUrl"
        manga.author = info.selectFirst("tr:has(td:contains(Author)) td:nth-child(2)")?.text().orEmpty()
        manga.artist = info.selectFirst("tr:has(td:contains(Artist)) td:nth-child(2)")?.text().orEmpty()
        manga.description = info.select("div.entry-content-single[itemprop=\"description\"] p")
            .eachText().joinToString("\n\n").let { desc ->
                info.selectFirst(".seriestualt")?.text()?.takeIf(String::isNotBlank)
                    ?.let { alt -> "$desc\n\nAlternative Title: $alt" } ?: desc
            }
        val genreList = info.select("div.seriestugenre a").eachText().toMutableList()
        info.selectFirst("tr:has(td:contains(Type)) td:nth-child(2)")?.text().orEmpty()
            .takeIf(String::isNotBlank)?.let(genreList::add)
        manga.genre = genreList.joinToString(", ")
        val statusText = info.select("table.infotable tr").firstOrNull { row ->
            row.selectFirst("td:nth-child(1)")?.text()?.equals("Status", ignoreCase = true) == true
        }?.selectFirst("td:nth-child(2)")?.text().orEmpty()
        manga.status = parseStatus(statusText)
        return manga
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        text.contains("Compleated", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapters
    override fun chapterListSelector(): String =
        "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val urlElem = element.selectFirst("a")!!
        chapter.setUrlWithoutDomain(urlElem.attr("href"))
        chapter.name = element.selectFirst("span.chapternum")?.text() ?: urlElem.text()
        element.selectFirst("span.chapterdate")?.text()?.let { dateStr ->
            val parser = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
            chapter.date_upload = try {
                parser.parse(dateStr)?.time ?: 0
            } catch (_: Exception) {
                0
            }
        }
        return chapter
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val script = document.selectFirst("script:containsData(ts_reader)")?.data()
        val prefix = getResizeServiceUrl().orEmpty()
        if (script != null) {
            val jsonString = script.substringAfter("ts_reader.run(").substringBefore(");")
            val tsReader = jsonParser.decodeFromString<TSReader>(jsonString)
            val imageUrls = tsReader.sources.firstOrNull()?.images.orEmpty()
            return imageUrls.mapIndexed { i, url -> Page(i, document.location(), prefix + url) }
        }
        return document.select("div#readerarea img").mapIndexed { i, img ->
            Page(i, document.location(), prefix + img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun getFilterList(): FilterList =
        FilterList(Filter.Header("No filters"))

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

    // Helper to convert element → SManga
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
