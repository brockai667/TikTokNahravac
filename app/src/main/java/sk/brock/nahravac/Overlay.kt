package sk.brock.nahravac

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Tlačidlo na plávajúcej karte. */
class OverlayButton(val label: String, val action: () -> Unit)

/**
 * Malá plávajúca karta (okno typu TYPE_ACCESSIBILITY_OVERLAY, nepotrebuje žiadne povolenie)
 * so stavovým textom a najviac 3 tlačidlami. Stojí vľavo v strede, aby neprekrývala horný a dolný panel TikToku;
 * dá sa presunúť ťahaním za text. Všetky metódy sa dajú volať z ľubovoľného vlákna.
 */
class Overlay(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density

    private var card: LinearLayout? = null
    private var statusView: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var generation = 0
    private var warned = false

    /** Po zničení (vypnutie služby) sa karta už nikdy neobjaví, ani keď dobehne pracovné vlákno. */
    @Volatile
    private var destroyed = false

    fun destroy() {
        destroyed = true
        hide()
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

    private fun dp(v: Int): Int = (v * density).toInt()

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(4)
            y = 0
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
}
