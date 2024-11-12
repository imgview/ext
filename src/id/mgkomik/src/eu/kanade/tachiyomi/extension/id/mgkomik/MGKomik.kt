package eu.kanade.tachiyomi.extension.id.mgkomik

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import okhttp3.Request
import eu.kanade.tachiyomi.network.GET
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomik : Madara(
    "MG Komik",
    "https://mgkomik.id",
    "id",
    SimpleDateFormat("dd MMM yy", Locale.US),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = false
    
    override val baseUrl by lazy { getPrefBaseUrl() }

    override val mangaSubString = "komik"
    
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Base URL preference for main domain
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(super.baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: ${super.baseUrl}"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)

        // Image proxy URL preference
        val imageProxyUrlPref = EditTextPreference(screen.context).apply {
            key = IMAGE_PROXY_URL_PREF
            title = IMAGE_PROXY_URL_PREF_TITLE
            summary = IMAGE_PROXY_URL_PREF_SUMMARY
            this.setDefaultValue(DEFAULT_IMAGE_PROXY_URL)
            dialogTitle = IMAGE_PROXY_URL_PREF_TITLE
            dialogMessage = "Default: $DEFAULT_IMAGE_PROXY_URL"

            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(imageProxyUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    private fun getImageProxyUrl(): String = preferences.getString(IMAGE_PROXY_URL_PREF, DEFAULT_IMAGE_PROXY_URL)!!

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != super.baseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, super.baseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, super.baseUrl)
                    .apply()
            }
        }
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random())) // added for webview, and removed in interceptor for normal use
    }
    
    override fun pageListParse(document: Document): List<Page> {
        // Memanggil super untuk memulai dari hasil yang sudah ada di kelas Madara
        val pagesFromSuper = super.pageListParse(document)

        // Memproses script dan deobfuscate chapter data
        val script = document.selectFirst("script:containsData(chapter_data)")?.data()
            ?: throw Exception("chapter_data script not found")

        val deobfuscated = Deobfuscator.deobfuscateScript(script)
            ?: throw Exception("Unable to deobfuscate chapter_data script")

        val keyMatch = KEY_REGEX.find(deobfuscated)?.groupValues
            ?: throw Exception("Unable to find key")

        val chapterData = json.decodeFromString<CDT>(
            CHAPTER_DATA_REGEX.find(script)?.groupValues?.get(1) ?: throw Exception("Unable to get chapter data")
        )
        val postId = POST_ID_REGEX.find(script)?.groupValues?.get(1) ?: throw Exception("Unable to get post_id")
        val otherId = OTHER_ID_REGEX.findAll(script).firstOrNull { it.groupValues[1] != "post" }?.groupValues?.get(2)
            ?: throw Exception("Unable to get other id")
        val key = otherId + keyMatch[1] + postId + keyMatch[2] + postId
        val salt = chapterData.s.decodeHex()

        val unsaltedCiphertext = Base64.decode(chapterData.ct, Base64.DEFAULT)
        val ciphertext = salted.plus(salt).plus(unsaltedCiphertext)

        // Mendekripsi ciphertext
        val decrypted = CryptoAES.decrypt(Base64.encodeToString(ciphertext, Base64.DEFAULT), key)
        val data = json.decodeFromString<List<String>>(decrypted)

        // Mendapatkan URL proxy untuk gambar
        val imageProxyUrl = getImageProxyUrl()

        // Memproses gambar dengan URL yang telah diproxy
        val pagesFromDecryptedData = data.mapIndexed { idx, imageUrl ->
            val proxiedImageUrl = "$imageProxyUrl${java.net.URLEncoder.encode(imageUrl, "UTF-8")}"
            Page(idx + pagesFromSuper.size, document.location(), proxiedImageUrl)
        }

        // Menggabungkan hasil dari super dan data yang baru diproses
        return pagesFromSuper + pagesFromDecryptedData
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(9, 2)
        .build()

    override fun popularMangaNextPageSelector() = ".wp-pagenavi span.current + a"

    override fun searchMangaNextPageSelector() = "a.page.larger"

    override fun latestUpdatesRequest(page: Int): Request =
        if (useLoadMoreRequest()) {
            loadMoreRequest(page, popular = false)
        } else {
            GET("$baseUrl/$mangaSubString/${searchPage(page)}", headers)
        }

    override val chapterUrlSuffix = ""
    
    @Serializable
data class CDT(
    val ct: String, // Ciphertext dari data chapter
    val s: String   // Salt untuk dekripsi
)

    companion object {
        private val KEY_REGEX by lazy { Regex("""_id\s+\+\s+'(.*?)'\s+\+\s+post_id\s+\+\s+'(.*?)'\s+\+\s+post_id""") }
        private val CHAPTER_DATA_REGEX by lazy { Regex("""var chapter_data\s*=\s*'(.*?)'""") }
        private val POST_ID_REGEX by lazy { Regex("""var post_id\s*=\s*'(.*?)'""") }
        private val OTHER_ID_REGEX by lazy { Regex("""var (\w+)_id\s*=\s*'(.*?)'""") }
        private const val RESTART_APP = "Untuk menerapkan perubahan, restart aplikasi."
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Untuk penggunaan sementara. Memperbarui aplikasi akan menghapus pengaturan"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"

        // New constants for image proxy
        private const val IMAGE_PROXY_URL_PREF_TITLE = "Ubah Image Proxy"
        private const val IMAGE_PROXY_URL_PREF = "imageProxyUrl"
        private const val IMAGE_PROXY_URL_PREF_SUMMARY = "URL proxy untuk gambar (harus mencakup parameter lengkap). Contoh: https://resize.sardo.work/?width=300&quality=75&imageUrl="
        private const val DEFAULT_IMAGE_PROXY_URL = "https://resize.sardo.work/?width=300&quality=75&imageUrl="
    }
}
