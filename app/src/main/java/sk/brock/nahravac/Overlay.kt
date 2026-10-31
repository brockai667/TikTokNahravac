package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max

/** Tlačidlo na plávajúcej karte. */
class OverlayButton(val label: String, val action: () -> Unit)

/** Dotyk zachytený pri učení: ťuknutie, podržanie alebo potiahnutie z [x1],[y1] do [x2],[y2] za [durMs]. */
class Caught(val kind: Kind, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durMs: Long) {
    enum class Kind { TAP, LONG, SWIPE }
}

/**
 * Malá plávajúca karta (okno typu TYPE_ACCESSIBILITY_OVERLAY, nepotrebuje žiadne povolenie)
 * so stavovým textom a najviac 3 tlačidlami. Stojí vľavo v strede, aby neprekrývala horný a dolný panel TikToku;
 * dá sa presunúť ťahaním za text.
 *
 * Pri učení navyše "lapač dotykov": priehľadné okno cez celú obrazovku (pod kartou), ktoré zachytí dotyk,
 * nahlási ho ([Caught]) a potom ho rovnakým gestom pošle ďalej do TikToku ([passThrough]).
 * Všetky metódy sa dajú volať z ľubovoľného vlákna (okná sa menia vždy na hlavnom vlákne).
 */
class Overlay(private val service: AccessibilityService) {

