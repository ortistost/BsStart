package com.example.bsstart

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

// Ondalık sayı girişi: noktadan sonra en fazla maxFrac rakam, en fazla 2 haneli tam kısım.
// Son rakam 0 olamaz (1.40 yazılamaz, 1.4 yazılır). Virgül noktaya çevrilir.
private class DecimalFilter(private val maxFrac: Int) : InputFilter {
    override fun filter(
        source: CharSequence, start: Int, end: Int,
        dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        val raw = source.subSequence(start, end).toString()
        val ins = raw.replace(',', '.')
        val result = dest.subSequence(0, dstart).toString() + ins +
            dest.subSequence(dend, dest.length).toString()
        if (!acceptable(result)) return ""
        return if (ins == raw) null else ins
    }

    private fun acceptable(t: String): Boolean {
        if (t.isEmpty()) return true
        val parts = t.split('.')
        if (parts.size > 2) return false
        val whole = parts[0]
        if (whole.length > 2 || whole.any { !it.isDigit() }) return false
        if (parts.size == 2) {
            val frac = parts[1]
            if (frac.length > maxFrac || frac.any { !it.isDigit() }) return false
            if (frac.length == maxFrac && frac.endsWith("0")) return false
        }
        return true
    }
}

// 1.0 -> "1", 1.40 -> "1.4", 1.04 -> "1.04"
private fun fmtNum(v: Float, maxFrac: Int): String {
    val s = String.format(Locale.US, "%.${maxFrac}f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

// null = biçim geçerli, değilse hata metni
private fun decimalError(t: String, maxFrac: Int): String? {
    val s = t.trim()
    if (s.isEmpty()) return "Bir sayı gir"
    val m = Regex("^(\\d{1,2})(?:\\.(\\d{0,$maxFrac}))?\$").find(s) ?: return "Geçerli bir sayı gir"
    val frac = m.groupValues[2]
    if (s.contains('.') && frac.isEmpty()) return "Noktadan sonra rakam yaz"
    if (frac.endsWith("0")) {
        val v = s.toFloatOrNull() ?: return "Geçerli bir sayı gir"
        return "Sondaki 0 gereksiz, ${fmtNum(v, maxFrac)} yaz"
    }
    return null
}

class MainActivity : Activity() {

    private val BG1 = Color.parseColor("#0A111C")
    private val BG2 = Color.parseColor("#13203A")
    private val CARD_A = Color.parseColor("#1D2D4C")
    private val CARD_B = Color.parseColor("#15213A")
    private val EDGE = Color.parseColor("#2E4268")
    private val FIELD = Color.parseColor("#0B1424")
    private val SUB = Color.parseColor("#9FB0C7")
    private val ACC = Color.parseColor("#6B97FF")
    private val GOLD = Color.parseColor("#FFB300")
    private val BLUE = intArrayOf(Color.parseColor("#2F6BFF"), Color.parseColor("#5B8CFF"))
    private val GREYG = intArrayOf(Color.parseColor("#34445F"), Color.parseColor("#2A3852"))
    private val RED = intArrayOf(Color.parseColor("#E53935"), Color.parseColor("#B71C1C"))
    private val GOLDG = intArrayOf(Color.parseColor("#F2A900"), Color.parseColor("#C98500"))

    private val h = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var eventTv: TextView
    private lateinit var stopTv: TextView
    private lateinit var readsTv: TextView
    private lateinit var recBox: LinearLayout
    private lateinit var recSec: Sec
    private lateinit var limitEt: EditText
    private lateinit var scaleEt: EditText
    private lateinit var rateEt: EditText
    private lateinit var aifEt: EditText
    private val checks = ArrayList<Pair<String, CheckBox>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun say(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun cfg() = getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE)
    private fun recPrefs() = getSharedPreferences(SlotStore.REC, MODE_PRIVATE)
    private fun setPrefs() = getSharedPreferences(SlotStore.SET, MODE_PRIVATE)
    private fun uiPrefs() = getSharedPreferences("bsui", MODE_PRIVATE)

    private fun grad(c: IntArray, r: Int, vertical: Boolean = false, stroke: Int? = null) =
        GradientDrawable(
            if (vertical) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.LEFT_RIGHT,
            c
        ).apply {
            cornerRadius = dp(r).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = grad(intArrayOf(CARD_A, CARD_B), 18, vertical = true, stroke = EDGE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(12)
    }

    private fun txt(t: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
    }

    // Açılıp kapanan bölüm. Kapalıyken başlıkta yalnızca ad görünür, parantez içi yazılmaz.
    private inner class Sec(val key: String, val base: String, val detail: () -> String = { "" }) {
        val card = cardBox()
        val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val head = TextView(this@MainActivity).apply {
            textSize = 13f; setTextColor(ACC); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val chev = TextView(this@MainActivity).apply { textSize = 16f; setTextColor(SUB) }
        private var isOpen = try { !uiPrefs().getBoolean("col_$key", false) } catch (_: Exception) { true }

        init {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    isOpen = !isOpen
                    try { uiPrefs().edit().putBoolean("col_$key", !isOpen).apply() } catch (_: Exception) {}
                    render()
                }
            }
            row.addView(head)
            row.addView(chev)
            card.addView(row)
            card.addView(body)
            render()
        }

        fun render() {
            val d = detail()
            head.text = if (isOpen && d.isNotEmpty()) "$base ($d)" else base
            chev.text = if (isOpen) "▾" else "▸"
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
    }

    private fun btn(label: String, g: IntArray = BLUE, act: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = grad(g, 12)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) }
        setOnClickListener { act() }
    }

    private fun rowBtn(label: String, g: IntArray, act: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
        background = grad(g, 10)
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
        setOnClickListener { act() }
    }

    // Basılı tutunca çalışan düğme: süre dolana kadar kalan saniyeyi gösterir, erken bırakırsan iptal olur
    private fun holdBtn(label: String, holdMs: Long, g: IntArray, done: () -> Unit): Button {
        val b = rowBtn(label, g) {}
        var t0 = 0L
        var ticking = false
        val tick = object : Runnable {
            override fun run() {
                if (!ticking) return
                val left = holdMs - (SystemClock.uptimeMillis() - t0)
                if (left <= 0L) {
                    ticking = false
                    b.text = label
                    done()
                } else {
                    b.text = ((left + 999L) / 1000L).toString()
                    h.postDelayed(this, 100L)
                }
            }
        }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    t0 = SystemClock.uptimeMillis()
                    ticking = true
                    h.post(tick)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ticking) {
                        ticking = false
                        h.removeCallbacks(tick)
                        b.text = label
                        if (e.actionMasked == MotionEvent.ACTION_UP) say("${holdMs / 1000L} sn basılı tut")
                    }
                }
            }
            true
        }
        return b
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type
        setTextColor(Color.WHITE); setHintTextColor(SUB)
        background = grad(intArrayOf(FIELD, FIELD), 10, stroke = EDGE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setSingleLine(true)
        layoutParams = lp(6)
    }

    private fun decimalField(hint: String, maxFrac: Int) =
        field(hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            filters = arrayOf<InputFilter>(DecimalFilter(maxFrac))
        }

    // Yazı yazıldıktan 700 ms sonra uygular (kaydet düğmesi yok)
    private fun watch(et: EditText, apply: (String) -> Unit) {
        var r: Runnable? = null
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                r?.let { h.removeCallbacks(it) }
                val text = s?.toString() ?: ""
                r = Runnable { apply(text) }.also { h.postDelayed(it, 700) }
            }
        })
    }

    private fun copyText(label: String, t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, t))
        say("Kopyalandı")
    }

    private fun refreshStatus() {
        val s = ServiceBridge.service
        statusTv.text = when {
            s == null -> "● Servis kapalı"
            s.isPanelVisible() -> "● Servis açık, panel görünüyor"
            else -> "● Servis açık, panel gizli"
        }
        statusTv.setTextColor(
            if (s == null) Color.parseColor("#FF5252") else Color.parseColor("#00E676")
        )
        val st = getSharedPreferences("bsstat", MODE_PRIVATE)
        eventTv.text = "Son olay: " + (st.getString("ev", null) ?: "-")
        stopTv.text = "Son durma sebebi: " + (st.getString("stop", null) ?: "-")
        val raw = st.getString("reads", "") ?: ""
        readsTv.text = if (raw.isEmpty()) "(henüz okuma yok)" else raw.lines().reversed().joinToString("\n")
    }

    // Kaydı yükler. Geri al için önce anlık görüntü alınır, panel gizliyse hemen açılır.
    private fun loadText(t: String) {
        val res = SlotStore.parse(t)
        val p = res.parsed
        if (p == null) { say("Hata: ${res.error}"); return }
        val svc = ServiceBridge.service
        svc?.prepareExternalLoad()
        val ok = SlotStore.apply(cfg(), p, setPrefs())
        svc?.onExternalLoad()
        svc?.setPanelVisible(true)
        syncFields()
        say(if (ok) "Yüklendi" else "Yükleme başarısız")
        refreshStatus()
    }

    private fun wrapPad(v: View): View = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun rename(n: Int) {
        val et = EditText(this).apply {
            hint = "Kayıt $n"
            setText(SlotStore.recName(recPrefs(), n) ?: "")
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Ad değiştir")
            .setView(wrapPad(et))
            .setPositiveButton("Tamam") { _, _ ->
                SlotStore.recSetName(recPrefs(), n, et.text.toString().trim().take(40))
                refreshRecords()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmDelete(n: Int) {
        if (SlotStore.recLocked(recPrefs(), n)) {
            say("Kilitli, silinemez. Önce kilidi aç (10 sn basılı tut)")
            return
        }
        val name = SlotStore.recName(recPrefs(), n) ?: "Kayıt $n"
        AlertDialog.Builder(this)
            .setTitle("Kayıt silinsin mi?")
            .setMessage("\"$name\" kalıcı olarak silinecek.")
            .setPositiveButton("Evet") { _, _ ->
                if (SlotStore.recDelete(recPrefs(), n)) refreshRecords() else say("Kilitli, silinemez")
            }
            .setNegativeButton("Hayır", null)
            .show()
    }

    // Metinden kayıt ekleme: önce metin, sonra kayıt adı
    private fun importFromText() {
        val cnt = SlotStore.recList(recPrefs()).size
        if (cnt >= SlotStore.MAX_RECORDS) {
            say("Kayıt sınırı dolu ($cnt/${SlotStore.MAX_RECORDS}), önce birini sil")
            return
        }
        val et = EditText(this).apply {
            hint = "${SlotStore.HEADER} ..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6; maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textSize = 12f
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Metinden kayıt ekle")
            .setMessage("Kayıt metnini yapıştır. İlk satır '${SlotStore.HEADER}' ile başlamalı.")
            .setView(wrapPad(et))
            .setPositiveButton("İleri", null)
            .setNegativeButton("İptal", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = et.text.toString().trim()
            val res = SlotStore.parse(text)
            if (res.parsed == null) {
                say("Hata: ${res.error}")
            } else {
                dlg.dismiss()
                askNameAndSave(text)
            }
        }
    }

    private fun askNameAndSave(text: String) {
        val et = EditText(this).apply {
            hint = "Kayıt adı (boşsa otomatik)"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Kayıt adı")
            .setMessage("Bu kayıt nasıl kaydedilsin?")
            .setView(wrapPad(et))
            .setPositiveButton("Kaydet") { _, _ ->
                val (n, added) = SlotStore.recAddUnique(recPrefs(), text)
                val cnt = SlotStore.recList(recPrefs()).size
                when {
                    n < 0 -> say("Kayıt sınırı dolu ($cnt/${SlotStore.MAX_RECORDS})")
                    !added -> say("Zaten kayıtlı (Kayıt $n), tekrar kaydedilmedi")
                    else -> {
                        val name = et.text.toString().trim().take(40)
                        if (name.isNotEmpty()) SlotStore.recSetName(recPrefs(), n, name)
                        say("Kaydedildi (Kayıt $n) ($cnt/${SlotStore.MAX_RECORDS})")
                    }
                }
                refreshRecords()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun refreshRecords() {
        recBox.removeAllViews()
        val list = SlotStore.recList(recPrefs())
        recSec.render()
        if (list.isEmpty()) {
            recBox.addView(txt("Kayıt yok. Panelde Kaydet düğmesine bas ya da metinden ekle.", 13f, SUB))
            return
        }
        for ((n, t) in list) {
            val locked = SlotStore.recLocked(recPrefs(), n)
            val name = SlotStore.recName(recPrefs(), n) ?: "Kayıt $n"
            val title = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            title.addView(txt(name, 15f).apply { typeface = Typeface.DEFAULT_BOLD })
            if (locked) {
                title.addView(txt("   KİLİTLİ", 11f, GOLD).apply { typeface = Typeface.DEFAULT_BOLD })
            }
            recBox.addView(title)

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(6) }
            row1.addView(rowBtn("Yükle", BLUE) { loadText(t) })
            row1.addView(rowBtn("Kopyala", GREYG) { copyText("bsstart-$n", t) })
            row1.addView(rowBtn("Ad", GREYG) { rename(n) })
            recBox.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(6) }
            val lockBtn = if (locked) {
                holdBtn("Kilidi aç (10 sn)", 10_000L, GOLDG) {
                    SlotStore.recSetLock(recPrefs(), n, false)
                    say("Kilit açıldı")
                    refreshRecords()
                }
            } else {
                holdBtn("Kilitle (5 sn)", 5_000L, GREYG) {
                    SlotStore.recSetLock(recPrefs(), n, true)
                    say("Kilitlendi")
                    refreshRecords()
                }
            }
            (lockBtn.layoutParams as LinearLayout.LayoutParams).weight = 2f
            row2.addView(lockBtn)
            row2.addView(rowBtn("Sil", RED) { confirmDelete(n) }.apply { if (locked) alpha = 0.4f })
            recBox.addView(row2)
        }
    }

    private fun syncFields() {
        val sp = setPrefs()
        val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString()
        if (limitEt.text.toString() != l) limitEt.setText(l)
        val scv = sp.getFloat("scale", 1f).coerceIn(SlotStore.MIN_SCALE, SlotStore.MAX_SCALE)
        val cur = scaleEt.text.toString().replace(',', '.').toFloatOrNull()
        if (cur == null || Math.abs(cur - scv) > 0.004f) scaleEt.setText(fmtNum(scv, 2))
        val rv = sp.getFloat("atkrate", SlotStore.DEFAULT_ATK_RATE)
            .coerceIn(SlotStore.MIN_ATK_RATE, SlotStore.MAX_ATK_RATE)
        val rcur = rateEt.text.toString().replace(',', '.').toFloatOrNull()
        if (rcur == null || Math.abs(rcur - rv) > 0.004f) rateEt.setText(fmtNum(rv, 1))
        val av = sp.getInt("aifpct", SlotStore.DEFAULT_AIF).coerceIn(SlotStore.MIN_AIF, SlotStore.MAX_AIF)
        if (aifEt.text.toString() != av.toString()) aifEt.setText(av.toString())
        for ((k, cb) in checks) {
            val on = SlotStore.btnOn(sp, k)
            if (cb.isChecked != on) cb.isChecked = on
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshRecords()
        syncFields()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG1
        window.navigationBarColor = BG1

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        val ht = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
        }
        ht.addView(txt("BS Start", 24f).apply { typeface = Typeface.DEFAULT_BOLD })
        ht.addView(txt("Otomatik döngü paneli", 13f, SUB))
        head.addView(ht)
        col.addView(head)

        // Durum
        val st = Sec("status", "DURUM")
        statusTv = txt("", 16f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, 0) }
        eventTv = txt("", 12f, SUB).apply { setPadding(0, dp(6), 0, 0) }
        stopTv = txt("", 12f, SUB).apply { setPadding(0, dp(2), 0, 0) }
        st.body.addView(statusTv); st.body.addView(eventTv); st.body.addView(stopTv)
        st.body.addView(btn("Paneli göster / gizle") {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else s.setPanelVisible(!s.isPanelVisible())
            refreshStatus()
        })
        st.body.addView(btn("Erişilebilirlik ayarlarını aç", GREYG) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        st.body.addView(btn("Uygulama bilgilerini aç", GREYG) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        col.addView(st.card)

        // Kurulum sırası
        val ord = Sec("order", "KURULUM SIRASI")
        val orderNotes = arrayOf(
            "", "zorunlu", "zorunlu", "", "zorunlu", "zorunlu",
            "karakter değiştirme", "karakter değiştirme, renge bakmaz", "karakter değiştirme, renge bakmaz"
        )
        ord.body.addView(txt("Panelde \"Yuva kaydet\" sıradaki boş yuvayı kaydeder. Bu sırayla kaydet:", 12f, SUB)
            .apply { setPadding(0, dp(6), 0, dp(6)) })
        for (i in 1..8) {
            val note = orderNotes[i]
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(txt("$i", 14f, ACC).apply {
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            line.addView(txt(SlotStore.NAMES[i], 14f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            if (note.isNotEmpty()) line.addView(txt(note, 12f, SUB))
            ord.body.addView(line)
        }
        ord.body.addView(txt("Panelde Renkler listesinden bir yuvayı silebilir ya da değiştirebilirsin. Silinen numara bir sonraki kayıtta tekrar kullanılır.", 12f, SUB)
            .apply { setPadding(0, dp(8), 0, 0) })
        col.addView(ord.card)

        // Ayarlar
        val sc = Sec("settings", "AYARLAR")
        val sp = setPrefs()
        val limitInfo = txt("", 12f, SUB).apply { setPadding(0, dp(8), 0, 0) }
        fun showLimit(msg: String? = null) {
            val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT)
            limitInfo.text = msg ?: "$l ve üstü karakter değiştirir, ${l - 1} ve altı maça girer."
        }
        sc.body.addView(txt("Kupa sınırı (1-3000)", 14f).apply { setPadding(0, dp(6), 0, 0) })
        limitEt = field("1-3000", InputType.TYPE_CLASS_NUMBER).apply {
            setText(sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString())
        }
        sc.body.addView(limitEt)
        sc.body.addView(limitInfo)
        showLimit()
        watch(limitEt) { t ->
            val v = t.trim().toIntOrNull()
            if (v == null || v < SlotStore.MIN_LIMIT || v > SlotStore.MAX_LIMIT) {
                showLimit("${SlotStore.MIN_LIMIT}-${SlotStore.MAX_LIMIT} arası bir sayı gir")
            } else {
                if (sp.getInt("limit", -1) != v) sp.edit().putInt("limit", v).apply()
                showLimit()
            }
        }

        sc.body.addView(txt("Panel boyutu (0.5-1.5)", 14f).apply { setPadding(0, dp(14), 0, 0) })
        scaleEt = decimalField("1", 2).apply {
            setText(fmtNum(sp.getFloat("scale", 1f).coerceIn(SlotStore.MIN_SCALE, SlotStore.MAX_SCALE), 2))
        }
        sc.body.addView(scaleEt)
        val scaleErr = txt("", 12f, Color.parseColor("#FF5252")).apply { setPadding(0, dp(6), 0, 0) }
        sc.body.addView(scaleErr)
        watch(scaleEt) { t ->
            val err = decimalError(t, 2)
            val v = t.trim().toFloatOrNull()
            if (err != null) {
                scaleErr.text = err
            } else if (v == null || v < SlotStore.MIN_SCALE || v > SlotStore.MAX_SCALE) {
                scaleErr.text = "0.5 ile 1.5 arasında bir sayı gir"
            } else {
                scaleErr.text = ""
                val r = Math.round(v * 100f) / 100f
                if (Math.abs(sp.getFloat("scale", 1f) - r) > 0.001f) sp.edit().putFloat("scale", r).apply()
            }
        }

        sc.body.addView(txt("Saldırı basışı (saniyede, 0.1-10)", 14f).apply { setPadding(0, dp(14), 0, 0) })
        rateEt = decimalField("3", 1).apply {
            setText(fmtNum(
                sp.getFloat("atkrate", SlotStore.DEFAULT_ATK_RATE)
                    .coerceIn(SlotStore.MIN_ATK_RATE, SlotStore.MAX_ATK_RATE), 1))
        }
        sc.body.addView(rateEt)
        val rateErr = txt("", 12f, Color.parseColor("#FF5252")).apply { setPadding(0, dp(6), 0, 0) }
        sc.body.addView(rateErr)
        watch(rateEt) { t ->
            val err = decimalError(t, 1)
            val v = t.trim().toFloatOrNull()
            if (err != null) {
                rateErr.text = err
            } else if (v == null || v < SlotStore.MIN_ATK_RATE || v > SlotStore.MAX_ATK_RATE) {
                rateErr.text = "0.1 ile 10 arasında bir sayı gir"
            } else {
                rateErr.text = ""
                val r = Math.round(v * 10f) / 10f
                if (Math.abs(sp.getFloat("atkrate", SlotStore.DEFAULT_ATK_RATE) - r) > 0.001f)
                    sp.edit().putFloat("atkrate", r).apply()
            }
        }

        sc.body.addView(txt("if% eşleşme eşiği (%25-%100)", 14f).apply { setPadding(0, dp(14), 0, 0) })
        aifEt = field("80", InputType.TYPE_CLASS_NUMBER).apply {
            setText(sp.getInt("aifpct", SlotStore.DEFAULT_AIF)
                .coerceIn(SlotStore.MIN_AIF, SlotStore.MAX_AIF).toString())
        }
        sc.body.addView(aifEt)
        val aifErr = txt("", 12f, Color.parseColor("#FF5252")).apply { setPadding(0, dp(6), 0, 0) }
        sc.body.addView(aifErr)
        watch(aifEt) { t ->
            val v = t.trim().toIntOrNull()
            if (v == null || v < SlotStore.MIN_AIF || v > SlotStore.MAX_AIF) {
                aifErr.text = "${SlotStore.MIN_AIF} ile ${SlotStore.MAX_AIF} arasında bir sayı gir"
            } else {
                aifErr.text = ""
                if (sp.getInt("aifpct", -1) != v) sp.edit().putInt("aifpct", v).apply()
            }
        }
        col.addView(sc.card)

        // Panel düğmeleri
        val pb = Sec("buttons", "PANEL DÜĞMELERİ")
        pb.body.addView(txt("İşaretli olanlar panelde görünür. Hepsini kapatırsan sadece küçük şeffaf sürüklenebilir kutu kalır.", 12f, SUB)
            .apply { setPadding(0, dp(6), 0, dp(4)) })
        for ((k, label) in SlotStore.BUTTONS) {
            val cb = CheckBox(this).apply {
                text = label; setTextColor(Color.WHITE)
                isChecked = SlotStore.btnOn(sp, k)
                setOnCheckedChangeListener { _, c ->
                    if (sp.getBoolean("btn_$k", true) != c) sp.edit().putBoolean("btn_$k", c).apply()
                }
            }
            checks.add(k to cb)
            pb.body.addView(cb)
        }
        val allRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
        allRow.addView(rowBtn("Hepsini aç", BLUE) {
            val e = sp.edit(); for ((k, _) in SlotStore.BUTTONS) e.putBoolean("btn_$k", true); e.apply()
            for ((_, cb) in checks) cb.isChecked = true
        })
        allRow.addView(rowBtn("Hepsini kapat", GREYG) {
            val e = sp.edit(); for ((k, _) in SlotStore.BUTTONS) e.putBoolean("btn_$k", false); e.apply()
            for ((_, cb) in checks) cb.isChecked = false
        })
        pb.body.addView(allRow)
        col.addView(pb.card)

        // Son okumalar
        val rd = Sec("reads", "SON OKUMALAR") { "en yeni üstte" }
        readsTv = txt("", 12f, SUB).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
        }
        rd.body.addView(readsTv)
        val rdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
        rdRow.addView(rowBtn("Yenile", GREYG) { refreshStatus() })
        rdRow.addView(rowBtn("Kopyala", GREYG) { copyText("bsstart-okumalar", readsTv.text.toString()) })
        rd.body.addView(rdRow)
        col.addView(rd.card)

        // Kupa tablosu
        val tb = Sec("table", "KUPA TABLOSU")
        tb.body.addView(HorizontalScrollView(this).apply {
            setPadding(0, dp(8), 0, 0)
            addView(TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Color.WHITE)
                text = TrophyTable.asText()
            })
        })
        col.addView(tb.card)

        // Kayıtlar
        recSec = Sec("records", "KAYITLAR") {
            "${SlotStore.recList(recPrefs()).size}/${SlotStore.MAX_RECORDS}"
        }
        recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recSec.body.addView(recBox)
        recSec.body.addView(btn("Metinden kayıt ekle", BLUE) { importFromText() })
        col.addView(recSec.card)

        setContentView(ScrollView(this).apply {
            background = grad(intArrayOf(BG1, BG2), 0, vertical = true)
            addView(col)
        })
    }
}
