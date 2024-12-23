package eu.kanade.tachiyomi.extension.id.komikmama

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import eu.kanade.tachiyomi.lib.domain.createBaseUrlPreference
import java.util.Locale

class KomikMama : MangaThemesia(
    "KomikMama",
    "https://komikmama.org",
    "id",
    "/komik",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
)
