package com.example.bsstart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private fun norm(s: String) =
        s.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n").lowercase(Locale.ROOT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "1) Erişilebilirlik ayarlarını aç, BS Start'ı aç.\n" +
                "2) Oyunda panelden yuvaları kalibre et.\n" +
                "3) Mor butonla kopyalanan metni aşağıya yapıştırıp OK'e basarsan aynı yuvalar yüklenir.\n\n" +
                "Android 13+ ve APK'yı dışarıdan yüklediysen izin gri olabilir: " +
                "Ayarlar > Uygulamalar > BS Start > sağ üst ⋮ > Kısıtlanmış ayarlara izin ver."
        })
        col.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        val input = EditText(this).apply {
            hint = "Kopyalanan yuva metnini buraya yapıştır"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        val out = TextView(this)
        col.addView(input)
        col.addView(Button(this).apply {
            text = "OK"
            setOnClickListener {
                val txt = input.text.toString()
                val res = SlotStore.parse(txt)
                val parsed = res.parsed
                if (parsed == null) {
                    out.text = "Hata: ${res.error}"
                    return@setOnClickListener
                }
                val prefs = getSharedPreferences(SlotStore.PREFS, MODE_PRIVATE)
                val saved = SlotStore.apply(prefs, parsed)
                val back = SlotStore.format(prefs, parsed.screen) ?: ""
                val same = norm(back) == norm(txt)
                out.text = (if (!saved) "Kayıt başarısız!\n" else "") +
                    (if (same) "AYNI ✓ (yüklendi, tekrar dışa aktarım birebir aynı)\n\n"
                    else "FARK VAR ✗ (aşağıdaki metin yüklenen halidir)\n\n") + back
            }
        })
        col.addView(out)
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
