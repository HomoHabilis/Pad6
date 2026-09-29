package io.github.pyp6.core.pattern

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hearing a pattern without the P-6: one loop rendered into a stereo buffer
 * that is then played looped, so timing is sample exact.
 *
 * Follows what decides WHICH audio plays WHEN - notes (PART, NOTE, VELO,
 * LENG, SUB, PROB, MT), TEMPO, SCALE, SHUFFLE, TRANSPOSE, PART_MUTE, and each
 * pad's .PRM (start/size, loop, gate, reverse, chop, tune, level, pan, amp
 * envelope, mono/poly, mute group). No filter, FX or motion. A port of the
 * desktop app's pattern preview.
 */
object PatternRender {
    const val SR = 44100
    const val ROOT_NOTE = 60
    const val MAX_CHOP = 64
    private val SCALE_BEATS = doubleArrayOf(0.5, 0.25, 0.125, 1.0 / 3, 1.0 / 6, 1.0 / 12)
    const val LENG_FULL = 100
    const val LENG_TIE = 255
    const val PROB_FULL = 10
    const val MAX_SUB_HITS = 8
    const val MAX_VOICE_SECONDS = 20.0

    data class Note(
        val step: Int, val part: Int, val note: Int, val velo: Int,
        val leng: Int, val sub: Int, val prob: Int, val mt: Int,
        /** How long the key is held, in steps, from its (shifted) start. */
        val steps: Double,
    ) {
        val start: Double get() = step + mt / 100.0
    }

    private class Seg(
        val step: Int, val part: Int, val note: Int, val velo: Int, val leng: Int,
        val sub: Int, val prob: Int, val mt: Int,
    ) {
        var next: Seg? = null
        var prev: Seg? = null
        fun toNote(steps: Double) = Note(step, part, note, velo, leng, sub, prob, mt, steps)
    }

    private val FIELD_RE = Regex("""\b([A-Z]+)(\d+)=(-?\d+)""")

    private fun stepEntries(p: P6Pattern): Map<Int, MutableList<Seg>> {
        val steps = HashMap<Int, MutableList<Seg>>()
        for (line in P6Pattern.splitLines(p.text)) {
            val m = P6Pattern.LINE_RE.find(line) ?: continue
            val key = m.groupValues[1].trim()
            val granular = when {
                key.startsWith("STEP_NOTE_SMPL ") -> false
                key.startsWith("STEP_NOTE_GRNL ") -> true
                else -> continue
            }
            val step = P6Pattern.intOr(key.split(Regex("""\s+""")).last(), 0)!!
            if (step !in 1..p.length) continue
            val byIndex = sortedMapOf<Int, HashMap<String, Int>>()
            for (f in FIELD_RE.findAll(m.groupValues[3])) {
                byIndex.getOrPut(f.groupValues[2].toInt()) { HashMap() }[f.groupValues[1]] = f.groupValues[3].toInt()
            }
            for ((_, f) in byIndex) {
                val part = if (granular) Parts.GRANULAR else (f["PART"] ?: -1)
                if (part < 0 || (f["NOTE"] ?: -1) < 0) continue
                steps.getOrPut(step - 1) { ArrayList() }.add(
                    Seg(step - 1, part, f.getValue("NOTE"), f["VELO"] ?: 0, f["LENG"] ?: 0,
                        f["SUB"] ?: 0, f["PROB"] ?: PROB_FULL, f["MT"] ?: 0)
                )
            }
        }
        return steps
    }

    private fun continues(e: Seg) = e.leng == LENG_TIE || e.mt + e.leng >= LENG_FULL

