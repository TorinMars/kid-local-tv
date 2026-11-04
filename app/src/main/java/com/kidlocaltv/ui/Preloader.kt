package com.kidlocaltv.ui

import android.os.Handler
import android.util.Log
import com.kidlocaltv.core.Video
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Keeps the next few videos opened, buffered and paused in hidden players, so Up/Down can switch
 * to one at once.
 *
 * Starting a video from an SMB share took 2-4 s on a slow TV: the SMB login, probing the format,
 * LibVLC's meta reader opening the file a second time for tags and cover art, then buffering. A
 * preloaded player has done all of that already; switching to it only attaches the picture.
 *
 * Preloaded players are muted, have no picture attached and stop paused at their start position.
 * Only one loads at a time, so the video on screen keeps most of the CPU and network.
 */
class Preloader(
    private val handler: Handler,
    /** A new, unattached player, or null when the TV is too short of memory for another one. */
    private val newPlayer: () -> MediaPlayer?,
    /** The media for a video, built exactly as for normal playback, or null if it can't be opened. */
    private val buildMedia: (Video) -> Prepared?,
) {
    /** A media ready to hand to a player, and the share id when it opens through native SMB. */
    class Prepared(val media: Media, val directSource: String?)

    class Warm internal constructor(val video: Video, val player: MediaPlayer, val directSource: String?) {
        internal var ready = false
    }

    private val warm = LinkedHashMap<String, Warm>()
    private var wanted: List<Video> = emptyList()
    /** Videos that failed to preload for the current wanted list; not retried until it changes. */
    private val failed = HashSet<String>()
    private var loading: Warm? = null
    private val timeout = Runnable { loading?.let { drop(it, "timed out") } }

    /** True when [video] is preloaded and ready to show at once. */
    fun isReady(video: Video): Boolean = warm[video.id]?.ready == true

    /** Sets the videos to keep ready, nearest first. Others are released; missing ones load one by one. */
    fun want(videos: List<Video>) {
        if (videos.map { it.id } != wanted.map { it.id }) failed.clear()
        wanted = videos
        val ids = videos.mapTo(HashSet()) { it.id }
        warm.values.filter { it.video.id !in ids }.forEach { drop(it, null) }
        loadNext()
    }

    /** Hands over the ready player for [video], or null. The caller attaches views, unmutes and plays. */
    fun take(video: Video): Warm? {
        val w = warm[video.id]
        if (w == null || !w.ready) return null
        warm.remove(video.id)
        w.player.setEventListener(null)
        return w
    }

    /** Releases every preloaded player (the activity is stopping). */
    fun clear() {
        wanted = emptyList()
        failed.clear()
        warm.values.toList().forEach { drop(it, null) }
    }

    private fun loadNext() {
        if (loading != null) return
        val next = wanted.firstOrNull { it.id !in warm && it.id !in failed } ?: return
        val mp = newPlayer() ?: return
        val prepared = buildMedia(next)
        if (prepared == null) {
            VlcEngine.releaseInBackground(mp)
            failed += next.id
            handler.post { loadNext() }
            return
        }
        val w = Warm(next, mp, prepared.directSource)
        warm[next.id] = w
        loading = w
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                if (warm[next.id] !== w) return
                when (event.type) {
                    MediaPlayer.Event.Paused -> markReady(w)
                    // In case ":start-paused" is not honoured: stop at once, it then reports Paused.
                    MediaPlayer.Event.Playing -> mp.pause()
                    MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached ->
                        handler.post { if (warm[next.id] === w) drop(w, "could not open") }
                }
            }
        })
        mp.volume = 0
        prepared.media.addOption(":start-paused")
        // No video decoder while hidden: without a surface LibVLC would open a software one
        // (avcodec) in every preloaded player. The track is selected when the player is shown.
        prepared.media.addOption(":no-video")
        mp.media = prepared.media
        prepared.media.release()
        mp.play()
        handler.postDelayed(timeout, LOAD_TIMEOUT_MS)
    }

    private fun markReady(w: Warm) {
        if (w.ready) return
        w.ready = true
        // In case ":no-video" was not honoured: drop any video decoder until the player is shown.
        w.player.setVideoTrack(-1)
        if (loading === w) {
            loading = null
            handler.removeCallbacks(timeout)
        }
        loadNext()
    }

    private fun drop(w: Warm, reason: String?) {
        if (warm[w.video.id] === w) warm.remove(w.video.id)
        if (reason != null) {
            Log.w(TAG, "Preload of ${w.video.title} $reason")
            failed += w.video.id
        }
        w.player.setEventListener(null)
        VlcEngine.releaseInBackground(w.player)
        if (loading === w) {
            loading = null
            handler.removeCallbacks(timeout)
            handler.post { loadNext() }
        }
    }

    private companion object {
        const val TAG = "Preloader"
        const val LOAD_TIMEOUT_MS = 15_000L
    }
}
