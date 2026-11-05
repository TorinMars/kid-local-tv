package com.kidlocaltv.server

import java.net.URLDecoder

/**
 * A parsed SMB link. Accepts the usual ways people copy share paths:
 *
 *  - `smb://nas/Videos/Ocean Friends`
 *  - `smb://user:password@192.168.1.20:445/Videos/clip.mkv`
 *  - `smb://WORKGROUP;user@nas/Videos`
 *  - `\\nas\Videos\Ocean Friends` (Windows) and `//nas/Videos` (macOS / Linux)
 *
 * Spaces don't need to be escaped; `%20`-style escapes are decoded.
 */
data class SmbLink(
    val host: String,
    val port: Int,
    val share: String,
    /** Path inside the share, using '/' separators, no leading or trailing slash. Empty = share root. */
    val path: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    /** Path inside the share in the backslash form SMB expects. */
    val smbPath: String get() = path.replace('/', '\\')

    /** Last component of the link: the folder or file name (or the share name at the root). */
    val leafName: String get() = path.substringAfterLast('/').ifEmpty { share }

    /** Same link with credentials filled in from separate fields when the link itself has none. */
    fun withCredentials(user: String?, pass: String?, dom: String?): SmbLink = copy(
        username = username.ifEmpty { user.orEmpty().trim() },
        password = password.ifEmpty { pass.orEmpty() },
        domain = domain.ifEmpty { dom.orEmpty().trim() },
    )

    /** For status messages; never includes the password. */
    override fun toString(): String {
        val portPart = if (port == DEFAULT_PORT) "" else ":$port"
        val pathPart = if (path.isEmpty()) "" else "/$path"
        return "smb://$host$portPart/$share$pathPart"
    }

    companion object {
        const val DEFAULT_PORT = 445

        fun isSmbLink(raw: String): Boolean {
            val s = raw.trim()
            return s.startsWith("smb://", ignoreCase = true) || s.startsWith("\\\\") ||
                (s.startsWith("//") && !s.startsWith("///"))
        }

        /**
         * Typing on a TV is slow, so "192.168.1.20/Videos" is enough there: smb:// is added.
         * `smb://…`, `\\server\share` and `//server/share` are kept as they are.
         */
        fun typedOnTv(raw: String): String {
            val s = raw.trim()
            return when {
                s.isEmpty() -> s
                s.startsWith("smb://", ignoreCase = true) || s.startsWith("\\\\") || s.startsWith("//") -> s
                else -> "smb://" + s.replace('\\', '/')
            }
        }

        /** @throws IllegalArgumentException with a user-readable message if the link is malformed. */
        fun parse(raw: String): SmbLink {
            var s = raw.trim()
            s = when {
                s.startsWith("\\\\") -> "smb://" + s.substring(2).replace('\\', '/')
                s.startsWith("//") -> "smb:$s"
                else -> s
            }
            require(s.startsWith("smb://", ignoreCase = true)) { "SMB links start with smb:// or \\\\" }
            val rest = s.substring("smb://".length)

            val authority = rest.substringBefore('/')
            val pathPart = rest.substringAfter('/', "")

            val userInfo = authority.substringBeforeLast('@', "")
            val hostPort = authority.substringAfterLast('@')

            var domain = ""
            var user = userInfo.substringBefore(':')
            val password = if (userInfo.contains(':')) decode(userInfo.substringAfter(':')) else ""
            if (user.contains(';')) {
                domain = decode(user.substringBefore(';'))
                user = user.substringAfter(';')
            }
            user = decode(user)

            val (host, port) = parseHostPort(hostPort)
            require(host.isNotEmpty()) { "The SMB link is missing a server name" }

            val segments = pathPart.split('/').map { decode(it) }.filter { it.isNotEmpty() }
            require(segments.isNotEmpty()) { "The SMB link must include a share name, e.g. smb://$host/Videos" }

            return SmbLink(
                host = host,
                port = port,
                share = segments.first(),
                path = segments.drop(1).joinToString("/"),
                username = user,
                password = password,
                domain = domain,
            )
        }

        private fun parseHostPort(hostPort: String): Pair<String, Int> {
            if (hostPort.startsWith("[")) { // IPv6 literal
                val host = hostPort.substringAfter('[').substringBefore(']')
                val port = hostPort.substringAfter("]:", "").toIntOrNull() ?: DEFAULT_PORT
                return host to port
            }
            val idx = hostPort.lastIndexOf(':')
            if (idx < 0) return hostPort to DEFAULT_PORT
            val port = hostPort.substring(idx + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port in SMB link")
            return hostPort.substring(0, idx) to port
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            s // stray '%' in a name: keep it literally
        }
    }
}
