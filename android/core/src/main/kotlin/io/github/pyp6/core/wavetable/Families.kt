package io.github.pyp6.core.wavetable

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** A waveform family: morph position m (0..1) and fundamental -> (cycle, description). */
fun interface WtRender {
    fun render(s: WtSynth, m: Double, f0: Double): Pair<DoubleArray, String>
}

/**
 * The nineteen built-in oscillator families, ported one to one from the
 * desktop app's _wtf_* functions.
 */
internal object BasicFamilies {
    private fun lerp(a: Double, b: Double, m: Double) = a + (b - a) * m
    private fun geom(a: Double, b: Double, m: Double) = a * (b / a).pow(m)
    private fun fmt(v: Double, digits: Int) = String.format(java.util.Locale.ROOT, "%.${digits}f", v)

    private fun fold(x: Double, gain: Double): Double {
        val y = x * gain
        return 2.0 * abs(2.0 * (y / 4.0 - floor(y / 4.0 + 0.5))) - 1.0
    }

    private fun mapK(s: WtSynth, f: (Double) -> Double) = DoubleArray(s.h) { f(s.k[it]) }

    private val noisePhases: DoubleArray by lazy { loadTable("/pyp6/rng_noise_2024.txt") }
    private val bellRandoms: DoubleArray by lazy { loadTable("/pyp6/rng_bell_77.txt") }

    private fun loadTable(path: String): DoubleArray {
        val text = BasicFamilies::class.java.getResourceAsStream(path)!!.use { String(it.readBytes()) }
        return text.lines().filter { it.isNotBlank() }.map { it.trim().toDouble() }.toDoubleArray()
    }

    val saw = WtRender { s, m, _ ->
        val a = lerp(1.45, 0.55, m)
        s.add(mapK(s) { it.pow(-a) }) to "tilt k^-${fmt(a, 2)}"
    }

    val sawSub = WtRender { s, m, _ ->
        val saw = s.add(mapK(s) { 1.0 / it })
        val depth = lerp(0.0, 0.85, m)
        val w: DoubleArray
        val label: String
        if (Math.rint(s.R.toDouble()).toInt() % 2 == 0) {
            // Odd harmonics of a square at half the fundamental.
            val sub = DoubleArray(s.L)
            var n = 1
            while (n < 2 * s.h) {
                for (i in 0 until s.L) sub[i] += sin(2 * PI * n * (s.t[i] / 2.0)) / n
                n += 2
            }
            var pk = 0.0
            for (v in sub) pk = max(pk, abs(v))
            if (pk == 0.0) pk = 1.0
            w = DoubleArray(s.L) { saw[it] + depth * sub[it] / pk }
            label = "sub -1 oct ${fmt(depth, 2)}"
        } else {
            val even = mapK(s) { if (it.toInt() % 2 == 0) 2.0 / it else 0.0 }
            val odd = mapK(s) { if (it.toInt() % 2 == 1) 1.0 / it else 0.0 }
            val e = s.add(even)
            val o = s.add(odd)
            w = DoubleArray(s.L) { e[it] + depth * o[it] }
            label = "sub -1 oct ${fmt(depth, 2)} (saw +1 oct)"
        }
        WtSynth.normalizePeak(w) to label
    }

    val pulse = WtRender { s, m, _ ->
        val d = geom(0.50, 0.015, m)
        val sinA = mapK(s) { (1 - kotlin.math.cos(2 * PI * it * d)) / it }
        val cosA = mapK(s) { sin(2 * PI * it * d) / it }
        s.addSc(sinA, cosA) to "${fmt(d * 100, 2)}%"
    }

    val triangle = WtRender { s, m, _ ->
        val sk = lerp(0.5, 0.06, m)
        val raw = DoubleArray(s.L) {
            val x = s.tf[it]
            (if (x < sk) x / sk else 1.0 - (x - sk) / (1.0 - sk)) * 2 - 1
        }
        s.bandLimit(raw) to "skew ${fmt(sk, 2)}"
    }

