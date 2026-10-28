package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale

/**
 * Pomocné funkcie nad stromom uzlov služby dostupnosti: prechod, štítky, okná TikToku, podpis obrazovky a hľadanie.
 * Uzly sa zámerne neuvoľňujú cez recycle() (od Androidu 13 je to no-op, na starších ich zbierka odpadu zruší sama).
 */
object Nodes {
    private const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 60

    /** Najviac toľko textov má podpis obrazovky. */
    private const val SIG_MAX = 15

    /** Čísla, časy a dĺžky (0:59, 12, 1:02:03) - menia sa, do podpisu ani štítku nepatria. */
    private val NUMBERISH = Regex("^[\\d\\s.,:%+\\-]+$")

    /** Počty ako 1.2K, 3M. */
    private val COUNT = Regex("^\\d+([.,]\\d+)?\\s*[KkMmBb]$")

    /** Dĺžka videa kdekoľvek v texte (napr. „Video, 00:58“). */
    private val DURATION_INSIDE = Regex("\\d{1,2}:\\d{2}")

    /** Prechod do hĺbky (poradie ako v strome). [visit] vráti false = prechod sa zastaví. */
    fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        walkInner(root, 0, intArrayOf(0), visit)
    }

    private fun walkInner(
        node: AccessibilityNodeInfo,
        depth: Int,
        count: IntArray,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        if (count[0] >= MAX_NODES || depth > MAX_DEPTH) return true
        count[0]++
        if (!visit(node)) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!walkInner(child, depth + 1, count, visit)) return false
        }
        return true
    }

    /** Všetky uzly daných okien v poradí prechodu do hĺbky. */
    fun collect(roots: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) walk(root) { all.add(it); true }
        return all
    }

    /** Koreň okna, v ktorom je [node] (šplhá po rodičoch). */
    fun root(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        var guard = 0
        while (guard++ < 100) {
            val parent = current.parent ?: break
            current = parent
        }
        return current
    }

    // --- obrazovka a okná ---

    /** Skutočná veľkosť obrazovky v px (v týchto súradniciach sú hranice uzlov, dotyky aj gestá). */
    fun screenSize(ctx: Context): Point {
        val p = Point()
        try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        } catch (e: Exception) {
            // nižšie záloha z displayMetrics
        }
        if (p.x <= 0 || p.y <= 0) {
            val m = ctx.resources.displayMetrics
            p.set(m.widthPixels, m.heightPixels)
        }
        return p
    }

    fun isTikTok(node: AccessibilityNodeInfo?): Boolean =
        node?.packageName?.toString()?.let { it in TikTokShare.PACKAGES } == true

    private fun windowsOf(service: AccessibilityService): List<AccessibilityWindowInfo> = try {
        service.windows
    } catch (e: Exception) {
        emptyList()
    }

    /** Korene všetkých okien TikToku; ak okná nie sú k dispozícii, aktívne okno (ak je TikTok). */
    fun tiktokRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val wins = windowsOf(service)
        for (w in wins) {
            val root = try {
                w.root
            } catch (e: Exception) {
                null
            } ?: continue
            if (isTikTok(root)) out.add(root)
        }
        if (out.isEmpty() && wins.isEmpty()) {
            val active = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (isTikTok(active)) out.add(active!!)
        }
        return out
    }

    /** Je bod [x],[y] v okne klávesnice? */
    fun isInIme(service: AccessibilityService, x: Float, y: Float): Boolean {
        val r = Rect()
        return windowsOf(service).any { w ->
            w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && run {
                w.getBoundsInScreen(r)
                r.contains(x.toInt(), y.toInt())
            }
        }
    }

    /**
     * Koreň najvyššieho okna (okrem našich prekryvných okien) v bode [x],[y], ak je to okno TikToku; inak null
     * (klávesnica, systémová lišta, iná appka).
     */
    fun tiktokRootAt(service: AccessibilityService, x: Float, y: Float): AccessibilityNodeInfo? {
        val wins = windowsOf(service)
        if (wins.isEmpty()) {
            val active = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            return if (isTikTok(active)) active else null
        }
        val r = Rect()
        for (w in wins.sortedByDescending { it.layer }) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            w.getBoundsInScreen(r)
            if (!r.contains(x.toInt(), y.toInt())) continue
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
            val root = try {
                w.root
            } catch (e: Exception) {
                null
            }
            return if (isTikTok(root)) root else null
        }
        return null
    }

    /** Najhlbší viditeľný uzol, ktorého hranice obsahujú bod (pri rovnakej hĺbke neskorší = navrchu). */
    fun deepestAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        val count = intArrayOf(0)
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count[0] >= MAX_NODES || depth > MAX_DEPTH) return
            count[0]++
            n.getBoundsInScreen(r)
            val inside = r.contains(x, y)
            // Potomkovia ležia v hraniciach rodiča; uzol s prázdnymi hranicami (kontajner) sa prechádza ďalej.
            if (!inside && !r.isEmpty && depth > 0) return
            if (inside && n.isVisibleToUser && depth >= bestDepth) {
                best = n
                bestDepth = depth
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return best
    }

    /** [node], ak je klikateľný, inak najbližší klikateľný predok (najviac [maxUp] úrovní); inak null. */
    fun clickableAncestor(node: AccessibilityNodeInfo, maxUp: Int = 6): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var up = 0
        while (current != null && up <= maxUp) {
            if (current.isClickable) return current
            current = try {
                current.parent
            } catch (e: Exception) {
                null
            }
            up++
        }
        return null
    }

    fun bounds(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    // --- štítky ---

    fun ownText(node: AccessibilityNodeInfo): String? = node.text?.toString()?.trim()?.ifEmpty { null }

    fun ownDesc(node: AccessibilityNodeInfo): String? = node.contentDescription?.toString()?.trim()?.ifEmpty { null }

    /** Vlastný text, inak popis, inak prvý neprázdny text/popis potomkov (max. do hĺbky 3). */
    fun label(node: AccessibilityNodeInfo): String? =
        ownText(node) ?: ownDesc(node) ?: descendantLabel(node, 0)

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth >= 3) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            (ownText(child) ?: ownDesc(child))?.let { return it }
            descendantLabel(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Text, ktorý sa mení (číslo, čas, dĺžka videa, počet ako 1.2K) - nehodí sa na hľadanie ani do podpisu. */
    fun isVolatile(s: String): Boolean =
        NUMBERISH.matches(s) || COUNT.matches(s) || DURATION_INSIDE.containsMatchIn(s)

    /** Poradie [node] medzi uzlami jeho okna s rovnakým viewId a triedou. */
    fun indexOf(node: AccessibilityNodeInfo): Int {
        val id = node.viewIdResourceName
        val cls = node.className?.toString()
        var count = 0
        var found = -1
        walk(root(node)) { n ->
            if (n.viewIdResourceName == id && n.className?.toString() == cls) {
                if (n == node) {
                    found = count
                    false
                } else {
                    count++
                    true
                }
            } else {
                true
            }
        }
        return if (found >= 0) found else 0
    }

    // --- podpis obrazovky ---

    /**
     * Podpis obrazovky: najviac 15 rôznych viditeľných textov/popisov (dĺžka 2-40, bez čísel, časov, počtov,
     * mien našich účtov [accountKeys] a obsahu editovateľných polí), zoradených podľa polohy (zhora, zľava).
     */
    fun signature(nodes: List<AccessibilityNodeInfo>, accountKeys: List<String>): List<String> {
        class Item(val text: String, val top: Int, val left: Int)
        val items = ArrayList<Item>()
        val r = Rect()
        for (n in nodes) {
            if (!n.isVisibleToUser || n.isEditable) continue
            n.getBoundsInScreen(r)
            for (s in listOfNotNull(ownText(n), ownDesc(n))) {
                if (s.length !in 2..40 || isVolatile(s) || Accounts.matches(s, accountKeys)) continue
                items.add(Item(s, r.top, r.left))
            }
        }
        return items.sortedWith(compareBy<Item>({ it.top }, { it.left }))
            .map { it.text }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(SIG_MAX)
    }

    /** Všetky viditeľné vlastné texty/popisy (malými písmenami) na porovnanie s podpisom. */
    fun visibleTexts(nodes: List<AccessibilityNodeInfo>): Set<String> {
        val out = HashSet<String>()
        for (n in nodes) {
            if (!n.isVisibleToUser) continue
            ownText(n)?.let { out.add(it.lowercase(Locale.ROOT)) }
            ownDesc(n)?.let { out.add(it.lowercase(Locale.ROOT)) }
        }
        return out
    }

    /**
     * Koľko textov podpisu treba vidieť: max(1, ceil(40 % veľkosti podpisu)), najviac však 3 - na obrazovkách
     * s meniacim sa obsahom (domovský feed) stačia stále texty ako Home/Friends/Profile. Prázdny podpis = 0.
     */
    fun sigNeed(sig: List<String>): Int = if (sig.isEmpty()) 0 else minOf(3, maxOf(1, (sig.size * 2 + 4) / 5))

    /** Koľko textov podpisu [sig] je medzi [visible] (presne, bez ohľadu na veľkosť písmen). */
    fun sigOverlap(sig: List<String>, visible: Set<String>): Int = sig.count { it.lowercase(Locale.ROOT) in visible }

    // --- hľadanie ---

    /**
     * Viditeľný uzol, ktorého vlastný text alebo popis sa presne rovná štítku selektora. Pri viacerých zhodách
     * rozhodne rovnaké viewId a potom vzdialenosť od zaznamenaného miesta. Bez štítku null.
     */
    fun findExact(nodes: List<AccessibilityNodeInfo>, sel: Selector, screenW: Int, screenH: Int): AccessibilityNodeInfo? {
        val t = sel.text
        val d = sel.desc
        if (t == null && d == null) return null
        val r = Rect()
        val candidates = nodes.filter { n ->
            n.isVisibleToUser &&
                ((t != null && ownText(n) == t) || (d != null && ownDesc(n) == d)) &&
                run {
                    n.getBoundsInScreen(r)
                    !r.isEmpty
                }
        }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val byId = if (sel.viewId != null) candidates.filter { it.viewIdResourceName == sel.viewId } else emptyList()
        val pool = if (byId.isNotEmpty()) byId else candidates
        val tx = sel.relX * screenW
        val ty = sel.relY * screenH
        return pool.minByOrNull { n ->
            n.getBoundsInScreen(r)
            val dx = r.exactCenterX() - tx
            val dy = r.exactCenterY() - ty
            dx * dx + dy * dy
        }
    }

    /** Viditeľný uzol, ktorého text alebo popis sa presne rovná niektorému zo [labels] (bez ohľadu na veľkosť písmen). */
    fun findByLabels(nodes: List<AccessibilityNodeInfo>, labels: List<String>): AccessibilityNodeInfo? =
        nodes.firstOrNull { n ->
            n.isVisibleToUser && listOfNotNull(ownText(n), ownDesc(n)).any { l -> labels.any { it.equals(l, ignoreCase = true) } }
        }

    /** Viditeľný uzol s vlastným textom/popisom účtu s kľúčmi [keys] (pozri [Accounts.matches]). */
    fun findAccount(nodes: List<AccessibilityNodeInfo>, keys: List<String>): AccessibilityNodeInfo? {
        if (keys.isEmpty()) return null
        val r = Rect()
        return nodes.firstOrNull { n ->
            n.isVisibleToUser &&
                listOfNotNull(ownText(n), ownDesc(n)).any { Accounts.matches(it, keys) } &&
                run {
                    n.getBoundsInScreen(r)
                    !r.isEmpty
                }
        }
    }

    /** [node], ak je editovateľný; inak prvý editovateľný potomok; inak [node]. */
    fun editableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isEditable) return node
        var found: AccessibilityNodeInfo? = null
        walk(node) { n ->
            if (n.isEditable) {
                found = n
                false
            } else {
                true
            }
        }
        return found ?: node
    }

    // --- akcie ---

    /** Nastaví text poľa (ACTION_SET_TEXT); ak to nejde, vloží ho cez schránku (ACTION_PASTE). */
    fun fillText(ctx: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        val field = editableOf(node)
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
        copyToClipboard(ctx, text)
        return try {
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            false
        }
    }

    fun copyToClipboard(ctx: Context, text: String) {
        try {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("popis", text))
        } catch (e: Exception) {
            // bez schránky ostáva aspoň ACTION_SET_TEXT
        }
    }
}
