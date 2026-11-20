package com.example.bsstart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

class StartService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg2", MODE_PRIVATE) }

    private var panel: LinearLayout? = null
    private var mainBtn: View? = null
    private var calibLayer: View? = null

    private var running = false
    private var shotBusy = false
    private var nextSlot = 1
    private var lastPkg: String? = null

    private var inMatch = false
    private var lastGateMs = 0L
    private var combatCount = 0

    private val streak = IntArray(SLOTS + 1)
    private val lastTapMs = LongArray(SLOTS + 1)

    private companion object {
        const val GAME_PKG = "com.supercell.brawlstars"   // Ayarlar > Uygulamalar'dan doğrula
        const val SLOTS = 6
        const val LOBBY_SLOTS = 4            // 1-4: lobi / maç sonu düğmeleri
        const val ATTACK = 5                 // saldırı düğmesi + "maçtayım" kontrolü
        const val SUPER = 6                  // ulti düğmesi
        const val SCAN_PERIOD_MS = 1000L
        const val SLOT_COOLDOWN_MS = 3000L
        const val CONFIRM_SCANS = 2
        const val COLOR_TOLERANCE = 35       // kalibre edilmedi
        const val SAMPLE_RADIUS = 3
        const val COMBAT_PERIOD_MS = 350L    // kalibre edilmedi
        const val SUPER_EVERY = 6            // her 6. dokunuş ulti (kalibre edilmedi)
        const val GATE_FRESH_MS = 2500L      // maç kontrolü bundan eskiyse basma
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui") return
        lastPkg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        removeView(calibLayer); calibLayer = null
        removeView(panel); panel = null
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circle(color: Int, sizeDp: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
        }
    }

    private fun showPanel() {
        val handle = circle(Color.parseColor("#FF9800"), 26)   // sürükle; kısa dokunma = yuva atla
        val main = circle(Color.parseColor("#E53935"), 64)     // döngü aç/kapat
        val calib = circle(Color.parseColor("#1E88E5"), 26)    // kalibrasyon
        mainBtn = main

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(handle); addView(main); addView(calib)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(120)
        }
        panel = box

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < dp(6) && abs(e.rawY - downY) < dp(6)) {
                        nextSlot = nextSlot % SLOTS + 1
                        toast("Sıradaki yuva: $nextSlot ${slotName(nextSlot)}")
                    }
                }
            }
            true
        }

        main.setOnClickListener { toggleLoop() }
        calib.setOnClickListener { startCalibration(nextSlot) }
        calib.setOnLongClickListener { clearSlots(); true }

        wm.addView(box, lp)
    }

    private fun slotName(i: Int) = when (i) {
        1 -> "(lobi Oyna)"
        2, 3 -> "(maç sonu Devam/Tamam)"
        4 -> "(yedek)"
        ATTACK -> "(saldırı, maç içinde)"
        else -> "(ulti, maç içinde)"
    }

    // --- Yuvalar ---

    private fun slotReady(i: Int) =
        prefs.contains("c$i") && prefs.getFloat("x$i", -1f) >= 0f

    private fun clearSlots() {
        running = false; inMatch = false; updateMainColor()
        prefs.edit().clear().apply()
        nextSlot = 1
        toast("Tüm yuvalar silindi, sıradaki: yuva 1")
    }

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun tapSlot(i: Int) {
        val b = screenBounds()
        val x = prefs.getFloat("x$i", 0f) * b.width()
        val y = prefs.getFloat("y$i", 0f) * b.height()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        lastTapMs[i] = System.currentTimeMillis()
        dispatchGesture(g, null, null)
    }

    // --- Kalibrasyon ---

    private fun startCalibration(slot: Int) {
        if (calibLayer != null) return
        running = false; inMatch = false; updateMainColor()
        toast("Yuva $slot ${slotName(slot)}: düğmeye dokun (basmaz, sadece kaydeder)")
        val layer = View(this).apply { setBackgroundColor(Color.parseColor("#33000000")) }
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val b = screenBounds()
                val fx = (e.rawX / b.width()).coerceIn(0f, 1f)
                val fy = (e.rawY / b.height()).coerceIn(0f, 1f)
                removeView(layer); calibLayer = null
                ui.postDelayed({
                    withScreenshot { bmp ->
                        val c = bmp?.let { sampleColor(it, fx, fy) }
                        if (c != null) {
                            prefs.edit().putFloat("x$slot", fx).putFloat("y$slot", fy)
                                .putInt("c$slot", c).apply()
                            nextSlot = slot % SLOTS + 1
                            toast("Yuva $slot kaydedildi, sıradaki: $nextSlot ${slotName(nextSlot)}")
                        } else {
                            toast("Renk alınamadı, yuva $slot kaydedilmedi. Tekrar dene.")
                        }
                    }
                }, 300)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        calibLayer = layer
        wm.addView(layer, lp)
    }

    // --- Döngü ---

    private fun toggleLoop() {
        if (!running) {
            if (!slotReady(1)) {
                toast("Önce yuva 1'i (lobideki Oyna) kalibre et"); return
            }
            streak.fill(0)
            inMatch = false
            combatCount = 0
            running = true
            ui.post(scanTick)
            ui.post(combatTick)
            toast(if (slotReady(ATTACK)) "Döngü açık" else "Döngü açık (yuva 5 yok: maçta saldırı yapmaz)")
        } else {
            running = false
            inMatch = false
            toast("Döngü kapalı")
        }
        updateMainColor()
    }

    private fun updateMainColor() {
        val bg = mainBtn?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (running) "#43A047" else "#E53935"))
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!running) return
            val pkg = lastPkg
            val gameFront = pkg == null || pkg == GAME_PKG
            if (gameFront && calibLayer == null) {
                withScreenshot { bmp ->
                    if (running && bmp != null) evaluate(bmp)
                }
            } else {
                streak.fill(0)
                inMatch = false
            }
            ui.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    // Maç içi: saldırı düğmesi görünüyorsa sürekli saldırı, belirli aralıkla ulti
    private val combatTick = object : Runnable {
        override fun run() {
            if (!running) return
            val fresh = System.currentTimeMillis() - lastGateMs < GATE_FRESH_MS
            if (inMatch && fresh && slotReady(ATTACK)) {
                combatCount++
                if (slotReady(SUPER) && combatCount % SUPER_EVERY == 0) tapSlot(SUPER)
                else tapSlot(ATTACK)
            }
            ui.postDelayed(this, COMBAT_PERIOD_MS)
        }
    }

    private fun evaluate(bmp: Bitmap) {
        val now = System.currentTimeMillis()

        // Maç kontrolü: saldırı düğmesinin rengi görünüyor mu
        if (slotReady(ATTACK)) {
            val g = sampleColor(bmp, prefs.getFloat("x$ATTACK", 0f), prefs.getFloat("y$ATTACK", 0f))
            inMatch = g != null && close(g, prefs.getInt("c$ATTACK", 0))
            lastGateMs = now
        } else {
            inMatch = false
        }

        var tapped = false
        for (i in 1..LOBBY_SLOTS) {
            if (!slotReady(i)) { streak[i] = 0; continue }
            val c = sampleColor(bmp, prefs.getFloat("x$i", 0f), prefs.getFloat("y$i", 0f))
            if (c != null && close(c, prefs.getInt("c$i", 0))) streak[i]++ else streak[i] = 0
            if (!tapped && streak[i] >= CONFIRM_SCANS &&
                now - lastTapMs[i] >= SLOT_COOLDOWN_MS) {
                tapSlot(i)
                streak[i] = 0
                tapped = true
            }
        }
    }

    private fun close(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
        abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
        abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE

    private fun sampleColor(bmp: Bitmap, fx: Float, fy: Float): Int? {
        val cx = (fx * bmp.width).toInt()
        val cy = (fy * bmp.height).toInt()
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (yy in cy - SAMPLE_RADIUS..cy + SAMPLE_RADIUS)
            for (xx in cx - SAMPLE_RADIUS..cx + SAMPLE_RADIUS) {
                if (xx !in 0 until bmp.width || yy !in 0 until bmp.height) continue
                val p = bmp.getPixel(xx, yy)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
        return if (n > 0) Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()) else null
    }

    // Bitmap yalnızca callback içinde geçerli; çıkışta serbest bırakılır
    private fun withScreenshot(done: (Bitmap?) -> Unit) {
        if (shotBusy) { done(null); return }
        shotBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        result.hardwareBuffer.close()
                        shotBusy = false
                    }
                    try { done(bmp) } finally { bmp?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    shotBusy = false
                    done(null)
                }
            })
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