    val sine = WtRender { s, m, _ ->
        val amps = DoubleArray(8)
        amps[0] = 1.0
        listOf(2, 3, 4, 6, 8).forEachIndexed { j, k -> amps[k - 1] = m.pow(1.0 + 0.5 * j) }
        s.add(amps) to "stack ${fmt(m, 2)}"
    }

    val folder = WtRender { s, m, _ ->
        val g = lerp(1.0, 8.0, m)
        s.bandLimit(DoubleArray(s.L) { fold(sin(2 * PI * s.t[it]), g) }) to "fold ${fmt(g, 2)}"
    }

    val sync = WtRender { s, m, _ ->
        val r = lerp(1.0, 6.0, m)
        s.bandLimit(DoubleArray(s.L) { sin(2 * PI * r * s.tf[it]) }) to "ratio ${fmt(r, 2)}"
    }

    val fm = WtRender { s, m, _ ->
        val idx = lerp(0.0, 8.0, m)
        s.bandLimit(DoubleArray(s.L) { sin(2 * PI * s.t[it] + idx * sin(4 * PI * s.t[it])) }) to
            "C:M 1:2 I=${fmt(idx, 2)}"
    }

    val phaseDist = WtRender { s, m, _ ->
        val bp = lerp(0.5, 0.96, m)
        s.bandLimit(DoubleArray(s.L) {
            val x = s.tf[it]
            val pd = if (x < bp) 0.5 * x / bp else 0.5 + 0.5 * (x - bp) / (1 - bp)
            sin(2 * PI * pd)
        }) to "bp ${fmt(bp, 2)}"
    }

    val staircase = WtRender { s, m, _ ->
        val lv = max(2, Math.rint(geom(32.0, 2.0, m)).toInt())
        s.bandLimit(DoubleArray(s.L) { Math.rint((2 * s.tf[it] - 1) * (lv / 2.0)) / (lv / 2.0) }) to "$lv levels"
    }

    private class Vowel(val name: String, val c: DoubleArray, val g: DoubleArray, val b: DoubleArray)

    private val vowels = listOf(
        Vowel("A", doubleArrayOf(730.0, 1090.0, 2440.0), doubleArrayOf(1.0, 0.50, 0.22), doubleArrayOf(110.0, 160.0, 240.0)),
        Vowel("E", doubleArrayOf(530.0, 1840.0, 2480.0), doubleArrayOf(1.0, 0.42, 0.28), doubleArrayOf(90.0, 180.0, 250.0)),
        Vowel("I", doubleArrayOf(270.0, 2290.0, 3010.0), doubleArrayOf(1.0, 0.35, 0.30), doubleArrayOf(70.0, 200.0, 260.0)),
        Vowel("O", doubleArrayOf(570.0, 840.0, 2410.0), doubleArrayOf(1.0, 0.60, 0.14), doubleArrayOf(90.0, 130.0, 240.0)),
        Vowel("U", doubleArrayOf(300.0, 870.0, 2240.0), doubleArrayOf(1.0, 0.30, 0.10), doubleArrayOf(70.0, 120.0, 230.0)),
    )

    val vowel = WtRender { s, m, f0 ->
        val x = m * (vowels.size - 1)
        val i = minOf(x.toInt(), vowels.size - 2)
        val fr = x - i
        val v1 = vowels[i]
        val v2 = vowels[i + 1]
        val cen = DoubleArray(3) { lerp(v1.c[it], v2.c[it], fr) }
        val gai = DoubleArray(3) { lerp(v1.g[it], v2.g[it], fr) }
        val bws = DoubleArray(3) { lerp(v1.b[it], v2.b[it], fr) }
        s.add(s.formant(f0, cen, gai, bws, tilt = 0.7)) to "${v1.name}>${v2.name} ${fmt(fr, 2)}"
    }