    /** Every note in step order, with held notes (written once per step) joined up. */
    fun stepNotes(p: P6Pattern): List<Note> {
        val length = max(1, p.length)
        val steps = stepEntries(p)
        for (s in 0 until length) {
            val nxt = steps[(s + 1) % length] ?: emptyList<Seg>()
            for (e in steps[s] ?: emptyList<Seg>()) {
                if (!continues(e)) continue
                for (cand in nxt) {
                    if (cand.prev == null && cand !== e && cand.part == e.part && cand.note == e.note) {
                        e.next = cand; cand.prev = e
                        break
                    }
                }
            }
        }
        fun lastPart(e: Seg): Double {
            val leng = if (e.leng == LENG_TIE) LENG_FULL else e.leng
            return max(leng, 1) / 100.0
        }
        fun held(head: Seg): Double {
            if (head.next == null) {
                if (head.leng == LENG_TIE) return max(LENG_FULL - head.mt, 1) / 100.0
                return lastPart(head)
            }
            var total = (LENG_FULL - head.mt) / 100.0
            var e = head.next!!
            while (e.next != null) {
                total += 1.0
                e = e.next!!
            }
            return total + lastPart(e)
        }
        val notes = ArrayList<Note>()
        val done = HashSet<Seg>()
        for (s in 0 until length) {
            for (e in steps[s] ?: emptyList<Seg>()) {
                if (e.prev != null) continue
                var e2: Seg? = e
                while (e2 != null) { done.add(e2); e2 = e2.next }
                notes.add(e.toNote(held(e)))
            }
        }
        for (s in 0 until length) {
            for (e in steps[s] ?: emptyList<Seg>()) {
                if (e in done) continue
                var e2: Seg = e
                while (e2 !in done) {
                    done.add(e2)
                    e2 = e2.next ?: break
                }
                notes.add(e.toNote(length.toDouble()))
            }
        }
        return mergeOverlappingKeys(notes, length)
    }

    private fun mergeOverlappingKeys(notes: List<Note>, length: Int): List<Note> {
        val byKey = LinkedHashMap<Pair<Int, Int>, MutableList<Note>>()
        for (n in notes) byKey.getOrPut(n.part to n.note) { ArrayList() }.add(n)
        val out = ArrayList<Note>()
        for (group0 in byKey.values) {
            val group = group0.sortedBy { it.start }
            val merged = ArrayList<Note>()
            for (n in group) {
                if (merged.isNotEmpty()) {
                    val cur = merged.last()
                    val end = cur.start + cur.steps
                    if (n.start < end - 1e-6) {
                        merged[merged.size - 1] = cur.copy(steps = max(end, n.start + n.steps) - cur.start)
                        continue
                    }
                }
                merged.add(n)
            }
            if (merged.size > 1) {
                val last = merged.last()
                val first = merged.first()
                val over = last.start + last.steps - length
                if (over > first.start + 1e-6) {
                    merged[merged.size - 1] = last.copy(
                        steps = max(over, first.start + first.steps) + length - last.start
                    )
                    merged.removeAt(0)
                }
            }
            for (n in merged) out.add(n.copy(steps = min(n.steps, length.toDouble())))
        }
        return out.sortedWith(compareBy<Note>({ it.step }, { it.part }, { it.note }))
    }

    /** P-6 envelope time (0-255) to seconds; a curve, not a measurement. */
    fun envSeconds(value: Int): Double {
        val v = value.coerceIn(0, 255) / 255.0
        return 8.0 * v * v * v
    }

    private fun panGains(pan: Int): Pair<Double, Double> {
        val p = pan.coerceIn(0, 127) / 127.0
        return cos(p * PI / 2) to sin(p * PI / 2)
    }

    private fun velocityGain(velocity: Int) =
        if (velocity > 0) 0.35 + 0.65 * (velocity.coerceIn(1, 127) / 127.0) else 0.85

    /** One pad as the preview plays it: audio (stereo) plus its voice settings. */
    class PadVoice(left: FloatArray, right: FloatArray, val fs: Double, prm: Map<String, Int>?) {
        val l = left
        val r = right
        val hasPrm = !prm.isNullOrEmpty()
        private val p = prm ?: emptyMap()
        val n = l.size
        val start: Int
        val size: Int
        val loopLen: Int
        val loop: Boolean
        val gate: Boolean
        val reverse: Int = p["REVERSE"] ?: 0
        val chop: Int
        val tune: Double
        val level: Double = (p["LEVEL"] ?: 100) / 100.0
        val pan = panGains(p["PAN"] ?: 64)
        val attack = envSeconds(p["TENV_ATTACK"] ?: 0)
        val decay = envSeconds(p["TENV_DECAY"] ?: 0)
        val sustain = (p["TENV_SUSTAIN"] ?: 255).coerceIn(0, 255) / 255.0
        val release = envSeconds(p["TENV_RELEASE"] ?: 0)
        val poly = (p["MONO_POLY"] ?: 0) != 0
        val muteGroup = p["MUTE_GROUP"] ?: 0

