package com.example.bsstart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Uygulama (MainActivity) ile servis aynı süreçte; paneli buradan aç/gizler
object ServiceBridge {
    @Volatile var service: StartService? = null
}

class StartService : AccessibilityService() {

    private enum class Phase { LOOP, READING, SWITCH }
    private enum class Ic { MOVE, PLAY, STOP, PLUS, MINUS, UNDO, EYE_ON, EYE_OFF, CLIP, SCREEN, HELP, CROSS }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE) }
    private val logPrefs by lazy { getSharedPreferences("bslog", MODE_PRIVATE) }
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshMarkers() }

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelShown = true
    private var mainItem: Item? = null
    private var eyeItem: Item? = null
    private var calibLayer: View? = null
    private var markers: MarkerView? = null
    private var markersOn = true
    private var readLabel: String? = null
    private var labelToken = 0
    private var rectToken = 0
    private var rectPending = false

    private var undoSnap: HashMap<String, Any?>? = null
    private var undoNext = 1

    private var running = false
    private var shotBusy = false
    private var shotFails = 0
    private var nextSlot = 1
    private var lastPkg: String? = null
    private var pkgSince = 0L
    private var lastProgressMs = 0L

    // Maç kapısı
    private var inMatch = false
    private var hitStreak = 0
    private var missStreak = 0
    private var lastGateMs = 0L

    // Kupa kontrolü
    private var phase = Phase.LOOP
    private var needCheck = false
    private var afterSwitch = false
    private var stepStartMs = 0L
    private var switchArmed = false
    private var switchToken = 0
    private var readStartMs = 0L
    private var settleUntil = 0L
    private var ocrBusy = false
    private var readAttempts = 0
    private var failCycles = 0
    private val reads = ArrayList<Int>()
    private var session = 0
    private var recognizer: TextRecognizer? = null

    private val streak = IntArray(SlotStore.SLOTS + 1)
    private val lastTapMs = LongArray(SlotStore.SLOTS + 1)

    private companion object {
        const val GAME_PKG = "com.supercell.brawlstars"   // Ayarlar > Uygulamalar'dan doğrula
        const val SLOTS = SlotStore.SLOTS
        const val PLAY = 1
        const val ATTACK = 2
        const val BRAWLERS = 6
        const val CHARACTER = 7
        const val SELECT = 8
        val MENU_SLOTS = intArrayOf(1, 3, 4, 5)           // renkle tanınıp basılanlar
        val RING_COLORS = intArrayOf(
            0, Color.parseColor("#43A047"), Color.parseColor("#E53935"),
            Color.parseColor("#1E88E5"), Color.parseColor("#8E24AA"),
            Color.parseColor("#FB8C00"), Color.parseColor("#00ACC1"),
            Color.parseColor("#F06292"), Color.parseColor("#A1887F")
        )
        const val TROPHY_LIMIT = 950

        const val SCAN_PERIOD_MS = 1100L     // takeScreenshot sık çağrılınca reddeder
        const val COMBAT_PERIOD_MS = 500L    // saniyede 2
        const val SHOT_OFFSET_MS = 220L      // kalibre edilmedi
        const val GATE_FRESH_MS = 3000L
        const val ENTER_HITS = 2
        const val EXIT_MISSES = 2
        const val SLOT_COOLDOWN_MS = 3000L
        const val CONFIRM_SCANS = 2
        const val COLOR_TOLERANCE = 35       // kalibre edilmedi
        const val SAMPLE_RADIUS = 3
        const val LABEL_MS = 3000L
        const val FOREIGN_PKG_MS = 15000L
        const val STUCK_MS = 120000L
        const val LOG_LINES = 150

        const val OCR_TARGET_H = 128
        const val MAX_READ_ATTEMPTS = 12
        const val REQUIRED_VOTES = 3
        const val MAX_FAIL_CYCLES = 3
        const val READ_TIMEOUT_MS = 30000L
        const val STEP_TIMEOUT_MS = 12000L   // sadece Savaşçılar düğmesinin görünmesi için
        const val STEP_DELAY_MS = 5000L      // 6→7 ve 7→8 arası, renge bakmadan
        const val SETTLE_MS = 2500L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        showMarkers()
        showPanel()
        ServiceBridge.service = this
        log("Servis bağlandı")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui") return
        if (p != lastPkg) { lastPkg = p; pkgSince = System.currentTimeMillis() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (ServiceBridge.service === this) ServiceBridge.service = null
        running = false
        session++
        ui.removeCallbacksAndMessages(null)
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        removeView(calibLayer); calibLayer = null
        removeView(panel); panel = null
        removeView(markers); markers = null
        recognizer?.close(); recognizer = null
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- Kayıt defteri (son 150 satır, "bslog" içinde) ---

    private fun log(m: String) {
        try {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val old = logPrefs.getString("l", "") ?: ""
            val lines = (old.lines().filter { it.isNotEmpty() } + "$t $m").takeLast(LOG_LINES)
            logPrefs.edit().putString("l", lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    // --- Halkalar ve OCR alanı: dokunmayı geçirir ---

    private inner class MarkerView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(18).toFloat(); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }
        private val loc = IntArray(2)

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val b = screenBounds()
            val r = dp(24).toFloat()
            for (i in 1..SLOTS) {
                if (!slotReady(i)) continue
                val cx = prefs.getFloat("x$i", 0f) * b.width() - loc[0]
                val cy = prefs.getFloat("y$i", 0f) * b.height() - loc[1]
                ring.color = RING_COLORS[i]
                txt.color = RING_COLORS[i]
                c.drawCircle(cx, cy, r, ring)
                c.drawText(i.toString(), cx, cy - r - dp(6), txt)
            }
            if (SlotStore.rectReady(prefs)) {
                // Çerçeve alanın DIŞINA çizilir, okunan bölgeye girmez
                val pad = dp(5).toFloat()
                val l = prefs.getFloat("ox1", 0f) * b.width() - loc[0] - pad
                val t = prefs.getFloat("oy1", 0f) * b.height() - loc[1] - pad
                val rr = prefs.getFloat("ox2", 0f) * b.width() - loc[0] + pad
                val bb = prefs.getFloat("oy2", 0f) * b.height() - loc[1] + pad
                ring.color = Color.WHITE
                txt.color = Color.WHITE
                c.drawRect(l, t, rr, bb, ring)
                val lab = readLabel
                c.drawText(if (lab == null) "OKU" else "OKU ($lab)", (l + rr) / 2f, t - dp(6), txt)
            }
        }
    }

    private fun showMarkers() {
        val v = MarkerView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        markers = v
        wm.addView(v, lp)
    }

    private fun refreshMarkers() { ui.post { markers?.invalidate() } }

    private fun toggleMarkers() {
        markersOn = !markersOn
        markers?.visibility = if (markersOn) View.VISIBLE else View.INVISIBLE
        eyeItem?.iv?.icon = if (markersOn) Ic.EYE_ON else Ic.EYE_OFF
        toast(if (markersOn) "Halkalar gösteriliyor" else "Halkalar gizli")
    }

    // OKU çerçevesindeki yazıyı 3 sn "OKU (x)" yapar, sonra "OKU"ya döndürür
    private fun showLabel(t: String) {
        ui.post {
            readLabel = t
            val tok = ++labelToken
            markers?.invalidate()
            if (!markersOn) toast("OKU ($t)")
            ui.postDelayed({
                if (tok == labelToken) { readLabel = null; markers?.invalidate() }
            }, LABEL_MS)
        }
    }

    // --- Panel simgeleri ---

    private inner class IconView(ctx: Context, fillColor: Int, inkColor: Int) : View(ctx) {
        var icon: Ic = Ic.PLUS
            set(v) { field = v; invalidate() }
        var fill: Int = fillColor
            set(v) { field = v; invalidate() }
        var ink: Int = inkColor
            set(v) { field = v; invalidate() }

        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }

        // Ucu (x,y) + yön*s olan, tabanı (x,y)'de üçgen ok başı
        private fun head(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, s: Float) {
            val px = -dy; val py = dx
            val p = Path().apply {
                moveTo(x + dx * s, y + dy * s)
                lineTo(x + px * s * 0.7f, y + py * s * 0.7f)
                lineTo(x - px * s * 0.7f, y - py * s * 0.7f)
                close()
            }
            c.drawPath(p, solid)
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val rad = min(width, height) / 2f - dp(1)
            fillP.color = fill
            c.drawCircle(cx, cy, rad, fillP)
            edgeP.strokeWidth = dp(2).toFloat()
            c.drawCircle(cx, cy, rad, edgeP)
            val r = rad * 0.55f
            line.color = ink; line.strokeWidth = max(2f, rad * 0.13f)
            solid.color = ink
            when (icon) {
                Ic.MOVE -> {
                    val dirs = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f),
                        floatArrayOf(0f, 1f), floatArrayOf(0f, -1f))
                    for (d in dirs) {
                        val ex = cx + d[0] * r * 0.65f; val ey = cy + d[1] * r * 0.65f
                        c.drawLine(cx, cy, ex, ey, line)
                        head(c, ex, ey, d[0], d[1], r * 0.35f)
                    }
                }
                Ic.PLAY -> {
                    val p = Path().apply {
                        moveTo(cx - r * 0.5f, cy - r * 0.75f)
                        lineTo(cx - r * 0.5f, cy + r * 0.75f)
                        lineTo(cx + r * 0.8f, cy)
                        close()
                    }
                    c.drawPath(p, solid)
                }
                Ic.STOP -> c.drawRoundRect(
                    RectF(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f),
                    dp(2).toFloat(), dp(2).toFloat(), solid
                )
                Ic.PLUS -> {
                    c.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, line)
                    c.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, line)
                }
                Ic.MINUS -> c.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, line)
                Ic.UNDO -> {
                    val rr = r * 0.8f
                    c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 340f, -240f, false, line)
                    val a = Math.toRadians(100.0)
                    val ex = cx + rr * cos(a).toFloat(); val ey = cy + rr * sin(a).toFloat()
                    head(c, ex, ey, sin(a).toFloat(), -cos(a).toFloat(), r * 0.45f)
                }
                Ic.EYE_ON, Ic.EYE_OFF -> {
                    val p = Path().apply {
                        moveTo(cx - r, cy)
                        quadTo(cx, cy - r * 1.3f, cx + r, cy)
                        quadTo(cx, cy + r * 1.3f, cx - r, cy)
                        close()
                    }
                    c.drawPath(p, line)
                    c.drawCircle(cx, cy, r * 0.32f, solid)
                    if (icon == Ic.EYE_OFF)
                        c.drawLine(cx - r, cy + r * 0.9f, cx + r, cy - r * 0.9f, line)
                }
                Ic.CLIP -> {
                    c.drawRoundRect(
                        RectF(cx - r * 0.65f, cy - r * 0.75f, cx + r * 0.65f, cy + r * 0.9f),
                        dp(2).toFloat(), dp(2).toFloat(), line
                    )
                    c.drawRect(cx - r * 0.3f, cy - r * 0.95f, cx + r * 0.3f, cy - r * 0.55f, solid)
                    c.drawLine(cx - r * 0.35f, cy + r * 0.05f, cx + r * 0.35f, cy + r * 0.05f, line)
                    c.drawLine(cx - r * 0.35f, cy + r * 0.45f, cx + r * 0.35f, cy + r * 0.45f, line)
                }
                Ic.SCREEN -> {
                    c.drawRoundRect(
                        RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f),
                        dp(2).toFloat(), dp(2).toFloat(), line
                    )
                    textP.color = ink; textP.textSize = r * 1.0f
                    val fm = textP.fontMetrics
                    c.drawText("9", cx, cy - (fm.ascent + fm.descent) / 2f, textP)
                }
                Ic.HELP -> {
                    textP.color = ink; textP.textSize = r * 2f
                    val fm = textP.fontMetrics
                    c.drawText("?", cx, cy - (fm.ascent + fm.descent) / 2f, textP)
                }
                Ic.CROSS -> {
                    c.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, line)
                    c.drawLine(cx + r * 0.7f, cy - r * 0.7f, cx - r * 0.7f, cy + r * 0.7f, line)
                }
            }
        }
    }

    // Simge + altında kısa yazı
    private inner class Item(kind: Ic, fillColor: Int, inkColor: Int, sizeDp: Int, label: String) {
        val iv = IconView(this@StartService, fillColor, inkColor).apply {
            icon = kind
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val tv = TextView(this@StartService).apply {
            this.text = label
            textSize = 9f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val col = LinearLayout(this@StartService).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, 0)
            addView(iv); addView(tv)
        }
    }

    private fun showPanel() {
        val handle = Item(Ic.MOVE, Color.parseColor("#FF9800"), Color.WHITE, 28, "Taşı")
        val main = Item(Ic.PLAY, Color.parseColor("#E53935"), Color.WHITE, 44, "Başlat")
        val calib = Item(Ic.PLUS, Color.parseColor("#1E88E5"), Color.WHITE, 28, "Yuva kaydet")
        val del = Item(Ic.MINUS, Color.parseColor("#B71C1C"), Color.WHITE, 28, "Hepsini sil")
        val undo = Item(Ic.UNDO, Color.parseColor("#607D8B"), Color.WHITE, 28, "Geri al")
        val hide = Item(Ic.EYE_ON, Color.parseColor("#9E9E9E"), Color.WHITE, 28, "Halkalar")
        val copy = Item(Ic.CLIP, Color.parseColor("#8E24AA"), Color.WHITE, 28, "Kopyala")
        val area = Item(Ic.SCREEN, Color.parseColor("#00ACC1"), Color.WHITE, 28, "Alan seç")
        val live = Item(Ic.HELP, Color.parseColor("#FDD835"), Color.BLACK, 28, "Test oku")
        val off = Item(Ic.CROSS, Color.BLACK, Color.parseColor("#FF1744"), 28, "Gizle")
        mainItem = main
        eyeItem = hide

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0101820"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(6), dp(4), dp(6), dp(6))
            for (it in listOf(handle, main, calib, del, undo, hide, copy, area, live, off)) addView(it.col)
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
            x = dp(8); y = dp(24)
        }
        panel = box
        panelLp = lp

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.iv.setOnTouchListener { _, e ->
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
                        toast("Sıradaki yuva: $nextSlot (${SlotStore.NAMES[nextSlot]})")
                    }
                }
            }
            true
        }

        // Seçim (yuva / okuma alanı) sürerken panelde herhangi bir butona basmak seçimi iptal eder
        main.iv.setOnClickListener { if (!cancelPick()) toggleLoop() }
        calib.iv.setOnClickListener { if (!cancelPick()) startCalibration(nextSlot) }
        del.iv.setOnClickListener { if (!cancelPick()) clearSlots() }
        undo.iv.setOnClickListener { if (!cancelPick()) undo() }
        hide.iv.setOnClickListener { if (!cancelPick()) toggleMarkers() }
        copy.iv.setOnClickListener { if (!cancelPick()) copySlots() }
        area.iv.setOnClickListener { if (!cancelPick()) selectRect() }
        area.iv.setOnLongClickListener { if (!cancelPick()) testRead(); true }
        live.iv.setOnClickListener { if (!cancelPick()) liveRead() }
        off.iv.setOnClickListener { if (!cancelPick()) hidePanel() }

        wm.addView(box, lp)
    }

    // Paneli seçim katmanının üstüne çıkarır (aksi halde katman panele gelen dokunmayı yutar)
    private fun raisePanel() {
        val p = panel ?: return
        val l = panelLp ?: return
        try { wm.removeView(p) } catch (_: Exception) {}
        try { wm.addView(p, l) } catch (_: Exception) {}
    }

    private fun setKeepAwake(on: Boolean) {
        val p = panel ?: return
        val l = panelLp ?: return
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        l.flags = if (on) l.flags or f else l.flags and f.inv()
        try { wm.updateViewLayout(p, l) } catch (_: Exception) {}
    }

    private fun cancelPick(): Boolean {
        if (calibLayer == null && !rectPending) return false
        rectToken++
        rectPending = false
        removeView(calibLayer); calibLayer = null
        toast("Seçim iptal edildi")
        return true
    }

    // Erişilebilirliği KAPATMAZ: döngüyü durdurur, paneli ve halkaları gizler, dokunmayı keser
    fun setPanelVisible(v: Boolean) {
        panelShown = v
        if (!v) { cancelPick(); haltLoop() }
        panel?.visibility = if (v) View.VISIBLE else View.GONE
        markers?.visibility = if (v && markersOn) View.VISIBLE else View.INVISIBLE
        log(if (v) "Panel gösterildi" else "Panel gizlendi, döngü durduruldu")
    }

    fun isPanelVisible() = panelShown

    private fun hidePanel() {
        setPanelVisible(false)
        toast("Panel gizlendi. Geri açmak için uygulamadaki düğme.")
    }

    // --- Yuvalar ---

    private fun slotReady(i: Int) = SlotStore.ready(prefs, i)

    // Son değişiklik için tek seviyelik geri alma
    private fun saveUndo() {
        undoSnap = HashMap<String, Any?>(prefs.all)
        undoNext = nextSlot
    }

    private fun undo() {
        val s = undoSnap
        if (s == null) { toast("Geri alınacak bir şey yok"); return }
        haltLoop()
        val e = prefs.edit().clear()
        for ((k, v) in s) {
            when (v) {
                is Float -> e.putFloat(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Boolean -> e.putBoolean(k, v)
                is String -> e.putString(k, v)
                else -> {}
            }
        }
        e.commit()
        nextSlot = undoNext
        undoSnap = null
        refreshMarkers()
        toast("Son değişiklik geri alındı")
    }

    private fun clearSlots() {
        haltLoop()
        saveUndo()
        prefs.edit().clear().apply()
        nextSlot = 1
        refreshMarkers()
        toast("Her şey silindi. Geri al ile kurtarabilirsin. Sıradaki: 1 (${SlotStore.NAMES[1]})")
    }

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun copySlots() {
        val b = screenBounds()
        val text = SlotStore.format(prefs, "${b.width()}x${b.height()}")
        if (text == null) {
            val have = (1..SlotStore.CORE).count { slotReady(it) }
            toast("$have/${SlotStore.CORE} çekirdek yuva dolu, kopyalanmadı"); return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("bsstart", text))
        toast("Panoya kopyalandı")
    }

    private fun tapSlot(i: Int) {
        val b = screenBounds()
        val x = prefs.getFloat("x$i", 0f) * b.width()
        val y = prefs.getFloat("y$i", 0f) * b.height()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        val now = System.currentTimeMillis()
        lastTapMs[i] = now
        if (i != ATTACK) {
            lastProgressMs = now
            log("Basıldı: $i (${SlotStore.NAMES[i]})")
        }
        dispatchGesture(g, null, null)
    }

    // --- Nokta seçme, kalibrasyon, okuma alanı ---

    private fun pickPoint(prompt: String, onPoint: (Float, Float) -> Unit) {
        if (calibLayer != null) return
        haltLoop()
        toast(prompt)
        val layer = View(this).apply { setBackgroundColor(Color.parseColor("#33000000")) }
        layer.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val b = screenBounds()
                val fx = (e.rawX / b.width()).coerceIn(0f, 1f)
                val fy = (e.rawY / b.height()).coerceIn(0f, 1f)
                removeView(layer); calibLayer = null
                onPoint(fx, fy)
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
        raisePanel()
    }

    private fun startCalibration(slot: Int) {
        pickPoint("Yuva $slot (${SlotStore.NAMES[slot]}): düğmeye dokun (basmaz, sadece kaydeder). İptal: panelde bir butona bas") { fx, fy ->
            ui.postDelayed({
                withScreenshot { bmp ->
                    val c = bmp?.let { sampleColor(it, fx, fy) }
                    if (c != null) {
                        saveUndo()
                        prefs.edit().putFloat("x$slot", fx).putFloat("y$slot", fy)
                            .putInt("c$slot", c).apply()
                        nextSlot = slot % SLOTS + 1
                        refreshMarkers()
                        toast("Yuva $slot kaydedildi, sıradaki: $nextSlot (${SlotStore.NAMES[nextSlot]})")
                    } else {
                        toast("Renk alınamadı, yuva $slot kaydedilmedi. Tekrar dene.")
                    }
                }
            }, 300)
        }
    }

    private fun selectRect() {
        if (calibLayer != null) return
        val t = ++rectToken
        rectPending = true
        pickPoint("Kupa alanı: SOL ÜST köşeye dokun. İptal: panelde bir butona bas") { x1, y1 ->
            ui.postDelayed({
                if (t != rectToken) return@postDelayed
                pickPoint("Şimdi SAĞ ALT köşeye dokun. İptal: panelde bir butona bas") { x2, y2 ->
                    rectPending = false
                    val l = min(x1, x2); val r = max(x1, x2)
                    val tp = min(y1, y2); val bt = max(y1, y2)
                    if (r - l < 0.01f || bt - tp < 0.005f) {
                        toast("Alan çok küçük, tekrar dene")
                    } else {
                        saveUndo()
                        prefs.edit().putFloat("ox1", l).putFloat("oy1", tp)
                            .putFloat("ox2", r).putFloat("oy2", bt).apply()
                        refreshMarkers()
                        toast("Okuma alanı kaydedildi. Test oku ile dene.")
                    }
                }
            }, 300)
        }
    }

    // Test oku: alanı 3 farklı görüntü işlemeyle okur, çerçevede OKU (sayı) / OKU (Yok) gösterir
    private fun liveRead() {
        if (!SlotStore.rectReady(prefs)) { toast("Önce okuma alanını seç"); return }
        if (running) { toast("Önce döngüyü durdur"); return }
        withScreenshot { bmp ->
            if (bmp == null) { showLabel("Yok"); return@withScreenshot }
            val crops = (0..2).map { cropForOcr(bmp, it) }
            if (crops.any { it == null }) {
                crops.forEach { it?.recycle() }
                showLabel("Yok"); return@withScreenshot
            }
            val vals = arrayOfNulls<Int>(3)
            var left = 3
            for (idx in 0..2) {
                recognize(crops[idx]!!) { raw ->
                    vals[idx] = TrophyParser.parse(raw)
                    left--
                    if (left == 0) {
                        val found = vals.filterNotNull()
                        val best = found.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                        showLabel(best?.toString() ?: "Yok")
                    }
                }
            }
        }
    }

    // Alan seç butonuna uzun basış: 3 görüntü işlemenin ham sonuçlarını gösterir
    private fun testRead() {
        if (!SlotStore.rectReady(prefs)) { toast("Önce okuma alanını seç"); return }
        if (running) { toast("Önce döngüyü durdur"); return }
        withScreenshot { bmp ->
            if (bmp == null) { toast("Ekran alınamadı"); return@withScreenshot }
            for (v in 0..2) {
                val crop = cropForOcr(bmp, v)
                if (crop == null) { toast("Okuma alanı geçersiz"); return@withScreenshot }
                recognize(crop) { raw ->
                    toast("Test$v: '${clean(raw)}' → ${TrophyParser.parse(raw) ?: "okunamadı"}")
                }
            }
        }
    }

    private fun clean(raw: String?) = raw?.trim()?.replace("\n", " ") ?: ""

    // --- Döngü ---

    private fun resetState() {
        streak.fill(0)
        inMatch = false; hitStreak = 0; missStreak = 0
        phase = Phase.LOOP; needCheck = false; afterSwitch = false
        ocrBusy = false; switchArmed = false; failCycles = 0
        reads.clear()
    }

    private fun haltLoop() {
        running = false
        session++
        resetState()
        updateMainColor()
        setKeepAwake(false)
    }

    private fun stopAll(msg: String) {
        haltLoop()
        toast(msg)
    }

    private fun toggleLoop() {
        if (!running) {
            if (!slotReady(PLAY)) {
                toast("Önce yuva 1'i (Maça gir) kaydet"); return
            }
            session++
            resetState()
            needCheck = true            // ilk Oyna'dan önce kupayı oku
            running = true
            val now = System.currentTimeMillis()
            lastProgressMs = now
            pkgSince = now
            setKeepAwake(true)
            ui.post(scanTick)
            ui.post(combatTick)
            val missing = (1..SlotStore.CORE).filter { !slotReady(it) }
            val ocr = if (SlotStore.rectReady(prefs)) "kupa kontrolü AÇIK" else "kupa alanı yok, kontrol kapalı"
            toast(if (missing.isEmpty()) "Döngü açık, $ocr" else "Döngü açık, eksik yuvalar: $missing")
        } else {
            haltLoop()
            toast("Döngü kapalı")
        }
        updateMainColor()
    }

    private fun updateMainColor() {
        val m = mainItem ?: return
        m.iv.fill = Color.parseColor(if (running) "#43A047" else "#E53935")
        m.iv.icon = if (running) Ic.STOP else Ic.PLAY
        m.tv.text = if (running) "Durdur" else "Başlat"
    }

    // Maçtayken ekran görüntüsünü iki saldırı basışının ortasına denk getirir
    private val scanTick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            var pkg = lastPkg
            if (pkg != null && pkg != GAME_PKG && now - pkgSince > FOREIGN_PKG_MS) {
                log("Başka uygulama ($pkg) ${FOREIGN_PKG_MS / 1000} sn görünüyor: oyunda sayılıyor")
                lastPkg = null; pkg = null
            }
            if (now - lastProgressMs > STUCK_MS) {
                log("TAKILDI? faz=$phase maçta=$inMatch pkg=$lastPkg ekranHata=$shotFails kontrolGerek=$needCheck")
                lastProgressMs = now
            }
            val gameFront = pkg == null || pkg == GAME_PKG
            if (gameFront && calibLayer == null) {
                val s = session
                val wait = if (inMatch) {
                    val since = now - lastTapMs[ATTACK]
                    ((SHOT_OFFSET_MS - since) % COMBAT_PERIOD_MS + COMBAT_PERIOD_MS) % COMBAT_PERIOD_MS
                } else 0L
                ui.postDelayed({
                    if (!running || s != session) return@postDelayed
                    withScreenshot { bmp ->
                        if (running && s == session && bmp != null) evaluate(bmp)
                    }
                }, wait)
            } else {
                streak.fill(0)
                inMatch = false; hitStreak = 0; missStreak = 0
            }
            ui.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    // Maçtayken saldırı düğmesine saniyede 2 basış
    private val combatTick = object : Runnable {
        override fun run() {
            if (!running) return
            val fresh = System.currentTimeMillis() - lastGateMs < GATE_FRESH_MS
            if (inMatch && fresh && phase == Phase.LOOP && slotReady(ATTACK)) {
                tapSlot(ATTACK)
            }
            ui.postDelayed(this, COMBAT_PERIOD_MS)
        }
    }

    private fun hit(i: Int, bmp: Bitmap): Boolean {
        if (!slotReady(i)) return false
        val c = sampleColor(bmp, prefs.getFloat("x$i", 0f), prefs.getFloat("y$i", 0f)) ?: return false
        return close(c, prefs.getInt("c$i", 0))
    }

    private fun updateGate(bmp: Bitmap, now: Long) {
        if (!slotReady(ATTACK)) { inMatch = false; hitStreak = 0; missStreak = 0; return }
        lastGateMs = now
        if (hit(ATTACK, bmp)) { hitStreak++; missStreak = 0 } else { missStreak++; hitStreak = 0 }
        if (!inMatch && hitStreak >= ENTER_HITS) inMatch = true
        if (inMatch && missStreak >= EXIT_MISSES) inMatch = false
    }

    private fun evaluate(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.LOOP -> {
                // Menü düğmeleri maç sırasında da izlenir: biri 2 taramada görünürse maç bitmiştir
                for (i in MENU_SLOTS) streak[i] = if (hit(i, bmp)) streak[i] + 1 else 0
                val endSeen = MENU_SLOTS.any { streak[it] >= CONFIRM_SCANS }
                val was = inMatch
                if (endSeen) {
                    inMatch = false; hitStreak = 0; missStreak = 0; lastGateMs = now
                } else {
                    updateGate(bmp, now)
                }
                if (inMatch != was) {
                    lastProgressMs = now
                    log(if (inMatch) "Maç başladı" else "Maç bitti")
                    if (inMatch) needCheck = true   // maçtan sonraki ilk Oyna'dan önce kupa okunacak
                }
                if (inMatch) return
                loopStep(now)
            }
            // Okuma ve karakter değiştirme sırasında maç kapısı çalışmaz, faz sıfırlanmaz
            Phase.READING -> readStep(bmp, now)
            Phase.SWITCH -> switchStep(bmp, now)
        }
    }

    // Maç dışı: Maça gir, Devam, Devam, Çıkış
    private fun loopStep(now: Long) {
        for (i in MENU_SLOTS) {
            if (streak[i] < CONFIRM_SCANS || now - lastTapMs[i] < SLOT_COOLDOWN_MS) continue
            streak.fill(0)
            if (i == PLAY && needCheck && SlotStore.rectReady(prefs)) {
                startReading(now)       // Oyna'ya basmadan önce kupayı oku
                return
            }
            tapSlot(i)
            if (i != PLAY) needCheck = true   // 3, 4, 5'e basıldıysa sonraki Oyna'dan önce oku
            return                      // bir taramada en fazla bir dokunuş
        }
    }

    // --- Kupa okuma ---

    private fun startReading(at: Long) {
        phase = Phase.READING
        reads.clear()
        readAttempts = 0
        ocrBusy = false
        readStartMs = at
        settleUntil = at
        log("Kupa okuması başladı")
    }

    private fun readStep(bmp: Bitmap, now: Long) {
        if (now < settleUntil) return
        if (now - readStartMs > READ_TIMEOUT_MS) { readFailed("Okuma zaman aşımı"); return }
        if (ocrBusy) return
        if (!hit(PLAY, bmp)) return          // lobi görünmüyorsa okuma
        val crop = cropForOcr(bmp, readAttempts % 3)
        if (crop == null) { stopAll("Okuma alanı geçersiz: sistem durdu"); return }
        ocrBusy = true
        val s = session
        recognize(crop) { raw ->
            if (s == session) {
                ocrBusy = false
                onRead(raw)
            }
        }
    }

    private fun onRead(raw: String?) {
        if (!running || phase != Phase.READING) return
        val v = TrophyParser.parse(raw)
        readAttempts++
        toast("Okuma $readAttempts: '${clean(raw)}' → ${v ?: "?"}")
        if (v != null) reads.add(v)
        // Aynı değer REQUIRED_VOTES kez gelmeden karar verilmez
        val winner = reads.groupingBy { it }.eachCount()
            .filter { it.value >= REQUIRED_VOTES }.keys.maxOrNull()
        when {
            winner != null -> decide(winner)
            readAttempts >= MAX_READ_ATTEMPTS -> readFailed("Kupa okunamadı")
        }
    }

    // Okunamazsa Oyna'ya BASMAZ: yeniden dener, 3 turdan sonra durur
    private fun readFailed(msg: String) {
        failCycles++
        if (failCycles >= MAX_FAIL_CYCLES) {
            stopAll("$msg ($failCycles tur): sistem durdu"); return
        }
        toast("$msg: tekrar deneniyor ($failCycles/$MAX_FAIL_CYCLES)")
        streak.fill(0)
        startReading(System.currentTimeMillis() + 1000)
    }

    private fun decide(v: Int) {
        toast("Kupa: $v")
        streak.fill(0)
        failCycles = 0
        if (v < TROPHY_LIMIT) {
            phase = Phase.LOOP; needCheck = false; afterSwitch = false
            return
        }
        if (afterSwitch) {
            stopAll("Karakter değişti ama kupa hâlâ $v (≥$TROPHY_LIMIT): sistem durdu")
            return
        }
        if (!(BRAWLERS..SELECT).all { slotReady(it) }) {
            stopAll("Kupa $v (≥$TROPHY_LIMIT) ama yuva 6-8 eksik: sistem durdu")
            return
        }
        phase = Phase.SWITCH
        switchArmed = false
        stepStartMs = System.currentTimeMillis()
    }

    private fun switchLive(s: Int, tok: Int) =
        running && s == session && tok == switchToken && phase == Phase.SWITCH

    // 6 renk görününce basılır. 7 ve 8 renge BAKMADAN 5 sn arayla basılır.
    // Bu süre boyunca okuma yapılmaz; 8'e basılınca okuma başlar.
    private fun switchStep(bmp: Bitmap, now: Long) {
        if (switchArmed) return
        if (now - stepStartMs > STEP_TIMEOUT_MS) {
            stopAll("Savaşçılar düğmesi görünmedi: sistem durdu")
            return
        }
        streak[BRAWLERS] = if (hit(BRAWLERS, bmp)) streak[BRAWLERS] + 1 else 0
        if (streak[BRAWLERS] < CONFIRM_SCANS) return
        streak[BRAWLERS] = 0
        tapSlot(BRAWLERS)
        switchArmed = true
        val s = session
        val tok = ++switchToken
        ui.postDelayed({
            if (switchLive(s, tok)) tapSlot(CHARACTER)
        }, STEP_DELAY_MS)
        ui.postDelayed({
            if (switchLive(s, tok)) {
                tapSlot(SELECT)
                afterSwitch = true
                startReading(System.currentTimeMillis() + SETTLE_MS)
            }
        }, STEP_DELAY_MS * 2)
    }

    // variant 0: normal, 1: kontrastlı gri, 2: kontrastlı gri ters çevrilmiş
    private fun cropForOcr(bmp: Bitmap, variant: Int): Bitmap? {
        if (!SlotStore.rectReady(prefs)) return null
        val w0 = bmp.width; val h0 = bmp.height
        val x1 = (prefs.getFloat("ox1", 0f) * w0).toInt().coerceIn(0, w0 - 1)
        val y1 = (prefs.getFloat("oy1", 0f) * h0).toInt().coerceIn(0, h0 - 1)
        val x2 = (prefs.getFloat("ox2", 0f) * w0).toInt().coerceIn(x1 + 1, w0)
        val y2 = (prefs.getFloat("oy2", 0f) * h0).toInt().coerceIn(y1 + 1, h0)
        val w = x2 - x1; val h = y2 - y1
        if (w < 8 || h < 8) return null
        return try {
            val crop = Bitmap.createBitmap(bmp, x1, y1, w, h)
            val s = (OCR_TARGET_H.toFloat() / h).coerceIn(1f, 4f)
            val sized = if (s <= 1.01f) crop else {
                val scaled = Bitmap.createScaledBitmap(crop, (w * s).toInt(), (h * s).toInt(), true)
                if (scaled !== crop) crop.recycle()
                scaled
            }
            styleVariant(sized, variant)
        } catch (_: Exception) { null }
    }

    private fun styleVariant(src: Bitmap, variant: Int): Bitmap {
        if (variant == 0) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val k = 2.2f
        val off = -128f * (k - 1f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, off,
            0f, k, 0f, 0f, off,
            0f, 0f, k, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )))
        if (variant == 2) {
            cm.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    private fun recognize(bm: Bitmap, cb: (String?) -> Unit) {
        try {
            val r = recognizer ?: TextRecognition
                .getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            r.process(InputImage.fromBitmap(bm, 0))
                .addOnSuccessListener { res -> bm.recycle(); cb(res.text) }
                .addOnFailureListener { bm.recycle(); cb(null) }
        } catch (_: Exception) {
            bm.recycle(); cb(null)
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
                    shotFails = 0
                    try { done(bmp) } finally { bmp?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    shotBusy = false
                    shotFails++
                    if (shotFails == 3 || shotFails % 30 == 0)
                        log("Ekran görüntüsü hatası kod=$errorCode (üst üste $shotFails)")
                    done(null)
                }
            })
    }

    private fun toast(s: String) {
        log(s)
        ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
