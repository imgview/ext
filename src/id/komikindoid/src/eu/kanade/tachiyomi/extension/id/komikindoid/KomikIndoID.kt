package eu.kanade.tachiyomi.extension.id.komikindoid

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomikIndoID : ParsedHttpSource(), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override var baseUrl: String = "https://komikindo.lol"

    override val client: OkHttpClient = network.cloudflareClient

    override val name: String = "KomikIndoID"

    override val lang: String = "id"

    override val supportsLatest: Boolean = true

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar. Kosongkan jika tidak ingin resize."
            dialogTitle = "Resize Service URL"
            dialogMessage = "Masukkan URL layanan resize gambar (contoh: https://resize.example.com/?imageUrl=)."
        }
        screen.addPreference(resizeServicePref)
    }

    private fun getResizeServiceUrl(): String {
        return preferences.getString("resize_service_url", "") ?: ""
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=update", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun popularMangaSelector(): String = "div.animepost"

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("div.tt h4").text()
            thumbnail_url = element.select("div.limit img").attr("src")
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("div.infoanime h1").text()
            author = document.select(".infox .spe span:contains(Pengarang)").text()
            genre = document.select(".genre-info a").joinToString { it.text() }
            status = parseStatus(document.select(".infox > .spe > span:nth-child(2)").text())
            description = document.select("div.desc > .entry-content p").text()
            thumbnail_url = document.select(".thumb img").attr("src")
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("berjalan", true) -> SManga.ONGOING
        status.contains("tamat", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector(): String = "#chapter_list li"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.select(".lchx a").attr("href"))
            name = element.select(".lchx a").text()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val resizeServiceUrl = getResizeServiceUrl()

        return document.select("div.img-landmine img").mapIndexed { i, element ->
            val originalUrl = element.attr("onError").substringAfter("src='").substringBefore("';")
            val finalUrl = if (resizeServiceUrl.isNotEmpty()) {
                resizeServiceUrl + originalUrl
            } else {
                originalUrl
            }
            Page(i, "", finalUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
}