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
        GET("$baseUrl/manga/?order=popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/?order=update&page=$page"
            .toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/manga/?s=$query&page=$page"
            .toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    // Selectors
    override fun popularMangaSelector(): String = "div.listupd div.bsx"
    override fun latestUpdatesSelector(): String = "div.listupd div.bsx"
    override fun searchMangaSelector(): String = "div.listupd div.bsx"

    override fun popularMangaFromElement(element: Element): SManga = element.toSManga()
    override fun latestUpdatesFromElement(element: Element): SManga = element.toSManga()
    override fun searchMangaFromElement(element: Element): SManga = element.toSManga()

    override fun popularMangaNextPageSelector(): String = ".hpage a.r"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Custom latest parse: only colored
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .mapNotNull { element ->
                if (element.selectFirst("span.colored") == null) return@mapNotNull null
                element.toSManga()
            }
        val hasNext = document.select(latestUpdatesNextPageSelector()).first() != null
        return MangasPage(mangas, hasNext)
    }

    // Details

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = SManga.create()
    val info = document.selectFirst("div.postbody")!!

    manga.title = info.selectFirst("h1")!!.text()
    manga.thumbnail_url = info.selectFirst("div.thumb img")!!.attr("abs:src")
    manga.author = info.selectFirst("b:contains(Author) + span")?.text().orEmpty()
    manga.artist = info.selectFirst("b:contains(Artist) + span")?.text().orEmpty()

    manga.description = ""
    info.select("div.entry-content-single[itemprop=\"description\"] p")
        .eachText()
        .joinToString("\n\n")
        .also { if (it.isNotBlank()) manga.description += it }

    info.selectFirst(".seriestualt")?.text()
        ?.takeIf { it.isNotBlank() }
        ?.let { manga.description += "\n\nAlternative Title: $it" }

    manga.genre = info.select("div.seriestugenre a")
        .eachText()
        .joinToString(", ")

    val statusText = info.select("table.infotable tr")
        .firstOrNull { row ->
            row.selectFirst("td:nth-child(1)")?.text()?.equals("Status", true) == true
        }
        ?.selectFirst("td:nth-child(2)")?.text()
        .orEmpty()

    manga.status = parseStatus(statusText)

    return manga
}

     private fun parseStatus(element: String): Int = when {
    element.contains("berjalan", true) -> SManga.ONGOING
    element.contains("tamat", true)    -> SManga.COMPLETED
    else                                -> SManga.UNKNOWN
}

    override fun chapterListSelector() = "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"

    override fun chapterFromElement(element: Element): SChapter {
    val chapter = SChapter.create()
    // URL
    val urlElem = element.selectFirst("a")!!
    chapter.setUrlWithoutDomain(urlElem.attr("href"))

    // Hanya ambil nomor chapter saja
    chapter.name = element
        .selectFirst("span.chapternum")
        ?.text()
        ?: urlElem.text()

    // Ambil dan parse tanggal upload
    element.selectFirst("span.chapterdate")?.text()?.let { dateStr ->
        val parser = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
        chapter.date_upload = try {
            parser.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    return chapter
}

    // Pages: override both versions
    override fun pageListParse(document: Document): List<Page> {
    // 1) Coba cari skrip ts_reader
    val script = document.selectFirst("script:containsData(ts_reader)")?.data()
    if (script != null) {
        val jsonString = script
            .substringAfter("ts_reader.run(")
            .substringBefore(");")
        val tsReader = jsonParser.decodeFromString<TSReader>(jsonString)
        val imageUrls = tsReader.sources
            .firstOrNull()
            ?.images
            ?: return emptyList()
        return imageUrls.mapIndexed { i, imageUrl ->
            Page(i, document.location(), "${getResizeServiceUrl() ?: ""}$imageUrl")
        }
    }

    // 2) Fallback: cari <img> di reader (contoh selector, sesuaikan dengan situs)
    return document.select("div#readerarea img")
        .mapIndexed { i, img ->
            val url = img.attr("abs:src")
            Page(i, document.location(), "${getResizeServiceUrl() ?: ""}$url")
        }
}

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

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
    }

    private fun Element.toSManga(): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(selectFirst("a")!!.attr("href"))
        manga.title = selectFirst("div.tt, h3")!!.text().trim()
        manga.thumbnail_url = selectFirst("img.ts-post-image")!!.attr("abs:src")
        return manga
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Base URL override"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Override the base URL"
    }
}