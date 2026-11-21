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
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private fun norm(s: String) =
        s.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n").lowercase(Locale.ROOT)

    private fun dot(color: String) = "<font color='$color'>●</font>"

    private fun guideHtml(): String =
        "<b>PANEL BUTONLARI (yukarıdan aşağı)</b><br>" +
        "${dot("#FF9800")} <b>Taşı</b> (dört yönlü ok): sürükle. Kısa dokunma sıradaki yuvaya atlar.<br>" +
        "${dot("#E53935")} <b>Başlat / Durdur</b> (▶ / ■): döngü. Yeşil = çalışıyor.<br>" +
        "${dot("#1E88E5")} <b>Yuva kaydet</b> (+): sıradaki yuvanın düğmesine dokunursun, konum ve renk kaydedilir.<br>" +
        "${dot("#B71C1C")} <b>Hepsini sil</b> (−): her şeyi onaysız siler. Yanlışsa Geri al.<br>" +
        "${dot("#607D8B")} <b>Geri al</b>: + / − / Alan seç işlemlerinin sonuncusunu geri alır (tek seviye).<br>" +
        "${dot("#9E9E9E")} <b>Halkalar</b> (göz): halkaları ve OKU çerçevesini gizle/göster.<br>" +
        "${dot("#8E24AA")} <b>Kopyala</b> (pano): yuvaları panoya kopyalar.<br>" +
        "${dot("#00ACC1")} <b>Alan seç</b> (ekran): kupa alanı, sol üst sonra sağ alt köşe. Uzun bas = detaylı test. " +
        "Seçim sırasında panelde herhangi bir butona basarsan iptal olur.<br>" +
        "${dot("#FDD835")} <b>Test oku</b> (?): çerçevede OKU (sayı) veya OKU (Yok) yazar.<br>" +
        "${dot("#000000")} <b>Gizle</b> (kırmızı X): döngüyü durdurur, paneli gizler. Geri açmak için uygulamadaki düğme.<br><br>" +

        "<b>HALKA RENKLERİ</b><br>" +
        "${dot("#43A047")} 1 Maça gir &nbsp; ${dot("#E53935")} 2 Saldırı &nbsp; ${dot("#1E88E5")} 3 Devam<br>" +
        "${dot("#8E24AA")} 4 Devam &nbsp; ${dot("#FB8C00")} 5 Bitir &nbsp; ${dot("#00ACC1")} 6 Savaşçılar<br>" +
        "${dot("#F06292")} 7 Karakter seç &nbsp; ${dot("#A1887F")} 8 Seç<br>" +
        "Beyaz çerçeve + OKU = kupa okuma alanı.<br><br>" +

        "<b>KURULUM SIRASI (oyun içinde)</b><br>" +
        "1. Erişilebilirlik ayarlarından BS Start'ı aç.<br>" +
        "2. Koyu kırmızı − ile eski kayıtları sil.<br>" +
        "3. Lobide Alan seç: kupa sayısının <b>sol üst</b>, sonra <b>sağ alt</b> köşesi.<br>" +
        "4. Test oku (?): OKU (sayı) doğru mu bak.<br>" +
        "5. Mavi + ile sırayla her seferinde düğmeye dokun:<br>" +
        "&nbsp;&nbsp;<b>1</b> Maça gir (lobide)<br>" +
        "&nbsp;&nbsp;<b>2</b> Saldırı (maç içinde)<br>" +
        "&nbsp;&nbsp;<b>3</b> Devam (maç sonu)<br>" +
        "&nbsp;&nbsp;<b>4</b> Devam (sonraki ekran)<br>" +
        "&nbsp;&nbsp;<b>5</b> Bitir (ana ekrana dönen düğme)<br>" +
        "&nbsp;&nbsp;<b>6</b> Savaşçılar (karakter ekranına gir)<br>" +
        "&nbsp;&nbsp;<b>7</b> Karakter seç (listedeki kart)<br>" +
        "&nbsp;&nbsp;<b>8</b> Seç (ana ekrana atar)<br>" +
        "6. Göz ile halkaları gizle, paneli kupa alanından uzağa taşı.<br>" +
        "7. Başlat'a bas, yeşil ■ olunca bırak. Durdurmak için ■ veya Gizle.<br><br>" +

        "<b>DÖNGÜ SIRASI</b><br>" +
        "Kupayı oku. 950'den azsa: 1 → 2 → 3 → 4 → 5, başa dön. " +
        "950 veya fazlaysa: 6 → (5 sn) 7 → (5 sn) 8, kupayı tekrar oku. " +
        "950'den azsa döngüye devam, hâlâ 950 veya fazlaysa dur.<br><br>"

    private lateinit var status: TextView
    private lateinit var logView: TextView

    private fun refreshStatus() {
        val s = ServiceBridge.service
        status.text = when {
            s == null -> "Servis kapalı: Erişilebilirlik ayarlarından BS Start'ı aç."
            s.isPanelVisible() -> "Panel görünüyor."
            else -> "Panel gizli."
        }
    }

    private fun refreshLog() {
        val raw = getSharedPreferences("bslog", MODE_PRIVATE).getString("l", "") ?: ""
        logView.text = if (raw.isEmpty()) "(kayıt yok)" else raw.lines().reversed().joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
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
        col.addView(Button(this).apply {
            text = "Paneli göster / gizle"
            setOnClickListener {
                val s = ServiceBridge.service
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Servis kapalı, önce erişilebilirliği aç", Toast.LENGTH_SHORT).show()
                } else {
                    s.setPanelVisible(!s.isPanelVisible())
                }
                refreshStatus()
            }
        })
        col.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(TextView(this).apply {
            text = "Android 13+ ve APK'yı dışarıdan yüklediysen izin gri olabilir: " +
                "Ayarlar > Uygulamalar > BS Start > sağ üst ⋮ > Kısıtlanmış ayarlara izin ver.\n"
        })

        col.addView(TextView(this).apply {
            text = Html.fromHtml(guideHtml(), Html.FROM_HTML_MODE_LEGACY)
            textSize = 15f
        })

        col.addView(TextView(this).apply { text = "KAYIT DEFTERİ (en yeni üstte)"; textSize = 15f })
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        col.addView(Button(this).apply {
            text = "Kayıtları yenile"
            setOnClickListener { refreshLog() }
        })
        col.addView(Button(this).apply {
            text = "Kayıtları sil"
            setOnClickListener {
                getSharedPreferences("bslog", MODE_PRIVATE).edit().clear().apply()
                refreshLog()
            }
        })
        col.addView(logView)

        col.addView(TextView(this).apply { text = "\nYUVA YEDEĞİ"; textSize = 15f })
        val input = EditText(this).apply {
            hint = "Kopyalanan yuva metnini buraya yapıştır"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
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
