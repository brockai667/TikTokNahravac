package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    class Item(val episode: Episode, val caption: String, val phoneOnly: Boolean = false)

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

        /** Odoslanie príspevku do kampane Content Rewards (Chrome). */
        private const val CHROME = "com.android.chrome"
        private const val CAMPAIGN_PREFIX = "https://contentrewards.com/c/campaigns/"
        private const val CAMPAIGN_FIRST_WAIT_MS = 60_000L
        private const val CAMPAIGN_RETRY_WAIT_MS = 60_000L
        private const val CAMPAIGN_ATTEMPTS = 6
        private const val SUBMIT_BUTTON_WAIT_MS = 25_000L
        private const val CHOOSE_WAIT_MS = 15_000L
        private const val RESULT_WAIT_MS = 20_000L
        private const val CLS_BUTTON = "android.widget.Button"
        private const val CLS_TOGGLE = "android.widget.ToggleButton"
        private const val CLS_CHECKBOX = "android.widget.CheckBox"
        private const val CLS_TEXTVIEW = "android.widget.TextView"
        private val SUBMIT_N_CLIPS = Regex("Submit \\d+ clips")
        private val WHITESPACE = Regex("[\\s\\u00A0]+")
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
    fun runAll(items: List<Item>): Boolean {
        if (items.isEmpty()) return false
        return startThread { runQueue(items) }
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
    private fun runQueue(items: List<Item>) {
        var okCount = 0
        var queue = items
        val failed = ArrayList<Item>()
        val unsure = ArrayList<Item>()
        val forCampaign = ArrayList<Item>()
        val phone = try {
            Repo.fetchPhonePosts(service)
        } catch (e: Exception) {
            emptySet()
        }
        round@ for (round in 1..2) {
            failed.clear()
            for (item in queue) {
                if (stopped) break@round
                val outcome = if (item.phoneOnly) Outcome.DONE else try {
                    runItem(item)
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
                    Outcome.DONE -> {
                        okCount++
                        if (!item.phoneOnly && !item.episode.campaignUrl.isNullOrBlank()) forCampaign.add(item)
                    }
                    Outcome.UNSURE -> unsure.add(item)
                    Outcome.SKIPPED -> failed.add(item)
                    Outcome.STOPPED -> break@round
                }
                // Potom ten istý diel z mobilu na YouTube a Instagram (len v 1. kole; každá platforma si pamätá svoj stav).
                if (round == 1 && phone.isNotEmpty() && !stopped) runPhonePosts(item, phone)
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
        if (!stopped) {
            // Koniec celého behu: po Post chvíľu počkať a domček, nech v TikToku nebeží video (upload ide na pozadí).
            sleepStoppable(4_000)
            if (forCampaign.isNotEmpty()) submitCampaigns(forCampaign)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    /** Skúška odoslania do kampane (adb SUBMIT_TEST): 1 pokus, bez čakania 60 s; po skončení domček. */
    fun submitTest(url: String, caption: String, account: String, dry: Boolean, link: String? = null): Boolean = startThread {
        val acct = account.trim().removePrefix("@")
        val r = if (link != null) {
            submitLink("test", url, link, dry, "test", "youtube", acct)
        } else {
            submitCampaign("test", acct, url, caption, acct, 1, dry)
        }
        overlay.show("Kampaň (skúška${if (dry) ", dry" else ""}): ${if (r.ok) "OK" else "zlyhalo"} – ${r.reason}")
        overlay.hideAfter(8_000)
        if (!stopped) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Odošle Instagram/YouTube príspevky (vyšli cez Buffer) do ich kampaní, jeden po druhom; po skončení domček.
     * Úspech = hotové; neúspech = pokus +1, po kliknutí na Submit rovno maximum (neklikať dvakrát).
     */
    fun runSubmits(subs: List<Submit>): Boolean {
        if (subs.isEmpty()) return false
        return startThread {
            var okCount = 0
            for (s in subs) {
                if (stopped) break
                val r = try {
                    submitOne(s)
                } catch (e: Exception) {
                    fail("chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
                if (r.ok) {
                    okCount++
                    Store.setSubmitDone(service, s.id)
                } else if (r.clicked) {
                    Store.setSubmitTries(service, s.id, AutoRun.MAX_SUBMIT_TRIES)
                } else if (!stopped) {
                    Store.setSubmitTries(service, s.id, Store.submitTries(service, s.id) + 1)
                }
                overlay.show("Kampane: ${s.account} (${s.platform}) – ${if (r.ok) "odoslané" else "zlyhalo: ${r.reason}"}", stopButton())
                sleepStoppable(2_000)
            }
            overlay.show("Kampane: $okCount z ${subs.size} odoslaných")
            overlay.hideAfter(6_000)
            if (!stopped) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    private fun submitOne(s: Submit): SubmitResult = when (s.platform) {
        "instagram" -> submitCampaign(s.id, s.account.removePrefix("@"), s.campaignUrl, s.caption, s.account, 1, false, s.id, "instagram")
        "youtube" -> submitYoutube(s)
        else -> fail("neznáma platforma: ${s.platform}")
    }

    /** Jeden kanál: stiahnutie a najviac [EPISODE_ATTEMPTS] pokusov, každý od spustenia TikToku. */
    private fun runItem(item: Item): Outcome {
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
            val outcome = playUpload(steps, ep, item.caption)
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
                Log.w(RunAllReceiver.TAG, "stahovanie ${ep.fileName} ($attempt/$DOWNLOAD_ATTEMPTS): ${e.javaClass.simpleName}: $why")
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
            sleepStoppable(gapFor(step))
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
    // Odoslanie príspevku do kampane Content Rewards (Chrome)
    // ------------------------------------------------------------------

    /** [clicked] = už sa kliklo na odosielacie tlačidlo (výsledok neistý): ten istý príspevok sa už nikdy nesmie odoslať znova. */
    private class SubmitResult(val ok: Boolean, val reason: String, val retry: Boolean = false, val clicked: Boolean = false)

    /** Dlaždica príspevku v dialógu „Choose clips“: [text] je s normalizovanými medzerami. */
    private class Tile(val node: AccessibilityNodeInfo, val text: String, val checked: Boolean)

    /** Na odosielacie tlačidlo sa v aktuálnom pokuse už kliklo (pre výsledok po výnimke). */
    @Volatile
    private var clickedSubmit = false

    private fun clog(msg: String) {
        Log.i(RunAllReceiver.TAG, "kampan: $msg")
    }

    private fun fail(reason: String, retry: Boolean = false, clicked: Boolean = false): SubmitResult {
        clog("zlyhalo: $reason")
        return SubmitResult(false, reason, retry, clicked)
    }

    /** Po 60 s (Content Rewards musí príspevok zbadať) odošle každú položku do jej kampane; výsledok ide do submitted.jsonl. */
    private fun submitCampaigns(items: List<Item>) {
        overlay.show("Kampaň: čakám 60 s, kým Content Rewards zbadá príspevky…", stopButton())
        sleepStoppable(CAMPAIGN_FIRST_WAIT_MS)
        for (item in items) {
            if (stopped) return
            val ep = item.episode
            val acct = ep.channel.tiktok.ifEmpty { ep.channel.name }.trim().removePrefix("@")
            val url = ep.campaignUrl ?: continue
            val r = submitCampaign(ep.key, ep.channel.slug, url, item.caption, acct, CAMPAIGN_ATTEMPTS, false)
            if (stopped) return
            overlay.show("Kampaň: ${ep.channel.name} – ${if (r.ok) "odoslané" else "zlyhalo: ${r.reason}"}", stopButton())
            sleepStoppable(2_000)
        }
    }

    /**
     * Odošle jeden príspevok do kampane; najviac [attempts] pokusov (opakuje sa len, keď dlaždica ešte nie je v zozname).
     * [dry] = všetko po kontrolu pred klikom, tlačidlo odoslania sa nestlačí. Výsledok sa zapíše do submitted.jsonl.
     */
    private fun submitCampaign(
        key: String, channel: String, url: String, caption: String, account: String, attempts: Int, dry: Boolean,
        id: String? = null, platform: String? = null,
    ): SubmitResult {
        val prefix = captionPrefix(caption)
        val acct = account.trim().removePrefix("@").lowercase(Locale.ROOT)
        var r = when {
            !url.startsWith(CAMPAIGN_PREFIX) -> fail("adresa kampane nie je povolená")
            prefix.isEmpty() -> fail("prázdny popis")
            acct.isEmpty() -> fail("chýba účet TikToku")
            else -> SubmitResult(false, "nespustené")
        }
        if (url.startsWith(CAMPAIGN_PREFIX) && prefix.isNotEmpty() && acct.isNotEmpty()) {
            for (attempt in 1..attempts) {
                if (stopped) {
                    r = fail("zastavené")
                    break
                }
                clog("$channel: pokus $attempt/$attempts${if (dry) " (dry)" else ""}")
                clickedSubmit = false
                r = try {
                    submitOnce(channel, url, prefix, acct, dry)
                } catch (e: Exception) {
                    fail("chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}", clicked = clickedSubmit)
                }
                if (r.ok || !r.retry || attempt == attempts) break
                overlay.show("Kampaň: $channel – dlaždica ešte nie je, čakám 60 s ($attempt/$attempts)…", stopButton())
                sleepStoppable(CAMPAIGN_RETRY_WAIT_MS)
            }
        }
        clog("$channel: výsledok ok=${r.ok} ${r.reason}")
        Diag.appendSubmitted(service, key, channel, url, r.ok, r.reason, dry, id, platform)
        return r
    }

    /** Prvých 40 znakov popisu s normalizovanými medzerami (nerozseknutý surogátový pár). */
    private fun captionPrefix(caption: String): String {
        var p = norm(caption).take(40)
        if (p.isNotEmpty() && p.last().isHighSurrogate()) p = p.dropLast(1)
        return p
    }

    private fun norm(s: String): String = s.replace(WHITESPACE, " ").trim()

    private fun cls(n: AccessibilityNodeInfo): String? = n.className?.toString()

    private fun txt(n: AccessibilityNodeInfo): String? = Nodes.ownText(n) ?: Nodes.ownDesc(n)

    private fun chromeNodes(): List<AccessibilityNodeInfo> = try {
        Nodes.collectFresh(Nodes.chromeRoots(service))
    } catch (e: Exception) {
        emptyList()
    }

    /** Ako [pollNode], ale nad oknami Chrome. */
    private fun <T : Any> pollChrome(timeoutMs: Long, find: (List<AccessibilityNodeInfo>) -> T?): T? {
        val start = SystemClock.uptimeMillis()
        while (!stopped) {
            val r = try {
                find(chromeNodes())
            } catch (e: Exception) {
                null
            }
            if (r != null) return r
            if (SystemClock.uptimeMillis() - start >= timeoutMs) return null
            sleepStoppable(POLL_MS)
        }
        return null
    }

    /** Uzol má nenulové hranice, ktoré aspoň sčasti ležia na obrazovke (strom Chrome má veľa uzlov [0,0][0,0] alebo pod okrajom). */
    private fun onScreen(n: AccessibilityNodeInfo): Boolean {
        val size = Nodes.screenSize(service)
        val r = Nodes.bounds(n)
        return !r.isEmpty && r.width() > 0 && r.height() > 0 && r.bottom > 0 && r.top < size.y && r.right > 0 && r.left < size.x
    }

    private fun isSubmitClipButton(n: AccessibilityNodeInfo): Boolean =
        cls(n) == CLS_BUTTON && Nodes.ownText(n) == "Submit clip" && onScreen(n)

    private fun hasChooseClips(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.any { txt(it)?.contains("Choose clips") == true }

    /** Dialóg odosielania je otvorený (stav „Choose clips“ aj „No new clips yet“): je v ňom CheckBox „I've read the requirements“. */
    private fun hasDialog(nodes: List<AccessibilityNodeInfo>): Boolean = findRequirementsBox(nodes) != null

    /** Dlaždice príspevkov: ToggleButton s textom obsahujúcim „, @“ a „Posted“ (filtre All, @účet N sú iné ToggleButtony). */
    private fun readTiles(nodes: List<AccessibilityNodeInfo>): List<Tile> = nodes.mapNotNull { n ->
        if (cls(n) != CLS_TOGGLE) return@mapNotNull null
        val t = txt(n) ?: return@mapNotNull null
        if (!t.contains(", @") || !t.contains("Posted")) return@mapNotNull null
        Tile(n, norm(t), n.isChecked)
    }

    /** Cieľová dlaždica: text začína popisom (prvých 40 znakov) a obsahuje „, @účet,“. */
    private fun isTarget(t: Tile, prefix: String, acct: String): Boolean =
        t.text.startsWith(prefix) && t.text.lowercase(Locale.ROOT).contains(", @$acct,")

    private fun findRequirementsBox(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        nodes.firstOrNull { cls(it) == CLS_CHECKBOX && txt(it)?.contains("read the requirements", ignoreCase = true) == true }

    /** Ťuk na uzol: ACTION_CLICK, ak ho uzol neprijme, gesto do stredu (nikdy oboje). */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val done = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            false
        }
        return done || click(node)
    }

    private fun closeDialog() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        sleepStoppable(1_000)
    }

    /** Dialóg je otvorený: každé zlyhanie ho zatvorí (BACK). */
    private fun failClosing(reason: String, retry: Boolean = false, clicked: Boolean = false): SubmitResult {
        closeDialog()
        return fail(reason, retry, clicked)
    }

    private fun describeTiles(tiles: List<Tile>): String =
        tiles.joinToString(" | ") { "${if (it.checked) "[x]" else "[ ]"} ${it.text.take(70)}" }

    /** a) a b): otvorí kampaň v Chrome a ťukne na „Submit clip“; null = dialóg sa otvára, inak zlyhanie. */
    private fun openCampaignDialog(label: String, url: String): SubmitResult? {
        // a) otvoriť adresu v Chrome
        overlay.show("Kampaň: $label – otváram kampaň v Chrome…", stopButton())
        try {
            service.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(CHROME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            return fail("Chrome sa nespustil: ${e.javaClass.simpleName}")
        }

        // b) tlačidlo „Submit clip“ (kým je otvorený starý dialóg, nehľadá sa: bolo by to odosielacie tlačidlo dialógu)
        val open = pollChrome(SUBMIT_BUTTON_WAIT_MS) { nodes ->
            if (hasDialog(nodes)) null else nodes.firstOrNull { isSubmitClipButton(it) }
        }
        if (open == null) {
            if (stopped) return fail("zastavené")
            val nodes = chromeNodes()
            return when {
                nodes.any { txt(it) == "Join Campaign" } -> fail("neprihlásený / nie v kampani (Join Campaign)")
                hasDialog(nodes) -> fail("dialóg bol už otvorený")
                else -> fail("tlačidlo Submit clip sa neukázalo")
            }
        }
        overlay.show("Kampaň: $label – otváram výber klipov…", stopButton())
        tap(open)
        return null
    }

    /** Zaškrtne „I've read the requirements“; null = zaškrtnuté, inak dôvod zlyhania. */
    private fun tickRequirements(): String? {
        var box = pollChrome(5_000L) { findRequirementsBox(it) } ?: return "políčko requirements sa nenašlo"
        if (!box.isChecked) {
            try {
                box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
            }
            sleepStoppable(700)
            val now = chromeNodes()
            box = findRequirementsBox(now) ?: return "políčko requirements zmizlo"
            if (!box.isChecked) {
                // políčko má len ~9×8 px: ťuk gestom na stred susedného textu
                val labelNode = now.firstOrNull {
                    cls(it) == CLS_TEXTVIEW && txt(it)?.contains("read the requirements", ignoreCase = true) == true && onScreen(it)
                }
                if (labelNode != null) {
                    click(labelNode)
                    sleepStoppable(700)
                }
            }
        }
        return null
    }

    /** Jeden pokus o odoslanie podľa postupu a) až i). */
    private fun submitOnce(label: String, url: String, prefix: String, acct: String, dry: Boolean): SubmitResult {
        openCampaignDialog(label, url)?.let { return it }

        // c) dialóg „Choose clips“
        val chooseNode = pollChrome(CHOOSE_WAIT_MS) { nodes -> nodes.firstOrNull { txt(it)?.contains("Choose clips") == true } }
            ?: return fail("dialóg Choose clips sa neotvoril")
        // d) dlaždice (môžu sa načítať s oneskorením)
        pollChrome(10_000L) { nodes -> readTiles(nodes).takeIf { it.isNotEmpty() } }
            ?: return failClosing("v dialógu nie sú žiadne dlaždice", retry = true)

        // e) práve jedna cieľová dlaždica
        val first = readTiles(chromeNodes())
        val targets = first.filter { isTarget(it, prefix, acct) }
        if (targets.isEmpty()) return failClosing("dlaždica príspevku sa nenašla (${first.size} dlaždíc)", retry = true)
        if (targets.size > 1) return failClosing("zhoduje sa viac dlaždíc (${targets.size})")
        clog("$label: cieľová dlaždica: ${targets[0].text.take(120)}")

        // f) výber: cieľová zaškrtnutá, všetky ostatné nie (po každom kliku nový strom)
        overlay.show("Kampaň: $label – vyberám príspevok…", stopButton())
        for (i in 1..12) {
            if (stopped) return failClosing("zastavené")
            val tiles = readTiles(chromeNodes())
            val bad = tiles.firstOrNull { isTarget(it, prefix, acct) != it.checked } ?: break
            tap(bad.node)
            sleepStoppable(700)
        }

        // g) začiarkavacie políčko „I've read the requirements“
        tickRequirements()?.let { return failClosing(it) }

        // h) tvrdá kontrola z čerstvého stromu tesne pred kliknutím (výber, políčko, tlačidlo)
        val nodes = chromeNodes()
        val tiles = readTiles(nodes)
        val finalTargets = tiles.filter { isTarget(it, prefix, acct) }
        val checked = tiles.filter { it.checked }
        val checkbox = findRequirementsBox(nodes)
        val tileState = "dlaždíc ${tiles.size}, zaškrtnutých ${checked.size}: ${describeTiles(tiles)}"
        clog("$label: $tileState; checkbox=${checkbox?.isChecked}")
        if (finalTargets.size != 1) return failClosing("po výbere nie je práve jedna cieľová dlaždica (${finalTargets.size})")
        if (checked.size != 1 || !isTarget(checked[0], prefix, acct)) return failClosing("výber nesedí: $tileState")
        if (checkbox?.isChecked != true) return failClosing("políčko requirements nie je zaškrtnuté")
        if (nodes.any { txt(it)?.let { t -> SUBMIT_N_CLIPS.matches(t) } == true }) return failClosing("vybrané je viac klipov (Submit N clips)")
        val submit = nodes.filter { isSubmitClipButton(it) }.maxByOrNull { Nodes.bounds(it).top }
            ?: return failClosing("tlačidlo Submit clip v dialógu sa nenašlo")
        val chooseBottom = Nodes.bounds(chooseNode).bottom
        if (chooseBottom > 0 && Nodes.bounds(submit).top <= chooseBottom) return failClosing("tlačidlo Submit clip nie je v dialógu")
        if (!submit.isEnabled) return failClosing("tlačidlo Submit clip je neaktívne")

        if (dry) {
            clog("$label: dry – kontroly prešli, neodosielam")
            closeDialog()
            return SubmitResult(true, "dry: kontroly prešli ($tileState)")
        }

        // i) odoslať (jedno kliknutie) a počkať na zavretie dialógu / „submitted“
        overlay.show("Kampaň: $label – odosielam…", stopButton())
        clickedSubmit = true
        tap(submit)
        val start = SystemClock.uptimeMillis()
        while (!stopped && SystemClock.uptimeMillis() - start < RESULT_WAIT_MS) {
            sleepStoppable(500)
            val now = chromeNodes()
            if (now.isEmpty()) continue
            // len zavretie dialógu: text „submitted“ je v dialógu vždy („N posts can't be submitted“)
            if (!hasDialog(now)) {
                return SubmitResult(true, "odoslané")
            }
        }
        if (stopped) return failClosing("zastavené po kliknutí na Submit (výsledok neznámy)", clicked = true)
        val errors = chromeNodes().mapNotNull { txt(it) }.filter { t ->
            listOf("error", "invalid", "already", "must", "fail", "unable", "limit", "not eligible", "required").any { t.contains(it, ignoreCase = true) }
        }.distinct().take(5)
        return failClosing("dialóg sa nezavrel; texty: ${errors.joinToString(" / ").ifEmpty { "(žiadne)" }}", clicked = true)
    }

    // ------------------------------------------------------------------
    // YouTube: odkaz sa vkladá do poľa v dialógu (Content Rewards YouTube príspevky nenačíta)
    // ------------------------------------------------------------------

    private val YT_SHORT_PREFIX = "https://www.youtube.com/shorts/"
    private val SIGNAL_TEXT = "Posted within the last 30 minutes"
    private val STATIC_TEXTS = listOf("Choose clips", "No new clips yet", "Submit clip", SIGNAL_TEXT, "read the requirements", "can't be submitted")

    /** Nájde odkaz v RSS kanála a odošle ho do kampane; výsledok ide do submitted.jsonl. */
    private fun submitYoutube(s: Submit): SubmitResult {
        val channelId = s.ytChannelId
        val wanted = s.title ?: s.caption
        val acct = s.account.removePrefix("@")
        val link = try {
            if (channelId == null) null else Repo.findYoutubeShort(channelId, wanted)
        } catch (e: Exception) {
            clog("${s.id}: RSS zlyhalo: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (link == null) {
            val r = fail(if (channelId == null) "chýba yt_channel_id" else "odkaz na YouTube Short sa v RSS nenašiel")
            Diag.appendSubmitted(service, s.id, acct, s.campaignUrl, false, r.reason, false, s.id, "youtube")
            return r
        }
        return submitLink(s.id, s.campaignUrl, link, false, s.id, "youtube", acct)
    }

    /** Odošle odkaz [link] do kampane [url] (len https://www.youtube.com/shorts/…); [dry] = bez kliknutia na odoslanie. */
    private fun submitLink(label: String, url: String, link: String, dry: Boolean, id: String, platform: String, account: String): SubmitResult {
        clickedSubmit = false
        val r = when {
            !url.startsWith(CAMPAIGN_PREFIX) -> fail("adresa kampane nie je povolená")
            !link.startsWith(YT_SHORT_PREFIX) -> fail("odkaz nie je YouTube Short")
            else -> try {
                clog("$label: youtube $link${if (dry) " (dry)" else ""}")
                submitLinkOnce(label, url, link, dry)
            } catch (e: Exception) {
                fail("chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}", clicked = clickedSubmit)
            }
        }
        clog("$label: výsledok ok=${r.ok} ${r.reason}")
        Diag.appendSubmitted(service, id, account, url, r.ok, r.reason, dry, id, platform, link)
        return r
    }

    private fun dialogHeading(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? = nodes.firstOrNull { n ->
        val t = txt(n)
        t != null && (t.contains("Choose clips") || t.contains("No new clips yet")) && onScreen(n)
    }

    /** EditText v dialógu: pod nadpisom dialógu a nikdy nie adresný riadok Chrome (hore, y < 230). */
    private fun dialogEdits(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val minTop = maxOf(230, dialogHeading(nodes)?.let { Nodes.bounds(it).top } ?: 0)
        return nodes.filter { cls(it) == "android.widget.EditText" && onScreen(it) && Nodes.bounds(it).top >= minTop }
    }

    /** Z čerstvého stromu: (text poľa == odkaz, objavil sa signál „Posted within the last 30 minutes“). */
    private fun linkState(link: String): Pair<Boolean, Boolean> {
        val nodes = chromeNodes()
        val edit = dialogEdits(nodes).singleOrNull()
        val textOk = edit != null && Nodes.ownText(edit)?.let { norm(it) } == link
        val signal = nodes.any { cls(it) == CLS_TEXTVIEW && txt(it)?.contains(SIGNAL_TEXT, ignoreCase = true) == true }
        return textOk to signal
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
        }
    }

    /** BACK (klávesnica alebo dialóg); ak dialóg ostal otvorený, ešte jeden BACK. */
    private fun closeKeyboardAndDialog() {
        closeDialog()
        if (hasDialog(chromeNodes())) closeDialog()
    }

    private fun ytFail(reason: String, clicked: Boolean = false): SubmitResult {
        closeKeyboardAndDialog()
        return fail(reason, false, clicked)
    }

    /** Jeden pokus o odoslanie odkazu YouTube podľa postupu b) až g). */
    private fun submitLinkOnce(label: String, url: String, link: String, dry: Boolean): SubmitResult {
        openCampaignDialog(label, url)?.let { return it }

        // dialóg je otvorený (Choose clips aj No new clips yet): rozpozná sa podľa CheckBoxu requirements
        pollChrome(CHOOSE_WAIT_MS) { findRequirementsBox(it) } ?: return fail("dialóg sa neotvoril")
        sleepStoppable(1_500)

        // c) odznačiť všetky dlaždice
        for (i in 1..12) {
            if (stopped) return ytFail("zastavené")
            val on = readTiles(chromeNodes()).firstOrNull { it.checked } ?: break
            tap(on.node)
            sleepStoppable(700)
        }
        if (readTiles(chromeNodes()).any { it.checked }) return ytFail("dlaždice sa nepodarilo odznačiť")

        // d) odkaz do poľa
        overlay.show("Kampaň: $label – vkladám odkaz…", stopButton())
        fun freshEdit(): AccessibilityNodeInfo? = dialogEdits(chromeNodes()).singleOrNull()
        val before = linkState("")
        clog("$label: signál pred vložením=${before.second}")
        val edit = freshEdit() ?: return ytFail("pole na odkaz sa nenašlo alebo ich je viac")
        setText(edit, link)
        sleepStoppable(1_500)
        var state = linkState(link)
        if (!(state.first && state.second)) {
            clog("$label: SET_TEXT nestačil (text=${state.first}, signál=${state.second}), skúšam vloženie zo schránky")
            val e2 = freshEdit() ?: return ytFail("pole odkaz neprijalo")
            try {
                e2.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                e2.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
            }
            sleepStoppable(500)
            val e3 = freshEdit() ?: return ytFail("pole odkaz neprijalo")
            setText(e3, "")
            Nodes.copyToClipboard(service, link)
            sleepStoppable(400)
            try {
                e3.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
            }
            sleepStoppable(1_500)
            state = linkState(link)
        }
        if (!(state.first && state.second)) return ytFail("pole odkaz neprijalo (text=${state.first}, signál=${state.second})")

        // f) zaškrtnúť requirements a tvrdá kontrola z jedného čerstvého stromu
        tickRequirements()?.let { return ytFail(it) }
        val nodes = chromeNodes()
        val tiles = readTiles(nodes)
        val edits = dialogEdits(nodes)
        val checkbox = findRequirementsBox(nodes)
        clog("$label: dlaždíc ${tiles.size}, zaškrtnutých ${tiles.count { it.checked }}; polí ${edits.size}; checkbox=${checkbox?.isChecked}")
        if (tiles.any { it.checked }) return ytFail("dlaždica je zaškrtnutá")
        if (edits.size != 1 || Nodes.ownText(edits[0])?.let { norm(it) } != link) return ytFail("pole nemá odkaz")
        if (checkbox?.isChecked != true) return ytFail("políčko requirements nie je zaškrtnuté")
        if (nodes.any { txt(it)?.let { t -> SUBMIT_N_CLIPS.matches(t) } == true }) return ytFail("zobrazuje sa Submit N clips")
        val submit = nodes.filter { isSubmitClipButton(it) }.maxByOrNull { Nodes.bounds(it).top }
            ?: return ytFail("tlačidlo Submit clip v dialógu sa nenašlo")
        val headBottom = dialogHeading(nodes)?.let { Nodes.bounds(it).bottom } ?: 0
        if (headBottom > 0 && Nodes.bounds(submit).top <= headBottom) return ytFail("tlačidlo Submit clip nie je v dialógu")
        if (!submit.isEnabled) return ytFail("tlačidlo Submit clip je neaktívne")

        if (dry) {
            clog("$label: dry – kontroly prešli (pole=$link, signál=${state.second}), neodosielam")
            closeKeyboardAndDialog()
            return SubmitResult(true, "dry: kontroly prešli (odkaz $link)")
        }

        // g) odoslať (jedno kliknutie); úspech = dialóg zmizol
        overlay.show("Kampaň: $label – odosielam…", stopButton())
        clickedSubmit = true
        tap(submit)
        val start = SystemClock.uptimeMillis()
        while (!stopped && SystemClock.uptimeMillis() - start < RESULT_WAIT_MS) {
            sleepStoppable(500)
            val now = chromeNodes()
            if (now.isEmpty()) continue
            if (!hasDialog(now)) return SubmitResult(true, "odoslané")
        }
        if (stopped) return ytFail("zastavené po kliknutí na Submit (výsledok neznámy)", clicked = true)
        val texts = chromeNodes().filter { onScreen(it) }.mapNotNull { txt(it) }.map { norm(it) }.filter { t ->
            t.length <= 200 && t != link && STATIC_TEXTS.none { t.contains(it, ignoreCase = true) } &&
                !(t.contains(", @") && t.contains("Posted"))
        }.distinct().take(6).map { it.take(80) }
        return ytFail("dialóg sa nezavrel; texty: ${texts.joinToString(" / ").ifEmpty { "(žiadne)" }}", clicked = true)
    }

    // ------------------------------------------------------------------
    // Jedna epizóda
    // ------------------------------------------------------------------

    /**
     * Prehrá naučený postup pre epizódu (účet je už prepnutý). Bez obsluhy: krok, ktorý sa nenájde ani po zopakovaní
     * ťuku, uloží diagnostiku a vráti SKIPPED - [runItem] potom skúsi celý postup znova od spustenia TikToku.
     */
    private fun playUpload(steps: List<Step>, ep: Episode, caption: String): Outcome {
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
            sleepStoppable(gapFor(step))
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

            if (result == StepResult.OK && step.type == StepType.POST && !captionOnScreen(caption)) {
                // Bez popisu (kredit hudby) sa neuverejňuje: vloží sa znova, inak nový pokus od začiatku.
                if (!refillCaption(steps, caption) || !captionOnScreen(caption)) {
                    runLog.add("$n: popis chýba a nedá sa vložiť")
                    Diag.save(service, "Popis sa nenašiel pred Uverejniť", name, step, n, steps, runLog)
                    return if (stopped) Outcome.STOPPED else Outcome.SKIPPED
                }
                runLog.add("$n: popis vložený znova")
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
                Diag.appendPosted(service, ep)
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

    /** Pauza pred krokom: naučené kroky max [MAX_GAP_MS]; krok bez podpisu obrazovky (vstavaný postup) čaká celý gapMs. */
    private fun gapFor(step: Step): Long = if (step.sig.isEmpty()) step.gapMs else minOf(step.gapMs, MAX_GAP_MS)

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

    // ------------------------------------------------------------------
    // Nahratie z mobilu: YouTube (Short) a Instagram (Reel); zapína ho kľúč phone_posts vo feede
    // ------------------------------------------------------------------

    private val YT = "com.google.android.youtube"
    private val IG = "com.instagram.android"
    private val CLS_EDITTEXT = "android.widget.EditText"

    private fun plog(msg: String) {
        Log.i(RunAllReceiver.TAG, "phone: $msg")
    }

    private fun pfail(reason: String, clicked: Boolean = false): SubmitResult {
        plog("zlyhalo: $reason")
        return SubmitResult(false, reason, false, clicked)
    }

    /** Vlastný text a popis uzla (Compose dáva text raz do text, raz do popisu). */
    private fun labels(n: AccessibilityNodeInfo): List<String> = listOfNotNull(Nodes.ownText(n), Nodes.ownDesc(n))

    /** Všetky uzly okien aplikácie; refresh je len pomoc (keď zlyhá, uzol sa aj tak prejde), aby sa podstrom nestratil. */
    private fun appNodes(pkg: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun go(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= 3_000 || depth > 50) return
            try {
                n.refresh()
            } catch (e: Exception) {
            }
            out.add(n)
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                go(c, depth + 1)
            }
        }
        try {
            for (r in Nodes.packageRoots(service, pkg)) go(r, 0)
        } catch (e: Exception) {
        }
        return out
    }

    private fun <T : Any> pollApp(pkg: String, timeoutMs: Long, find: (List<AccessibilityNodeInfo>) -> T?): T? {
        val start = SystemClock.uptimeMillis()
        while (!stopped) {
            val r = try {
                find(appNodes(pkg))
            } catch (e: Exception) {
                null
            }
            if (r != null) return r
            if (SystemClock.uptimeMillis() - start >= timeoutMs) return null
            sleepStoppable(POLL_MS)
        }
        return null
    }

    /** Prvý viditeľný uzol, ktorého text alebo popis obsahuje [s] (bez ohľadu na veľkosť písmen); [editable] = aj editovateľné. */
    private fun has(nodes: List<AccessibilityNodeInfo>, s: String, editable: Boolean = false): AccessibilityNodeInfo? =
        nodes.firstOrNull { n -> (editable || !n.isEditable) && onScreen(n) && labels(n).any { it.contains(s, ignoreCase = true) } }

    /** Prvý viditeľný uzol, ktorého text alebo popis sa presne rovná [s] (bez ohľadu na veľkosť písmen). */
    private fun exact(nodes: List<AccessibilityNodeInfo>, s: String): AccessibilityNodeInfo? =
        nodes.firstOrNull { n -> onScreen(n) && labels(n).any { it.trim().equals(s, ignoreCase = true) } }

    private fun byId(nodes: List<AccessibilityNodeInfo>, suffix: String): AccessibilityNodeInfo? =
        nodes.firstOrNull { it.viewIdResourceName?.endsWith(suffix) == true && onScreen(it) }

    /** Prvých ~40 viditeľných textov/popisov obrazovky do logu (na dolaďovanie selektorov). */
    private fun logScreen(tag: String, pkg: String) {
        val seen = LinkedHashSet<String>()
        val all = appNodes(pkg)
        for (n in all) {
            if (!onScreen(n)) continue
            val id = n.viewIdResourceName?.substringAfterLast('/')?.let { "#$it" } ?: ""
            for (l in labels(n)) {
                seen.add(l.replace(WHITESPACE, " ").take(60) + id)
                if (seen.size >= 40) break
            }
            if (seen.size >= 40) break
        }
        val wins = try {
            service.windows.joinToString(",") { w -> (w.root?.packageName?.toString() ?: "?").substringAfterLast('.') }
        } catch (e: Exception) {
            "?"
        }
        plog("$tag: obrazovka (uzlov ${all.size}, okna $wins): ${seen.joinToString(" | ")}")
    }

    /** Ladenie (adb SCREEN_LOG): strom okien aplikácie [pkg] do logu, bez zásahu do obrazovky. */
    fun logTree(pkg: String) {
        Thread {
            val lines = ArrayList<String>()
            fun go(n: AccessibilityNodeInfo, d: Int) {
                if (lines.size >= 150 || d > 40) return
                try {
                    n.refresh()
                } catch (e: Exception) {
                }
                val b = Nodes.bounds(n)
                lines.add(
                    "${" ".repeat(minOf(d, 12))}${(n.className ?: "?").toString().substringAfterLast('.')} " +
                        "${n.viewIdResourceName?.substringAfterLast('/') ?: ""} t=${Nodes.ownText(n)?.take(40) ?: ""} d=${Nodes.ownDesc(n)?.take(40) ?: ""} " +
                        "[${b.left},${b.top},${b.right},${b.bottom}] c=${n.childCount}${if (n.isEditable) " EDIT" else ""}",
                )
                for (i in 0 until n.childCount) {
                    val c = try {
                        n.getChild(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (c == null) {
                        lines.add("${" ".repeat(minOf(d + 1, 12))}(child $i = null)")
                        continue
                    }
                    go(c, d + 1)
                }
            }
            try {
                for (r in Nodes.packageRoots(service, pkg)) go(r, 0)
            } catch (e: Exception) {
                lines.add("chyba ${e.javaClass.simpleName}")
            }
            Log.i(RunAllReceiver.TAG, "tree $pkg: ${lines.size} riadkov")
            lines.chunked(12).forEach { Log.i(RunAllReceiver.TAG, "tree: " + it.joinToString(" ¦ ")) }
        }.start()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val b = android.os.Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        } catch (e: Exception) {
            false
        }
    }

    private fun back(wait: Long = 1_000) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        sleepStoppable(wait)
    }

    private fun goHome() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        sleepStoppable(1_500)
    }

    /** Uri videa v galérii: už stiahnuté, inak sa stiahne. */
    private fun phoneVideoUri(ep: Episode): Uri? =
        Downloads.savedUri(service, ep) ?: if (download(ep)) Downloads.savedUri(service, ep) else null

    /** Nahrá diel [item] z mobilu na platformy z [enabled], na ktoré ešte nie je (poradie YouTube, Instagram); jedna zlyhaná platforma nezastaví druhú. */
    private fun runPhonePosts(item: Item, enabled: Set<String>) {
        val ep = item.episode
        for (platform in PhonePosts.pending(service, ep, enabled)) {
            if (stopped) return
            val key = PhonePosts.key(ep, platform)
            Store.addPostTry(service, key)
            val uri = phoneVideoUri(ep)
            val r = if (uri == null) {
                pfail("video sa nepodarilo stiahnuť")
            } else {
                try {
                    if (platform == PhonePosts.YOUTUBE) postYoutube(ep, uri, false) else postInstagram(ep, uri, false)
                } catch (e: Exception) {
                    pfail("chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            }
            if (stopped && !r.ok) return
            Diag.appendPhonePost(service, ep, platform, r.ok, r.reason, false)
            if (r.ok) {
                Store.setPostDone(service, key)
                PhonePosts.addLocalSubmit(service, ep, platform)
            }
            overlay.show("$platform: ${ep.channel.name} – ${if (r.ok) "nahrané" else "zlyhalo: ${r.reason}"}", stopButton())
            goHome()
        }
    }

    /** Skúška nahratia (adb UPLOAD_TEST): najnovší diel sady, celý postup; pri [dry] sa na Upload/Share neklikne a koncept sa zahodí. */
    fun uploadTest(platform: String, set: String, dry: Boolean, url: String? = null, title: String? = null, caption: String? = null): Boolean = startThread {
        val ch = channels.firstOrNull { it.dropKey == set }
        val ep = try {
            if (ch != null && !url.isNullOrBlank()) {
                // Skúška bez dielu vo feede: video zo zadanej adresy.
                val file = Uri.parse(url).lastPathSegment ?: "test.mp4"
                val t = title?.takeIf { it.isNotBlank() } ?: "Test clip"
                Episode(ch, 0, file, t, caption?.takeIf { it.isNotBlank() } ?: t, url, null)
            } else {
                ch?.let { Repo.fetchEpisodes(it, 1).firstOrNull() }
            }
        } catch (e: Exception) {
            null
        }
        val uri = ep?.let { galleryUriByName(it.fileName) ?: phoneVideoUri(it) }
        val r = if (ep == null || uri == null) {
            pfail("diel sady $set sa nenašiel alebo nestiahol")
        } else {
            try {
                if (platform == PhonePosts.YOUTUBE) postYoutube(ep, uri, dry) else postInstagram(ep, uri, dry)
            } catch (e: Exception) {
                pfail("chyba: ${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
        }
        if (ep != null) Diag.appendPhonePost(service, ep, platform, r.ok, r.reason, dry)
        Log.i(RunAllReceiver.TAG, "UPLOAD_TEST: $platform $set dry=$dry ok=${r.ok} ${r.reason}")
        overlay.show("Nahratie (skúška${if (dry) ", dry" else ""}): $platform – ${if (r.ok) "OK" else "zlyhalo"} – ${r.reason}")
        overlay.hideAfter(8_000)
        if (!stopped) goHome()
    }

    /** Skúška: video s týmto názvom, ktoré už je v galérii (ušetrí sťahovanie); inak null. */
    private fun galleryUriByName(name: String): Uri? = try {
        val coll = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        service.contentResolver.query(
            coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null }
    } catch (e: Exception) {
        null
    }

    private fun launchApp(pkg: String): Boolean {
        val i = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            service.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun normText(n: AccessibilityNodeInfo): String = norm(n.text?.toString() ?: "")

    /** Posunie prvý rolovateľný uzol aplikácie o stranu dopredu. */
    private fun scrollForward(pkg: String) {
        val sc = appNodes(pkg).firstOrNull { it.isScrollable && onScreen(it) } ?: return
        try {
            sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } catch (e: Exception) {
        }
        sleepStoppable(800)
    }

    /** [pollApp] s rolovaním: kým sa [find] nenájde, najviac 3× posunie obrazovku. */
    private fun <T : Any> pollScrolling(pkg: String, find: (List<AccessibilityNodeInfo>) -> T?): T? {
        pollApp(pkg, 3_000L, find)?.let { return it }
        for (i in 1..3) {
            if (stopped) return null
            scrollForward(pkg)
            pollApp(pkg, 1_200L, find)?.let { return it }
        }
        return null
    }

    // ---- YouTube ----

    private fun ytTitle(title: String): String {
        var t = norm(title)
        if (!t.contains("#shorts", ignoreCase = true) && t.length + 8 <= 100) t += " #shorts"
        t = t.take(100)
        if (t.isNotEmpty() && t.last().isHighSurrogate()) t = t.dropLast(1)
        return t
    }

    /** Prepne kanál v appke YouTube; null = kanál [name] je aktívny, inak zlyhanie. */
    private fun ytSwitchChannel(name: String): SubmitResult? {
        if (!launchApp(YT)) return pfail("YouTube sa nespustil")
        val size = Nodes.screenSize(service)
        val you = pollApp(YT, 25_000L) { nodes ->
            val m = nodes.filter { n -> onScreen(n) && labels(n).any { it.trim().equals("You", ignoreCase = true) } }
            m.firstOrNull { Nodes.bounds(it).top > size.y * 0.6 } ?: m.firstOrNull()
        }
        if (you == null) {
            logScreen("yt", YT)
            return pfail("YouTube: karta You sa nenašla")
        }
        sleepStoppable(1_000)
        tap(you)
        val sw = pollApp(YT, 10_000L) { has(it, "Switch account") }
        if (sw == null) {
            logScreen("yt", YT)
            return pfail("YouTube: Switch account sa nenašlo")
        }
        tap(sw)
        val row = pollApp(YT, 20_000L) { nodes ->
            nodes.firstOrNull { n ->
                onScreen(n) && labels(n).any { l ->
                    l.startsWith("$name,", ignoreCase = true) || l.startsWith("Selected account: $name,", ignoreCase = true)
                }
            }
        }
        if (row == null) {
            logScreen("yt", YT)
            back()
            return pfail("YouTube: kanál $name nie je v zozname účtov")
        }
        val selected = labels(row).any { it.startsWith("Selected account:", ignoreCase = true) }
        plog("yt: kanál $name ${if (selected) "už je vybraný" else "prepínam"}")
        if (selected) {
            back(1_500)
        } else {
            tap(row)
            sleepStoppable(3_000)
        }
        return null
    }

    /** Zahodí rozpracované nahrávanie: Navigate up -> dialóg „Discard changes?“ -> Discard. */
    private fun ytDiscard() {
        for (i in 1..4) {
            if (stopped) return
            var d = exact(appNodes(YT), "Discard")
            if (d == null) {
                val up = exact(appNodes(YT), "Navigate up")
                if (up != null) tap(up) else back()
                sleepStoppable(1_200)
                d = pollApp(YT, 3_000L) { exact(it, "Discard") }
            }
            if (d != null) {
                tap(d)
                sleepStoppable(1_500)
                return
            }
            if (has(appNodes(YT), "Add details") == null) return
        }
    }

    private fun isUploadButton(n: AccessibilityNodeInfo): Boolean =
        onScreen(n) && (n.viewIdResourceName?.endsWith("upload_bottom_button") == true ||
            (cls(n) == CLS_BUTTON && labels(n).any { it.trim().equals("Upload", ignoreCase = true) }))

    private fun postYoutube(ep: Episode, uri: Uri, dry: Boolean): SubmitResult {
        val t = PhonePosts.target(ep) ?: return pfail("sada nemá cieľ")
        val name = t.ytName
        val title = ytTitle(ep.title)
        val caption = ep.text
        overlay.show("YouTube: ${ep.channel.name} – prepínam kanál…", stopButton())
        ytSwitchChannel(name)?.let { return it }
        if (stopped) return pfail("zastavené")

        overlay.show("YouTube: ${ep.channel.name} – otváram nahrávanie…", stopButton())
        val up = Intent("com.google.android.youtube.intent.action.UPLOAD")
            .setDataAndType(uri, "video/mp4")
            .setComponent(android.content.ComponentName(YT, "com.google.android.apps.youtube.app.application.Shell_UploadActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        up.clipData = android.content.ClipData.newRawUri("video", uri)
        try {
            service.startActivity(up)
        } catch (e: Exception) {
            return pfail("nahrávanie YouTube sa nespustilo: ${e.javaClass.simpleName}")
        }
        if (pollApp(YT, 30_000L) { has(it, "Add details") } == null) {
            logScreen("yt", YT)
            if (!stopped) ytDiscard()
            return pfail("obrazovka Add details sa neukázala")
        }
        sleepStoppable(1_500)
        logScreen("yt Add details", YT)

        fun bail(reason: String): SubmitResult {
            logScreen("yt", YT)
            ytDiscard()
            return pfail(reason)
        }

        // Kanál: viditeľný názov očakávaného kanála (pred zadaním textov, aby ho nenahradil text z titulku/popisu).
        if (pollApp(YT, 8_000L) { has(it, name) } == null) return bail("na Add details nie je kanál $name")

        // Title
        overlay.show("YouTube: ${ep.channel.name} – názov…", stopButton())
        val titleNode = pollApp(YT, 8_000L) { nodes ->
            val ed = nodes.filter { it.isEditable && onScreen(it) }
            ed.firstOrNull { it.hintText?.toString()?.startsWith("Create a title", ignoreCase = true) == true }
                ?: ed.firstOrNull { cls(it) == CLS_EDITTEXT }
        } ?: return bail("pole Title sa nenašlo")
        setNodeText(titleNode, title)
        sleepStoppable(700)
        val titlePrefix = norm(title).take(30)
        fun titleOk(nodes: List<AccessibilityNodeInfo>) = nodes.any { it.isEditable && normText(it).startsWith(titlePrefix) }
        if (!titleOk(appNodes(YT))) return bail("názov sa nezapísal")

        // Audience (povinné)
        var audience = has(appNodes(YT), "not Made for Kids") != null
        if (!audience) {
            val row = pollScrolling(YT) { has(it, "Select audience") }
                ?: return bail("riadok Audience sa nenašiel")
            tap(row)
            val no = pollApp(YT, 10_000L) { has(it, "not Made for Kids") } ?: return bail("voľba Not Made for Kids sa nenašla")
            tap(no)
            sleepStoppable(800)
            val upNode = pollApp(YT, 3_000L) { exact(it, "Navigate up") }
            if (upNode != null) tap(upNode) else back()
            if (pollApp(YT, 8_000L) { has(it, "Add details") } == null) return bail("po Audience sa Add details nevrátil")
            audience = true
        }

        // Description (nepovinné)
        val dRow = has(appNodes(YT), "Add description")
        if (dRow == null) {
            plog("yt: riadok Add description sa nenašiel, pokračujem bez popisu")
        } else {
            overlay.show("YouTube: ${ep.channel.name} – popis…", stopButton())
            tap(dRow)
            val ed = pollApp(YT, 8_000L) { nodes ->
                val c = nodes.filter { it.isEditable && onScreen(it) && !normText(it).startsWith(titlePrefix) }
                c.firstOrNull { it.hintText?.toString()?.contains("description", ignoreCase = true) == true } ?: c.firstOrNull()
            }
            if (ed == null) {
                plog("yt: pole popisu sa nenašlo, pokračujem bez popisu")
            } else {
                val ok = setNodeText(ed, caption)
                sleepStoppable(500)
                plog("yt: popis ${if (ok) "zapísaný" else "sa nezapísal"}")
                for (i in 1..3) {
                    val upNode = exact(appNodes(YT), "Navigate up")
                    if (upNode != null) tap(upNode) else back()
                    sleepStoppable(1_000)
                    if (appNodes(YT).none { it.isEditable && onScreen(it) && !normText(it).startsWith(titlePrefix) }) break
                }
            }
            if (pollApp(YT, 8_000L) { has(it, "Add details") } == null) return bail("po popise sa Add details nevrátil")
        }

        // Tvrdá kontrola pred odoslaním
        sleepStoppable(500)
        var fin = appNodes(YT)
        logScreen("yt pred odoslaním", YT)
        if (!titleOk(fin)) return bail("kontrola: názov nesedí")
        if (!audience || has(fin, "Select audience") != null) return bail("kontrola: Audience nie je nastavené")
        val badVis = fin.firstOrNull { n ->
            onScreen(n) && !n.isEditable && labels(n).any { l ->
                val x = l.trim().lowercase(Locale.ROOT)
                x == "private" || x == "unlisted" || (x.startsWith("visibility") && (x.contains("private") || x.contains("unlisted")))
            }
        }
        if (badVis != null) return bail("kontrola: viditeľnosť nie je Public (${labels(badVis).firstOrNull()})")
        plog("yt: Public ${if (has(fin, "Public") != null) "viditeľné" else "nepotvrdené (len nie je Private/Unlisted)"}")
        var upload = fin.firstOrNull { isUploadButton(it) }
        if (upload == null) {
            scrollForward(YT)
            fin = appNodes(YT)
            upload = fin.firstOrNull { isUploadButton(it) }
        }
        if (upload == null) return bail("kontrola: tlačidlo Upload sa nenašlo")
        if (!upload.isEnabled) return bail("kontrola: tlačidlo Upload je neaktívne")

        if (dry) {
            plog("yt: dry – kontroly prešli")
            ytDiscard()
            return SubmitResult(true, "dry – kontroly prešli (kanál $name, názov, audience, Public)")
        }

        overlay.show("YouTube: ${ep.channel.name} – nahrávam…", stopButton())
        tap(upload)
        val start = SystemClock.uptimeMillis()
        while (!stopped && SystemClock.uptimeMillis() - start < 30_000L) {
            sleepStoppable(1_000)
            val now = appNodes(YT)
            if (now.isNotEmpty() && has(now, "Add details") == null) {
                plog("yt: Add details zmizlo, počkám 20 s")
                sleepStoppable(20_000)
                return SubmitResult(true, "nahrané")
            }
        }
        return pfail(if (stopped) "zastavené po kliknutí na Upload (výsledok neznámy)" else "Add details nezmizlo do 30 s po Upload", clicked = true)
    }

    // ---- Instagram ----

    /** Zahodí rozpracovaný reel: Back -> „Save draft?“ -> Start Again; výber z galérie sa len zruší, nič sa nevyberá. */
    private fun igDiscard() {
        for (i in 1..5) {
            if (stopped) return
            val nodes = appNodes(IG)
            val sa = has(nodes, "Start Again")
            if (sa != null) {
                tap(sa)
                sleepStoppable(1_500)
                pollApp(IG, 3_000L) { byId(it, "gallery_cancel_button") }?.let {
                    tap(it)
                    sleepStoppable(1_000)
                }
                return
            }
            val gc = byId(nodes, "gallery_cancel_button")
            if (gc != null) {
                tap(gc)
                sleepStoppable(1_000)
                return
            }
            if (has(nodes, "New reel") == null && has(nodes, "Save draft") == null && byId(nodes, "clips_right_action_button") == null) return
            back(1_200)
        }
    }

    private fun postInstagram(ep: Episode, uri: Uri, dry: Boolean): SubmitResult {
        val t = PhonePosts.target(ep) ?: return pfail("sada nemá cieľ")
        val user = t.igUser
        val caption = ep.text
        overlay.show("Instagram: ${ep.channel.name} – kontrolujem účet…", stopButton())
        if (!launchApp(IG)) return pfail("Instagram sa nespustil")
        val tab = pollApp(IG, 30_000L) { byId(it, "profile_tab") }
        if (tab == null) {
            logScreen("ig", IG)
            return pfail("Instagram: profile_tab sa nenašiel (neprihlásený alebo iná obrazovka)")
        }
        sleepStoppable(1_500)
        val longOk = try {
            tab.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (e: Exception) {
            false
        }
        if (!longOk) pressNode(tab, 900)
        val row = pollApp(IG, 8_000L) { nodes ->
            nodes.firstOrNull { n -> onScreen(n) && labels(n).any { it.trim().removePrefix("@").equals(user, ignoreCase = true) } }
        }
        if (row == null) {
            logScreen("ig účty", IG)
            back()
            return pfail("Instagram účet $user nie je v mobile prihlásený")
        }
        tap(row)
        sleepStoppable(3_500)
        val size = Nodes.screenSize(service)
        pollApp(IG, 10_000L) { byId(it, "profile_tab") }?.let { tap(it) }
        val active = pollApp(IG, 10_000L) { nodes ->
            nodes.firstOrNull { n ->
                onScreen(n) && Nodes.bounds(n).top < size.y * 0.2 && labels(n).any { it.trim().removePrefix("@").equals(user, ignoreCase = true) }
            }
        }
        if (active == null) {
            logScreen("ig", IG)
            return pfail("Instagram: po prepnutí nie je aktívny účet $user")
        }

        overlay.show("Instagram: ${ep.channel.name} – otváram editor…", stopButton())
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setComponent(android.content.ComponentName(IG, "com.instagram.share.handleractivity.ReelShareHandlerActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("video", uri)
        try {
            service.startActivity(send)
        } catch (e: Exception) {
            return pfail("Instagram editor sa nespustil: ${e.javaClass.simpleName}")
        }

        fun bail(reason: String): SubmitResult {
            logScreen("ig", IG)
            igDiscard()
            return pfail(reason)
        }

        val next = pollApp(IG, 30_000L) { byId(it, "clips_right_action_button") } ?: return bail("editor reelu sa neukázal")
        sleepStoppable(1_000)
        tap(next)
        if (pollApp(IG, 20_000L) { has(it, "New reel") } == null) return bail("obrazovka New reel sa neukázala")
        sleepStoppable(1_500)
        logScreen("ig New reel", IG)

        // Popis: editovateľný uzol; inak ťuk na pole a nový pokus.
        overlay.show("Instagram: ${ep.channel.name} – popis…", stopButton())
        val capPrefix = norm(caption).take(25)
        fun capOk(nodes: List<AccessibilityNodeInfo>) = nodes.any { n -> n.text?.let { norm(it.toString()).startsWith(capPrefix) } == true }
        fun editable(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull { n -> onScreen(n) && (n.isEditable || cls(n) == CLS_EDITTEXT) }
        var written = false
        val ed0 = editable(appNodes(IG))
        if (ed0 != null) {
            written = setNodeText(ed0, caption)
            sleepStoppable(600)
        }
        if (!written || !capOk(appNodes(IG))) {
            val field = has(appNodes(IG), "Write a caption", editable = true)
            if (field != null) {
                tap(field)
                sleepStoppable(1_000)
            }
            val ed = pollApp(IG, 4_000L) { editable(it) }
            if (ed != null) {
                setNodeText(ed, caption)
                sleepStoppable(600)
            }
        }
        if (!capOk(appNodes(IG))) return bail("popis sa nepodarilo zapísať")

        // Tvrdá kontrola pred odoslaním
        var share = byId(appNodes(IG), "share_button")
        if (share == null) {
            back()
            share = pollApp(IG, 4_000L) { byId(it, "share_button") }
        }
        if (share == null) return bail("kontrola: tlačidlo Share sa nenašlo")
        if (!capOk(appNodes(IG))) return bail("kontrola: popis nesedí")
        if (!share.isEnabled) return bail("kontrola: tlačidlo Share je neaktívne")

        if (dry) {
            plog("ig: dry – kontroly prešli")
            igDiscard()
            return SubmitResult(true, "dry – kontroly prešli (účet $user, popis)")
        }

        overlay.show("Instagram: ${ep.channel.name} – zverejňujem…", stopButton())
        tap(share)
        val start = SystemClock.uptimeMillis()
        while (!stopped && SystemClock.uptimeMillis() - start < 30_000L) {
            sleepStoppable(1_000)
            val now = appNodes(IG)
            if (now.isNotEmpty() && has(now, "New reel") == null) {
                plog("ig: New reel zmizlo, počkám 20 s")
                sleepStoppable(20_000)
                return SubmitResult(true, "nahrané")
            }
        }
        return pfail(if (stopped) "zastavené po kliknutí na Share (výsledok neznámy)" else "New reel nezmizlo do 30 s po Share", clicked = true)
    }
}

