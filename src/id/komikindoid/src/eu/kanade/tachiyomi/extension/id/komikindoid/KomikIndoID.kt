package eu.kanade.tachiyomi.extension.id.komikindoid

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale

class KomikIndoID : ParsedHttpSource(), ConfigurableSource {

private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

private fun getPrefCustomUA(): String {
return preferences.getString("custom_ua", "Default User-Agent") ?: "Default User-Agent"
}

private fun getResizeServiceUrl(): String {
return preferences.getString("resize_service_url", "https://resize.sardo.work/?width=300&quality=75&imageUrl=")
?: "https://resize.sardo.work/?width=300&quality=75&imageUrl="
}

override var baseUrl = preferences.getString(BASE_URL_PREF, "https://komikindo.lol")!!

override val client: OkHttpClient = network.cloudflareClient.newBuilder()
.addInterceptor { chain ->
val original = chain.request()
val requestBuilder = original.newBuilder()
.header("User-Agent", getPrefCustomUA())
chain.proceed(requestBuilder.build())
}.build()

override val name = "KomikIndoID"
override val lang = "id"
override val supportsLatest = true
private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

override fun setupPreferenceScreen(screen: PreferenceScreen) {
val customUserAgentPref = EditTextPreference(screen.context).apply {
key = "custom_ua"
title = "Custom User-Agent"
summary = "Masukkan custom User-Agent Anda di sini."
setDefaultValue("Default User-Agent")
}
screen.addPreference(customUserAgentPref)

val resizeServicePref = EditTextPreference(screen.context).apply {
key = "resize_service_url"
title = "Resize Service URL"
summary = "Masukkan URL layanan resize gambar."
setDefaultValue("https://resize.sardo.work/?width=300&quality=75&imageUrl=")
dialogTitle = "Resize Service URL"
dialogMessage = "Masukkan URL layanan resize gambar. (default: https://resize.sardo.work/?width=300&quality=75&imageUrl=)"
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

companion object {
private const val BASE_URL_PREF_TITLE = "Ubah Domain"
private const val BASE_URL_PREF = "overrideBaseUrl"
private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
}

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

override fun mangaDetailsParse(document: Document): SManga {
val infoElement = document.select("div.infoanime").first()!!
val manga = SManga.create()
manga.author = document.select(".infox .spe span:contains(Pengarang)").text()
manga.artist = document.select(".infox .spe span:contains(Ilustrator)").text()
manga.genre = infoElement.select(".genre-info a").joinToString { it.text() }
manga.status = parseStatus(infoElement.select(".infox > .spe > span:nth-child(2)").text())
manga.description = document.select("div.desc > .entry-content.entry-content-single p").text()
manga.thumbnail_url = document.select(".thumb > img:nth-child(1)").attr("src").substringBeforeLast("?")
return manga
}

private fun parseStatus(status: String): Int = when {
status.contains("berjalan", true) -> SManga.ONGOING
status.contains("tamat", true) -> SManga.COMPLETED
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
val value = date.split(' ')[0].toInt()
return Calendar.getInstance().apply {
when {
"detik" in date -> add(Calendar.SECOND, -value)
"menit" in date -> add(Calendar.MINUTE, -value)
"jam" in date -> add(Calendar.HOUR_OF_DAY, -value)
"hari" in date -> add(Calendar.DATE, -value)
"minggu" in date -> add(Calendar.DATE, -value * 7)
"bulan" in date -> add(Calendar.MONTH, -value)
"tahun" in date -> add(Calendar.YEAR, -value)
}
}.timeInMillis
}

override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
chapter.chapter_number = Regex("""Chapter\s([0-9]+)""")
.find(chapter.name)
?.groupValues?.get(1)?.toFloat() ?: 0f
}

override fun pageListParse(document: Document): List<Page> {
    return document.select("div.img-landmine img")
        .mapIndexed { i, element ->
            val url = element.attr("onError").substringAfter("src='").substringBefore("';")
            Page(i, "", url)
        }
}

override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

override fun getFilterList() = FilterList(
SortFilter(),
Filter.Header("NOTE: Ignored if using text search!"),
AuthorFilter(),
YearFilter(),
Filter.Separator(),
OriginalLanguageFilter(getOriginalLanguage()),
FormatFilter(getFormat()),
DemographicFilter(getDemographic()),
StatusFilter(getStatus()),
ContentRatingFilter(getContentRating()),
ThemeFilter(getTheme()),
GenreFilter(getGenre()),
)

private class AuthorFilter : Filter.Text("Author")

private class YearFilter : Filter.Text("Year")

private class SortFilter : UriPartFilter(
"Sort By",
arrayOf(
Pair("A-Z", "title"),
Pair("Z-A", "titlereverse"),
Pair("Latest Update", "update"),
Pair("Latest Added", "latest"),
Pair("Popular", "popular"),
),
)

private class OriginalLanguage(name: String, val id: String = name) : Filter.CheckBox(name)
private class OriginalLanguageFilter(originalLanguage: List<OriginalLanguage>) :
Filter.Group<OriginalLanguage>("Original language", originalLanguage)
private fun getOriginalLanguage() = listOf(
OriginalLanguage("Japanese (Manga)", "Manga"),
OriginalLanguage("Chinese (Manhua)", "Manhua"),
OriginalLanguage("Korean (Manhwa)", "Manhwa"),
)

private class Format(name: String, val id: String = name) : Filter.CheckBox(name)
private class FormatFilter(formatList: List<Format>) :
Filter.Group<Format>("Format", formatList)
private fun getFormat() = listOf(
Format("Black & White", "0"),
Format("Full Color", "1"),
)

private class Demographic(name: String, val id: String = name) : Filter.CheckBox(name)
private class DemographicFilter(demographicList: List<Demographic>) :
Filter.Group<Demographic>("Publication Demographic", demographicList)
private fun getDemographic() = listOf(
Demographic("Josei", "josei"),
Demographic("Seinen", "seinen"),
Demographic("Shoujo", "shoujo"),
Demographic("Shounen", "shounen"),
)

private class Status(name: String, val id: String = name) : Filter.CheckBox(name)
private class StatusFilter(statusList: List<Status>) :
Filter.Group<Status>("Status", statusList)
private fun getStatus() = listOf(
Status("Ongoing", "Ongoing"),
Status("Completed", "Completed"),
)

private class ContentRating(name: String, val id: String = name) : Filter.CheckBox(name)
private class ContentRatingFilter(contentRating: List<ContentRating>) :
Filter.Group<ContentRating>("Content Rating", contentRating)
private fun getContentRating() = listOf(
ContentRating("Ecchi", "ecchi"),
ContentRating("Gore", "gore"),
ContentRating("Sexual Violence", "sexual-violence"),
ContentRating("Smut", "smut"),
)

private class Theme(name: String, val id: String = name) : Filter.CheckBox(name)
private class ThemeFilter(themeList: List<Theme>) :
Filter.Group<Theme>("Story Theme", themeList)
private fun getTheme() = listOf(
Theme("Alien", "aliens"),
Theme("Animal", "animals"),
Theme("Cooking", "cooking"),
Theme("Crossdressing", "crossdressing"),
Theme("Delinquent", "delinquents"),
Theme("Demon", "demons"),
Theme("Ecchi", "ecchi"),
Theme("Gal", "gyaru"),
Theme("Genderswap", "genderswap"),
Theme("Ghost", "ghosts"),
Theme("Harem", "harem"),
Theme("Incest", "incest"),
Theme("Loli", "loli"),
Theme("Mafia", "mafia"),
Theme("Magic", "magic"),
Theme("Martial Arts", "martial-arts"),
Theme("Military", "military"),
Theme("Monster Girls", "monster-girls"),
Theme("Monsters", "monsters"),
Theme("Music", "music"),
Theme("Ninja", "ninja"),
Theme("Office Workers", "office-workers"),
Theme("Police", "police"),
Theme("Post-Apocalyptic", "post-apocalyptic"),
Theme("Reincarnation", "reincarnation"),
Theme("Reverse Harem", "reverse-harem"),
Theme("Samurai", "samurai"),
Theme("School Life", "school-life"),
Theme("Shota", "shota"),
Theme("Smut", "smut"),
Theme("Supernatural", "supernatural"),
Theme("Survival", "survival"),
Theme("Time Travel", "time-travel"),
Theme("Traditional Games", "traditional-games"),
Theme("Vampires", "vampires"),
Theme("Video Games", "video-games"),
Theme("Villainess", "villainess"),
Theme("Virtual Reality", "virtual-reality"),
Theme("Zombies", "zombies"),
)

private class Genre(name: String, val id: String = name) : Filter.CheckBox(name)
private class GenreFilter(genreList: List<Genre>) :
Filter.Group<Genre>("Genre", genreList)
private fun getGenre() = listOf(
Genre("Action", "action"),
Genre("Adventure", "adventure"),
Genre("Comedy", "comedy"),
Genre("Crime", "crime"),
Genre("Drama", "drama"),
Genre("Fantasy", "fantasy"),
Genre("Girls Love", "girls-love"),
Genre("Harem", "harem"),
Genre("Historical", "historical"),
Genre("Horror", "horror"),
Genre("Isekai", "isekai"),
Genre("Magical Girls", "magical-girls"),
Genre("Mecha", "mecha"),
Genre("Medical", "medical"),
Genre("Philosophical", "philosophical"),
Genre("Psychological", "psychological"),
Genre("Romance", "romance"),
Genre("Sci-Fi", "sci-fi"),
Genre("Shoujo Ai", "shoujo-ai"),
Genre("Shounen Ai", "shounen-ai"),
Genre("Slice of Life", "slice-of-life"),
Genre("Sports", "sports"),
Genre("Superhero", "superhero"),
Genre("Thriller", "thriller"),
Genre("Tragedy", "tragedy"),
Genre("Wuxia", "wuxia"),
Genre("Yuri", "yuri"),
)

private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
fun toUriPart() = vals[state].second
}
}