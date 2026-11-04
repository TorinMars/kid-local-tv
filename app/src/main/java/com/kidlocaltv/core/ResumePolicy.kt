package com.kidlocaltv.core

/** Storage for per-video resume positions. */
interface PositionStore {
    fun get(videoId: String): Long
    fun put(videoId: String, positionMs: Long)
    fun remove(videoId: String)
}

/** Rules for when a saved position is worth keeping. */
object ResumePolicy {
    /** Positions closer than this to the start aren't worth resuming. */
    const val MIN_RESUME_MS = 5_000L

    /** If fewer than this many ms remain, treat the video as finished. */
    const val END_MARGIN_MS = 15_000L

    /** Saves (or clears) the position for [videoId] depending on how far the user got. */
    fun save(store: PositionStore, videoId: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        if (positionMs < MIN_RESUME_MS || finished) {
            store.remove(videoId)
        } else {
            store.put(videoId, positionMs)
        }
    }

    /** Where to start [videoId]; 0 if there's no saved position. */
    fun startPosition(store: PositionStore, videoId: String): Long = store.get(videoId).coerceAtLeast(0)
}
