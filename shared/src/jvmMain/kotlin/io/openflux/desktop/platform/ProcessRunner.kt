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

    /**
     * What a run produced, with "could not run at all" kept distinct from
     * "ran and exited non-zero".
     *
     * [run] collapses both to null, which is fine for callers that only want a
     * string. It is not fine for a caller that has to tell "the value is not
     * there" from "I could not find out": those look identical from the exit
     * code alone, and conflating them is how a failed read of the registry
     * becomes a recorded claim that the user had no proxy.
     */
    sealed interface Outcome {
        /** The process ran to completion. [output] is stdout and stderr merged. */
        data class Exited(val code: Int, val output: String) : Outcome

        /** The process never started, or overran [timeout] and was killed. */
        data object Unavailable : Outcome
    }

    fun attempt(timeout: Long, unit: TimeUnit, vararg command: String): Outcome = runCatching {
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
            return@runCatching Outcome.Unavailable
        }
        // The process has exited; the reader drains what is left and stops.
        reader.join(2_000)
        Outcome.Exited(process.exitValue(), synchronized(out) { out.toString() })
    }.getOrElse { Outcome.Unavailable }

    fun run(timeout: Long, unit: TimeUnit, vararg command: String): String? =
        when (val o = attempt(timeout, unit, *command)) {
            is Outcome.Exited -> if (o.code == 0) o.output else null
            Outcome.Unavailable -> null
        }
}
