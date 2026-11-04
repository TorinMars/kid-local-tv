package com.kidlocaltv.server

import com.kidlocaltv.core.Video
import com.kidlocaltv.core.VideoLocation
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SMB shares the library streams from. Nothing is copied to the TV: for each share we only keep
 * its link (with login) and a small cached index of the videos found there, so the app can start
 * instantly, even before the share has been re-scanned.
 *
 * Files in [dir]:
 *  - `source-<id>.properties`  link, login and series-name settings
 *  - `index-<id>.tsv`          cached list of videos (folder, name, size, path on share)
 *
 * Pure JVM code; thread-safe.
 */
class NetworkSources(private val dir: File) {

    data class Source(
        val id: String,
        val link: SmbLink,
        /** Folder/series name prefix for this share's videos ("" = use the share's own folders). */
        val baseFolder: String,
    )

    data class Entry(val folder: String, val name: String, val size: Long, val path: String)

    /** Scan state shown in the web page. */
    data class Status(val state: String, val scannedAt: Long)

    private val scanning = ConcurrentHashMap<String, String>()
    private val lastError = ConcurrentHashMap<String, String>()

    init {
        dir.mkdirs()
    }

    // ---- sources ----

    @Synchronized
    fun sources(): List<Source> =
        dir.listFiles { f -> f.name.startsWith("source-") && f.name.endsWith(".properties") }
            .orEmpty()
            .mapNotNull { load(it) }
            .sortedBy { it.link.toString().lowercase() }

    fun find(id: String): Source? = sources().firstOrNull { it.id == id }

    /**
     * Adds a share, or updates the login / series name of one that's already added
     * (same server, share and folder).
     */
    @Synchronized
    fun add(link: SmbLink, folderOverride: String): Source {
        val base = folderOverride.ifEmpty {
            // A linked folder becomes the series; a whole share's top-level folders become series.
            if (link.path.isNotEmpty()) ImportServer.sanitizeFolder(link.leafName) else ""
        }
        val existing = findSameLocation(link)
        val source = Source(existing?.id ?: newId(), link, base)
        save(source)
        return source
    }

    /** The already-added share at the same server / share / folder as [link], if any. */
    fun findSameLocation(link: SmbLink): Source? = sources().firstOrNull { sameLocation(it.link, link) }

    /** Puts back a source's previous settings, e.g. when a new login turned out to be wrong. */
    @Synchronized
    fun restore(previous: Source, note: String) {
        save(previous)
        lastError[previous.id]?.let { lastError[previous.id] = "$it ($note)" }
    }

    @Synchronized
    fun remove(id: String) {
        sourceFile(id).delete()
        indexFile(id).delete()
        lastError.remove(id)
    }

    // ---- index ----

    fun entries(id: String): List<Entry> {
        val file = indexFile(id)
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) null else Entry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0, parts[3])
        }
    }

    /** All network videos from the cached indexes. */
    fun videos(): List<Video> = sources().flatMap { source ->
        entries(source.id).map { e ->
            Video(
                id = "smb:${source.id}:${e.path}",
                folder = e.folder,
                name = e.name,
                location = VideoLocation.Network(source.id, e.path, e.size),
            )
        }
    }

    fun status(id: String): Status {
        val file = indexFile(id)
        val scannedAt = if (file.isFile) file.lastModified() else 0L
        val state = scanning[id] ?: lastError[id] ?: if (file.isFile) "ok" else "not scanned yet"
        return Status(state, scannedAt)
    }

    /**
     * Connects to the share and refreshes its index. On failure the previous index is kept
     * (so a NAS that's switched off doesn't empty the library). Returns true on success.
     */
    fun scan(id: String): Boolean {
        val source = find(id) ?: return false
        if (scanning.putIfAbsent(id, "scanning…") != null) return false
        try {
            SmbSource(source.link).use { smb ->
                smb.connect()
                val entries = smb.listVideos().map { v ->
                    val folder = listOf(source.baseFolder, v.relativeFolder).filter { it.isNotEmpty() }.joinToString("/")
                    Entry(ImportServer.sanitizeFolder(folder), clean(v.name), v.size, v.smbPath)
                }
                writeIndex(id, entries)
            }
            lastError.remove(id)
            return true
        } catch (e: Exception) {
            lastError[id] = "failed: ${readableError(e)}"
            return false
        } finally {
            scanning.remove(id)
        }
    }

    /** Re-scans every share whose index is older than [maxAgeMs]. Returns true if anything changed. */
    fun scanStale(maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        for (source in sources()) {
            val age = now - status(source.id).scannedAt
            if (age >= maxAgeMs && scan(source.id)) changed = true
        }
        return changed
    }

    // ---- storage ----

    private fun sourceFile(id: String) = File(dir, "source-$id.properties")
    private fun indexFile(id: String) = File(dir, "index-$id.tsv")

    private fun save(source: Source) {
        val p = Properties()
        with(source.link) {
            p["host"] = host
            p["port"] = port.toString()
            p["share"] = share
            p["path"] = path
            p["username"] = username
            p["password"] = password
            p["domain"] = domain
        }
        p["baseFolder"] = source.baseFolder
        atomicWrite(sourceFile(source.id)) { out -> p.store(out, "Kid Local TV network folder") }
    }

    private fun load(file: File): Source? = try {
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val id = file.name.removePrefix("source-").removeSuffix(".properties")
        Source(
            id = id,
            link = SmbLink(
                host = p.getProperty("host"),
                port = p.getProperty("port")?.toIntOrNull() ?: SmbLink.DEFAULT_PORT,
                share = p.getProperty("share"),
                path = p.getProperty("path", ""),
                username = p.getProperty("username", ""),
                password = p.getProperty("password", ""),
                domain = p.getProperty("domain", ""),
            ),
            baseFolder = p.getProperty("baseFolder", ""),
        )
    } catch (e: Exception) {
        null
    }

    private fun writeIndex(id: String, entries: List<Entry>) {
        atomicWrite(indexFile(id)) { out ->
            out.bufferedWriter().apply {
                for (e in entries) write("${clean(e.folder)}\t${clean(e.name)}\t${e.size}\t${clean(e.path)}\n")
                flush()
            }
        }
    }

    private fun atomicWrite(target: File, block: (java.io.OutputStream) -> Unit) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use(block)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun sameLocation(a: SmbLink, b: SmbLink) =
        a.host.equals(b.host, ignoreCase = true) && a.port == b.port &&
            a.share.equals(b.share, ignoreCase = true) && a.path.equals(b.path, ignoreCase = true)

    companion object {
        /** smbj wraps errors several layers deep; show our own message or the innermost cause. */
        fun readableError(e: Throwable): String {
            var t: Throwable = e
            if (t.javaClass != java.io.IOException::class.java) {
                while (t.cause != null && t.cause !== t) t = t.cause!!
            }
            return t.message ?: t.javaClass.simpleName
        }
    }
}
