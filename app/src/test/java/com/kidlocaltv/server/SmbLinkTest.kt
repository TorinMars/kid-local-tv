package com.kidlocaltv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbLinkTest {

    @Test
    fun parsesPlainLinkWithSpaces() {
        val link = SmbLink.parse("smb://nas/Videos/Ocean Friends/")
        assertEquals("nas", link.host)
        assertEquals(445, link.port)
        assertEquals("Videos", link.share)
        assertEquals("Ocean Friends", link.path)
        assertEquals("Ocean Friends", link.leafName)
        assertEquals("", link.username)
    }

    @Test
    fun parsesCredentialsPortAndEscapes() {
        val link = SmbLink.parse("smb://WORKGROUP;mum:p%40ss+word@192.168.1.20:4450/Kids%20TV/Season%201/Ep%201.mkv")
        assertEquals("192.168.1.20", link.host)
        assertEquals(4450, link.port)
        assertEquals("mum", link.username)
        assertEquals("p@ss+word", link.password)
        assertEquals("WORKGROUP", link.domain)
        assertEquals("Kids TV", link.share)
        assertEquals("Season 1/Ep 1.mkv", link.path)
        assertEquals("Season 1\\Ep 1.mkv", link.smbPath)
    }

    @Test
    fun parsesWindowsAndUnixStyles() {
        val win = SmbLink.parse("\\\\NAS\\Videos\\Trains")
        assertEquals("NAS", win.host)
        assertEquals("Videos", win.share)
        assertEquals("Trains", win.path)

        val unix = SmbLink.parse("//nas/Videos")
        assertEquals("Videos", unix.share)
        assertEquals("", unix.path)
        assertEquals("Videos", unix.leafName)
    }

    @Test
    fun separateCredentialsOnlyFillGaps() {
        val link = SmbLink.parse("smb://nas/Videos").withCredentials("dad", "secret", "")
        assertEquals("dad", link.username)
        assertEquals("secret", link.password)
        val inline = SmbLink.parse("smb://mum:pw@nas/Videos").withCredentials("dad", "secret", null)
        assertEquals("mum", inline.username)
        assertEquals("pw", inline.password)
    }

    @Test
    fun toStringNeverShowsPassword() {
        val link = SmbLink.parse("smb://mum:hunter2@nas/Videos/Shows")
        assertEquals("smb://nas/Videos/Shows", link.toString())
        assertFalse(link.toString().contains("hunter2"))
    }

    @Test
    fun detectsSmbLinks() {
        assertTrue(SmbLink.isSmbLink("SMB://nas/x"))
        assertTrue(SmbLink.isSmbLink("\\\\nas\\x"))
        assertTrue(SmbLink.isSmbLink("//nas/x"))
        assertFalse(SmbLink.isSmbLink("http://nas/x.mp4"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLinkWithoutShare() {
        SmbLink.parse("smb://nas/")
    }

    @Test
    fun typedOnTvAddsTheScheme() {
        assertEquals("smb://192.168.1.20/Videos", SmbLink.typedOnTv(" 192.168.1.20/Videos "))
        assertEquals("smb://nas/Videos/Kids", SmbLink.typedOnTv("nas\\Videos\\Kids"))
        assertEquals("SMB://nas/Videos", SmbLink.typedOnTv("SMB://nas/Videos"))
        assertEquals("\\\\nas\\Videos", SmbLink.typedOnTv("\\\\nas\\Videos"))
        assertEquals("//nas/Videos", SmbLink.typedOnTv("//nas/Videos"))
        assertEquals("", SmbLink.typedOnTv("  "))
        val link = SmbLink.parse(SmbLink.typedOnTv("192.168.1.20/Videos/Kids"))
        assertEquals("192.168.1.20", link.host)
        assertEquals("Videos", link.share)
        assertEquals("Kids", link.path)
    }
}
