package com.kidlocaltv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbStreamServerTest {
    @Test
    fun parsesRangeHeaders() {
        assertEquals(0L to 999L, SmbStreamServer.parseRange(null, 1000))
        assertEquals(100L to 199L, SmbStreamServer.parseRange("bytes=100-199", 1000))
        assertEquals(900L to 999L, SmbStreamServer.parseRange("bytes=900-", 1000))
        assertEquals(500L to 999L, SmbStreamServer.parseRange("bytes=-500", 1000))
        assertEquals(990L to 999L, SmbStreamServer.parseRange("bytes=990-5000", 1000))
        assertEquals(0L to 999L, SmbStreamServer.parseRange("bytes=-5000", 1000))
    }

    @Test
    fun rejectsUnsatisfiableRanges() {
        assertNull(SmbStreamServer.parseRange("bytes=1000-", 1000))
        assertNull(SmbStreamServer.parseRange("bytes=500-100", 1000))
        assertNull(SmbStreamServer.parseRange("bytes=abc-", 1000))
    }
}