        init {
            val s0 = (p["START_POS"] ?: 0).coerceIn(0, max(n - 1, 0))
            var sz = p["SIZE"] ?: 0
            if (sz <= 0 || s0 + sz > n) sz = n - s0
            start = s0
            size = max(sz, 1)
            val ls = p["LOOP_SIZE"] ?: 0
            loopLen = if (ls > 0) min(ls, size) else size
            loop = if (hasPrm) (p["LOOP"] ?: 0) != 0 else false
            gate = if (hasPrm) (p["GATE"] ?: 0) != 0 else false
            val c = p["CHOP"] ?: 1
            chop = if (c in 2..MAX_CHOP) c else 1
            tune = (p["C.TUNE"] ?: 0) + (p["F.TUNE"] ?: 0) / 100.0
        }

        private data class Region(val start: Int, val size: Int, val loopLen: Int, val speed: Double)

        private fun regionFor(note: Int, transpose: Int): Region? {
            val rel = (if (note >= 0) note else ROOT_NOTE) - ROOT_NOTE
            val base = fs / SR
            if (chop > 1) {
                if (rel !in 0 until chop) return null
                val s0 = Math.rint(rel.toDouble() * n / chop).toInt()
                val s1 = Math.rint((rel + 1).toDouble() * n / chop).toInt()
                val len = max(s1 - s0, 1)
                return Region(s0, len, len, base * 2.0.pow(tune / 12.0))
            }
            val semis = rel + transpose + tune
            return Region(start, size, loopLen, base * 2.0.pow(semis / 12.0))
        }

        /** One note as (left, right), or null. */
        fun render(note: Int, velocity: Int, gateS: Double, cutS: Double, transpose: Int = 0): Pair<FloatArray, FloatArray>? {
            val region = regionFor(note, transpose) ?: return null
            val speed = region.speed
            if (speed <= 0) return null
            val natural = region.size / speed / SR     // seconds the region plays once
            val rel = if (gate || loop) release else 0.0
            // LOOP on a chopped pad loops the slice the note plays, like the
            // device: it keeps repeating through the release.
            var length = when {
                loop -> gateS + rel
                gate -> min(natural, gateS + rel)
                else -> natural
            }
            length = minOf(length, cutS, MAX_VOICE_SECONDS)
            val frames = (length * SR).toInt()
            if (frames < 2) return null
            val env = envelope(frames, gateS, rel)
            val gain = level * velocityGain(velocity)
            val gl = (gain * pan.first * sqrt(2.0)).toFloat()
            val gr = (gain * pan.second * sqrt(2.0)).toFloat()
            val outL = FloatArray(frames)
            val outR = FloatArray(frames)
            val loops = loop && natural < length
            val head = region.size - region.loopLen
            for (i in 0 until frames) {
                var pos = i * speed
                if (loops && pos >= region.size) {
                    var rr = pos - region.size
                    if (reverse == 2) {
                        val period = 2.0 * region.loopLen
                        rr %= period
                        rr = if (rr < region.loopLen) region.loopLen - 1 - rr else rr - region.loopLen
                    } else rr %= region.loopLen
                    pos = head + rr
                }
                pos = min(pos, (region.size - 1).toDouble())
                if (reverse == 1) pos = (region.size - 1) - pos
                pos += region.start
                val i0 = pos.toInt().coerceIn(0, n - 1)
                val i1 = min(i0 + 1, n - 1)
                val frac = (pos - pos.toInt()).toFloat()
                val e = env[i]
                outL[i] = (l[i0] * (1 - frac) + l[i1] * frac) * e * gl
                outR[i] = (r[i0] * (1 - frac) + r[i1] * frac) * e * gr
            }
            return outL to outR
        }

        private fun envelope(frames: Int, gateS: Double, rel: Double): FloatArray {
            val env = FloatArray(frames)
            for (i in 0 until frames) {
                val t = i.toDouble() / SR
                var e = 1.0
                if (attack > 0.002) e = min(e, t / attack)
                if (gate || loop) {
                    if (decay > 0.002 && sustain < 1.0) {
                        val d = ((t - attack) / decay).coerceIn(0.0, 1.0)
                        e *= 1.0 - (1.0 - sustain) * d
                    } else if (sustain < 1.0) {
                        if (t > attack) e *= sustain
                    }
                    if (rel > 0.002) e *= (1.0 - (t - gateS) / rel).coerceIn(0.0, 1.0)
                    else if (t >= gateS) e = 0.0
                }
                env[i] = e.toFloat()
            }
            val edge = min((0.002 * SR).toInt(), frames / 2)
            if (edge > 0) {
                for (i in 0 until edge) {
                    val ramp = if (edge == 1) 0f else i.toFloat() / (edge - 1)
                    env[i] *= ramp
                    env[frames - 1 - i] *= ramp
                }
            }
            return env
        }

