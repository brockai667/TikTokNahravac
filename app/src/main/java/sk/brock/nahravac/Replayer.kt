package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.IOException
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

    /** UNSURE = na Uverejniť sa ťuklo, ale potvrdenie neprišlo: kanál sa už neopakuje (inak hrozí dvojité video). */
    private enum class Outcome { DONE, SKIPPED, STOPPED, UNSURE }

    /** Výsledok čakania na obrazovku: OK, AHEAD = TikTok je už na obrazovke ďalšieho kroku, TIMEOUT. */
    private enum class Screen { OK, AHEAD, TIMEOUT }

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

        /** Krok sa preskočí ako už hotový, ak je TikTok aspoň takto dlho na obrazovke ďalšieho kroku. */
        private const val AHEAD_AFTER_MS = 3_000L

        /** Po tomto čase stačí na zhodu obrazovky, že predošlá obrazovka zmizla (nové texty kroku nemusia byť). */
        private const val RELAX_MS = 8_000L

        /** Po zhode obrazovky: ako dlho hľadať prvok podľa textu, kým sa ťukne na zaznamenané miesto. */
        private const val LABEL_WAIT_MS = 2_000L

        /** Hľadanie Uverejniť, účtu a poľa popisu (bez zálohy podľa polohy). */
        private const val TARGET_WAIT_MS = 5_000L

        // Nech sa nahrávanie v TikToku rozbehne, kým ďalší kanál TikTok znova spustí.
        private const val AFTER_POST_WAIT_MS = 20_000L

        /** Po ťuku na Uverejniť: ako dlho čakať, kým obrazovka s Uverejniť zmizne, ako často pozerať, koľkokrát potvrdiť. */
        private const val POSTED_WAIT_MS = 15_000L
        private const val POSTED_POLL_MS = 500L
        private const val MAX_CONFIRMS = 2

        /** Potvrdenie po Uverejniť („Post video publicly?“) - na toto appka ťukne sama. */
        private val POST_CONFIRM = listOf("Post Now", "Post anyway", "Uverejniť teraz", "Zverejniť teraz", "Publikovať teraz")

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

        /** Bez obsluhy: pokusy na kanál (vždy od spustenia TikToku), na stiahnutie a na prepnutie účtu. */
        private const val EPISODE_ATTEMPTS = 3
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val SWITCH_ATTEMPTS = 2

        /** Ťuk sa stratil: predošlá obrazovka svieti takto dlho bez zmeny -> ťuk sa zopakuje (najviac 2×). */
        private const val LOST_TAP_MS = 8_000L
        private const val MAX_REPEATS = 2

        /** Galéria videí: najviac takto dlho čakať na náhľady (trvanie „0:42“), kým sa ťukne na video. */
        private const val GRID_WAIT_MS = 6_000L
        private val DURATION = Regex("^\\d{1,2}:\\d{2}$")

        /** Text priebehu („45 %“): TikTok niečo spracúva, ťuk sa neopakuje. */
        private val PERCENT = Regex("\\d{1,3}\\s?%")
        /** Texty (malými písmenami), pri ktorých TikTok ešte niečo načítava - ťuk sa vtedy neopakuje. */
        private val BUSY_WORDS = listOf("loading", "processing", "načítava", "spracúva", "uploading")

        /** Meno účtu v hlavičke vlastného profilu (stred hornej lišty), ak sa nenájde ako text. */
        private const val HEADER_Y = 0.064f
    }

    @Volatile
    var running = false
        private set

    @Volatile
    private var stopped = false

    private val decisions = LinkedBlockingQueue<Decision>()
    private val main = Handler(Looper.getMainLooper())
    private var note = ""

    /** Priebeh prehrávania epizódy (krok: výsledok) - ide do diagnostiky. Len na pracovnom vlákne. */
    private val runLog = ArrayList<String>()

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

    /**
     * Bez obsluhy: appka sa nikdy nepýta. Každý kanál má [EPISODE_ATTEMPTS] pokusov; kanál, ktorý nevyšiel, sa preskočí
     * (diagnostika sa uloží) a na konci ide ešte raz (2. kolo). Zastaví ju len Stop alebo nenaučený postup.
     */
    private fun runQueue(items: List<Item>, confirmBeforePost: Boolean) {
        var okCount = 0
        var queue = items
        val failed = ArrayList<Item>()
        val unsure = ArrayList<Item>()
        round@ for (round in 1..2) {
            failed.clear()
            for (item in queue) {
                if (stopped) break@round
                val outcome = try {
                    runItem(item, confirmBeforePost)
                } catch (e: Exception) {
                    // Chyba jedného kanála nezastaví celú frontu.
                    Diag.save(service, "Chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}", item.episode.channel.name, null, 0)
                    when {
                        stopped -> Outcome.STOPPED
                        Store.isDone(service, item.episode.key) -> Outcome.UNSURE // už sa ťuklo na Uverejniť
                        else -> Outcome.SKIPPED
                    }
                }
                when (outcome) {
                    Outcome.DONE -> okCount++
                    Outcome.UNSURE -> unsure.add(item)
                    Outcome.SKIPPED -> failed.add(item)
                    Outcome.STOPPED -> break@round
                }
            }
            if (failed.isEmpty()) break
            queue = ArrayList(failed)
            if (round == 1) {
                overlay.show("↻ Ešte raz: ${failed.joinToString { it.episode.channel.name }}", stopButton())
                sleepStoppable(2_000)
            }
        }
        val names = failed.map { it.episode.channel.name }.distinct()
        val unsureNames = unsure.map { it.episode.channel.name }.distinct()
        val head = if (stopped) "Zastavené" else "Hotovo"
        val tail = (if (names.isEmpty()) "" else "\nNepodarilo sa: ${names.joinToString()} (diagnostika uložená)") +
            (if (unsureNames.isEmpty()) "" else "\nNeoverené, pozri profil: ${unsureNames.joinToString()}")
        overlay.show("$head: $okCount z ${items.size} nahraných$tail")
        overlay.hideAfter(if (tail.isEmpty()) 6_000 else 30_000)
    }

    /** Jeden kanál: stiahnutie a najviac [EPISODE_ATTEMPTS] pokusov, každý od spustenia TikToku. */
    private fun runItem(item: Item, confirmBeforePost: Boolean): Outcome {
        val ep = item.episode
        val steps = Macro.load(service)
        if (steps.isEmpty()) {
            ask("Postup nie je naučený.", "Zavrieť" to Decision.STOP)
            return Outcome.STOPPED
        }
        if (item.caption.isBlank()) {
            // Bez popisu (kredit hudby) sa nikdy neuverejňuje.
            Diag.save(service, "Prázdny popis", ep.channel.name, null, 0)
            return Outcome.SKIPPED
        }
        if (!download(ep)) return if (stopped) Outcome.STOPPED else Outcome.SKIPPED
        for (attempt in 1..EPISODE_ATTEMPTS) {
            if (stopped) return Outcome.STOPPED
            note = if (attempt > 1) " · pokus $attempt/$EPISODE_ATTEMPTS" else ""
            Nodes.copyToClipboard(service, item.caption)
            // Prepnutie skúša samo [SWITCH_ATTEMPTS]×; keď nezaberie, kanál ide znova až v 2. kole.
            val account = ensureAccount(ep.channel)
            if (account != Outcome.DONE) return account
            if (!openHome()) {
                if (stopped) return Outcome.STOPPED
                continue
            }
            val outcome = playUpload(steps, ep, item.caption, confirmBeforePost)
            if (outcome != Outcome.SKIPPED) return outcome
        }
        return Outcome.SKIPPED
    }

    /** Čerstvé stiahnutie do galérie (video je potom najnovšie). Pri chybe skúša znova sama; false = kanál sa preskočí. */
    private fun download(ep: Episode): Boolean {
        val label = "⬇ ${ep.channel.name}: sťahujem video…"
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            if (stopped) return false
            overlay.show(label, stopButton())
            try {
                var lastPercent = -2 // karta sa prekreslí len pri zmene celého percenta
                Downloads.fetchToGallery(service, ep) { percent ->
                    // Stop preruší aj rozbehnuté sťahovanie (dočasný súbor sa zmaže, galéria ostane bez zmeny).
                    if (stopped) throw IOException("zastavené")
                    if (percent != lastPercent) {
                        lastPercent = percent
                        if (percent >= 0 && !stopped) overlay.show("$label $percent %", stopButton())
                    }
                }
                overlay.show("✔ video v galérii", stopButton())
                sleepStoppable(500)
                return true
            } catch (e: Exception) {
                if (stopped) return false
                val why = e.message ?: e.javaClass.simpleName
                if (attempt == DOWNLOAD_ATTEMPTS) {
                    Diag.save(service, "Sťahovanie zlyhalo: $why", ep.channel.name, null, 0)
                    return false
                }
                overlay.show("Sťahovanie zlyhalo ($why) – skúsim znova o ${attempt * 10} s", stopButton())
                sleepStoppable(attempt * 10_000L)
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
     * Zabezpečí, že v TikToku je prihlásený účet kanála [ch]. Skúša [SWITCH_ATTEMPTS]× (zakaždým od spustenia
     * TikToku); keď nič nezaberie, uloží diagnostiku s priebehom a vráti SKIPPED - nepýta sa.
     */
    private fun ensureAccount(ch: Channel): Outcome {
        val keys = Accounts.keys(ch)
        if (keys.isEmpty()) return Outcome.DONE
        val handle = ch.tiktok.ifEmpty { ch.name }
        val log = ArrayList<String>()
        for (attempt in 1..SWITCH_ATTEMPTS) {
            if (stopped) return Outcome.STOPPED
            switchOnce(ch, keys, handle, attempt, log)?.let { return it }
        }
        if (stopped) return Outcome.STOPPED
        Diag.save(service, "Nepodarilo sa prepnúť na $handle", ch.name, null, 0, Macro.loadSwitch(service), log)
        return Outcome.SKIPPED
    }

    /**
     * Jeden pokus o prepnutie: Profil → ak je účet v hlavičke, hotovo; inak tlačidlo „Switch account“, ťuk na meno
     * účtu v hlavičke (známy účet, inak stred hornej lišty) a naučená cesta. null = nepodarilo sa (priebeh v [log]).
     */
    private fun switchOnce(ch: Channel, keys: List<String>, handle: String, attempt: Int, log: MutableList<String>): Outcome? {
        val tag = if (attempt > 1) " (pokus $attempt/$SWITCH_ATTEMPTS)" else ""
        overlay.show("👤 ${ch.name}: kontrolujem účet…$tag", stopButton())
        if (!openProfile()) return if (stopped) Outcome.STOPPED else null
        var check = waitProfile(keys)
        if (stopped) return Outcome.STOPPED
        if (check.isTarget) return Outcome.DONE
        var current = check.current
        log.add("$attempt: profil, aktuálny účet ${current?.firstOrNull() ?: "neznámy"}")
        overlay.show("👤 ${ch.name}: prepínam na $handle…$tag", stopButton())

        // (1) tlačidlo "Switch account" v hornej lište
        val switchButton = pollNode(1_500L) { findInTopBar(it) { label -> SWITCH_WORDS.any { w -> label.lowercase(Locale.ROOT).contains(w) } } }
        if (switchButton != null) {
            click(switchButton)
            if (tapAccountInList(keys) && verifySwitched(ch, keys)) return Outcome.DONE
            if (stopped) return Outcome.STOPPED
            log.add("$attempt: Switch account nezabralo")
            if (!openProfile()) return if (stopped) Outcome.STOPPED else null
            check = waitProfile(keys)
            if (check.isTarget) return Outcome.DONE
            current = check.current ?: current
        }

        // (2) ťuk na meno účtu v hlavičke profilu (otvorí zoznam účtov) - nezávisí od naučenej cesty ani od účtu
        val cur = current
        val header = pollNode(1_500L) { nodes ->
            (if (cur != null) findInTopBar(nodes) { label -> Accounts.matches(label, cur) } else null) ?: findHeaderTitle(nodes)
        }
        val size = Nodes.screenSize(service)
        if (header != null) {
            click(header)
        } else if (!forbiddenAt(size.x * 0.5f, size.y * HEADER_Y)) {
            tapRel(0.5f, HEADER_Y)
        }
        if (tapAccountInList(keys) && verifySwitched(ch, keys)) return Outcome.DONE
        if (stopped) return Outcome.STOPPED
        val tapped = if (header != null) "„" + (Nodes.label(header) ?: "") + "“" else "(stred lišty)"
        log.add("$attempt: hlavička $tapped nezabrala")

        // (3) naučená cesta; učí sa len, keď ešte nie je (raz, s používateľom)
        if (!openProfile()) return if (stopped) Outcome.STOPPED else null
        if (waitProfile(keys).isTarget) return Outcome.DONE
        if (stopped) return Outcome.STOPPED
        val path = Macro.loadSwitch(service)
        if (path.isNotEmpty()) {
            if (playSwitch(path, ch, log) && verifySwitched(ch, keys)) return Outcome.DONE
            log.add("$attempt: naučená cesta nezabrala")
        } else if (attempt == 1) {
            if (record(Recorder.Mode.SWITCH, "", ch) == Recorder.Result.ACCOUNT && verifySwitched(ch, keys)) {
                return Outcome.DONE
            }
        }
        return if (stopped) Outcome.STOPPED else null
    }

    /** Meno účtu v hlavičke vlastného profilu: klikateľný text v hornej lište, približne v strede šírky. */
    private fun findHeaderTitle(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val size = Nodes.screenSize(service)
        return nodes.firstOrNull { n ->
            val r = Nodes.bounds(n)
            n.isVisibleToUser && !r.isEmpty && Nodes.ownText(n) != null &&
                r.exactCenterY() <= size.y * TOP_BAR &&
                r.exactCenterX() >= size.x * 0.25f && r.exactCenterX() <= size.x * 0.75f &&
                Nodes.clickableAncestor(n) != null
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

    /** Spustí TikTok od začiatku (domovský feed) a chvíľu počká; pri neúspechu ešte 2×. False = nejde / zastavené. */
    private fun openHome(): Boolean {
        var tries = 1
        while (!TikTokShare.launchHome(service)) {
            if (stopped || tries++ >= 3) return false
            sleepStoppable(3_000)
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

    /** Prehrá naučenú cestu prepnutia účtu (od profilu); krok ACCOUNT vyberie účet kanála [ch]. Chyba ide do [log]. */
    private fun playSwitch(path: List<Step>, ch: Channel, log: MutableList<String>): Boolean {
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
                log.add("cesta: krok ${i + 1} sa nenašiel – ${Macro.describe(step)}")
                return false
            }
        }
        return !stopped
    }

    // ------------------------------------------------------------------
    // Jedna epizóda
    // ------------------------------------------------------------------

    /**
     * Prehrá naučený postup pre epizódu (účet je už prepnutý). Bez obsluhy: krok, ktorý sa nenájde ani po zopakovaní
     * ťuku, uloží diagnostiku a vráti SKIPPED - [runItem] potom skúsi celý postup znova od spustenia TikToku.
     */
    private fun playUpload(steps: List<Step>, ep: Episode, caption: String, confirmBeforePost: Boolean): Outcome {
        val name = ep.channel.name
        runLog.clear()
        var previous: Step? = null
        // Texty obrazovky, na ktorej sa robil predošlý krok (nové texty ďalšieho kroku sa porovnávajú s nimi).
        var seen: Set<String> = emptySet()
        val total = steps.size
        for ((i, step) in steps.withIndex()) {
            if (stopped) return Outcome.STOPPED
            val n = i + 1
            showProgress(name, n, total)
            sleepStoppable(minOf(step.gapMs, MAX_GAP_MS))
            if (stopped) return Outcome.STOPPED

            val before = previous?.sig ?: emptyList()

            // Vyskakovacie okno, ktoré bolo pri učení: keď sa neukáže, krok sa ticho preskočí.
            if (isOptional(step)) {
                if (awaitScreen(step, OPTIONAL_TIMEOUT_MS, dismiss = false, before = before, seen = seen)) {
                    seen = seenNow()
                    act(step, ep.channel, caption, optional = true)
                }
                previous = step
                continue
            }

            val timeout = timeoutFor(step, previous)
            // Ťuk predošlého kroku TikTok nemusel zachytiť: keď predošlá obrazovka stále svieti, ťuk sa zopakuje.
            val prev = previous
            val retap: (() -> Unit)? = if (prev != null && canRepeat(prev)) {
                { act(prev, ep.channel, caption) }
            } else {
                null
            }
            val screen = awaitScreenOrAhead(step, steps.getOrNull(i + 1), timeout, true, before, seen, retap)
            seen = seenNow()
            if (screen == Screen.AHEAD) {
                // TikTok je už ďalej (krok sa pri učení zapísal dvakrát) - krok netreba robiť.
                runLog.add("$n ${Macro.describe(step)}: už hotové, preskočené")
                previous = step
                continue
            }
            runLog.add("$n ${Macro.describe(step)}: ${screen.name}")
            var result = if (screen == Screen.OK) StepResult.OK else StepResult.NOT_FOUND

            if (result == StepResult.OK && step.type == StepType.POST) {
                if (confirmBeforePost) {
                    val d = ask(
                        "Skontroluj účet, video a popis ($name)",
                        "Uverejniť" to Decision.CONFIRM,
                        "Zrušiť" to Decision.STOP,
                    )
                    if (d != Decision.CONFIRM) return Outcome.STOPPED
                    showProgress(name, n, total)
                } else if (!captionOnScreen(caption)) {
                    // Bez popisu (kredit hudby) sa neuverejňuje: vloží sa znova, inak nový pokus od začiatku.
                    if (!refillCaption(steps, caption) || !captionOnScreen(caption)) {
                        runLog.add("$n: popis chýba a nedá sa vložiť")
                        Diag.save(service, "Popis sa nenašiel pred Uverejniť", name, step, n, steps, runLog)
                        return if (stopped) Outcome.STOPPED else Outcome.SKIPPED
                    }
                    runLog.add("$n: popis vložený znova")
                }
            }
            if (result == StepResult.OK) {
                result = act(step, ep.channel, caption)
                if (result != StepResult.OK) runLog.add("$n: ${result.name}")
            }
            if (result != StepResult.OK) {
                if (stopped) return Outcome.STOPPED
                val what = if (result == StepResult.FAILED) "zlyhal" else "sa nenašiel"
                Diag.save(service, "Krok $n $what", name, step, n, steps, runLog)
                return Outcome.SKIPPED
            }

            if (step.type == StepType.POST) {
                // Po ťuku na Uverejniť sa kanál už nikdy neopakuje (ani po Stop, ani v ďalšom pokuse či behu):
                // pomalé uverejnenie by inak poslalo video dvakrát.
                Store.setDone(service, ep.key, true)
                var posted = confirmPosted(step)
                if (!posted && !stopped) {
                    // Uverejniť stále svieti = video nie je uverejnené (inak by TikTok obrazovku zavrel) -> ešte raz.
                    runLog.add("$n: Uverejniť ešte svieti, ťukám znova")
                    posted = when (act(step, ep.channel, caption)) {
                        // tlačidlo medzitým zmizlo = TikTok už uverejňuje
                        StepResult.NOT_FOUND -> true
                        StepResult.OK -> confirmPosted(step)
                        else -> false
                    }
                }
                if (stopped) return Outcome.STOPPED
                if (!posted) {
                    runLog.add("$n: uverejnenie sa nepotvrdilo")
                    Diag.save(service, "Uverejnenie sa nepotvrdilo", name, step, n, steps, runLog)
                    return Outcome.UNSURE
                }
                overlay.show("✔ $name: uverejnené, čakám…", stopButton())
                sleepStoppable(AFTER_POST_WAIT_MS)
                return Outcome.DONE
            }
            previous = step
        }
        ask("Postup skončil bez kroku Uverejniť.", "Zavrieť" to Decision.STOP)
        return Outcome.STOPPED
    }

    /**
     * Po ťuku na Uverejniť: potvrdí okno „Post video publicly?“ ([POST_CONFIRM]) a počká, kým tlačidlo Uverejniť zmizne.
     * False = TikTok je po [POSTED_WAIT_MS] stále na obrazovke s Uverejniť (video sa neuverejnilo).
     */
    private fun confirmPosted(step: Step): Boolean {
        val size = Nodes.screenSize(service)
        val start = SystemClock.uptimeMillis()
        var confirms = 0
        while (!stopped && SystemClock.uptimeMillis() - start < POSTED_WAIT_MS) {
            sleepStoppable(POSTED_POLL_MS)
            val nodes = try {
                Nodes.collect(Nodes.tiktokRoots(service))
            } catch (e: Exception) {
                emptyList()
            }
            if (nodes.isEmpty()) continue
            val confirm = Nodes.findByLabels(nodes, POST_CONFIRM)
            if (confirm != null) {
                if (confirms < MAX_CONFIRMS) {
                    runLog.add("potvrdenie „${Nodes.label(confirm)}“")
                    click(confirm)
                    confirms++
                }
                continue
            }
            if ((findLabeled(nodes, step.sel, size.x, size.y) ?: Nodes.findByLabels(nodes, Recorder.POST_LABELS)) == null) return true
        }
        return false
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

    /** Krok, ktorý sa smie zopakovať, keď TikTok jeho ťuk nezachytil: obyčajný ťuk (nie popis, Uverejniť ani okno). */
    private fun canRepeat(step: Step): Boolean =
        (step.type == StepType.CLICK || step.type == StepType.LONG) && !isOptional(step)

    /** Popis nie je na obrazovke: vloží ho znova do poľa popisu. False = pole sa nenašlo alebo sa nedalo vyplniť. */
    private fun refillCaption(steps: List<Step>, caption: String): Boolean {
        val sel = steps.firstOrNull { it.type == StepType.CAPTION }?.sel ?: return false
        val size = Nodes.screenSize(service)
        val field = pollNode(TARGET_WAIT_MS) { captionField(it, sel, size.x, size.y) } ?: return false
        if (!Nodes.fillText(service, field, caption)) return false
        sleepStoppable(800)
        return !stopped
    }

    private fun showProgress(name: String, n: Int, total: Int) {
        overlay.show("▶ $name: krok $n/$total$note", stopButton())
    }

    private fun stopButton() = OverlayButton("Stop") { stop() }

    // ------------------------------------------------------------------
    // Obrazovka a prvok kroku
    // ------------------------------------------------------------------

    private fun awaitScreen(
        step: Step,
        timeoutMs: Long,
        dismiss: Boolean = true,
        before: List<String> = emptyList(),
        seen: Set<String> = emptySet(),
    ): Boolean = awaitScreenOrAhead(step, null, timeoutMs, dismiss, before, seen) == Screen.OK

    /** Texty, ktoré sú teraz vidieť v TikToku (malými písmenami); pri chybe prázdna množina. */
    private fun seenNow(): Set<String> = try {
        Nodes.visibleTexts(Nodes.collect(Nodes.tiktokRoots(service)))
    } catch (e: Exception) {
        emptySet()
    }

    /**
     * Čaká, kým obrazovka TikToku zodpovedá podpisu kroku: aspoň Nodes.sigNeed textov podpisu je vidieť a aj
     * niektoré texty, ktoré na predošlej obrazovke neboli (jej podpis [before] a všetko, čo na nej bolo vidieť pri
     * prehrávaní, [seen]) - inak by sa ťuklo skôr, než sa obrazovka zmení (kamera, galéria a editor majú spoločné
     * „Add sound“, „Music“, „Next“…). Po [RELAX_MS] stačí aj to, že predošlá obrazovka zmizla.
     *
     * AHEAD = TikTok je už na obrazovke kroku [ahead] (krok sa pri učení zapísal dvakrát alebo sa už vykonal):
     * aspoň 3 s nesedí obrazovka kroku ani predošlá, ale sedí obrazovka ďalšieho kroku aj s jeho novými textami.
     * Kým čaká dlhšie než 4 s, skúša zavrieť vyskakovacie okná ([dismiss]) - nikdy prvkom z obrazovky kroku
     * ani predošlej (krížik „Close“ galérie). TIMEOUT = vypršal čas / Stop.
     */
    private fun awaitScreenOrAhead(
        step: Step,
        ahead: Step?,
        timeoutMs: Long,
        dismiss: Boolean,
        before: List<String>,
        seen: Set<String>,
        retap: (() -> Unit)? = null,
    ): Screen {
        val need = Nodes.sigNeed(step.sig)
        val fresh = Nodes.sigNew(step.sig, before + seen)
        // 1 nový text môže byť náhoda (bublina s radou), 4+ nových = iná obrazovka: musia byť vidieť aspoň 2
        // („Next“ je v galérii aj v editore).
        val freshNeed = when {
            fresh.size < 2 -> 0
            fresh.size < 4 -> 1
            else -> 2
        }
        val beforeNeed = Nodes.sigNeed(before)
        // Texty, ktoré mala len predošlá obrazovka (nie táto) - ťuk sa opakuje, len keď svietia všetky.
        val beforeOnly = Nodes.sigNew(before, step.sig)
        val canAhead = ahead != null && ahead.sig.isNotEmpty() &&
            (step.type == StepType.CLICK || step.type == StepType.LONG || step.type == StepType.SWIPE)
        val aheadFresh = if (ahead != null && canAhead) Nodes.sigNew(ahead.sig, step.sig + seen) else emptyList()
        val keep = step.sig + before
        val start = SystemClock.uptimeMillis()
        var lastDismissAt = 0L
        var lastTapAt = start
        var repeats = 0
        while (!stopped) {
            val elapsed = SystemClock.uptimeMillis() - start
            var busy = false
            val visible = try {
                val nodes = Nodes.collect(Nodes.tiktokRoots(service))
                busy = nodes.any { it.isVisibleToUser && it.className?.toString()?.contains("ProgressBar") == true }
                if (nodes.isEmpty()) null else Nodes.visibleTexts(nodes)
            } catch (e: Exception) {
                null
            }
            if (visible != null) {
                val changed = Nodes.sigOverlap(fresh, visible) >= freshNeed ||
                    (elapsed >= RELAX_MS && (beforeNeed == 0 || Nodes.sigOverlap(before, visible) < beforeNeed))
                if (Nodes.sigOverlap(step.sig, visible) >= need && changed) return Screen.OK
                // Predošlá obrazovka už nesmie byť vidieť (inak sa len pomaly otvára obrazovka tohto kroku).
                if (ahead != null && aheadFresh.size >= 2 && elapsed >= AHEAD_AFTER_MS &&
                    (beforeNeed == 0 || Nodes.sigOverlap(before, visible) < beforeNeed) &&
                    Nodes.sigOverlap(ahead.sig, visible) >= Nodes.sigNeed(ahead.sig) &&
                    Nodes.sigOverlap(aheadFresh, visible) >= 2
                ) {
                    return Screen.AHEAD
                }
                // Ťuk predošlého kroku sa stratil: svietia všetky texty, ktoré mala len predošlá obrazovka, nič nové
                // z tohto kroku a nič sa nenačítava (koliesko, percentá, Loading) -> zopakovať ([retap], najviac
                // [MAX_REPEATS]×).
                if (retap != null && repeats < MAX_REPEATS && SystemClock.uptimeMillis() - lastTapAt >= LOST_TAP_MS &&
                    beforeOnly.isNotEmpty() && Nodes.sigOverlap(beforeOnly, visible) == beforeOnly.size &&
                    Nodes.sigOverlap(fresh, visible) == 0 && !busy &&
                    visible.none { t -> PERCENT.containsMatchIn(t) || BUSY_WORDS.any { t.contains(it) } }
                ) {
                    repeats++
                    runLog.add("opakujem ťuk predošlého kroku ($repeats×)")
                    retap()
                    lastTapAt = SystemClock.uptimeMillis()
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) return Screen.TIMEOUT
            // Obrazovka sa dlho neukazuje -> možno ju zakrýva vyskakovacie okno TikToku, skúsime ho bezpečne zavrieť.
            if (dismiss && now - start > DISMISS_AFTER_MS && now - lastDismissAt >= DISMISS_EVERY_MS) {
                lastDismissAt = now
                try {
                    dismissPopup(keep)
                } catch (e: Exception) {
                    // zatváranie okna je len pomoc
                }
            }
            sleepStoppable(POLL_MS)
        }
        return Screen.TIMEOUT
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
                // Galéria videí: ťuk až keď sú načítané náhľady (trvanie „0:42“), inak padne do prázdnej mriežky.
                if (step.sig.any { it.equals("Videos", ignoreCase = true) }) {
                    pollNode(GRID_WAIT_MS) { nodes ->
                        nodes.firstOrNull { n -> n.isVisibleToUser && Nodes.ownText(n)?.let { DURATION.matches(it) } == true }
                    }
                    sleepStoppable(400)
                }
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
     * Zatvorí vyskakovacie okno TikToku: ťukne na viditeľný uzol, ktorého text/popis je presne zo [SAFE_DISMISS]
     * a nie je medzi textami [keep] (obrazovka, ktorá patrí k postupu, napr. krížik „Close“ galérie).
     * Hľadá sa len v oknách TikToku (nikdy v našej karte). True = ťuklo sa.
     */
    private fun dismissPopup(keep: List<String> = emptyList()): Boolean {
        val roots = Nodes.tiktokRoots(service)
        if (roots.isEmpty()) return false
        val labels = SAFE_DISMISS.filter { l -> keep.none { it.equals(l, ignoreCase = true) } }
        if (labels.isEmpty()) return false
        val node = Nodes.findByLabels(Nodes.collect(roots), labels) ?: return false
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
