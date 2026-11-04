package com.kidlocaltv.core

import java.io.File

/**
 * A single video in the library.
 *
 * @property id stable identifier: the path relative to the library root, using '/' separators.
 * @property folder the folder (series) the video belongs to, relative to the library root.
 *   Videos placed directly in the root have an empty folder.
 */
data class Video(
    val id: String,
    val folder: String,
    val name: String,
    val file: File,
) {
    /** Name without extension, for display. */
    val title: String get() = name.substringBeforeLast('.', name)

    val folderLabel: String get() = folder.ifEmpty { "Unsorted" }
}
