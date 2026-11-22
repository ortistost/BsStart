package com.example.bsstart

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var recBox: LinearLayout
    private lateinit var out: TextView

    private fun say(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copyText(label: String, t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, t))
        say("Kopyalandı")
    }

    private fun recPrefs() = getSharedPreferences(SlotStore.REC, MODE_PRIVATE)

    private fun plainBtn(label: String, act: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { act() }
    }

    private fun rowBtn(label: String, act: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { act() }
    }

    private fun refreshStatus() {
        val s = ServiceBridge.service
        status.text = when {
            s == null -> "Servis kapalı: Erişilebilirlik ayarlarından BS Start'ı aç."
            s.isPanelVisible() -> "Panel görünüyor."
            else -> "Panel gizli."
        }
    }

    // Metni aktif yuvalara yükler, servise haber verir
    private fun loadText(t: String): String {
        val res = SlotStore.parse(t)
        val p = res.parsed ?: return "Hata: ${res.error}"
        val prefs = getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE)
        val sets = getSharedPreferences(SlotStore.SET, MODE_PRIVATE)
        val ok = SlotStore.apply(prefs, p, sets)
        ServiceBridge.service?.onExternalLoad()
        val back = SlotStore.format(prefs, p.screen, if (p.set != null) sets else null) ?: ""
        val same = SlotStore.norm(back) == SlotStore.norm(t)
        return (if (!ok) "Kayıt başarısız!\n" else "") +
            (if (same) "AYNI ✓ (yüklendi, tekrar dışa aktarım birebir aynı)\n\n"
            else "FARK VAR ✗ (aşağıdaki metin yüklenen halidir)\n\n") + back
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
            recBox.addView(TextView(this).apply { text = "(kayıt yok. Panelde Kaydet düğmesine bas)" })
            return
        }
        for ((n, t) in list) {
            val scr = Regex("screen=(\\S+)").find(t)?.groupValues?.get(1) ?: "?"
            val name = SlotStore.recName(recPrefs(), n) ?: "Kayıt $n"
            recBox.addView(TextView(this).apply { text = "$name  (ekran $scr)"; textSize = 15f })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(rowBtn("Yükle") { out.text = loadText(t); refreshStatus() })
            row.addView(rowBtn("Kopyala") { copyText("bsstart-$n", t) })
            row.addView(rowBtn("Ad") { rename(n) })
            row.addView(rowBtn("Sil") { SlotStore.recDelete(recPrefs(), n); refreshRecords() })
            recBox.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshRecords()
    }

    private fun addSettings(col: LinearLayout) {
        val sp = getSharedPreferences(SlotStore.SET, MODE_PRIVATE)

        val limitInfo = TextView(this)
        fun showLimit() {
            val l = sp.getInt("limit", SlotStore.DEFAULT_LIMIT)
            limitInfo.text = "Kupa sınırı: $l. $l ve üstü karakter değiştirir, ${l - 1} ve altı maça girer."
        }
        showLimit()
        val limitInput = EditText(this).apply {
            hint = "Kupa sınırı (${SlotStore.MIN_LIMIT}-${SlotStore.MAX_LIMIT})"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("limit", SlotStore.DEFAULT_LIMIT).toString())
        }
        col.addView(limitInfo)
        col.addView(limitInput)
        col.addView(plainBtn("Kupa sınırını kaydet") {
            val v = limitInput.text.toString().trim().toIntOrNull()
            if (v == null || v < SlotStore.MIN_LIMIT || v > SlotStore.MAX_LIMIT) {
                say("${SlotStore.MIN_LIMIT}-${SlotStore.MAX_LIMIT} arası bir sayı gir")
            } else {
                sp.edit().putInt("limit", v).apply()
                showLimit()
            }
        })

        val scaleLabel = TextView(this)
        val cur = sp.getFloat("scale", 1f).coerceIn(0.5f, 2f)
        scaleLabel.text = String.format(Locale.US, "Panel boyutu: x%.2f (bırakınca uygulanır)", cur)
        val bar = SeekBar(this).apply {
            max = 150
            progress = Math.round((cur - 0.5f) * 100f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    scaleLabel.text = String.format(Locale.US, "Panel boyutu: x%.2f (bırakınca uygulanır)", 0.5f + p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val p = s?.progress ?: 50
                    sp.edit().putFloat("scale", 0.5f + p / 100f).apply()
                }
            })
        }
        col.addView(scaleLabel)
        col.addView(bar)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        col.addView(status)
        col.addView(plainBtn("Paneli göster / gizle") {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else s.setPanelVisible(!s.isPanelVisible())
            refreshStatus()
        })
        col.addView(plainBtn("Erişilebilirlik ayarlarını aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(plainBtn("Uygulama bilgilerini aç") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        addSettings(col)

        col.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
            text = "Kupa        Kazanma   Kaybetme\n" +
                "0–49        +10       0\n" +
                "50–299      +10       −1\n" +
                "300–599     +10       −2\n" +
                "600–799     +10       −3\n" +
                "800–999     +10       −4\n" +
                "1000-1099   +10       −5"
        })

        col.addView(TextView(this).apply { text = "KAYITLAR"; textSize = 15f })
        recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recBox)

        col.addView(TextView(this).apply { text = "\nMETİNDEN YÜKLE"; textSize = 15f })
        val input = EditText(this).apply {
            hint = "Kopyalanan metni buraya yapıştır"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        out = TextView(this)
        col.addView(input)
        col.addView(plainBtn("Yükle (aktif yap)") {
            val txt = input.text.toString()
            val first = SlotStore.splitBlocks(txt).firstOrNull() ?: txt
            out.text = loadText(first)
            refreshStatus()
        })
        col.addView(plainBtn("Kayıtlara ekle") {
            val blocks = SlotStore.splitBlocks(input.text.toString())
            var added = 0
            var dup = 0
            var bad = 0
            for (b in blocks) {
                if (SlotStore.parse(b).parsed == null) { bad++; continue }
                if (SlotStore.recAddUnique(recPrefs(), b).second) added++ else dup++
            }
            out.text = if (blocks.isEmpty()) "Hata: metinde '${SlotStore.HEADER}' başlığı yok"
            else "$added kayıt eklendi" +
                (if (dup > 0) ", $dup tanesi zaten kayıtlıydı" else "") +
                (if (bad > 0) ", $bad geçersiz atlandı" else "")
            refreshRecords()
        })
        col.addView(out)

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