    companion object {
        /** Podržanie dlhšie než toto = LONG. */
        const val LONG_PRESS_MS = 500L

        /** Pauza medzi vypnutím dotykov lapača a odoslaním gesta (kým sa zmena okna prejaví). */
        private const val PASS_DELAY_MS = 60L

        /** Poistka: lapač sa znova zapne najneskôr po trvaní gesta + toto. */
        private const val PASS_SAFETY_MS = 1_500L

        /**
         * Ozvena: lapač chytil naše vlastné gesto (Samsung nestihol vypnúť jeho dotyky). Dotyk na rovnakom mieste
         * do konca gesta + toto sa nezapíše, len sa gesto pošle znova s dlhšou pauzou [ECHO_DELAY_MS].
         */
        private const val ECHO_GRACE_MS = 400L
        private const val ECHO_DELAY_MS = 200L

        /** Ozvenu jedného gesta pošleme znova najviac toľkokrát (žiadna slučka). */
        private const val ECHO_MAX = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density

    private var card: LinearLayout? = null
    private var statusView: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var generation = 0
    private var warned = false

    private var catcher: View? = null
    private var catcherParams: WindowManager.LayoutParams? = null
    private var passId = 0

    /** Posledné gesto poslané ďalej (na rozpoznanie ozveny); mení sa len na hlavnom vlákne. */
    private var lastGesture: GestureDescription? = null
    private var lastDurationMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var echoUntil = 0L
    private var echoCount = 0

    /** Prehrávanie práve posiela gesto: všetky naše okná sú nedotykové (mení sa len na hlavnom vlákne). */
    private var replayPass = false

    /** Po zničení (vypnutie služby) sa karta už nikdy neobjaví, ani keď dobehne pracovné vlákno. */
    @Volatile
    private var destroyed = false

    fun destroy() {
        destroyed = true
        hide()
        hideCatcher()
    }

    /** Ukáže kartu s textom a tlačidlami (nahradí predchádzajúci obsah a zruší naplánované skrytie). */
    fun show(text: String, vararg buttons: OverlayButton) {
        onMain {
            if (destroyed) return@onMain
            generation++
            ensureCard()
            statusView?.text = text
            val row = buttonRow
            if (row != null) {
                row.removeAllViews()
                for (b in buttons) row.addView(makeButton(b))
                row.visibility = if (buttons.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** Skryje kartu hneď. */
    fun hide() {
        onMain {
            generation++
            removeCard()
        }
    }

    /** Skryje kartu po [ms], ak sa medzitým nezobrazí nový obsah. */
    fun hideAfter(ms: Long) {
        onMain {
            val g = generation
            main.postDelayed({
                if (g == generation) removeCard()
            }, ms)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Vykoná [block] na hlavnom vlákne a počká naň (najviac 1 s). */
    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        try {
            done.await(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    // ------------------------------------------------------------------
    // Karta
    // ------------------------------------------------------------------

    private fun ensureCard() {
        if (card != null) return
        // Vlastná téma kontextu, aby vzhľad nezávisel od témy služby.
        val themed = android.view.ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
        val c = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xD9202020.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        val status = TextView(themed).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = service.resources.displayMetrics.widthPixels * 55 / 100
        }
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        c.addView(status)
        c.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Kým karta svieti (nahrávanie beží), obrazovka nezhasne - telefón môže ležať bokom.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(4)
            y = 0
            // Karta vzniknutá počas gesta prehrávania nesmie gesto zachytiť.
            if (replayPass) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        // Ťahanie za text presúva kartu (x od ľavého okraja, y od stredu obrazovky).
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        status.setOnTouchListener(View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    try {
                        wm.updateViewLayout(c, lp)
                    } catch (ex: Exception) {
                    }
                }
            }
            true
        })

        try {
            wm.addView(c, lp)
            card = c
            statusView = status
            buttonRow = row
            params = lp
        } catch (e: Exception) {
            card = null
            if (!warned) {
                warned = true
                Toast.makeText(
                    service.applicationContext,
                    "Plávajúcu kartu sa nepodarilo zobraziť: ${e.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun makeButton(b: OverlayButton): Button {
        val ctx: Context = buttonRow?.context ?: service
        return Button(ctx).apply {
            text = b.label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(0xFF505050.toInt())
                cornerRadius = dp(8).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34),
            )
            lp.rightMargin = dp(6)
            layoutParams = lp
            setOnClickListener {
                try {
                    b.action()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun removeCard() {
        val c = card
        if (c != null) {
            try {
                wm.removeView(c)
            } catch (e: Exception) {
            }
        }
        card = null
        statusView = null
        buttonRow = null
        params = null
    }

    /** Karta znova navrch (nové okno rovnakého typu je vždy nad staršími). Len na hlavnom vlákne. */
    private fun bringCardToFront() {
        val c = card ?: return
        val lp = params ?: return
        try {
            wm.removeViewImmediate(c)
            wm.addView(c, lp)
        } catch (e: Exception) {
            removeCard()
        }
    }

    /** Leží bod (súradnice obrazovky) na karte? Len na hlavnom vlákne. */
    fun cardContains(x: Float, y: Float): Boolean {
        val c = card ?: return false
        return contains(c, x, y)
    }

    private fun contains(v: View, x: Float, y: Float): Boolean {
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }

    // ------------------------------------------------------------------
    // Lapač dotykov (učenie)
    // ------------------------------------------------------------------

    /** Ukáže priehľadný lapač cez celú obrazovku; karta ostane nad ním. [listener] sa volá na hlavnom vlákne. */
    fun showCatcher(listener: (Caught) -> Unit) {
        onMain {
            if (destroyed) return@onMain
            removeCatcher()
            val v = makeCatcherView(listener)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                wm.addView(v, lp)
                catcher = v
                catcherParams = lp
            } catch (e: Exception) {
                catcher = null
                catcherParams = null
                Toast.makeText(
                    service.applicationContext,
                    "Lapač dotykov sa nepodarilo zobraziť: ${e.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            bringCardToFront()
        }
    }

    fun hideCatcher() {
        onMain { removeCatcher() }
    }

    private fun removeCatcher() {
        val v = catcher
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        catcher = null
        catcherParams = null
        passId++
        lastGesture = null
    }

    private fun makeCatcherView(listener: (Caught) -> Unit): View {
        val slop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var maxMove = 0f
        var tracking = false
        val v = View(service)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downAt = e.eventTime
                    maxMove = 0f
                    tracking = true
                }
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    maxMove = max(maxMove, hypot(e.rawX - downX, e.rawY - downY))
                }
                // Viac prstov naraz sa neprehráva.
                MotionEvent.ACTION_POINTER_DOWN -> tracking = false
                MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    if (isEcho(downX, downY, slop)) {
                        // naše vlastné gesto sa vrátilo do lapača: nezapisuje sa, len sa pošle znova
                        repassEcho()
                    } else {
                        val dt = e.eventTime - downAt
                        val moved = max(maxMove, hypot(e.rawX - downX, e.rawY - downY))
                        val kind = when {
                            moved >= slop -> Caught.Kind.SWIPE
                            dt >= LONG_PRESS_MS -> Caught.Kind.LONG
                            else -> Caught.Kind.TAP
                        }
                        try {
                            listener(Caught(kind, downX, downY, e.rawX, e.rawY, dt))
                        } catch (ex: Exception) {
                            // chyba v nahrávaní nesmie zhodiť službu
                        }
                    }
                }
                // Systémové gesto (napr. späť od okraja) si dotyk vzalo - nič sa neposiela.
                MotionEvent.ACTION_CANCEL -> tracking = false
            }
            true
        }
        return v
    }

    private fun setCatcherTouchable(touchable: Boolean) {
        val v = catcher ?: return
        val lp = catcherParams ?: return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Nedotykové okno s alfou 0 Android neráta ako zakrývajúce (TikTok dostane gesto bez príznaku "zakryté").
        lp.alpha = if (touchable) 1f else 0f
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    /**
     * Pošle zachytený dotyk ďalej do TikToku: lapač prestane prijímať dotyky, o ~60 ms sa odošle [gesture]
     * a po jeho skončení (alebo poistke) sa lapač znova zapne. [durationMs] = trvanie gesta, [x],[y] = jeho začiatok.
     */
    fun passThrough(gesture: GestureDescription, durationMs: Long, x: Float, y: Float) {
        onMain {
            if (destroyed) return@onMain
            lastGesture = gesture
            lastDurationMs = durationMs
            lastX = x
            lastY = y
            echoCount = 0
            dispatchPass(gesture, durationMs, PASS_DELAY_MS)
        }
    }

    /** Dotyk na mieste [x],[y] je ozvenou posledného gesta (prišiel počas neho alebo tesne po ňom). Hlavné vlákno. */
    private fun isEcho(x: Float, y: Float, slop: Float): Boolean =
        lastGesture != null && SystemClock.uptimeMillis() <= echoUntil && hypot(x - lastX, y - lastY) <= slop

    /** Ozvena: posledné gesto sa pošle znova s dlhšou pauzou (najviac [ECHO_MAX]-krát). Hlavné vlákno. */
    private fun repassEcho() {
        val g = lastGesture ?: return
        if (echoCount >= ECHO_MAX) return
        echoCount++
        dispatchPass(g, lastDurationMs, ECHO_DELAY_MS)
    }

    /** Hlavné vlákno: vypne dotyky lapača, po [delayMs] odošle gesto a po ňom lapač znova zapne. */
    private fun dispatchPass(gesture: GestureDescription, durationMs: Long, delayMs: Long) {
        val id = ++passId
        setCatcherTouchable(false)
        echoUntil = SystemClock.uptimeMillis() + delayMs + durationMs + ECHO_GRACE_MS
        main.postDelayed({
            if (destroyed) return@postDelayed
            val ok = try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            restoreCatcher(id)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            restoreCatcher(id)
                        }
                    },
                    null,
                )
            } catch (e: Exception) {
                false
            }
            if (!ok) restoreCatcher(id)
        }, delayMs)
        main.postDelayed({ restoreCatcher(id) }, delayMs + durationMs + PASS_SAFETY_MS)
    }

    private fun restoreCatcher(id: Int) {
        if (id == passId) setCatcherTouchable(true)
    }

    // ------------------------------------------------------------------
    // Prehrávanie: naše okná nesmú zachytiť vlastné gesto
    // ------------------------------------------------------------------

    /**
     * [on] = všetky naše okná prestanú prijímať dotyky (karta, ktorá leží na bode [x],[y], sa navyše skryje);
     * false = vráti ich späť. Čaká, kým sa zmena urobí na hlavnom vlákne.
     */
    fun setReplayPassThrough(on: Boolean, x: Float = -1f, y: Float = -1f) {
        runSync {
            replayPass = on
            val c = card
            val lp = params
            if (c != null && lp != null) {
                lp.flags = if (on) {
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                lp.alpha = if (on && contains(c, x, y)) 0f else 1f
                try {
                    wm.updateViewLayout(c, lp)
                } catch (e: Exception) {
                }
            }
            if (catcher != null) setCatcherTouchable(!on)
        }
    }
}
