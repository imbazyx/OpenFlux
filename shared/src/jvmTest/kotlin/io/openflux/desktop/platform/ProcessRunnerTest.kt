package io.openflux.desktop.platform

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A timeout that has never fired is a comment. These two cases are the ones the
 * three call sites were written for, and both used to hang the calling thread
 * forever because the pipe was read before the wait.
 */
class ProcessRunnerTest {

    @Test
    fun `returns output and exit status when the process finishes`() {
        val out = ProcessRunner.run(30, TimeUnit.SECONDS, "cmd", "/c", "echo hello")
        assertEquals("hello", out?.trim())
    }

    @Test
    fun `gives up on a process that never exits`() {
        // The regression this guards: readText() would block on this forever and
        // the waitFor below it would never be reached.
        val started = System.nanoTime()
        val out = ProcessRunner.run(2, TimeUnit.SECONDS, "cmd", "/c", "ping -n 60 127.0.0.1 > nul")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(out, "a process that overran must not report output")
        assertTrue(elapsedMs < 15_000, "expected the timeout to fire, took ${elapsedMs}ms")
    }

    @Test
    fun `non-zero exit is a failure, not output`() {
        assertNull(ProcessRunner.run(30, TimeUnit.SECONDS, "cmd", "/c", "exit 3"))
    }
}
