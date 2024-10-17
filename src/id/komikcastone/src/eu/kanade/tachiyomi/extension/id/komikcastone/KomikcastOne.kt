package eu.kanade.tachiyomi.extension.id.komikcastone

import eu.kanade.tachiyomi.network.GET
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KomikcastOne : ParsedHttpSource() {

    // Array of domains to support domain switching
    private val domains = arrayOf(
        "https://komikindo.lol",
        "https://komikindo.id"
    )

    // Base domain to use (can be switched dynamically)
    private var currentBaseUrl: String = domains[0]

    override val name = "KomikcastOne"
    override val baseUrl: String
        get() = currentBaseUrl
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    // Function to switch to the next available domain
    private fun switchDomain() {
        currentBaseUrl = domains[(domains.indexOf(currentBaseUrl) + 1) % domains.size]
    }

    // Override request functions to handle domain switch
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/daftar-manga/page/$page/?order=update", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/daftar-manga/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)

        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> url.addQueryParameter("author", filter.state)
                is YearFilter -> url.addQueryParameter("yearx", filter.state)
                is SortFilter -> url.addQueryParameter("order", filter.toUriPart())
                is OriginalLanguageFilter -> filter.state.forEach { lang ->
                    if (lang.state) url.addQueryParameter("type[]", lang.id)
                }
                is FormatFilter -> filter.state.forEach { format ->
                    if (format.state) url.addQueryParameter("format[]", format.id)
                }
                is DemographicFilter -> filter.state.forEach { demographic ->
                    if (demographic.state) url.addQueryParameter("demografis[]", demographic.id)
                }
                is StatusFilter -> filter.state.forEach { status ->
                    if (status.state) url.addQueryParameter("status[]", status.id)
                }
                is ContentRatingFilter -> filter.state.forEach { rating ->
                    if (rating.state) url.addQueryParameter("konten[]", rating.id)
                }
                is ThemeFilter -> filter.state.forEach { theme ->
                    if (theme.state) url.addQueryParameter("tema[]", theme.id)
                }
                is GenreFilter -> filter.state.forEach { genre ->
                    if (genre.state) url.addQueryParameter("genre[]", genre.id)
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
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

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.infoanime").first()!!
        val descElement = document.select("div.desc > .entry-content.entry-content-single").first()!!
        val manga = SManga.create()

        val authorCleaner = document.select(".infox .spe b:contains(Pengarang)").text()
        manga.author = document.select(".infox .spe span:contains(Pengarang)").text().substringAfter(authorCleaner)
        val artistCleaner = document.select(".infox .spe b:contains(Ilustrator)").text()
        manga.artist = document.select(".infox .spe span:contains(Ilustrator)").text().substringAfter(artistCleaner)

        val genres = mutableListOf<String>()
        infoElement.select(".infox .genre-info a").forEach { element ->
            genres.add(element.text())
        }
        manga.genre = genres.joinToString(", ")
        manga.status = parseStatus(infoElement.select(".infox > .spe > span:nth-child(2)").text())
        manga.description = descElement.select("p").text().substringAfter("bercerita tentang ")

        val altName = document.selectFirst(".infox > .spe > span:nth-child(1)")?.text().takeIf { !it.isNullOrBlank() }
        altName?.let {
            manga.description += "\n\n$altName"
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
                "detik" in date -> Calendar.getInstance().apply { add(Calendar.SECOND, value * -1) }.timeInMillis
                "menit" in date -> Calendar.getInstance().apply { add(Calendar.MINUTE, value * -1) }.timeInMillis
                "jam" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, value * -1) }.timeInMillis
                "hari" in date -> Calendar.getInstance().apply { add(Calendar.DATE, value * -1) }.timeInMillis
                "minggu" in date -> Calendar.getInstance().apply { add(Calendar.DATE, value * 7 * -1) }.timeInMillis
                "bulan" in date -> Calendar.getInstance().apply { add(Calendar.MONTH, value * -1) }.timeInMillis
                "tahun" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, value * -1) }.timeInMillis
                else -> 0L
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
        basic.find(chapter.name)?.let {
            chapter.chapter_number = it.groups[1]?.value?.toFloat() ?: -1f
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        var i = 0
        document.select("div.img-landmine img").forEach { element ->
            val url = element.attr("onError").substringAfter("src='").substringBefore("';")
            i++
            if (url.isNotEmpty()) {
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SortFilter(),
        Filter.Header("NOTE: Ignored if using text search!"),
        AuthorFilter(),
        YearFilter(),
        Filter.Separator(),
        OriginalLanguageFilter(getOriginalLanguageList()),
        FormatFilter(getFormatList()),
        DemographicFilter(getDemographicList()),
        StatusFilter(getStatusList()),
        ContentRatingFilter(getContentRatingList()),
        ThemeFilter(getThemeList()),
        GenreFilter(getGenreList())
    )

    // Retry domain if there's an error in the request
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return super.fetchChapterList(manga).onErrorResumeNext {
            switchDomain()
            super.fetchChapterList(manga)
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return super.fetchPageList(chapter).onErrorResumeNext {
            switchDomain()
            super.fetchPageList(chapter)
        }
    }

    // Filters implementation...

    private class AuthorFilter : Filter.Text("Author")
    private class YearFilter : Filter.Text("Year")

    private class SortFilter : UriPartFilter(
        "Sort by",
        arrayOf(
            Pair("update", "Latest"),
            Pair("popular", "Popular"),
            Pair("rating", "Rating")
        )
    )

    private class OriginalLanguageFilter(langs: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Original Language", langs.map { CheckBox(it.second, it.first) })

    private class FormatFilter(formats: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Format", formats.map { CheckBox(it.second, it.first) })

    private class DemographicFilter(demographics: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Demographic", demographics.map { CheckBox(it.second, it.first) })

    private class StatusFilter(statuses: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Status", statuses.map { CheckBox(it.second, it.first) })

    private class ContentRatingFilter(ratings: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Content Rating", ratings.map { CheckBox(it.second, it.first) })

    private class ThemeFilter(themes: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Themes", themes.map { CheckBox(it.second, it.first) })

    private class GenreFilter(genres: Array<Pair<String, String>>) :
        Filter.Group<CheckBox>("Genres", genres.map { CheckBox(it.second, it.first) })

    private class CheckBox(name: String, val id: String) : Filter.CheckBox(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }

    // Utility functions for filters

    private fun getOriginalLanguageList() = arrayOf(
        Pair("jp", "Japanese"),
        Pair("kr", "Korean"),
        Pair("cn", "Chinese"),
        Pair("id", "Indonesian")
    )

    private fun getFormatList() = arrayOf(
        Pair("webcomic", "Webcomic"),
        Pair("manga", "Manga"),
        Pair("manhua", "Manhua"),
        Pair("manhwa", "Manhwa")
    )

    private fun getDemographicList() = arrayOf(
        Pair("shounen", "Shounen"),
        Pair("shoujo", "Shoujo"),
        Pair("seinen", "Seinen"),
        Pair("josei", "Josei")
    )

    private fun getStatusList() = arrayOf(
        Pair("completed", "Completed"),
        Pair("ongoing", "Ongoing"),
        Pair("hiatus", "Hiatus")
    )

    private fun getContentRatingList() = arrayOf(
        Pair("safe", "Safe"),
        Pair("suggestive", "Suggestive"),
        Pair("erotica", "Erotica"),
        Pair("pornographic", "Pornographic")
    )

    private fun getThemeList() = arrayOf(
        Pair("action", "Action"),
        Pair("comedy", "Comedy"),
        Pair("drama", "Drama"),
        Pair("fantasy", "Fantasy")
    )

    private fun getGenreList() = arrayOf(
        Pair("adventure", "Adventure"),
        Pair("slice-of-life", "Slice of Life"),
        Pair("romance", "Romance"),
        Pair("horror", "Horror")
    )
}
