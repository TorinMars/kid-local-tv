package com.kidlocaltv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {
    private class MapStore : PositionStore {
        val map = mutableMapOf<String, Long>()
        override fun get(videoId: String) = map[videoId] ?: 0L
        override fun put(videoId: String, positionMs: Long) { map[videoId] = positionMs }
        override fun remove(videoId: String) { map.remove(videoId) }
    }

    @Test
    fun savesMidVideoPosition() {
        val store = MapStore()
        ResumePolicy.save(store, "a", 60_000, 600_000)
        assertEquals(60_000L, ResumePolicy.startPosition(store, "a"))
    }

    @Test
    fun clearsWhenNearlyFinishedOrBarelyStarted() {
        val store = MapStore()
        store.put("a", 1)
        ResumePolicy.save(store, "a", 595_000, 600_000)
        assertEquals(0L, ResumePolicy.startPosition(store, "a"))
        store.put("b", 1)
        ResumePolicy.save(store, "b", 2_000, 600_000)
        assertEquals(0L, ResumePolicy.startPosition(store, "b"))
    }
}
