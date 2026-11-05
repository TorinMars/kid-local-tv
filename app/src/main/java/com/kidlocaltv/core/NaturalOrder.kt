package com.kidlocaltv.core

/**
 * Case-insensitive "natural" ordering so that "Episode 2" sorts before "Episode 10".
 *
 * Written for speed: sorting a 2,400-video library took 3.9 s on a slow TV. Lowercasing a
 * non-ASCII character (every Chinese name) is a Unicode table lookup there, and names in one folder
 * share long prefixes. So equal characters are skipped without lowercasing, ASCII is lowercased by
 * hand, and digit runs are compared in place instead of being copied into new strings. (Most of
 * the rest was the app running interpreted before Android compiled it; see README.)
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            val digitA = isDigit(ca)
            val digitB = isDigit(cb)
            if (digitA && digitB) {
                val cmp = compareNumbers(a, i, b, j)
                if (cmp != 0) return cmp
                i = endOfDigits(a, i)
                j = endOfDigits(b, j)
            } else {
                if (ca != cb) {
                    val cmp = lower(ca).compareTo(lower(cb))
                    if (cmp != 0) return cmp
                }
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    /** Compares the digit runs starting at [i] in [a] and [j] in [b] by value, as the old code did. */
    private fun compareNumbers(a: String, i: Int, b: String, j: Int): Int {
        val endA = endOfDigits(a, i)
        val endB = endOfDigits(b, j)
        var startA = i
        var startB = j
        while (startA < endA && a[startA] == '0') startA++
        while (startB < endB && b[startB] == '0') startB++
        val lengthA = endA - startA
        val lengthB = endB - startB
        if (lengthA != lengthB) return lengthA - lengthB
        for (k in 0 until lengthA) {
            val cmp = a[startA + k].compareTo(b[startB + k])
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun endOfDigits(s: String, from: Int): Int {
        var k = from
        while (k < s.length && isDigit(s[k])) k++
        return k
    }

    private fun isDigit(c: Char): Boolean = if (c < '\u0080') c in '0'..'9' else c.isDigit()

    private fun lower(c: Char): Char = when {
        c in 'A'..'Z' -> c + 32
        c < '\u0080' -> c
        else -> c.lowercaseChar()
    }
}
