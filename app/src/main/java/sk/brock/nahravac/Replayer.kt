package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatické nahrávanie aj učenie. Beží na vlastnom vlákne (HandlerThread), hlavné vlákno nikdy neblokuje.
 * Pred každým kanálom appka sama prepne účet ([ensureAccount]), potom prehrá naučený postup: každý krok čaká,
 * kým obrazovka TikToku zodpovedá podpisu z učenia, a prvok hľadá podľa textu (zahmlené ID TikToku sa nepoužívajú),
 * inak ťukne na zaznamenané miesto. Karta ([Overlay]) ukazuje stav a vždy má tlačidlo Stop.
 */
class Replayer(
    private val service: AccessibilityService,
    private val overlay: Overlay,
) {
    /** Jedna položka fronty: epizóda a popis, ktorý sa vloží. */
    class Item(val episode: Episode, val caption: String)

    private enum class Decision { RETRY, SKIP, STOP, CONFIRM }

    private enum class StepResult { OK, NOT_FOUND, FAILED }

    private enum class Outcome { DONE, SKIPPED, STOPPED }

    /** Stav profilu: je tam cieľový účet? [current] = kľúče účtu, ktorý je v hlavičke (ak je to náš kanál). */
    private class ProfileCheck(val isTarget: Boolean, val current: List<String>?)

    companion object {
        private const val START_WAIT_MS = 2_500L
        private const val MAX_GAP_MS = 1_500L
        private const val POLL_MS = 300L

        /** Čakanie na obrazovku kroku (podpis); po výbere videa dlhšie (video sa spracúva). */
        private const val SCREEN_TIMEOUT_MS = 15_000L
        private const val AFTER_PICK_TIMEOUT_MS = 30_000L

        /** Vyskakovacie okno z učenia (Not now…): keď sa do 5 s neukáže, krok sa ticho preskočí. */
        private const val OPTIONAL_TIMEOUT_MS = 5_000L

        /** Po zhode obrazovky: ako dlho hľadať prvok podľa textu, kým sa ťukne na zaznamenané miesto. */
        private const val LABEL_WAIT_MS = 2_000L

        /** Hľadanie Uverejniť, účtu a poľa popisu (bez zálohy podľa polohy). */
        private const val TARGET_WAIT_MS = 5_000L

        // Nech sa nahrávanie v TikToku rozbehne, kým ďalší kanál TikTok znova spustí.
        private const val AFTER_POST_WAIT_MS = 20_000L

        /** Po koľkých ms bez zhody sa začnú zatvárať vyskakovacie okná a ako často. */
        private const val DISMISS_AFTER_MS = 4_000L
        private const val DISMISS_EVERY_MS = 3_000L

        /** Prepínanie účtu: profil, zoznam účtov, prepnutie, pauza po ťuku na účet. */
        private const val PROFILE_WAIT_MS = 8_000L
        private const val LIST_WAIT_MS = 5_000L
        private const val SWITCH_WAIT_MS = 10_000L
        private const val AFTER_ACCOUNT_TAP_MS = 3_000L

        /** Hlavička profilu = horných 40 % obrazovky, horná lišta = horných 15 %. */
        private const val TOP_HEADER = 0.40f
        private const val TOP_BAR = 0.15f

        /** Pauza po vypnutí dotykov našich okien pred gestom (kým sa zmena okien prejaví). */
        private const val GESTURE_SETTLE_MS = 50L

        /** Tlačidlá, ktoré sa smú samé stlačiť pri zatváraní okna (nikdy OK, Zrušiť, Povoliť, Zahodiť a pod.). */
        private val SAFE_DISMISS = listOf(
            "Not now", "Nie teraz", "Teraz nie", "Later", "Neskôr", "Skip", "Preskočiť",
            "Got it", "Rozumiem", "Close", "Zavrieť", "Dismiss",
        )

        /** Na toto appka nikdy neťukne (okamžite uverejní príbeh). */
        private val FORBIDDEN = listOf("Your Story", "Tvoj príbeh", "Váš príbeh")

        private val PROFILE_LABELS = listOf("Profile", "Profil", "Me")
        private val SWITCH_WORDS = listOf("switch account", "prepnúť účet")

        /** Texty výberu videa v galérii: krok po ňom čaká dlhšie. */
        private val PICKER_WORDS = listOf("Recents", "All", "Videos")
    }

    @Volatile
    var running = false
        private set

    @Volatile
    private var stopped = false

    private val decisions = LinkedBlockingQueue<Decision>()
    private val main = Handler(Looper.getMainLooper())
    private var note = ""

    /** Bežiace učenie (len na hlavnom vlákne). */
    private var recorder: Recorder? = null

    private val channels: List<Channel> by lazy {
        try {
            Repo.loadChannels(service)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Kľúče účtov všetkých kanálov. */
    private val allKeys: List<String> by lazy { channels.flatMap { Accounts.keys(it) }.distinct() }

    /** Spustí frontu na pozadí. False, ak už niečo beží alebo je fronta prázdna. */
    fun runAll(items: List<Item>, confirmBeforePost: Boolean): Boolean {
        if (items.isEmpty()) return false
        return startThread { runQueue(items, confirmBeforePost) }
    }

    /** Učenie na epizóde [ep]: prepne účet, otvorí TikTok a nahráva dotyky. False, ak už niečo beží. */
    fun learn(ep: Episode, caption: String): Boolean = startThread { learnUpload(ep, caption) }

    private fun startThread(block: () -> Unit): Boolean {
        if (running) return false
        running = true
        stopped = false
        decisions.clear()
        val thread = HandlerThread("nahravac-replay")
        thread.start()
        Handler(thread.looper).post {
            try {
                block()
            } catch (e: Exception) {
                overlay.setReplayPassThrough(false)
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

    /** Okamžite zastaví celú frontu aj učenie. */
    fun stop() {
        stopped = true
        decisions.offer(Decision.STOP)
        main.post {
            recorder?.abort()
            recorder = null
        }
        if (running) overlay.show("Zastavujem…")
    }

    // ------------------------------------------------------------------
    // Fronta
    // ------------------------------------------------------------------

    private fun runQueue(items: List<Item>, confirmBeforePost: Boolean) {
        var okCount = 0
        var completed = true
        for (item in items) {
            if (stopped) {
                completed = false
                break
            }
            val ep = item.episode
            if (!download(ep) || stopped) {
                completed = false
                break
            }
            Nodes.copyToClipboard(service, item.caption)
            val outcome = runEpisode(ep, item.caption, confirmBeforePost)
            if (outcome == Outcome.DONE) {
                okCount++
            } else if (outcome == Outcome.STOPPED) {
                completed = false
                break
            }
        }
        val head = if (stopped || !completed) "Zastavené" else "Hotovo"
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
    // Učenie
    // ------------------------------------------------------------------

    private fun learnUpload(ep: Episode, caption: String) {
        Nodes.copyToClipboard(service, caption)
        if (ensureAccount(ep.channel) != Outcome.DONE || !openHome()) {
            overlay.show("Učenie zrušené.")
            overlay.hideAfter(3_000)
            return
        }
        when (record(Recorder.Mode.UPLOAD, caption, ep.channel)) {
            Recorder.Result.POSTED -> {
                Store.setDone(service, ep.key, true)
                overlay.show("Postup uložený: ${Macro.stepsText(Macro.load(service).size)}. Video sa uverejňuje.")
                overlay.hideAfter(6_000)
            }
            Recorder.Result.SAVED -> {
                overlay.show(
                    "Postup uložený: ${Macro.stepsText(Macro.load(service).size)}.\n" +
                        "Nemá krok Uverejniť – prehrávanie sa pred uverejnením zastaví. Nauč ho znova až po Post."
                )
                overlay.hideAfter(10_000)
            }
            Recorder.Result.EMPTY -> {
                overlay.show("Nič sa nenahralo, postup sa neuložil.")
                overlay.hideAfter(5_000)
            }
            Recorder.Result.FAILED -> {
                overlay.show("Postup sa nepodarilo uložiť.")
                overlay.hideAfter(8_000)
            }
            else -> {
                overlay.show("Učenie zrušené.")
                overlay.hideAfter(3_000)
            }
        }
    }

    /** Spustí nahrávanie dotykov na hlavnom vlákne a čaká na výsledok (Stop = CANCELLED). */
    private fun record(mode: Recorder.Mode, caption: String, target: Channel?): Recorder.Result {
        val results = LinkedBlockingQueue<Recorder.Result>()
        val keys = allKeys
        main.post {
            if (stopped) {
                results.offer(Recorder.Result.CANCELLED)
                return@post
            }
            val r = Recorder(service, overlay, mode, caption, target, keys) { res ->
                recorder = null
                results.offer(res)
            }
            recorder = r
            r.start()
        }
        while (!stopped) {
            val r = results.poll(200, TimeUnit.MILLISECONDS)
            if (r != null) return r
        }
        main.post {
            recorder?.abort()
            recorder = null
        }
        return Recorder.Result.CANCELLED
    }

    // ------------------------------------------------------------------
    // Prepnutie účtu
    // ------------------------------------------------------------------

    /**
     * Zabezpečí, že v TikToku je prihlásený účet kanála [ch]: Profil → ak je v hlavičke, hotovo; inak tlačidlo
     * „Switch account“, ťuk na meno aktuálneho účtu, naučená cesta (alebo ju raz naučí). Nakoniec sa pýta.
     */
    private fun ensureAccount(ch: Channel): Outcome {
        val keys = Accounts.keys(ch)
        if (keys.isEmpty()) return Outcome.DONE
        val handle = ch.tiktok.ifEmpty { ch.name }

        overlay.show("👤 ${ch.name}: kontrolujem účet…", stopButton())
        if (!openProfile()) return Outcome.STOPPED
        var check = waitProfile(keys)
        if (stopped) return Outcome.STOPPED
        if (check.isTarget) return Outcome.DONE
        var current = check.current
        var dirty = false

        // (1) tlačidlo "Switch account" v hornej lište
        overlay.show("👤 ${ch.name}: prepínam na $handle…", stopButton())
        val switchButton = pollNode(1_500L) { findInTopBar(it) { label -> SWITCH_WORDS.any { w -> label.lowercase(Locale.ROOT).contains(w) } } }
        if (switchButton != null) {
            dirty = true
            click(switchButton)
            if (tapAccountInList(keys) && verifySwitched(ch, keys)) return Outcome.DONE
            if (stopped) return Outcome.STOPPED
        }

        // (2) ťuk na meno aktuálneho účtu (otvorí zoznam účtov)
        if (dirty) {
            if (!openProfile()) return Outcome.STOPPED
            check = waitProfile(keys)
            if (check.isTarget) return Outcome.DONE
            current = check.current ?: current
            dirty = false
        }
        val cur = current
        if (cur != null) {
            val header = pollNode(1_500L) { findInTopBar(it) { label -> Accounts.matches(label, cur) } }
            if (header != null) {
                dirty = true
                click(header)
                if (tapAccountInList(keys) && verifySwitched(ch, keys)) return Outcome.DONE
                if (stopped) return Outcome.STOPPED
            }
        }

        // (3) naučená cesta, inak ju raz naučiť
        if (dirty) {
            if (!openProfile()) return Outcome.STOPPED
            if (waitProfile(keys).isTarget) return Outcome.DONE
        }
        if (stopped) return Outcome.STOPPED
        val path = Macro.loadSwitch(service)
        if (path.isNotEmpty()) {
            if (playSwitch(path, ch) && verifySwitched(ch, keys)) return Outcome.DONE
        } else {
            if (record(Recorder.Mode.SWITCH, "", ch) == Recorder.Result.ACCOUNT && verifySwitched(ch, keys)) {
                return Outcome.DONE
            }
        }
        if (stopped) return Outcome.STOPPED

        // Nič nezabralo: ručne.
        val saved = Diag.save(service, "Nepodarilo sa prepnúť na $handle", ch.name, null, 0)
        val d = ask(
            "Nepodarilo sa prepnúť na $handle. Prepni ručne a ťukni Pokračovať" + diagNote(saved),
            "Pokračovať" to Decision.CONFIRM,
            "Preskočiť kanál" to Decision.SKIP,
            "Stop" to Decision.STOP,
        )
        return when (d) {
            Decision.CONFIRM -> Outcome.DONE
            Decision.SKIP -> Outcome.SKIPPED
            else -> Outcome.STOPPED
        }
    }

    /** Spustí TikTok na domovskej stránke a ťukne na Profil (dolná lišta; inak miesto 90 %, 96 %). */
    private fun openProfile(): Boolean {
        if (!openHome()) return false
        val tab = pollNode(PROFILE_WAIT_MS, dismiss = true) { findProfileTab(it) }
        if (stopped) return false
        if (tab != null) click(tab) else tapRel(0.9f, 0.96f)
        return !stopped
    }

    /** Spustí TikTok od začiatku (domovský feed) a chvíľu počká. False = zastavené. */
    private fun openHome(): Boolean {
        while (!TikTokShare.launchHome(service)) {
            if (ask("TikTok sa nepodarilo otvoriť.", "Znova" to Decision.RETRY, "Stop" to Decision.STOP) != Decision.RETRY) {
                return false
            }
        }
        sleepStoppable(START_WAIT_MS)
        return !stopped
    }

    /** Ťuk na Profil v dolnej lište: uzol s textom Profile/Profil/Me, prednostne v dolných 20 % obrazovky. */
    private fun findProfileTab(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val h = Nodes.screenSize(service).y
        val all = nodes.filter { n ->
            n.isVisibleToUser && listOfNotNull(Nodes.ownText(n), Nodes.ownDesc(n)).any { l -> PROFILE_LABELS.any { it.equals(l, ignoreCase = true) } }
        }
        return all.firstOrNull { Nodes.bounds(it).exactCenterY() > h * 0.8f } ?: all.firstOrNull()
    }

    /**
     * Čaká (najviac 8 s) na profil: ak je v horných 40 % účet [targetKeys], hotovo. Ak je tam iný náš účet
     * aspoň 1,5 s, vráti ho ako aktuálny.
     */
    private fun waitProfile(targetKeys: List<String>): ProfileCheck {
        val h = Nodes.screenSize(service).y
        val start = SystemClock.uptimeMillis()
        var current: List<String>? = null
        var currentSince = 0L
        while (!stopped) {
            val labels = try {
                Nodes.collect(Nodes.tiktokRoots(service))
                    .filter { it.isVisibleToUser && Nodes.bounds(it).exactCenterY() <= h * TOP_HEADER }
                    .flatMap { listOfNotNull(Nodes.ownText(it), Nodes.ownDesc(it)) }
            } catch (e: Exception) {
                emptyList()
            }
            if (labels.any { Accounts.matches(it, targetKeys) }) return ProfileCheck(true, null)
            val now = SystemClock.uptimeMillis()
            val other = channels.map { Accounts.keys(it) }
                .firstOrNull { k -> k != targetKeys && k.isNotEmpty() && labels.any { Accounts.matches(it, k) } }
            if (other != null) {
                if (current == null) {
                    current = other
                    currentSince = now
                } else if (now - currentSince >= 1_500L) {
                    return ProfileCheck(false, current)
                }
            }
            if (now - start >= PROFILE_WAIT_MS) return ProfileCheck(false, current)
            sleepStoppable(POLL_MS)
        }
        return ProfileCheck(false, current)
    }

    /** Viditeľný uzol v horných 15 % obrazovky (klikateľný alebo s klikateľným predkom), ktorého text/popis spĺňa [test]. */
    private fun findInTopBar(nodes: List<AccessibilityNodeInfo>, test: (String) -> Boolean): AccessibilityNodeInfo? {
        val h = Nodes.screenSize(service).y
        return nodes.firstOrNull { n ->
            n.isVisibleToUser &&
                listOfNotNull(Nodes.ownText(n), Nodes.ownDesc(n)).any(test) &&
                run {
                    val r = Nodes.bounds(n)
                    !r.isEmpty && r.exactCenterY() <= h * TOP_BAR
                } &&
                Nodes.clickableAncestor(n) != null
        }
    }

    /** Hľadá (najviac 5 s) cieľový účet kdekoľvek (zoznam účtov) a ťukne naň. */
    private fun tapAccountInList(keys: List<String>): Boolean {
        val node = pollNode(LIST_WAIT_MS) { Nodes.findAccount(it, keys) } ?: return false
        return click(node)
    }

    /** Po ťuku na účet: počká, kým sa TikTok prepne (najviac 10 s na lištu), ťukne na Profil a overí hlavičku. */
    private fun verifySwitched(ch: Channel, keys: List<String>): Boolean {
        if (stopped) return false
        overlay.show("👤 ${ch.name}: overujem účet…", stopButton())
        sleepStoppable(AFTER_ACCOUNT_TAP_MS)
        val tab = pollNode(SWITCH_WAIT_MS, dismiss = true) { findProfileTab(it) }
        if (stopped) return false
        if (tab != null) click(tab) else tapRel(0.9f, 0.96f)
        return waitProfile(keys).isTarget
    }

    /** Prehrá naučenú cestu prepnutia účtu (od profilu); krok ACCOUNT vyberie účet kanála [ch]. */
    private fun playSwitch(path: List<Step>, ch: Channel): Boolean {
        for ((i, step) in path.withIndex()) {
            if (stopped) return false
            overlay.show("👤 ${ch.name}: prepínam účet – krok ${i + 1}/${path.size}", stopButton())
            sleepStoppable(minOf(step.gapMs, MAX_GAP_MS))
            val optional = isOptional(step)
            val screenOk = awaitScreen(step, if (optional) OPTIONAL_TIMEOUT_MS else SCREEN_TIMEOUT_MS, dismiss = !optional)
            if (optional) {
                if (screenOk) act(step, ch, "", optional = true)
                continue
            }
            if (!screenOk || act(step, ch, "") != StepResult.OK) {
                if (!stopped) Diag.save(service, "Prepnutie účtu: krok ${i + 1} sa nenašiel", ch.name, step, i + 1)
                return false
            }
        }
        return !stopped
    }

    // ------------------------------------------------------------------
    // Jedna epizóda
    // ------------------------------------------------------------------

    /** Prepne účet a prehrá naučený postup pre epizódu. */
    private fun runEpisode(ep: Episode, caption: String, confirmBeforePost: Boolean): Outcome {
        val steps = Macro.load(service)
        if (steps.isEmpty()) {
            ask("Postup nie je naučený.", "Zavrieť" to Decision.STOP)
            return Outcome.STOPPED
        }
        val account = ensureAccount(ep.channel)
        if (account != Outcome.DONE) return account
        if (!openHome()) return Outcome.STOPPED
        return playUpload(steps, ep, caption, confirmBeforePost)
    }

    private fun playUpload(steps: List<Step>, ep: Episode, caption: String, confirmBeforePost: Boolean): Outcome {
        val name = ep.channel.name
        note = ""
        var skippedAny = false
        var previous: Step? = null
        val total = steps.size
        for ((i, step) in steps.withIndex()) {
            if (stopped) return Outcome.STOPPED
            val n = i + 1
            showProgress(name, n, total)
            sleepStoppable(minOf(step.gapMs, MAX_GAP_MS))
            if (stopped) return Outcome.STOPPED

            // Vyskakovacie okno, ktoré bolo pri učení: keď sa neukáže, krok sa ticho preskočí.
            if (isOptional(step)) {
                if (awaitScreen(step, OPTIONAL_TIMEOUT_MS, dismiss = false)) act(step, ep.channel, caption, optional = true)
                previous = step
                continue
            }

            val timeout = timeoutFor(step, previous)
            var result = if (awaitScreen(step, timeout)) StepResult.OK else StepResult.NOT_FOUND

            if (result == StepResult.OK && step.type == StepType.POST) {
                // Pýtame sa, ak to chce používateľ, ak sa niečo preskočilo alebo ak popis nie je na obrazovke.
                val question = when {
                    confirmBeforePost || skippedAny -> "Skontroluj účet, video a popis ($name)"
                    !captionOnScreen(caption) -> "Popis sa nenašiel na obrazovke – skontroluj účet, video a popis ($name)"
                    else -> null
                }
                if (question != null) {
                    val d = ask(
                        question,
                        "Uverejniť" to Decision.CONFIRM,
                        "Zrušiť" to Decision.STOP,
                    )
                    if (d != Decision.CONFIRM) return Outcome.STOPPED
                    showProgress(name, n, total)
                }
            }
            if (result == StepResult.OK) result = act(step, ep.channel, caption)

            while (result != StepResult.OK) {
                if (stopped) return Outcome.STOPPED
                val what = if (result == StepResult.FAILED) "zlyhal" else "sa nenašiel"
                val saved = Diag.save(service, "Krok $n $what", name, step, n)
                val d = ask(
                    "Krok $n $what: ${Macro.describe(step)}" + diagNote(saved),
                    "Znova" to Decision.RETRY,
                    "Preskočiť" to Decision.SKIP,
                    "Stop" to Decision.STOP,
                )
                if (d == Decision.RETRY) {
                    showProgress(name, n, total)
                    result = if (awaitScreen(step, timeout)) act(step, ep.channel, caption) else StepResult.NOT_FOUND
                } else if (d == Decision.SKIP) {
                    skippedAny = true
                    break
                } else {
                    return Outcome.STOPPED
                }
            }

            if (step.type == StepType.POST && result == StepResult.OK) {
                overlay.show("✔ $name: uverejnené, čakám…", stopButton())
                sleepStoppable(AFTER_POST_WAIT_MS)
                Store.setDone(service, ep.key, true)
                return Outcome.DONE
            }
            previous = step
        }
        ask("Postup skončil bez kroku Uverejniť.", "Zavrieť" to Decision.STOP)
        return Outcome.STOPPED
    }

    /** Krok po výbere videa (obrazovka galérie) a krok v editore čakajú dlhšie. */
    private fun timeoutFor(step: Step, previous: Step?): Long {
        val afterPick = previous != null && previous.sig.any { s -> PICKER_WORDS.any { it.equals(s, ignoreCase = true) } }
        val editor = step.sig.any { it.equals("Add sound", ignoreCase = true) }
        return if (afterPick || editor) AFTER_PICK_TIMEOUT_MS else SCREEN_TIMEOUT_MS
    }

    /** Ťuk na zatvorenie vyskakovacieho okna (štítok zo [SAFE_DISMISS]) - nemusí sa vždy ukázať. */
    private fun isOptional(step: Step): Boolean {
        if (step.type != StepType.CLICK) return false
        val label = step.sel.text ?: step.sel.desc ?: return false
        return SAFE_DISMISS.any { it.equals(label, ignoreCase = true) }
    }

    private fun diagNote(saved: Boolean): String = if (saved) "\n(diagnostika uložená – appka → Diagnostika)" else ""

    private fun showProgress(name: String, n: Int, total: Int) {
        overlay.show("▶ $name: krok $n/$total$note", stopButton())
    }

    private fun stopButton() = OverlayButton("Stop") { stop() }

    // ------------------------------------------------------------------
    // Obrazovka a prvok kroku
    // ------------------------------------------------------------------

    /**
     * Čaká, kým obrazovka TikToku zodpovedá podpisu kroku (aspoň Nodes.sigNeed textov podpisu je vidieť).
     * Kým čaká dlhšie než 4 s, skúša zavrieť vyskakovacie okná ([dismiss]). False = vypršal čas / Stop.
     */
    private fun awaitScreen(step: Step, timeoutMs: Long, dismiss: Boolean = true): Boolean {
        val need = Nodes.sigNeed(step.sig)
        val start = SystemClock.uptimeMillis()
        var lastDismissAt = 0L
        while (!stopped) {
            val ok = try {
                val nodes = Nodes.collect(Nodes.tiktokRoots(service))
                nodes.isNotEmpty() && Nodes.sigOverlap(step.sig, Nodes.visibleTexts(nodes)) >= need
            } catch (e: Exception) {
                false
            }
            if (ok) return true
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) return false
            // Obrazovka sa dlho neukazuje -> možno ju zakrýva vyskakovacie okno TikToku, skúsime ho bezpečne zavrieť.
            if (dismiss && now - start > DISMISS_AFTER_MS && now - lastDismissAt >= DISMISS_EVERY_MS) {
                lastDismissAt = now
                try {
                    dismissPopup()
                } catch (e: Exception) {
                    // zatváranie okna je len pomoc
                }
            }
            sleepStoppable(POLL_MS)
        }
        return false
    }

    /** Vykoná krok na obrazovke, ktorá už zodpovedá podpisu. [optional] = bez ťuku podľa polohy. */
    private fun act(step: Step, channel: Channel, caption: String, optional: Boolean = false): StepResult {
        val size = Nodes.screenSize(service)
        val sel = step.sel
        return when (step.type) {
            StepType.BACK ->
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) StepResult.OK else StepResult.FAILED
            StepType.SWIPE -> {
                val ms = step.durMs.coerceIn(100L, 1_500L)
                if (swipe(sel.relX * size.x, sel.relY * size.y, step.endX * size.x, step.endY * size.y, ms)) {
                    StepResult.OK
                } else {
                    StepResult.FAILED
                }
            }
            StepType.CAPTION -> {
                val field = pollNode(TARGET_WAIT_MS) { captionField(it, sel, size.x, size.y) } ?: return StepResult.NOT_FOUND
                if (Nodes.fillText(service, field, caption)) StepResult.OK else StepResult.FAILED
            }
            StepType.ACCOUNT -> {
                val node = pollNode(TARGET_WAIT_MS) { Nodes.findAccount(it, Accounts.keys(channel)) } ?: return StepResult.NOT_FOUND
                if (click(node)) StepResult.OK else StepResult.FAILED
            }
            StepType.POST -> {
                val node = pollNode(TARGET_WAIT_MS) {
                    findLabeled(it, sel, size.x, size.y) ?: Nodes.findByLabels(it, Recorder.POST_LABELS)
                } ?: return StepResult.NOT_FOUND
                if (click(node)) StepResult.OK else StepResult.FAILED
            }
            StepType.CLICK, StepType.LONG -> {
                val ms = if (step.type == StepType.LONG) Recorder.LONG_MS else Recorder.TAP_MS
                val label = sel.text ?: sel.desc
                if (label != null && isForbidden(label)) return StepResult.NOT_FOUND
                if (usableLabel(sel)) {
                    val node = pollNode(LABEL_WAIT_MS) { findLabeled(it, sel, size.x, size.y) }
                    if (node != null) return if (pressNode(node, ms)) StepResult.OK else StepResult.FAILED
                }
                if (optional) return StepResult.OK
                // Záloha: zaznamenané miesto (obrazovka už sedí podľa podpisu).
                val x = sel.relX * size.x
                val y = sel.relY * size.y
                if (sel.relX <= 0f || sel.relY <= 0f || forbiddenAt(x, y)) return StepResult.NOT_FOUND
                if (press(x, y, ms)) StepResult.OK else StepResult.FAILED
            }
        }
    }

    /** Štítok sa dá hľadať: je, nie je zakázaný a nie je to meno niektorého z našich účtov. */
    private fun usableLabel(sel: Selector): Boolean {
        val label = sel.text ?: sel.desc ?: return false
        return !isForbidden(label) && !Accounts.matches(label, allKeys)
    }

    private fun findLabeled(nodes: List<AccessibilityNodeInfo>, sel: Selector, w: Int, h: Int): AccessibilityNodeInfo? {
        if (!usableLabel(sel)) return null
        return Nodes.findExact(nodes, sel, w, h)
    }

    /** Pole popisu: zamerané editovateľné pole, inak editovateľné pole najbližšie k zaznamenanému miestu. */
    private fun captionField(nodes: List<AccessibilityNodeInfo>, sel: Selector, w: Int, h: Int): AccessibilityNodeInfo? {
        val editable = nodes.filter { it.isVisibleToUser && it.isEditable }
        if (editable.isEmpty()) return null
        editable.firstOrNull { it.isFocused }?.let { return it }
        val tx = sel.relX * w
        val ty = sel.relY * h
        return editable.minByOrNull { n ->
            val r = Nodes.bounds(n)
            val dx = r.exactCenterX() - tx
            val dy = r.exactCenterY() - ty
            dx * dx + dy * dy
        }
    }

    private fun isForbidden(label: String): Boolean {
        val l = label.trim()
        return FORBIDDEN.any { it.equals(l, ignoreCase = true) } || l.contains("your story", ignoreCase = true)
    }

    /** Je na mieste [x],[y] prvok „Your Story“ (alebo iný zakázaný)? */
    private fun forbiddenAt(x: Float, y: Float): Boolean {
        val root = Nodes.tiktokRootAt(service, x, y) ?: return false
        val deepest = Nodes.deepestAt(root, x.toInt(), y.toInt()) ?: return false
        val clickable = Nodes.clickableAncestor(deepest)
        val labels = listOfNotNull(
            Nodes.ownText(deepest), Nodes.ownDesc(deepest),
            clickable?.let { Nodes.label(it) }, clickable?.let { Nodes.ownDesc(it) },
        )
        return labels.any { isForbidden(it) }
    }

    /**
     * Zatvorí vyskakovacie okno TikToku: ťukne na viditeľný uzol, ktorého text/popis je presne zo [SAFE_DISMISS].
     * Hľadá sa len v oknách TikToku (nikdy v našej karte). True = ťuklo sa.
     */
    private fun dismissPopup(): Boolean {
        val roots = Nodes.tiktokRoots(service)
        if (roots.isEmpty()) return false
        val node = Nodes.findByLabels(Nodes.collect(roots), SAFE_DISMISS) ?: return false
        return click(node)
    }

    /** Je v niektorom editovateľnom poli TikToku začiatok (25 znakov) popisu? Prázdny popis = vždy true. */
    private fun captionOnScreen(caption: String): Boolean {
        val prefix = caption.trim().take(25)
        if (prefix.isEmpty()) return true
        return try {
            Nodes.collect(Nodes.tiktokRoots(service)).any { it.isEditable && it.text?.toString()?.contains(prefix) == true }
        } catch (e: Exception) {
            false
        }
    }

    /** Opakovane (každých 300 ms) volá [find], kým nevráti uzol alebo nevyprší čas / Stop. */
    private fun pollNode(
        timeoutMs: Long,
        dismiss: Boolean = false,
        find: (List<AccessibilityNodeInfo>) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        var lastDismissAt = 0L
        while (!stopped) {
            val node = try {
                find(Nodes.collect(Nodes.tiktokRoots(service)))
            } catch (e: Exception) {
                null
            }
            if (node != null) return node
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) return null
            if (dismiss && now - start > DISMISS_AFTER_MS && now - lastDismissAt >= DISMISS_EVERY_MS) {
                lastDismissAt = now
                try {
                    dismissPopup()
                } catch (e: Exception) {
                }
            }
            sleepStoppable(POLL_MS)
        }
        return null
    }

    // ------------------------------------------------------------------
    // Gestá
    // ------------------------------------------------------------------

    /** Ťuk gestom do stredu uzla (spoľahlivejšie než ACTION_CLICK v TikToku); bez hraníc ACTION_CLICK. */
    private fun click(node: AccessibilityNodeInfo): Boolean = pressNode(node, Recorder.TAP_MS)

    private fun pressNode(node: AccessibilityNodeInfo, ms: Long): Boolean {
        val r = Nodes.bounds(node)
        val size = Nodes.screenSize(service)
        // intersect() pri neprekrytí obdĺžnik nemení -> výsledok sa kontroluje.
        if (r.intersect(0, 0, size.x, size.y) && !r.isEmpty) return press(r.exactCenterX(), r.exactCenterY(), ms)
        val target = Nodes.clickableAncestor(node) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            false
        }
    }

    private fun tapRel(relX: Float, relY: Float): Boolean {
        val size = Nodes.screenSize(service)
        return press(relX * size.x, relY * size.y, Recorder.TAP_MS)
    }

    private fun press(x: Float, y: Float, ms: Long): Boolean {
        val size = Nodes.screenSize(service)
        val px = x.coerceIn(0f, (size.x - 1).toFloat())
        val py = y.coerceIn(0f, (size.y - 1).toFloat())
        return gesture(Path().apply { moveTo(px, py) }, ms, px, py)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val size = Nodes.screenSize(service)
        val maxX = (size.x - 1).toFloat()
        val maxY = (size.y - 1).toFloat()
        val sx = x1.coerceIn(0f, maxX)
        val sy = y1.coerceIn(0f, maxY)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
        }
        return gesture(path, ms, sx, sy)
    }

    /**
     * Pošle gesto do TikToku: najprv všetky naše okná prestanú prijímať dotyky (karta na mieste [x],[y] sa skryje),
     * gesto sa odošle na hlavnom vlákne, počká sa na jeho koniec a okná sa vrátia.
     */
    private fun gesture(path: Path, ms: Long, x: Float, y: Float): Boolean {
        val description = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
        } catch (e: Exception) {
            return false
        }
        overlay.setReplayPassThrough(true, x, y)
        try {
            Thread.sleep(GESTURE_SETTLE_MS)
            val done = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            main.post {
                val dispatched = try {
                    service.dispatchGesture(description, callback, null)
                } catch (e: Exception) {
                    false
                }
                if (!dispatched) done.countDown()
            }
            done.await(ms + 3_000L, TimeUnit.MILLISECONDS)
            return ok.get()
        } catch (e: InterruptedException) {
            return false
        } finally {
            overlay.setReplayPassThrough(false)
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
