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
import android.widget.SeekBar
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
        "${dot("#607D8B")} <b>Geri al</b>: + / − / alan seçimi işlemlerinin sonuncusunu geri alır (tek seviye).<br>" +
        "${dot("#9E9E9E")} <b>Halkalar</b> (göz): halkaları ve çerçeveleri gizle/göster.<br>" +
        "${dot("#8E24AA")} <b>Kopyala</b> (pano): yuvaları ve alanları panoya kopyalar.<br>" +
        "${dot("#00ACC1")} <b>Alan seç</b> (ekran): karakter kupası alanı, sol üst sonra sağ alt köşe. Uzun bas = detaylı test. " +
        "Seçim sırasında panelde herhangi bir butona basarsan iptal olur.<br>" +
        "${dot("#FDD835")} <b>Test oku</b> (?): çerçevede OKU (sayı) veya OKU (Yok) yazar.<br>" +
        "${dot("#FFB300")} <b>Toplam</b> (kupa): ilk basış toplam kupa alanını seçtirir (sol üst, sağ alt). " +
        "Sonraki basışlar takibi açar/kapatır, uzun basış alanı yeniden seçtirir. " +
        "Açıkken ekranın üstünde [başlangıç → şimdi (+fark)] görünür.<br>" +
        "${dot("#000000")} <b>Gizle</b> (kırmızı X): döngüyü durdurur, paneli gizler. Geri açmak için uygulamadaki düğme. " +
        "Tekrar açınca toplam kupa farkı (+0)'dan başlar.<br><br>" +

        "<b>HALKA RENKLERİ</b><br>" +
        "${dot("#43A047")} 1 Maça gir &nbsp; ${dot("#E53935")} 2 Saldırı &nbsp; ${dot("#1E88E5")} 3 Devam<br>" +
        "${dot("#8E24AA")} 4 Devam &nbsp; ${dot("#FB8C00")} 5 Bitir &nbsp; ${dot("#00ACC1")} 6 Savaşçılar<br>" +
        "${dot("#F06292")} 7 Karakter seç &nbsp; ${dot("#A1887F")} 8 Seç<br>" +
        "Beyaz çerçeve = karakter kupası alanı, sarı çerçeve = toplam kupa alanı.<br><br>" +

        "<b>KURULUM SIRASI (oyun içinde)</b><br>" +
        "1. Erişilebilirlik ayarlarından BS Start'ı aç.<br>" +
        "2. Koyu kırmızı − ile eski kayıtları sil.<br>" +
        "3. Lobide Alan seç: karakter kupasının <b>sol üst</b>, sonra <b>sağ alt</b> köşesi.<br>" +
        "4. Test oku (?): OKU (sayı) doğru mu bak.<br>" +
        "5. İstersen Toplam: toplam kupa sayısının sol üst ve sağ alt köşesi.<br>" +
        "6. Mavi + ile sırayla her seferinde düğmeye dokun:<br>" +
        "&nbsp;&nbsp;<b>1</b> Maça gir (lobide)<br>" +
        "&nbsp;&nbsp;<b>2</b> Saldırı (maç içinde)<br>" +
        "&nbsp;&nbsp;<b>3</b> Devam (maç sonu)<br>" +
        "&nbsp;&nbsp;<b>4</b> Devam (sonraki ekran)<br>" +
        "&nbsp;&nbsp;<b>5</b> Bitir (ana ekrana dönen düğme)<br>" +
        "&nbsp;&nbsp;<b>6</b> Savaşçılar (karakter ekranına gir)<br>" +
        "&nbsp;&nbsp;<b>7</b> Karakter seç (listedeki kart)<br>" +
        "&nbsp;&nbsp;<b>8</b> Seç (ana ekrana atar)<br>" +
        "7. Göz ile halkaları gizle, paneli kupa alanlarından uzağa taşı.<br>" +
        "8. Başlat'a bas, yeşil ■ olunca bırak. Durdurmak için ■ veya Gizle.<br><br>" +

        "<b>DÖNGÜ SIRASI</b><br>" +
        "Kupayı oku. Sınırın <b>altındaysa</b>: 1 → 2 (saniyede 5 vuruş) → 3 → 4 → 5, başa dön. " +
        "Sınır <b>ve üstündeyse</b>: 6 → (5 sn) 7 → (5 sn) 8, kupayı tekrar oku. " +
        "Sınırın altındaysa döngüye devam, hâlâ sınır ve üstündeyse dur.<br><br>"

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

    private fun addSettings(col: LinearLayout) {
        val sp = getSharedPreferences(SlotStore.SET, MODE_PRIVATE)
        col.addView(TextView(this).apply { text = "AYARLAR"; textSize = 15f })

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
        col.addView(Button(this).apply {
            text = "Kupa sınırını kaydet"
            setOnClickListener {
                val v = limitInput.text.toString().trim().toIntOrNull()
                if (v == null || v < SlotStore.MIN_LIMIT || v > SlotStore.MAX_LIMIT) {
                    Toast.makeText(this@MainActivity,
                        "${SlotStore.MIN_LIMIT}-${SlotStore.MAX_LIMIT} arası bir sayı gir", Toast.LENGTH_SHORT).show()
                } else {
                    sp.edit().putInt("limit", v).apply()
                    showLimit()
                }
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
        col.addView(TextView(this).apply { text = "\n" })
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

        addSettings(col)

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