    val organ = WtRender { s, m, _ ->
        val stops = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)
        val amps = DoubleArray(stops.max())
        stops.forEachIndexed { j, k ->
            val w = (m * (stops.size - 1) - j + 1.0).coerceIn(0.0, 1.0)
            amps[k - 1] = w / (1.0 + 0.25 * j)
        }
        s.add(amps) to "drawbars ${fmt(m, 2)}"
    }

    val piano = WtRender { s, m, f0 ->
        val tilt = lerp(1.9, 1.05, m)
        val cut = lerp(3500.0, 11000.0, m)
        val amps = mapK(s) { k ->
            var a = k.pow(-tilt)
            val z = (k - 3) / 1.6
            a *= 1.0 + 0.45 * exp(-0.5 * z * z)
            a *= 1.0 - 0.30 * (if (k.toInt() % 2 == 0) 1.0 else 0.0)
            val q = k * f0 / cut
            a * exp(-(q * q))
        }
        s.add(amps) to "hardness ${fmt(m, 2)}"
    }

    val strings = WtRender { s, m, f0 ->
        val beta = lerp(0.26, 0.055, m)
        val body = s.formant(f0, doubleArrayOf(420.0, 1100.0, 2600.0), doubleArrayOf(1.0, 0.55, 0.30),
            doubleArrayOf(180.0, 320.0, 700.0), tilt = 0.6)
        val peak = body.max()
        if (peak > 1e-12) for (i in body.indices) body[i] /= peak
        val cut = lerp(3200.0, 11000.0, m)
        val amps = DoubleArray(s.h) { i ->
            val k = s.k[i]
            val comb = abs(sin(PI * k * beta)) / k
            comb * (0.30 + 0.70 * body[i]) * exp(-((k * f0 / cut).pow(1.6)))
        }
        val phases = DoubleArray(s.h) { 0.35 * sin(s.k[it]) }
        s.add(amps, phases) to "bow ${fmt(beta, 3)}"
    }

    val brass = WtRender { s, m, f0 ->
        val fc = lerp(500.0, 3200.0, m)
        val f = s.formant(f0, doubleArrayOf(fc, fc * 2.1), doubleArrayOf(1.0, 0.35), doubleArrayOf(fc * 0.55, fc * 0.8), tilt = 0.9)
        val cut = lerp(2200.0, 9000.0, m)
        val amps = DoubleArray(s.h) { i -> val q = s.k[i] * f0 / cut; f[i] * exp(-(q * q)) }
        s.add(amps) to "blow ${fmt(m, 2)}"
    }

    val bell = WtRender { s, m, _ ->
        val partials = intArrayOf(1, 2, 3, 5, 7, 9, 13, 17, 23)
        val amps = DoubleArray(partials.max())
        val ph = DoubleArray(partials.max())
        partials.forEachIndexed { j, k ->
            val w = (m * partials.size - j + 1.0).coerceIn(0.0, 1.0)
            amps[k - 1] = w / (1.0 + 0.55 * j)
            ph[k - 1] = bellRandoms[j] * 2 * PI
        }
        s.add(amps, ph) to "partials ${fmt(m, 2)}"
    }

    val noise = WtRender { s, m, _ ->
        val km = max(2, Math.rint(geom(3.0, s.h.toDouble(), m)).toInt())
        val amps = DoubleArray(s.h) { if (it < km) 1.0 / (it + 1.0).pow(0.5) else 0.0 }
        val phases = DoubleArray(s.h) { noisePhases[it] * 2 * PI }
        s.add(amps, phases) to "$km harm"
    }

    // --- vintage oscillator measurements ---------------------------------
    private val SAW_K = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 14.0, 19.0, 25.0, 33.0, 44.0, 59.0, 80.0, 107.0, 143.0, 191.0, 256.0)
    private val SAW_A = doubleArrayOf(1.0, 0.464957, 0.310573, 0.233347, 0.154043, 0.113546, 0.0893753, 0.0617407, 0.0428412, 0.0296247, 0.0195821, 0.0115579, 0.00582949, 0.00234654, 0.000716398, 0.000108901, 2.13059e-05, 1.95368e-05)
    private val TRI_K = doubleArrayOf(1.0, 3.0, 5.0, 7.0, 11.0, 13.0, 19.0, 25.0, 33.0, 45.0, 59.0, 79.0, 107.0, 143.0, 191.0, 255.0)
    private val TRI_A = doubleArrayOf(1.0, 0.0783355, 0.0289325, 0.0154596, 0.00537686, 0.00377635, 0.00137013, 0.000623957, 0.000207069, 0.000162785, 0.00012197, 9.58815e-05, 5.05027e-05, 2.54599e-05, 1.94472e-05, 1.43346e-05)
    private val SQ_K = doubleArrayOf(1.0, 3.0, 5.0, 7.0, 11.0, 13.0, 19.0, 25.0, 33.0, 44.0, 60.0, 80.0, 107.0, 143.0, 190.0, 255.0)
    private val SQ_R = doubleArrayOf(1.0, 1.03282, 1.03119, 1.02463, 1.00099, 0.988386, 0.934963, 0.872358, 0.777271, 0.505692, 0.341348, 0.196441, 0.0831758, 0.0291113, 0.0298395, 0.0245305)

    /** np.interp on log-log axes, holding the end values beyond the nodes. */
    private fun vintageCurve(k: Double, nodes: DoubleArray, vals: DoubleArray): Double {
        val x = ln(k)
        val xs = DoubleArray(nodes.size) { ln(nodes[it]) }
        val ys = DoubleArray(vals.size) { ln(vals[it]) }
        if (x <= xs[0]) return exp(ys[0])
        if (x >= xs.last()) return exp(ys.last())
        var i = 0
        while (xs[i + 1] < x) i++
        val f = (x - xs[i]) / (xs[i + 1] - xs[i])
        return exp(ys[i] + f * (ys[i + 1] - ys[i]))
    }

    val vintageSaw = WtRender { s, m, _ ->
        val amps = DoubleArray(s.h) { i ->
            val k = s.k[i]
            val saw = vintageCurve(k, SAW_K, SAW_A)
            val tri = if (k.toInt() % 2 == 1) vintageCurve(k, TRI_K, TRI_A) else 0.0
            saw + (tri - saw) * m
        }
        s.add(amps) to "saw→tri ${fmt(m, 2)}"
    }

    val vintageSquare = WtRender { s, m, _ ->
        val d = geom(0.50, 0.05, m)
        val sinA = DoubleArray(s.h) { i ->
            val k = s.k[i]
            (1 - kotlin.math.cos(2 * PI * k * d)) / k * vintageCurve(k, SQ_K, SQ_R)
        }
        val cosA = DoubleArray(s.h) { i ->
            val k = s.k[i]
            sin(2 * PI * k * d) / k * vintageCurve(k, SQ_K, SQ_R)
        }
        s.addSc(sinA, cosA) to "${fmt(d * 100, 2)}%"
    }

    val ALL: List<Pair<String, WtRender>> = listOf(
        "Saw" to saw,
        "Saw + Sub" to sawSub,
        "Vintage Saw" to vintageSaw,
        "Vintage Square" to vintageSquare,
        "Pulse / PWM" to pulse,
        "Triangle" to triangle,
        "Sine" to sine,
        "Wave Folder" to folder,
        "Hard Sync" to sync,
        "FM" to fm,
        "Phase Dist." to phaseDist,
        "Staircase" to staircase,
        "Vowel Formant" to vowel,
        "Organ" to organ,
        "Piano" to piano,
        "Strings" to strings,
        "Brass" to brass,
        "Bell / Metal" to bell,
        "Noise Morph" to noise,
    )
}
