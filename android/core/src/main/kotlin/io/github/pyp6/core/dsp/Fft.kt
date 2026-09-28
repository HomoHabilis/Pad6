package io.github.pyp6.core.dsp

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT for any length.
 *
 * Powers of two use an iterative radix-2 transform. Everything else (a
 * wavetable segment of 674 frames, a cycle of 2048/3 points) goes through
 * Bluestein's algorithm, which turns an arbitrary-length DFT into a
 * convolution done with power-of-two transforms. numpy's pocketfft gives the
 * same results to within floating point rounding.
 */
object Fft {
    private val twiddles = ConcurrentHashMap<Int, Pair<DoubleArray, DoubleArray>>()
    private val bluesteinCache = ConcurrentHashMap<Int, Bluestein>()

    fun isPowerOfTwo(n: Int) = n > 0 && (n and (n - 1)) == 0

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** In-place complex transform. inverse = true computes the unscaled inverse. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(im.size == n)
        if (n <= 1) return
        if (isPowerOfTwo(n)) radix2(re, im, inverse) else bluestein(re, im, inverse)
    }

    private fun radix2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val (cosT, sinT) = twiddles.getOrPut(n) {
            val c = DoubleArray(n / 2) { cos(2 * PI * it / n) }
            val s = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
            c to s
        }
        val sign = if (inverse) -1.0 else 1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val a = i + m
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    private class Bluestein(val n: Int) {
        val m = nextPowerOfTwo(2 * n - 1)
        val wr = DoubleArray(n)
        val wi = DoubleArray(n)
        val br = DoubleArray(m)
        val bi = DoubleArray(m)

        init {
            for (k in 0 until n) {
                // k*k can overflow Int for large n; reduce modulo 2n first.
                val kk = (k.toLong() * k) % (2L * n)
                val ang = PI * kk / n
                wr[k] = cos(ang)
                wi[k] = -sin(ang)
            }
            br[0] = wr[0]; bi[0] = -wi[0]
            for (k in 1 until n) {
                br[k] = wr[k]; bi[k] = -wi[k]
                br[m - k] = wr[k]; bi[m - k] = -wi[k]
            }
            radix2Static(br, bi, false)
        }
    }

    private fun radix2Static(re: DoubleArray, im: DoubleArray, inverse: Boolean) = radix2(re, im, inverse)

    private fun bluestein(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (inverse) {
            // inverse DFT = conj(DFT(conj(x)))
            for (i in 0 until n) im[i] = -im[i]
            bluestein(re, im, false)
            for (i in 0 until n) im[i] = -im[i]
            return
        }
        val b = bluesteinCache.getOrPut(n) { Bluestein(n) }
        val m = b.m
        val ar = DoubleArray(m)
        val ai = DoubleArray(m)
        for (k in 0 until n) {
            ar[k] = re[k] * b.wr[k] - im[k] * b.wi[k]
            ai[k] = re[k] * b.wi[k] + im[k] * b.wr[k]
        }
        radix2(ar, ai, false)
        for (k in 0 until m) {
            val r = ar[k] * b.br[k] - ai[k] * b.bi[k]
            val i = ar[k] * b.bi[k] + ai[k] * b.br[k]
            ar[k] = r; ai[k] = i
        }
        radix2(ar, ai, true)
        for (k in 0 until n) {
            val r = ar[k] / m
            val i = ai[k] / m
            re[k] = r * b.wr[k] - i * b.wi[k]
            im[k] = r * b.wi[k] + i * b.wr[k]
        }
    }

    /** numpy.fft.rfft: returns (re, im) with n/2+1 bins. */
    fun rfft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = x.copyOf()
        val im = DoubleArray(n)
        transform(re, im, false)
        val h = n / 2 + 1
        return re.copyOf(h) to im.copyOf(h)
    }

    /** numpy.fft.irfft(spec, n). Missing bins count as zero, extra ones are ignored. */
    fun irfft(specRe: DoubleArray, specIm: DoubleArray, n: Int): DoubleArray {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val h = n / 2 + 1
        val have = minOf(h, specRe.size)
        for (k in 0 until have) {
            re[k] = specRe[k]
            im[k] = specIm[k]
        }
        // numpy discards the imaginary part of DC and (for even n) Nyquist.
        im[0] = 0.0
        if (n % 2 == 0 && have == h) im[h - 1] = 0.0
        for (k in 1 until h) {
            val mirror = n - k
            if (mirror > k && mirror < n) {
                re[mirror] = re[k]
                im[mirror] = -im[k]
            }
        }
        transform(re, im, true)
        for (i in 0 until n) re[i] /= n
        return re
    }

    /** Magnitudes of rfft(x). */
    fun rfftMagnitude(x: DoubleArray): DoubleArray {
        val (r, i) = rfft(x)
        return DoubleArray(r.size) { kotlin.math.sqrt(r[it] * r[it] + i[it] * i[it]) }
    }
}

/** numpy.hanning(n): symmetric Hann window. */
fun hanning(n: Int): DoubleArray = when {
    n < 1 -> DoubleArray(0)
    n == 1 -> doubleArrayOf(1.0)
    else -> DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
}

/** numpy.linspace(a, b, n) with the end point included. */
fun linspace(a: Double, b: Double, n: Int): DoubleArray = when {
    n <= 0 -> DoubleArray(0)
    n == 1 -> doubleArrayOf(a)
    else -> DoubleArray(n) { a + (b - a) * it / (n - 1) }
}
