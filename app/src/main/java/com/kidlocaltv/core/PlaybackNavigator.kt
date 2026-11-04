package com.kidlocaltv.core

import kotlin.random.Random

enum class PlaybackMode { SHUFFLE, SERIES }

/**
 * Decides which video plays next. Contains no Android code so it can be unit tested.
 *
 * - Shuffle mode: Up and Down both pick another random video from the whole library.
 *   A "shuffle bag" is used so every video is seen once before any repeats.
 * - Series mode: Up/Down move to the previous/next video in the current video's folder,
 *   wrapping around at either end.
 */
class PlaybackNavigator(
    library: VideoLibrary,
    private val random: Random = Random.Default,
) {
    var library: VideoLibrary = library
        private set

    var mode: PlaybackMode = PlaybackMode.SHUFFLE
        private set

    var current: Video? = null
        private set

    private val shuffleBag = ArrayDeque<Video>()

    /** Random picks already drawn by [preview]; up()/down() in shuffle mode take these first. */
    private val upcomingRandom = ArrayDeque<Video>()

    /**
     * The videos the next Up/Down presses will switch to, nearest first, without switching.
     * Used to preload them. Shuffle mode: the next [ahead] + [behind] random picks (Up and Down
     * both take the next one). Series mode: next episode, previous episode, then further ahead.
     * Never contains the current video or the same video twice.
     */
    fun preview(ahead: Int, behind: Int): List<Video> {
        val out = mutableListOf<Video>()
        when (mode) {
            PlaybackMode.SHUFFLE -> {
                val wanted = ahead + behind
                while (upcomingRandom.size < wanted) {
                    // Keep the preview free of repeats: skip the current video and those already queued.
                    val avoid = upcomingRandom.mapTo(mutableSetOf()) { it.id }.apply { current?.id?.let(::add) }
                    val pick = drawRandom(avoid) ?: break
                    if (pick.id in avoid) break // the library has no other video to offer
                    upcomingRandom.addLast(pick)
                }
                out += upcomingRandom.take(wanted)
            }
            PlaybackMode.SERIES -> {
                val order = mutableListOf<Int>()
                for (i in 1..maxOf(ahead, behind)) {
                    if (i <= ahead) order += i
                    if (i <= behind) order += -i
                }
                for (offset in order) episodeOffset(offset)?.let { out += it }
            }
        }
        val seen = mutableSetOf(current?.id)
        return out.filter { seen.add(it.id) }
    }

    /** Picks a random video to start with (shuffle mode). */
    fun start(): Video? {
        mode = PlaybackMode.SHUFFLE
        return nextRandom().also { current = it }
    }

    /** Resumes a specific video (e.g. after the activity is recreated). */
    fun restore(videoId: String, restoredMode: PlaybackMode): Video? {
        val video = library.find(videoId) ?: return null
        current = video
        mode = restoredMode
        return video
    }

    /** Up on the remote. Returns the video to switch to, or null if nothing changes. */
    fun up(): Video? = when (mode) {
        PlaybackMode.SHUFFLE -> switchTo(nextRandom())
        PlaybackMode.SERIES -> switchTo(episodeOffset(-1))
    }

    /** Down on the remote. Returns the video to switch to, or null if nothing changes. */
    fun down(): Video? = when (mode) {
        PlaybackMode.SHUFFLE -> switchTo(nextRandom())
        PlaybackMode.SERIES -> switchTo(episodeOffset(+1))
    }

    /** Right on the remote: lock onto the current video's folder. Returns true if the mode changed. */
    fun enterSeriesMode(): Boolean {
        if (current == null || mode == PlaybackMode.SERIES) return false
        mode = PlaybackMode.SERIES
        return true
    }

    /** Left on the remote: go back to shuffling the whole library. Returns true if the mode changed. */
    fun enterShuffleMode(): Boolean {
        if (mode == PlaybackMode.SHUFFLE) return false
        mode = PlaybackMode.SHUFFLE
        return true
    }

    /** Called when the current video plays to the end. Series mode continues with the next episode. */
    fun onVideoEnded(): Video? = when (mode) {
        PlaybackMode.SHUFFLE -> switchTo(nextRandom())
        PlaybackMode.SERIES -> switchTo(episodeOffset(+1) ?: current)
    }

    /** Called when the current video can't be played; always moves on to something else. */
    fun skipBroken(): Video? = when (mode) {
        PlaybackMode.SHUFFLE -> switchTo(nextRandom())
        PlaybackMode.SERIES -> switchTo(episodeOffset(+1) ?: nextRandom())
    }

    /** Swap in a freshly scanned library, keeping the current video if it still exists. */
    fun updateLibrary(newLibrary: VideoLibrary) {
        library = newLibrary
        shuffleBag.clear()
        upcomingRandom.clear()
        current = current?.let { newLibrary.find(it.id) }
        if (current == null) mode = PlaybackMode.SHUFFLE
    }

    /** Position of the current video within its folder, as (1-based index, count). */
    fun episodeInfo(): Pair<Int, Int>? {
        val video = current ?: return null
        val episodes = library.episodesOf(video.folder)
        val index = episodes.indexOfFirst { it.id == video.id }
        return if (index < 0) null else (index + 1) to episodes.size
    }

    private fun switchTo(video: Video?): Video? {
        if (video != null) current = video
        return video
    }

    private fun episodeOffset(offset: Int): Video? {
        val video = current ?: return null
        val episodes = library.episodesOf(video.folder)
        if (episodes.size < 2) return null
        val index = episodes.indexOfFirst { it.id == video.id }
        if (index < 0) return null
        val target = (index + offset).mod(episodes.size)
        return episodes[target]
    }

    private fun nextRandom(): Video? {
        // Picks shown by preview() come first, so a preloaded video is what actually plays next.
        while (upcomingRandom.isNotEmpty()) {
            val next = upcomingRandom.removeFirst()
            if (next.id != current?.id) return next
        }
        return drawRandom(setOfNotNull(current?.id))
    }

    /**
     * Draws the next video from the shuffle bag, skipping ids in [avoid] (the video playing now,
     * and videos already queued by [preview]) when the library has anything else.
     */
    private fun drawRandom(avoid: Set<String>): Video? {
        val all = library.videos
        if (all.isEmpty()) return null
        if (all.size == 1) return all[0]
        if (shuffleBag.isEmpty()) shuffleBag.addAll(all.shuffled(random))
        // Look through the bag once for a video that is not avoided; skipped ones go to the back.
        repeat(shuffleBag.size) {
            val candidate = shuffleBag.removeFirst()
            if (candidate.id !in avoid) return candidate
            shuffleBag.addLast(candidate)
        }
        // Everything left in this bag is avoided: start a fresh bag and try once more.
        shuffleBag.addAll(all.shuffled(random))
        repeat(shuffleBag.size) {
            val candidate = shuffleBag.removeFirst()
            if (candidate.id !in avoid) return candidate
            shuffleBag.addLast(candidate)
        }
        return shuffleBag.removeFirst()
    }
}
