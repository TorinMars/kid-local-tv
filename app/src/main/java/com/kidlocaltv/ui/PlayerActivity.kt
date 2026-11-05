package com.kidlocaltv.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.kidlocaltv.core.VideoLibrary
import com.kidlocaltv.core.VideoLocation
import com.kidlocaltv.data.AppPrefs
import com.kidlocaltv.data.PrefsPositionStore
import com.kidlocaltv.server.SmbDirect
import com.kidlocaltv.server.SmbStreamServer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
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
    /** A focusable parental-control overlay must silence playback even when this app is Home. */
    private var focusPausedPlayer: MediaPlayer? = null
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(400).start() }
    private val hideToast = Runnable { toastText.animate().alpha(0f).setDuration(300).start() }

    /** True when we opened the manage screen ourselves and should continue the same video after. */
    private var continueCurrentOnStart = false
    private var consecutiveErrors = 0

    /** Share id of the video now playing through LibVLC's native SMB client, or null. */
    private var playingDirectSource: String? = null
    /** Shares the native SMB client failed to open; they stream through [SmbStreamServer] instead. */
    private val directSmbFailed = mutableSetOf<String>()

    /** The video the current player shows. Differs from navigator.current while a switch is pending. */
    private var playingVideo: Video? = null

    /** Keeps the next few videos ready so Up/Down switch at once. */
    private val preloader = Preloader(
        handler = handler,
        newPlayer = { libVlc?.takeIf { hasMemoryForPreload() }?.let { MediaPlayer(it) } },
        buildMedia = ::buildMedia,
    )
    private val preloadRefresh = Runnable { refreshPreloads() }

    /** A switch to a video that isn't preloaded waits until the Up/Down presses stop. */
    private var pendingPlay: Runnable? = null

    /** Between onStart and onStop. A library load that finishes outside it is dropped. */
    private var started = false
    /** Bumped on every load and on stop, so only the newest load result is used. */
    private var loadGeneration = 0
    /** When the current start began loading, for the startup timing log; 0 once a video plays. */
    private var loadStartedAt = 0L
    /** The saved video playing before the library is in; the navigator learns of it in [onLoaded]. */
    private var quickStart: Video? = null
    private var engineReady = false
    private var loadedLibrary: VideoLibrary? = null
    /** An end or error of the quick-start video that came before the library; handled in [onLoaded]. */
    private var eventBeforeLoad: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        positions = PrefsPositionStore(this)

        if (!prefs.setupComplete) {
            openSetup()
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

        // The window shows straight away; the library and LibVLC load in the background (onStart).
        bufferingView.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        // The LibVLC instance is shared by the whole process (VlcEngine), so it is not released here.
        libVlc = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        started = true
        startStreamServer()
        loadLibraryThenPlay()
    }

    /**
     * Gets LibVLC and scans the library off the main thread, in parallel. Both are slow on low-end
     * TVs; on the main thread they froze the window long enough to cause an ANR.
     *
     * The video saved last time ([AppPrefs.startVideo]) starts as soon as LibVLC is ready, without
     * waiting for the library; Up/Down work once the library is in.
     */
    private fun loadLibraryThenPlay() {
        val generation = ++loadGeneration
        val app = applicationContext
        loadStartedAt = SystemClock.elapsedRealtime()
        loadedLibrary = null
        engineReady = false
        quickStart = if (continueCurrentOnStart && ::navigator.isInitialized && navigator.current != null) null else prefs.startVideo
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val vlc = try {
                VlcEngine.get(app, VLC_OPTIONS)
            } catch (e: Throwable) {
                Log.e(TAG, "LibVLC could not start", e)
                null
            }
            Log.i(TAG, "Startup: LibVLC ${SystemClock.elapsedRealtime() - t0} ms")
            handler.post { onEngineReady(generation, vlc) }
        }.apply { isDaemon = true; name = "player-engine" }.start()
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val library = prefs.scanLibrary()
            Log.i(TAG, "Startup: library ${SystemClock.elapsedRealtime() - t0} ms (${library.size} videos)")
            handler.post { onLibraryReady(generation, library) }
        }.apply { isDaemon = true; name = "player-library" }.start()
    }

    private fun isCurrentLoad(generation: Int) = generation == loadGeneration && started && !isFinishing && !isDestroyed

    private fun onEngineReady(generation: Int, vlc: LibVLC?) {
        if (!isCurrentLoad(generation)) return
        engineReady = true
        libVlc = vlc
        if (vlc == null) {
            bufferingView.visibility = View.GONE
            showToast(getString(R.string.nothing_playable))
            return
        }
        quickStart?.let {
            Log.i(TAG, "Startup: opening the saved start video after ${SystemClock.elapsedRealtime() - loadStartedAt} ms")
            play(it)
        }
        loadedLibrary?.let { onLoaded(it) }
    }

    private fun onLibraryReady(generation: Int, library: VideoLibrary) {
        if (!isCurrentLoad(generation)) return
        loadedLibrary = library
        if (engineReady) onLoaded(library)
    }

    /** Runs once both LibVLC and the library are in. */
    private fun onLoaded(library: VideoLibrary) {
        loadedLibrary = null
        if (library.isEmpty) {
            prefs.startVideo = null
            openSetup()
            return
        }
        if (libVlc == null) return
        if (::navigator.isInitialized) navigator.updateLibrary(library) else navigator = PlaybackNavigator(library)
        refreshNetworkSourcesInBackground()

        val quick = quickStart
        quickStart = null
        val waiting = eventBeforeLoad
        eventBeforeLoad = null
        if (quick != null && navigator.restore(quick.id, PlaybackMode.SHUFFLE) != null) {
            // The saved video is already on screen; the navigator now knows it.
            showOverlay()
            when (waiting) {
                MediaPlayer.Event.EndReached -> onVideoEnded()
                MediaPlayer.Event.EncounteredError -> onVideoFailed()
                else -> if (player?.isPlaying == true) schedulePreloads(PRELOAD_AFTER_START_MS)
            }
        } else {
            // Nothing saved, the saved video is gone, or the activity came back from the manage screen.
            val video = if (quick == null && continueCurrentOnStart && navigator.current != null) navigator.current else navigator.start()
            video?.let(::play)
        }
        continueCurrentOnStart = false
        saveNextStartVideo(library)
    }

    /** A random video, not the one on screen, to open at once next time the app starts. */
    private fun saveNextStartVideo(library: VideoLibrary) {
        val current = navigator.current?.id
        val choices = library.videos.filter { it.id != current }.ifEmpty { library.videos }
        prefs.startVideo = choices.random()
    }

    private fun openSetup() {
        startActivity(SetupActivity.intent(this, manage = false))
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            pauseForFocusLoss()
        } else {
            val waiting = focusPausedPlayer
            focusPausedPlayer = null
            if (waiting != null && player === waiting && started && !paused) waiting.play()
        }
    }

    private fun pauseForFocusLoss() {
        val current = player ?: return
        if (!paused && current.isPlaying) {
            focusPausedPlayer = current
            current.pause()
        }
    }

    override fun onStop() {
        started = false
        focusPausedPlayer = null
        loadGeneration++ // a load still running finishes into nothing
        if (::navigator.isInitialized) saveCurrentPosition()
        pendingPlay = null
        preloader.clear()
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
                handler.post {
                    if (player != null && !fresh.isEmpty) {
                        navigator.updateLibrary(fresh)
                        schedulePreloads(MODE_CHANGE_PRELOAD_MS) // the upcoming picks were redrawn
                    }
                }
            }
        }.apply { isDaemon = true; name = "network-rescan" }.start()
    }

    private fun createPlayer() {
        val vlc = libVlc ?: return
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        listenAsCurrent(mp)
        player = mp
    }

    /** The event handling of the player on screen. */
    private fun listenAsCurrent(mp: MediaPlayer) {
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                // Only react to events from the player that's currently attached.
                if (player !== mp) return
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        consecutiveErrors = 0
                        bufferingView.visibility = View.GONE
                        if (loadStartedAt > 0) {
                            Log.i(TAG, "Startup: first video playing after ${SystemClock.elapsedRealtime() - loadStartedAt} ms")
                            loadStartedAt = 0
                        }
                        // Loading can finish after a lock overlay has already taken focus.
                        if (!hasWindowFocus()) pauseForFocusLoss()
                        // The video on screen has started: now prepare the next ones.
                        schedulePreloads(PRELOAD_AFTER_START_MS)
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
    }

    /** Returns false when there was no player to release ([onReleased] then never runs). */
    private fun releasePlayer(onReleased: Runnable? = null): Boolean {
        val mp = player ?: return false
        player = null
        mp.setEventListener(null)
        mp.detachViews()
        // stop() can block for seconds on a stuck network read; never wait for it on the main thread.
        VlcEngine.releaseInBackground(mp, onReleased)
        return true
    }

    // ---- playback ----

    private fun play(video: Video) {
        cancelPendingPlay()
        handler.removeCallbacks(preloadRefresh)
        // Preloaded: it is already open, buffered and paused; only the picture needs attaching.
        preloader.take(video)?.let {
            showPreloaded(video, it)
            return
        }
        if (libVlc == null) return
        // A fresh player per video: reusing one means setMedia(), which first stops the old input
        // and blocks just like stop(). The old player is stopped in the background instead.
        releasePlayer()
        createPlayer()
        val mp = player ?: return
        val prepared = buildMedia(video)
        if (prepared == null) {
            playingVideo = video
            onVideoFailed()
            return
        }
        playingDirectSource = prepared.directSource
        playingVideo = video
        mp.media = prepared.media
        prepared.media.release()
        bufferingView.visibility = View.VISIBLE
        startedAt = SystemClock.elapsedRealtime()
        mp.play()
        paused = false
        pausedIcon.visibility = View.GONE
        showOverlay()
    }

    /**
     * Puts a preloaded player on screen. It was preloaded without video (no surface to decode to),
     * so the hardware decoder is opened now. Order matters:
     *  1. attach the views, and wait until both the surface exists and the previous player has been
     *     released ([SWITCH_WAIT_MS] at most). The TV's hardware decoder serves one player at a time:
     *     while the old player still held it, LibVLC fell back to software decoding (avcodec), which
     *     is heavy on this CPU;
     *  2. select the video track: LibVLC opens the hardware decoder on that surface;
     *  3. seek to where the player is paused, so the decoder starts from a keyframe instead of
     *     showing nothing until the next one (the data is still in LibVLC's read-ahead buffer);
     *  4. play.
     */
    private fun showPreloaded(video: Video, warm: Preloader.Warm) {
        var surfaceReady = false
        var oldReleased = false
        var start: Runnable? = null
        val startWhenReady = Runnable { if (surfaceReady && oldReleased) start?.run() }
        if (!releasePlayer(Runnable { oldReleased = true; startWhenReady.run() })) oldReleased = true
        val mp = warm.player
        listenAsCurrent(mp)
        player = mp
        playingDirectSource = warm.directSource
        playingVideo = video
        paused = false
        pausedIcon.visibility = View.GONE
        showOverlay()

        var begun = false
        start = Runnable {
            if (begun || player !== mp) return@Runnable
            begun = true
            // Select the video track explicitly, through -1 first, so LibVLC (re)opens the decoder
            // now that the surface exists. setVideoTrackEnabled(true) does nothing when a track is
            // still selected, and a decoder opened without a surface is a software one.
            val track = mp.videoTracks?.firstOrNull { it.id >= 0 }?.id
            if (track != null) {
                mp.setVideoTrack(-1)
                mp.setVideoTrack(track)
            }
            val position = mp.time
            if (position >= 0) mp.time = position
            mp.volume = 100
            startedAt = SystemClock.elapsedRealtime()
            mp.play()
        }
        val vout = mp.vlcVout
        vout.addCallback(object : IVLCVout.Callback {
            override fun onSurfacesCreated(v: IVLCVout) {
                v.removeCallback(this)
                handler.post {
                    surfaceReady = true
                    startWhenReady.run()
                }
            }

            override fun onSurfacesDestroyed(v: IVLCVout) {}
        })
        mp.attachViews(videoLayout, null, false, false)
        // Never wait longer than this; at worst the video decodes in software.
        handler.postDelayed(start, SWITCH_WAIT_MS)
    }

    /**
     * The media for [video], with the same options for normal playback and for preloading.
     * Null when it can't be opened (a fallback share while the loopback server is down).
     */
    private fun buildMedia(video: Video): Preloader.Prepared? {
        val vlc = libVlc ?: return null
        var directSource: String? = null
        val media = when (val location = video.location) {
            is VideoLocation.LocalFile -> Media(vlc, Uri.fromFile(location.file))
            is VideoLocation.Network -> {
                // First choice: LibVLC's own native SMB client. The loopback server (pure-Java smbj)
                // used a whole CPU core on slow TVs and playback starved; it stays as the fallback.
                val source = if (location.sourceId in directSmbFailed) null else prefs.networkSources.find(location.sourceId)
                if (source != null) {
                    directSource = location.sourceId
                    Media(vlc, Uri.parse(SmbDirect.url(source.link, location.path))).apply {
                        SmbDirect.loginOptions(source.link).forEach(::addOption)
                        // The native client delivers steadily, so a short buffer is enough and the
                        // video starts sooner after Up/Down.
                        addOption(":network-caching=$DIRECT_NETWORK_CACHING_MS")
                    }
                } else {
                    val server = streamServer ?: return null
                    Media(vlc, Uri.parse(server.urlFor(location))).apply {
                        // A little more buffer for Wi-Fi hiccups when streaming from a share.
                        addOption(":network-caching=$NETWORK_CACHING_MS")
                    }
                }
            }
        }
        // Hardware decoding when the TV supports the codec; LibVLC falls back to software otherwise.
        media.setHWDecoderEnabled(true, false)
        val start = ResumePolicy.startPosition(positions, video.id)
        if (start > 0) media.addOption(":start-time=${start / 1000.0}")
        return Preloader.Prepared(media, directSource)
    }

    // ---- preloading ----

    private fun schedulePreloads(delayMs: Long) {
        handler.removeCallbacks(preloadRefresh)
        handler.postDelayed(preloadRefresh, delayMs)
    }

    /** Asks the preloader for the videos the next presses will reach, in the current mode. */
    private fun refreshPreloads() {
        if (!started || player == null || !::navigator.isInitialized) return
        val (ahead, behind) = if (navigator.mode == PlaybackMode.SERIES) SERIES_AHEAD to SERIES_BEHIND else SHUFFLE_AHEAD to 0
        preloader.want(navigator.preview(ahead, behind))
    }

    /** Each preloaded player holds a few MB; stop adding them when the TV runs short of memory. */
    private fun hasMemoryForPreload(): Boolean {
        val info = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        return !info.lowMemory && info.availMem > MIN_FREE_MEMORY_FOR_PRELOAD
    }

    private fun cancelPendingPlay() {
        pendingPlay?.let(handler::removeCallbacks)
        pendingPlay = null
    }

    private fun saveCurrentPosition() {
        val video = playingVideo ?: return
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
        if (preloader.isReady(next)) {
            play(next)
            return
        }
        // Not preloaded: show which video is coming, and start loading it only once the presses stop,
        // so a fast run of Up/Down doesn't open every video it passes. The old one plays meanwhile.
        cancelPendingPlay()
        handler.removeCallbacks(preloadRefresh)
        showOverlay()
        val run = Runnable {
            pendingPlay = null
            play(next)
        }
        pendingPlay = run
        handler.postDelayed(run, COLD_SWITCH_DELAY_MS)
    }

    private fun onVideoEnded() {
        if (!::navigator.isInitialized) { eventBeforeLoad = MediaPlayer.Event.EndReached; return }
        // A switch is already on its way; the old video ending changes nothing.
        if (pendingPlay != null) return
        playingVideo?.let { positions.remove(it.id) }
        navigator.onVideoEnded()?.let(::play)
    }

    private fun onVideoFailed() {
        if (!::navigator.isInitialized) { eventBeforeLoad = MediaPlayer.Event.EncounteredError; return }
        // A switch is already on its way; the old video failing changes nothing.
        if (pendingPlay != null) return
        // LibVLC's native SMB client couldn't open this share: use the loopback server for it from
        // now on, and retry the same video that way before counting it as broken.
        val direct = playingDirectSource
        val current = playingVideo ?: navigator.current
        if (direct != null && current != null) {
            Log.w(TAG, "Native SMB playback failed; streaming this share through the app instead")
            directSmbFailed += direct
            play(current)
            return
        }
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
        // Until the background load is done, the player or the navigator may not exist yet.
        if (player == null || !::navigator.isInitialized) return super.onKeyDown(keyCode, event)
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
                        schedulePreloads(MODE_CHANGE_PRELOAD_MS) // Up/Down now mean other videos
                    }
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (firstPress) {
                    if (navigator.enterShuffleMode()) {
                        showToast(getString(R.string.shuffle_mode_on))
                        schedulePreloads(MODE_CHANGE_PRELOAD_MS)
                    }
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
        if (player == null || !::navigator.isInitialized) return super.onKeyLongPress(keyCode, event)
        if (isCenterKey(keyCode)) {
            openManageScreen()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (player == null || !::navigator.isInitialized) return super.onKeyUp(keyCode, event)
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
        if (!::navigator.isInitialized) return
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
        const val TAG = "PlayerActivity"

        /** Options tuned for modest TV hardware: keep audio smooth, let software decoding skip work. */
        val VLC_OPTIONS = arrayListOf(
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1", // cheaper software H.264/HEVC decoding on slow boxes
            "--no-sub-autodetect-file",
            "--no-osd",
            // LibVLC reads up to 16 MB ahead of every open file by default. With several videos
            // preloaded that is too much memory and network for a 1 GB TV; 4 MB is ~18 s at 1.85 Mbit/s.
            "--prefetch-buffer-size=4096",
        )
        const val NETWORK_CACHING_MS = 3_000
        const val DIRECT_NETWORK_CACHING_MS = 1_000

        /** Videos kept preloaded: shuffle mode takes the next random picks for both Up and Down. */
        const val SHUFFLE_AHEAD = 5
        /** Series mode: the next episodes (Down) and the previous ones (Up). */
        const val SERIES_AHEAD = 3
        const val SERIES_BEHIND = 2
        /** Let the video on screen start smoothly before preloading competes for CPU and network. */
        const val PRELOAD_AFTER_START_MS = 1_500L
        const val MODE_CHANGE_PRELOAD_MS = 500L
        /** How long Up/Down must rest before a video that isn't preloaded starts loading. */
        const val COLD_SWITCH_DELAY_MS = 350L
        /** Longest wait for the surface and the old player's release before a preloaded player starts. */
        const val SWITCH_WAIT_MS = 1_500L
        const val MIN_FREE_MEMORY_FOR_PRELOAD = 200L * 1024 * 1024
        const val RESCAN_AFTER_MS = 10 * 60_000L
        const val OVERLAY_MS = 4_000L
        const val TOAST_MS = 2_000L
        const val SEEK_MS = 10_000L
    }
}
