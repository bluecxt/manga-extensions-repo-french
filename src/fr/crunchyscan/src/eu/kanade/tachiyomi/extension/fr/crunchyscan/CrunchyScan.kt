package eu.kanade.tachiyomi.extension.fr.crunchyscan

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class CrunchyScan :
    Madara(
        "CrunchyScan",
        "https://cdn.crunchyscan.fr",
        "fr",
        SimpleDateFormat("d MMMM yyyy", Locale.FRANCE),
    ) {
    override val useNewChapterEndpoint = true
}
