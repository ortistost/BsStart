package com.example.bsstart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "1) Aşağıdaki düğmeyle erişilebilirlik ayarlarını aç.\n" +
                "2) Yüklü uygulamalar bölümünden BS Start'ı bulup aç.\n" +
                "3) Oyunda lobideyken alttaki küçük hedef butonuna bas, sonra Oyna butonuna dokun (kalibrasyon).\n" +
                "4) Büyük buton: tek dokunuş atar. Uzun basış: otomatik taramayı açar/kapatır.\n\n" +
                "Android 13+ ve APK'yı dışarıdan yüklediysen izin gri olabilir: " +
                "Ayarlar > Uygulamalar > BS Start > sağ üst ⋮ > Kısıtlanmış ayarlara izin ver."
        })
        col.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        setContentView(col)
    }
}
