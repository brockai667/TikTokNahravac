package sk.brock.nahravac

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Načítanie kanálov (assets) a stavu fabriky (state.json z GitHubu). Volať mimo hlavného vlákna. */
object Repo {

    /** JSON null / chýbajúce / prázdne -> null (org.json inak vracia text "null"). */
    private fun JSONObject.str(name: String): String? {
        if (isNull(name)) return null
        return optString(name, "").trim().ifEmpty { null }
    }

    fun loadChannels(ctx: Context): List<Channel> {
        val text = ctx.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        val arr: JSONArray = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("channels") ?: JSONArray()
        }
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.str("stateUrl") ?: continue
            out.add(
                Channel(
                    name = o.str("name") ?: url,
                    tiktok = o.str("tiktok") ?: "",
                    stateUrl = url,
                    account = o.str("account"),
                    format = (o.str("format") ?: FORMAT_STATE).lowercase(Locale.ROOT),
                    dropKey = o.str("dropKey"),
                    suffix = o.str("suffix"),
                    since = o.str("since"),
                    cover = o.str("cover"),
                )
            )
        }
        return out
    }

    /** Formát "state" = state.json fabriky (kľúč `episodes`). Nový formát = nová vetva v [fetchEpisodes] a tu. */
    const val FORMAT_STATE = "state"

    /** Formát "drop" = tiktok_drop.json z repa tiktok-drop (`{repo: {cta, videos: [...]}}`). */
    const val FORMAT_DROP = "drop"

    fun isSupported(channel: Channel): Boolean =
        channel.format == FORMAT_STATE || (channel.format == FORMAT_DROP && channel.dropKey != null)

    /** Najnovšie epizódy (podľa čísla) s videom; formát kanála určuje parser. */
    fun fetchEpisodes(channel: Channel, limit: Int): List<Episode> = when (channel.format) {
        FORMAT_STATE -> fetchStateEpisodes(channel, limit)
        FORMAT_DROP -> fetchDropEpisodes(channel, limit)
        else -> throw IOException("Nepodporovaný formát: ${channel.format}")
    }

    /** ISO čas s posunom (aj „Z“) alebo len dátum (polnoc UTC); inak null. */
    private fun instantOf(s: String?): Instant? {
        if (s == null) return null
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (e: Exception) {
            try {
                LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** False pre diel starší než [Channel.since]; diel bez dátumu sa ukáže. */
    private fun afterSince(channel: Channel, due: String?): Boolean {
        val since = instantOf(channel.since) ?: return true
        val at = instantOf(due) ?: return true
        return !at.isBefore(since)
    }

    /** Pripojí [Channel.suffix], ak ho text ešte neobsahuje. */
    private fun withSuffix(channel: Channel, text: String): String {
        val s = channel.suffix ?: return text
        if (text.contains(s)) return text
        return if (text.isEmpty()) s else "$text\n\n$s"
    }

    /**
     * Videá jedného repa z tiktok_drop.json, najnovšie (podľa `created_at`/`created`) prvé.
     * Popis = `caption` z fabriky (presne ako pre Buffer); ak chýba, desc (alebo názov) + CTA + hashtagy + [Channel.suffix].
     * id = názov súboru (stabilný kľúč „hotovo“).
     */
    private fun fetchDropEpisodes(channel: Channel, limit: Int): List<Episode> {
        val body = httpGet(channel.stateUrl)
        val repo = JSONObject(body).optJSONObject(channel.dropKey ?: "") ?: return emptyList()
        val videos = repo.optJSONArray("videos") ?: return emptyList()
        val cta = repo.str("cta")
        val brandTags = repo.optJSONArray("brand_hashtags")
        val raw = ArrayList<JSONObject>()
        for (i in 0 until videos.length()) {
            val o = videos.optJSONObject(i) ?: continue
            val url = o.str("url") ?: continue
            if (!url.startsWith("http")) continue
            raw.add(o)
        }
        // Stabilné triedenie: rovnaký dátum ponechá poradie z feedu.
        val sorted = raw.sortedByDescending { it.createdOf() ?: "" }
            .filter { afterSince(channel, it.createdOf()) }
        return sorted.take(limit).mapIndexed { i, o ->
            val title = o.str("title") ?: o.str("file") ?: "Video"
            val tags = (o.optJSONArray("hashtags")?.takeIf { it.length() > 0 } ?: brandTags)
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } } }
                ?.joinToString(" ")
                ?.ifEmpty { null }
            val head = o.str("desc") ?: title.replace('-', ' ')
            val text = o.str("caption")
                ?: withSuffix(channel, listOfNotNull(head, cta, tags).joinToString("\n\n"))
            Episode(
                channel = channel,
                number = sorted.size - i,
                id = o.str("file") ?: title,
                title = title,
                text = text,
                videoUrl = o.str("url")!!,
                due = o.createdOf(),
                coverUrl = coverUrlOf(channel, o.str("url")!!, o.str("file")),
            )
        }
    }

    private fun JSONObject.createdOf(): String? = str("created_at") ?: str("created")

    /**
     * Adresa obalu epizódy zo šablóny [Channel.cover]: {video_base} = [videoUrl] bez koncového ".mp4",
     * {slug} = [file] (pri "drop") alebo posledný segment cesty videa, v oboch prípadoch bez ".mp4".
     * Bez šablóny alebo pri videu, ktorého adresa nekončí na ".mp4", vráti null (appka použije snímku z videa).
     */
    private fun coverUrlOf(channel: Channel, videoUrl: String, file: String?): String? {
        val template = channel.cover ?: return null
        if (!videoUrl.endsWith(".mp4", ignoreCase = true)) return null
        val base = videoUrl.dropLast(".mp4".length)
        val name = file ?: base.substringAfterLast('/')
        val slug = if (name.endsWith(".mp4", ignoreCase = true)) name.dropLast(".mp4".length) else name
        return template.replace("{video_base}", base).replace("{slug}", slug)
    }

    /** Epizódy bez `video` alebo s nečíselným kľúčom sa preskočia. */
    private fun fetchStateEpisodes(channel: Channel, limit: Int): List<Episode> {
        val body = httpGet(channel.stateUrl)
        val episodes = JSONObject(body).optJSONObject("episodes") ?: return emptyList()
        val list = ArrayList<Episode>()
        val keys = episodes.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val number = key.toIntOrNull() ?: continue
            val o = episodes.optJSONObject(key) ?: continue
            val video = o.str("video") ?: continue
            if (!video.startsWith("http")) continue
            list.add(
                Episode(
                    channel = channel,
                    number = number,
                    id = o.str("id") ?: key,
                    title = o.str("title") ?: o.str("id") ?: "Epizóda $number",
                    text = withSuffix(channel, o.str("text") ?: ""),
                    videoUrl = video,
                    due = o.str("due"),
                    coverUrl = coverUrlOf(channel, video, null),
                )
            )
        }
        return list.filter { afterSince(channel, it.due) }.sortedByDescending { it.number }.take(limit)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "TikTokNahravac/0.2")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private val dueFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M. yyyy HH:mm")

    /** ISO čas (napr. 2026-10-26T18:09:00+00:00) -> miestny čas telefónu. */
    fun formatDue(iso: String?): String? {
        if (iso == null) return null
        return try {
            OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(dueFormat)
        } catch (e: Exception) {
            iso
        }
    }
}
