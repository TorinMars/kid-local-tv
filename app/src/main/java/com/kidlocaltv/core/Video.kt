package com.kidlocaltv.core

import java.io.File

/** Where a video's bytes live. */
sealed interface VideoLocation {
    /** Stored on the TV (uploaded or downloaded). */
    data class LocalFile(val file: File) : VideoLocation

    /** Streamed from a network (SMB) share; nothing is copied to the TV. */
    data class Network(val sourceId: String, val path: String, val size: Long) : VideoLocation
}

/**
 * A single video in the library.
 *
 * @property id stable identifier, used e.g. to remember resume positions. Local videos use their path
 *   relative to the library root; network videos use "smb:<sourceId>:<path on share>".
 * @property folder the folder (series) the video belongs to. Videos with no folder have an empty folder.
 */
data class Video(
    val id: String,
    val folder: String,
    val name: String,
    val location: VideoLocation,
) {
    /** Name without extension, for display. */
    val title: String get() = name.substringBeforeLast('.', name)

    val folderLabel: String get() = folder.ifEmpty { "Unsorted" }

    val isNetwork: Boolean get() = location is VideoLocation.Network
}
