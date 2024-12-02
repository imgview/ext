package eu.kanade.tachiyomi.extension.id.mangkomik

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SirenKomik :
    MangaThemesia(
        "Siren Komik",
        "https://sirenkomik.my.id",
        "id",
        "/manga",
    ),
    ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override val client = super.client.newBuilder()
        .setRandomUserAgent(
            preferences.getPrefUAType(),
            preferences.getPrefCustomUA(),
        )
        .rateLimit(1)
        .build()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isEmpty()) {
            super.searchMangaRequest(page, query, filters)
        } else {
            GET("$baseUrl/?s=$query&page=$page", headers)
        }
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Note: Can't be used with text search!"),
            Filter.Separator(),
            StatusFilter(intl["status_filter_title"], statusOptions),
            TypeFilter(intl["type_filter_title"], typeFilterOptions),
            OrderByFilter(intl["order_by_filter_title"], orderByFilterOptions),
        )
        if (!genrelist.isNullOrEmpty()) {
            filters.addAll(
                listOf(
                    Filter.Header(intl["genre_exclusion_warning"]),
                    GenreListFilter(intl["genre_filter_title"], getGenreList()),
                ),
            )
        } else {
            filters.add(
                Filter.Header(intl["genre_missing_warning"]),
            )
        }
        if (hasProjectPage) {
            filters.addAll(
                mutableListOf<Filter<*>>(
                    Filter.Separator(),
                    Filter.Header(intl["project_filter_warning"]),
                    Filter.Header(intl.format("project_filter_name", name)),
                    ProjectFilter(intl["project_filter_title"], projectFilterOptions),
                ),
            )
        }
        return FilterList(filters)
    }

    override fun pageListParse(document: Document): List<Page> {
    // Cari elemen <script> yang mengandung 'SExtras.imgview'
    val scriptContent = document.selectFirst("script:containsData(SExtras.imgview)")?.data()
        ?: return super.pageListParse(document)
    
    // Ekstrak JSON dari fungsi 'SExtras.imgview({...});'
    val jsonString = scriptContent.substringAfter("SExtras.imgview(").substringBefore(");")
    
    // Parsing JSON ke dalam model data
    val sExtrasData = json.decodeFromString<SExtras>(jsonString)
    
    // Ambil URL gambar dari sumber pertama
    val imageUrls = sExtrasData.sources.firstOrNull()?.images ?: return emptyList()
    
    // Konversi URL gambar menjadi daftar Page
    return imageUrls.mapIndexed { index, imageUrl -> Page(index, document.location(), imageUrl) }
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addRandomUAPreferenceToScreen(screen)
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
