package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Nahrávanie postupu cez lapač dotykov ([Overlay.showCatcher]), ako pri auto-clicker appkách: každý dotyk
 * používateľa sa zachytí, zapíše ako [Step] (s tým, čo bolo pod prstom, a podpisom obrazovky) a hneď sa rovnakým
 * gestom pošle ďalej do TikToku. Nespolieha sa na udalosti TYPE_VIEW_CLICKED (TikTok ich pri mnohých prvkoch neposiela).
 *
 * [Mode.UPLOAD] = nahratie videa (+ → Upload → video → Next … → Post); ťuk do poľa popisu popis rovno vloží,
 * ťuk na Post postup uloží. [Mode.SWITCH] = prepnutie účtu na [target]; ťuk na účet kanála postup uloží.
 * Všetko beží na hlavnom vlákne; výsledok príde cez [onEnd].
 */
class Recorder(
    private val service: AccessibilityService,
    private val overlay: Overlay,
    private val mode: Mode,
    private val caption: String,
    private val target: Channel?,
    /** Kľúče účtov všetkých kanálov: mená účtov sa nikdy neukladajú ako štítok ani do podpisu. */
    private val allKeys: List<String>,
    private val onEnd: (Result) -> Unit,
) {
    enum class Mode { UPLOAD, SWITCH }

    /** POSTED = uložené s krokom Uverejniť, SAVED = uložené tlačidlom Hotovo, ACCOUNT = uložené prepnutie účtu. */
    enum class Result { POSTED, SAVED, ACCOUNT, CANCELLED, EMPTY, FAILED }

    private enum class After { PASS, PASS_AND_FINISH, KEEP }

    companion object {
        /** Texty tlačidla "Uverejniť" (porovnanie bez ohľadu na veľkosť písmen). */
        val POST_LABELS = listOf("Uverejniť", "Zverejniť", "Publikovať", "Post", "Uverejnit")

        private const val MAX_GAP_MS = 10_000L
        const val TAP_MS = 50L
        const val LONG_MS = 600L
        private const val SWIPE_MIN_MS = 100L
        private const val SWIPE_MAX_MS = 1_500L

        /** Štítok potomka sa berie len z malého klikateľného prvku (nie z celého zoznamu). */
        private const val SMALL_HEIGHT = 0.25f
    }

    private val steps = ArrayList<Step>()
    private var lastAt = SystemClock.uptimeMillis()
    private var finished = false
    private val screen = Nodes.screenSize(service)
    private val targetKeys = target?.let { Accounts.keys(it) } ?: emptyList()
    private var note = ""

    /** Zapne lapač dotykov a ukáže kartu (karta je nad lapačom). */
    fun start() {
        overlay.showCatcher { onGesture(it) }
        showStatus()
    }

    // ------------------------------------------------------------------
    // Dotyky
    // ------------------------------------------------------------------

    private fun onGesture(g: Caught) {
        if (finished) {
            pass(g)
            return
        }
        var after = After.PASS
        try {
            after = handle(g)
        } catch (e: Exception) {
            // jeden zlý dotyk nesmie zhodiť nahrávanie; dotyk sa aspoň pošle ďalej
        }
        if (after != After.KEEP) pass(g)
        if (after == After.PASS_AND_FINISH) finish(save = true) else showStatus()
    }

    /** Zapíše krok (ak treba) a povie, čo s dotykom ďalej. */
    private fun handle(g: Caught): After {
        val x = g.x1
        val y = g.y1
        // Naše okná, klávesnica a cudzie okná: dotyk prejde ďalej, nič sa nezapíše.
        if (overlay.cardContains(x, y)) return After.PASS
        if (Nodes.isInIme(service, x, y)) return After.PASS
        val root = Nodes.tiktokRootAt(service, x, y) ?: return After.PASS

        val sig = Nodes.signature(Nodes.collect(Nodes.tiktokRoots(service)), allKeys)
        return when (g.kind) {
            Caught.Kind.TAP -> onTap(root, x, y, sig)
            Caught.Kind.LONG -> {
                val sel = labelSelector(root, x, y)
                steps.add(Step(StepType.LONG, sel, gap(), sig, durMs = LONG_MS))
                After.PASS
            }
            Caught.Kind.SWIPE -> {
                val sel = Selector(
                    pkg = root.packageName?.toString() ?: "",
                    viewId = null,
                    text = null,
                    desc = null,
                    cls = null,
                    index = 0,
                    relX = x / screen.x,
                    relY = y / screen.y,
                )
                steps.add(
                    Step(
                        StepType.SWIPE, sel, gap(), sig,
                        endX = g.x2 / screen.x,
                        endY = g.y2 / screen.y,
                        durMs = swipeMs(g),
                    )
                )
                After.PASS
            }
        }
    }

    private fun onTap(root: AccessibilityNodeInfo, x: Float, y: Float, sig: List<String>): After {
        val deepest = Nodes.deepestAt(root, x.toInt(), y.toInt()) ?: root
        val clickable = Nodes.clickableAncestor(deepest)

        // Pole popisu: zapíše sa CAPTION a popis sa hneď vloží (používateľ nič nepíše).
        val editable = listOfNotNull(deepest, clickable).firstOrNull { it.isEditable }
        if (mode == Mode.UPLOAD && editable != null) {
            steps.add(Step(StepType.CAPTION, selector(editable, x, y, withLabel = false), gap(), sig))
            val filled = try {
                Nodes.fillText(service, editable, caption)
            } catch (e: Exception) {
                false
            }
            if (filled) {
                note = "\n✔ popis vložený"
                return After.KEEP
            }
            // Vloženie nevyšlo: ťuk prejde (otvorí klávesnicu), popis je v schránke - podržať → Vložiť.
            Nodes.copyToClipboard(service, caption)
            note = "\n⚠ popis sa nevložil – podrž pole → Vložiť"
            return After.PASS
        }

        val labels = labelsAt(deepest, clickable)

        if (mode == Mode.SWITCH && labels.any { Accounts.matches(it, targetKeys) }) {
            steps.add(Step(StepType.ACCOUNT, selector(clickable ?: deepest, x, y, withLabel = false), gap(), sig))
            return After.PASS_AND_FINISH
        }

        val isPost = mode == Mode.UPLOAD && labels.any { l -> POST_LABELS.any { it.equals(l, ignoreCase = true) } }
        val sel = selector(labelTarget(deepest, clickable), x, y, withLabel = true)
        steps.add(Step(if (isPost) StepType.POST else StepType.CLICK, sel, gap(), sig))
        return if (isPost) After.PASS_AND_FINISH else After.PASS
    }

    /** Selektor pre podržanie: rovnaké pravidlá štítku ako pri ťuknutí. */
    private fun labelSelector(root: AccessibilityNodeInfo, x: Float, y: Float): Selector {
        val deepest = Nodes.deepestAt(root, x.toInt(), y.toInt()) ?: root
        val clickable = Nodes.clickableAncestor(deepest)
        return selector(labelTarget(deepest, clickable), x, y, withLabel = true)
    }

    /** Všetky štítky pod prstom (na rozpoznanie Uverejniť / účtu). */
    private fun labelsAt(deepest: AccessibilityNodeInfo, clickable: AccessibilityNodeInfo?): List<String> {
        val out = ArrayList<String>()
        if (clickable != null) {
            Nodes.ownText(clickable)?.let { out.add(it) }
            Nodes.ownDesc(clickable)?.let { out.add(it) }
        }
        Nodes.ownText(deepest)?.let { out.add(it) }
        Nodes.ownDesc(deepest)?.let { out.add(it) }
        if (clickable != null && Nodes.bounds(clickable).height() < screen.y * SMALL_HEIGHT) {
            Nodes.label(clickable)?.let { out.add(it) }
        }
        return out
    }

    /** Uzol, ktorého štítok sa uloží: najprv klikateľný predok s použiteľným textom/popisom, potom najhlbší uzol. */
    private fun labelTarget(deepest: AccessibilityNodeInfo, clickable: AccessibilityNodeInfo?): AccessibilityNodeInfo {
        if (clickable != null && hasUsableLabel(clickable)) return clickable
        if (hasUsableLabel(deepest)) return deepest
        return clickable ?: deepest
    }

    private fun hasUsableLabel(n: AccessibilityNodeInfo): Boolean =
        (!n.isEditable && Nodes.ownText(n)?.let { usable(it) } == true) || Nodes.ownDesc(n)?.let { usable(it) } == true

    /** Štítok, podľa ktorého sa dá prvok nájsť aj nabudúce: nie číslo/čas/počet, nie meno účtu, nie dlhý text. */
    private fun usable(label: String): Boolean =
        label.length <= 60 && !Nodes.isVolatile(label) && !Accounts.matches(label, allKeys)

    /** Selektor uzla [node]; poloha = miesto ťuknutia. [withLabel] = false pri účte a poli popisu. */
    private fun selector(node: AccessibilityNodeInfo, x: Float, y: Float, withLabel: Boolean): Selector {
        val text = if (withLabel && !node.isEditable) Nodes.ownText(node)?.takeIf { usable(it) } else null
        val desc = if (withLabel) Nodes.ownDesc(node)?.takeIf { usable(it) } else null
        return Selector(
            pkg = node.packageName?.toString() ?: "",
            viewId = node.viewIdResourceName?.takeIf { it.isNotEmpty() },
            text = text,
            desc = desc,
            cls = node.className?.toString(),
            index = try {
                Nodes.indexOf(node)
            } catch (e: Exception) {
                0
            },
            relX = x / screen.x,
            relY = y / screen.y,
        )
    }

    // ------------------------------------------------------------------
    // Poslanie dotyku ďalej
    // ------------------------------------------------------------------

    private fun swipeMs(g: Caught): Long = g.durMs.coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS)

    private fun pass(g: Caught) {
        val maxX = (screen.x - 1).toFloat()
        val maxY = (screen.y - 1).toFloat()
        val x1 = g.x1.coerceIn(0f, maxX)
        val y1 = g.y1.coerceIn(0f, maxY)
        val path = Path().apply { moveTo(x1, y1) }
        val ms = when (g.kind) {
            Caught.Kind.TAP -> TAP_MS
            Caught.Kind.LONG -> LONG_MS
            Caught.Kind.SWIPE -> {
                path.lineTo(g.x2.coerceIn(0f, maxX), g.y2.coerceIn(0f, maxY))
                swipeMs(g)
            }
        }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        } catch (e: Exception) {
            return
        }
        overlay.passThrough(gesture, ms)
    }

    // ------------------------------------------------------------------
    // Karta a koniec
    // ------------------------------------------------------------------

    /** Čas od predošlého kroku (0..10 s); posunie značku posledného kroku. */
    private fun gap(): Long {
        val now = SystemClock.uptimeMillis()
        val g = (now - lastAt).coerceIn(0L, MAX_GAP_MS)
        lastAt = now
        return g
    }

    /** Tlačidlo Späť na karte: systémové späť, zapíše sa ako krok BACK. */
    private fun back() {
        if (finished) return
        val sig = try {
            Nodes.signature(Nodes.collect(Nodes.tiktokRoots(service)), allKeys)
        } catch (e: Exception) {
            emptyList()
        }
        steps.add(Step(StepType.BACK, Selector("", null, null, null, null, 0, 0f, 0f), gap(), sig))
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        note = ""
        showStatus()
    }

    private fun showStatus() {
        if (finished) return
        val back = OverlayButton("Späť") { back() }
        if (mode == Mode.SWITCH) {
            overlay.show(
                "Prepni účet na ${target?.tiktok ?: ""} – appka sa to raz naučí\n(krok ${steps.size + 1})$note",
                back,
                OverlayButton("Zrušiť") { finish(save = false) },
            )
        } else {
            overlay.show(
                "● Učím sa… krok ${steps.size + 1}\n+ → Upload → prvé video → Next … → ťuk do popisu → Post$note",
                back,
                OverlayButton("Hotovo") { finish(save = true) },
                OverlayButton("Zrušiť") { finish(save = false) },
            )
        }
    }

    /** Ukončí nahrávanie; [save] = uložiť postup (Hotovo, Uverejniť, ťuk na účet). */
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        overlay.hideCatcher()
        val result = when {
            !save -> Result.CANCELLED
            mode == Mode.SWITCH && steps.lastOrNull()?.type != StepType.ACCOUNT -> Result.CANCELLED
            steps.isEmpty() -> Result.EMPTY
            else -> try {
                if (mode == Mode.SWITCH) {
                    Macro.saveSwitch(service, steps)
                    Result.ACCOUNT
                } else {
                    Macro.save(service, steps)
                    if (steps.last().type == StepType.POST) Result.POSTED else Result.SAVED
                }
            } catch (e: Exception) {
                Result.FAILED
            }
        }
        onEnd(result)
    }

    /** Zruší nahrávanie bez uloženia (Stop, vypnutie služby); [onEnd] sa nevolá. */
    fun abort() {
        if (finished) return
        finished = true
        overlay.hideCatcher()
    }
}
