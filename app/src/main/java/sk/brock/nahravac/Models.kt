package sk.brock.nahravac

import android.net.Uri
import java.util.Locale

/** Jeden kanál (fabrika) z assets/channels.json. */
data class Channel(
    val name: String,
    val tiktok: String,
    val stateUrl: String,
    /** Text, ktorý TikTok ukazuje v prepínači účtov (ak sa líši od [tiktok]). */
    val account: String? = null,
    /** Formát stavu fabriky; "state" = state.json, "drop" = tiktok_drop.json (pozri [Repo.fetchEpisodes]). */
    val format: String = Repo.FORMAT_STATE,
    /** Pri formáte "drop": kľúč repa v tiktok_drop.json (napr. "coldcasedaily667"). */
    val dropKey: String? = null,
    /** Text pripojený na koniec poskladaného popisu (napr. kredit hudby), ak v ňom ešte nie je. */
    val suffix: String? = null,
    /** ISO čas; staršie diely sa neukážu (na TikTok ich ešte poslal Buffer). */
    val since: String? = null,
) {
    /** Krátky ASCII identifikátor kanála (do názvov súborov a kľúčov). */
    val slug: String
        get() = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "").ifEmpty { "kanal" }

    /** Normalizovaný názov účtu na hľadanie v prepínači účtov TikToku (malé písmená, bez "@"). */
    val accountKey: String
        get() = (account ?: tiktok).lowercase(Locale.ROOT).trim().removePrefix("@")
}

/** Jedna epizóda zo state.json (len také, ktoré majú video). */
data class Episode(
    val channel: Channel,
    val number: Int,
    val id: String,
    val title: String,
    val text: String,
    val videoUrl: String,
    val due: String?,
) {
    /** Kľúč pre SharedPreferences (hotovo, Uri stiahnutého videa). Pri "drop" podľa súboru, lebo poradie sa mení. */
    val key: String
        get() = if (channel.format == Repo.FORMAT_DROP) "${channel.slug}#$id" else "${channel.slug}#$number"

    /** Názov súboru v Movies/TikTokNahravac/. */
    val fileName: String
        get() {
            val segment = Uri.parse(videoUrl).lastPathSegment
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: String.format(Locale.ROOT, "ep-%03d", number)
            val withExt = if (segment.endsWith(".mp4", ignoreCase = true)) segment else "$segment.mp4"
            return "${channel.slug}_$withExt"
        }
}
