package sk.brock.nahravac

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Typ naučeného kroku. */
enum class StepType { CLICK, ACCOUNT, CAPTION, TEXT, PROGRESS, POST }

/**
 * Ako nájsť ten istý prvok na obrazovke pri prehrávaní.
 * [index] = poradie uzla medzi všetkými uzlami okna s rovnakým viewId a triedou (do hĺbky).
 * [relX]/[relY] = stred uzla / veľkosť obrazovky (len ak uzol nemá platné súradnice).
 */
data class Selector(
    val pkg: String,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val cls: String?,
    val index: Int,
    val relX: Float,
    val relY: Float,
)

/** Jeden krok postupu; [gapMs] = pauza od predošlého kroku, [value] = text (TEXT) alebo hodnota posuvníka (PROGRESS). */
data class Step(val type: StepType, val sel: Selector, val value: String? = null, val gapMs: Long = 0)

/** Naučený postup nahrávania uložený ako JSON v filesDir/macro.json. */
object Macro {
    private const val FILE = "macro.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Načíta postup; chýbajúci alebo poškodený súbor = prázdny zoznam. */
    fun load(ctx: Context): List<Step> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("steps") ?: JSONArray()
            val out = ArrayList<Step>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = try {
                    StepType.valueOf(o.optString("type"))
                } catch (e: Exception) {
                    continue
                }
                val s = o.optJSONObject("sel") ?: continue
                val sel = Selector(
                    pkg = s.optString("pkg", ""),
                    viewId = s.str("viewId"),
                    text = s.str("text"),
                    desc = s.str("desc"),
                    cls = s.str("cls"),
                    index = s.optInt("index", 0),
                    relX = s.optDouble("relX", 0.0).toFloat(),
                    relY = s.optDouble("relY", 0.0).toFloat(),
                )
                out.add(Step(type, sel, o.str("value"), o.optLong("gapMs", 0L)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, steps: List<Step>) {
        val arr = JSONArray()
        for (step in steps) {
            val s = JSONObject()
                .put("pkg", step.sel.pkg)
                .putOpt("viewId", step.sel.viewId)
                .putOpt("text", step.sel.text)
                .putOpt("desc", step.sel.desc)
                .putOpt("cls", step.sel.cls)
                .put("index", step.sel.index)
                .put("relX", step.sel.relX.toDouble())
                .put("relY", step.sel.relY.toDouble())
            val o = JSONObject()
                .put("type", step.type.name)
                .put("sel", s)
                .putOpt("value", step.value)
                .put("gapMs", step.gapMs)
            arr.put(o)
        }
        file(ctx).writeText(JSONObject().put("version", 1).put("steps", arr).toString(), Charsets.UTF_8)
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Slovenský popis kroku, napr. `3. klik „Nahrať“ (id …/abc)`; [number] > 0 pridá poradie na začiatok. */
    fun describe(step: Step, number: Int = 0): String {
        val prefix = if (number > 0) "$number. " else ""
        val sel = step.sel
        val body = when (step.type) {
            StepType.CLICK -> {
                val label = sel.text ?: sel.desc
                val id = sel.viewId?.substringAfterLast('/')
                when {
                    label != null && id != null -> "klik „${shorten(label)}“ (id …/$id)"
                    label != null -> "klik „${shorten(label)}“"
                    id != null -> "klik (bez textu, id …/$id)"
                    else -> "klik (${(sel.relX * 100).toInt()} %, ${(sel.relY * 100).toInt()} %)"
                }
            }
            StepType.ACCOUNT -> "účet"
            StepType.CAPTION -> "popis"
            StepType.TEXT -> "text „${shorten(step.value ?: "")}“"
            StepType.PROGRESS -> "posuvník na ${step.value ?: "?"}"
            StepType.POST -> "UVEREJNIŤ"
        }
        return prefix + body
    }

    /** "1 krok", "3 kroky", "12 krokov". */
    fun stepsText(count: Int): String = when {
        count == 1 -> "1 krok"
        count in 2..4 -> "$count kroky"
        else -> "$count krokov"
    }

    private fun shorten(s: String): String {
        val oneLine = s.replace('\n', ' ').trim()
        return if (oneLine.length > 30) oneLine.take(29) + "…" else oneLine
    }

    /** JSON null / chýbajúce / prázdne -> null (org.json inak vracia text "null"). */
    private fun JSONObject.str(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").ifEmpty { null }
    }
}
