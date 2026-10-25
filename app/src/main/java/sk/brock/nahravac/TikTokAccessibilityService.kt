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
 * V1: iba kalibrácia. Keď appka nastaví príznak "dump" (tlačidlo "Nahrať strom obrazovky")
 * a TikTok je [DUMP_DELAY_MS] v popredí, zapíše strom uzlov aktívneho okna do súboru
 * (Android/data/sk.brock.nahravac/files/dumps/) a ukáže toast + notifikáciu s cestou.
 *
 * Nič sa nekliká. Nižšie sú pripravené TODO háčiky (pasteCaption, tapPost, switchAccount, tapNext),
 * ktoré NIE SÚ k ničomu pripojené - doladia sa po kalibrácii na reálnom telefóne.
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

        // TODO(kalibrácia): doplniť presné texty/contentDescription podľa výpisu z reálneho telefónu.
        val LABELS_POST = listOf("Uverejniť", "Zverejniť", "Post")
        val LABELS_NEXT = listOf("Ďalej", "Next")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tiktokSince = 0L
    private val pollRunnable = Runnable { tick() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Store.dumpRequestedAt(this) > 0L) startDumpWatch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // V1 nepotrebuje udalosti: aktívne okno sa zisťuje pollovaním v tick().
        // TODO(kalibrácia): tu neskôr reagovať na zmeny obrazovky TikToku pri automatickom nahrávaní.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(pollRunnable)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Výpis stromu obrazovky (kalibrácia)
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

    // ------------------------------------------------------------------
    // TODO háčiky pre budúce automatické nahrávanie - NIE SÚ ZAPOJENÉ
    // (nič ich nevolá; doladiť po kalibrácii podľa výpisu stromu z telefónu)
    // ------------------------------------------------------------------

    /** TODO: vložiť popis do poľa popisu (predpokladá, že text je v schránke). Zatiaľ berie prvé editovateľné pole. */
    fun pasteCaption(): Boolean {
        val root = rootInActiveWindow ?: return false
        // TODO(kalibrácia): nájsť pole popisu presne (viewId / hint), nie len "prvé editovateľné".
        val field = findFirst(root) { it.isEditable } ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** TODO: stlačiť "Uverejniť" / "Post". */
    fun tapPost(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByLabel(root, LABELS_POST) ?: return false
        return clickNode(node)
    }

    /** TODO: stlačiť "Ďalej" / "Next". */
    fun tapNext(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByLabel(root, LABELS_NEXT) ?: return false
        return clickNode(node)
    }

    /** TODO: prepnúť účet na [handle] (napr. "@plainsight.667"). Zatiaľ nič nerobí. */
    @Suppress("UNUSED_PARAMETER")
    fun switchAccount(handle: String): Boolean {
        // TODO(kalibrácia): profil -> rozbaľovací zoznam účtov -> uzol s textom == handle -> clickNode().
        // Postup a presné uzly sa zistia z výpisov stromu obrazovky.
        return false
    }

    // --- pomocné vyhľadávanie uzlov (text + contentDescription, SK aj EN) ---

    /**
     * Prvý uzol, ktorého text alebo contentDescription sa zhoduje s niektorým z [labels]
     * (bez ohľadu na veľkosť písmen). [exact] = true: celé porovnanie, false: "obsahuje".
     */
    fun findNodeByLabel(root: AccessibilityNodeInfo, labels: List<String>, exact: Boolean = true): AccessibilityNodeInfo? {
        val wanted = labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun matches(s: CharSequence?): Boolean {
            val v = s?.toString()?.trim()?.lowercase() ?: return false
            if (v.isEmpty()) return false
            return wanted.any { if (exact) v == it else v.contains(it) }
        }
        return findFirst(root) { matches(it.text) || matches(it.contentDescription) }
    }

    /** Prehľadá strom do hĺbky a vráti prvý uzol spĺňajúci [predicate]. */
    fun findFirst(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }

    /** Klikne na uzol; ak sám nie je klikateľný, vyšplhá po rodičoch k prvému klikateľnému. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }
}
