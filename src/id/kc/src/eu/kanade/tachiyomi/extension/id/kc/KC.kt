package eu.kanade.tachiyomi.extension.id.kc

import eu.kanade.tachiyomi.network.GET
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KC : ParsedHttpSource() {
    override val name = "KC"
    override val baseUrl = "https://komikindo.lol"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    // similar/modified theme of "https://bacakomik.co"
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=update", headers)
    }

    override fun popularMangaSelector() = "div.animepost"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("div.limit img").attr("src")
        manga.title = element.select("div.tt h4").text()
        element.select("div.animposx > a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
        }
        return manga
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/daftar-manga/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
        return GET(url.build(), headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infoanime").first()!!
        val descElement = document.select("div.desc > .entry-content.entry-content-single").first()!!
        val manga = SManga.create()
        val authorCleaner = document.select(".infox .spe b:contains(Pengarang)").text()
        manga.author = document.select(".infox .spe span:contains(Pengarang)").text().substringAfter(authorCleaner)
        val artistCleaner = document.select(".infox .spe b:contains(Ilustrator)").text()
        manga.artist = document.select(".infox .spe span:contains(Ilustrator)").text().substringAfter(artistCleaner)
        val genres = mutableListOf<String>()
        infoElement.select(".infox .genre-info a, .infox .spe span:contains(Grafis:) a, .infox .spe span:contains(Tema:) a, .infox .spe span:contains(Konten:) a, .infox .spe span:contains(Jenis Komik:) a").forEach { element ->
            val genre = element.text()
            genres.add(genre)
        }
        manga.genre = genres.joinToString(", ")
        manga.status = parseStatus(infoElement.select(".infox > .spe > span:nth-child(2)").text())
        manga.description = descElement.select("p").text().substringAfter("bercerita tentang ")
        val altName = document.selectFirst(".infox > .spe > span:nth-child(1)")?.text().takeIf { it.isNullOrBlank().not() }
        altName?.let {
            manga.description = manga.description + "\n\n$altName"
        }
        manga.thumbnail_url = document.select(".thumb > img:nth-child(1)").attr("src").substringBeforeLast("?")
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("berjalan", true) -> SManga.ONGOING
        element.contains("tamat", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapter_list li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select(".lchx a").first()!!
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select(".dt a").first()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toInt()
            when {
                "detik" in date -> Calendar.getInstance().apply {
                    add(Calendar.SECOND, value * -1)
                }.timeInMillis
                "menit" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, value * -1)
                }.timeInMillis
                "jam" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, value * -1)
                }.timeInMillis
                "hari" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * -1)
                }.timeInMillis
                "minggu" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * 7 * -1)
                }.timeInMillis
                "bulan" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, value * -1)
                }.timeInMillis
                "tahun" in date -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, value * -1)
                }.timeInMillis
                else -> {
                    0L
                }
            }
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    chapter.chapter_number = it.groups[1]?.value!!.toFloat()
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        var i = 0
        document.select("div.img-landmine img").forEach { element ->
            val url = element.attr("onError").substringAfter("src='").substringBefore("';")
            i++
            if (url.isNotEmpty()) {
                val resizedImageUrl = "https://resize.sardo.work/?width=300&quality=75&imageUrl=$url"
                pages.add(Page(i, "", resizedImageUrl))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList {
    return FilterList(
        Filter.Header("NOTE: <a href='https://example.com'>Ignored if using text search!</a>")
    )
}

