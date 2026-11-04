package com.kidlocaltv.data

import android.content.Context
import com.kidlocaltv.core.PositionStore
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

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"

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
