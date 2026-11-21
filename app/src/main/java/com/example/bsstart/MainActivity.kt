package com.example.bsstart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Html
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

    private fun dot(color: String) = "<font color='$color'>●</font>"

    private fun guideHtml(): String =
        "<b>PANEL BUTONLARI (yukarıdan aşağı)</b><br>" +
        "${dot("#FF9800")} <b>Turuncu:</b> sürükle. Kısa dokunma sıradaki yuvaya atlar.<br>" +
        "${dot("#E53935")} <b>Kırmızı (büyük):</b> döngüyü aç/kapat. Yeşil = çalışıyor.<br>" +
        "${dot("#1E88E5")} <b>Mavi:</b> kalibrasyon, sıradaki yuvayı kaydeder. Uzun bas = her şeyi sil.<br>" +
        "${dot("#9E9E9E")} <b>Gri:</b> halkaları ve OKU çerçevesini gizle/göster.<br>" +
        "${dot("#8E24AA")} <b>Mor:</b> yuvaları panoya kopyala.<br>" +
        "${dot("#00ACC1")} <b>Turkuaz:</b> kupa alanını seç (sol üst, sonra sağ alt). Uzun bas = detaylı test.<br>" +
        "${dot("#FDD835")} <b>Sarı:</b> okumayı dene. Çerçevede OKU (sayı) veya OKU (Yok) yazar, 3 sn sonra OKU olur.<br>" +
        "${dot("#000000")} <b>Siyah:</b> servisi tamamen kapat.<br><br>" +

        "<b>HALKA RENKLERİ (ekrandaki numaralar)</b><br>" +
        "${dot("#43A047")} 1 Maça gir &nbsp; ${dot("#E53935")} 2 Saldırı &nbsp; ${dot("#1E88E5")} 3 Devam<br>" +
        "${dot("#8E24AA")} 4 Devam &nbsp; ${dot("#FB8C00")} 5 Bitir &nbsp; ${dot("#00ACC1")} 6 Savaşçılar<br>" +
        "${dot("#F06292")} 7 Karakter seç &nbsp; ${dot("#A1887F")} 8 Seç<br>" +
        "Beyaz çerçeve + OKU = kupa okuma alanı.<br><br>" +

        "<b>KURULUM SIRASI (oyun içinde)</b><br>" +
        "1. Erişilebilirlik ayarlarından BS Start'ı aç.<br>" +
        "2. Mavi butona <b>uzun bas</b>: eski kayıtlar silinir.<br>" +
        "3. Lobide turkuaz butona bas: kupa sayısının <b>sol üst</b>, sonra <b>sağ alt</b> köşesine dokun.<br>" +
        "4. Sarı butona bas, OKU (sayı) doğru mu bak.<br>" +
        "5. Mavi butona bas, sırayla her seferinde düğmeye dokun:<br>" +
        "&nbsp;&nbsp;<b>1</b> Maça gir (lobide)<br>" +
        "&nbsp;&nbsp;<b>2</b> Saldırı (maç içinde)<br>" +
        "&nbsp;&nbsp;<b>3</b> Devam (maç sonu)<br>" +
        "&nbsp;&nbsp;<b>4</b> Devam (sonraki ekran)<br>" +
        "&nbsp;&nbsp;<b>5</b> Bitir (ana ekrana dönen düğme)<br>" +
        "&nbsp;&nbsp;<b>6</b> Savaşçılar (karakter ekranına gir)<br>" +
        "&nbsp;&nbsp;<b>7</b> Karakter seç (listedeki kart)<br>" +
        "&nbsp;&nbsp;<b>8</b> Seç (ana ekrana atar)<br>" +
        "6. İstersen mor butonla kopyala, aşağıya yapıştırıp OK'e bas (yedek).<br>" +
        "7. Gri butonla halkaları gizle, paneli kupa alanından uzağa sürükle.<br>" +
        "8. Kırmızıya bas, yeşil olunca bırak. Durdurmak için siyah.<br><br>" +

        "<b>DÖNGÜ SIRASI</b><br>" +
        "Kupayı oku. 950'den azsa: 1 → 2 → 3 → 4 → 5, başa dön. " +
        "950 veya fazlaysa: 6 → (10 sn) 7 → (10 sn) 8, kupayı tekrar oku. " +
        "950'den azsa döngüye devam, hâlâ 950 veya fazlaysa dur.<br><br>"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = Html.fromHtml(guideHtml(), Html.FROM_HTML_MODE_LEGACY)
            textSize = 15f
        })
        col.addView(TextView(this).apply {
            text = "Android 13+ ve APK'yı dışarıdan yüklediysen izin gri olabilir: " +
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
