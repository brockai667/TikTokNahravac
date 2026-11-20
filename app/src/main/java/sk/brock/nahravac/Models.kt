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
    /** Šablóna adresy obalu: {video_base} = adresa videa bez ".mp4", {slug} = názov súboru videa bez ".mp4". Bez nej sa obal vezme zo snímky videa. */
    val cover: String? = null,
) {
    /** Krátky ASCII identifikátor kanála (do názvov súborov a kľúčov). */
    val slug: String
        get() = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "").ifEmpty { "kanal" }

    /** Kľúče účtu na rozpoznanie v hlavičke profilu a v prepínači účtov TikToku (pozri [Accounts]). */
    val accountKeys: List<String>
        get() = Accounts.keys(this)
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
    /** Adresa obalu z fabriky (podľa [Channel.cover]); null = žiadny, použije sa snímka z videa. */
    val coverUrl: String? = null,
    /** Adresa kampane na contentrewards.com (pole `campaign_url` vo formáte "drop"); null = nič sa neodosiela. */
    val campaignUrl: String? = null,
) {
    /** Kľúč pre SharedPreferences (hotovo, Uri stiahnutého videa). Pri "drop" podľa súboru, lebo poradie sa mení. */
    val key: String
        get() = if (channel.format == Repo.FORMAT_DROP) "${channel.slug}#$id" else "${channel.slug}#$number"

    /** Názov súboru v DCIM/TikTokNahravac/. */
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

/** Príspevok z Instagramu/YouTube (vyšiel cez Buffer), ktorý sa má odoslať do kampane Content Rewards (kľúč `submits` vo feede). */
data class Submit(
    val id: String,
    val platform: String,
    val account: String,
    val caption: String,
    val title: String?,
    val dueAt: java.time.Instant?,
    val campaignUrl: String,
    val ytChannelId: String?,
)
