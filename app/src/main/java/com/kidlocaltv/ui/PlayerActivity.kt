package com.kidlocaltv.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.kidlocaltv.R
import com.kidlocaltv.core.PlaybackMode
import com.kidlocaltv.core.PlaybackNavigator
import com.kidlocaltv.core.PositionStore
import com.kidlocaltv.core.ResumePolicy
import com.kidlocaltv.core.Video
import com.kidlocaltv.core.VideoLocation
import com.kidlocaltv.data.AppPrefs
import com.kidlocaltv.data.PrefsPositionStore
import com.kidlocaltv.server.SmbStreamServer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Launcher activity. Opens straight into full-screen playback of a random video.
 *
 * Remote controls:
 *  - Up / Down          shuffle mode: another random video; series mode: previous / next in folder
 *  - Right              switch to series mode for the current video's folder
 *  - Left               switch back to shuffle mode
 *  - OK / Play-Pause    pause or resume
 *  - Hold OK, or Menu   manage videos (import screen)
 *
 * The position of the video being left is saved on every Up/Down switch (and when the app stops),
 * and that video resumes from there the next time it comes up.
 *
 * Playback uses LibVLC, which decodes almost any format: hardware decoding via MediaCodec when the
 * TV supports the codec, and software decoding (libavcodec) otherwise.
 *
 * Videos on SMB shares are streamed through [SmbStreamServer] (loopback only) — never copied to the TV.
 */
class PlayerActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var positions: PositionStore
    private lateinit var navigator: PlaybackNavigator

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var bufferingView: View
    private lateinit var overlay: View
    private lateinit var modeText: TextView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var hintText: TextView
    private lateinit var pausedIcon: View
    private lateinit var toastText: TextView

    private var libVlc: LibVLC? = null
    private var streamServer: SmbStreamServer? = null
    private var player: MediaPlayer? = null
    private var paused = false
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }
    private val hideToast = Runnable { toastText.animate().alpha(0f).setDuration(300).start() }

    /** True when we opened the manage screen ourselves and should continue the same video after. */
    private var continueCurrentOnStart = false
    private var consecutiveErrors = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        positions = PrefsPositionStore(this)

        val library = prefs.scanLibrary()
        if (!prefs.setupComplete || library.isEmpty) {
            startActivity(SetupActivity.intent(this, manage = false))
            finish()
            return
        }

        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoLayout = findViewById(R.id.video_layout)
        bufferingView = findViewById(R.id.buffering)
        overlay = findViewById(R.id.overlay)
        modeText = findViewById(R.id.overlay_mode)
        titleText = findViewById(R.id.overlay_title)
        detailText = findViewById(R.id.overlay_detail)
        hintText = findViewById(R.id.overlay_hint)
        pausedIcon = findViewById(R.id.paused_icon)
        toastText = findViewById(R.id.toast)

        navigator = PlaybackNavigator(library)
        libVlc = LibVLC(applicationContext, VLC_OPTIONS)
    }

    override fun onDestroy() {
        libVlc?.release()
        libVlc = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        val library = prefs.scanLibrary()
        if (library.isEmpty) {
            startActivity(SetupActivity.intent(this, manage = false))
            finish()
            return
        }
        navigator.updateLibrary(library)

        startStreamServer()
        refreshNetworkSourcesInBackground()
        createPlayer()
        val video = if (continueCurrentOnStart && navigator.current != null) navigator.current else navigator.start()
        continueCurrentOnStart = false
        video?.let(::play)
    }

    override fun onStop() {
        if (::navigator.isInitialized) saveCurrentPosition()
        releasePlayer()
        streamServer?.stopServer()
        streamServer = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun startStreamServer() {
        if (streamServer != null) return
        streamServer = SmbStreamServer(prefs.networkSources).also {
            try {
                it.startServer()
            } catch (e: java.io.IOException) {
                showToast(getString(R.string.stream_server_failed))
            }
        }
    }

    /**
     * The library starts instantly from each share's cached file list; refresh those lists quietly so
     * new episodes show up. A share that's offline keeps its old list.
     */
    private fun refreshNetworkSourcesInBackground() {
        val sources = prefs.networkSources
        if (sources.sources().isEmpty()) return
        Thread {
            if (sources.scanStale(RESCAN_AFTER_MS)) {
                val fresh = prefs.scanLibrary()
                handler.post { if (player != null && !fresh.isEmpty) navigator.updateLibrary(fresh) }
            }
        }.apply { isDaemon = true; name = "network-rescan" }.start()
    }

    private fun createPlayer() {
        val vlc = libVlc ?: return
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                // Only react to events from the player that's currently attached.
                if (player !== mp) return
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        consecutiveErrors = 0
                        bufferingView.visibility = View.GONE
                    }
                    MediaPlayer.Event.Buffering ->
                        bufferingView.visibility = if (event.buffering < 100f) View.VISIBLE else View.GONE
                    // Leave LibVLC's callback before starting the next video.
                    MediaPlayer.Event.EndReached -> handler.post {
                        if (player !== mp) return@post
                        // A file VLC can't decode sometimes "ends" instantly instead of erroring.
                        val instant = SystemClock.elapsedRealtime() - startedAt < 1_500 && mp.length <= 0
                        if (instant) onVideoFailed() else onVideoEnded()
                    }
                    MediaPlayer.Event.EncounteredError -> handler.post { if (player === mp) onVideoFailed() }
                }
            }
        })
        player = mp
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        mp.setEventListener(null)
        mp.stop()
        mp.detachViews()
        mp.release()
    }

    // ---- playback ----

    private fun play(video: Video) {
        val mp = player ?: return
        val vlc = libVlc ?: return
        val start = ResumePolicy.startPosition(positions, video.id)
        val media = when (val location = video.location) {
            is VideoLocation.LocalFile -> Media(vlc, Uri.fromFile(location.file))
            is VideoLocation.Network -> {
                val server = streamServer
                if (server == null) {
                    onVideoFailed()
                    return
                }
                Media(vlc, Uri.parse(server.urlFor(location))).apply {
                    // A little more buffer for Wi-Fi hiccups when streaming from a share.
                    addOption(":network-caching=$NETWORK_CACHING_MS")
                }
            }
        }
        // Hardware decoding when the TV supports the codec; LibVLC falls back to software otherwise.
        media.setHWDecoderEnabled(true, false)
        if (start > 0) media.addOption(":start-time=${start / 1000.0}")
        mp.setMedia(media)
        media.release()
        startedAt = SystemClock.elapsedRealtime()
        mp.play()
        paused = false
        pausedIcon.visibility = View.GONE
        showOverlay()
    }

    private fun saveCurrentPosition() {
        val video = navigator.current ?: return
        val mp = player ?: return
        val position = mp.time
        if (position < 0) return // nothing loaded yet
        ResumePolicy.save(positions, video.id, position, mp.length)
    }

    private fun switchVideo(pick: () -> Video?) {
        saveCurrentPosition()
        val next = pick()
        if (next == null) {
            showToast(getString(R.string.only_episode))
            return
        }
        play(next)
    }

    private fun onVideoEnded() {
        navigator.current?.let { positions.remove(it.id) }
        navigator.onVideoEnded()?.let(::play)
    }

    private fun onVideoFailed() {
        val failed = navigator.current
        failed?.let { positions.remove(it.id) }
        consecutiveErrors++
        if (consecutiveErrors >= navigator.library.size.coerceAtLeast(1)) {
            showToast(getString(R.string.nothing_playable))
            return
        }
        showToast(getString(R.string.cannot_play, failed?.title ?: ""))
        // A whole series failing (e.g. its network share is off): go back to shuffling everything.
        val episodes = navigator.episodeInfo()?.second ?: 0
        if (navigator.mode == PlaybackMode.SERIES && consecutiveErrors >= episodes) {
            navigator.enterShuffleMode()
        }
        navigator.skipBroken()?.let(::play)
    }

    private fun togglePause() {
        val mp = player ?: return
        if (paused) mp.play() else mp.pause()
        paused = !paused
        pausedIcon.visibility = if (paused) View.VISIBLE else View.GONE
        showOverlay()
    }

    private fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        val length = mp.length
        var target = (mp.time + deltaMs).coerceAtLeast(0)
        if (length > 0) target = target.coerceAtMost(length - 1_000)
        mp.setTime(target)
    }

    private fun openManageScreen() {
        saveCurrentPosition()
        continueCurrentOnStart = true
        startActivity(SetupActivity.intent(this, manage = true))
    }

    // ---- remote control ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val firstPress = event.repeatCount == 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (firstPress) switchVideo { navigator.up() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (firstPress) switchVideo { navigator.down() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (firstPress) {
                    if (navigator.enterSeriesMode()) {
                        showToast(getString(R.string.series_mode_on, navigator.current?.folderLabel ?: ""))
                    }
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (firstPress) {
                    if (navigator.enterShuffleMode()) showToast(getString(R.string.shuffle_mode_on))
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Decide on key-up so a long press can open the manage screen instead.
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (firstPress) togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (paused) togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (!paused) togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_MS); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_MS); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (firstPress) openManageScreen()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenterKey(keyCode)) {
            openManageScreen()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isCenterKey(keyCode)) {
            val wasLongPress = (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0
            if (event.isTracking && !event.isCanceled && !wasLongPress) togglePause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isCenterKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---- on-screen info ----

    private fun showOverlay() {
        val video = navigator.current ?: return
        val series = navigator.mode == PlaybackMode.SERIES
        modeText.setText(if (series) R.string.mode_series else R.string.mode_shuffle)
        modeText.setBackgroundResource(if (series) R.drawable.chip_series else R.drawable.chip_shuffle)
        titleText.text = video.title
        val episode = navigator.episodeInfo()
        detailText.text = if (episode != null) {
            getString(R.string.episode_detail, video.folderLabel, episode.first, episode.second)
        } else {
            video.folderLabel
        }
        hintText.setText(if (series) R.string.hint_series else R.string.hint_shuffle)

        overlay.animate().cancel()
        overlay.alpha = 1f
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun showToast(message: String) {
        toastText.text = message
        toastText.animate().cancel()
        toastText.alpha = 1f
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, TOAST_MS)
    }

    private companion object {
        /** Options tuned for modest TV hardware: keep audio smooth, let software decoding skip work. */
        val VLC_OPTIONS = arrayListOf(
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1", // cheaper software H.264/HEVC decoding on slow boxes
            "--no-sub-autodetect-file",
            "--no-osd",
        )
        const val NETWORK_CACHING_MS = 3_000
        const val RESCAN_AFTER_MS = 10 * 60_000L
        const val OVERLAY_MS = 4_000L
        const val TOAST_MS = 2_000L
        const val SEEK_MS = 10_000L
    }
}
