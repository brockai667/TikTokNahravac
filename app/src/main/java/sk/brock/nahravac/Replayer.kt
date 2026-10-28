package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatické prehrávanie naučeného postupu. Beží na vlastnom vlákne (HandlerThread), hlavné vlákno nikdy neblokuje.
 * Karta ([Overlay]) ukazuje stav a vždy má tlačidlo Stop.
 */
class Replayer(
    private val service: AccessibilityService,
    private val overlay: Overlay,
) {
    /** Jedna položka fronty: epizóda a popis, ktorý sa vloží. */
    class Item(val episode: Episode, val caption: String)

    private enum class Decision { RETRY, SKIP, STOP, CONFIRM }

    private enum class StepResult { OK, NOT_FOUND, FAILED }

    companion object {
        private const val START_WAIT_MS = 2_500L
        private const val MAX_GAP_MS = 1_500L
        private const val POLL_MS = 300L
        private const val FIND_TIMEOUT_MS = 15_000L
        private const val AFTER_THUMB_TIMEOUT_MS = 30_000L
        // Nech sa nahrávanie v TikToku rozbehne, kým ďalší kanál TikTok znova spustí.
        private const val AFTER_POST_WAIT_MS = 20_000L

        /** Po koľkých ms bez nájdenia uzla sa začnú zatvárať vyskakovacie okná a ako často. */
        private const val DISMISS_AFTER_MS = 4_000L
        private const val DISMISS_EVERY_MS = 3_000L

        /** Záloha podľa polohy až po tejto dobe bez zhody, aby sa neklikalo na obrazovku, ktorá sa ešte len načítava. */
        private const val POSITION_AFTER_MS = 2_000L

        /** Tlačidlá, ktoré sa smú samé stlačiť pri zatváraní okna (nikdy OK, Zrušiť, Povoliť, Zahodiť a pod.). */
        private val SAFE_DISMISS = listOf(
            "Not now", "Nie teraz", "Teraz nie", "Later", "Neskôr", "Skip", "Preskočiť",
            "Got it", "Rozumiem", "Close", "Zavrieť", "Dismiss",
        )
    }

    @Volatile
    var running = false
        private set

    @Volatile
    private var stopped = false

    private val decisions = LinkedBlockingQueue<Decision>()
    private var note = ""

    /** Spustí frontu na pozadí. False, ak už niečo beží alebo je fronta prázdna. */
    fun runAll(items: List<Item>, confirmBeforePost: Boolean): Boolean {
        if (running || items.isEmpty()) return false
        running = true
        stopped = false
        decisions.clear()
        val thread = HandlerThread("nahravac-replay")
        thread.start()
        Handler(thread.looper).post {
            try {
                runQueue(items, confirmBeforePost)
            } catch (e: Exception) {
                overlay.show(
                    "Chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}",
                    OverlayButton("Zavrieť") { overlay.hide() },
                )
            } finally {
                running = false
                thread.quitSafely()
            }
        }
        return true
    }

    /** Okamžite zastaví celú frontu. */
    fun stop() {
        stopped = true
        decisions.offer(Decision.STOP)
        if (running) overlay.show("Zastavujem…")
    }

    // ------------------------------------------------------------------
    // Fronta
    // ------------------------------------------------------------------

    private fun runQueue(items: List<Item>, confirmBeforePost: Boolean) {
        var okCount = 0
        for (item in items) {
            if (stopped) break
            val ep = item.episode
            if (!download(ep)) break
            if (stopped) break
            copyToClipboard(item.caption)
            if (!runEpisode(ep, item.caption, confirmBeforePost)) break
            okCount++
        }
        val head = if (stopped || okCount < items.size) "Zastavené" else "Hotovo"
        overlay.show("$head: $okCount z ${items.size} nahraných")
        overlay.hideAfter(6_000)
    }

    /** Čerstvé stiahnutie do galérie (video je potom najnovšie). False = fronta sa končí. */
    private fun download(ep: Episode): Boolean {
        while (!stopped) {
            val label = "⬇ ${ep.channel.name}: sťahujem video…"
            overlay.show(label, stopButton())
            try {
                var lastPercent = -2 // karta sa prekreslí len pri zmene celého percenta
                Downloads.fetchToGallery(service, ep) { percent ->
                    if (percent != lastPercent) {
                        lastPercent = percent
                        if (percent >= 0 && !stopped) overlay.show("$label $percent %", stopButton())
                    }
                }
                overlay.show("✔ video v galérii", stopButton())
                sleepStoppable(500)
                return true
            } catch (e: Exception) {
                val d = ask(
                    "Sťahovanie zlyhalo: ${e.message ?: e.javaClass.simpleName}",
                    "Znova" to Decision.RETRY,
                    "Stop" to Decision.STOP,
                )
                if (d != Decision.RETRY) return false
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Jedna epizóda
    // ------------------------------------------------------------------

    /** Prehrá naučený postup pre epizódu. True = uverejnené; false = zastavené / zrušené. */
    fun runEpisode(ep: Episode, caption: String, confirmBeforePost: Boolean): Boolean {
        val steps = Macro.load(service)
        if (steps.isEmpty()) {
            ask("Postup nie je naučený.", "Zavrieť" to Decision.STOP)
            return false
        }
        val name = ep.channel.name
        note = ""

        while (!TikTokShare.launchHome(service)) {
            if (ask("TikTok sa nepodarilo otvoriť.", "Znova" to Decision.RETRY, "Stop" to Decision.STOP) != Decision.RETRY) {
                return false
            }
        }
        sleepStoppable(START_WAIT_MS)

        var skippedAny = false
        var previous: Step? = null
        for ((i, step) in steps.withIndex()) {
            if (stopped) return false
            val n = i + 1
            val total = steps.size
            // Po kliku na miniatúru v galérii sa video spracúva dlhšie.
            val timeout = if (previous?.looksLikeThumbnail() == true) AFTER_THUMB_TIMEOUT_MS else FIND_TIMEOUT_MS

            showProgress(name, n, total)
            sleepStoppable(minOf(step.gapMs, MAX_GAP_MS))
            if (stopped) return false

            if (step.type == StepType.POST) {
                // Pýtame sa, ak to chce používateľ, ak sa niečo preskočilo alebo ak popis nie je na obrazovke.
                val question = when {
                    confirmBeforePost || skippedAny -> "Skontroluj účet, video a popis ($name)"
                    !captionOnScreen(step.sel, caption) ->
                        "Popis sa nenašiel na obrazovke – skontroluj účet, video a popis ($name)"
                    else -> null
                }
                if (question != null) {
                    val d = ask(
                        question,
                        "Uverejniť" to Decision.CONFIRM,
                        "Zrušiť" to Decision.STOP,
                    )
                    if (d != Decision.CONFIRM) return false
                    showProgress(name, n, total)
                }
            }

            var result = doStep(step, ep, caption, timeout)
            while (result != StepResult.OK) {
                val what = if (result == StepResult.FAILED) "zlyhal" else "sa nenašiel"
                val d = ask(
                    "Krok $n $what: ${Macro.describe(step)}",
                    "Znova" to Decision.RETRY,
                    "Preskočiť" to Decision.SKIP,
                    "Stop" to Decision.STOP,
                )
                if (d == Decision.RETRY) {
                    showProgress(name, n, total)
                    result = doStep(step, ep, caption, timeout)
                } else if (d == Decision.SKIP) {
                    skippedAny = true
                    break
                } else {
                    return false
                }
            }

            if (step.type == StepType.POST && result == StepResult.OK) {
                overlay.show("✔ $name: uverejnené, čakám…", stopButton())
                sleepStoppable(AFTER_POST_WAIT_MS)
                Store.setDone(service, ep.key, true)
                return true
            }
            previous = step
        }
        ask("Postup skončil bez kroku Uverejniť.", "Zavrieť" to Decision.STOP)
        return false
    }

    private fun Step.looksLikeThumbnail(): Boolean =
        type == StepType.CLICK && sel.text == null && sel.desc == null

    private fun showProgress(name: String, n: Int, total: Int) {
        overlay.show("▶ $name: krok $n/$total$note", stopButton())
    }

    private fun stopButton() = OverlayButton("Stop") { stop() }

    // ------------------------------------------------------------------
    // Vykonanie kroku
    // ------------------------------------------------------------------

    private fun doStep(step: Step, ep: Episode, caption: String, timeoutMs: Long): StepResult {
        val node = awaitNode(step, ep.channel, timeoutMs) ?: return StepResult.NOT_FOUND
        return when (step.type) {
            StepType.CLICK, StepType.ACCOUNT, StepType.POST ->
                if (click(node, step.sel)) StepResult.OK else StepResult.FAILED
            StepType.CAPTION ->
                if (setText(node, caption)) StepResult.OK else StepResult.FAILED
            StepType.TEXT ->
                if (setText(node, step.value ?: "")) StepResult.OK else StepResult.FAILED
            StepType.PROGRESS -> {
                // Hlasitosť je „best effort“: pri neúspechu pokračujeme a len upozorníme.
                if (!setProgress(node, step.value)) note = " · ⚠ posuvník sa nenastavil"
                StepResult.OK
            }
        }
    }

    /** Opakovane hľadá uzol kroku (každých 300 ms), kým ho nenájde alebo nevyprší čas / Stop. */
    private fun awaitNode(step: Step, channel: Channel, timeoutMs: Long): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        val deadline = start + timeoutMs
        var lastDismissAt = 0L
        while (!stopped) {
            val node = try {
                findNow(step, channel, SystemClock.uptimeMillis() - start >= POSITION_AFTER_MS)
            } catch (e: Exception) {
                null
            }
            if (node != null) return node
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) return null
            // Uzol sa dlho nenachádza -> možno ho zakrýva vyskakovacie okno TikToku, skúsime ho bezpečne zavrieť.
            if (now - start > DISMISS_AFTER_MS && now - lastDismissAt >= DISMISS_EVERY_MS) {
                lastDismissAt = now
                try {
                    dismissPopup(step.sel)
                } catch (e: Exception) {
                    // zatváranie okna je len pomoc, krok sa hľadá ďalej
                }
            }
            sleepStoppable(POLL_MS)
        }
        return null
    }

    /** [byPosition] = povoliť zálohu podľa polohy (Nodes.find (d)); pre ACCOUNT a POST sa nepoužije nikdy. */
    private fun findNow(step: Step, channel: Channel, byPosition: Boolean): AccessibilityNodeInfo? {
        val roots = rootsFor(step.sel.pkg)
        if (roots.isEmpty()) return null
        val nodes = Nodes.collect(roots)
        return when (step.type) {
            StepType.ACCOUNT -> Nodes.findAccount(nodes, channel.accountKey)
            StepType.POST -> Nodes.find(nodes, step.sel) ?: Nodes.findByLabels(nodes, Recorder.POST_LABELS)
            else -> {
                if (byPosition) {
                    val m = service.resources.displayMetrics
                    Nodes.find(nodes, step.sel, m.widthPixels, m.heightPixels)
                } else {
                    Nodes.find(nodes, step.sel)
                }
            }
        }
    }

    /**
     * Zatvorí vyskakovacie okno TikToku: klikne na viditeľný uzol, ktorého text/popis je presne z [SAFE_DISMISS].
     * Hľadá sa len v oknách TikToku (nikdy v našej karte). True = kliklo sa.
     */
    private fun dismissPopup(sel: Selector): Boolean {
        if (sel.pkg.isEmpty() || sel.pkg == service.packageName) return false
        val roots = rootsFor(sel.pkg)
        if (roots.isEmpty()) return false
        val node = Nodes.findByLabels(Nodes.collect(roots), SAFE_DISMISS) ?: return false
        return click(node, sel)
    }

    /** Je v niektorom editovateľnom poli TikToku začiatok (25 znakov) popisu? Prázdny popis = vždy true. */
    private fun captionOnScreen(sel: Selector, caption: String): Boolean {
        val prefix = caption.trim().take(25)
        if (prefix.isEmpty()) return true
        return try {
            val roots = rootsFor(sel.pkg)
            Nodes.collect(roots).any { it.isEditable && it.text?.toString()?.contains(prefix) == true }
        } catch (e: Exception) {
            false
        }
    }

    /** Korene okien daného balíka (TikTok); ak okná nie sú k dispozícii, aktívne okno. */
    private fun rootsFor(pkg: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        var windowsKnown = false
        try {
            val all = service.windows
            windowsKnown = all.isNotEmpty()
            for (w in all) {
                val root = w.root ?: continue
                if (pkg.isEmpty() || root.packageName?.toString() == pkg) out.add(root)
            }
        } catch (e: Exception) {
            // okná sa nepodarilo získať -> skúsime aktívne okno
        }
        if (out.isEmpty()) {
            val active = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (active != null && (!windowsKnown || pkg.isEmpty() || active.packageName?.toString() == pkg)) {
                out.add(active)
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Akcie nad uzlami
    // ------------------------------------------------------------------

    /** Klikne na uzol (alebo na najbližšieho klikateľného predka); inak ťuknutie gestom do stredu uzla. */
    private fun click(node: AccessibilityNodeInfo, sel: Selector): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                val ok = try {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) {
                    false
                }
                if (ok) return true
            }
            current = try {
                current.parent
            } catch (e: Exception) {
                null
            }
            depth++
        }
        return tapCenter(node, sel)
    }

    private fun tapCenter(node: AccessibilityNodeInfo, sel: Selector): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        val m = service.resources.displayMetrics
        val x: Float
        val y: Float
        if (!r.isEmpty) {
            x = r.exactCenterX()
            y = r.exactCenterY()
        } else if (sel.relX > 0f && sel.relY > 0f) {
            x = sel.relX * m.widthPixels
            y = sel.relY * m.heightPixels
        } else {
            return false
        }
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val done = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        ok.set(true)
                        done.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done.countDown()
                    }
                },
                null,
            )
            if (!dispatched) return false
            done.await(2, TimeUnit.SECONDS)
            ok.get()
        } catch (e: Exception) {
            false
        }
    }

    /** Nastaví text poľa (ACTION_SET_TEXT); ak to nejde, vloží ho cez schránku (ACTION_PASTE). */
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val field = Nodes.editableOf(node)
        try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (e: Exception) {
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = try {
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
        if (ok) return true
        copyToClipboard(text)
        return try {
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        }
    }

    private fun setProgress(node: AccessibilityNodeInfo, value: String?): Boolean {
        val v = value?.toFloatOrNull() ?: return false
        val args = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
        } catch (e: Exception) {
            false
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            service.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("popis", text))
        } catch (e: Exception) {
            // bez schránky ostáva aspoň ACTION_SET_TEXT
        }
    }

    // ------------------------------------------------------------------
    // Čakanie
    // ------------------------------------------------------------------

    /** Ukáže [text] s tlačidlami a čaká na rozhodnutie používateľa (bez časového limitu). Stop vráti STOP. */
    private fun ask(text: String, vararg options: Pair<String, Decision>): Decision {
        decisions.clear()
        val buttons = options.map { (label, d) -> OverlayButton(label) { decisions.offer(d) } }
        overlay.show(text, *buttons.toTypedArray())
        while (!stopped) {
            val d = decisions.poll(200, TimeUnit.MILLISECONDS)
            if (d != null) return d
        }
        return Decision.STOP
    }

    /** Spánok po 100 ms úsekoch, aby Stop zabral hneď. */
    private fun sleepStoppable(ms: Long) {
        var left = ms
        while (left > 0 && !stopped) {
            val slice = minOf(left, 100L)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                return
            }
            left -= slice
        }
    }
}
