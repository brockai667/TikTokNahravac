package sk.brock.nahravac

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pomocné funkcie nad stromom uzlov služby dostupnosti: prechod, štítky, tvorba a hľadanie [Selector].
 * Uzly sa zámerne neuvoľňujú cez recycle() (od Androidu 13 je to no-op, na starších ich zbierka odpadu zruší sama).
 */
object Nodes {
    private const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 60

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

    /** Malé písmená, orezané medzery, bez úvodného "@". */
    fun normalize(s: String): String = s.lowercase().trim().removePrefix("@")

    // --- selektor ---

    /** Poradie [node] medzi uzlami jeho okna s rovnakým viewId a triedou. */
    private fun indexOf(node: AccessibilityNodeInfo): Int {
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

    fun selector(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Selector {
        val r = Rect()
        node.getBoundsInScreen(r)
        val viewId = node.viewIdResourceName?.takeIf { it.isNotEmpty() }
        val editable = node.isEditable
        // Text editovateľného poľa je to, čo používateľ napísal - do selektora nepatrí.
        var text = if (editable) null else ownText(node)
        val desc = ownDesc(node)
        // Uzol bez ID a bez vlastného textu sa inak nedá nájsť -> zoberieme štítok potomka.
        if (!editable && text == null && desc == null && viewId == null) text = label(node)
        return Selector(
            pkg = node.packageName?.toString() ?: "",
            viewId = viewId,
            text = text,
            desc = desc,
            cls = node.className?.toString(),
            index = indexOf(node),
            relX = if (screenW > 0) r.exactCenterX() / screenW else 0f,
            relY = if (screenH > 0) r.exactCenterY() / screenH else 0f,
        )
    }

    // --- hľadanie ---

    /**
     * Nájde uzol podľa selektora: (a) viewId a text/popis sa zhodujú, (b) text alebo popis sa zhoduje,
     * (c) iba viewId -> uzol na poradí [Selector.index] (alebo prvý). Inak null.
     */
    fun find(nodes: List<AccessibilityNodeInfo>, sel: Selector): AccessibilityNodeInfo? {
        val wantText = sel.text
        val wantDesc = sel.desc
        val hasLabel = wantText != null || wantDesc != null
        fun labelEquals(n: AccessibilityNodeInfo): Boolean =
            (wantText != null && ownText(n) == wantText) || (wantDesc != null && ownDesc(n) == wantDesc)

        if (hasLabel) {
            if (sel.viewId != null) {
                nodes.firstOrNull { it.isVisibleToUser && it.viewIdResourceName == sel.viewId && labelEquals(it) }
                    ?.let { return it }
            }
            nodes.firstOrNull { it.isVisibleToUser && labelEquals(it) }?.let { return it }
        }

        val id = sel.viewId ?: return null
        val sameId = nodes.filter { it.viewIdResourceName == id }
        if (sameId.isEmpty()) return null
        val sameClass = sameId.filter { it.className?.toString() == sel.cls }
        val pool = if (sameClass.isNotEmpty()) sameClass else sameId
        return pool.getOrNull(sel.index) ?: pool.first()
    }

    /** Uzol účtu: viditeľný uzol, ktorého vlastný text/popis obsahuje [accountKey]; potom klikateľný kontajner so štítkom. */
    fun findAccount(nodes: List<AccessibilityNodeInfo>, accountKey: String): AccessibilityNodeInfo? {
        if (accountKey.isEmpty()) return null
        nodes.firstOrNull { n ->
            n.isVisibleToUser && listOfNotNull(ownText(n), ownDesc(n)).any { normalize(it).contains(accountKey) }
        }?.let { return it }
        return nodes.firstOrNull { n ->
            n.isVisibleToUser && n.isClickable && (label(n)?.let { normalize(it).contains(accountKey) } ?: false)
        }
    }

    /** Viditeľný uzol, ktorého text alebo popis sa presne rovná niektorému zo [labels] (bez ohľadu na veľkosť písmen). */
    fun findByLabels(nodes: List<AccessibilityNodeInfo>, labels: List<String>): AccessibilityNodeInfo? =
        nodes.firstOrNull { n ->
            n.isVisibleToUser && listOfNotNull(ownText(n), ownDesc(n)).any { l -> labels.any { it.equals(l, ignoreCase = true) } }
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
}
