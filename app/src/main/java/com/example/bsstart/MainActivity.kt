package com.example.bsstart

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
        s.replace("\r", "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("===") }
            .joinToString("\n").lowercase(Locale.ROOT)

    private fun guideHtml(): String =
        "<b>PANEL DÜĞMELERİ (yukarıdan aşağı)</b><br>" +
        "• <b>Taşı</b> (dört yönlü ok): sürükle. Kısa dokunma sıradaki yuvaya atlar.<br>" +
        "• <b>Başlat / Durdur</b>: döngü.<br>" +
        "• <b>Yuva kaydet</b> (+): sıradaki yuvanın düğmesine dokunursun, konum ve renk kaydedilir.<br>" +
        "• <b>Hepsini sil</b> (−): her şeyi onaysız siler. Yanlışsa Geri al.<br>" +
        "• <b>Geri al</b>: + / − / alan seçimi işlemlerinin sonuncusunu geri alır (tek seviye).<br>" +
        "• <b>Halkalar</b> (göz): halkaları ve çerçeveleri gizle/göster. Toplam kupa yazısı hep görünür.<br>" +
        "• <b>Kopyala</b> (pano): yuvaları, alanları ve ayarları panoya kopyalar.<br>" +
        "• <b>Alan seç</b> (ekran): karakter kupası alanı, sol üst sonra sağ alt köşe. Uzun bas = detaylı test. " +
        "Seçim sırasında panelde herhangi bir butona basarsan iptal olur.<br>" +
        "• <b>Test oku</b> (?): çerçevede OKU (sayı) veya OKU (Yok) yazar.<br>" +
        "• <b>Toplam</b> (kupa): ilk basış toplam kupa alanını seçtirir (sol üst, sağ alt). " +
        "Sonraki basışlar takibi açar/kapatır, uzun basış alanı yeniden seçtirir. " +
        "Çerçevenin altında iki satır çıkar: güncel sayı (+fark) ve ilk okunan → güncel.<br>" +
        "• <b>Kaydet</b>: mevcut her şeyi aşağıdaki Kayıtlar bölümüne Kayıt 1, Kayıt 2... diye ekler.<br>" +
        "• <b>Gizle</b> (X): döngüyü durdurur, paneli gizler. Geri açmak için uygulamadaki düğme. " +
        "Tekrar açınca toplam kupa farkı (+0)'dan başlar.<br><br>" +

        "<b>SIFIRDAN YENİ PANEL</b><br>" +
        "Uygulamadaki düğme paneli varsayılan yerde, o anki boyutta, boş yuvalarla açar. " +
        "Eski yuvalar yedeğe alınır. Paneli Gizle deyip uygulamadan tekrar açarsan eski yuvalar geri gelir.<br><br>" +

        "<b>KURULUM SIRASI (oyun içinde)</b><br>" +
        "1. Erişilebilirlik ayarlarından BS Start'ı aç.<br>" +
        "2. Hepsini sil (−) ile eski kayıtları sil.<br>" +
        "3. Lobide Alan seç: karakter kupasının <b>sol üst</b>, sonra <b>sağ alt</b> köşesi.<br>" +
        "4. Test oku (?): OKU (sayı) doğru mu bak.<br>" +
        "5. İstersen Toplam: toplam kupa sayısının sol üst ve sağ alt köşesi.<br>" +
        "6. Yuva kaydet (+) ile sırayla her seferinde düğmeye dokun:<br>" +
        "&nbsp;&nbsp;<b>1</b> Maça gir (lobide)<br>" +
        "&nbsp;&nbsp;<b>2</b> Saldırı (maç içinde)<br>" +
        "&nbsp;&nbsp;<b>3</b> Devam (maç sonu)<br>" +
        "&nbsp;&nbsp;<b>4</b> Devam (sonraki ekran)<br>" +
        "&nbsp;&nbsp;<b>5</b> Bitir (ana ekrana dönen düğme)<br>" +
        "&nbsp;&nbsp;<b>6</b> Savaşçılar (karakter ekranına gir)<br>" +
        "&nbsp;&nbsp;<b>7</b> Karakter seç (listedeki kart)<br>" +
        "&nbsp;&nbsp;<b>8</b> Seç (ana ekrana atar)<br>" +
        "7. Kaydet ile yedek al.<br>" +
        "8. Göz ile halkaları gizle, paneli kupa alanlarından uzağa taşı.<br>" +
        "9. Başlat'a bas. Durdurmak için Durdur veya Gizle.<br><br>" +

        "<b>DÖNGÜ SIRASI</b><br>" +
        "Kupayı oku. Sınırın <b>altındaysa</b>: 1 → 2 (saniyede 5 vuruş, maç sonu düğmesi görünene kadar durmaz) → 3 → 4 → 5, başa dön. " +
        "Sınır <b>ve üstündeyse</b>: 6 → (5 sn) 7 → (5 sn) 8, kupayı tekrar oku. " +
        "Sınırın altındaysa döngüye devam, hâlâ sınır ve üstündeyse dur.<br><br>"

    private lateinit var status: TextView
    private lateinit var logView: TextView
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

    private fun refreshLog() {
        val raw = getSharedPreferences("bslog", MODE_PRIVATE).getString("l", "") ?: ""
        logView.text = if (raw.isEmpty()) "(kayıt yok)" else raw.lines().reversed().joinToString("\n")
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
        val same = norm(back) == norm(t)
        return (if (!ok) "Kayıt başarısız!\n" else "") +
            (if (same) "AYNI ✓ (yüklendi, tekrar dışa aktarım birebir aynı)\n\n"
            else "FARK VAR ✗ (aşağıdaki metin yüklenen halidir)\n\n") + back
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
            recBox.addView(TextView(this).apply { text = "Kayıt $n  (ekran $scr)"; textSize = 15f })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(rowBtn("Yükle") { out.text = loadText(t); refreshStatus() })
            row.addView(rowBtn("Kopyala") { copyText("bsstart-$n", t) })
            row.addView(rowBtn("Sil") { SlotStore.recDelete(recPrefs(), n); refreshRecords() })
            recBox.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
        refreshRecords()
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
        col.addView(plainBtn("Paneli göster / gizle") {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else s.setPanelVisible(!s.isPanelVisible())
            refreshStatus()
        })
        col.addView(plainBtn("Sıfırdan yeni panel aç") {
            val s = ServiceBridge.service
            if (s == null) say("Servis kapalı, önce erişilebilirliği aç")
            else { s.openFresh(); say("Yeni panel açılıyor") }
            refreshStatus()
        })
        col.addView(plainBtn("Erişilebilirlik ayarlarını aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        col.addView(TextView(this).apply { text = "KAYITLAR"; textSize = 15f })
        recBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recBox)
        col.addView(plainBtn("Tüm kayıtları kopyala") {
            val l = SlotStore.recList(recPrefs())
            if (l.isEmpty()) say("Kayıt yok")
            else copyText("bsstart-all", l.joinToString("\n") { "=== Kayıt ${it.first} ===\n${it.second}" })
        })

        col.addView(TextView(this).apply { text = "\nMETİNDEN YÜKLE"; textSize = 15f })
        val input = EditText(this).apply {
            hint = "Kopyalanan metni (tek kayıt veya tüm kayıtlar) buraya yapıştır"
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
            var bad = 0
            for (b in blocks) {
                if (SlotStore.parse(b).parsed != null) { SlotStore.recAdd(recPrefs(), b); added++ } else bad++
            }
            out.text = if (blocks.isEmpty()) "Hata: metinde '${SlotStore.HEADER}' başlığı yok"
            else "$added kayıt eklendi" + (if (bad > 0) ", $bad geçersiz atlandı" else "")
            refreshRecords()
        })
        col.addView(out)

        col.addView(TextView(this).apply { text = "\nKAYIT DEFTERİ (en yeni üstte)"; textSize = 15f })
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        col.addView(plainBtn("Günlüğü yenile") { refreshLog() })
        col.addView(plainBtn("Günlüğü sil") {
            getSharedPreferences("bslog", MODE_PRIVATE).edit().clear().apply()
            refreshLog()
        })
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
