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

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"
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

    // Details, chapters, pages
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val info = document.select("div.postbody").first()!!
        manga.title = document.selectFirst("div.postbody h1")!!.text()
        manga.author = info.select("b:contains(Author) + span").text()
        manga.artist = info.select("b:contains(Artist) + span").text()
        val genres = info.select("div.genres a").eachText()
        manga.genre = genres.joinToString(", ")
        manga.status = SManga.UNKNOWN
        manga.description = document.selectFirst("div.description")!!.text()
        manga.thumbnail_url = document.selectFirst("div.thumb img")!!.attr("abs:src")
        return manga
    }

    override fun chapterListSelector(): String = "div#chapterlist ul > li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElem = element.selectFirst("a")!!
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElem.attr("href"))
        chapter.name = urlElem.text()
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#readerarea img").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val basePref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)
            dialogMessage = "Original: $baseUrl"
            setOnPreferenceChangeListener { _, newVal ->
                baseUrl = newVal as String
                preferences.edit().putString(BASE_URL_PREF, baseUrl).apply()
                summary = "Current baseUrl: $baseUrl"
                true
            }
        }
        screen.addPreference(basePref)
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
