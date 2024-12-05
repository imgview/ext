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
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Komikindomoe : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komikindo.moe"

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override var baseUrl: String = preferences.getString(BASE_URL_PREF, "https://komikindo.moe")!!

    override val lang = "id"
    override val supportsLatest = true
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(12, 3)
        .build()

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers)
    }

    private fun resizeImage(imageUrl: String, width: Int, height: Int): String {
    return "https://resize.sardo.work/?width=$width&height=$height&imageUrl=$imageUrl"
}

    override fun popularMangaSelector() = "div.listupd div.utao div.uta"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = "div.bsx"

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
    val manga = SManga.create()
    manga.setUrlWithoutDomain(element.select("a").attr("href")) // URL manga
    manga.title = element.select("div.tt, h3").text() // Judul manga

    // Mengatur thumbnail dengan penggantian URL dan resize
    val originalImageUrl = element.selectFirst("img.ts-post-image")?.attr("src") ?: ""
    val replacedImageUrl = originalImageUrl.replace(
        "https://cdn.statically.io/img/komikindo.moe/img",
        "https://ikiru.one/wp-content/uploads"
    )
    manga.thumbnail_url = resizeImage(replacedImageUrl, 50, 50)

    return manga
}

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    override fun popularMangaNextPageSelector() = "div.hpage a.r"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = "a.next.page-numbers"

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = SManga.create()
    val infoElement = document.select("div.wd-full, div.postbody").first()!!
    val descElement = document.select("div.entry-content.entry-content-single").first()!!

    manga.title = document.select("div.thumb img").attr("title") // Judul manga
    manga.author = infoElement.select("b:contains(Author) + span").text() // Penulis
    manga.artist = infoElement.select("b:contains(Artist) + span").text() // Ilustrator

    // Menambahkan genre dan tipe manga
    val genres = mutableListOf<String>()
    val typeManga = mutableListOf<String>()
    infoElement.select("span.mgen a").forEach { genres.add(it.text()) }
    infoElement.select(".imptdt a").forEach { typeManga.add(it.text()) }
    manga.genre = (genres + typeManga).joinToString(", ")

    manga.status = parseStatus(infoElement.select(".imptdt i").text()) // Status manga
    manga.description = descElement.select("p").text() // Deskripsi

    // Menambahkan nama alternatif jika ada
    val altName = document.selectFirst("b:contains(Alternative Titles) + span")?.text()
    if (altName != null) {
        manga.description += "\n\nAlternative Name: $altName"
    }

    // Mengatur thumbnail dengan penggantian URL dan resize
    val originalImageUrl = document.selectFirst("div.thumb img")?.attr("src") ?: ""
    val replacedImageUrl = originalImageUrl.replace(
        "https://cdn.statically.io/img/komikindo.moe/img",
        "https://ikiru.one/wp-content/uploads"
    )
    manga.thumbnail_url = resizeImage(replacedImageUrl, 110, 150)

    return manga
}

    private fun parseStatus(element: String): Int = when {
        element.lowercase().contains("ongoing") -> SManga.ONGOING
        element.lowercase().contains("completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div#chapterlist ul > li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("div.eph-num a").first()!!
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.select("span.chapternum").text()
        chapter.date_upload = element.select("span.chapterdate").text()?.let { parseChapterDate(it) } ?: 0L
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        if (basic.containsMatchIn(chapter.name)) {
            chapter.chapter_number = basic.find(chapter.name)?.groups?.get(1)?.value?.toFloat() ?: 0f
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val resizeServiceUrl = getResizeServiceUrl()
        return document.select("div#readerarea img").mapIndexed { i, element ->
            val imageUrl = element.imgAttr()
            val finalImageUrl = resizeServiceUrl?.let { "$it$imageUrl" } ?: imageUrl
            Page(i, "", finalImageUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Filter sengaja kosong, di web mereka gak ada filter juga"),
        Filter.Separator()
    )

    private fun Element.imgAttr(): String {
    return when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("src") -> attr("abs:src")
        else -> ""
    }
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL Resize"
            setDefaultValue("")
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

    private fun Elements.imgAttr(): String = this.first()!!.imgAttr()

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }
}