package com.kidlocaltv.ui

import android.content.Context
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.Executors

/**
 * One LibVLC instance for the whole process.
 *
 * Creating LibVLC loads its native libraries and plugins. On a slow 32-bit TV that takes seconds
 * (about 2.8 s on a Skyworth MT9255 set). Doing it on the main thread, again on every start of the
 * player, kept the player window from appearing for over 5 s, and Android then reported the app as
 * not responding (ANR). So the instance is built once, off the main thread, and kept for the life of
 * the process.
 */
object VlcEngine {
    @Volatile private var instance: LibVLC? = null

    /**
     * Returns the shared instance, creating it on the calling thread the first time.
     * The first call is slow, so never make it on the main thread.
     */
    fun get(context: Context, options: List<String>): LibVLC =
        instance ?: synchronized(this) {
            instance ?: LibVLC(context.applicationContext, ArrayList(options)).also { instance = it }
        }

    private val releaser = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vlc-release").apply { isDaemon = true }
    }

    /**
     * Stops and frees a player in the background. stop() waits for VLC's input thread to end, and
     * when that thread is waiting on a network read (an SMB stream) it can take seconds. On the main
     * thread that froze the app into an ANR. The caller must detach the player's views first.
     */
    fun releaseInBackground(player: MediaPlayer) {
        releaser.execute {
            player.stop()
            player.release()
        }
    }
}
