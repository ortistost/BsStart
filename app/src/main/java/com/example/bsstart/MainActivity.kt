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
    private val SUB = Color.parseColor("#9FB0C7")

    private val h = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var eventTv: TextView
    private lateinit var stopTv: TextView
    private lateinit var recBox: LinearLayout
    private lateinit var limitEt: EditText
    private lateinit var scaleEt: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun say(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun shape(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }
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

    private fun refreshStatus() {
        val s = ServiceBridge.service
        statusTv.text = when {
            s == null -> "● Servis kapalı"
            s.isPanelVisible() -> "● Servis açık, panel görünüyor"
            else -> "● Servis açık, panel gizli"
        }
        statusTv.setTextColor(
            if (s == null) Color.parseColor("#FF5252")
            else Color.parseColor("#00E676")
        )
        val st = getSharedPreferences("bsstat", MODE_PRIVATE)
        eventTv.text = "Son olay: " + (st.getString("ev", null) ?: "-")
        stopTv.text = "Son durma sebebi: " + (st.getString("stop", null) ?: "-")
    }

    private fun loadText(t: String) {
        val res = SlotStore.parse(t)
        val p = res.parsed
        if (p == null) { say("Hata: ${res.error}"); return }
        val prefs = getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE)
        val ok = SlotStore.apply(prefs, p, setPrefs())
        ServiceBridge.service?.onExternalLoad()
        syncFields()
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
            val scr = Regex("screen=(\\S+)").find(t)?.groupValues?.get(1) ?: "?"
            val name = SlotStore.recName(recPrefs(), n) ?: "Kayıt $n"
            recBox.addView(body(name, 15f).apply {
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0)
            })
            recBox.addView(body("ekran $scr", 12f, SUB))
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

    // Kayıttan yükleme ayarları değiştirince alanları günceller
    private fun syncFields() {
        val sp = setPrefs()
        val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString()
        if (limitEt.text.toString() != l) limitEt.setText(l)
        val sc = String.format(Locale.US, "%.2f", sp.getFloat("scale", 1f))
        val cur = scaleEt.text.toString().replace(',', '.').toFloatOrNull()
        if (cur == null || Math.abs(cur - sp.getFloat("scale", 1f)) > 0.004f) scaleEt.setText(sc)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshRecords()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        // Başlık
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

        // Ayarlar (kaydet düğmesi yok, yazınca uygulanır)
        val sc = card()
        sc.addView(heading("AYARLAR"))
        val sp = setPrefs()
        val limitInfo = body("", 12f, SUB).apply { setPadding(0, dp(8), 0, 0) }
        fun showLimit(msg: String? = null) {
            val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT)
            limitInfo.text = msg ?: "$l ve üstü karakter değiştirir, ${l - 1} ve altı maça girer."
        }
        sc.addView(body("Kupa sınırı", 14f).apply { setPadding(0, dp(6), 0, 0) })
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
            }
        }

        sc.addView(body("Panel boyutu (0,5 - 2)", 14f).apply { setPadding(0, dp(14), 0, 0) })
        scaleEt = field("1.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            setText(String.format(Locale.US, "%.2f", sp.getFloat("scale", 1f)))
        }
        sc.addView(scaleEt)
        val scaleInfo = body("1 = varsayılan boyut. Geçerli değer yazınca panel yeniden kurulur.", 12f, SUB)
            .apply { setPadding(0, dp(8), 0, 0) }
        sc.addView(scaleInfo)
        watch(scaleEt) { t ->
            val v = t.trim().replace(',', '.').toFloatOrNull()
            if (v == null || v < 0.5f || v > 2f) {
                scaleInfo.text = "0,5 ile 2 arasında bir sayı gir"
            } else {
                val r = Math.round(v * 100f) / 100f
                if (Math.abs(sp.getFloat("scale", 1f) - r) > 0.001f) sp.edit().putFloat("scale", r).apply()
                scaleInfo.text = "Panel boyutu x$r uygulandı."
            }
        }
        col.addView(sc)

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
