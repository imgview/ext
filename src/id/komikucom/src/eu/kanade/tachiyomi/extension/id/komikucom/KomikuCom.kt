package eu.kanade.tachiyomi.extension.id.komikucom

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.util.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.lib.randomua.UserAgentType
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.http2.ErrorCode
import org.jsoup.nodes.Document
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

// Interceptor untuk bypass Cloudflare JS Challenge otomatis
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val initial = chain.proceed(request)
        if (initial.code != 503 || initial.body == null) return initial

        val html = initial.body!!.string().also { initial.close() }
        // Ekstrak JS challenge
        val js = html
            .substringAfter("setTimeout(function(){")
            .substringBefore("}, 1500);")
            .replace("document.cookie", "var cookie") + "\n; cookie;"

        // Eksekusi dengan Rhino
        val cx = Context.enter().apply { optimizationLevel = -1 }
        return try {
            val scope: Scriptable = cx.initStandardObjects()
            // Setup fake document & location
            val loc = cx.newObject(scope)
            loc.defineProperty("href", request.url.toString(), Scriptable.PERMANENT)
            scope.defineProperty("location", loc, Scriptable.PERMANENT)
            val doc = cx.newObject(scope)
            doc.defineProperty("cookie", "", Scriptable.PERMANENT)
            scope.defineProperty("document", doc, Scriptable.PERMANENT)

            // Jalankan challenge
            val result = cx.evaluateString(scope, js, "cf", 1, null)
            val cookie = result?.toString() ?: return initial
            // Ulangi request dengan cookie
            val newReq = request.newBuilder()
                .header("Cookie", cookie)
                .build()
            chain.proceed(newReq)
        } catch (_: Exception) {
            initial
        } finally {
            Context.exit()
        }
    }
}

class KomikuCom : MangaThemesia(
    "Komik",
    "https://komiku.com",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val prefs = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl = prefs.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client: OkHttpClient = super.client.newBuilder()
        // throttle
        .rateLimit(1, 1)
        // random UA
        .setRandomUserAgent(UserAgentType.MOBILE)
        // bypass Cloudflare JS challenge
        .addInterceptor(CloudflareInterceptor())
        // kustom header
        .addInterceptor { chain: Interceptor.Chain ->
            val req = chain.request().newBuilder()
                .header("Referer", baseUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(req)
        }
        .build()

    override fun mangaDetailsParse(document: Document) =
        super.mangaDetailsParse(document).apply {
            title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
        }

    override fun pageListParse(document: Document): List<Page> {
        val script = document.selectFirst("script:containsData(ts_reader)")?.data()
            ?: throw Exception("ts_reader not found")
        val jsonStr = script.substringAfter("ts_reader.run(").substringBefore(");")
        val ts = json.decodeFromString<TSReader>(jsonStr)
        val imgs = ts.sources.firstOrNull()?.images.orEmpty()
        val filtered = imgs.filterNot { it.contains("/banner/", true) }
        val resize = prefs.getString("resize_service_url", null).orEmpty()
        return filtered.mapIndexed { i, u -> Page(i, document.location(), "$resize$u") }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // random UA settings
        addRandomUAPreferenceToScreen(screen.context, screen, prefs)
        // resize URL
        androidx.preference.EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = prefs.getString(key, "") ?: ""
            setDefaultValue("")
            dialogTitle = "Resize Service URL"
            setOnPreferenceChangeListener { _, newV ->
                prefs.edit().putString(key, newV as String).apply(); summary = newV; true
            }
        }.also(screen::addPreference)
        // override domain
        androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF; title = BASE_URL_PREF_TITLE; summary = baseUrl;
            setDefaultValue(baseUrl); dialogTitle = BASE_URL_PREF_TITLE;
            dialogMessage = "Original: ${super.baseUrl}";
            setOnPreferenceChangeListener { _, nv ->
                baseUrl = nv as String; prefs.edit().putString(key,nv).apply(); summary=nv; true
            }
        }.also(screen::addPreference)
        // manual cookies
        androidx.preference.EditTextPreference(screen.context).apply {
            key="cookies"; title="Cookies"; summary=prefs.getString(key,"")?:"";
            setDefaultValue(""); dialogTitle="Cookies";
            setOnPreferenceChangeListener { _, nv -> prefs.edit().putString(key,nv as String).apply(); true }
        }.also(screen::addPreference)
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
    }

    @Serializable data class TSReader(val sources: List<ReaderImageSource>)
    @Serializable data class ReaderImageSource(val source: String, val images: List<String>)
}
