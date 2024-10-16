package eu.kanade.tachiyomi.extension.id.tukangkomik

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TukangKomik : MangaThemesia("TukangKomik", "https://tukangkomik.id", "id") {

    private val preferences: SharedPreferences by lazy {
        application.getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    private val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, "https://tukangkomik.id")!!

    private val rateLimitInterceptor = RateLimitInterceptor(1, 2, TimeUnit.SECONDS)

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            var request = chain.request()
            val url = HttpUrl.parse(baseUrl) ?: throw Exception("Invalid URL")
            val newUrl = request.url().newBuilder()
                .scheme(url.scheme())
                .host(url.host())
                .build()
            request = request.newBuilder().url(newUrl).build()
            chain.proceed(request)
        }
        .addInterceptor(rateLimitInterceptor)
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val prefBaseUrl = EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Base URL"
            summary = "Set the base URL of the source"
            defaultValue = "https://tukangkomik.id"
            dialogTitle = "Base URL"
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString(PREF_BASE_URL_KEY, newUrl).commit()
            }
        }
        screen.addPreference(prefBaseUrl)
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "base_url"
    }
}
