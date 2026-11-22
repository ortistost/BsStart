package com.example.bsstart

import android.content.SharedPreferences
import java.util.Locale

object SlotStore {
    const val PREFS = "cfg3"
    const val SET = "bsset"
    const val REC = "bsrec"
    const val DEFAULT_LIMIT = 475
    const val MIN_LIMIT = 1
    const val DEFAULT_AIF = 90    // if% eşiği (%)
    const val MIN_AIF = 50
    const val MAX_AIF = 100
    const val MAX_LIMIT = 3000
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.25f
    const val SLOTS = 18          // 1-8 yuvalar, 11-18 = if (1)..if (8)
    const val IF_BASE = 10
    const val HEADER = "BsStart slots v2"
    val REQUIRED = intArrayOf(1, 2, 4, 5)
    val NAMES = arrayOf(
        "", "Maça gir", "Saldırı", "Devam 1", "Devam 2", "Çıkış",
        "Savaşçılar", "Karakter", "Seç", "", "",
        "if (1)", "if (2)", "if (3)", "if (4)", "if (5)", "if (6)", "if (7)", "if (8)"
    )
    // Uygulamanın hazır ayarı. Kaynak: kullanıcının kendi kaydı, screen=2340x1080.
    // Konumlar ekran oranı olduğundan başka çözünürlükte aynı yere denk gelir; renkler cihaza özgüdür.
    val PRESET = listOf(
        "$HEADER screen=2340x1080",
        "1 Maça gir x=0.8645 y=0.8954 color=#D9D8D2",
        "2 Saldırı x=0.8543 y=0.6565 color=#B3391B",
        "3 Devam 1 x=0.9628 y=0.9194 color=#2373FF",
        "4 Devam 2 x=0.9521 y=0.9259 color=#2373FF",
        "5 Çıkış x=0.9603 y=0.9231 color=#2373FF",
        "6 Savaşçılar x=0.1013 y=0.4435 color=#353C52",
        "7 Karakter x=0.3128 y=0.2315 color=#B116EC",
        "8 Seç x=0.0902 y=0.8815 color=#EFC609",
        "12 if (2) x=0.0410 y=0.0593 color=#08105E",
        "OCR x1=0.4064 y1=0.1269 x2=0.6338 y2=0.2269",
        "SET limit=475 scale=1.00 tot=1"
    ).joinToString("\n")

    val BUTTONS = listOf(
        "move" to "Taşı", "mini" to "Küçült / Büyült", "main" to "Başlat / Durdur",
        "calib" to "Yuva kaydet", "if" to "If", "aif" to "If%", "del" to "Hepsini sil", "undo" to "Geri al",
        "eye" to "Halkalar", "area" to "Alan seç", "live" to "Test oku",
        "tot" to "Toplam", "save" to "Kaydet", "colors" to "Renkler", "off" to "Gizle"
    )

    fun btnOn(sets: SharedPreferences, key: String) = sets.getBoolean("btn_$key", true)

    // Otomatik kupa anahtarı. Hiç ayarlanmadıysa okuma alanı varsa açık sayılır (eski davranış).
    fun autoTrophy(sets: SharedPreferences, p: SharedPreferences) =
        if (sets.contains("atrophy")) sets.getBoolean("atrophy", false) else rectReady(p)

    fun ready(p: SharedPreferences, i: Int) =
        p.contains("c$i") && p.getFloat("x$i", -1f) >= 0f

