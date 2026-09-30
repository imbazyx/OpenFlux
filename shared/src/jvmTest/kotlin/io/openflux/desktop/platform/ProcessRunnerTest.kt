package io.openflux.desktop.platform

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A timeout that has never fired is a comment. These are the cases the three
 * call sites were written for, and both used to hang the calling thread forever
 * because the pipe was read before the wait.
 *
 * The commands are chosen per OS rather than assuming cmd.exe: this suite runs
 * on the Windows build and on the Linux build, and a test that only works on
 * one of them is worse than none - it looks like coverage everywhere except
 * where it is actually absent.
 */
class ProcessRunnerTest {

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    /** A command that prints one line and exits successfully. */
    private val printsHello: Array<String>
        get() = if (windows) arrayOf("cmd", "/c", "echo hello") else arrayOf("sh", "-c", "echo hello")

    /** A command that never exits on its own. */
    private val neverExits: Array<String>
        get() = if (windows) arrayOf("cmd", "/c", "ping -n 60 127.0.0.1 > nul")
        else arrayOf("sh", "-c", "sleep 60")

    private val exitsThree: Array<String>
        get() = if (windows) arrayOf("cmd", "/c", "exit 3") else arrayOf("sh", "-c", "exit 3")

    @Test
    fun `returns output and exit status when the process finishes`() {
        val out = ProcessRunner.run(30, TimeUnit.SECONDS, *printsHello)
        assertEquals("hello", out?.trim())
    }

    @Test
    fun `gives up on a process that never exits`() {
        // The regression this guards: readText() would block on this forever and
        // the waitFor below it would never be reached.
        val started = System.nanoTime()
        val out = ProcessRunner.run(2, TimeUnit.SECONDS, *neverExits)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(out, "a process that overran must not report output")
        assertTrue(elapsedMs < 15_000, "expected the timeout to fire, took ${elapsedMs}ms")
    }

    @Test
    fun `non-zero exit is a failure, not output`() {
        assertNull(ProcessRunner.run(30, TimeUnit.SECONDS, *exitsThree))
    }
}
