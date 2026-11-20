package com.example.bsstart

import android.content.SharedPreferences
import java.util.Locale

object SlotStore {
    const val PREFS = "cfg3"
    const val SLOTS = 8
    const val CORE = 5
    const val HEADER = "BsStart slots v2"
    val NAMES = arrayOf(
        "", "Maça gir", "Saldırı", "Devam 1", "Devam 2", "Çıkış",
        "Savaşçılar", "Karakter", "Seç"
    )

    fun ready(p: SharedPreferences, i: Int) =
        p.contains("c$i") && p.getFloat("x$i", -1f) >= 0f

    fun coreReady(p: SharedPreferences) = (1..CORE).all { ready(p, it) }

    fun rectReady(p: SharedPreferences) =
        listOf("ox1", "oy1", "ox2", "oy2").all { p.contains(it) }

    // Çekirdek 5 yuva dolu değilse null. Dolu olan 6-8 ve OCR alanı eklenir.
    fun format(p: SharedPreferences, screen: String): String? {
        if (!coreReady(p)) return null
        val sb = StringBuilder("$HEADER screen=$screen\n")
        for (i in 1..SLOTS) {
            if (!ready(p, i)) continue
            sb.append(
                String.format(
                    Locale.US, "%d %s x=%.4f y=%.4f color=#%06X\n", i, NAMES[i],
                    p.getFloat("x$i", 0f), p.getFloat("y$i", 0f),
                    p.getInt("c$i", 0) and 0xFFFFFF
                )
            )
        }
        if (rectReady(p)) {
            sb.append(
                String.format(
                    Locale.US, "OCR x1=%.4f y1=%.4f x2=%.4f y2=%.4f\n",
                    p.getFloat("ox1", 0f), p.getFloat("oy1", 0f),
                    p.getFloat("ox2", 0f), p.getFloat("oy2", 0f)
                )
            )
        }
        return sb.toString()
    }

    class Parsed(
        val xs: FloatArray, val ys: FloatArray, val colors: IntArray,
        val have: BooleanArray, val rect: FloatArray?, val screen: String
    )

    class ParseResult(val parsed: Parsed?, val error: String?)

    private val SLOT_RE =
        Regex("""^(\d)\s+.+?\s+x=([0-9.]+)\s+y=([0-9.]+)\s+color=#([0-9A-Fa-f]{6})$""")
    private val RECT_RE =
        Regex("""^OCR\s+x1=([0-9.]+)\s+y1=([0-9.]+)\s+x2=([0-9.]+)\s+y2=([0-9.]+)$""")

    private fun err(m: String) = ParseResult(null, m)

    fun parse(text: String): ParseResult {
        val lines = text.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines[0].startsWith(HEADER))
            return err("İlk satır '$HEADER' ile başlamalı (eski v1 metni geçmez)")
        val screen = Regex("screen=(\\S+)").find(lines[0])?.groupValues?.get(1) ?: "?"

        val xs = FloatArray(SLOTS + 1); val ys = FloatArray(SLOTS + 1)
        val cs = IntArray(SLOTS + 1); val have = BooleanArray(SLOTS + 1)
        var rect: FloatArray? = null

        for (line in lines.drop(1)) {
            val m = SLOT_RE.find(line)
            if (m != null) {
                val i = m.groupValues[1].toInt()
                if (i !in 1..SLOTS) return err("Geçersiz yuva numarası: $line")
                if (have[i]) return err("Yuva $i iki kere var")
                val x = m.groupValues[2].toFloatOrNull()
                val y = m.groupValues[3].toFloatOrNull()
                if (x == null || y == null || x !in 0f..1f || y !in 0f..1f)
                    return err("Konum 0-1 aralığında değil: $line")
                xs[i] = x; ys[i] = y
                cs[i] = (0xFF shl 24) or m.groupValues[4].toInt(16)
                have[i] = true
                continue
            }
            val r = RECT_RE.find(line)
            if (r != null) {
                if (rect != null) return err("OCR satırı iki kere var")
                val v = FloatArray(4) { r.groupValues[it + 1].toFloatOrNull() ?: -1f }
                if (v.any { it !in 0f..1f } || v[2] <= v[0] || v[3] <= v[1])
                    return err("OCR alanı geçersiz: $line")
                rect = v
                continue
            }
            return err("Tanınmayan satır: $line")
        }
        val missing = (1..CORE).filter { !have[it] }
        if (missing.isNotEmpty()) return err("Eksik yuvalar: $missing")
        return ParseResult(Parsed(xs, ys, cs, have, rect, screen), null)
    }

    fun apply(p: SharedPreferences, d: Parsed): Boolean {
        val e = p.edit().clear()
        for (i in 1..SLOTS) {
            if (!d.have[i]) continue
            e.putFloat("x$i", d.xs[i]).putFloat("y$i", d.ys[i]).putInt("c$i", d.colors[i])
        }
        d.rect?.let {
            e.putFloat("ox1", it[0]).putFloat("oy1", it[1])
                .putFloat("ox2", it[2]).putFloat("oy2", it[3])
        }
        e.putString("screen", d.screen)
        return e.commit()
    }
}

object TrophyParser {
    const val MAX_PLAUSIBLE = 2000
    private val DIGITLIKE = Regex("^[0-9OolI|]{1,5}$")

    // Satır satır okur; okunamazsa veya makul değilse null
    fun parse(raw: String?): Int? {
        if (raw == null) return null
        val found = ArrayList<Int>()
        for (line in raw.lines()) {
            val s = line.filter { !it.isWhitespace() }
            if (s.isEmpty()) continue
            val t = if (DIGITLIKE.matches(s))
                s.map {
                    when (it) {
                        'O', 'o' -> '0'
                        'I', 'l', '|' -> '1'
                        else -> it
                    }
                }.joinToString("")
            else s
            for (m in Regex("\\d+").findAll(t)) {
                if (m.value.length > 4) continue
                val v = m.value.toIntOrNull() ?: continue
                if (v in 0..MAX_PLAUSIBLE) found.add(v)
            }
        }
        return found.maxWithOrNull(compareBy<Int>({ it.toString().length }, { it }))
    }
}
