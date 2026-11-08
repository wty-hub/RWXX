package io.github.rwx.net.timeline

import java.net.URI

/** A private v7 seat credential. Do not open this URI in a browser or include it in diagnostics. */
data class TimelineInvitation(val host: String, val port: Int, val fingerprint: String, val token: String) {
    init {
        validateHost(host)
        require(port in 1..65535) { "Invalid server port" }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid server fingerprint" }
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Invalid seat credential" }
    }

    fun encode(): String = URI("rwx-timeline", null, host, port, "/", "v=7&fingerprint=$fingerprint", token).toASCIIString()
    override fun toString() = "TimelineInvitation($host:$port, private seat)"

    companion object {
        fun endpoint(text: String): Pair<String, Int> {
            require(text.length in 1..300) { "Enter a server address and port" }
            val uri = try { URI("rwx-timeline://${text.trim()}") }
                catch (_: Exception) { throw IllegalArgumentException("Invalid server address") }
            require(uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                "Enter only a server address and port"
            }
            val host = requireNotNull(uri.host) { "Invalid server address" }.removeSurrounding("[", "]")
            validateHost(host)
            val port = if (uri.port == -1) 5124 else uri.port
            require(port in 1..65535) { "Invalid server port" }
            return host to port
        }

        fun parse(text: String): TimelineInvitation {
            require(text.length <= 1024) { "Invalid timeline invitation" }
            val uri = try { URI(text.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid timeline invitation") }
            require(uri.scheme == "rwx-timeline") { "This invitation requires a different network protocol" }
            require(uri.rawUserInfo == null && uri.rawPath == "/" && uri.port in 1..65535) { "Invalid timeline invitation" }
            val query = uri.rawQuery.orEmpty().split('&')
            require(query.size == 2 && query[0] == "v=7" && query[1].startsWith("fingerprint=")) { "Unsupported timeline invitation version" }
            return TimelineInvitation(requireNotNull(uri.host) { "Invalid server address" }.removeSurrounding("[", "]"),
                uri.port, query[1].substringAfter('='), uri.rawFragment.orEmpty())
        }

        private fun validateHost(host: String) {
            require(host.length in 1..253 && host.none { it.isWhitespace() || it in "/\\?#%@" } &&
                host !in setOf("0.0.0.0", "::")) { "Use an address the other player can reach" }
            val uri = try { URI("rwx-timeline", null, host, 5124, null, null, null) }
                catch (_: Exception) { throw IllegalArgumentException("Invalid server address") }
            require(uri.host?.removeSurrounding("[", "]") == host) { "Invalid server address" }
        }
    }
}
