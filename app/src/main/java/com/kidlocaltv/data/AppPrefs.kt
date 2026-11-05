package com.kidlocaltv.data

import android.content.Context
import com.kidlocaltv.core.PositionStore
import com.kidlocaltv.core.Video
import com.kidlocaltv.core.VideoLocation
import com.kidlocaltv.core.VideoLibrary
import com.kidlocaltv.server.NetworkSources
import java.io.File

/** App-wide settings: whether first-run setup is done, and where the library lives. */
class AppPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    /** Videos are stored in app-specific storage, so no storage permission is needed. */
    val libraryRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "library").apply { mkdirs() }

    /** SMB shares that are streamed (never copied); stored in app-private storage. */
    val networkSources: NetworkSources get() = sharedSources(appContext)

    /** Videos stored on the TV plus videos on network shares (from their cached indexes). */
    fun scanLibrary(): VideoLibrary =
        VideoLibrary(VideoLibrary.scan(libraryRoot).videos + networkSources.videos())

    /**
     * The video to play the moment the app opens next time, picked while the library is loaded.
     * Playback then starts without waiting for the library, which takes a while for a big share.
     * Null when none is saved; a video that is gone by then just fails and is skipped.
     */
    var startVideo: Video?
        get() {
            val id = prefs.getString(KEY_START_ID, null) ?: return null
            val folder = prefs.getString(KEY_START_FOLDER, null) ?: return null
            val name = prefs.getString(KEY_START_NAME, null) ?: return null
            val location = when (prefs.getString(KEY_START_KIND, null)) {
                "local" -> VideoLocation.LocalFile(File(prefs.getString(KEY_START_FILE, null) ?: return null))
                "smb" -> VideoLocation.Network(
                    sourceId = prefs.getString(KEY_START_SOURCE, null) ?: return null,
                    path = prefs.getString(KEY_START_FILE, null) ?: return null,
                    size = prefs.getLong(KEY_START_SIZE, 0L),
                )
                else -> return null
            }
            return Video(id, folder, name, location)
        }
        set(video) {
            val edit = prefs.edit()
            if (video == null) {
                listOf(KEY_START_ID, KEY_START_FOLDER, KEY_START_NAME, KEY_START_KIND, KEY_START_FILE,
                    KEY_START_SOURCE, KEY_START_SIZE).forEach { edit.remove(it) }
            } else {
                edit.putString(KEY_START_ID, video.id)
                    .putString(KEY_START_FOLDER, video.folder)
                    .putString(KEY_START_NAME, video.name)
                when (val location = video.location) {
                    is VideoLocation.LocalFile -> edit.putString(KEY_START_KIND, "local")
                        .putString(KEY_START_FILE, location.file.path)
                    is VideoLocation.Network -> edit.putString(KEY_START_KIND, "smb")
                        .putString(KEY_START_SOURCE, location.sourceId)
                        .putString(KEY_START_FILE, location.path)
                        .putLong(KEY_START_SIZE, location.size)
                }
            }
            edit.apply()
        }

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_START_ID = "start_id"
        private const val KEY_START_FOLDER = "start_folder"
        private const val KEY_START_NAME = "start_name"
        private const val KEY_START_KIND = "start_kind"
        private const val KEY_START_FILE = "start_file"
        private const val KEY_START_SOURCE = "start_source"
        private const val KEY_START_SIZE = "start_size"

        @Volatile private var sources: NetworkSources? = null

        /** One instance per process, so scan status is shared between screens. */
        private fun sharedSources(context: Context): NetworkSources =
            sources ?: synchronized(this) {
                sources ?: NetworkSources(File(context.filesDir, "network")).also { sources = it }
            }
    }
}

/** Resume positions, keyed by the video's path inside the library. */
class PrefsPositionStore(context: Context) : PositionStore {
    private val prefs = context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    override fun get(videoId: String): Long = prefs.getLong(videoId, 0L)

    override fun put(videoId: String, positionMs: Long) {
        prefs.edit().putLong(videoId, positionMs).apply()
    }

    override fun remove(videoId: String) {
        prefs.edit().remove(videoId).apply()
    }
}