    // if% kaydı tek satırlık metindir: x1,y1,x2,y2,gw,gh,HEX (HEX = hücre başına RRGGBB)
    fun aifEncode(r: AifRef): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f,%d,%d,", r.x1, r.y1, r.x2, r.y2, r.gw, r.gh))
        for (c in r.rgb) sb.append(String.format(Locale.US, "%06X", c and 0xFFFFFF))
        return sb.toString()
    }

    // Geçersiz ya da bozuk kayıt için null
    fun aifDecode(raw: String): AifRef? {
        val f = raw.trim().split(",")
        if (f.size != 7) return null
        val x1 = f[0].toFloatOrNull() ?: return null
        val y1 = f[1].toFloatOrNull() ?: return null
        val x2 = f[2].toFloatOrNull() ?: return null
        val y2 = f[3].toFloatOrNull() ?: return null
        val gw = f[4].toIntOrNull() ?: return null
        val gh = f[5].toIntOrNull() ?: return null
        if (listOf(x1, y1, x2, y2).any { it !in 0f..1f } || x2 <= x1 || y2 <= y1) return null
        if (gw !in 4..64 || gh !in 4..64) return null
        val hex = f[6]
        if (hex.length != gw * gh * 6) return null
        val rgb = IntArray(gw * gh)
        for (i in rgb.indices) rgb[i] = hex.substring(i * 6, i * 6 + 6).toIntOrNull(16) ?: return null
        return AifRef(x1, y1, x2, y2, gw, gh, rgb)
    }

    fun coreReady(p: SharedPreferences) = REQUIRED.all { ready(p, it) }

    fun rectReady(p: SharedPreferences) =
        listOf("ox1", "oy1", "ox2", "oy2").all { p.contains(it) }

    fun totReady(p: SharedPreferences) =
        listOf("tx1", "ty1", "tx2", "ty2").all { p.contains(it) }

    fun format(p: SharedPreferences, screen: String, sets: SharedPreferences? = null): String? {
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
        for (n in 1..8) {
            val raw = p.getString("aif_$n", null) ?: continue
            if (aifDecode(raw) != null) sb.append("AIF $n $raw\n")
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
        if (totReady(p)) {
            sb.append(
                String.format(
                    Locale.US, "TOT x1=%.4f y1=%.4f x2=%.4f y2=%.4f\n",
                    p.getFloat("tx1", 0f), p.getFloat("ty1", 0f),
                    p.getFloat("tx2", 0f), p.getFloat("ty2", 0f)
                )
            )
        }
        if (sets != null) {
            sb.append(
                String.format(
                    Locale.US, "SET limit=%d scale=%.2f tot=%d\n",
                    sets.getInt("limit", DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT),
                    sets.getFloat("scale", 1f).coerceIn(MIN_SCALE, MAX_SCALE),
                    if (sets.getBoolean("ttot", false)) 1 else 0
                )
            )
        }
        return sb.toString()
    }

    class Parsed(
        val xs: FloatArray, val ys: FloatArray, val colors: IntArray,
        val have: BooleanArray, val rect: FloatArray?, val tot: FloatArray?,
        val set: Triple<Int, Float, Boolean>?, val screen: String,
        val aif: Map<Int, String> = emptyMap()
    )

    class ParseResult(val parsed: Parsed?, val error: String?)

    private val SLOT_RE =
        Regex("""^(\d{1,2})\s+.+?\s+x=([0-9.]+)\s+y=([0-9.]+)\s+color=#([0-9A-Fa-f]{6})$""")
    private val RECT_RE =
        Regex("""^OCR\s+x1=([0-9.]+)\s+y1=([0-9.]+)\s+x2=([0-9.]+)\s+y2=([0-9.]+)$""")
    private val TOT_RE =
        Regex("""^TOT\s+x1=([0-9.]+)\s+y1=([0-9.]+)\s+x2=([0-9.]+)\s+y2=([0-9.]+)$""")
    private val AIF_RE =
        Regex("""^AIF\s+(\d)\s+(\S+)$""")
    private val SET_RE =
        Regex("""^SET\s+limit=(\d+)\s+scale=([0-9.]+)\s+tot=([01])$""")

    private fun err(m: String) = ParseResult(null, m)

    private fun rectOf(r: MatchResult): FloatArray? {
        val v = FloatArray(4) { r.groupValues[it + 1].toFloatOrNull() ?: -1f }
        if (v.any { it !in 0f..1f } || v[2] <= v[0] || v[3] <= v[1]) return null
        return v
    }

    fun norm(s: String) =
        s.replace("\r", "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("===") }
            .joinToString("\n").lowercase(Locale.ROOT)

    fun parse(text: String): ParseResult {
        val lines = text.replace("\r", "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("===") }
        if (lines.isEmpty() || !lines[0].startsWith(HEADER))
            return err("İlk satır '$HEADER' ile başlamalı")
        val screen = Regex("screen=(\\S+)").find(lines[0])?.groupValues?.get(1) ?: "?"

        val xs = FloatArray(SLOTS + 1); val ys = FloatArray(SLOTS + 1)
        val cs = IntArray(SLOTS + 1); val have = BooleanArray(SLOTS + 1)
        var rect: FloatArray? = null
        var tot: FloatArray? = null
        var set: Triple<Int, Float, Boolean>? = null
        val aifMap = HashMap<Int, String>()

        for (line in lines.drop(1)) {
            val m = SLOT_RE.find(line)
            if (m != null) {
                var i = m.groupValues[1].toInt()
                if (i == 9) i = IF_BASE + 2          // eski "Koşul" = if (2)
                if (i !in 1..SLOTS || i == IF_BASE) return err("Geçersiz yuva numarası: $line")
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
            val a = AIF_RE.find(line)
            if (a != null) {
                val n = a.groupValues[1].toInt()
                if (n !in 1..6) return err("if% yuva numarası geçersiz: ${line.take(20)}")
                if (aifMap.containsKey(n)) return err("if% $n iki kere var")
                if (aifDecode(a.groupValues[2]) == null) return err("if% $n kaydı bozuk")
                aifMap[n] = a.groupValues[2]
                continue
            }
            val r = RECT_RE.find(line)
            if (r != null) {
                if (rect != null) return err("OCR satırı iki kere var")
                rect = rectOf(r) ?: return err("OCR alanı geçersiz: $line")
                continue
            }
            val t = TOT_RE.find(line)
            if (t != null) {
                if (tot != null) return err("TOT satırı iki kere var")
                tot = rectOf(t) ?: return err("TOT alanı geçersiz: $line")
                continue
            }
            val s = SET_RE.find(line)
            if (s != null) {
                if (set != null) return err("SET satırı iki kere var")
                val lim = s.groupValues[1].toIntOrNull()
                val sc = s.groupValues[2].toFloatOrNull()
                if (lim == null || lim !in MIN_LIMIT..MAX_LIMIT) return err("Kupa sınırı geçersiz: $line")
                if (sc == null) return err("Panel boyutu geçersiz: $line")
                set = Triple(lim, sc.coerceIn(MIN_SCALE, MAX_SCALE), s.groupValues[3] == "1")
                continue
            }
            return err("Tanınmayan satır: $line")
        }
        val missing = REQUIRED.filter { !have[it] }
        if (missing.isNotEmpty()) return err("Eksik yuvalar: $missing")
        return ParseResult(Parsed(xs, ys, cs, have, rect, tot, set, screen, aifMap), null)
    }

    fun apply(p: SharedPreferences, d: Parsed, sets: SharedPreferences? = null): Boolean {
        val e = p.edit().clear()
        for (i in 1..SLOTS) {
            if (!d.have[i]) continue
            e.putFloat("x$i", d.xs[i]).putFloat("y$i", d.ys[i]).putInt("c$i", d.colors[i])
        }
        for ((n, raw) in d.aif) e.putString("aif_$n", raw)
        d.rect?.let {
            e.putFloat("ox1", it[0]).putFloat("oy1", it[1])
                .putFloat("ox2", it[2]).putFloat("oy2", it[3])
        }
        d.tot?.let {
            e.putFloat("tx1", it[0]).putFloat("ty1", it[1])
                .putFloat("tx2", it[2]).putFloat("ty2", it[3])
        }
        e.putString("screen", d.screen)
        var ok = e.commit()
        val st = d.set
        if (st != null && sets != null) {
            ok = sets.edit().putInt("limit", st.first).putFloat("scale", st.second)
                .putBoolean("ttot", st.third).commit() && ok
        }
        return ok
    }

    // Hazır ayardan yalnızca eksik olanları doldurur, var olan hiçbir değeri ezmez.
    // Hiç temel yuva yoksa hepsini alır; varsa sadece kupa kısmını (yuva 6-8, okuma alanı, ayarlar).
    // Doldurulan öğe sayısını döndürür.
    fun fillMissing(p: SharedPreferences, d: Parsed, sets: SharedPreferences?): Int {
        val trophyOnly = coreReady(p)
        val e = p.edit()
        var n = 0
        for (i in 1..SLOTS) {
            if (!d.have[i] || ready(p, i)) continue
            if (trophyOnly && i !in 6..8) continue
            e.putFloat("x$i", d.xs[i]).putFloat("y$i", d.ys[i]).putInt("c$i", d.colors[i])
            n++
        }
        d.rect?.let {
            if (!rectReady(p)) {
                e.putFloat("ox1", it[0]).putFloat("oy1", it[1])
                    .putFloat("ox2", it[2]).putFloat("oy2", it[3])
                n++
            }
        }
        if (!trophyOnly) {
            d.tot?.let {
                if (!totReady(p)) {
                    e.putFloat("tx1", it[0]).putFloat("ty1", it[1])
                        .putFloat("tx2", it[2]).putFloat("ty2", it[3])
                    n++
                }
            }
        }
        if (n > 0) e.commit()
        val st = d.set
        if (st != null && sets != null) {
            val se = sets.edit()
            if (!sets.contains("limit")) se.putInt("limit", st.first)
            if (!trophyOnly) {
                if (!sets.contains("scale")) se.putFloat("scale", st.second)
                if (!sets.contains("ttot")) se.putBoolean("ttot", st.third)
            }
            se.commit()
        }
        return n
    }

    // --- Kayıtlar (r1, r2, ... metin; n1, n2, ... isim) ---

    fun recList(r: SharedPreferences): List<Pair<Int, String>> =
        r.all.entries.mapNotNull { e ->
            val k = e.key
            val s = e.value as? String
            if (s != null && k.length > 1 && k[0] == 'r' && k.substring(1).all { it.isDigit() })
                (k.substring(1).toIntOrNull() ?: return@mapNotNull null) to s
            else null
        }.sortedBy { it.first }

    fun recAddUnique(r: SharedPreferences, text: String): Pair<Int, Boolean> {
        val want = norm(text)
        recList(r).firstOrNull { norm(it.second) == want }?.let { return it.first to false }
        val n = r.getInt("next", 1)
        r.edit().putString("r$n", text).putInt("next", n + 1).commit()
        return n to true
    }

    fun recDelete(r: SharedPreferences, n: Int) {
        r.edit().remove("r$n").remove("n$n").commit()
    }

    fun recName(r: SharedPreferences, n: Int): String? =
        r.getString("n$n", null)?.takeIf { it.isNotEmpty() }

    fun recSetName(r: SharedPreferences, n: Int, name: String) {
        val e = r.edit()
        if (name.isEmpty()) e.remove("n$n") else e.putString("n$n", name)
        e.commit()
    }
}

object TrophyParser {
    const val MAX_PLAUSIBLE = 5000
    const val MAX_TOTAL = 2_000_000
    private val DIGITLIKE = Regex("^[0-9OolI|]{1,5}$")
    private val TOTALLIKE = Regex("^[0-9OolI|]{1,8}$")

    private fun fix(s: String) = s.map {
        when (it) {
            'O', 'o' -> '0'
            'I', 'l', '|' -> '1'
            else -> it
        }
    }.joinToString("")

    fun parse(raw: String?): Int? {
        if (raw == null) return null
        val found = ArrayList<Int>()
        for (line in raw.lines()) {
            val s = line.filter { !it.isWhitespace() }
            if (s.isEmpty()) continue
            val t = if (DIGITLIKE.matches(s)) fix(s) else s
            for (m in Regex("\\d+").findAll(t)) {
                if (m.value.length > 4) continue
                val v = m.value.toIntOrNull() ?: continue
                if (v in 0..MAX_PLAUSIBLE) found.add(v)
            }
        }
        return found.maxWithOrNull(compareBy<Int>({ it.toString().length }, { it }))
    }

    fun parseTotal(raw: String?): Int? {
        if (raw == null) return null
        var best: Int? = null
        var bestLen = 0
        for (line in raw.lines()) {
            val s = line.filter { !it.isWhitespace() && it != ',' && it != '.' }
            if (s.isEmpty()) continue
            val t = if (TOTALLIKE.matches(s)) fix(s) else s
            val cands = if (t.all { it.isDigit() }) listOf(t)
            else Regex("\\d+").findAll(t).map { it.value }.toList()
            for (c in cands) {
                if (c.length > 7) continue
                val v = c.toIntOrNull() ?: continue
                if (v > MAX_TOTAL) continue
                if (c.length > bestLen || (c.length == bestLen && v > (best ?: -1))) {
                    best = v; bestLen = c.length
                }
            }
        }
        return best
    }
}
