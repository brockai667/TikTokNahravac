package sk.brock.nahravac

import java.util.Locale

/**
 * Rozpoznanie našich TikTok účtov podľa textov na obrazovke (meno v hlavičke profilu, zoznam účtov).
 * Kľúč = normalizovaný text (malé písmená, len [a-z0-9]); kľúče kratšie než 5 znakov sa ignorujú.
 * Príklady: "Plain Sight" -> "plainsight" sedí na kľúč "plainsight667"; "@min.dblowndaily" -> "mindblowndaily".
 */
object Accounts {
    private const val MIN_LEN = 5

    fun norm(s: String): String = s.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }

    /** Kľúče kanála: [Channel.account] (ak je), handle bez "@" a názov kanála. */
    fun keys(ch: Channel): List<String> =
        listOfNotNull(ch.account, ch.tiktok.removePrefix("@"), ch.name)
            .map { norm(it) }
            .filter { it.length >= MIN_LEN }
            .distinct()

    /** Text [label] patrí účtu s kľúčmi [keys]: obsahuje niektorý kľúč, alebo je začiatkom kľúča. */
    fun matches(label: String?, keys: List<String>): Boolean {
        if (label == null || keys.isEmpty()) return false
        val n = norm(label)
        if (n.length < MIN_LEN) return false
        return keys.any { n.contains(it) || it.startsWith(n) }
    }
}
