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
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var mainBtn: View? = null
    private var calibLayer: View? = null

    private var watching = false
    private var lastTapMs = 0L
    private var shotBusy = false

    private companion object {
        const val TAP_COOLDOWN_MS = 4000L
        const val SCAN_PERIOD_MS = 700L
        const val COLOR_TOLERANCE = 45   // kanal başına izin verilen fark
        const val SAMPLE_RADIUS = 3      // piksel, ortalama alınır
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        watching = false
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
        val handle = circle(Color.parseColor("#FF9800"), 26)   // sadece sürükleme
        val main = circle(Color.parseColor("#E53935"), 64)     // dokunma / uzun bas = tarama
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
        panel = box; panelLp = lp

        // Sürükleme sadece turuncu dairede
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
            }
            true
        }

        main.setOnClickListener { tapSaved() }
        main.setOnLongClickListener { toggleWatch(); true }
        calib.setOnClickListener { startCalibration() }

        wm.addView(box, lp)
    }

    // --- Ekran ölçüsü ve dokunma ---

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun hasCalibration() = prefs.getFloat("fx", -1f) >= 0f

    private fun tapSaved(): Boolean {
        if (!hasCalibration()) {
            toast("Önce mavi butonla kalibre et"); return false
        }
        val b = screenBounds()
        val x = prefs.getFloat("fx", 0f) * b.width()
        val y = prefs.getFloat("fy", 0f) * b.height()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        lastTapMs = System.currentTimeMillis()
        return dispatchGesture(g, null, null)
    }

    // --- Kalibrasyon ---

    private fun startCalibration() {
        if (calibLayer != null) return
        watching = false; updateMainColor()
        toast("Şimdi Oyna butonuna dokun")
        val layer = View(this).apply { setBackgroundColor(Color.parseColor("#33000000")) }
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val b = screenBounds()
                val fx = (e.rawX / b.width()).coerceIn(0f, 1f)
                val fy = (e.rawY / b.height()).coerceIn(0f, 1f)
                prefs.edit().putFloat("fx", fx).putFloat("fy", fy)
                    .remove("color").apply()
                removeView(layer); calibLayer = null
                sampleColorAtSavedPoint { c ->
                    if (c != null) {
                        prefs.edit().putInt("color", c).apply()
                        toast("Kaydedildi (nokta + renk)")
                    } else {
                        toast("Nokta kaydedildi, renk alınamadı: otomatik tarama kapalı")
                    }
                }
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

    // --- Otomatik tarama ---

    private fun toggleWatch() {
        if (!watching) {
            if (!hasCalibration() || !prefs.contains("color")) {
                toast("Tarama için önce kalibrasyon (nokta + renk) lazım"); return
            }
            watching = true
            ui.post(scanTick)
        } else {
            watching = false
        }
        updateMainColor()
    }

    private fun updateMainColor() {
        val bg = mainBtn?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (watching) "#43A047" else "#E53935"))
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!watching) return
            val cooling = System.currentTimeMillis() - lastTapMs < TAP_COOLDOWN_MS
            if (!cooling && !shotBusy) {
                sampleColorAtSavedPoint { c ->
                    val ref = prefs.getInt("color", 0)
                    if (watching && c != null && close(c, ref)) tapSaved()
                }
            }
            ui.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    private fun close(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
        abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
        abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE

    // Kayıtlı noktanın etrafının ortalama rengi; başarısızsa null
    private fun sampleColorAtSavedPoint(done: (Int?) -> Unit) {
        shotBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var color: Int? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (bmp != null) {
                            val cx = (prefs.getFloat("fx", 0f) * bmp.width).toInt()
                            val cy = (prefs.getFloat("fy", 0f) * bmp.height).toInt()
                            var r = 0L; var g = 0L; var bl = 0L; var n = 0
                            for (yy in cy - SAMPLE_RADIUS..cy + SAMPLE_RADIUS)
                                for (xx in cx - SAMPLE_RADIUS..cx + SAMPLE_RADIUS) {
                                    if (xx !in 0 until bmp.width || yy !in 0 until bmp.height) continue
                                    val p = bmp.getPixel(xx, yy)
                                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
                                }
                            if (n > 0) color = Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
                            bmp.recycle()
                        }
                    } catch (_: Exception) {
                    } finally {
                        result.hardwareBuffer.close()
                        shotBusy = false
                    }
                    done(color)
                }

                override fun onFailure(errorCode: Int) {
                    shotBusy = false
                    done(null)
                }
            })
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
