package eu.kanade.tachiyomi.multisrc.mangathemesia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.SoftReference
import java.text.SimpleDateFormat
import java.util.Locale

abstract class MangaThemesiaAlt(
    name: String,
    baseUrl: String,
    lang: String,
    mangaUrlDirectory: String = "/manga",
    dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US),
    private val randomUrlPrefKey: String = "pref_auto_random_url",
) : MangaThemesia(name, baseUrl, lang, mangaUrlDirectory, dateFormat), ConfigurableSource {

    protected open val listUrl = "$mangaUrlDirectory/list-mode/"
    protected open val listSelector = "div#content div.soralist ul li a.series"
    
    // Mengambil SharedPreferences dengan lazy initialization
    protected val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000).also {
            if (it.contains("__random_part_cache")) {
                it.edit().remove("__random_part_cache").apply()
            }
            if (it.contains("titles_without_random_part")) {
                it.edit().remove("titles_without_random_part").apply()
            }
        }
    }

    // Override baseUrl untuk mengambil dari SharedPreferences atau default
    override var baseUrl: String = preferences.getString(BASE_URL_PREF, baseUrl) ?: baseUrl

    // Fungsi untuk mengupdate baseUrl
    private fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl
        preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
    }

    // Mengonfigurasi tampilan pengaturan (preferences)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Tambahkan SwitchPreferenceCompat untuk opsi dinamis (misalnya untuk mengatur URL acak)
        val switchPref = SwitchPreferenceCompat(screen.context).apply {
            key = randomUrlPrefKey
            title = intl["pref_dynamic_url_title"]
            summary = intl["pref_dynamic_url_summary"]
            setDefaultValue(true)
        }
        screen.addPreference(switchPref)

        // Tambahkan EditTextPreference untuk mengganti baseUrl
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)  // Menggunakan baseUrl yang sudah diinisialisasi
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                updateBaseUrl(newUrl)  // Update baseUrl ketika ada perubahan
                summary = "Current domain: $newUrl"  // Update summary dengan domain baru
                true
            }
        }
        screen.addPreference(baseUrlPref)  // Tambahkan ke screen
}

    private fun getRandomUrlPref() = preferences.getBoolean(randomUrlPrefKey, true)

    private val mutex = Mutex()
    private var cachedValue: SoftReference<Map<String, String>>? = null
    private var fetchTime = 0L

    private suspend fun getUrlMapInternal(): Map<String, String> {
        if (fetchTime + 3600000 < System.currentTimeMillis()) {
            // Reset cache jika sudah kadaluarsa
            cachedValue = null
        }

        cachedValue?.get()?.let {
            return it
        }
        return mutex.withLock {
            cachedValue?.get()?.let {
                return it
            }

            fetchUrlMap().also {
                cachedValue = SoftReference(it)
                fetchTime = System.currentTimeMillis()
                preferences.urlMapCache = it
            }
        }
    }

    protected open fun fetchUrlMap(): Map<String, String> {
        client.newCall(GET("$baseUrl$listUrl", headers)).execute().use { response ->
            val document = response.asJsoup()

            return document.select(listSelector).associate {
                val url = it.absUrl("href")

                val slug = url.removeSuffix("/")
                    .substringAfterLast("/")

                val permaSlug = slug
                    .replaceFirst(slugRegex, "")

                permaSlug to slug
            }
        }
    }

    protected fun getUrlMap(cached: Boolean = false): Map<String, String> {
        return if (cached && cachedValue == null) {
            preferences.urlMapCache
        } else {
            runBlocking { getUrlMapInternal() }
        }
    }

    // Cache dalam preference untuk URL WebView
    private var SharedPreferences.urlMapCache: Map<String, String>
        get(): Map<String, String> {
            val value = getString("url_map_cache", "{}")!!
            return try {
                json.decodeFromString(value)
            } catch (_: Exception) {
                emptyMap()
            }
        }
        set(newMap) = edit().putString("url_map_cache", json.encodeToString(newMap)).apply()

    override fun searchMangaParse(response: Response): MangasPage {
        val mp = super.searchMangaParse(response)

        if (!getRandomUrlPref()) return mp

        val mangas = mp.mangas.toPermanentMangaUrls()

        return MangasPage(mangas, mp.hasNextPage)
    }

    protected fun List<SManga>.toPermanentMangaUrls(): List<SManga> {
        return onEach {
            val slug = it.url
                .removeSuffix("/")
                .substringAfterLast("/")

            val permaSlug = slug
                .replaceFirst(slugRegex, "")

            it.url = "$mangaUrlDirectory/$permaSlug/"
        }
    }

    protected open val slugRegex = Regex("""^(\d+-)""")

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (!getRandomUrlPref()) return super.mangaDetailsRequest(manga)

        val slug = manga.url
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")
            .replaceFirst(slugRegex, "")

        val randomSlug = getUrlMap()[slug] ?: slug

        return GET("${getCurrentBaseUrl()}$mangaUrlDirectory/$randomSlug/", headers)
    }

    override fun getMangaUrl(manga: SManga): String {
        if (!getRandomUrlPref()) return super.getMangaUrl(manga)

        val slug = manga.url
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")
            .replaceFirst(slugRegex, "")

        val randomSlug = getUrlMap(true)[slug] ?: slug

        return "${getCurrentBaseUrl()}$mangaUrlDirectory/$randomSlug/"
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    // Fungsi untuk mendapatkan base URL dari preferensi atau default
    private fun getCurrentBaseUrl(): String {
        return preferences.getString(BASE_URL_PREF, baseUrl) ?: baseUrl
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }
}