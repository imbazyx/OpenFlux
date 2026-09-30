package io.openflux.desktop.platform

import java.util.concurrent.TimeUnit

/**
 * Runs a helper process and returns its output, or null if it failed or
 * overran.
 *
 * The order matters and is the whole point of this class. Reading the pipe to
 * EOF first blocks until the process closes it, so a process that never exits -
 * a wedged reg.exe, a powershell waiting on a UAC prompt the user never saw -
 * hangs the calling thread forever, and the waitFor below that read could never
 * fire. Three call sites had exactly that shape, so their timeouts were
 * decoration.
 *
 * Draining the pipe on its own thread keeps the child from blocking on a full
 * pipe, which would otherwise look identical to a hang, and lets the timeout be
 * a real deadline rather than a comment.
 */
object ProcessRunner {

    fun run(timeout: Long, unit: TimeUnit, vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = StringBuilder()

        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.appendRange(buf, 0, n) }
                    }
                }
            }
            Unit
        }, "process-out")
        reader.isDaemon = true
        reader.start()

        if (!process.waitFor(timeout, unit)) {
            process.destroyForcibly()
            // Let the reader see the pipe close before giving up on its output.
            reader.join(500)
            return null
        }
        // The process has exited; the reader drains what is left and stops.
        reader.join(2_000)
        if (process.exitValue() != 0) null else synchronized(out) { out.toString() }
    }.getOrNull()
}
