package sk.brock.nahravac

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Nahrávanie postupu: sleduje, čo používateľ ručne robí v TikToku, a ukladá to ako [Step]y.
 * Všetko beží na hlavnom vlákne (udalosti služby dostupnosti aj tlačidlá karty).
 */
class Recorder(
    private val service: TikTokAccessibilityService,
    private val overlay: Overlay,
    private val episode: Episode,
    private val caption: String,
    private val onEnd: () -> Unit,
) {
    companion object {
        /** Texty tlačidla "Uverejniť" (porovnanie bez ohľadu na veľkosť písmen). */
        val POST_LABELS = listOf("Uverejniť", "Zverejniť", "Publikovať", "Post", "Uverejnit")

        /** Akcie plávajúcej lišty pri úprave textu - nahrávať sa nemajú (popis nastaví krok CAPTION). */
        private val TOOLBAR_LABELS = setOf(
            "vložiť", "prilepiť", "paste", "kopírovať", "copy", "vystrihnúť", "cut", "vybrať všetko", "select all",
        )

        private const val MAX_GAP_MS = 10_000L
    }

    /** Rozpísaná úprava poľa (text) alebo posuvníka, ktorá sa zapíše ako krok pri ďalšom kliknutí / konci. */
    private class Pending(
        val progress: Boolean,
        val sel: Selector,
        var value: String,
        val firstAt: Long,
        var lastAt: Long,
    )

    private val steps = ArrayList<Step>()
    private val pending = LinkedHashMap<String, Pending>()
    private var lastAt = SystemClock.uptimeMillis()
    private var finished = false
    private val captionPrefix = caption.trim().take(25)
    private val accountKey = episode.channel.accountKey
    private val metrics = service.resources.displayMetrics
    private val imePackages: Set<String> = try {
        service.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Spustí TikTok na domovskej stránke a ukáže kartu. False, ak sa TikTok nepodarilo otvoriť. */
    fun start(): Boolean {
        if (!TikTokShare.launchHome(service)) return false
        showStatus()
        return true
    }

    fun onEvent(event: AccessibilityEvent) {
        if (finished) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnored(pkg)) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event)
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> onRange(event)
                else -> {}
            }
        } catch (e: Exception) {
            // jedna zlá udalosť nesmie zhodiť nahrávanie
        }
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg == service.packageName ||
            pkg == "com.android.systemui" ||
            pkg in imePackages ||
            pkg.contains("launcher", ignoreCase = true)

    // ------------------------------------------------------------------
    // Udalosti
    // ------------------------------------------------------------------

    private fun onClick(event: AccessibilityEvent) {
        val src = event.source ?: return
        val label = Nodes.label(src)
        val norm = label?.let { Nodes.normalize(it) }

        // Prilepenie / kopírovanie z plávajúcej lišty: zahodiť, nezapisovať ani nepreplachovať rozpísaný text.
        if (norm != null && norm in TOOLBAR_LABELS) return

        flushPending()

        val sel = Nodes.selector(src, metrics.widthPixels, metrics.heightPixels)
        val editable = src.isEditable
        val type = when {
            !editable && norm != null && accountKey.isNotEmpty() && norm.contains(accountKey) -> StepType.ACCOUNT
            !editable && label != null && POST_LABELS.any { it.equals(label.trim(), ignoreCase = true) } -> StepType.POST
            else -> StepType.CLICK
        }

        if (type == StepType.POST) addCaptionFromScreen(src)

        val finalSel = if (type == StepType.ACCOUNT) sel.copy(text = null, desc = null) else sel
        steps.add(Step(type, finalSel, null, gap()))

        if (type == StepType.POST) {
            finish(save = true)
        } else {
            showStatus()
        }
    }

    private fun onTextChanged(event: AccessibilityEvent) {
        val src = event.source ?: return
        if (!src.isEditable || src.isPassword) return
        val key = "t:${src.hashCode()}"
        val text = src.text?.toString() ?: event.text.joinToString("")
        val now = SystemClock.uptimeMillis()
        val p = pending[key]
        if (p == null) {
            val sel = Nodes.selector(src, metrics.widthPixels, metrics.heightPixels)
            pending[key] = Pending(false, sel, text, now, now)
        } else {
            p.value = text
            p.lastAt = now
        }
    }

    private fun onRange(event: AccessibilityEvent) {
        val src = event.source ?: return
        val range = src.rangeInfo ?: return
        val key = "p:${src.hashCode()}"
        val value = range.current.toString()
        val now = SystemClock.uptimeMillis()
        val p = pending[key]
        if (p == null) {
            val sel = Nodes.selector(src, metrics.widthPixels, metrics.heightPixels)
            pending[key] = Pending(true, sel, value, now, now)
        } else {
            p.value = value
            p.lastAt = now
        }
    }

    // ------------------------------------------------------------------
    // Zápis krokov
    // ------------------------------------------------------------------

    /** Čas od predošlého kroku (0..10 s); posunie značku posledného kroku. */
    private fun gap(): Long {
        val now = SystemClock.uptimeMillis()
        val g = (now - lastAt).coerceIn(0L, MAX_GAP_MS)
        lastAt = now
        return g
    }

    /** Zapíše rozpísané úpravy polí/posuvníkov ako kroky (v poradí, v akom sa začali meniť). */
    private fun flushPending() {
        if (pending.isEmpty()) return
        for (p in pending.values.sortedBy { it.firstAt }) {
            val g = (p.firstAt - lastAt).coerceIn(0L, MAX_GAP_MS)
            lastAt = p.lastAt
            val step = when {
                p.progress -> Step(StepType.PROGRESS, p.sel, p.value, g)
                captionPrefix.isNotEmpty() && p.value.contains(captionPrefix) -> Step(StepType.CAPTION, p.sel, null, g)
                else -> Step(StepType.TEXT, p.sel, p.value, g)
            }
            steps.add(step)
        }
        pending.clear()
    }

    /**
     * Záloha: ak TikTok nehlásil zmeny textu, pred uverejnením skúsime nájsť na obrazovke pole,
     * v ktorom je náš popis, a zapísať krok CAPTION.
     */
    private fun addCaptionFromScreen(from: AccessibilityNodeInfo) {
        if (captionPrefix.isEmpty() || steps.any { it.type == StepType.CAPTION }) return
        var field: AccessibilityNodeInfo? = null
        Nodes.walk(Nodes.root(from)) { n ->
            if (n.isEditable && n.text?.toString()?.contains(captionPrefix) == true) {
                field = n
                false
            } else {
                true
            }
        }
        val f = field ?: return
        val sel = Nodes.selector(f, metrics.widthPixels, metrics.heightPixels)
        steps.add(Step(StepType.CAPTION, sel, null, gap()))
    }

    // ------------------------------------------------------------------
    // Karta a koniec
    // ------------------------------------------------------------------

    private fun showStatus() {
        overlay.show(
            "● Učím sa – krok ${steps.size + 1}",
            OverlayButton("Hotovo") { finish(save = true) },
            OverlayButton("Zrušiť") { finish(save = false) },
        )
    }

    /** Ukončí nahrávanie; [save] = uložiť postup (tlačidlo "Hotovo" alebo kliknutie na Uverejniť). */
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        if (!save) {
            overlay.show("Učenie zrušené.")
            overlay.hideAfter(3_000)
            onEnd()
            return
        }
        flushPending()
        if (steps.isEmpty()) {
            overlay.show("Nič sa nenahralo, postup sa neuložil.")
            overlay.hideAfter(5_000)
            onEnd()
            return
        }
        try {
            Macro.save(service, steps)
        } catch (e: Exception) {
            overlay.show("Postup sa nepodarilo uložiť: ${e.javaClass.simpleName}: ${e.message}")
            overlay.hideAfter(8_000)
            onEnd()
            return
        }
        Store.setDone(service, episode.key, true)
        val noAccount = steps.none { it.type == StepType.ACCOUNT }
        val text = "Postup uložený: ${Macro.stepsText(steps.size)}" +
            if (noAccount) "\n" + service.getString(R.string.warn_no_account_step) else ""
        overlay.show(text)
        overlay.hideAfter(if (noAccount) 12_000 else 4_000)
        if (noAccount) {
            Toast.makeText(service.applicationContext, R.string.warn_no_account_step, Toast.LENGTH_LONG).show()
        }
        onEnd()
    }

    /** Zruší nahrávanie bez uloženia (napr. pri vypnutí služby). */
    fun abort() {
        if (finished) return
        finished = true
        overlay.hide()
    }
}
