package sk.brock.nahravac

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Typ naučeného kroku: CLICK = ťuknutie, LONG = podržanie, SWIPE = potiahnutie, BACK = tlačidlo späť,
 * ACCOUNT = výber účtu (pri prehrávaní podľa kanála), CAPTION = popis (vloží sa sám), POST = Uverejniť.
 */
enum class StepType { CLICK, LONG, SWIPE, BACK, ACCOUNT, CAPTION, POST }

/**
 * Čo bolo pod prstom pri učení. [text]/[desc] = štítok prvku (len stabilný text, nikdy meno účtu),
 * [viewId] je len slabá pomôcka (TikTok má ID zahmlené a menia sa), [index] = poradie uzla medzi uzlami okna
 * s rovnakým viewId a triedou. [relX]/[relY] = miesto ťuknutia / veľkosť obrazovky (pri SWIPE začiatok ťahu).
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

/**
 * Jeden krok postupu. [gapMs] = pauza od predošlého kroku, [sig] = podpis obrazovky (texty, ktoré na nej boli
 * pri učení; krok sa vykoná, až keď ich je na obrazovke dosť), pri SWIPE [endX]/[endY] = koniec ťahu
 * (pomer k obrazovke) a [durMs] = trvanie ťahu.
 */
data class Step(
    val type: StepType,
    val sel: Selector,
    val gapMs: Long = 0,
    val sig: List<String> = emptyList(),
    val endX: Float = 0f,
    val endY: Float = 0f,
    val durMs: Long = 0,
)

/**
 * Naučené postupy uložené ako JSON: nahratie videa (filesDir/macro.json) a prepnutie účtu (filesDir/switch.json).
 * Formát má verziu [VERSION]; staršie verzie sa nenačítajú (postup sa raz naučí znova).
 */
object Macro {
    const val VERSION = 2
    private const val FILE = "macro.json"
    private const val SWITCH_FILE = "switch.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun switchFile(ctx: Context) = File(ctx.filesDir, SWITCH_FILE)

    /** Postup nahratia videa; chýbajúci, poškodený alebo starší súbor = prázdny zoznam. */
    fun load(ctx: Context): List<Step> = loadFile(file(ctx))

    /** Naučené prepnutie účtu (končí krokom ACCOUNT); prázdny zoznam = nenaučené. */
    fun loadSwitch(ctx: Context): List<Step> = loadFile(switchFile(ctx))

    fun save(ctx: Context, steps: List<Step>) = saveFile(file(ctx), steps)

    fun saveSwitch(ctx: Context, steps: List<Step>) = saveFile(switchFile(ctx), steps)

    /** Zmaže oba postupy (nahratie aj prepnutie účtu). */
    fun clear(ctx: Context) {
        file(ctx).delete()
        switchFile(ctx).delete()
    }

    private fun loadFile(f: File): List<Step> {
        if (!f.isFile) return emptyList()
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            if (root.optInt("version", 1) != VERSION) return emptyList()
            val arr = root.optJSONArray("steps") ?: JSONArray()
            val out = ArrayList<Step>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fromJson(o)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFile(f: File, steps: List<Step>) {
        val arr = JSONArray()
        for (step in steps) arr.put(toJson(step))
        f.writeText(JSONObject().put("version", VERSION).put("steps", arr).toString(), Charsets.UTF_8)
    }

    /** Krok ako JSON (aj do diagnostiky). */
    fun toJson(step: Step): JSONObject {
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
            .put("gapMs", step.gapMs)
            .put("sig", JSONArray(step.sig))
        if (step.type == StepType.SWIPE) {
            o.put("endX", step.endX.toDouble()).put("endY", step.endY.toDouble())
        }
        if (step.durMs > 0) o.put("durMs", step.durMs)
        return o
    }

    private fun fromJson(o: JSONObject): Step? {
        val type = try {
            StepType.valueOf(o.optString("type"))
        } catch (e: Exception) {
            return null
        }
        val s = o.optJSONObject("sel") ?: return null
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
        val sigArr = o.optJSONArray("sig")
        val sig = if (sigArr == null) {
            emptyList()
        } else {
            (0 until sigArr.length()).mapNotNull { sigArr.optString(it).trim().ifEmpty { null } }
        }
        return Step(
            type = type,
            sel = sel,
            gapMs = o.optLong("gapMs", 0L),
            sig = sig,
            endX = o.optDouble("endX", 0.0).toFloat(),
            endY = o.optDouble("endY", 0.0).toFloat(),
            durMs = o.optLong("durMs", 0L),
        )
    }

    /**
     * Slovenský popis kroku, napr. `3. ťuk „Next“`; [number] > 0 pridá poradie na začiatok,
     * [withScreen] pridá prvé texty podpisu obrazovky (na ktorej sa krok robí).
     */
    fun describe(step: Step, number: Int = 0, withScreen: Boolean = false): String {
        val prefix = if (number > 0) "$number. " else ""
        val sel = step.sel
        val label = sel.text ?: sel.desc
        val at = "(${pct(sel.relX)}, ${pct(sel.relY)})"
        val body = when (step.type) {
            StepType.CLICK -> if (label != null) "ťuk „${shorten(label)}“" else "ťuk na miesto $at"
            StepType.LONG -> if (label != null) "podržanie „${shorten(label)}“" else "podržanie na mieste $at"
            StepType.SWIPE -> "potiahnutie $at → (${pct(step.endX)}, ${pct(step.endY)}), ${step.durMs} ms"
            StepType.BACK -> "späť"
            StepType.ACCOUNT -> "účet (vyberie sa podľa kanála)"
            StepType.CAPTION -> "popis (vloží sa sám)"
            StepType.POST -> "UVEREJNIŤ"
        }
        val screen = if (withScreen && step.sig.isNotEmpty()) {
            " · obrazovka: " + step.sig.take(3).joinToString(", ") { shorten(it) }
        } else {
            ""
        }
        return prefix + body + screen
    }

    /** "1 krok", "3 kroky", "12 krokov". */
    fun stepsText(count: Int): String = when {
        count == 1 -> "1 krok"
        count in 2..4 -> "$count kroky"
        else -> "$count krokov"
    }

    private fun pct(f: Float): String = "${(f * 100).roundToInt()} %"

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
