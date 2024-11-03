package eu.kanade.tachiyomi.extension.id.shinigami

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Shinigami : Madara("Shinigami", "https://shinigami06.com", "id"), ConfigurableSource {
    override val id = 3411809758861089969

    override val baseUrl by lazy { getPrefBaseUrl() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(super.baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: ${super.baseUrl}"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)

        // Custom User-Agent Preference
        val userAgentPref = EditTextPreference(screen.context).apply {
            key = CUSTOM_UA_PREF
            title = "Custom User-Agent"
            summary = "Isi User-Agent kustom yang diinginkan"
            setDefaultValue(DEFAULT_UA)

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(userAgentPref)

        // Image Resize Service Preference
        val imageResizeServicePref = EditTextPreference(screen.context).apply {
            key = IMAGE_RESIZE_SERVICE_PREF
            title = "Ganti Layanan Resize Gambar"
            summary = "Ganti dengan URL layanan resize gambar yang diinginkan"
            setDefaultValue(DEFAULT_IMAGE_RESIZE_SERVICE)

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(imageResizeServicePref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, super.baseUrl)!!
    private fun getPrefUserAgent(): String = preferences.getString(CUSTOM_UA_PREF, DEFAULT_UA)!!
    private fun getImageResizeService(): String = preferences.getString(IMAGE_RESIZE_SERVICE_PREF, DEFAULT_IMAGE_RESIZE_SERVICE)!!

    override fun headersBuilder() = super.headersBuilder().apply {
        add("User-Agent", getPrefUserAgent())
        add("Sec-Fetch-Dest", "document")
        add("Sec-Fetch-Mode", "navigate")
        add("Sec-Fetch-Site", "same-origin")
        add("Upgrade-Insecure-Requests", "1")
        add("X-Requested-With", randomString((1..20).random()))
    }
    
    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
        .build()

    @Serializable
    data class CDT(val ct: String, val s: String)

    override fun pageListParse(document: Document): List<Page> {
        val script = document.selectFirst("script:containsData(chapter_data)")?.data()
            ?: throw Exception("chapter_data script not found")

        val deobfuscated = Deobfuscator.deobfuscateScript(script)
            ?: throw Exception("Unable to deobfuscate chapter_data script")

        val keyMatch = KEY_REGEX.find(deobfuscated)?.groupValues
            ?: throw Exception("Unable to find key")

        val chapterData = json.decodeFromString<CDT>(
            CHAPTER_DATA_REGEX.find(script)?.groupValues?.get(1) ?: throw Exception("Unable to get chapter data"),
        )
        val postId = POST_ID_REGEX.find(script)?.groupValues?.get(1) ?: throw Exception("Unable to get post_id")
        val otherId = OTHER_ID_REGEX.findAll(script).firstOrNull { it.groupValues[1] != "post" }?.groupValues?.get(2) ?: throw Exception("Unable to get other id")
        val key = otherId + keyMatch[1] + postId + keyMatch[2] + postId
        val salt = chapterData.s.decodeHex()

        val unsaltedCiphertext = Base64.decode(chapterData.ct, Base64.DEFAULT)
        val ciphertext = salt + unsaltedCiphertext

        val decrypted = CryptoAES.decrypt(Base64.encodeToString(ciphertext, Base64.DEFAULT), key)
        val data = json.decodeFromString<List<String>>(decrypted)

        val resizeService = getImageResizeService()
        return data.mapIndexed { idx, it ->
            // Menggunakan layanan resize kustom
            val resizedImageUrl = "$resizeService?width=300&quality=75&imageUrl=$it"
            Page(idx, document.location(), resizedImageUrl)
        }
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }

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

        private const val CUSTOM_UA_PREF = "customUserAgent"
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36"

        private const val IMAGE_RESIZE_SERVICE_PREF = "imageResizeService"
        private const val DEFAULT_IMAGE_RESIZE_SERVICE = "https://resize.sardo.work"
    }
}