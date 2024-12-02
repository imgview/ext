package eu.kanade.tachiyomi.extension.id.mangkomik

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Request
import org.jsoup.nodes.Document

class SirenKomik :
    MangaThemesia(
        "Siren Komik",
        "https://sirenkomik.my.id",
        "id",
        "/manga",
    ),
    ConfigurableSource {

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
        )
        return FilterList(filters)
    }

    override fun pageListParse(document: Document): List<Page> {
        val scriptContent = document.selectFirst("script:containsData(SExtras.imgview)")?.data()
            ?: return super.pageListParse(document)

        val jsonString = scriptContent.substringAfter("SExtras.imgview(").substringBefore(");")
        val sExtrasData = json.decodeFromString<SExtras>(jsonString)
        val imageUrls: List<String> = sExtrasData.sources.firstOrNull()?.images ?: return emptyList()

        return imageUrls.mapIndexed { index, imageUrl -> 
            Page(index, document.location(), imageUrl)
        }
    }

    @Serializable
    data class SExtras(
        val sources: List<ImageSource>,
    )

    @Serializable
    data class ImageSource(
        val source: String,
        val images: List<String>,
    )
}