package eu.kanade.tachiyomi.extension.id.komikstation

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class KomikStation : MangaThemesia(
    "Noromax",
    "https://komikstation.co",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString("overrideBaseUr", super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(4)
        .build()

    private val Cover = "https://"

    override fun searchMangaFromElement(element: Element): SManga {
    return SManga.create().apply {
        val GambarOri = element.select("img").imgAttr()
        thumbnail_url = "$Cover$GambarOri"
        title = element.select("a").attr("title")
        setUrlWithoutDomain(element.select("a").attr("href"))
    }
}

// Untuk thumbnail di halaman detail manga
    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
    val seriesDetails = document.select(seriesThumbnailSelector)
    val GambarOri = seriesDetails.imgAttr()
    thumbnail_url = "$Cover$GambarOri"
    title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
}

    override fun pageListParse(document: Document): List<Page> {
    val resizeServiceUrl = getResizeServiceUrl()
    val imageElements = document.select("#readerarea img")
    return imageElements.mapIndexedNotNull { index, element ->
        val imageUrl = element.absUrl("src")
        if (imageUrl.endsWith("999.png")) null
        else Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
    }
}

        override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar."
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Ubah Domain"
            dialogMessage = "Original: $baseUrl"

    setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                baseUrl = newUrl
                preferences.edit().putString("overrideBaseUrl", newUrl).apply()
                summary = "Current domain: $newUrl" // Update summary untuk domain yang baru
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }
}