        /** A granular note: a cloud of windowed grains from a moving playhead. */
        fun renderGrains(note: Int, velocity: Int, gateS: Double, cutS: Double, g: Map<String, Int>): Pair<FloatArray, FloatArray>? {
            val rel = envSeconds(g["GRANU_TENV_RELEASE"] ?: 0)
            val att = envSeconds(g["GRANU_TENV_ATTACK"] ?: 0)
            val length = minOf(gateS + rel, cutS, MAX_VOICE_SECONDS)
            val frames = (length * SR).toInt()
            if (frames < 2 || n < 16) return null
            val relNote = (if (note >= 0) note else ROOT_NOTE) - ROOT_NOTE
            val semis = relNote + (g["GRANU_COARSE_TUNE"] ?: 0) + (g["GRANU_FINE_TUNE"] ?: 0) / 100.0
            val pitch = fs / SR * 2.0.pow(semis / 12.0)
            val sizeSrc = (g["GRANU_SIZE"] ?: 1764).coerceIn(64, max(64, min(n, fs.toInt())))
            var grain = max((sizeSrc / pitch).toInt(), 32)
            grain = min(grain, (0.5 * SR).toInt())
            val density = (g["GRANU_GRAINS"] ?: 255).coerceIn(1, 255) / 255.0
            val overlap = 1.0 + 3.0 * density
            val hop = max((grain / overlap).toInt(), 16)
            val window = io.github.pyp6.core.dsp.hanning(grain)
            val head0 = Math.floorMod(g["GRANU_HEAD_POS"] ?: 0, n)
            val headSpeed = (g["GRANU_HEAD_SPEED"] ?: 100) / 100.0 * fs / SR
            val spread = (n * ((g["GRANU_SPREAD"] ?: 0).coerceIn(0, 255) / 255.0) * 0.1).toInt()
            val jitter = max((sizeSrc * 0.25).toInt(), 1)
            val rng = Random(note * 7919 + frames)
            val outL = FloatArray(frames + grain)
            val outR = FloatArray(frames + grain)
            var at = 0
            while (at < frames) {
                var src = head0 + at * headSpeed + rng.nextInt(-jitter, jitter + 1)
                if (spread > 0) src += rng.nextInt(-spread, spread + 1)
                for (k in 0 until grain) {
                    val pos = Math.floorMod((src + k * pitch).toLong(), n.toLong()).toInt()
                    val w = window[k].toFloat()
                    outL[at + k] += l[pos] * w
                    outR[at + k] += r[pos] * w
                }
                val jig = hop / 3
                at += max(1, hop + if (jig > 0) rng.nextInt(-jig, jig + 1) else 0)
            }
            val gain = (g["GRANU_LEVEL"] ?: 100).coerceIn(0, 127) / 100.0 / max(overlap / 2.0, 1.0)
            val vel = velocityGain(velocity)
            val l2 = FloatArray(frames)
            val r2 = FloatArray(frames)
            for (i in 0 until frames) {
                val t = i.toDouble() / SR
                var e = 1.0
                if (att > 0.002) e = min(e, t / att)
                e *= if (rel > 0.002) (1.0 - (t - gateS) / rel).coerceIn(0.0, 1.0) else if (t < gateS) 1.0 else 0.0
                val f = (gain * e * vel).toFloat()
                l2[i] = outL[i] * f
                r2[i] = outR[i] * f
            }
            return l2 to r2
        }
    }

    class Result(
        val left: FloatArray,
        val right: FloatArray,
        val stepSeconds: Double,
        val missing: Set<Pair<Char, Int>>,
        val notesPlayed: Int,
        val chopped: Set<Pair<Char, Int>>,
    ) {
        val frames: Int get() = left.size
    }

    private class Hit(val t: Double, val note: Note, val voice: PadVoice, val gate: Double, val key: Any?) {
        var cut: Double? = null
    }

    fun stepSeconds(p: P6Pattern): Double {
        val tempo = if (p.tempo > 0) p.tempo else 120.0
        val scale = P6Pattern.intOr(p.value("SCALE"), 1)!!
        val beats = if (scale in SCALE_BEATS.indices) SCALE_BEATS[scale] else 0.25
        return 60.0 / tempo * beats
    }

