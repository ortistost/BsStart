package com.example.bsstart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.ScrollView
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

object ServiceBridge {
    @Volatile var service: StartService? = null
}

class StartService : AccessibilityService() {

    private enum class Phase { LOOP, READING, SWITCH }
    private enum class Ic { MOVE, PLAY, STOP, PLUS, MINUS, UNDO, EYE_ON, EYE_OFF, SCREEN, HELP, CROSS, TROPHY, SAVE, SHRINK, GROW, IF, COLORS, AIF }

    // Okuma oyu: hangi değer, hangi ekran karesinden (en az MIN_FRAMES farklı kare gerekir)
    private class Vote(val value: Int, val frame: Int)

    // Bir ekran karesindeki görüntü varyantlarının OCR sonucu (varyant numarasına göre)
    private class FrameRead(val raws: Array<String?>, val vals: Array<Int?>) {
        var info: String = ""
        fun values(): List<Int> = vals.filterNotNull()
        fun total(): Int = raws.count { it != null }
    }

    private class Tally(val counts: Map<Int, Int>, val frames: Map<Int, Int>)

    private class FrameWaiter(val cb: (Bitmap?) -> Unit)

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE) }
    private val sets by lazy { getSharedPreferences(SlotStore.SET, MODE_PRIVATE) }
    private val stat by lazy { getSharedPreferences("bsstat", MODE_PRIVATE) }
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            aifLast.clear()
            refreshMarkers(); updateTotUi(); updateLabels(); refreshColors()
        }
    private val setListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            ui.post {
                when {
                    key == "scale" -> rebuildPanel()
                    key == "ttot" -> { ensureScan(); updateTotUi() }
                    key != null && key.startsWith("btn_") -> applyVisibility()
                }
            }
        }

    // Saldırı (yuva 2) basış hızı: saniyede kaç kez, ayarlardan gelir (0.3-10, varsayılan 3)
    private fun attackRate() = sets.getFloat("atkrate", SlotStore.DEFAULT_ATK_RATE)
        .coerceIn(SlotStore.MIN_ATK_RATE, SlotStore.MAX_ATK_RATE)
    private fun attackPeriodMs(): Long = max(1L, (1000f / attackRate()).toLong())

    private fun limit() = sets.getInt("limit", SlotStore.DEFAULT_LIMIT)
        .coerceIn(SlotStore.MIN_LIMIT, SlotStore.MAX_LIMIT)
    private fun scale() = sets.getFloat("scale", 1f).coerceIn(SlotStore.MIN_SCALE, SlotStore.MAX_SCALE)
    private fun aifThreshold() = sets.getInt("aifpct", SlotStore.DEFAULT_AIF)
        .coerceIn(SlotStore.MIN_AIF, SlotStore.MAX_AIF)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun d(v: Int) = max(1, (v * resources.displayMetrics.density * scale()).toInt())
    private fun recPrefs() = getSharedPreferences(SlotStore.REC, MODE_PRIVATE)

    // Otomatik kupa: uygulamadaki anahtar açık VE okuma alanı kayıtlı
    private fun trophyCheckOn() = SlotStore.autoTrophy(sets, prefs) && SlotStore.rectReady(prefs)

    // Anahtar döngü sırasında kapatılırsa süren okuma/karakter değiştirme bırakılır
    private fun abortTrophy() {
        phase = Phase.LOOP
        ocrBusy = false; switchArmed = false; afterSwitch = false
        switchToken++
        needCheck = false
        reads.clear()
    }

    // Son 40 okuma satırı, uygulamadaki "Son okumalar" bölümünde görünür
    private fun logRead(m: String) {
        try {
            val old = stat.getString("reads", "") ?: ""
            val lines = (old.split("\n").filter { it.isNotEmpty() } + "${stamp()} $m").takeLast(40)
            stat.edit().putString("reads", lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelShown = true
    private var minimized = false
    private var mainItem: Item? = null
    private var eyeItem: Item? = null
    private var totItem: Item? = null
    private var miniItem: Item? = null
    private var calibItem: Item? = null
    private var ifItem: Item? = null
    private var aifItem: Item? = null
    private var colorsItem: Item? = null
    private var colorsLayer: View? = null
    private var colorsLp: WindowManager.LayoutParams? = null
    private val itemMap = LinkedHashMap<String, Item>()
    private val iconViews = ArrayList<View>()
    private var calibLayer: View? = null
    private var markers: MarkerView? = null
    private var markersOn = true
    private var readLabel: String? = null
    private var labelToken = 0
    private var rectToken = 0
    private var rectPending = false
    private var liveBusy = false
    private var liveStartMs = 0L

    private var undoSnap: HashMap<String, Any?>? = null

    private var running = false
    private var scanRunning = false
    private var combatRunning = false
    private var nextTapAt = 0L
    private var shotBusy = false
    private var shotStartMs = 0L
    private val frameWaiters = ArrayList<FrameWaiter>()

    // if% (gelişmiş if): çözülmüş kayıt önbelleği ve son hesaplanan uyum
    private val aifCache = HashMap<Int, Pair<String, AifRef?>>()
    private val aifLast = HashMap<Int, VisionMath.AifScore>()

    // Maç kapısı
    private var inMatch = false
    private var hitStreak = 0
    private var missStreak = 0
    private var lastAttackSeenMs = 0L
    private var lastGateMs = 0L
    private val endStreak = IntArray(SlotStore.SLOTS + 1)

    // Toplam kupa takibi
    private var totBase: Int? = null
    private var totNow: Int? = null
    private val totWin = ArrayList<Int>()
    private var totBusy = false
    private var totVar = 0

    // Kupa kontrolü
    private var phase = Phase.LOOP
    private var needCheck = false
    private var afterSwitch = false
    private var trophyUpper: Int? = null    // son okunan kupa + maç başına en fazla 10
    private var trophyLow: Int? = null      // son okunan kupa - maç başına en fazla 5
    private var stepStartMs = 0L
    private var switchArmed = false
    private var switchToken = 0
    private var readStartMs = 0L
    private var settleUntil = 0L
    private var ocrBusy = false
    private var ocrInFlight = 0
    private var readAttempts = 0            // okunan ekran karesi sayısı
    private var failCycles = 0
    private var lastWaitLogMs = 0L
    private var lastOcrErrLogMs = 0L
    private val reads = ArrayList<Vote>()
    private var session = 0
    private var recognizer: TextRecognizer? = null

    private val streak = IntArray(SlotStore.SLOTS + 1)
    private val lastTapMs = LongArray(SlotStore.SLOTS + 1)

    private companion object {
        const val SLOTS = SlotStore.SLOTS
        const val IF_BASE = SlotStore.IF_BASE
        const val PLAY = 1
        const val ATTACK = 2
        const val ATTACK_IF = IF_BASE + ATTACK
        const val BRAWLERS = 6
        const val CHARACTER = 7
        const val SELECT = 8
        val MENU_SLOTS = intArrayOf(1, 3, 4, 5)
        val MIN_KEEP = setOf("move", "mini", "main", "off")
        val RING_COLORS = intArrayOf(
            0, Color.parseColor("#43A047"), Color.parseColor("#E53935"),
            Color.parseColor("#1E88E5"), Color.parseColor("#8E24AA"),
            Color.parseColor("#FB8C00"), Color.parseColor("#00ACC1"),
            Color.parseColor("#F06292"), Color.parseColor("#A1887F")
        )
        val IF_COLOR = Color.parseColor("#CDDC39")

        const val SCAN_PERIOD_MS = 350L
        const val TAP_MS = 30L
        const val GATE_FRESH_MS = 15000L
        const val DEAD_HOLD_MS = 60000L
        const val IF_HOLD_MS = 900L
        const val IF_EXIT_MISSES = 3
        const val ENTER_HITS = 2
        const val ABORT_ATTACK_SCANS = 3
        const val END_SCANS = 4
        const val END_TOL = 22
        const val SLOT_COOLDOWN_MS = 3000L
        const val CONFIRM_SCANS = 3
        const val COLOR_TOLERANCE = 35
        const val PLAY_TOLERANCE = 48       // yuva 1 (Maça gir) açık gri yazı rengi: telefonlar arası parlaklık farkı geniş
        const val HUE_TOL = 20f
        const val SAMPLE_RADIUS = 3
        const val LABEL_MS = 3000L

        const val OCR_TARGET_H = 128        // varyant ölçeği 1: kırpılan alan bu yüksekliğe büyütülür
        const val OCR_TARGET_H2 = 192       // varyant ölçeği 2
        const val STYLES = 8                // görüntü işleme biçimi sayısı (0-5 eski, 6-7 Otsu)
        const val VARIANTS = STYLES * 2     // 8 biçim x 2 ölçek
        const val MAX_READ_ATTEMPTS = 8     // bir okuma turunda en fazla bu kadar ekran karesi
        const val VARIANTS_PER_FRAME = 4    // döngüde her ekran karesinde okunan varyant sayısı
        const val FRAME_OCR_TIMEOUT_MS = 4000L  // bir karenin OCR'ı bunu aşarsa eldeki sonuçla devam edilir
        const val LIVE_TIMEOUT_MS = 12000L  // Test oku en geç bu sürede cevap verir
        const val MIN_FRAMES = 2            // kazanan değer en az bu kadar farklı karede görülmeli
        const val REQUIRED_VOTES = 3
        const val SWITCH_VOTES = 4          // karakter değiştirmek için gereken oy
        const val SUSPECT_SLACK = 10        // olası kupa aralığının dışında kabul edilen pay
        const val MAX_FAIL_CYCLES = 20
        const val READ_TIMEOUT_MS = 30000L
        const val STEP_TIMEOUT_MS = 12000L
        const val STEP_DELAY_MS = 5000L
        const val SETTLE_MS = 2500L
        const val FRAME_WAIT_MS = 1500L     // test düğmeleri tarama karesini en fazla bu kadar bekler
    }

    // Sıradaki boş yuva (1-8); hepsi doluysa null
    private fun nextSlotNum(): Int? = (1..8).firstOrNull { !slotReady(it) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        sets.registerOnSharedPreferenceChangeListener(setListener)
        showMarkers()
        showPanel(d(8), d(24))
        ServiceBridge.service = this
        resetTotal()
        ensureScan()
        note("Servis bağlandı")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val fix = Runnable {
            panel?.requestLayout()
            clampPanel()
            markers?.invalidate()
            if (colorsLayer != null) rebuildColors()
        }
        ui.postDelayed(fix, 400)
        ui.postDelayed(fix, 1200)
    }

    override fun onDestroy() {
        if (ServiceBridge.service === this) ServiceBridge.service = null
        running = false
        session++
        ui.removeCallbacksAndMessages(null)
        frameWaiters.clear()
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        try { sets.unregisterOnSharedPreferenceChangeListener(setListener) } catch (_: Exception) {}
        removeView(calibLayer); calibLayer = null
        removeView(panel); panel = null
        removeView(colorsLayer); colorsLayer = null
        removeView(markers); markers = null
        recognizer?.close(); recognizer = null
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun stamp() = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date())

    private fun note(m: String) {
        try { stat.edit().putString("ev", "${stamp()} $m").apply() } catch (_: Exception) {}
    }

    private fun noteStop(m: String) {
        try {
            val t = "${stamp()} $m"
            stat.edit().putString("stop", t).putString("ev", t).apply()
        } catch (_: Exception) {}
    }

    // --- Halkalar, okuma çerçeveleri ve toplam kupa yazısı: dokunmayı geçirir ---

    private inner class MarkerView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(18).toFloat(); textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }
        private val info = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val infoBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#99101820")
        }
        private val loc = IntArray(2)

        private fun frame(c: Canvas, b: Rect, p: String, color: Int, label: String) {
            val ok = if (p == "o") SlotStore.rectReady(prefs) else SlotStore.totReady(prefs)
            if (!ok) return
            val pad = dp(5).toFloat()
            val l = prefs.getFloat("${p}x1", 0f) * b.width() - loc[0] - pad
            val t = prefs.getFloat("${p}y1", 0f) * b.height() - loc[1] - pad
            val rr = prefs.getFloat("${p}x2", 0f) * b.width() - loc[0] + pad
            val bb = prefs.getFloat("${p}y2", 0f) * b.height() - loc[1] + pad
            ring.color = color
            txt.color = color
            c.drawRect(l, t, rr, bb, ring)
            c.drawText(label, (l + rr) / 2f, t - dp(6), txt)
        }

        // if% alanı: mor (henüz ölçülmedi), yeşil (eşik üstü), kırmızı (eşik altı) + son yüzde
        private fun aifFrame(c: Canvas, b: Rect, n: Int, ref: AifRef) {
            val sc = aifLast[n]
            val col = when {
                sc == null -> Color.parseColor("#B39DDB")
                sc.total * 100f >= aifThreshold().toFloat() -> Color.parseColor("#00E676")
                else -> Color.parseColor("#FF5252")
            }
            val pad = dp(5).toFloat()
            val l = ref.x1 * b.width() - loc[0] - pad
            val t = ref.y1 * b.height() - loc[1] - pad
            val rr = ref.x2 * b.width() - loc[0] + pad
            val bb = ref.y2 * b.height() - loc[1] + pad
            ring.color = col
            txt.color = col
            c.drawRect(l, t, rr, bb, ring)
            val label = if (sc == null) "if% $n" else "if% $n ${Math.round(sc.total * 100f)}%"
            c.drawText(label, (l + rr) / 2f, t - dp(6), txt)
        }

        private fun drawTotal(c: Canvas, b: Rect) {
            val pad = dp(5).toFloat()
            val top = prefs.getFloat("ty1", 0f) * b.height() - loc[1] - pad
            val bottom = prefs.getFloat("ty2", 0f) * b.height() - loc[1] + pad
            val cx = (prefs.getFloat("tx1", 0f) + prefs.getFloat("tx2", 0f)) / 2f * b.width() - loc[0]
            val base = totBase
            val now = totNow
            val dl = if (base == null || now == null) null else now - base
            val head = if (now == null) "…" else "$now "
            val par = if (dl == null) "" else if (dl >= 0) "(+$dl)" else "($dl)"
            val l2 = if (base == null || now == null) "… → …" else "$base → $now"
            val ts = dp(14) * scale().coerceIn(0.8f, 1.25f)
            info.textSize = ts
            val gap = ts * 1.3f
            var y1 = bottom + ts * 1.4f
            var y2 = y1 + gap
            if (y2 + ts * 0.6f > height) {
                y2 = top - ts * 1.8f
                y1 = y2 - gap
            }
            val wh = info.measureText(head)
            val w1 = wh + info.measureText(par)
            val w2 = info.measureText(l2)
            val w = max(w1, w2) + dp(16)
            c.drawRoundRect(RectF(cx - w / 2f, y1 - ts * 1.1f, cx + w / 2f, y2 + ts * 0.5f),
                dp(8).toFloat(), dp(8).toFloat(), infoBg)
            val x0 = cx - w1 / 2f
            info.color = Color.WHITE
            c.drawText(head, x0, y1, info)
            info.color = when {
                dl == null || dl == 0 -> Color.WHITE
                dl > 0 -> Color.parseColor("#00E676")
                else -> Color.parseColor("#FF5252")
            }
            c.drawText(par, x0 + wh, y1, info)
            info.color = Color.WHITE
            c.drawText(l2, cx - w2 / 2f, y2, info)
        }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val b = screenBounds()
            if (markersOn) {
                val r = dp(24).toFloat()
                for (i in 1..SLOTS) {
                    if (!slotReady(i)) continue
                    val cx = prefs.getFloat("x$i", 0f) * b.width() - loc[0]
                    val cy = prefs.getFloat("y$i", 0f) * b.height() - loc[1]
                    val col = if (i > IF_BASE) IF_COLOR else RING_COLORS[i]
                    ring.color = col
                    txt.color = col
                    c.drawCircle(cx, cy, r, ring)
                    c.drawText(if (i > IF_BASE) "if ${i - IF_BASE}" else i.toString(), cx, cy - r - dp(6), txt)
                }
                val lab = readLabel
                frame(c, b, "o", Color.WHITE, if (lab == null) "OKU" else "OKU ($lab)")
                frame(c, b, "t", Color.parseColor("#FFD600"), "TOPLAM")
                for (n in 1..8) {
                    val ref = aifRef(n) ?: continue
                    aifFrame(c, b, n, ref)
                }
            }
            if (totalActive()) drawTotal(c, b)
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
        eyeItem?.iv?.icon = if (markersOn) Ic.EYE_ON else Ic.EYE_OFF
        markers?.invalidate()
        toast(if (markersOn) "Halkalar gösteriliyor" else "Halkalar gizli")
    }

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

    // --- Toplam kupa ---

    private fun totalActive() =
        panelShown && sets.getBoolean("ttot", false) && SlotStore.totReady(prefs)

    private fun resetTotal() {
        totBase = null; totNow = null; totWin.clear()
        updateTotUi()
    }

    private fun updateTotUi() {
        ui.post {
            val on = totalActive()
            totItem?.iv?.fill = Color.parseColor(if (on) "#FFB300" else "#795548")
            markers?.invalidate()
        }
    }

    private fun readTotal(bmp: Bitmap) {
        if (totBusy) return
        val crop = cropRect(bmp, "t", totVar++ % VARIANTS) ?: return
        totBusy = true
        recognize(crop) { raw ->
            totBusy = false
            val v = TrophyParser.parseTotal(raw)
            if (v != null) {
                totWin.add(v)
                while (totWin.size > 6) totWin.removeAt(0)
                val counts = totWin.groupingBy { it }.eachCount()
                var best: Int? = null
                var bc = 0
                for (x in totWin) {
                    val c = counts[x] ?: 0
                    if (c >= bc) { best = x; bc = c }
                }
                val cur = totNow
                val need = if (best != null && cur != null && abs(best - cur) > 1000) 5 else 3
                if (best != null && bc >= need && best != cur) {
                    if (totBase == null) totBase = best
                    totNow = best
                    updateTotUi()
                }
            }
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

        private fun diagArrows(c: Canvas, cx: Float, cy: Float, r: Float, outward: Boolean) {
            val u = 0.7071f
            for (k in intArrayOf(1, -1)) {
                val dirx = (if (outward) k else -k) * u
                val diry = (if (outward) -k else k) * u
                val far = floatArrayOf(cx + k * r * 0.75f, cy - k * r * 0.75f)
                val near = floatArrayOf(cx + k * r * 0.2f, cy - k * r * 0.2f)
                if (outward) {
                    c.drawLine(near[0], near[1], far[0] - dirx * r * 0.2f, far[1] - diry * r * 0.2f, line)
                    head(c, far[0] - dirx * r * 0.2f, far[1] - diry * r * 0.2f, dirx, diry, r * 0.4f)
                } else {
                    c.drawLine(far[0], far[1], near[0] - dirx * r * 0.05f, near[1] - diry * r * 0.05f, line)
                    head(c, near[0] - dirx * r * 0.05f, near[1] - diry * r * 0.05f, dirx, diry, r * 0.4f)
                }
            }
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val rad = min(width, height) / 2f - dp(1)
            fillP.color = fill
            c.drawCircle(cx, cy, rad, fillP)
            edgeP.strokeWidth = max(1f, dp(2) * scale())
            c.drawCircle(cx, cy, rad, edgeP)
            val r = rad * 0.55f
            val corner = max(1f, dp(2) * scale())
            line.color = ink; line.strokeWidth = max(2f, rad * 0.13f)
            solid.color = ink
            when (icon) {
                Ic.MOVE -> {
                    val dirs = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f),
                        floatArrayOf(0f, 1f), floatArrayOf(0f, -1f))
                    for (dd in dirs) {
                        val ex = cx + dd[0] * r * 0.65f; val ey = cy + dd[1] * r * 0.65f
                        c.drawLine(cx, cy, ex, ey, line)
                        head(c, ex, ey, dd[0], dd[1], r * 0.35f)
                    }
                }
                Ic.SHRINK -> diagArrows(c, cx, cy, r, false)
                Ic.GROW -> diagArrows(c, cx, cy, r, true)
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
                    corner, corner, solid
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
                Ic.SCREEN -> {
                    c.drawRoundRect(
                        RectF(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f),
                        corner, corner, line
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
                Ic.IF -> {
                    textP.color = ink; textP.textSize = r * 1.7f
                    val fm = textP.fontMetrics
                    c.drawText("if", cx, cy - (fm.ascent + fm.descent) / 2f, textP)
                }
                Ic.AIF -> {
                    textP.color = ink; textP.textSize = r * 1.25f
                    val fm = textP.fontMetrics
                    c.drawText("if%", cx, cy - (fm.ascent + fm.descent) / 2f, textP)
                }
                Ic.COLORS -> {
                    val cols = intArrayOf(
                        Color.parseColor("#FF5252"), Color.parseColor("#FFEB3B"),
                        Color.parseColor("#40C4FF"), Color.parseColor("#69F0AE")
                    )
                    val sx = floatArrayOf(-1f, 1f, -1f, 1f)
                    val sy = floatArrayOf(-1f, -1f, 1f, 1f)
                    for (k in 0 until 4) {
                        solid.color = cols[k]
                        c.drawCircle(cx + sx[k] * r * 0.5f, cy + sy[k] * r * 0.5f, r * 0.34f, solid)
                    }
                }
                Ic.CROSS -> {
                    c.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, line)
                    c.drawLine(cx + r * 0.7f, cy - r * 0.7f, cx - r * 0.7f, cy + r * 0.7f, line)
                }
                Ic.TROPHY -> {
                    val p = Path().apply {
                        moveTo(cx - r * 0.7f, cy - r * 0.8f)
                        lineTo(cx + r * 0.7f, cy - r * 0.8f)
                        lineTo(cx + r * 0.45f, cy + r * 0.1f)
                        quadTo(cx, cy + r * 0.5f, cx - r * 0.45f, cy + r * 0.1f)
                        close()
                    }
                    c.drawPath(p, solid)
                    c.drawRect(cx - r * 0.1f, cy + r * 0.3f, cx + r * 0.1f, cy + r * 0.65f, solid)
                    c.drawRect(cx - r * 0.5f, cy + r * 0.65f, cx + r * 0.5f, cy + r * 0.85f, solid)
                }
                Ic.SAVE -> {
                    c.drawRoundRect(
                        RectF(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f),
                        corner, corner, line
                    )
                    c.drawRect(cx - r * 0.45f, cy - r * 0.8f, cx + r * 0.35f, cy - r * 0.2f, solid)
                    c.drawRect(cx - r * 0.5f, cy + r * 0.15f, cx + r * 0.5f, cy + r * 0.8f, solid)
                }
            }
        }
    }

    private inner class Item(kind: Ic, fillColor: Int, inkColor: Int, sizeDp: Int, label: String) {
        val iv = IconView(this@StartService, fillColor, inkColor).apply {
            icon = kind
            layoutParams = LinearLayout.LayoutParams(d(sizeDp), d(sizeDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val tv = TextView(this@StartService).apply {
            this.text = label
            textSize = 9f * scale()
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val col = LinearLayout(this@StartService).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d(3), 0, 0)
            addView(iv); addView(tv)
        }
    }

    // Düğme olmayan yerden (saydam arka plan, yazılar) sürüklenince paneli taşır
    private inner class CappedScroll(ctx: Context) : ScrollView(ctx) {
        private var dragging = false
        private var dx0 = 0f
        private var dy0 = 0f
        private var sx = 0
        private var sy = 0

        override fun onMeasure(w: Int, h: Int) {
            val cap = (screenBounds().height() * 0.92f).toInt()
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }

        private fun onIcon(rx: Float, ry: Float): Boolean {
            val loc = IntArray(2)
            for (v in iconViews) {
                if (!v.isShown) continue
                v.getLocationOnScreen(loc)
                if (rx >= loc[0] && rx <= loc[0] + v.width && ry >= loc[1] && ry <= loc[1] + v.height)
                    return true
            }
            return false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !onIcon(ev.rawX, ev.rawY)) {
                val lp = panelLp
                if (lp != null) {
                    dragging = true
                    dx0 = ev.rawX; dy0 = ev.rawY; sx = lp.x; sy = lp.y
                    return true
                }
            }
            return if (dragging) true else super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (dragging) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val lp = panelLp
                        if (lp != null) {
                            val b = screenBounds()
                            lp.x = (sx + (ev.rawX - dx0).toInt()).coerceIn(0, max(0, b.width() - width))
                            lp.y = (sy + (ev.rawY - dy0).toInt()).coerceIn(0, max(0, b.height() - height))
                            try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun showPanel(px: Int, py: Int) {
        val handle = Item(Ic.MOVE, Color.parseColor("#FF9800"), Color.WHITE, 28, "Taşı")
        val mini = Item(Ic.SHRINK, Color.parseColor("#3949AB"), Color.WHITE, 28, "Küçült")
        val main = Item(Ic.PLAY, Color.parseColor("#E53935"), Color.WHITE, 44, "Başlat")
        val calib = Item(Ic.PLUS, Color.parseColor("#1E88E5"), Color.WHITE, 28, "Yuva 1")
        val ifb = Item(Ic.IF, Color.parseColor("#7E57C2"), Color.WHITE, 28, "if (-)")
        val aifb = Item(Ic.AIF, Color.parseColor("#5E35B1"), Color.WHITE, 28, "if% (-)")
        val del = Item(Ic.MINUS, Color.parseColor("#B71C1C"), Color.WHITE, 28, "Hepsini sil")
        val undo = Item(Ic.UNDO, Color.parseColor("#607D8B"), Color.WHITE, 28, "Geri al")
        val hide = Item(Ic.EYE_ON, Color.parseColor("#9E9E9E"), Color.WHITE, 28, "Halkalar")
        val area = Item(Ic.SCREEN, Color.parseColor("#00ACC1"), Color.WHITE, 28, "Alan seç")
        val live = Item(Ic.HELP, Color.parseColor("#FDD835"), Color.BLACK, 28, "Test oku")
        val tot = Item(Ic.TROPHY, Color.parseColor("#795548"), Color.WHITE, 28, "Toplam")
        val save = Item(Ic.SAVE, Color.parseColor("#2E7D32"), Color.WHITE, 28, "Kaydet")
        val off = Item(Ic.CROSS, Color.BLACK, Color.parseColor("#FF1744"), 28, "Gizle")
        val colorsBtn = Item(Ic.COLORS, Color.parseColor("#37474F"), Color.WHITE, 28, "Renkler")
        mainItem = main
        eyeItem = hide
        totItem = tot
        miniItem = mini
        calibItem = calib
        ifItem = ifb
        aifItem = aifb
        colorsItem = colorsBtn
        hide.iv.icon = if (markersOn) Ic.EYE_ON else Ic.EYE_OFF
        itemMap.clear()
        itemMap["move"] = handle; itemMap["mini"] = mini; itemMap["main"] = main
        itemMap["calib"] = calib; itemMap["if"] = ifb; itemMap["aif"] = aifb; itemMap["del"] = del
        itemMap["undo"] = undo; itemMap["eye"] = hide; itemMap["area"] = area
        itemMap["live"] = live; itemMap["tot"] = tot; itemMap["save"] = save
        itemMap["colors"] = colorsBtn
        itemMap["off"] = off
        iconViews.clear()
        iconViews.addAll(itemMap.values.map { it.iv })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0101820"))
                cornerRadius = d(10).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            minimumWidth = d(36)
            minimumHeight = d(36)
            setPadding(d(6), d(4), d(6), d(6))
            for (item in itemMap.values) addView(item.col)
        }
        val root = CappedScroll(this).apply {
            addView(box)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = if (panelShown) View.VISIBLE else View.GONE
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
            x = px; y = py
            if (running) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.iv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val b = screenBounds()
                    lp.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, max(0, b.width() - root.width))
                    lp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, max(0, b.height() - root.height))
                    try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                }
            }
            true
        }

        mini.iv.setOnClickListener { if (!cancelPick()) toggleMinimize() }
        main.iv.setOnClickListener { if (!cancelPick()) toggleLoop() }
        calib.iv.setOnClickListener {
            if (!cancelPick()) {
                val n = nextSlotNum()
                if (n == null) toast("Tüm yuvalar dolu (1-8)") else startCalibration(n)
            }
        }
        ifb.iv.setOnClickListener { if (!cancelPick()) startIf() }
        aifb.iv.setOnClickListener { if (!cancelPick()) startAif() }
        aifb.iv.setOnLongClickListener { if (!cancelPick()) testAif(); true }
        del.iv.setOnClickListener { if (!cancelPick()) clearSlots() }
        undo.iv.setOnClickListener { if (!cancelPick()) undo() }
        hide.iv.setOnClickListener { if (!cancelPick()) toggleMarkers() }
        area.iv.setOnClickListener { if (!cancelPick()) selectRect("o") }
        area.iv.setOnLongClickListener { if (!cancelPick()) testRead(); true }
        live.iv.setOnClickListener { if (!cancelPick()) liveRead() }
        tot.iv.setOnClickListener { if (!cancelPick()) onTotalTap() }
        tot.iv.setOnLongClickListener { if (!cancelPick()) selectRect("t"); true }
        save.iv.setOnClickListener { if (!cancelPick()) saveRecord() }
        off.iv.setOnClickListener { if (!cancelPick()) hidePanel() }
        colorsBtn.iv.setOnClickListener { if (!cancelPick()) toggleColors() }

        wm.addView(root, lp)
        panel = root
        panelLp = lp
        updateColorsBtn()
        updateMainColor()
        applyVisibility()
        updateLabels()
        updateTotUi()
        root.post { clampPanel() }
    }

    // --- Renk listesi penceresi ---

    private fun updateColorsBtn() {
        colorsItem?.iv?.fill = Color.parseColor(if (colorsLayer != null) "#00ACC1" else "#37474F")
    }

    private fun toggleColors() {
        if (colorsLayer != null) { closeColors(); return }
        if (running) {
            toast("Döngü açıkken renk listesi açılmaz. Önce durdur.")
            return
        }
        openColors()
    }

    private fun closeColors() {
        removeView(colorsLayer)
        colorsLayer = null
        colorsLp = null
        updateColorsBtn()
    }

    private fun refreshColors() {
        ui.post { if (colorsLayer != null) rebuildColors() }
    }

    private fun rebuildColors() {
        removeView(colorsLayer)
        colorsLayer = null
        colorsLp = null
        openColors()
    }

    private fun openColors() {
        val v = buildColorsView()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        placeColors(v, lp)
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            toast("Renk listesi açılamadı")
            updateColorsBtn()
            return
        }
        colorsLayer = v
        colorsLp = lp
        updateColorsBtn()
    }

    // Panelin sağına koyar, sığmazsa soluna
    private fun placeColors(v: View, lp: WindowManager.LayoutParams) {
        val b = screenBounds()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(b.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(b.height(), View.MeasureSpec.AT_MOST)
        )
        val w = v.measuredWidth
        val h = v.measuredHeight
        val px = panelLp?.x ?: 0
        val py = panelLp?.y ?: 0
        val pw = panel?.width ?: 0
        var x = px + pw + d(6)
        if (x + w > b.width()) x = px - w - d(6)
        lp.x = x.coerceIn(0, max(0, b.width() - w))
        lp.y = py.coerceIn(0, max(0, b.height() - h))
    }

    private fun buildColorsView(): View {
        val sc = scale()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101820"))
                cornerRadius = d(10).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(d(10), d(8), d(10), d(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "Seçilen renkler"
            textSize = 13f * sc
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = d(16) }
        })
        head.addView(TextView(this).apply {
            text = "Kapat"
            textSize = 12f * sc
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B71C1C"))
                cornerRadius = d(8).toFloat()
            }
            setPadding(d(10), d(4), d(10), d(4))
            setOnClickListener { closeColors() }
        })
        box.addView(head)

        for (i in 1..8) {
            val ok = slotReady(i)
            val c = if (ok) prefs.getInt("c$i", 0) or (0xFF shl 24) else Color.TRANSPARENT
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d(5), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = i.toString()
                textSize = 13f * sc
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(RING_COLORS[i])
                layoutParams = LinearLayout.LayoutParams(d(16), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = SlotStore.NAMES[i]
                textSize = 12f * sc
                setTextColor(Color.WHITE)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(d(84), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(c)
                    cornerRadius = d(5).toFloat()
                    setStroke(dp(1), if (ok) Color.WHITE else Color.parseColor("#88FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(d(24), d(24)).apply {
                    marginStart = d(8); marginEnd = d(8)
                }
            })
            row.addView(TextView(this).apply {
                text = if (ok) String.format(java.util.Locale.US, "#%06X", c and 0xFFFFFF) else "kayıtlı değil"
                textSize = 12f * sc
                typeface = Typeface.MONOSPACE
                setTextColor(if (ok) Color.WHITE else Color.parseColor("#9FB0C7"))
                maxLines = 1
            })
            box.addView(row)
        }
        return box
    }

    private fun toggleMinimize() {
        minimized = !minimized
        applyVisibility()
    }

    // Uygulamadaki işaretlere ve küçültme durumuna göre düğmeleri gösterir/gizler
    private fun applyVisibility() {
        for ((k, item) in itemMap) {
            val on = SlotStore.btnOn(sets, k) && (!minimized || k in MIN_KEEP)
            item.col.visibility = if (on) View.VISIBLE else View.GONE
        }
        if (colorsLayer != null && (minimized || !SlotStore.btnOn(sets, "colors"))) closeColors()
        miniItem?.iv?.icon = if (minimized) Ic.GROW else Ic.SHRINK
        miniItem?.tv?.text = if (minimized) "Büyült" else "Küçült"
        panel?.post { panel?.requestLayout(); clampPanel() }
    }

    private fun updateLabels() {
        ui.post {
            val n = nextSlotNum()
            calibItem?.tv?.text = if (n == null) "Dolu" else "Yuva $n"
            val last = (1..8).lastOrNull { slotReady(it) }
            ifItem?.tv?.text = if (last == null) "if (-)" else "if ($last)"
            aifItem?.tv?.text = if (last == null) "if% (-)" else "if% ($last)"
        }
    }

    private fun rebuildPanel() {
        try {
            val x = panelLp?.x ?: d(8)
            val y = panelLp?.y ?: d(24)
            removeView(panel); panel = null
            showPanel(x, y)
            if (colorsLayer != null) ui.postDelayed({ rebuildColors() }, 350)
            ui.postDelayed({ clampPanel() }, 300)
        } catch (_: Exception) {}
    }

    private fun clampPanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        val b = screenBounds()
        val w = if (p.width > 0) p.width else p.measuredWidth
        val h = if (p.height > 0) p.height else p.measuredHeight
        lp.x = lp.x.coerceIn(0, max(0, b.width() - w))
        lp.y = lp.y.coerceIn(0, max(0, b.height() - h))
        try { wm.updateViewLayout(p, lp) } catch (_: Exception) {}
    }

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

    fun setPanelVisible(v: Boolean) {
        panelShown = v
        if (!v) { closeColors(); cancelPick(); haltLoop(); noteStop("Panel gizlendi (elle)") }
        panel?.visibility = if (v) View.VISIBLE else View.GONE
        markers?.visibility = if (v) View.VISIBLE else View.INVISIBLE
        if (v) {
            resetTotal()
            ensureScan()
        }
        updateTotUi()
    }

    fun isPanelVisible() = panelShown

    fun isRunning() = running

    fun toggleLoopExternal() {
        ui.post { if (calibLayer == null) toggleLoop() }
    }

    private fun hidePanel() {
        setPanelVisible(false)
        toast("Panel gizlendi. Geri açmak için uygulamadaki düğme.")
    }

    fun onExternalLoad() {
        ui.post {
            haltLoop()
            undoSnap = null
            resetTotal()
            refreshMarkers()
            updateLabels()
            ensureScan()
        }
    }

    private fun onTotalTap() {
        if (!SlotStore.totReady(prefs)) { selectRect("t"); return }
        val on = !sets.getBoolean("ttot", false)
        sets.edit().putBoolean("ttot", on).apply()
        toast(if (on) "Toplam kupa takibi açık" else "Toplam kupa takibi kapalı")
    }

    private fun saveRecord() {
        val b = screenBounds()
        val text = SlotStore.format(prefs, "${b.width()}x${b.height()}", sets)
        if (text == null) {
            val have = SlotStore.REQUIRED.count { slotReady(it) }
            toast("$have/${SlotStore.REQUIRED.size} zorunlu yuva (1, 2, 4, 5) dolu, kaydedilmedi"); return
        }
        val (n, added) = SlotStore.recAddUnique(recPrefs(), text)
        val cnt = SlotStore.recList(recPrefs()).size
        toast(
            when {
                n < 0 -> "Kayıt sınırı dolu ($cnt/${SlotStore.MAX_RECORDS}), önce uygulamadan birini sil"
                added -> "Kaydedildi (Kayıt $n) ($cnt/${SlotStore.MAX_RECORDS})"
                else -> "Zaten kayıtlı (Kayıt $n), tekrar kaydedilmedi"
            }
        )
    }

    // --- Yuvalar ---

    private fun slotReady(i: Int) = SlotStore.ready(prefs, i)

    private fun saveUndo() {
        undoSnap = HashMap<String, Any?>(prefs.all)
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
        undoSnap = null
        refreshMarkers()
        updateLabels()
        toast("Son değişiklik geri alındı")
    }

    private fun clearSlots() {
        haltLoop()
        saveUndo()
        prefs.edit().clear().commit()
        refreshMarkers()
        updateLabels()
        toast("Her şey silindi. Geri al ile kurtarabilirsin. Sıradaki: 1 (${SlotStore.NAMES[1]})")
    }

    private fun screenBounds(): Rect = wm.currentWindowMetrics.bounds

    private fun tapSlot(i: Int) {
        val b = screenBounds()
        val x = prefs.getFloat("x$i", 0f) * b.width()
        val y = prefs.getFloat("y$i", 0f) * b.height()
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        lastTapMs[i] = System.currentTimeMillis()
        if (i != ATTACK) note("Basıldı: $i (${SlotStore.NAMES[i]})")
        dispatchGesture(g, null, null)
    }

    // --- Nokta seçme, kalibrasyon, okuma alanı ---

    private fun pickPoint(prompt: String, onPoint: (Float, Float) -> Unit) {
        if (calibLayer != null) return
        haltLoop()
        closeColors()
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

    // Noktanın rengini alıp slot numarasına kaydeder (slot 1-8 veya IF_BASE+n)
    private fun saveSlotPoint(slot: Int, fx: Float, fy: Float, okMsg: String) {
        ui.postDelayed({
            withScreenshotWait { bmp ->
                val c = bmp?.let { sampleColor(it, fx, fy) }
                if (c != null) {
                    saveUndo()
                    prefs.edit().putFloat("x$slot", fx).putFloat("y$slot", fy)
                        .putInt("c$slot", c).commit()
                    refreshMarkers()
                    updateLabels()
                    val nx = nextSlotNum()
                    toast(okMsg + (if (nx != null) ", sıradaki: $nx (${SlotStore.NAMES[nx]})" else ", tüm yuvalar dolu"))
                } else {
                    toast("Renk alınamadı, kaydedilmedi. Tekrar dene.")
                }
            }
        }, 300)
    }

    private fun startCalibration(slot: Int) {
        pickPoint("Yuva $slot (${SlotStore.NAMES[slot]}): düğmeye dokun (basmaz, sadece kaydeder). İptal: panelde bir butona bas") { fx, fy ->
            saveSlotPoint(slot, fx, fy, "Yuva $slot kaydedildi")
        }
    }

    // if: en son kaydedilen yuvaya bağlanır
    private fun startIf() {
        val last = (1..8).lastOrNull { slotReady(it) }
        if (last == null) { toast("Önce yuva kaydet, ilk kayıt if olamaz"); return }
        if (last == CHARACTER || last == SELECT) {
            toast("7 ve 8 renge bakmaz, bunlara if konmaz"); return
        }
        if (slotReady(IF_BASE + last)) { toast("if ($last) zaten var"); return }
        pickPoint("if ($last): bu renk görününce yuva $last basılır. Noktaya dokun. İptal: panelde bir butona bas") { fx, fy ->
            saveSlotPoint(IF_BASE + last, fx, fy, "if ($last) kaydedildi")
        }
    }

    // --- if% (gelişmiş if): alandaki her şeye (renk, şekil, çizgi, yazı) bakar ---

    // Kayıt çözülmüş haliyle önbellekte tutulur; metin değişince yeniden çözülür.
    private fun aifRef(n: Int): AifRef? {
        val raw = prefs.getString("aif_$n", null) ?: return null
        val c = aifCache[n]
        if (c != null && c.first == raw) return c.second
        val r = SlotStore.aifDecode(raw)
        aifCache[n] = raw to r
        return r
    }

    private fun aifReady(n: Int) = aifRef(n) != null

    // Kesirli alan -> piksel dikdörtgeni (en az 4x4 piksel)
    private fun pxRect(bmp: Bitmap, fx1: Float, fy1: Float, fx2: Float, fy2: Float): Rect? {
        val w0 = bmp.width
        val h0 = bmp.height
        val x1 = (fx1 * w0).toInt().coerceIn(0, w0 - 1)
        val y1 = (fy1 * h0).toInt().coerceIn(0, h0 - 1)
        val x2 = (fx2 * w0).toInt().coerceIn(x1 + 1, w0)
        val y2 = (fy2 * h0).toInt().coerceIn(y1 + 1, h0)
        return if (x2 - x1 < 4 || y2 - y1 < 4) null else Rect(x1, y1, x2, y2)
    }

    private fun aifSnapshot(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): AifRef? {
        val rc = pxRect(bmp, l, t, r, b) ?: return null
        val w = rc.width()
        val h = rc.height()
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, rc.left, rc.top, w, h)
            val g = VisionMath.gridSize(w, h)
            AifRef(l, t, r, b, g[0], g[1], VisionMath.downsample(px, w, h, g[0], g[1]))
        } catch (_: Exception) { null }
    }

    private fun aifScore(n: Int, bmp: Bitmap): VisionMath.AifScore? {
        val ref = aifRef(n) ?: return null
        val rc = pxRect(bmp, ref.x1, ref.y1, ref.x2, ref.y2) ?: return null
        val w = rc.width()
        val h = rc.height()
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, rc.left, rc.top, w, h)
            val cur = VisionMath.downsample(px, w, h, ref.gw, ref.gh)
            VisionMath.compare(ref.rgb, cur, ref.gw, ref.gh)
        } catch (_: Exception) { null }
    }

    // Toplam uyum yüzdesi eşiğin (varsayılan %90) üstündeyse true
    private fun aifMatch(n: Int, bmp: Bitmap): Boolean {
        val s = aifScore(n, bmp) ?: return false
        aifLast[n] = s
        return s.total * 100f >= aifThreshold().toFloat()
    }

    // if%: en son kaydedilen yuvaya bağlanır (if ile aynı kural), alanı iki köşeyle seçersin
    private fun startAif() {
        val last = (1..8).lastOrNull { slotReady(it) }
        if (last == null) { toast("Önce yuva kaydet, ilk kayıt if% olamaz"); return }
        if (last == CHARACTER || last == SELECT) {
            toast("7 ve 8 görüntüye bakmaz, bunlara if% konmaz"); return
        }
        if (aifReady(last)) { toast("if% ($last) zaten var"); return }
        selectAif(last)
    }

    private fun selectAif(slot: Int) {
        if (calibLayer != null) return
        val t = ++rectToken
        rectPending = true
        pickPoint("if% ($slot): bakılacak alanın SOL ÜST köşesine dokun. İptal: panelde bir butona bas") { x1, y1 ->
            ui.postDelayed({
                if (t != rectToken) return@postDelayed
                pickPoint("if% ($slot): şimdi SAĞ ALT köşeye dokun. İptal: panelde bir butona bas") { x2, y2 ->
                    rectPending = false
                    val l = min(x1, x2); val r = max(x1, x2)
                    val tp = min(y1, y2); val bt = max(y1, y2)
                    if (r - l < 0.01f || bt - tp < 0.01f) {
                        toast("Alan çok küçük, tekrar dene")
                    } else {
                        saveAif(slot, l, tp, r, bt)
                    }
                }
            }, 300)
        }
    }

    private fun r4(f: Float) = Math.round(f * 10000f) / 10000f

    // Alanın o anki görüntüsünü referans olarak kaydeder
    private fun saveAif(slot: Int, l0: Float, t0: Float, r0: Float, b0: Float) {
        val l = r4(l0); val t = r4(t0); val r = r4(r0); val b = r4(b0)
        ui.postDelayed({
            withScreenshotWait { bmp ->
                val ref = bmp?.let { aifSnapshot(it, l, t, r, b) }
                if (ref == null) {
                    toast("Alan alınamadı, kaydedilmedi. Tekrar dene.")
                } else {
                    saveUndo()
                    prefs.edit().putString("aif_$slot", SlotStore.aifEncode(ref)).commit()
                    refreshMarkers()
                    updateLabels()
                    toast("if% ($slot) kaydedildi (${ref.gw}x${ref.gh} hücre). Uyumu görmek için if% düğmesine basılı tut.")
                }
            }
        }, 300)
    }

    // if% düğmesine uzun basış: kayıtlı tüm if% alanlarının şu anki uyumunu gösterir
    private fun testAif() {
        val ns = (1..8).filter { aifReady(it) }
        if (ns.isEmpty()) { toast("Kayıtlı if% yok"); return }
        withScreenshotWait { bmp ->
            if (bmp == null) {
                toast("Ekran alınamadı")
            } else {
                val th = aifThreshold()
                val sb = StringBuilder("if% (eşik %$th)")
                for (n in ns) {
                    val s = aifScore(n, bmp)
                    if (s == null) {
                        sb.append("\n$n: hesaplanamadı")
                    } else {
                        aifLast[n] = s
                        val pc = Math.round(s.total * 100f)
                        val pcc = Math.round(s.color * 100f)
                        val pce = Math.round(s.edge * 100f)
                        sb.append("\n$n: %$pc (renk %$pcc, şekil %$pce) ")
                        sb.append(if (s.total * 100f >= th.toFloat()) "uyuyor" else "uymuyor")
                    }
                }
                toast(sb.toString())
                logRead(sb.toString().replace("\n", " | "))
                refreshMarkers()
            }
        }
    }

    private fun selectRect(p: String) {
        if (calibLayer != null) return
        val name = if (p == "o") "Karakter kupası alanı" else "Toplam kupa alanı"
        val t = ++rectToken
        rectPending = true
        pickPoint("$name: SOL ÜST köşeye dokun. İptal: panelde bir butona bas") { x1, y1 ->
            ui.postDelayed({
                if (t != rectToken) return@postDelayed
                pickPoint("$name: şimdi SAĞ ALT köşeye dokun. İptal: panelde bir butona bas") { x2, y2 ->
                    rectPending = false
                    val l = min(x1, x2); val r = max(x1, x2)
                    val tp = min(y1, y2); val bt = max(y1, y2)
                    if (r - l < 0.01f || bt - tp < 0.005f) {
                        toast("Alan çok küçük, tekrar dene")
                    } else {
                        saveUndo()
                        prefs.edit().putFloat("${p}x1", l).putFloat("${p}y1", tp)
                            .putFloat("${p}x2", r).putFloat("${p}y2", bt).apply()
                        if (p == "t") {
                            sets.edit().putBoolean("ttot", true).apply()
                            resetTotal()
                            ensureScan()
                        }
                        refreshMarkers()
                        toast("$name kaydedildi.")
                    }
                }
            }, 300)
        }
    }

    // --- Kupa okuma (hem sistem hem test aynı hattı kullanır) ---

    // Bir ekran karesinde okuma alanını verilen varyantlarla okur. Hepsi bitince ya da FRAME_OCR_TIMEOUT_MS
    // sonra (eldeki sonuçlarla) cb çağrılır. bmp yalnızca bu çağrı dönene kadar geçerli, kırpmalar burada
    // eşzamanlı alınır. Okuma alanı geçersizse cb(null).
    private fun readFrame(bmp: Bitmap, variants: IntArray, cb: (FrameRead?) -> Unit) {
        val vs = ArrayList<Int>()
        val crops = ArrayList<Bitmap>()
        for (v in variants) {
            val c = cropForOcr(bmp, v)
            if (c != null) { vs.add(v); crops.add(c) }
        }
        if (crops.isEmpty()) { cb(null); return }
        val fr = FrameRead(arrayOfNulls<String>(VARIANTS), arrayOfNulls<Int>(VARIANTS))
        fr.info = regionStats(bmp, "o")
        var left = crops.size
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            cb(fr)
        }
        ui.postDelayed({
            if (!finished) {
                logRead("okuma süresi doldu: ${vs.size - left}/${vs.size} varyant bitti")
                finish()
            }
        }, FRAME_OCR_TIMEOUT_MS)
        for (i in crops.indices) {
            val v = vs[i]
            recognize(crops[i]) { raw ->
                if (!finished) {
                    fr.raws[v] = clean(raw)
                    fr.vals[v] = TrophyParser.parse(raw)
                }
                left--
                if (left == 0) finish()
            }
        }
    }

    // Döngüde her kare VARIANTS_PER_FRAME varyant okur, kareler arasında varyantlar döner
    private fun frameVariants(frame: Int): IntArray =
        IntArray(VARIANTS_PER_FRAME) { (frame * VARIANTS_PER_FRAME + it) % VARIANTS }

    private fun tallyText(vals: List<Int>): String {
        if (vals.isEmpty()) return "(boş)"
        return vals.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(3)
            .joinToString(", ", "(", ")") { "${it.key}×${it.value}" }
    }

    private fun rawSample(fr: FrameRead): String =
        fr.raws.filterNotNull().filter { it.isNotEmpty() }.distinct().take(3)
            .joinToString("|") { it.take(14) }

    // Okuma alanının ekran görüntüsündeki durumu: tamamen siyah/boş kare mi, yazı benzeri kenar var mı
    private fun regionStats(bmp: Bitmap, p: String): String {
        val rc = rectPx(bmp, p) ?: return "alan yok"
        val w = rc.width()
        val h = rc.height()
        return try {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, rc.left, rc.top, w, h)
            var sum = 0L
            var edges = 0
            var cnt = 0
            for (y in 0 until h) {
                val row = y * w
                var prev = lumaOfPixel(px[row])
                for (x in 0 until w) {
                    val l = lumaOfPixel(px[row + x])
                    sum += l
                    if (x > 0 && abs(l - prev) > 40) edges++
                    prev = l
                    cnt++
                }
            }
            "alan ${w}x$h parlaklık ${sum / cnt} kenar %${edges * 100 / cnt}"
        } catch (_: Exception) { "alan okunamadı" }
    }

    private fun lumaOfPixel(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    // Test oku: birkaç ekran karesi, turda 8 varyant, oy çoğunluğu.
    // En geç LIVE_TIMEOUT_MS sonra cevap verir: sayı ya da "Yok". Etikette sadece sayı görünür.
    private fun liveRead() {
        if (!SlotStore.rectReady(prefs)) { toast("Önce okuma alanını seç"); return }
        if (running) { toast("Önce döngüyü durdur"); return }
        val t0 = System.currentTimeMillis()
        if (liveBusy && t0 - liveStartMs < LIVE_TIMEOUT_MS + 1000L) { toast("Okuma sürüyor, bekle"); return }
        liveBusy = true
        liveStartMs = t0
        val votes = ArrayList<Int>()
        val raws = ArrayList<String>()
        var total = 0
        var info = ""
        var done = false

        fun finish(why: String?) {
            if (done) return
            done = true
            liveBusy = false
            val best = votes.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (best == null) {
                showLabel("Yok")
                val r = raws.filter { it.isNotEmpty() }.distinct().take(4).joinToString(" | ")
                val msg = (why ?: "Okunamadı") + ". Ham: " + (if (r.isEmpty()) "boş" else r).take(140)
                toast(msg)
                logRead("test: $msg | $info")
            } else {
                showLabel(best.key.toString())
                logRead("test: ${best.key} ${best.value}/$total oy | $info")
            }
        }

        ui.postDelayed({ finish("Zaman aşımı") }, LIVE_TIMEOUT_MS)

        fun round(n: Int) {
            if (done) return
            withScreenshotWait { bmp ->
                if (done) {
                    // zaman aşımı geçti, sonuç zaten bildirildi
                } else if (bmp == null) {
                    finish("Ekran görüntüsü alınamadı")
                } else {
                    readFrame(bmp, IntArray(8) { (n % 2) * 8 + it }) { fr ->
                        if (!done) {
                            if (fr == null) {
                                finish("Okuma alanı geçersiz")
                            } else {
                                total += fr.total()
                                info = fr.info
                                votes.addAll(fr.values())
                                raws.addAll(fr.raws.filterNotNull())
                                val top = votes.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
                                if (top >= 2 || n >= 2) finish(null)
                                else ui.postDelayed({ round(n + 1) }, 350)
                            }
                        }
                    }
                }
            }
        }
        round(0)
    }

    // Alan seç düğmesine uzun basış: tek karede her varyantın ham OCR çıktısını gösterir
    private fun testRead() {
        if (!SlotStore.rectReady(prefs)) { toast("Önce okuma alanını seç"); return }
        if (running) { toast("Önce döngüyü durdur"); return }
        withScreenshotWait { bmp ->
            if (bmp == null) {
                toast("Ekran alınamadı")
            } else {
                readFrame(bmp, IntArray(VARIANTS) { it }) { fr ->
                    if (fr == null) {
                        toast("Okuma alanı geçersiz")
                    } else {
                        val vals = fr.values()
                        val line = "Test: ${vals.size}/${fr.total()} okudu ${tallyText(vals)} | ${fr.info}"
                        toast(line)
                        logRead(line)
                        val detail = fr.raws.mapIndexed { i, r ->
                            if (r == null) null else "$i:'${r.take(10)}'→${fr.vals[i] ?: "?"}"
                        }.filterNotNull().joinToString("  ")
                        logRead("test ayrıntı $detail")
                    }
                }
            }
        }
    }

    private fun clean(raw: String?) = raw?.trim()?.replace("\n", " ") ?: ""

    // --- Döngü ---

    private fun resetState() {
        streak.fill(0)
        endStreak.fill(0)
        inMatch = false; hitStreak = 0; missStreak = 0
        phase = Phase.LOOP; needCheck = false; afterSwitch = false
        ocrBusy = false; switchArmed = false; failCycles = 0
        trophyUpper = null
        trophyLow = null
        reads.clear()
    }

    private fun haltLoop() {
        running = false
        session++
        switchToken++
        resetState()
        updateMainColor()
        setKeepAwake(false)
    }

    private fun stopAll(msg: String) {
        haltLoop()
        noteStop(msg)
        toast(msg)
    }

    private fun ensureScan() {
        if (scanRunning) return
        if (!running && !totalActive()) return
        scanRunning = true
        ui.post(scanTick)
    }

    private fun toggleLoop() {
        if (!running) {
            if (!slotReady(PLAY)) {
                toast("Önce yuva 1'i (Maça gir) kaydet"); return
            }
            session++
            resetState()
            needCheck = true
            closeColors()
            running = true
            setKeepAwake(true)
            ensureScan()
            if (!combatRunning) { combatRunning = true; nextTapAt = 0L; ui.post(combatTick) }
            note("Döngü başlatıldı")
            logRead("başlatıldı: sınır ${limit()}, kupa kontrolü ${if (trophyCheckOn()) "açık" else "kapalı"}, saldırı ${attackRate()}/sn")
            val missing = SlotStore.REQUIRED.filter { !slotReady(it) }
            val ocr = if (trophyCheckOn()) "kupa kontrolü AÇIK (sınır ${limit()})"
            else "kupa alanı yok, kontrol kapalı"
            val ifc = (1..8).count { slotReady(IF_BASE + it) }
            val aifc = (1..8).count { aifReady(it) }
            val ifs = (if (ifc > 0) ", $ifc if" else "") + (if (aifc > 0) ", $aifc if%" else "")
            toast(if (missing.isEmpty()) "Döngü açık, $ocr$ifs" else "Döngü açık, eksik yuvalar: $missing")
        } else {
            haltLoop()
            noteStop("Elle durduruldu")
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

    private val scanTick = object : Runnable {
        override fun run() {
            if (!running && !totalActive()) { scanRunning = false; return }
            if (calibLayer == null) {
                withScreenshot { bmp ->
                    if (bmp != null) {
                        evaluate(bmp)
                        deliverFrame(bmp)
                    }
                }
            }
            ui.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    // Saldırı basışı ayarlardaki hızda (varsayılan saniyede 3). if (2) veya if% (2) varsa sadece o koşul görünürken basar.
    private val combatTick = object : Runnable {
        override fun run() {
            if (!running) { combatRunning = false; return }
            val now = System.currentTimeMillis()
            val period = attackPeriodMs()
            val gated = slotReady(ATTACK_IF) || aifReady(ATTACK)
            val ok = if (gated) now - lastAttackSeenMs < IF_HOLD_MS
            else now - lastGateMs < GATE_FRESH_MS
            if (inMatch && ok && phase == Phase.LOOP && slotReady(ATTACK)) {
                tapSlot(ATTACK)
            }
            if (nextTapAt == 0L || now - nextTapAt > period * 3) nextTapAt = now
            nextTapAt += period
            if (nextTapAt - now > period) nextTapAt = now + period   // hız ayarı düşükten yükseğe değişirse hemen uyar
            ui.postDelayed(this, max(1L, nextTapAt - System.currentTimeMillis()))
        }
    }

    private fun hit(i: Int, bmp: Bitmap, tol: Int = COLOR_TOLERANCE): Boolean {
        if (!slotReady(i)) return false
        val c = sampleColor(bmp, prefs.getFloat("x$i", 0f), prefs.getFloat("y$i", 0f)) ?: return false
        val t = if (i == PLAY && tol == COLOR_TOLERANCE) PLAY_TOLERANCE else tol
        return close(c, prefs.getInt("c$i", 0), t)
    }

    // Yuvanın rengi görünüyor ve (varsa) if rengi ile if% uyumu da tamam
    private fun menuHit(i: Int, bmp: Bitmap) =
        hit(i, bmp) &&
            (!slotReady(IF_BASE + i) || hit(IF_BASE + i, bmp)) &&
            (!aifReady(i) || aifMatch(i, bmp))

    private fun attackMatch(bmp: Bitmap): Boolean {
        if (!slotReady(ATTACK)) return false
        val c = sampleColor(bmp, prefs.getFloat("x$ATTACK", 0f), prefs.getFloat("y$ATTACK", 0f)) ?: return false
        val ref = prefs.getInt("c$ATTACK", 0)
        if (close(c, ref, COLOR_TOLERANCE)) return true
        val hc = FloatArray(3); val hr = FloatArray(3)
        Color.colorToHSV(c, hc); Color.colorToHSV(ref, hr)
        if (hr[1] < 0.2f) return hc[1] < 0.3f && abs(hc[2] - hr[2]) < 0.3f
        var dh = abs(hc[0] - hr[0])
        if (dh > 180f) dh = 360f - dh
        return dh <= HUE_TOL && abs(hc[1] - hr[1]) <= 0.35f &&
            hc[2] >= hr[2] * 0.55f && hc[2] <= hr[2] * 1.35f + 0.05f
    }

    // Her maç başında olası kupa aralığı genişler: en fazla +10, kayıp kupa tablosuna göre (TrophyTable)
    private fun onMatchStart() {
        needCheck = true
        trophyUpper = trophyUpper?.plus(10)
        val up = trophyUpper
        trophyLow = trophyLow?.let { lo -> max(0, lo - TrophyTable.lossAt(up ?: lo)) }
        note("Maç başladı")
    }

    private var lastDiagMs = 0L

    private fun hexOf(c: Int?) = if (c == null) "-" else String.format("#%06X", c and 0xFFFFFF)

    // Takılma teşhisi: lobide beklerken 8 sn'de bir yuvaların beklenen ve okunan rengini yazar
    private fun diag(bmp: Bitmap, now: Long) {
        if (now - lastDiagMs < 8000L) return
        lastDiagMs = now
        val sb = StringBuilder("teşhis faz=$phase needCheck=$needCheck afterSwitch=$afterSwitch")
        val slots = if (phase == Phase.SWITCH) intArrayOf(BRAWLERS) else intArrayOf(PLAY, 3, 4, 5)
        for (i in slots) {
            if (!slotReady(i)) { sb.append(" | $i yok"); continue }
            val cur = sampleColor(bmp, prefs.getFloat("x$i", 0f), prefs.getFloat("y$i", 0f))
            sb.append(" | $i ${hexOf(cur)}/${hexOf(prefs.getInt("c$i", 0))} ")
            sb.append(if (hit(i, bmp)) "UYUYOR" else "uymuyor")
            sb.append(" seri=${streak[i]}")
        }
        sb.append(" | üst=${trophyUpper ?: "-"} alt=${trophyLow ?: "-"}")
        logRead(sb.toString())
    }

    private fun evaluate(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        if (totalActive()) readTotal(bmp)
        if (!running) return
        lastGateMs = now

        for (i in MENU_SLOTS) streak[i] = if (menuHit(i, bmp)) streak[i] + 1 else 0
        val ifMode = slotReady(ATTACK_IF)
        val aifMode = aifReady(ATTACK)
        val sig = when {
            aifMode -> aifMatch(ATTACK, bmp) && (!ifMode || hit(ATTACK_IF, bmp))
            ifMode -> hit(ATTACK_IF, bmp)
            else -> attackMatch(bmp)
        }
        if (aifLast.isNotEmpty()) refreshMarkers()
        if (sig) { hitStreak++; missStreak = 0; lastAttackSeenMs = now }
        else { hitStreak = 0; missStreak++ }

        if (hitStreak >= ABORT_ATTACK_SCANS && phase != Phase.LOOP) {
            if (phase == Phase.READING) logRead("okuma yarıda kesildi: saldırı/if sinyali $hitStreak taramadır görünüyor")
            phase = Phase.LOOP
            ocrBusy = false; switchArmed = false; afterSwitch = false
            switchToken++
            reads.clear()
            if (!inMatch) onMatchStart()
            inMatch = true
        }
        if (hitStreak >= ENTER_HITS && !inMatch && phase == Phase.LOOP) {
            inMatch = true
            onMatchStart()
        }

        if (phase == Phase.LOOP && inMatch) {
            if (ifMode || aifMode) {
                if (missStreak >= IF_EXIT_MISSES) {
                    inMatch = false; hitStreak = 0; endStreak.fill(0)
                    note(if (aifMode) "Maç bitti (if% uymadı)" else "Maç bitti (if rengi gitti)")
                }
            } else {
                if (sig) endStreak.fill(0)
                else for (i in MENU_SLOTS) endStreak[i] = if (hit(i, bmp, END_TOL)) endStreak[i] + 1 else 0
                if (MENU_SLOTS.any { endStreak[it] >= END_SCANS }) {
                    inMatch = false; hitStreak = 0; endStreak.fill(0)
                    note("Maç bitti (maç sonu düğmesi)")
                } else if (now - lastAttackSeenMs > DEAD_HOLD_MS) {
                    inMatch = false
                    note("Maç bitti (düğme ${DEAD_HOLD_MS / 1000} sn görünmedi)")
                }
            }
            if (inMatch) return
        }

        diag(bmp, now)
        when (phase) {
            Phase.LOOP -> loopStep(now)
            Phase.READING -> readStep(bmp, now)
            Phase.SWITCH -> switchStep(bmp, now)
        }
    }

    private fun loopStep(now: Long) {
        for (i in MENU_SLOTS) {
            if (streak[i] < CONFIRM_SCANS || now - lastTapMs[i] < SLOT_COOLDOWN_MS) continue
            streak.fill(0)
            if (i == PLAY && needCheck && trophyCheckOn()) {
                startReading(now)
                return
            }
            tapSlot(i)
            if (i != PLAY) needCheck = true
            return
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
        note("Kupa okuması başladı")
    }

    private fun readStep(bmp: Bitmap, now: Long) {
        if (now < settleUntil) return
        if (now - readStartMs > READ_TIMEOUT_MS) { readFailed("Okuma zaman aşımı"); return }
        if (ocrBusy) return
        if (!hit(PLAY, bmp)) {
            if (now - lastWaitLogMs > 5000L) {
                lastWaitLogMs = now
                logRead("okuma bekliyor: yuva 1 (Maça gir) rengi görünmüyor")
            }
            return
        }
        ocrBusy = true
        val s = session
        // Her karede yalnızca VARIANTS_PER_FRAME varyant okunur (16'sı birden telefonu yavaşlatıyordu)
        readFrame(bmp, frameVariants(readAttempts)) { fr ->
            if (s == session) {
                ocrBusy = false
                if (fr == null) stopAll("Okuma alanı geçersiz: sistem durdu") else onRead(fr)
            }
        }
    }

    private fun onRead(fr: FrameRead) {
        if (!running || phase != Phase.READING) return
        readAttempts++
        val vals = fr.values()
        for (v in vals) reads.add(Vote(v, readAttempts))
        val head = "okuma $readAttempts: ${vals.size}/${fr.total()} okudu ${tallyText(vals)}"
        toast(head)
        logRead(head + (if (vals.isEmpty()) " ham: ${rawSample(fr)}" else "") + " | ${fr.info}")
        val winner = pickWinner()
        when {
            winner != null -> decide(winner)
            readAttempts >= MAX_READ_ATTEMPTS -> {
                val weak = weakWinner()
                if (weak != null) {
                    logRead("zayıf çoğunluk kabul edildi: $weak")
                    decide(weak)
                } else {
                    readFailed("Kupa okunamadı")
                }
            }
        }
    }

    private fun readFailed(msg: String) {
        failCycles++
        val up = trophyUpper
        if (up != null && up < limit() && !afterSwitch) {
            note("$msg: tahmini üst değer $up < sınır ${limit()}, okumadan devam")
            toast("$msg: tahmin $up ≤ sınır altı, devam")
            phase = Phase.LOOP; needCheck = false; failCycles = 0
            streak.fill(0)
            return
        }
        if (failCycles >= MAX_FAIL_CYCLES) {
            stopAll("$msg ($failCycles tur): sistem durdu"); return
        }
        note("$msg: tekrar deneniyor ($failCycles/$MAX_FAIL_CYCLES)")
        streak.fill(0)
        startReading(System.currentTimeMillis() + 1500)
    }

    private fun tallyVotes(): Tally {
        val c = HashMap<Int, Int>()
        val f = HashMap<Int, HashSet<Int>>()
        for (r in reads) {
            c[r.value] = (c[r.value] ?: 0) + 1
            f.getOrPut(r.value) { HashSet() }.add(r.frame)
        }
        return Tally(c, f.mapValues { it.value.size })
    }

    // En çok oy alan değer kazanır (beraberlikte okumaya devam), en az MIN_FRAMES farklı karede görülmeli.
    // Sınırın altı 3 oyla kabul edilir; sınır ve üstü (karakter değiştirir) en az 4 oy ister ve
    // sınır altı okumalar (kazanan oyun dörtte biri kadar) çelişmemeli.
    private fun pickWinner(): Int? {
        val lim = limit()
        val t = tallyVotes()
        val ranked = t.counts.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0
        if (top.value < REQUIRED_VOTES || top.value <= second) return null
        if ((t.frames[top.key] ?: 0) < MIN_FRAMES) return null
        if (top.key < lim) return top.key
        val low = t.counts.entries.filter { it.key < lim }.sumOf { it.value }
        return if (top.value >= SWITCH_VOTES && low <= top.value / 4) top.key else null
    }

    // Tur sonunda net bir sonuç çıkmadıysa: sınırın ALTINDAKİ belirgin çoğunluğu (en az 2 oy,
    // ikincinin iki katı, en az 2 karede) kabul eder. Karakter değiştirmeye yol açan değerler için asla.
    private fun weakWinner(): Int? {
        val lim = limit()
        val t = tallyVotes()
        val ranked = t.counts.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0
        if (top.key >= lim) return null
        if (top.value < 2 || top.value < second * 2) return null
        if ((t.frames[top.key] ?: 0) < MIN_FRAMES) return null
        return top.key
    }

    // Bilinen kupadan maçlarla ulaşılamayacak bir okuma: yok sayılır, karakter değişmez.
    private fun implausible(v: Int, lim: Int, up: Int, lo: Int) {
        note("İmkânsız okuma $v (olası $lo..$up): yok sayıldı")
        toast("Okuma $v olası aralığın ($lo..$up) dışında, yok sayıldı")
        logRead("yok sayıldı: $v, olası $lo..$up, sınır $lim")
        streak.fill(0)
        if (up < lim) {
            // Tahmin sınırın altında: karakter değişmez, oynamaya devam
            phase = Phase.LOOP; needCheck = false; failCycles = 0
            return
        }
        // Tahmin sınırı geçmiş olabilir ama okuma tutarsız: tekrar oku
        failCycles++
        if (failCycles >= MAX_FAIL_CYCLES) {
            stopAll("Okuma tutarsız ($v, olası $lo..$up): sistem durdu"); return
        }
        startReading(System.currentTimeMillis() + 1500)
    }

    private fun decide(v: Int) {
        val lim = limit()
        val up = trophyUpper
        val lo = trophyLow
        logRead("karar: $v (sınır $lim, olası aralık ${lo ?: "-"}..${up ?: "-"})")
        // Karakter değiştirdikten sonraki ilk okumada eski aralık geçersizdir
        if (!afterSwitch && up != null && lo != null &&
            (v > up + SUSPECT_SLACK || v < lo - SUSPECT_SLACK)) {
            implausible(v, lim, up, lo)
            return
        }
        trophyUpper = v
        trophyLow = v
        note("Kupa $v (sınır $lim)")
        toast("Kupa: $v (sınır $lim)")
        streak.fill(0)
        failCycles = 0
        if (v < lim) {
            phase = Phase.LOOP; needCheck = false; afterSwitch = false
            return
        }
        if (afterSwitch) {
            stopAll("Karakter değişti ama kupa hâlâ $v (≥$lim): sistem durdu")
            return
        }
        if (!(BRAWLERS..SELECT).all { slotReady(it) }) {
            stopAll("Kupa $v (≥$lim) ama yuva 6-8 eksik: sistem durdu")
            return
        }
        phase = Phase.SWITCH
        switchArmed = false
        stepStartMs = System.currentTimeMillis()
        note("Karakter değiştiriliyor")
    }

    private fun switchLive(s: Int, tok: Int) =
        running && s == session && tok == switchToken && phase == Phase.SWITCH

    private fun switchStep(bmp: Bitmap, now: Long) {
        if (switchArmed) return
        if (now - stepStartMs > STEP_TIMEOUT_MS) {
            stopAll("Savaşçılar düğmesi görünmedi: sistem durdu")
            return
        }
        streak[BRAWLERS] = if (menuHit(BRAWLERS, bmp)) streak[BRAWLERS] + 1 else 0
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

    private fun cropForOcr(bmp: Bitmap, variant: Int): Bitmap? = cropRect(bmp, "o", variant)

    // "o" okuma alanı, "t" toplam alanı: kayıtlı kesirli alanın piksel dikdörtgeni
    private fun rectPx(bmp: Bitmap, p: String): Rect? {
        val ok = if (p == "o") SlotStore.rectReady(prefs) else SlotStore.totReady(prefs)
        if (!ok) return null
        return pxRect(
            bmp, prefs.getFloat("${p}x1", 0f), prefs.getFloat("${p}y1", 0f),
            prefs.getFloat("${p}x2", 0f), prefs.getFloat("${p}y2", 0f)
        )
    }

    // variant = ölçek * STYLES + biçim.
    // Biçim: 0 normal, 1 kontrastlı gri, 2 kontrastlı gri ters, 3 ters renk,
    // 4 parlak yazıyı siyaha çeviren eşik, 5 parlak yazı beyaz kalan eşik,
    // 6 Otsu (az görülen sınıf yazı, siyah üstüne beyaz zemin), 7 Otsu ters.
    // Ölçek: 0 -> OCR_TARGET_H, 1 -> OCR_TARGET_H2 yüksekliğine büyütülür.
    private fun cropRect(bmp: Bitmap, p: String, variant: Int): Bitmap? {
        val rc = rectPx(bmp, p) ?: return null
        val w = rc.width()
        val h = rc.height()
        if (w < 8 || h < 8) return null
        return try {
            val crop = Bitmap.createBitmap(bmp, rc.left, rc.top, w, h)
            val target = if ((variant / STYLES) % 2 == 0) OCR_TARGET_H else OCR_TARGET_H2
            val s = (target.toFloat() / h).coerceIn(1f, 4f)
            val sized = if (s <= 1.01f) crop else {
                val scaled = Bitmap.createScaledBitmap(crop, (w * s).toInt(), (h * s).toInt(), true)
                if (scaled !== crop) crop.recycle()
                scaled
            }
            styleVariant(padBitmap(sized), variant % STYLES)
        } catch (_: Exception) { null }
    }

    private fun padBitmap(src: Bitmap): Bitmap {
        val pad = max(8, src.height / 3)
        val out = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(src.getPixel(1, 1))
        cv.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        src.recycle()
        return out
    }

    private fun contrast(k: Float, off: Float) = ColorMatrix(floatArrayOf(
        k, 0f, 0f, 0f, off,
        0f, k, 0f, 0f, off,
        0f, 0f, k, 0f, off,
        0f, 0f, 0f, 1f, 0f
    ))

    private fun invertM() = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    ))

    // Otsu eşiğiyle iki renge indirir. flip=false: yazı (az görülen sınıf) siyah, zemin beyaz.
    private fun otsuVariant(src: Bitmap, flip: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val l = lumaOfPixel(px[i]).coerceIn(0, 255)
            lum[i] = l
            hist[l]++
        }
        val t = VisionMath.otsu(hist, px.size)
        var above = 0
        for (l in lum) if (l > t) above++
        val brightIsText = above * 2 < px.size
        for (i in px.indices) {
            val bright = lum[i] > t
            val isText = if (brightIsText) bright else !bright
            px[i] = if (isText != flip) Color.BLACK else Color.WHITE
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        return out
    }

    private fun styleVariant(src: Bitmap, variant: Int): Bitmap {
        if (variant == 0) return src
        if (variant >= 6) return otsuVariant(src, variant == 7)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        when (variant) {
            1, 2 -> {
                cm.setSaturation(0f)
                cm.postConcat(contrast(2.2f, -128f * 1.2f))
                if (variant == 2) cm.postConcat(invertM())
            }
            3 -> cm.postConcat(invertM())
            else -> {
                cm.setSaturation(0f)
                cm.postConcat(contrast(8f, 128f - 8f * 170f))
                if (variant == 4) cm.postConcat(invertM())
            }
        }
        val pnt = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, pnt)
        src.recycle()
        return out
    }

    // OCR hatası olursa nedeni "Son okumalar"a yazılır; bekleyen iş kalmadıysa tanıyıcı yeniden kurulur.
    private fun recognize(bm: Bitmap, cb: (String?) -> Unit) {
        try {
            val r = recognizer ?: TextRecognition
                .getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            val task = r.process(InputImage.fromBitmap(bm, 0))
            ocrInFlight++
            task.addOnSuccessListener { res ->
                ocrInFlight--
                bm.recycle()
                cb(res.text)
            }.addOnFailureListener { e ->
                ocrInFlight--
                bm.recycle()
                ocrFailed(e)
                cb(null)
            }
        } catch (e: Exception) {
            bm.recycle()
            ocrFailed(e)
            cb(null)
        }
    }

    private fun ocrFailed(e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastOcrErrLogMs > 3000L) {
            lastOcrErrLogMs = now
            logRead("OCR hatası: ${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}")
        }
        if (ocrInFlight <= 0) {
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
        }
    }

    private fun close(a: Int, b: Int, tol: Int) =
        abs(Color.red(a) - Color.red(b)) <= tol &&
        abs(Color.green(a) - Color.green(b)) <= tol &&
        abs(Color.blue(a) - Color.blue(b)) <= tol

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
        if (shotBusy) {
            // takeScreenshot geri çağrısı hiç gelmezse bayrak sonsuza dek takılı kalmasın
            if (System.currentTimeMillis() - shotStartMs > 4000L) shotBusy = false
            else { done(null); return }
        }
        shotBusy = true
        shotStartMs = System.currentTimeMillis()
        try {
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
        } catch (e: Exception) {
            shotBusy = false
            logRead("ekran görüntüsü hatası: ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}")
            done(null)
        }
    }

    // Tarama zaten ~0,35 sn'de bir ekran görüntüsü alıyor; Android iki görüntü arasında en az bir süre
    // bekletir (bildiğim kadarıyla yaklaşık 0,33 sn). Test düğmeleri ayrı görüntü istemek yerine taramanın
    // bir sonraki karesini bekler; tarama yoksa ya da kare gelmezse eski yöntemle kendi görüntüsünü alır.
    private fun withScreenshotWait(tries: Int = 15, done: (Bitmap?) -> Unit) {
        if (!scanRunning) { shotRetry(tries, done); return }
        val w = FrameWaiter(done)
        frameWaiters.add(w)
        ui.postDelayed({
            if (frameWaiters.remove(w)) shotRetry(tries, done)
        }, FRAME_WAIT_MS)
    }

    // Meşgul veya sistem aralık hatası verirse bekleyip tekrar dener (en fazla ~2,3 sn)
    private fun shotRetry(tries: Int, done: (Bitmap?) -> Unit) {
        withScreenshot { bmp ->
            if (bmp == null && tries > 0) {
                ui.postDelayed({ shotRetry(tries - 1, done) }, 150)
            } else {
                done(bmp)
            }
        }
    }

    // Tarama karesini bekleyen test düğmelerine verir (bitmap yalnızca bu çağrı boyunca geçerli)
    private fun deliverFrame(bmp: Bitmap) {
        if (frameWaiters.isEmpty()) return
        val list = ArrayList(frameWaiters)
        frameWaiters.clear()
        for (w in list) {
            try {
                w.cb(bmp)
            } catch (e: Exception) {
                logRead("kare geri çağrısı hatası: ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}")
            }
        }
    }

    private fun toast(s: String) {
        ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
