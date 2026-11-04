package com.kidlocaltv.server

import com.kidlocaltv.core.VideoLocation
import fi.iki.elonen.NanoHTTPD
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams videos from SMB shares to the player without copying them to the TV.
 *
 * The player gets an ordinary http://127.0.0.1 URL; this server answers it by reading the file
 * from the share on demand, including HTTP Range requests so seeking and resuming work.
 * It only listens on the loopback interface, so it can't be reached from the network.
 */
class SmbStreamServer(private val sources: NetworkSources) : NanoHTTPD(LOOPBACK, 0) {

    /** One SMB connection per share, reused across requests and reopened after errors. */
    private val connections = ConcurrentHashMap<String, SmbSource>()

    /** Shares that just failed to connect; answer immediately instead of waiting on timeouts again. */
    private val offlineUntil = ConcurrentHashMap<String, Long>()

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, true)
    }

    fun stopServer() {
        stop()
        connections.values.forEach { it.close() }
        connections.clear()
    }

    fun urlFor(location: VideoLocation.Network): String =
        "http://$LOOPBACK:$listeningPort/stream/${location.sourceId}/${encode(location.path)}"

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        }
        // URI is already percent-decoded by NanoHTTPD: /stream/<sourceId>/<path on share>
        val parts = session.uri.removePrefix("/").split('/', limit = 3)
        if (parts.size != 3 || parts[0] != "stream") return notFound()
        val source = sources.find(parts[1]) ?: return notFound()
        val path = parts[2]

        if ((offlineUntil[source.id] ?: 0L) > System.currentTimeMillis()) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Share is offline")
        }
        val remote = try {
            open(source, path)
        } catch (e: Exception) {
            if (e is ShareUnreachable) {
                // Couldn't even connect: treat the whole share as offline for a minute.
                offlineUntil[source.id] = System.currentTimeMillis() + OFFLINE_RETRY_MS
            }
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, NetworkSources.readableError(e)
            )
        }
        offlineUntil.remove(source.id)

        val size = remote.size
        val range = parseRange(session.headers["range"], size)
        if (range == null) {
            remote.close()
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                addHeader("Content-Range", "bytes */$size")
            }
        }
        val (start, end) = range
        val length = end - start + 1
        val partial = session.headers.containsKey("range")
        val body = if (session.method == Method.HEAD) {
            remote.close()
            java.io.ByteArrayInputStream(ByteArray(0))
        } else {
            remote.stream(start, end)
        }
        return newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mimeFor(path),
            body,
            length,
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$size")
        }
    }

    private fun open(source: NetworkSources.Source, path: String): SmbSource.RemoteFile {
        // Try the cached connection first; if it has gone stale, reconnect once.
        for (attempt in 0..1) {
            val smb = try {
                connection(source)
            } catch (e: Exception) {
                throw ShareUnreachable(e)
            }
            try {
                return smb.openRemote(path)
            } catch (e: Exception) {
                connections.remove(source.id, smb)
                smb.close()
                if (attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    private fun connection(source: NetworkSources.Source): SmbSource {
        connections[source.id]?.let { if (it.healthy) return it else { connections.remove(source.id, it); it.close() } }
        synchronized(this) {
            connections[source.id]?.let { if (it.healthy) return it }
            val smb = SmbSource(source.link)
            smb.connect()
            connections[source.id] = smb
            return smb
        }
    }

    private class ShareUnreachable(cause: Throwable) : java.io.IOException(cause.message, cause)

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val OFFLINE_RETRY_MS = 60_000L

        private fun encode(path: String): String =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        /** Parses "bytes=a-b", "bytes=a-" and "bytes=-n". Returns null if unsatisfiable. */
        fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            if (size <= 0) return if (header == null) 0L to -1L else null
            if (header == null || !header.startsWith("bytes=")) return 0L to size - 1
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val startText = spec.substringBefore('-').trim()
            val endText = spec.substringAfter('-', "").trim()
            val (start, end) = if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                maxOf(0, size - suffix) to size - 1
            } else {
                val s = startText.toLongOrNull() ?: return null
                val e = endText.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                s to e
            }
            return if (start > end || start >= size) null else start to end
        }

        private fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv", "mk3d" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi", "divx", "xvid" -> "video/x-msvideo"
            "mov", "qt" -> "video/quicktime"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mpg", "mpeg" -> "video/mpeg"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            else -> "application/octet-stream"
        }
    }
}
