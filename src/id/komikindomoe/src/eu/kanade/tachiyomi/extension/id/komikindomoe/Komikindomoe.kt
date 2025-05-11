package eu.kanade.tachiyomi.extension.id.komikindomoe

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Komikindomoe : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komikcast02.com"
    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://kiryuu01.com")!!
    override val lang = "id"
    override val supportsLatest = true
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private fun getResizeServiceUrl(): String? = preferences.getString("resize_service_url", null)

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesRequest(page: Int): Request {
    val url = "$baseUrl/manga/?order=update&page=$page"
        .toHttpUrl().newBuilder().build()
    return GET(url, headers)
}

    private fun resizeImage(imageUrl: String, width: Int, height: Int): String =
        "https://resize.sardo.work/?width=$width&height=$height&imageUrl=$imageUrl"

    override fun popularMangaSelector(): String = "div.listupd div.utao div.uta"
    override fun latestUpdatesSelector() = "div.listupd div.bsx"
    override fun latestUpdatesFromElement(element: Element): SManga =
    searchMangaFromElement(element)
    override fun searchMangaSelector() = "div.bsx"
    override fun latestUpdatesParse(response: Response): MangasPage {
    val doc = response.asJsoup()
    val list = doc.select(latestUpdatesSelector())
        .mapNotNull { el ->
            if (el.selectFirst("span.colored") == null) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(el.selectFirst("a")!!.attr("href"))
                title = el.selectFirst("div.tt")!!.text().trim()
                thumbnail_url = el.selectFirst("img.ts-post-image")!!
                    .attr("abs:src")
            }
        }
    val hasNext = doc.select(latestUpdatesNextPageSelector()).first() != null
    return MangasPage(list, hasNext)
}

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.title = element.select("div.tt, h3").text()
        val originalImageUrl = element.selectFirst("img.ts-post-image")?.attr("abs:src") ?: ""
        manga.thumbnail_url = resizeImage(originalImageUrl, 50, 50)
        return manga
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    override fun popularMangaNextPageSelector(): String = "div.hpage a.r"
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector(): String = "a.next.page-numbers"

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.select("div.wd-full, div.postbody").first()!!
        val descElement = document.select("div.entry-content.entry-content-single").first()!!

        manga.title = document.select("div.thumb img").attr("title")
        manga.author = infoElement.select("b:contains(Author) + span").text()
        manga.artist = infoElement.select("b:contains(Artist) + span").text()

        val genres = mutableListOf<String>()
        val types = mutableListOf<String>()
        infoElement.select("span.mgen a").forEach { genres.add(it.text()) }
        infoElement.select(".imptdt a").forEach { types.add(it.text()) }
        manga.genre = (genres + types).joinToString(", ")

        manga.status = parseStatus(infoElement.select(".imptdt i").text())
        manga.description = descElement.select("p").text()

        document.selectFirst("b:contains(Alternative Titles) + span")?.text()?.let {
            manga.description += "\n\nAlternative Name: $it"
        }

        val thumbUrl = document.selectFirst("div.thumb img")?.attr("src") ?: ""
        manga.thumbnail_url = resizeImage(thumbUrl, 110, 150)
        return manga
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("ongoing", true) -> SManga.ONGOING
        text.contains("completed", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector(): String = "div#chapterlist ul > li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("div.eph-num a").first()!!
        return SChapter.create().apply {
            setUrlWithoutDomain(urlElement.attr("href"))
            name = urlElement.select("span.chapternum").text()
            date_upload = element.select("span.chapterdate").text().let { parseChapterDate(it) }
        }
    }

    private fun parseChapterDate(date: String): Long = try {
        dateFormat.parse(date)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        Regex("""Chapter\s([0-9]+)""")
            .find(chapter.name)
            ?.groups?.get(1)?.value
            ?.toFloatOrNull()
            ?.let { chapter.chapter_number = it }
    }

    override fun pageListParse(document: Document): List<Page> {
        val urlPrefix = getResizeServiceUrl()
        return document.select("div#readerarea img").mapIndexed { i, img ->
            val src = img.attr("abs:src")
            Page(i, "", urlPrefix?.let { "$it$src" } ?: src)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Referer", page.url)
            .build()
    )

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("No filters available on site"),
        Filter.Separator()
    )

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("src") -> attr("abs:src")
        else -> ""
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.apply {
            addPreference(
                EditTextPreference(context).apply {
                    key = "resize_service_url"
                    title = "Resize Service URL"
                    summary = "Masukkan URL Resize"
                    setDefaultValue("")
                }
            )
            addPreference(
                EditTextPreference(context).apply {
                    key = BASE_URL_PREF
                    title = BASE_URL_PREF_TITLE
                    summary = BASE_URL_PREF_SUMMARY
                    setDefaultValue(baseUrl)
                    dialogMessage = "Original: $baseUrl"
                    setOnPreferenceChangeListener { _, newValue ->
                        baseUrl = newValue as String
                        preferences.edit().putString(BASE_URL_PREF, newValue).apply()
                        summary = "Current domain: $baseUrl"
                        true
                    }
                }
            )
        }
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }
}