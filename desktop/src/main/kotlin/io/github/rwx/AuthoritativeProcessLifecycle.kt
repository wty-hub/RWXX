package io.github.rwx

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A host owns this server's stdin pipe. OS closure also covers an ungraceful host exit. */
internal fun observeAuthoritativeOwner(input: InputStream, ownerClosed: () -> Unit): Thread =
    Thread({
        try { while (input.read() != -1) { /* An open pipe is the lease; no credentials travel here. */ } }
        catch (_: java.io.IOException) { /* A broken owned pipe also ends the lease. */ }
        finally { ownerClosed() }
    }, "authority-owner-lease").apply { isDaemon = true; start() }

/** Only Process identities started by this application, plus their verified server descendants. */
internal fun stopAuthoritativeProcess(process: Process) {
    val servers = runCatching { process.descendants().filter { handle ->
        handle.info().arguments().orElse(emptyArray()).contains("--authority-server")
    }.toList() }.getOrDefault(emptyList())
    // Closing the owned pipe lets a server seal its archive before any signal. A host client
    // also releases its child pipe from its shutdown cleanup.
    runCatching { process.outputStream.close() }
    val isServer = process.info().arguments().orElse(emptyArray()).contains("--authority-server")
    if (process.isAlive) {
        if (!isServer) process.destroy()
        if (!process.waitFor(18, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
    for (server in servers) {
        if (server.isAlive) {
            try { server.onExit().get(5, TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { server.destroyForcibly() }
        }
    }
}
