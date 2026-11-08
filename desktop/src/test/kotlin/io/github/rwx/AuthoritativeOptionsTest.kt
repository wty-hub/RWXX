package io.github.rwx

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AuthoritativeOptionsTest {
    @Test fun `removed or unknown authority switches fail before any graphical entry`() {
        assertFalse(AuthoritativeDesktopMain.handlesArguments(emptyArray()))
        for (mode in listOf("--authority-verify", "--authority-unknown")) {
            assertTrue(AuthoritativeDesktopMain.handlesArguments(arrayOf(mode)))
            assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf(mode)) }
            assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-server", mode)) }
        }
    }

    @Test fun `view count is bounded and only graphical timeline modes accept it`() {
        for (mode in listOf("--authority-client", "--authority-host", "--authority-replay")) {
            val extra = if (mode == "--authority-replay") listOf("--replay-file=sample.rwxr") else emptyList()
            for (value in listOf("1", "2", "3", "4")) assertEquals(value,
                AuthorityOptions.parse((listOf(mode, "--views=$value") + extra).toTypedArray()).values["views"])
            for (value in listOf("0", "5", "-1", "1.5", "NaN")) assertFailsWith<IllegalArgumentException> {
                AuthorityOptions.parse((listOf(mode, "--views=$value") + extra).toTypedArray())
            }
        }
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-server", "--views=1")) }
    }
    @Test
    fun `new protocol requires one explicit mode and rejects ambiguous options`() {
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(emptyArray()) }
        assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-server", "--authority-client"))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-server", "--port=1", "--port=2"))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-server", "--legacy=true"))
        }
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-server", "--port=0")) }
        assertEquals(5124, AuthorityOptions.parse(arrayOf("--authority-server")).port)
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-replay")) }
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-server", "--record-view=file.rwxview")) }
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-client", "--client-renderer=other")) }
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-replay", "--replay-file=file.rwxview", "--speed=NaN")) }
        for (renderer in listOf("kool", "swing")) assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-client", "--client-renderer=$renderer"))
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-server", "--map-cache-dir=cache"))
        }
        for (limit in listOf("-1", "257", "NaN", "1.5")) assertFailsWith<IllegalArgumentException> {
            AuthorityOptions.parse(arrayOf("--authority-client", "--map-cache-limit=$limit"))
        }
        for (limit in listOf("0", "32", "256")) assertEquals(limit,
            AuthorityOptions.parse(arrayOf("--authority-client", "--map-cache-limit=$limit")).values["map-cache-limit"])
        assertEquals("--authority-replay", AuthorityOptions.parse(arrayOf("--authority-replay", "--replay-file=file.rwxview", "--speed=2")).mode)
        assertEquals("0.3", AuthorityOptions.parse(arrayOf("--authority-replay", "--replay-file=file.rwxr", "--speed=0.3")).values["speed"])
        assertEquals("archives", AuthorityOptions.parse(arrayOf("--authority-server", "--archive-dir=archives")).values["archive-dir"])
        assertFailsWith<IllegalArgumentException> { AuthorityOptions.parse(arrayOf("--authority-client", "--archive-dir=archives")) }
    }

    @Test
    fun `seat file binds distinct secrets to server chosen teams including optional late seats`() {
        val file = Files.createTempFile("rwxx-seats-", ".properties").toFile()
        try {
            val first = "a".repeat(32)
            val second = "b".repeat(32)
            file.writeText("0=$first\n1=$second\n")
            assertEquals(mapOf(first to 0, second to 1), AuthoritativeDesktopMain.readSeats(file.path))
            val third = "c".repeat(32)
            file.writeText("0=$first\n1=$second\n63=$third\n")
            assertEquals(mapOf(first to 0, second to 1, third to 63), AuthoritativeDesktopMain.readSeats(file.path))
            file.writeText((0..63).joinToString("\n") { "$it=${it.toString().padStart(32, 'x')}" })
            assertEquals(64, AuthoritativeDesktopMain.readSeats(file.path).size)
            for (invalid in listOf("0=$first\n1=$first\n", "0=short\n1=$second\n",
                "0=$first\n2=$second\n", "0=$first\n1=$second\n2=extra\n", "0=${"字".repeat(32)}\n1=$second\n")) {
                file.writeText(invalid)
                assertFailsWith<IllegalArgumentException> { AuthoritativeDesktopMain.readSeats(file.path) }
            }
        } finally { file.delete() }
    }
}
