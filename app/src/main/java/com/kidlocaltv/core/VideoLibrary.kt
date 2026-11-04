package com.kidlocaltv.core

import java.io.File

/**
 * Immutable snapshot of all videos, grouped by folder and sorted in natural (episode) order.
 */
class VideoLibrary(videos: List<Video>) {

    val videos: List<Video> = videos.sortedWith(
        compareBy<Video, String>(NaturalOrder) { it.folder }.thenBy(NaturalOrder) { it.name }
    )

    private val byFolder: Map<String, List<Video>> = this.videos.groupBy { it.folder }
    private val byId: Map<String, Video> = this.videos.associateBy { it.id }

    val folders: List<String> get() = byFolder.keys.toList()

    val isEmpty: Boolean get() = videos.isEmpty()
    val size: Int get() = videos.size

    fun find(id: String): Video? = byId[id]

    fun episodesOf(folder: String): List<Video> = byFolder[folder].orEmpty()

    companion object {
        /** Containers LibVLC can open. Codecs inside are decoded in hardware when possible, software otherwise. */
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "mk3d", "webm", "mov", "qt",
            "avi", "divx", "xvid", "wmv", "asf", "flv", "f4v",
            "ts", "m2ts", "mts", "m2t", "tp", "trp", "vob", "evo",
            "mpg", "mpeg", "mpe", "m1v", "m2v", "mp2v", "mpv",
            "3gp", "3g2", "ogv", "ogm", "ogx", "rm", "rmvb",
            "dv", "mxf", "nut", "nsv", "amv", "y4m", "wtv", "dvr-ms", "ivf",
        )

        fun isVideoFile(name: String): Boolean =
            !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

        /** Recursively scans [root]; every directory containing videos becomes a folder/series. */
        fun scan(root: File): VideoLibrary {
            val result = mutableListOf<Video>()
            if (root.isDirectory) {
                root.walkTopDown()
                    .onEnter { !it.name.startsWith(".") || it == root }
                    .filter { it.isFile && isVideoFile(it.name) }
                    .forEach { file ->
                        val rel = file.relativeTo(root).invariantSeparatorsPath
                        val folder = rel.substringBeforeLast('/', "")
                        result += Video(id = rel, folder = folder, name = file.name, location = VideoLocation.LocalFile(file))
                    }
            }
            return VideoLibrary(result)
        }
    }
}
