package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Služba dostupnosti (predvolene VYPNUTÁ; zapína ju používateľ v systémových nastaveniach).
 *
 * Appka sa "naučí postup" - [Recorder] cez lapač dotykov zaznamená ťuknutia, ktoré používateľ ručne urobí pri jednom
 * nahraní v TikToku, a [Replayer] ich potom prehrá pre ďalšie videá (účet prepína sám). Oboje beží IBA keď to
 * používateľ spustí v appke.
 * Navyše ostala skrytá ladiaca funkcia: výpis stromu obrazovky TikToku do súboru (dlhé podržanie hlavičky v appke).
 */
class TikTokAccessibilityService : AccessibilityService() {

    companion object {
        /** Bežiaca inštancia (nastavená, kým je služba zapnutá a pripojená). */
        @Volatile
        var instance: TikTokAccessibilityService? = null

        val TIKTOK_PACKAGES: Set<String> = TikTokShare.PACKAGES.toSet()

        /** Ako dlho musí byť TikTok súvisle v popredí, kým sa strom uloží (čas na prepnutie na správnu obrazovku). */
        const val DUMP_DELAY_MS = 5_000L

        /** Po tomto čase bez TikToku v popredí sa požiadavka zruší. */
        const val DUMP_TIMEOUT_MS = 120_000L

        private const val POLL_MS = 1_000L
        private const val MAX_NODES = 5_000
        private const val MAX_DEPTH = 60
        private const val NOTIF_CHANNEL = "kalibracia"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tiktokSince = 0L
    private val pollRunnable = Runnable { tick() }

    private var overlay: Overlay? = null
    private var replayer: Replayer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val o = Overlay(this)
        overlay = o
        replayer = Replayer(this, o)
        if (Store.dumpRequestedAt(this) > 0L) startDumpWatch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Učenie už nepočúva udalosti kliknutí (TikTok ich často neposiela) - dotyky zachytáva lapač v [Overlay].
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        instance = null
        handler.removeCallbacks(pollRunnable)
        replayer?.stop()
        replayer = null
        overlay?.destroy()
        overlay = null
    }

    // ------------------------------------------------------------------
    // Učenie a automatické nahrávanie (volá appka)
    // ------------------------------------------------------------------

    /** Niečo práve beží (učenie alebo prehrávanie). */
    fun isBusy(): Boolean = replayer?.running == true

    /**
     * Spustí učenie postupu na epizóde [ep] s popisom [caption]: prepne účet, otvorí TikTok a nahráva dotyky.
     * False, ak je služba zaneprázdnená.
     */
    fun startRecording(ep: Episode, caption: String): Boolean {
        if (isBusy()) return false
        val r = replayer ?: return false
        return r.learn(ep, caption)
    }

    /** Stiahne a nahrá všetky položky po jednej; False, ak je služba zaneprázdnená. */
    fun runAll(items: List<Replayer.Item>, confirmBeforePost: Boolean): Boolean {
        if (isBusy()) return false
        val r = replayer ?: return false
        return r.runAll(items, confirmBeforePost)
    }

    // ------------------------------------------------------------------
    // Výpis stromu obrazovky (ladenie)
    // ------------------------------------------------------------------

    /** Zavolá appka po nastavení príznaku (alebo sa zavolá po pripojení služby). */
    fun startDumpWatch() {
        tiktokSince = 0L
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun tick() {
        val requestedAt = Store.dumpRequestedAt(this)
        if (requestedAt == 0L) {
            tiktokSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - requestedAt > DUMP_TIMEOUT_MS) {
            Store.clearDump(this)
            toast(getString(R.string.toast_dump_timeout))
            return
        }

        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root != null && pkg != null && pkg in TIKTOK_PACKAGES) {
            if (tiktokSince == 0L) tiktokSince = now
            if (now - tiktokSince >= DUMP_DELAY_MS) {
                Store.clearDump(this)
                tiktokSince = 0L
                try {
                    val file = dumpTree(root)
                    toast(getString(R.string.toast_dump_saved, file.absolutePath))
                    notifyDump(file)
                } catch (e: Exception) {
                    toast(getString(R.string.toast_dump_failed, e.message ?: e.javaClass.simpleName))
                }
                return
            }
        } else {
            tiktokSince = 0L
        }
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun dumpTree(root: AccessibilityNodeInfo): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(Dumps.dir(this), "tiktok-tree-$stamp.txt")
        val metrics = resources.displayMetrics
        val sb = StringBuilder()
        sb.append("# TikTok Nahravac - strom obrazovky\n")
        sb.append("# cas: $stamp\n")
        sb.append("# balik: ${root.packageName}\n")
        sb.append("# zariadenie: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("# obrazovka: ${metrics.widthPixels}x${metrics.heightPixels} px, hustota ${metrics.density}\n")
        sb.append("# format (odsadenie = hlbka): trieda | text | desc (contentDescription) | id (viewIdResourceName) | bounds | clickable\n")

        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val r = Rect()
            node.getBoundsInScreen(r)
            sb.append("  ".repeat(depth))
                .append("${node.className} | text=${quote(node.text)} | desc=${quote(node.contentDescription)}")
                .append(" | id=${node.viewIdResourceName} | bounds=${r.toShortString()} | clickable=${node.isClickable}\n")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                safeRecycle(child)
            }
        }
        walk(root, 0)
        if (count >= MAX_NODES) sb.append("# ... orezané na $MAX_NODES uzlov\n")

        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun quote(s: CharSequence?): String =
        if (s == null) "null" else "\"" + s.toString().replace("\n", "\\n") + "\""

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        // Od Androidu 13 je recycle() no-op; na starších uvoľní uzol.
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    private fun notifyDump(file: File) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.notif_dump_title))
                .setContentText(file.absolutePath)
                .setStyle(Notification.BigTextStyle().bigText(file.absolutePath))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notification)
        } catch (e: Exception) {
            // bez povolenia na notifikácie ostane aspoň toast
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }
}
