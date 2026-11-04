package com.kidlocaltv.server

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.kidlocaltv.core.VideoLibrary
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A video found behind an SMB link (the share's path in [smbPath]). */
data class SmbVideo(
    /** Sub-folder relative to the linked folder ("" for files directly inside it, or for a file link). */
    val relativeFolder: String,
    val name: String,
    val size: Long,
    val smbPath: String,
)

/**
 * Reads videos from a Windows / Samba / NAS share (SMB 2 and 3, via the smbj library).
 * Pure JVM code: no Android dependencies.
 */
class SmbSource(private val link: SmbLink) : AutoCloseable {

    private val client = SMBClient(
        SmbConfig.builder()
            // Bundled Bouncy Castle provides MD4/NTLM support that Android's built-in crypto lacks.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )
    private var session: Session? = null
    private lateinit var share: DiskShare

    /** False once the connection hit an error; callers should reconnect with a new [SmbSource]. */
    @Volatile
    var healthy: Boolean = true
        private set

    /** True when the link points at a folder (or a share root) rather than a single file. */
    var isFolder: Boolean = false
        private set

    /** Connects, logs in and opens the share. Throws [IOException] with a readable message on failure. */
    fun connect() {
        val connection = try {
            client.connect(link.host, link.port)
        } catch (e: Exception) {
            throw IOException("Couldn't reach ${link.host} (${e.message ?: e.javaClass.simpleName})", e)
        }
        session = authenticate(connection)
        share = try {
            session!!.connectShare(link.share) as? DiskShare
                ?: throw IOException("\"${link.share}\" is not a file share")
        } catch (e: SMBApiException) {
            throw IOException("Couldn't open share \"${link.share}\": ${statusText(e)}", e)
        }
        isFolder = link.path.isEmpty() || try {
            share.getFileInformation(link.smbPath).standardInformation.isDirectory
        } catch (e: SMBApiException) {
            throw IOException("Nothing found at $link (${statusText(e)})", e)
        }
    }

    private fun authenticate(connection: com.hierynomus.smbj.connection.Connection): Session {
        val attempts = if (link.username.isNotEmpty()) {
            listOf(AuthenticationContext(link.username, link.password.toCharArray(), link.domain.ifEmpty { null }))
        } else {
            // No user given: try anonymous first, then the guest account.
            listOf(AuthenticationContext.anonymous(), AuthenticationContext.guest())
        }
        var last: Exception? = null
        for (auth in attempts) {
            try {
                return connection.authenticate(auth)
            } catch (e: Exception) {
                last = e
            }
        }
        val hint = if (link.username.isEmpty()) {
            " — this share probably needs a username and password"
        } else {
            " — check the username and password"
        }
        throw IOException("Login to ${link.host} failed$hint", last)
    }

    /** Lists every video behind the link; folders are searched recursively (up to [MAX_DEPTH] levels). */
    fun listVideos(): List<SmbVideo> {
        if (!isFolder) {
            val info = share.getFileInformation(link.smbPath)
            return listOf(SmbVideo("", link.leafName, info.standardInformation.endOfFile, link.smbPath))
        }
        val out = mutableListOf<SmbVideo>()
        walk(link.smbPath, "", 0, out)
        return out
    }

    private fun walk(dir: String, relative: String, depth: Int, out: MutableList<SmbVideo>) {
        for (entry in share.list(dir)) {
            val name = entry.fileName
            if (name == "." || name == ".." || isIgnored(name)) continue
            val childPath = if (dir.isEmpty()) name else "$dir\\$name"
            val isDir = EnumWithValue.EnumUtils.isSet(entry.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            if (isDir) {
                if (depth < MAX_DEPTH) {
                    walk(childPath, if (relative.isEmpty()) name else "$relative/$name", depth + 1, out)
                }
            } else if (VideoLibrary.isVideoFile(name)) {
                out += SmbVideo(relative, name, entry.endOfFile, childPath)
            }
        }
    }

    /** Opens a file on the share for random-access reads (used for streaming with seeking). */
    fun openRemote(smbPath: String): RemoteFile {
        val file = share.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val size = try {
            file.fileInformation.standardInformation.endOfFile
        } catch (e: Exception) {
            closeQuietly(file)
            throw e
        }
        return RemoteFile(file, size)
    }

    /** An open file on the share. [stream] reads a byte range without loading the whole file. */
    inner class RemoteFile internal constructor(
        private val file: com.hierynomus.smbj.share.File,
        val size: Long,
    ) : java.io.Closeable {

        /** Bytes [start, endInclusive]; the file is closed when the stream is closed. */
        fun stream(start: Long, endInclusive: Long): InputStream = object : InputStream() {
            private var position = start
            private var closed = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position > endInclusive) return -1
                val want = minOf(len.toLong(), endInclusive - position + 1, MAX_READ.toLong()).toInt()
                val read = try {
                    file.read(b, position, off, want)
                } catch (e: Exception) {
                    healthy = false
                    throw IOException("Lost connection to ${link.host}", e)
                }
                if (read <= 0) return -1
                position += read
                return read
            }

            override fun close() {
                if (closed) return
                closed = true
                this@RemoteFile.close()
            }
        }

        override fun close() = closeQuietly(file)
    }

    private fun closeQuietly(file: com.hierynomus.smbj.share.File) {
        try {
            // Don't wait for the server's reply: some servers answer slowly or malformed.
            file.closeNoWait()
        } catch (e: Exception) {
            healthy = false
        }
    }

    override fun close() {
        try {
            if (::share.isInitialized) share.close()
            session?.close()
        } catch (_: Exception) {
        }
        client.close()
    }

    companion object {
        const val MAX_DEPTH = 6
        private const val MAX_READ = 1 shl 20

        /** NAS housekeeping folders and hidden files that never contain real videos. */
        fun isIgnored(name: String): Boolean =
            name.startsWith(".") || name.startsWith("@") || name.startsWith("#") || name.startsWith("$") ||
                name.equals("System Volume Information", ignoreCase = true) ||
                name.startsWith("._")

        private fun statusText(e: SMBApiException): String = when (e.status?.name) {
            "STATUS_BAD_NETWORK_NAME", "STATUS_OBJECT_PATH_NOT_FOUND" -> "not found"
            "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_NO_SUCH_FILE" -> "not found"
            "STATUS_ACCESS_DENIED" -> "access denied"
            "STATUS_LOGON_FAILURE" -> "wrong username or password"
            else -> e.status?.name ?: (e.message ?: "error")
        }
    }
}
