package sk.brock.nahravac

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Nahratie toho istého dielu z mobilu aj na YouTube (Short) a Instagram (Reel); zapína kľúč `phone_posts` vo feede.
 * Tu je tabuľka cieľov podľa sady (`dropKey`), kľúče „hotovo“ a lokálne odoslania do kampane; samotné klikanie je v [Replayer].
 */
object PhonePosts {
    const val YOUTUBE = "youtube"
    const val INSTAGRAM = "instagram"
    val PLATFORMS = listOf(YOUTUBE, INSTAGRAM)

    /** Diely staršie než toto (podľa created_at) sa na YouTube/Instagram už nedonahrávajú. */
    private const val MAX_AGE_MS = 24 * 3_600_000L

    /** Lokálne odoslania staršie než toto sa pri zápise zahodia. */
    private const val LOCAL_KEEP_MS = 3 * 24 * 3_600_000L

    class Target(val ytName: String, val ytChannelId: String, val igUser: String)

    /** Sada (dropKey) -> kanál YouTube a účet Instagramu. */
    private val TARGETS = mapOf(
        "chatdiff" to Target("Chat Diff", "UCFPRMI0Wl_29rOGPZpGPCrw", "chatdiffclips"),
        "clutchdiff" to Target("Clutch Diff", "UCx_9Bxen9v003cx-pqXT8bQ", "clutchdiff"),
    )

    fun target(set: String?): Target? = set?.let { TARGETS[it] }

    fun target(ep: Episode): Target? = target(ep.channel.dropKey)

    /** Kľúč „hotovo“ v [Store]: `<sada>#<súbor>#<platforma>`. */
    fun key(ep: Episode, platform: String): String = "${ep.channel.dropKey}#${ep.id}#$platform"

    fun isFresh(ep: Episode): Boolean {
        val at = Repo.instantOf(ep.due) ?: return false
        return Instant.now().toEpochMilli() - at.toEpochMilli() <= MAX_AGE_MS
    }

    /** Platformy z [enabled], na ktoré sa diel ešte má nahrať (cieľ existuje, nie je starý, nie je hotovo, < [AutoRun.MAX_TRIES] pokusov). */
    fun pending(ctx: Context, ep: Episode, enabled: Set<String>): List<String> {
        if (enabled.isEmpty() || target(ep) == null || !isFresh(ep)) return emptyList()
        return PLATFORMS.filter { p ->
            p in enabled && !Store.isPostDone(ctx, key(ep, p)) && Store.postTries(ctx, key(ep, p)) < AutoRun.MAX_TRIES
        }
    }

    /** Po úspešnom nahratí založí lokálny „submit“ (rovnaké polia ako položka `submits` vo feede) pre [AutoRun.dueSubmits]. */
    fun addLocalSubmit(ctx: Context, ep: Episode, platform: String) {
        val camp = ep.campaignUrl ?: return
        val t = target(ep) ?: return
        val now = Instant.now()
        val arr = JSONArray()
        try {
            val old = JSONArray(Store.getLocalSubmits(ctx))
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                val at = try {
                    Instant.parse(o.optString("due"))
                } catch (e: Exception) {
                    null
                }
                if (at != null && now.toEpochMilli() - at.toEpochMilli() < LOCAL_KEEP_MS) arr.put(o)
            }
        } catch (e: Exception) {
            // poškodený zápis sa zahodí
        }
        val id = key(ep, platform)
        val o = JSONObject()
            .put("id", id)
            .put("platform", platform)
            .put("account", if (platform == INSTAGRAM) t.igUser else ep.channel.dropKey ?: ep.channel.name)
            .put("caption", ep.text)
            .put("title", ep.title)
            .put("due", now.toString())
            .put("campaign_url", camp)
            .put("yt_channel_id", t.ytChannelId)
        arr.put(o)
        Store.setLocalSubmits(ctx, arr.toString())
    }

    fun localSubmits(ctx: Context): List<Submit> {
        val out = ArrayList<Submit>()
        try {
            val arr = JSONArray(Store.getLocalSubmits(ctx))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val due = try {
                    Instant.parse(o.optString("due"))
                } catch (e: Exception) {
                    null
                }
                out.add(
                    Submit(
                        id = o.optString("id"), platform = o.optString("platform"), account = o.optString("account"),
                        caption = o.optString("caption"), title = o.optString("title").ifEmpty { null }, dueAt = due,
                        campaignUrl = o.optString("campaign_url"), ytChannelId = o.optString("yt_channel_id").ifEmpty { null },
                    ),
                )
            }
        } catch (e: Exception) {
            // poškodený zápis = žiadne lokálne odoslania
        }
        return out.filter { it.id.isNotEmpty() && it.campaignUrl.isNotEmpty() }
    }
}
