package com.kidlocaltv.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PrefetchInputStreamTest {

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun readAll(input: InputStream, chunk: Int = 16 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(chunk)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Returns at most [maxPerRead] bytes per call, like a network source, and counts the calls. */
    private class TrickleStream(data: ByteArray, private val maxPerRead: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        val calls = AtomicInteger()
        val closed = AtomicBoolean()
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            calls.incrementAndGet()
            return inner.read(b, off, minOf(len, maxPerRead))
        }
        override fun close() { closed.set(true) }
    }

    @Test
    fun deliversEveryByteInOrder() {
        val data = bytes(3 * 1024 * 1024 + 123)
        val stream = PrefetchInputStream(ByteArrayInputStream(data), firstBlock = 1000, maxBlock = 64 * 1024)
        assertArrayEquals(data, readAll(stream, chunk = 777))
        assertEquals(-1, stream.read())
    }

    @Test
    fun emptySourceEndsAtOnce() {
        val stream = PrefetchInputStream(ByteArrayInputStream(ByteArray(0)))
        assertEquals(-1, stream.read(ByteArray(10), 0, 10))
    }

    @Test
    fun singleByteReadsWork() {
        val data = bytes(5000)
        val stream = PrefetchInputStream(ByteArrayInputStream(data), firstBlock = 64, maxBlock = 1024)
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = stream.read().toByte()
        assertArrayEquals(data, out)
        assertEquals(-1, stream.read())
    }

    @Test
    fun asksTheSourceForLargeBlocks() {
        // The consumer reads 16 KB at a time; the source should still be asked for big blocks.
        val data = bytes(4 * 1024 * 1024)
        val source = TrickleStream(data, maxPerRead = 1 shl 20)
        val stream = PrefetchInputStream(source, firstBlock = 64 * 1024, maxBlock = 512 * 1024)
        assertArrayEquals(data, readAll(stream, chunk = 16 * 1024))
        // 4 MB in 16 KB reads would be 256 source calls; with growing blocks it is about a dozen.
        assertTrue("source calls: ${source.calls.get()}", source.calls.get() < 20)
    }

    @Test
    fun shortSourceReadsAreCombined() {
        val data = bytes(200_000)
        val stream = PrefetchInputStream(TrickleStream(data, maxPerRead = 1000), firstBlock = 50_000, maxBlock = 50_000)
        assertArrayEquals(data, readAll(stream))
    }

    @Test
    fun sourceErrorReachesTheReaderAfterTheGoodBytes() {
        val good = bytes(10_000)
        val failing = object : InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw IOException("share went away")
                sent = true
                System.arraycopy(good, 0, b, off, good.size)
                return good.size
            }
        }
        val stream = PrefetchInputStream(failing, firstBlock = good.size, maxBlock = good.size)
        val buf = ByteArray(good.size)
        var n = 0
        while (n < good.size) n += stream.read(buf, n, good.size - n)
        assertArrayEquals(good, buf)
        try {
            stream.read(buf, 0, 1)
            fail("expected the source's IOException")
        } catch (e: IOException) {
            assertEquals("share went away", e.message)
        }
    }

    @Test
    fun closeClosesTheSourceAndStopsReading() {
        val source = TrickleStream(bytes(10 * 1024 * 1024), maxPerRead = 4096)
        val stream = PrefetchInputStream(source, firstBlock = 4096, maxBlock = 4096, queueBlocks = 2)
        stream.read(ByteArray(100), 0, 100)
        stream.close()
        assertTrue(source.closed.get())
        Thread.sleep(300)
        val callsAfterClose = source.calls.get()
        Thread.sleep(300)
        assertEquals("the worker kept reading after close", callsAfterClose, source.calls.get())
        try {
            stream.read(ByteArray(1), 0, 1)
            fail("read after close should fail")
        } catch (_: IOException) {
        }
    }
}
