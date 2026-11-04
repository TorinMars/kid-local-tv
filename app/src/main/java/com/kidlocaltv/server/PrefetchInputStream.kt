package com.kidlocaltv.server

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads [source] ahead on its own thread, in large blocks, while the caller consumes earlier ones.
 *
 * The HTTP server copies a response body 16 KB at a time, and every read from an SMB file is a
 * network round trip. Without read-ahead, streaming from a share was a long chain of small,
 * back-to-back requests, and on a slow TV the video starved: VLC logged "PCR is called too late",
 * kept raising its buffer, and the audio underran. Large blocks cut the number of SMB requests, and
 * reading ahead hides each request's latency behind playback.
 *
 * Blocks start at [firstBlock] so the first bytes (VLC probing the file) arrive quickly, then double
 * up to [maxBlock]. At most [queueBlocks] blocks wait unread, which bounds the memory used.
 */
class PrefetchInputStream(
    private val source: InputStream,
    private val firstBlock: Int = FIRST_BLOCK,
    private val maxBlock: Int = MAX_BLOCK,
    queueBlocks: Int = QUEUE_BLOCKS,
    threadName: String = "smb-prefetch",
) : InputStream() {

    private class Chunk(val data: ByteArray, val length: Int, val error: IOException? = null, val end: Boolean = false)

    private val queue = ArrayBlockingQueue<Chunk>(queueBlocks)
    @Volatile private var closed = false
    private var current: Chunk? = null
    private var pos = 0
    private var finished = false
    private val worker = Thread({ fill() }, threadName).apply {
        isDaemon = true
        start()
    }

    private fun fill() {
        var blockSize = firstBlock
        try {
            while (!closed) {
                val buf = ByteArray(blockSize)
                var n = 0
                // The source may return less than asked per call; fill the block unless the stream ends.
                while (n < blockSize) {
                    val r = source.read(buf, n, blockSize - n)
                    if (r < 0) break
                    n += r
                }
                if (n > 0) put(Chunk(buf, n))
                if (n < blockSize) {
                    put(END)
                    return
                }
                blockSize = minOf(blockSize * 2, maxBlock)
            }
        } catch (e: InterruptedException) {
            // Closed while waiting for room in the queue.
        } catch (e: Exception) {
            if (!closed) {
                val error = e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
                try {
                    put(Chunk(EMPTY, 0, error = error))
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun put(chunk: Chunk) {
        while (!closed) {
            if (queue.offer(chunk, POLL_MS, TimeUnit.MILLISECONDS)) return
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            if (closed) throw IOException("Stream closed")
            if (finished) return -1
            val chunk = current
            if (chunk != null && pos < chunk.length) {
                val n = minOf(len, chunk.length - pos)
                System.arraycopy(chunk.data, pos, b, off, n)
                pos += n
                return n
            }
            val next = try {
                queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", e)
            } ?: continue
            next.error?.let {
                finished = true
                throw it
            }
            if (next.end) {
                finished = true
                return -1
            }
            current = next
            pos = 0
        }
    }

    override fun available(): Int {
        val chunk = current ?: return 0
        return chunk.length - pos
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.interrupt()
        queue.clear()
        current = null
        source.close()
    }

    companion object {
        const val FIRST_BLOCK = 64 * 1024
        const val MAX_BLOCK = 512 * 1024
        const val QUEUE_BLOCKS = 4
        private const val POLL_MS = 100L
        private val EMPTY = ByteArray(0)
        private val END = Chunk(EMPTY, 0, end = true)
    }
}
