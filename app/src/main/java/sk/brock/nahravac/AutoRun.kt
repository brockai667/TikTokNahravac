package sk.brock.nahravac

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Samočinné nahrávanie: spoločná logika výberu dielov pre [RunAllReceiver] aj päťminútový časovač služby dostupnosti. */
object AutoRun {
    private const val TAG = RunAllReceiver.TAG

    /** Najviac toľko samočinných spustení jedného dielu; potom ho samočinný režim vynechá (ručný beh ide ďalej). */
    const val MAX_TRIES = 3

    private val checking = AtomicBoolean(false)

    @Volatile
    private var lastState = ""

    /** Najnovší nehotový diel každého kanála; pri [auto] sa vynechajú diely, ktoré už mali [MAX_TRIES] samočinné pokusy. */
    fun collect(app: Context, auto: Boolean): List<Replayer.Item> {
        val items = ArrayList<Replayer.Item>()
        for (ch in Repo.loadChannels(app)) {
            if (!Repo.isSupported(ch)) continue
            try {
                val newest = Repo.fetchEpisodes(ch, 3).firstOrNull() ?: continue
                if (Store.isDone(app, newest.key)) continue
                if (auto && Store.autoTries(app, newest.key) >= MAX_TRIES) continue
                items.add(Replayer.Item(newest, newest.text))
            } catch (e: Exception) {
                Log.w(TAG, "kanal ${ch.name}: ${e.message}")
            }
        }
        return items
    }

    /** Jedna samočinná kontrola (časovač: [source] = "timer", shell: "shell"). Beží na vlastnom vlákne. */
    fun check(context: Context, source: String) {
        val app = context.applicationContext
        val verbose = source != "timer"
        fun note(state: String, warn: Boolean = false) {
            if (verbose || state != lastState) {
                if (warn) Log.w(TAG, "auto: $state") else Log.i(TAG, "auto: $state")
            }
            lastState = state
        }
        val reason = skipReason(app)
        if (reason != null) {
            note("preskocene – $reason")
            return
        }
        if (!checking.compareAndSet(false, true)) {
            note("preskocene – kontrola uz bezi")
            return
        }
        Thread {
            try {
                val items = collect(app, true)
                val svc = TikTokAccessibilityService.instance
                if (items.isEmpty()) {
                    val subs = dueSubmits(app)
                    if (subs.isEmpty()) {
                        note("ziadny novy diel")
                    } else if (svc?.runSubmits(subs) == true) {
                        Log.i(TAG, "auto: kampane (${subs.size})")
                        lastState = "kampane"
                    } else {
                        note("preskocene – sluzba je zaneprazdnena")
                    }
                } else if (svc?.runAll(items) == true) {
                    for (item in items) Store.addAutoTry(app, item.episode.key)
                    Log.i(TAG, "auto: spustene (${items.size})")
                    lastState = "spustene"
                } else {
                    note("preskocene – sluzba je zaneprazdnena")
                }
            } catch (e: Exception) {
                Log.w(TAG, "auto: zlyhalo: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                checking.set(false)
            }
        }.start()
    }

    /** Najviac toľko pokusov na odoslanie jedného Instagram/YouTube príspevku do kampane. */
    const val MAX_SUBMIT_TRIES = 6

    /** Nehotové príspevky s < [MAX_SUBMIT_TRIES] pokusmi, ktorých okno (due + 2 min až due + 28 min) práve beží, podľa due. Volať mimo hlavného vlákna. */
    fun dueSubmits(app: Context): List<Submit> {
        val now = java.time.Instant.now()
        return Repo.fetchSubmits(app).filter { s ->
            val due = s.dueAt ?: return@filter false
            !Store.isSubmitDone(app, s.id) && Store.submitTries(app, s.id) < MAX_SUBMIT_TRIES &&
                !now.isBefore(due.plusSeconds(2 * 60L)) && !now.isAfter(due.plusSeconds(28 * 60L))
        }.sortedBy { it.dueAt }
    }

    /** Okamžitá kontrola len príspevkov na odoslanie do kampane (adb SUBMITS_CHECK, na testovanie). */
    fun checkSubmits(context: Context) {
        val app = context.applicationContext
        val reason = skipReason(app)
        if (reason != null) {
            Log.i(TAG, "auto: kampane preskocene – $reason")
            return
        }
        Thread {
            try {
                val subs = dueSubmits(app)
                if (subs.isEmpty()) {
                    Log.i(TAG, "auto: kampane (0)")
                } else if (TikTokAccessibilityService.instance?.runSubmits(subs) == true) {
                    Log.i(TAG, "auto: kampane (${subs.size})")
                } else {
                    Log.w(TAG, "auto: kampane preskocene – sluzba je zaneprazdnena")
                }
            } catch (e: Exception) {
                Log.w(TAG, "auto: kampane zlyhalo: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    /** Dôvod, prečo sa kontrola preskočí, alebo null. */
    private fun skipReason(app: Context): String? {
        if (!Store.isAutoRun(app)) return "vypnute"
        val svc = TikTokAccessibilityService.instance ?: return "sluzba dostupnosti nie je zapnuta"
        if (svc.isBusy()) return "uz nieco bezi"
        if (TikTokShare.installedPackage(app) == null) return "TikTok nie je nainstalovany"
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return "obrazovka je vypnuta"
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return "telefon je zamknuty"
        return null
    }
}
