package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale

class KomikCast : MangaThemesia(
    "Komik Cast",
    "https://komikcast.cz",
    "id",
    "/daftar-komik",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getPrefCustomUA(): String {
        return preferences.getString("custom_ua", "Default User-Agent") ?: "Default User-Agent"
    }

    private fun getResizeServiceUrl(): String {
        return preferences.getString("resize_service_url", "https://resize.sardo.work/?width=300&quality=75&imageUrl=") ?: "https://resize.sardo.work/?width=300&quality=75&imageUrl="
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, "https://komiku.com")!!

    override val client = super.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", getPrefCustomUA())
            chain.proceed(requestBuilder.build())
        }
        .rateLimit(1)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun popularMangaRequest(page: Int) = customPageRequest(page, "orderby", "popular")
    override fun latestUpdatesRequest(page: Int) = customPageRequest(page, "sortby", "update")

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""

        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }

    override fun searchMangaSelector() = "div.list-update_item"

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        title = element.selectFirst("h3.title")!!.ownText()
    }

    override val seriesDetailsSelector = "div.komik_info:has(.komik_info-content)"
    override val seriesTitleSelector = "h1.komik_info-content-body-title"
    override val seriesDescriptionSelector = ".komik_info-description-sinopsis"
    override val seriesAltNameSelector = ".komik_info-content-native"
    override val seriesGenreSelector = ".komik_info-content-genre a"
    override val seriesThumbnailSelector = ".komik_info-content-thumbnail img"
    override val seriesStatusSelector = ".komik_info-content-info:contains(Status)"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst(seriesDetailsSelector)?.let { seriesDetails ->
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text()
                ?.replace("bahasa indonesia", "", ignoreCase = true)?.trim().orEmpty()
            artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
            author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
            description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }.trim()
            // Add alternative name to manga description
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altNamePrefix$altName".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            // Add series type (manga/manhwa/manhua/other) to genre
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.forLanguageTag(lang))
                    } else {
                        char.toString()
                    }
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
            thumbnail_url = seriesDetails.select(seriesThumbnailSelector).imgAttr()
        }
    }

    override fun chapterListSelector() = "div.komik_info-chapters li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val urlElements = element.select("a")
        setUrlWithoutDomain(urlElements.attr("href"))
        name = element.select(".chapter-link-item").text()
        date_upload = parseChapterDate2(element.select(".chapter-link-time").text())
    }

    private fun parseChapterDate2(date: String): Long {
        return if (date.endsWith("ago")) {
            val value = date.split(' ')[0].toInt()
            when {
                "min" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, value * -1)
                }.timeInMillis
                "hour" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, value * -1)
                }.timeInMillis
                "day" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * -1)
                }.timeInMillis
                "week" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * 7 * -1)
                }.timeInMillis
                "month" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, value * -1)
                }.timeInMillis
                "year" in date -> Calendar.getInstance().apply {
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

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#chapter_body .main-reading-area img.size-full")
            .distinctBy { img -> img.imgAttr() }
            .mapIndexed { i, img -> Page(i, document.location(), img.imgAttr()) }
    }

    override val hasProjectPage: Boolean = true
    override val projectPageString = "/project-list"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addPathSegments("page/$page/").addQueryParameter("s", query)
            return GET(url.build(), headers)
        }

        url.addPathSegment(mangaUrlDirectory.substring(1))
            .addPathSegments("page/$page/")

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    url.addQueryParameter("status", filter.selectedValue())
                }
                is TypeFilter -> {
                    url.addQueryParameter("type", filter.selectedValue())
                }
                is OrderByFilter -> {
                    url.addQueryParameter("orderby", filter.selectedValue())
                }
                is GenreListFilter -> {
                    filter.state
                        .filter { it.state != Filter.TriState.STATE_IGNORE }
                        .forEach {
                            val value = if (it.state == Filter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                            url.addQueryParameter("genre[]", value)
                        }
                }
                // if site has project page, default value "hasProjectPage" = false
                is ProjectFilter -> {
                    if (filter.selectedValue() == "project-filter-on") {
                        url.setPathSegment(0, projectPageString.substring(1))
                    }
                }
                else -> { /* Do Nothing */ }
            }
        }
        return GET(url.build(), headers)
    }

    private class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        ),
    )

    private class TypeFilter : SelectFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
        ),
    )

    private class OrderByFilter(defaultOrder: String? = null) : SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "titleasc"),
            Pair("Z-A", "titledesc"),
            Pair("Update", "update"),
            Pair("Popular", "popular"),
        ),
        defaultOrder,
    )

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Separator(),
            StatusFilter(),
            TypeFilter(),
            OrderByFilter(),
            Filter.Header(intl["genre_exclusion_warning"]),
            GenreListFilter(intl["genre_filter_title"], getGenreList()),
            Filter.Separator(),
            Filter.Header(intl["project_filter_warning"]),
            Filter.Header(intl.format("project_filter_name", name)),
            ProjectFilter(intl["project_filter_title"], projectFilterOptions),
        )
        return FilterList(filters)
    }
}

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

        // Preference untuk mengubah base URL
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
                summary = "Current domain: $newUrl" // Update summary untuk domain yang baru
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
    
        @Serializable
    data class TSReader(
        val sources: List<ReaderImageSource>,
    )

    @Serializable
    data class ReaderImageSource(
        val source: String,
        val images: List<String>,
    )
}