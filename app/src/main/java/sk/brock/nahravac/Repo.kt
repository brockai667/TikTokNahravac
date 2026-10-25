package sk.brock.nahravac

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
            out.add(Channel(name = o.str("name") ?: url, tiktok = o.str("tiktok") ?: "", stateUrl = url))
        }
        return out
    }

    /** Najnovšie epizódy (podľa čísla) s videom. Epizódy bez `video` alebo s nečíselným kľúčom sa preskočia. */
    fun fetchEpisodes(channel: Channel, limit: Int): List<Episode> {
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
                    text = o.str("text") ?: "",
                    videoUrl = video,
                    due = o.str("due"),
                )
            )
        }
        return list.sortedByDescending { it.number }.take(limit)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "TikTokNahravac/0.1")
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
