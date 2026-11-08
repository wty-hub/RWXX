package io.github.rwx

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AuthoritativeOwnerLeaseTest {
    @Test fun `server owner lease stays alive until the host pipe closes`() {
        PipedInputStream().use { input ->
            PipedOutputStream(input).use { output ->
                val lost = CountDownLatch(1)
                val watcher = observeAuthoritativeOwner(input, lost::countDown)
                output.write(0)
                output.flush()
                assertFalse(lost.await(50, TimeUnit.MILLISECONDS), "A live pipe must not terminate its server")
                output.close()
                assertTrue(lost.await(2, TimeUnit.SECONDS), "OS EOF after a host exit must end the server lease")
                watcher.join(2000)
                assertFalse(watcher.isAlive)
            }
        }
    }
}
