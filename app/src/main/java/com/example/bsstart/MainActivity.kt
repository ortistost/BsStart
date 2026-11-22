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
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0E1520")
    private val CARD = Color.parseColor("#172233")
    private val FIELD = Color.parseColor("#0E1520")
    private val ACC = Color.parseColor("#2979FF")
    private val GREY = Color.parseColor("#34445C")
    private val DANGER = Color.parseColor("#C62828")
    private val GREEN = Color.parseColor("#2E7D32")
    private val SUB = Color.parseColor("#9FB0C7")

    private val h = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var eventTv: TextView
    private lateinit var stopTv: TextView
    private lateinit var autoTv: TextView
    private lateinit var autoInfo: TextView
    private lateinit var autoBtn: Button
    private lateinit var readsTv: TextView
    private lateinit var recBox: LinearLayout
    private lateinit var limitEt: EditText
    private lateinit var scaleEt: EditText
    private lateinit var runBtn: Button
    private val checks = ArrayList<Pair<String, CheckBox>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun say(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun shape(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }
    private fun cfg() = getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE)
    private fun recPrefs() = getSharedPreferences(SlotStore.REC, MODE_PRIVATE)
    private fun setPrefs() = getSharedPreferences(SlotStore.SET, MODE_PRIVATE)

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(12)
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(ACC); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    }

    private fun body(t: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
    }

    private fun btn(label: String, color: Int = ACC, act: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = shape(color, 12)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) }
        setOnClickListener { act() }
    }

    private fun rowBtn(label: String, color: Int, act: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 12f
        background = shape(color, 10)
        minHeight = 0; minimumHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
        setOnClickListener { act() }
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type
        setTextColor(Color.WHITE); setHintTextColor(SUB)
        background = shape(FIELD, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setSingleLine(true)
        layoutParams = lp(6)
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

    private fun autoOn() = SlotStore.autoTrophy(setPrefs(), cfg())

    private fun refreshAuto() {
        val on = autoOn()
        val lim = setPrefs().getInt("limit", SlotStore.DEFAULT_LIMIT)
        autoTv.text = "Otomatik kupa: " + if (on) "AÇIK" else "kapalı"
        autoTv.setTextColor(if (on) Color.parseColor("#00E676") else Color.WHITE)
        autoBtn.text = if (on) "Kapat" else "Aç"
        autoBtn.background = shape(if (on) DANGER else GREEN, 12)
        autoInfo.text = if (on)
            "Karakter kupası $lim ve üstüne çıkınca Savaşçılar → Karakter → Seç yapılır. ${lim - 1} ve altında karakter değişmez."
        else
            "Kapalıyken karakter hiç değişmez, sadece maç döngüsü çalışır."
    }

    private fun toggleAuto() {
        val sp = setPrefs()
        if (autoOn()) {
            sp.edit().putBoolean("atrophy", false).apply()
            say("Otomatik kupa kapatıldı")
        } else {
            val parsed = SlotStore.parse(SlotStore.PRESET).parsed
            val n = if (parsed != null) SlotStore.fillMissing(cfg(), parsed, sp) else 0
            sp.edit().putBoolean("atrophy", true).apply()
            if (n > 0) ServiceBridge.service?.onExternalLoad()
            syncFields()
            say(if (n > 0) "Otomatik kupa açıldı, hazır ayardan $n öğe eklendi" else "Otomatik kupa açıldı")
        }
        refreshAuto()
    }

    private fun loadPreset() {
        AlertDialog.Builder(this)
            .setTitle("Hazır ayarı baştan yükle")
            .setMessage("Mevcut yuvalar, okuma alanı ve ayarlar silinip hazır ayarla değiştirilir. Devam edilsin mi?")
            .setPositiveButton("Evet") { _, _ ->
                val parsed = SlotStore.parse(SlotStore.PRESET).parsed
                if (parsed == null) { say("Hazır ayar okunamadı"); return@setPositiveButton }
                SlotStore.apply(cfg(), parsed, setPrefs())
                setPrefs().edit().putBoolean("atrophy", true).apply()
                ServiceBridge.service?.onExternalLoad()
                syncFields()
                refreshAuto()
                say("Hazır ayar yüklendi, otomatik kupa açık")
            }
            .setNegativeButton("İptal", null)
            .show()
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
        runBtn.text = if (s != null && s.isRunning()) "Döngüyü durdur" else "Döngüyü başlat"
        val st = getSharedPreferences("bsstat", MODE_PRIVATE)
        eventTv.text = "Son olay: " + (st.getString("ev", null) ?: "-")
        stopTv.text = "Son durma sebebi: " + (st.getString("stop", null) ?: "-")
        val raw = st.getString("reads", "") ?: ""
        readsTv.text = if (raw.isEmpty()) "(henüz okuma yok)" else raw.lines().reversed().joinToString("\n")
    }

    private fun loadText(t: String) {
        val res = SlotStore.parse(t)
        val p = res.parsed
        if (p == null) { say("Hata: ${res.error}"); return }
        val ok = SlotStore.apply(cfg(), p, setPrefs())
        ServiceBridge.service?.onExternalLoad()
        syncFields()
        refreshAuto()
        say(if (ok) "Yüklendi" else "Yükleme başarısız")
        refreshStatus()
    }

    private fun rename(n: Int) {
        val et = EditText(this).apply {
            hint = "Kayıt $n"
            setText(SlotStore.recName(recPrefs(), n) ?: "")
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Ad değiştir")
            .setView(et)
            .setPositiveButton("Tamam") { _, _ ->
                SlotStore.recSetName(recPrefs(), n, et.text.toString().trim())
                refreshRecords()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun refreshRecords() {
        recBox.removeAllViews()
        val list = SlotStore.recList(recPrefs())
        if (list.isEmpty()) {
            recBox.addView(body("Kayıt yok. Panelde Kaydet düğmesine bas.", 13f, SUB))
            return
        }
        for ((n, t) in list) {
            val name = SlotStore.recName(recPrefs(), n) ?: "Kayıt $n"
            recBox.addView(body(name, 15f).apply {
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutParams = lp(6)
            }
            row.addView(rowBtn("Yükle", ACC) { loadText(t) })
            row.addView(rowBtn("Kopyala", GREY) { copyText("bsstart-$n", t) })
            row.addView(rowBtn("Ad", GREY) { rename(n) })
            row.addView(rowBtn("Sil", DANGER) { SlotStore.recDelete(recPrefs(), n); refreshRecords() })
            recBox.addView(row)
        }
    }

    private fun syncFields() {
        val sp = setPrefs()
        val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString()
        if (limitEt.text.toString() != l) limitEt.setText(l)
        val scv = sp.getFloat("scale", 1f).coerceIn(SlotStore.MIN_SCALE, SlotStore.MAX_SCALE)
        val cur = scaleEt.text.toString().replace(',', '.').toFloatOrNull()
        if (cur == null || Math.abs(cur - scv) > 0.004f)
            scaleEt.setText(String.format(Locale.US, "%.2f", scv))
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
        refreshAuto()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

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
        ht.addView(body("BS Start", 24f).apply { typeface = Typeface.DEFAULT_BOLD })
        ht.addView(body("Otomatik döngü paneli", 13f, SUB))
        head.addView(ht)
        col.addView(head)

        // Durum
        val st = card()
        st.addView(heading("DURUM"))
        statusTv = body("", 16f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, 0) }
        eventTv = body("", 12f, SUB).apply { setPadding(0, dp(6), 0, 0) }
        stopTv = body("", 12f, SUB).apply { setPadding(0, dp(2), 0, 0) }
        st.addView(statusTv); st.addView(eventTv); st.addView(stopTv)
        runBtn = btn("Döngüyü başlat", GREEN) {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else s.toggleLoopExternal()
            h.postDelayed({ refreshStatus() }, 400)
        }
        st.addView(runBtn)
        st.addView(btn("Paneli göster / gizle") {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else s.setPanelVisible(!s.isPanelVisible())
            refreshStatus()
        })
        st.addView(btn("Erişilebilirlik ayarlarını aç", GREY) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        st.addView(btn("Uygulama bilgilerini aç", GREY) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        col.addView(st)

        // Otomatik kupa
        val ac = card()
        ac.addView(heading("OTOMATİK KUPA"))
        val arow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(8)
        }
        autoTv = body("Otomatik kupa", 15f).apply {
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        autoBtn = Button(this).apply {
            isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(44))
            setOnClickListener { toggleAuto() }
        }
        arow.addView(autoTv); arow.addView(autoBtn)
        ac.addView(arow)
        autoInfo = body("", 12f, SUB).apply { setPadding(0, dp(8), 0, 0) }
        ac.addView(autoInfo)
        ac.addView(btn("Hazır ayarı baştan yükle", GREY) { loadPreset() })
        col.addView(ac)

        // Ayarlar
        val sc = card()
        sc.addView(heading("AYARLAR"))
        val sp = setPrefs()
        val limitInfo = body("", 12f, SUB).apply { setPadding(0, dp(8), 0, 0) }
        fun showLimit(msg: String? = null) {
            val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT)
            limitInfo.text = msg ?: "$l ve üstü karakter değiştirir, ${l - 1} ve altı maça girer."
        }
        sc.addView(body("Kupa sınırı (1-3000)", 14f).apply { setPadding(0, dp(6), 0, 0) })
        limitEt = field("1-3000", InputType.TYPE_CLASS_NUMBER).apply {
            setText(sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString())
        }
        sc.addView(limitEt)
        sc.addView(limitInfo)
        showLimit()
        watch(limitEt) { t ->
            val v = t.trim().toIntOrNull()
            if (v == null || v < SlotStore.MIN_LIMIT || v > SlotStore.MAX_LIMIT) {
                showLimit("${SlotStore.MIN_LIMIT}-${SlotStore.MAX_LIMIT} arası bir sayı gir")
            } else {
                if (sp.getInt("limit", -1) != v) sp.edit().putInt("limit", v).apply()
                showLimit()
                refreshAuto()
            }
        }

        sc.addView(body("Panel boyutu (0.6-1.25)", 14f).apply { setPadding(0, dp(14), 0, 0) })
        scaleEt = field("1.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText(String.format(Locale.US, "%.2f",
                sp.getFloat("scale", 1f).coerceIn(SlotStore.MIN_SCALE, SlotStore.MAX_SCALE)))
        }
        sc.addView(scaleEt)
        val scaleErr = body("", 12f, Color.parseColor("#FF5252")).apply { setPadding(0, dp(6), 0, 0) }
        sc.addView(scaleErr)
        watch(scaleEt) { t ->
            val v = t.trim().replace(',', '.').toFloatOrNull()
            if (v == null || v < SlotStore.MIN_SCALE || v > SlotStore.MAX_SCALE) {
                scaleErr.text = "0.6 ile 1.25 arasında bir sayı gir"
            } else {
                scaleErr.text = ""
                val r = Math.round(v * 100f) / 100f
                if (Math.abs(sp.getFloat("scale", 1f) - r) > 0.001f) sp.edit().putFloat("scale", r).apply()
            }
        }
        col.addView(sc)

        // Panel düğmeleri
        val pb = card()
        pb.addView(heading("PANEL DÜĞMELERİ"))
        pb.addView(body("İşaretli olanlar panelde görünür. Hepsini kapatırsan sadece küçük şeffaf sürüklenebilir kutu kalır.", 12f, SUB)
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
            pb.addView(cb)
        }
        val allRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
        allRow.addView(rowBtn("Hepsini aç", ACC) {
            val e = sp.edit(); for ((k, _) in SlotStore.BUTTONS) e.putBoolean("btn_$k", true); e.apply()
            for ((_, cb) in checks) cb.isChecked = true
        })
        allRow.addView(rowBtn("Hepsini kapat", GREY) {
            val e = sp.edit(); for ((k, _) in SlotStore.BUTTONS) e.putBoolean("btn_$k", false); e.apply()
            for ((_, cb) in checks) cb.isChecked = false
        })
        pb.addView(allRow)
        col.addView(pb)

        // Son okumalar
        val rd = card()
        rd.addView(heading("SON OKUMALAR (en yeni üstte)"))
        readsTv = body("", 12f, SUB).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
        }
        rd.addView(readsTv)
        rd.addView(btn("Yenile", GREY) { refreshStatus() })
        col.addView(rd)

        // Kupa tablosu
        val tb = card()
        tb.addView(heading("KUPA TABLOSU"))
        tb.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
            text = "Kupa        Kazanma   Kaybetme\n" +
                "0–49        +10       0\n" +
                "50–299      +10       −1\n" +
                "300–599     +10       −2\n" +
                "600–799     +10       −3\n" +
                "800–999     +10       −4\n" +
                "1000-1099   +10       −5"
        })
        col.addView(tb)

        // Kayıtlar
        val rc = card()
        rc.addView(heading("KAYITLAR"))
        recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rc.addView(recBox)
        col.addView(rc)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(col)
        })
    }
}
