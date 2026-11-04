package com.kidlocaltv.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kidlocaltv.R
import com.kidlocaltv.core.PlaybackMode
import com.kidlocaltv.core.PlaybackNavigator
import com.kidlocaltv.core.PositionStore
import com.kidlocaltv.core.ResumePolicy
import com.kidlocaltv.core.Video
import com.kidlocaltv.data.AppPrefs
import com.kidlocaltv.data.PrefsPositionStore

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
 */
class PlayerActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var positions: PositionStore
    private lateinit var navigator: PlaybackNavigator

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var modeText: TextView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var hintText: TextView
    private lateinit var pausedIcon: View
    private lateinit var toastText: TextView

    private var player: ExoPlayer? = null
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
        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.overlay)
        modeText = findViewById(R.id.overlay_mode)
        titleText = findViewById(R.id.overlay_title)
        detailText = findViewById(R.id.overlay_detail)
        hintText = findViewById(R.id.overlay_hint)
        pausedIcon = findViewById(R.id.paused_icon)
        toastText = findViewById(R.id.toast)

        // Keys go to the activity, not to the player's own controls.
        playerView.useController = false
        playerView.isFocusable = false
        playerView.keepScreenOn = true

        navigator = PlaybackNavigator(library)
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

        createPlayer()
        val video = if (continueCurrentOnStart && navigator.current != null) navigator.current else navigator.start()
        continueCurrentOnStart = false
        video?.let(::play)
    }

    override fun onStop() {
        if (::navigator.isInitialized) saveCurrentPosition()
        player?.release()
        player = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun createPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) consecutiveErrors = 0
                if (state == Player.STATE_ENDED) onVideoEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pausedIcon.visibility = if (!isPlaying && exo.playbackState == Player.STATE_READY && !exo.playWhenReady) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) = onVideoFailed()
        })
        playerView.player = exo
        player = exo
    }

    // ---- playback ----

    private fun play(video: Video) {
        val exo = player ?: return
        val start = ResumePolicy.startPosition(positions, video.id)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)), start)
        exo.prepare()
        exo.playWhenReady = true
        showOverlay()
    }

    private fun saveCurrentPosition() {
        val video = navigator.current ?: return
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_IDLE) return
        ResumePolicy.save(positions, video.id, exo.currentPosition, exo.duration)
    }

    /** Up/Down: remember where we were in the video being left, then switch. */
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
        navigator.skipBroken()?.let(::play)
    }

    private fun togglePause() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
        pausedIcon.visibility = if (exo.playWhenReady) View.GONE else View.VISIBLE
        showOverlay()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))
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
                if (player?.playWhenReady == false) togglePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (player?.playWhenReady == true) togglePause()
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
        const val OVERLAY_MS = 4_000L
        const val TOAST_MS = 2_000L
        const val SEEK_MS = 10_000L
    }
}
