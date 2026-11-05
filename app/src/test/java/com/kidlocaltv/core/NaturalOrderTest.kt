package com.kidlocaltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NaturalOrderTest {

    @Test
    fun numbersSortByValue() {
        val sorted = listOf("Episode 10", "episode 2", "Episode 1", "Episode 002b").sortedWith(NaturalOrder)
        assertEquals(listOf("Episode 1", "episode 2", "Episode 002b", "Episode 10"), sorted)
    }

    @Test
    fun ignoresCaseButStaysTotal() {
        assertTrue(NaturalOrder.compare("abc", "ABD") < 0)
        assertTrue(NaturalOrder.compare("abc", "ABC") != 0)
        assertEquals(0, NaturalOrder.compare("小猪佩奇 第3集", "小猪佩奇 第3集"))
    }

    @Test
    fun matchesTheOriginalImplementation() {
        val random = Random(42)
        val alphabet = "aAbBzZ09 _-.第集小猪佩奇１２３ÄäΩω".toCharArray()
        val words = List(4000) {
            String(CharArray(random.nextInt(0, 12)) { alphabet[random.nextInt(alphabet.size)] })
        }
        for (k in 0 until 20000) {
            val a = words[random.nextInt(words.size)]
            val b = words[random.nextInt(words.size)]
            assertEquals("'$a' vs '$b'", Integer.signum(Original.compare(a, b)), Integer.signum(NaturalOrder.compare(a, b)))
        }
    }

    @Test
    fun libraryOrderMatchesFolderThenName() {
        val random = Random(7)
        val videos = List(500) { n ->
            val folder = listOf("", "动画/小猪佩奇", "动画/Bluey", "Songs 2", "Songs 10")[random.nextInt(5)]
            val name = "第${random.nextInt(1, 40)}集 ${n % 3}.mp4"
            Video(id = "$folder/$name/$n", folder = folder, name = name, location = VideoLocation.Network("s", "$n", 0))
        }
        val expected = videos.sortedWith(compareBy<Video, String>(Original) { it.folder }.thenBy(Original) { it.name })
        assertEquals(expected.map { it.id }, VideoLibrary(videos).videos.map { it.id })
    }

    /** The comparator before the speed-up, kept as the reference for the order. */
    private object Original : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = i
                    val startB = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val numA = a.substring(startA, i).trimStart('0')
                    val numB = b.substring(startB, j).trimStart('0')
                    if (numA.length != numB.length) return numA.length - numB.length
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return cmp
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return cmp
                    i++
                    j++
                }
            }
            val rest = (a.length - i) - (b.length - j)
            return if (rest != 0) rest else a.compareTo(b)
        }
    }
}
