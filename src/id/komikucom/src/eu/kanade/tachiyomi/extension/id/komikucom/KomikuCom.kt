package eu.kanade.tachiyomi.revived.id.komikucom

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.PageList
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class KomikuCom : MangaThemesia("Komiku.com", "https://komiku.com", "id", dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))) {

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img[src]").mapIndexed { i, element ->
            val imageUrl = element.attr("abs:src")
            val resizedImageUrl = "https://resize.sardo.work/?width=300&quality=75&imageUrl=$imageUrl"
            Page(i, "", resizedImageUrl)
        }
    }
}
