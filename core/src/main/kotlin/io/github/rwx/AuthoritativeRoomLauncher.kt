package io.github.rwx

/** Optional platform capability. New rooms run separately from the legacy local game session. */
interface AuthoritativeRoomLauncher {
    /** [endpoint] is the address and port the other player can reach, for example 192.168.1.2:5124. */
    fun host(endpoint: String, playerName: String): AuthoritativeHostLaunch
    /** The invitation binds a seat token to one server and its pinned TLS certificate. */
    fun join(invitation: String, playerName: String)
}

data class AuthoritativeHostLaunch(val invitation: String) {
    override fun toString(): String = "AuthoritativeHostLaunch(private invitation)"
}
