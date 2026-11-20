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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StartService : AccessibilityService() {

    private enum class Phase { LOOP, READING, SWITCH }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE) }
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshMarkers() }

    private var panel: LinearLayout? = null
    private var mainBtn: View? = null
    private var calibLayer: View? = null
    private var markers: MarkerView? = null
    private var markersOn = true

    private var running = false
    private var shotBusy = false
    private var quiet = false            // true iken saldırı basmaz (ekran okuma anı)
    private var nextSlot = 1
    private var lastPkg: String? = null

    // Maç kapısı
    private var inMatch = false
    private var hitStreak = 0
    private var missStreak = 0
    private var lastGateMs = 0L

    // Kupa kontrolü
    private var phase = Phase.LOOP
    private var needCheck = false
    private var afterSwitch = false
    private var switchStep = BRAWLERS
    private var stepStartMs = 0L
    private var stepNotBefore = 0L
    private var readStartMs = 0L
    private var settleUntil = 0L
    private var ocrBusy = false
    private var readAttempts = 0
    private val reads = ArrayList<Int?>()
    private var session = 0
    private var recognizer: TextRecognizer? = null

    private val streak = IntArray(SlotStore.SLOTS + 1)
    private val lastTapMs = LongArray(SlotStore.SLOTS + 1)

    private companion object {
        const val GAME_PKG = "com.supercell.brawlstars"   // Ayarlar > Uygulamalar'dan doğrula
        const val SLOTS = SlotStore.SLOTS
        const val PLAY = 1
        const val ATTACK = 2
        const val EXIT = 5
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
        const val QUIET_MS = 150L            // okumadan önce saldırıyı durdurma süresi (kalibre edilmedi)
        const val QUIET_SAFETY_MS = 700L
        const val COMBAT_PERIOD_MS = 200L    // saniyede 5
        const val GATE_FRESH_MS = 2500L
        const val ENTER_HITS = 2
        const val EXIT_MISSES = 2
        const val SLOT_COOLDOWN_MS = 3000L
        const val CONFIRM_SCANS = 2
        const val COLOR_TOLERANCE = 35       // kalibre edilmedi
        const val SAMPLE_RADIUS = 3

        const val OCR_TARGET_H = 128
        const val MAX_READ_ATTEMPTS = 6
        const val READ_TIMEOUT_MS = 15000L
        const val STEP_TIMEOUT_MS = 12000L
        const val STEP_GAP_MS = 1500L
        const val SETTLE_MS = 2500L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        showMarkers()
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
                c.drawText("OKU", (l + rr) / 2f, t - dp(6), txt)
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
        toast(if (markersOn) "Halkalar gösteriliyor" else "Halkalar gizli")
    }

    // --- Panel ---

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
        val calib = circle(Color.parseColor("#1E88E5"), 26)    // kalibrasyon; uzun = hepsini sil
        val hide = circle(Color.parseColor("#9E9E9E"), 26)     // halkaları gizle/göster
        val copy = circle(Color.parseColor("#8E24AA"), 26)     // panoya kopyala
        val area = circle(Color.parseColor("#00ACC1"), 26)     // kupa alanı seç; uzun = test oku
        mainBtn = main

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(handle); addView(main); addView(calib)
            addView(hide); addView(copy); addView(area)
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
            x = dp(16); y = dp(80)
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
                        toast("Sıradaki yuva: $nextSlot (${SlotStore.NAMES[nextSlot]})")
                    }
                }
            }
            true
        }

        main.setOnClickListener { toggleLoop() }
        calib.setOnClickListener { startCalibration(nextSlot) }
        calib.setOnLongClickListener { clearSlots(); true }
        hide.setOnClickListener { toggleMarkers() }
        copy.setOnClickListener { copySlots() }
        area.setOnClickListener { selectRect() }
        area.setOnLongClickListener { testRead(); true }

        wm.addView(box, lp)
    }

    // --- Yuvalar ---

    private fun slotReady(i: Int) = SlotStore.ready(prefs, i)

    private fun clearSlots() {
        haltLoop()
        prefs.edit().clear().apply()
        nextSlot = 1
        refreshMarkers()
        toast("Her şey silindi (yuvalar + okuma alanı), sıradaki: 1 (${SlotStore.NAMES[1]})")
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
        lastTapMs[i] = System.currentTimeMillis()
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
    }

    private fun startCalibration(slot: Int) {
        pickPoint("Yuva $slot (${SlotStore.NAMES[slot]}): düğmeye dokun (basmaz, sadece kaydeder)") { fx, fy ->
            ui.postDelayed({
                withScreenshot { bmp ->
                    val c = bmp?.let { sampleColor(it, fx, fy) }
                    if (c != null) {
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
        pickPoint("Kupa alanı: SOL ÜST köşeye dokun") { x1, y1 ->
            ui.postDelayed({
                pickPoint("Şimdi SAĞ ALT köşeye dokun") { x2, y2 ->
                    val l = min(x1, x2); val r = max(x1, x2)
                    val t = min(y1, y2); val bt = max(y1, y2)
                    if (r - l < 0.01f || bt - t < 0.005f) {
                        toast("Alan çok küçük, tekrar dene")
                    } else {
                        prefs.edit().putFloat("ox1", l).putFloat("oy1", t)
                            .putFloat("ox2", r).putFloat("oy2", bt).apply()
                        refreshMarkers()
                        toast("Okuma alanı kaydedildi. Uzun basarak test oku.")
                    }
                }
            }, 300)
        }
    }

    // Teal butona uzun basış: alanı bir kere okur, ham metni ve sayıyı gösterir
    private fun testRead() {
        if (!SlotStore.rectReady(prefs)) { toast("Önce okuma alanını seç"); return }
        if (running) { toast("Önce döngüyü kapat"); return }
        withScreenshot { bmp ->
            val crop = bmp?.let { cropForOcr(it) }
            if (crop == null) {
                toast("Ekran alınamadı veya alan geçersiz")
            } else {
                recognize(crop) { raw ->
                    toast("Ham: '${raw?.trim()}' → ${TrophyParser.parse(raw) ?: "okunamadı"}")
                }
            }
        }
    }

    // --- Döngü ---

    private fun resetState() {
        streak.fill(0)
        inMatch = false; hitStreak = 0; missStreak = 0
        phase = Phase.LOOP; needCheck = false; afterSwitch = false
        ocrBusy = false; quiet = false
        reads.clear()
    }

    private fun haltLoop() {
        running = false
        session++
        resetState()
        updateMainColor()
    }

    private fun stopAll(msg: String) {
        haltLoop()
        toast(msg)
    }

    private fun toggleLoop() {
        if (!running) {
            if (!slotReady(PLAY)) {
                toast("Önce yuva 1'i (Maça gir) kalibre et"); return
            }
            session++
            resetState()
            needCheck = true            // ilk Oyna'dan önce kupayı oku
            running = true
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
        val bg = mainBtn?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (running) "#43A047" else "#E53935"))
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!running) return
            val pkg = lastPkg
            val gameFront = pkg == null || pkg == GAME_PKG
            if (gameFront && calibLayer == null) {
                // Okumadan hemen önce saldırıyı durdur ki basılı renk okunmasın
                quiet = true
                ui.postDelayed({ quiet = false }, QUIET_SAFETY_MS)
                ui.postDelayed({
                    if (!running) { quiet = false; return@postDelayed }
                    withScreenshot { bmp ->
                        try {
                            if (running && bmp != null) evaluate(bmp)
                        } finally {
                            quiet = false
                        }
                    }
                }, QUIET_MS)
            } else {
                streak.fill(0)
                inMatch = false; hitStreak = 0; missStreak = 0
            }
            ui.postDelayed(this, SCAN_PERIOD_MS)
        }
    }

    // Maçtayken saldırı düğmesine saniyede 5 basış
    private val combatTick = object : Runnable {
        override fun run() {
            if (!running) return
            val fresh = System.currentTimeMillis() - lastGateMs < GATE_FRESH_MS
            if (inMatch && fresh && !quiet && phase == Phase.LOOP && slotReady(ATTACK)) {
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
        updateGate(bmp, now)
        if (inMatch) {
            streak.fill(0)
            if (phase != Phase.LOOP) { phase = Phase.LOOP; needCheck = false; afterSwitch = false }
            return
        }
        when (phase) {
            Phase.LOOP -> loopStep(bmp, now)
            Phase.READING -> readStep(bmp, now)
            Phase.SWITCH -> switchStep(bmp, now)
        }
    }

    // Maç dışı: Maça gir, Devam, Devam, Çıkış
    private fun loopStep(bmp: Bitmap, now: Long) {
        for (i in MENU_SLOTS) streak[i] = if (hit(i, bmp)) streak[i] + 1 else 0
        for (i in MENU_SLOTS) {
            if (streak[i] < CONFIRM_SCANS || now - lastTapMs[i] < SLOT_COOLDOWN_MS) continue
            streak.fill(0)
            if (i == PLAY && needCheck && SlotStore.rectReady(prefs)) {
                startReading(now)       // Oyna'ya basmadan önce kupayı oku
                return
            }
            tapSlot(i)
            if (i == EXIT) needCheck = true
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
    }

    private fun readStep(bmp: Bitmap, now: Long) {
        if (now < settleUntil) return
        if (now - readStartMs > READ_TIMEOUT_MS) { giveUp("Okuma zaman aşımı"); return }
        if (ocrBusy) return
        if (!hit(PLAY, bmp)) return          // lobi görünmüyorsa okuma
        val crop = cropForOcr(bmp)
        if (crop == null) { giveUp("Okuma alanı geçersiz"); return }
        ocrBusy = true
        val s = session
        recognize(crop) { raw ->
            if (s == session) {
                ocrBusy = false
                onRead(TrophyParser.parse(raw))
            }
        }
    }

    private fun onRead(v: Int?) {
        if (!running || phase != Phase.READING) return
        reads.add(v)
        readAttempts++
        val n = reads.size
        val agreed = n >= 2 && v != null && reads[n - 2] == v
        when {
            agreed -> decide(v!!)
            readAttempts >= MAX_READ_ATTEMPTS -> giveUp("Kupa okunamadı")
        }
    }

    private fun giveUp(msg: String) {
        phase = Phase.LOOP; needCheck = false; afterSwitch = false
        streak.fill(0)
        toast("$msg: kontrol atlandı, döngü devam")
    }

    private fun decide(v: Int) {
        toast("Kupa: $v")
        streak.fill(0)
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
        val now = System.currentTimeMillis()
        phase = Phase.SWITCH
        switchStep = BRAWLERS
        stepStartMs = now
        stepNotBefore = now
    }

    // Savaşçılar → Karakter → Seç: her adım kendi düğme rengi görününce basılır
    private fun switchStep(bmp: Bitmap, now: Long) {
        if (now - stepStartMs > STEP_TIMEOUT_MS) {
            stopAll("Adım $switchStep (${SlotStore.NAMES[switchStep]}) zaman aşımı: sistem durdu")
            return
        }
        if (now < stepNotBefore) return
        val i = switchStep
        streak[i] = if (hit(i, bmp)) streak[i] + 1 else 0
        if (streak[i] < CONFIRM_SCANS) return
        streak[i] = 0
        tapSlot(i)
        if (i < SELECT) {
            switchStep = i + 1
            stepStartMs = now
            stepNotBefore = now + STEP_GAP_MS
        } else {
            afterSwitch = true
            startReading(now + SETTLE_MS)
        }
    }

    private fun cropForOcr(bmp: Bitmap): Bitmap? {
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
            if (s <= 1.01f) crop else {
                val scaled = Bitmap.createScaledBitmap(crop, (w * s).toInt(), (h * s).toInt(), true)
                if (scaled !== crop) crop.recycle()
                scaled
            }
        } catch (_: Exception) { null }
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
