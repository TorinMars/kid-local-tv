package com.kidlocaltv.server

import com.kidlocaltv.core.VideoLibrary
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small HTTP server that lets a phone or computer on the same network import videos into the
 * TV's library. Contains no Android code (the page is passed in) so it can be tested on the JVM.
 *
 * Endpoints:
 *  - GET  /                       web page
 *  - GET  /api/library            JSON list of folders and videos
 *  - PUT  /api/upload?folder&name raw file body; streamed straight to disk
 *  - POST /api/fetch?folder&url   TV downloads a video from a URL on the local network
 *  - POST /api/delete?folder&name delete one video
 *  - GET  /api/downloads          progress of URL downloads
 */
class ImportServer(
    port: Int,
    private val root: File,
    private val page: () -> InputStream,
    private val onLibraryChanged: () -> Unit = {},
) : NanoHTTPD(port) {

    private class Download(val folder: String, val name: String) {
        @Volatile var bytes: Long = 0
        @Volatile var total: Long = -1
        @Volatile var state: String = "downloading"
    }

    private val downloads = ConcurrentHashMap<Int, Download>()
    private val downloadIds = AtomicInteger()
    private val downloadExecutor = Executors.newFixedThreadPool(2)

    fun startServer() {
        root.mkdirs()
        // Long socket timeout: uploads of large videos over Wi-Fi can stall briefly.
        start(120_000, false)
    }

    fun stopServer() {
        stop()
        downloadExecutor.shutdownNow()
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: BadRequestException) {
        json(Response.Status.BAD_REQUEST, """{"error":${quote(e.message ?: "bad request")}}""")
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, """{"error":${quote(e.toString())}}""")
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method
        return when (session.uri) {
            "/", "/index.html" -> newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", page())
            "/api/library" -> libraryJson()
            "/api/downloads" -> downloadsJson()
            "/api/upload" -> if (method == Method.PUT || method == Method.POST) upload(session) else notAllowed()
            "/api/fetch" -> if (method == Method.POST) fetch(session) else notAllowed()
            "/api/delete" -> if (method == Method.POST) delete(session) else notAllowed()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun notAllowed() =
        newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")

    private fun param(session: IHTTPSession, key: String): String? {
        // Decode the raw query ourselves so '+' and UTF-8 names survive intact.
        val query = session.queryParameterString ?: return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            if (k == key) {
                val raw = if (idx >= 0) pair.substring(idx + 1) else ""
                return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            }
        }
        return null
    }

    // ---- library ----

    private fun libraryJson(): Response {
        val library = VideoLibrary.scan(root)
        val sb = StringBuilder("""{"total":${library.size},"folders":[""")
        library.folders.forEachIndexed { i, folder ->
            if (i > 0) sb.append(',')
            sb.append("""{"name":${quote(folder)},"videos":[""")
            library.episodesOf(folder).forEachIndexed { j, v ->
                if (j > 0) sb.append(',')
                sb.append("""{"name":${quote(v.name)},"size":${v.file.length()}}""")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return json(Response.Status.OK, sb.toString())
    }

    // ---- uploads ----

    private fun upload(session: IHTTPSession): Response {
        val folder = sanitizeFolder(param(session, "folder"))
        val name = sanitizeName(param(session, "name"))
        if (!VideoLibrary.isVideoFile(name)) throw BadRequestException("Not a supported video file: $name")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw BadRequestException("Missing Content-Length")

        val target = targetFile(folder, name)
        val temp = tempFile()
        try {
            temp.outputStream().buffered(256 * 1024).use { out ->
                copyExactly(session.inputStream, out, length)
            }
            moveInto(temp, target)
        } finally {
            temp.delete()
        }
        onLibraryChanged()
        return json(Response.Status.OK, """{"ok":true,"name":${quote(target.name)}}""")
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, length: Long) {
        val buf = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Upload interrupted")
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    // ---- URL downloads ----

    private fun fetch(session: IHTTPSession): Response {
        val folder = sanitizeFolder(param(session, "folder"))
        val urlString = param(session, "url")?.trim().orEmpty()
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            throw BadRequestException("Invalid URL")
        }
        if (url.protocol != "http" && url.protocol != "https") throw BadRequestException("Only http(s) URLs are supported")
        val rawName = URLDecoder.decode(url.path.substringAfterLast('/'), "UTF-8")
        val name = sanitizeName(rawName.ifBlank { "video.mp4" })
        if (!VideoLibrary.isVideoFile(name)) throw BadRequestException("URL must point to a video file (got \"$name\")")

        val id = downloadIds.incrementAndGet()
        val download = Download(folder, name)
        downloads[id] = download
        downloadExecutor.execute { runDownload(url, download) }
        return json(Response.Status.OK, """{"ok":true,"id":$id}""")
    }

    private fun runDownload(url: URL, download: Download) {
        val temp = tempFile()
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            download.total = conn.contentLengthLong
            conn.inputStream.use { input ->
                temp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        download.bytes += read
                    }
                }
            }
            moveInto(temp, targetFile(download.folder, download.name))
            download.state = "done"
            onLibraryChanged()
        } catch (e: Exception) {
            download.state = "failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            temp.delete()
        }
    }

    private fun downloadsJson(): Response {
        val items = downloads.entries.sortedBy { it.key }.joinToString(",") { (id, d) ->
            """{"id":$id,"folder":${quote(d.folder)},"name":${quote(d.name)},"bytes":${d.bytes},"total":${d.total},"state":${quote(d.state)}}"""
        }
        return json(Response.Status.OK, "[$items]")
    }

    // ---- delete ----

    private fun delete(session: IHTTPSession): Response {
        val folder = sanitizeFolder(param(session, "folder"))
        val name = sanitizeName(param(session, "name"))
        val file = File(folderDir(folder), name)
        if (!file.isFile || !file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw BadRequestException("No such video")
        }
        file.delete()
        // Remove the folder too once it's empty.
        val dir = file.parentFile
        if (dir != null && dir != root && dir.list()?.isEmpty() == true) dir.delete()
        onLibraryChanged()
        return json(Response.Status.OK, """{"ok":true}""")
    }

    // ---- helpers ----

    private fun folderDir(folder: String): File = if (folder.isEmpty()) root else File(root, folder)

    /** Picks a non-clashing destination, e.g. "Episode 1 (2).mp4". */
    private fun targetFile(folder: String, name: String): File {
        val dir = folderDir(folder).apply { mkdirs() }
        var file = File(dir, name)
        var n = 2
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        while (file.exists()) file = File(dir, "$base ($n).$ext").also { n++ }
        return file
    }

    private fun tempFile(): File {
        val dir = File(root, ".incoming").apply { mkdirs() }
        return File.createTempFile("upload", ".part", dir)
    }

    private fun moveInto(temp: File, target: File) {
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = false)
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    companion object {
        const val DEFAULT_PORT = 8080

        private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

        fun sanitizeName(raw: String?): String {
            val cleaned = raw.orEmpty().replace(ILLEGAL, "_").trim().trimStart('.')
            if (cleaned.isEmpty()) throw BadRequestException("Missing file name")
            return cleaned.take(200)
        }

        /** Folder names may be nested ("Cartoons/Season 1"); each segment is cleaned. */
        fun sanitizeFolder(raw: String?): String =
            raw.orEmpty().split('/', '\\')
                .map { it.replace(ILLEGAL, "_").trim().trimStart('.') }
                .filter { it.isNotEmpty() }
                .joinToString("/") { it.take(100) }

        fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}

class BadRequestException(message: String) : IllegalArgumentException(message)
