package com.kidlocaltv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbDirectTest {

    private val link = SmbLink(host = "192.168.1.20", port = 445, share = "kids", path = "")

    @Test
    fun buildsUrlFromShareAndBackslashPath() {
        assertEquals(
            "smb://192.168.1.20/kids/Cartoons/Ep%201.mp4",
            SmbDirect.url(link, "Cartoons\\Ep 1.mp4"),
        )
    }

    @Test
    fun encodesNonAsciiNamesAsUtf8() {
        assertEquals(
            "smb://192.168.1.20/kids/%E5%AD%97%E6%AF%8D%E7%A7%AF%E6%9C%A8/2/Alphablocks%20S02E07%20Pen.flv",
            SmbDirect.url(link, "字母积木\\2\\Alphablocks S02E07 Pen.flv"),
        )
    }

    @Test
    fun keepsANonDefaultPortAndBracketsIpv6() {
        assertEquals("smb://nas:1445/kids/a.mkv", SmbDirect.url(link.copy(host = "nas", port = 1445), "a.mkv"))
        assertEquals("smb://[fe80::1]/kids/a.mkv", SmbDirect.url(link.copy(host = "fe80::1"), "a.mkv"))
    }

    @Test
    fun encodesCharactersThatWouldBreakTheUrl() {
        val url = SmbDirect.url(link, "Odd #1?\\50% & more+.mp4")
        assertEquals("smb://192.168.1.20/kids/Odd%20%231%3F/50%25%20%26%20more%2B.mp4", url)
    }

    @Test
    fun loginGoesIntoOptionsNotTheUrl() {
        val withLogin = link.copy(username = "mum", password = "s3cret:@/", domain = "HOME")
        val url = SmbDirect.url(withLogin, "a.mp4")
        assertFalse(url.contains("mum"))
        assertFalse(url.contains("s3cret"))
        assertEquals(
            listOf(":smb-user=mum", ":smb-pwd=s3cret:@/", ":smb-domain=HOME"),
            SmbDirect.loginOptions(withLogin),
        )
    }

    @Test
    fun guestShareHasNoLoginOptions() {
        assertTrue(SmbDirect.loginOptions(link).isEmpty())
    }
}
