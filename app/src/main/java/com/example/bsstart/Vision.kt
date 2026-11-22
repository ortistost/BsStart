package com.example.bsstart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// if% referansı: seçilen alan (ekran oranı 0..1) ve o alanın gw x gh hücreye küçültülmüş görüntüsü.
// rgb: hücre başına 0xRRGGBB (alfa yok), satır satır soldan sağa.
class AifRef(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val gw: Int, val gh: Int, val rgb: IntArray
)

// Saf matematik: Android'e bağlı değil, oyunsuz denenebilir.
// Eşikler (CELL_TOL, EDGE_STEP, COLOR_WEIGHT) kalibre EDİLMEDİ; gerçek ekranda if% düğmesine
// uzun basıp çıkan yüzdelere bakarak ayarlanmalı.
object VisionMath {
    // total: 0..1 toplam uyum, color: hücre rengi uyumu, edge: şekil/çizgi (kenar) uyumu
    class AifScore(val total: Float, val color: Float, val edge: Float)

    private const val MAX_SIDE = 32     // uzun kenarda en fazla bu kadar hücre
    private const val MIN_SIDE = 4
    private const val CELL_TOL = 40     // hücre rengi: en büyük kanal farkı bu değerin altındaysa "uyuyor"
    private const val EDGE_STEP = 24    // komşu hücreler arası parlaklık farkı bunu aşarsa kenar
    private const val COLOR_WEIGHT = 0.6f

    // Alan piksel boyutundan hücre ızgarası: [gw, gh]. Alan 4x4 pikselden küçük olmamalı.
    fun gridSize(w: Int, h: Int): IntArray {
        val ww = max(1, w)
        val hh = max(1, h)
        val s = min(1f, MAX_SIDE.toFloat() / max(ww, hh))
        val gw = max(MIN_SIDE, Math.round(ww * s)).coerceAtMost(ww)
        val gh = max(MIN_SIDE, Math.round(hh * s)).coerceAtMost(hh)
        return intArrayOf(gw, gh)
    }

    // px: ARGB pikseller (w x h). Her hücre, kapladığı piksellerin ortalama rengidir.
    fun downsample(px: IntArray, w: Int, h: Int, gw: Int, gh: Int): IntArray {
        val out = IntArray(gw * gh)
        for (cy in 0 until gh) {
            val y0 = cy * h / gh
            val y1 = min(h, max(y0 + 1, (cy + 1) * h / gh))
            for (cx in 0 until gw) {
                val x0 = cx * w / gw
                val x1 = min(w, max(x0 + 1, (cx + 1) * w / gw))
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val p = px[row + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[cy * gw + cx] =
                    if (n == 0) 0
                    else (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    private fun chanDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    private fun luma(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun edges(g: IntArray, gw: Int, gh: Int): BooleanArray {
        val e = BooleanArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val l = luma(g[y * gw + x])
                val right = x + 1 < gw && abs(l - luma(g[y * gw + x + 1])) > EDGE_STEP
                val down = y + 1 < gh && abs(l - luma(g[(y + 1) * gw + x])) > EDGE_STEP
                e[y * gw + x] = right || down
            }
        }
        return e
    }

    private fun near(e: BooleanArray, gw: Int, gh: Int, x: Int, y: Int): Boolean {
        for (yy in max(0, y - 1)..min(gh - 1, y + 1))
            for (xx in max(0, x - 1)..min(gw - 1, x + 1))
                if (e[yy * gw + xx]) return true
        return false
    }

    // Kenar haritalarının 1 hücre toleranslı, iki yönlü benzerliği (0..1).
    // İki tarafta da neredeyse hiç kenar yoksa (düz renk alan) 1 sayılır.
    private fun edgeSimilarity(a: BooleanArray, b: BooleanArray, gw: Int, gh: Int): Float {
        val minEdges = max(2, gw * gh / 50)
        val ca = a.count { it }
        val cb = b.count { it }
        if (ca < minEdges && cb < minEdges) return 1f
        var ma = 0
        var mb = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                if (a[i] && near(b, gw, gh, x, y)) ma++
                if (b[i] && near(a, gw, gh, x, y)) mb++
            }
        }
        return (ma + mb).toFloat() / (ca + cb)
    }

    fun compare(ref: IntArray, cur: IntArray, gw: Int, gh: Int): AifScore {
        val n = gw * gh
        if (n <= 0 || ref.size != n || cur.size != n) return AifScore(0f, 0f, 0f)
        var same = 0
        for (i in 0 until n) if (chanDiff(ref[i], cur[i]) <= CELL_TOL) same++
        val color = same.toFloat() / n
        val edge = edgeSimilarity(edges(ref, gw, gh), edges(cur, gw, gh), gw, gh)
        val total = COLOR_WEIGHT * color + (1f - COLOR_WEIGHT) * edge
        return AifScore(total, color, edge)
    }

    // Otsu eşiği: hist 256 kutulu parlaklık histogramı, total toplam piksel sayısı.
    fun otsu(hist: IntArray, total: Int): Int {
        if (total <= 0) return 128
        var sum = 0.0
        for (t in 0 until 256) sum += t.toDouble() * hist[t]
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF <= 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }
}
