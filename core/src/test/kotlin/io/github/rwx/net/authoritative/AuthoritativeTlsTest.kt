package io.github.rwx.net.authoritative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Version-independent TLS helper checks. Real socket pinning is covered by TimelineTlsTransportTest. */
class AuthoritativeTlsTest {
    @Test fun `helper accepts a complete hexadecimal SHA256 fingerprint with optional formatting`() {
        val fingerprint = "aB".repeat(32)
        for (value in listOf(fingerprint, fingerprint.chunked(2).joinToString(":")))
            assertEquals("TLS", AuthoritativeTls.clientContext(value).protocol)
    }

    @Test fun `helper rejects incomplete nonhex and whitespace decorated fingerprints`() {
        for (value in listOf("", "1234", "xx".repeat(32), "a".repeat(63), "a".repeat(65), " " + "a".repeat(64), ":".repeat(64)))
            assertFailsWith<IllegalArgumentException> { AuthoritativeTls.clientContext(value) }
    }
}
