package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostika pri chybe prehrávania (krok sa nenašiel, účet sa neprepol): JSON s popisom kroku, jeho podpisom
 * a zoznamom viditeľných prvkov TikToku do Download/TikTokNahravac/diag-<čas>.json (Android 10+; na 8-9 nič).
 * Používateľ ich pošle tlačidlom Diagnostika v appke.
 */
object Diag {
    private const val SUBDIR = "TikTokNahravac"
    private const val MAX_NODES = 1_500
    private const val POSTED_FILE = "posted.jsonl"
    private const val SUBMITTED_FILE = "submitted.jsonl"

    /**
     * Uloží diagnostiku; true = uložená. [macro] = celý naučený postup, [log] = priebeh prehrávania.
     * Volať mimo hlavného vlákna. Chyby sa prehltnú (diagnostika je len pomoc).
     */
    fun save(
        service: AccessibilityService,
        reason: String,
        channel: String?,
        step: Step?,
        number: Int,
        macro: List<Step> = emptyList(),
        log: List<String> = emptyList(),
    ): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val o = build(service, reason, channel, step, number)
            if (macro.isNotEmpty()) {
                val arr = JSONArray()
                for ((i, s) in macro.withIndex()) arr.put(Macro.toJson(s).put("describe", Macro.describe(s, i + 1)))
                o.put("macro", arr)
            }
            if (log.isNotEmpty()) o.put("log", JSONArray(log))
            write(service, o.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun build(service: AccessibilityService, reason: String, channel: String?, step: Step?, number: Int): JSONObject {
        val size = Nodes.screenSize(service)
        val o = JSONObject()
            .put("reason", reason)
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            .put("app", versionName(service))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("screen", "${size.x}x${size.y}")
            .putOpt("channel", channel)
        if (step != null) {
            o.put(
                "step",
                JSONObject()
                    .put("number", number)
                    .put("describe", Macro.describe(step))
                    .put("data", Macro.toJson(step)),
            )
        }
        val nodes = try {
            Nodes.collect(Nodes.tiktokRoots(service))
        } catch (e: Exception) {
            emptyList()
        }
        val visible = nodes.filter { it.isVisibleToUser }
        o.put("screenSignature", JSONArray(Nodes.signature(nodes, emptyList())))
        val arr = JSONArray()
        val r = Rect()
        for (n in visible.take(MAX_NODES)) {
            n.getBoundsInScreen(r)
            arr.put(
                JSONObject()
                    .putOpt("text", Nodes.ownText(n))
                    .putOpt("desc", Nodes.ownDesc(n))
                    .putOpt("cls", n.className?.toString())
                    .putOpt("id", n.viewIdResourceName)
                    .put("bounds", r.toShortString())
                    .put("clickable", n.isClickable)
                    .put("editable", n.isEditable)
                    .put("focused", n.isFocused)
                    .putOpt("pkg", n.packageName?.toString()),
            )
        }
        o.put("visibleNodes", arr)
        if (visible.size > MAX_NODES) o.put("truncated", visible.size)
        return o
    }

    /**
     * Zapíše jeden JSON riadok o uverejnenej epizóde do Download/TikTokNahravac/posted.jsonl (Android 10+; na 8-9 nič).
     * Súbor sa prečíta a prepíše aj s novým riadkom (MediaStore nezaručuje režim "append"). Chyby sa prehltnú.
     */
    fun appendPosted(ctx: Context, ep: Episode) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val line = JSONObject()
                .put("episode", ep.key)
                .put("channel", ep.channel.slug)
                .put("title", ep.title)
                .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date()))
                .toString() + "\n"
            appendLine(ctx, POSTED_FILE, line)
        } catch (e: Exception) {
            // záznam je len pomoc
        }
    }

    /** Výsledok odoslania príspevku do kampane Content Rewards: riadok do Download/TikTokNahravac/submitted.jsonl. */
    fun appendSubmitted(ctx: Context, episode: String, channel: String, campaignUrl: String, ok: Boolean, reason: String, dry: Boolean) {
        try {
            val o = JSONObject()
                .put("episode", episode)
                .put("channel", channel)
                .put("campaign_url", campaignUrl)
                .put("ok", ok)
                .put("reason", reason)
                .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date()))
            if (dry) o.put("dry", true)
            appendLine(ctx, SUBMITTED_FILE, o.toString() + "\n")
        } catch (e: Exception) {
            // záznam je len pomoc
        }
    }

    private fun appendLine(ctx: Context, fileName: String, line: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            var uri: Uri? = null
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%"),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
            val existing = uri
            if (existing == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/")
                }
                val created = resolver.insert(collection, values) ?: return
                resolver.openOutputStream(created)?.use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } else {
                val old = resolver.openInputStream(existing)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val prefix = if (old.isEmpty() || old.endsWith("\n")) old else old + "\n"
                resolver.openOutputStream(existing, "wt")?.use { it.write((prefix + line).toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            // záznam je len pomoc
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    @RequiresApi(29)
    private fun write(ctx: Context, text: String) {
        val name = "diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore nevytvoril záznam")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Nedá sa zapisovať do Download")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (e2: Exception) {
            }
            throw e
        }
    }

    /** Najnovšie diagnostiky (najviac [limit], najnovšia prvá). Na Androide 8-9 prázdny zoznam. */
    fun latest(ctx: Context, limit: Int): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = ArrayList<Uri>()
        try {
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("diag-%.json", "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) out.add(ContentUris.withAppendedId(collection, c.getLong(0)))
            }
        } catch (e: Exception) {
            // bez diagnostík
        }
        return out
    }
}
