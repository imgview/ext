package eu.kanade.tachiyomi.multisrc.mangathemesia

import android.app.Application
import androidx.preference.EditTextPreference
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

abstract class Domain(
    name: String,
    baseUrl: String,
    lang: String,
    // Menambahkan 'override' karena 'dateFormat' sudah ada di superclass
    override val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
) : ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Implementasi setupPreferenceScreen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        createBaseUrlPreference(screen)
    }

    // Fungsi untuk membuat baseUrl preference
    private fun createBaseUrlPreference(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                summary = "Current domain: $newUrl" // Update summary untuk domain yang baru
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    // Fungsi tambahan untuk memformat tanggal menjadi String
    fun formatDate(date: Date): String {
        return dateFormat.format(date) // Menggunakan SimpleDateFormat untuk mengubah Date ke String
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }
}
