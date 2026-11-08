package io.github.rwx.net.timeline

import kotlin.test.*

class TimelineInvitationTest {
    private val fingerprint = "a".repeat(64)
    private val token = "secret_".repeat(8)

    @Test fun `v7 invitations round trip IPv4 IPv6 and DNS without diagnostic credentials`() {
        for (host in listOf("127.0.0.1", "::1", "server.example.com")) {
            val value = TimelineInvitation(host, 5124, fingerprint, token)
            assertEquals(value, TimelineInvitation.parse(value.encode()))
            assertFalse(value.toString().contains(token))
        }
        assertTrue(TimelineInvitation("::1", 5124, fingerprint, token).encode().contains("[::1]:5124"))
    }

    @Test fun `other protocols versions and encoded credentials are rejected without exposing input`() {
        val valid = TimelineInvitation("127.0.0.1", 5124, fingerprint, token).encode()
        for (bad in listOf(valid.replace("rwx-timeline", "rwx-authority"), valid.replace("v=7", "v=6"),
            valid.replace("v=7", "v=8"), "$valid&extra=1", valid.replace("#$token", "#%41" + token.drop(1)),
            valid.replace("/?", "/other?"), valid.replace("127.0.0.1", "user@127.0.0.1"),
            valid.replace("&fingerprint=", "&extra=x&fingerprint="))) {
            val error = assertFailsWith<IllegalArgumentException> { TimelineInvitation.parse(bad) }
            assertFalse(error.message.orEmpty().contains(token))
        }
    }
}
