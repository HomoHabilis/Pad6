package io.github.pyp6.core.io

import java.text.Normalizer
import java.util.UUID

/**
 * File names that survive the P-6's FAT drive: plain ASCII, nothing FAT or
 * Windows rejects, no leading dot, no trailing dot/space, a sane length.
 * Port of safe_base_name.
 */
object SafeName {
    private val TRANSLITERATE = mapOf(
        'ß' to "ss", 'æ' to "ae", 'Æ' to "AE", 'ø' to "oe", 'Ø' to "OE",
        'ł' to "l", 'Ł' to "L", 'đ' to "d", 'Đ' to "D",
        'þ' to "th", 'Þ' to "TH", 'ð' to "d", 'Ð' to "D",
        'œ' to "oe", 'Œ' to "OE", 'å' to "aa", 'Å' to "AA",
    )
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    private val TEMP_TAG = Regex("""_(?:trim|norm|fade|fit|mono|imp|chop|conv|edit|wt)(?:_[A-H][1-6])?_[0-9a-f]{6,8}$""",
        RegexOption.IGNORE_CASE)

    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }

    fun baseName(path: String?, maxLen: Int = 48, stripTags: Boolean = false, fallback: String = "sample"): String {
        var base = stem(path ?: "")
        base = buildString { for (c in base) append(TRANSLITERATE[c] ?: c.toString()) }
        base = Normalizer.normalize(base, Normalizer.Form.NFKD)
        base = base.filter { it.code < 128 }
        base = base.replace(Regex("""[^A-Za-z0-9_ \-.]"""), "_")
        base = base.trim().trimStart('.')
        if (stripTags) {
            while (true) {
                val s = TEMP_TAG.replace(base, "")
                if (s == base) break
                base = s
            }
        }
        base = base.take(maxLen).trim(' ', '.')
        if (base.uppercase() in RESERVED) base += "_"
        return base.ifEmpty { fallback }
    }

    /** "<source name>_<tag>_<random>.wav": recognisable, and never colliding. */
    fun derived(source: String?, tag: String, ext: String = ".wav"): String =
        "${baseName(source, stripTags = true)}_${tag}_${UUID.randomUUID().toString().replace("-", "").take(8)}$ext"

    /** AppleDouble companions ("._Kick.wav") macOS leaves on FAT drives. */
    fun isSidecar(name: String): Boolean = name.substringAfterLast('/').startsWith("._")
}
