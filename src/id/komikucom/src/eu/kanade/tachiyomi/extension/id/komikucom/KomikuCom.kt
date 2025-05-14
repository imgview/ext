package eu.kanade.tachiyomi.extension.id.komikucom

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

// Cloudflare JS Challenge Interceptor (copied from Tachiyomi core)
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val initialResponse = chain.proceed(request)
        // Jika tidak JS challenge, langsung kembalikan
        if (initialResponse.code != 503) return initialResponse

        val html = initialResponse.body?.string() ?: return initialResponse
        val jsChallenge = Regex("setTimeout\\(function\\(\\\\\\\\\\\\\\\\\) \").find(html)
        // Jika tidak challenge JS, return
        if (jsChallenge == null) return initialResponse

        // Jalankan challenge via Rhino (mirip implementasi core)
        val solutionCookie = eu.kanade.tachiyomi.network.interceptor.CloudflareSolver.solve(html, request.url.toString())
            ?: return initialResponse

        // Tutup response lama
        initialResponse.close()

        // Ulangi request dengan cookie clearance
        val newReq = request.newBuilder()
            .header("Cookie", solutionCookie)
            .build()
        return chain.proceed(newReq)
    }
}

class KomikuCom : MangaThemesia(
    "Komik",
    "https://komiku.com",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(1, 1)
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor(Interceptor { chain: Interceptor.Chain ->
            val original = chain.request()
            val req = original.newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3")
                .addHeader("Referer", baseUrl)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.5")
                .addHeader("DNT", "1")
                .addHeader("Connection", "keep-alive")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(req)
        })
        .build()

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override fun pageListParse(document: Document): List<Page> {
        val scriptContent = document.selectFirst("script:containsData(ts_reader)")?.data()
            ?: throw Exception("Script containing 'ts_reader' not found")
        val jsonString = scriptContent.substringAfter("ts_reader.run(").substringBefore(");")
        val tsReader = json.decodeFromString<TSReader>(jsonString)
        val imageUrls = tsReader.sources.firstOrNull()?.images
            ?: throw Exception("No images found in ts_reader data")
        val filtered = imageUrls.filterNot { it.contains("/banner/", true) }
        val resizeServiceUrl = preferences.getString("resize_service_url", null)

        return filtered.mapIndexed { idx, url ->
            Page(idx, document.location(), "${'$'}{resizeServiceUrl ?: ""}$url")
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = preferences.getString(key, "") ?: ""
            setDefaultValue("")
            dialogTitle = "Resize Service URL"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                summary = newValue
                true
            }
        }
        screen.addPreference(resizePref)

        val basePref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = baseUrl
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: ${'$'}{super.baseUrl}"
            setOnPreferenceChangeListener { _, newValue ->
                val v = newValue as String
                baseUrl = v
                preferences.edit().putString(key, v).apply()
                summary = v
                true
            }
        }
        screen.addPreference(basePref)

        val cookiesPref = EditTextPreference(screen.context).apply {
            key = "cookies"
            title = "Cookies"
            summary = preferences.getString(key, "") ?: ""
            setDefaultValue("")
            dialogTitle = "Cookies"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }
        screen.addPreference(cookiesPref)
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
    }

    @Serializable
    data class TSReader(val sources: List<ReaderImageSource>)

    @Serializable
    data class ReaderImageSource(val source: String, val images: List<String>)
}
