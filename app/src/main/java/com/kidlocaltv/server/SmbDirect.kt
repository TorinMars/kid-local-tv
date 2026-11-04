package com.kidlocaltv.server

import java.net.URLEncoder

/**
 * Lets LibVLC open a share file itself, with its own native SMB clients.
 *
 * Streaming through [SmbStreamServer] goes through smbj, a pure-Java SMB client. On a 32-bit TV
 * its packet reader used a whole CPU core to deliver a 1.85 Mbit/s video, and playback starved.
 * LibVLC's native clients do the same work with far less CPU. The loopback server stays as the
 * fallback for shares they can't open.
 *
 * LibVLC tries libsmb2 (SMB2/3) first and then libdsm (SMB1). Against a macOS share libsmb2 got
 * STATUS_LOGON_FAILURE with the same login smbj and libdsm accept, so libdsm does the playing
 * there. libdsm looks up the server's NetBIOS name when the link uses an IP address and waits 1 s
 * when the server doesn't answer; LibVLC's taglib meta reader opens the file a second time, so
 * that wait happens twice per video.
 */
object SmbDirect {
    /** [path] is the file's path from the share root, as stored in the index (`\` or `/` separators). */
    fun url(link: SmbLink, path: String): String {
        val host = if (link.host.contains(':')) "[${link.host}]" else link.host
        val port = if (link.port == SmbLink.DEFAULT_PORT) "" else ":${link.port}"
        val segments = listOf(link.share) + path.split('\\', '/').filter { it.isNotEmpty() }
        return "smb://$host$port/" + segments.joinToString("/") { encode(it) }
    }

    /** LibVLC options with the login, so it is never part of the URL. Empty for guest shares. */
    fun loginOptions(link: SmbLink): List<String> = buildList {
        if (link.username.isNotEmpty()) add(":smb-user=${link.username}")
        if (link.password.isNotEmpty()) add(":smb-pwd=${link.password}")
        if (link.domain.isNotEmpty()) add(":smb-domain=${link.domain}")
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