    /** Renders one loop of [p]. [voiceFor] returns null for an empty pad. */
    fun render(p: P6Pattern, voiceFor: (Char, Int) -> PadVoice?, rng: Random = Random.Default): Result {
        val stepS = stepSeconds(p)
        val steps = max(1, p.length)
        val loopS = steps * stepS
        val total = max(1, Math.rint(loopS * SR).toInt())
        val transpose = P6Pattern.intOr(p.value("TRANSPOSE"), 0)!!
        val swing = (P6Pattern.intOr(p.value("SHUFFLE"), 0)!!).coerceIn(0, 75) / 100.0 * stepS

        val notes = stepNotes(p)
        val granular = p.values.filterKeys { it.startsWith("GRANU_") }.mapValues { P6Pattern.intOr(it.value, 0)!! }
        val granWhere = Parts.padFor(p.granularSource)
        val voices = HashMap<Pair<Char, Int>, PadVoice?>()
        val missing = LinkedHashSet<Pair<Char, Int>>()
        val chopped = LinkedHashSet<Pair<Char, Int>>()

        fun voice(part: Int): PadVoice? {
            val where = (if (part == Parts.GRANULAR) granWhere else Parts.padFor(part)) ?: return null
            if (where !in voices) {
                val v = voiceFor(where.first, where.second)
                voices[where] = v
                if (v == null) missing.add(where)
                else if (v.chop > 1 && part != Parts.GRANULAR) chopped.add(where)
            }
            return voices[where]
        }

        val hits = ArrayList<Hit>()
        for (n in notes) {
            if (n.part in p.mutedParts) continue
            val prob = min(n.prob, PROB_FULL) / PROB_FULL.toDouble()
            val v = voice(n.part) ?: continue
            var t0 = n.step * stepS + (if (n.step % 2 == 1) swing else 0.0)
            t0 += n.mt.coerceIn(-100, 100) / 100.0 * stepS
            val count = 1 + n.sub.coerceIn(0, MAX_SUB_HITS - 1)
            var gate = n.steps * stepS
            if (count > 1) gate = min(gate, stepS / count)
            for (k in 0 until count) {
                if (prob < 1.0 && rng.nextDouble() >= prob) continue
                val t = (t0 + k * stepS / count).mod(loopS)
                val key: Any? = when {
                    n.part == Parts.GRANULAR -> "granular"
                    v.muteGroup != 0 -> "group" to v.muteGroup
                    !v.poly -> "part" to n.part
                    else -> null
                }
                hits.add(Hit(t, n, v, gate, key))
            }
        }
        hits.sortBy { it.t }
        val byKey = LinkedHashMap<Any, MutableList<Hit>>()
        for (h in hits) h.key?.let { byKey.getOrPut(it) { ArrayList() }.add(h) }
        for (group in byKey.values) {
            for ((i, h) in group.withIndex()) {
                var nxt = group[(i + 1) % group.size].t
                if (i + 1 == group.size) nxt += loopS
                h.cut = if (group.size > 1) max(nxt - h.t, 0.005) else loopS
            }
        }
        val bufL = FloatArray(total)
        val bufR = FloatArray(total)
        var played = 0
        for (h in hits) {
            val cut = h.cut ?: MAX_VOICE_SECONDS
            val audio = if (h.note.part == Parts.GRANULAR) h.voice.renderGrains(h.note.note, h.note.velo, h.gate, cut, granular)
            else h.voice.render(h.note.note, h.note.velo, h.gate, cut, transpose)
            audio ?: continue
            played++
            var at = Math.floorMod(Math.rint(h.t * SR).toLong(), total.toLong()).toInt()
            var pos = 0
            val len = audio.first.size
            while (pos < len) {
                val room = min(total - at, len - pos)
                for (k in 0 until room) {
                    bufL[at + k] += audio.first[pos + k]
                    bufR[at + k] += audio.second[pos + k]
                }
                pos += room
                at = 0
            }
        }
        val level = P6Pattern.intOr(p.value("LEVEL"), 100)!!
        val scale = 0.5 * (if (level != 0) max(level, 0) / 100.0 else 1.0)
        var peak = 0.0
        for (i in 0 until total) {
            bufL[i] = (bufL[i] * scale).toFloat()
            bufR[i] = (bufR[i] * scale).toFloat()
            peak = max(peak, kotlin.math.abs(bufL[i].toDouble()))
            peak = max(peak, kotlin.math.abs(bufR[i].toDouble()))
        }
        if (peak > 0.95) {
            val g = (0.95 / peak).toFloat()
            for (i in 0 until total) { bufL[i] *= g; bufR[i] *= g }
        }
        return Result(bufL, bufR, stepS, missing, played, chopped)
    }
}
