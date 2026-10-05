package io.openflux.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The identity rule that decides whether a finished run may clear shared state.
 *
 * This exists because of a defect that a comment described correctly and the
 * code did not implement, twice, in two different shapes:
 *
 *   if (synchronized(lock) { this.run === run }) { ...clear... }
 *
 * is an expression - the monitor is released as soon as the condition has been
 * evaluated, so the whole body ran outside it. Splitting it into
 *
 *   synchronized(lock) { if (this.run === run) this.run = null }
 *   if (synchronized(lock) { this.run !== run }) return
 *
 * was worse: `run` is non-null, so once nulled the field is trivially `!== run`,
 * and when the nulling did not happen it was already a different run and also
 * `!== run`. Both branches returned and every line after them was unreachable.
 *
 * The decision has to be snapshotted where the state is still held. The two
 * cases below are the ones that have to hold; a rule that passes one and fails
 * the other is exactly what went wrong twice.
 *
 * What is and is not covered here: `Box` is a REPLICA of the shape cleanup()
 * uses, not cleanup() itself - the real one is private, needs an AppContainer
 * and spawns a core process, so it cannot be driven from a unit test. The
 * fourth case is the load-bearing one: it pins the exact expression that
 * shipped, and fails, so the shipped bug is executable here rather than only
 * described in a comment.
 */
class RunIdentityTest {

    private class Box {
        var current: Any? = null

        /** What cleanup() actually does, in the shipped order. */
        fun cleanup(run: Any): Boolean {
            val mine = synchronized(this) {
                if (current === run) {
                    current = null
                    true
                } else {
                    false
                }
            }
            if (!mine) return false
            cleared = true
            return true
        }

        var cleared = false
    }

    @Test
    fun `the current run clears the shared state`() {
        val box = Box()
        val run = Any()
        box.current = run
        assertTrue(box.cleanup(run), "a current run must clear the shared state")
        assertTrue(box.cleared)
        assertEquals(null, box.current)
    }

    @Test
    fun `a stale run clears nothing`() {
        val box = Box()
        val stale = Any()
        val fresh = Any()
        box.current = fresh
        assertFalse(box.cleanup(stale), "a stale run must not clear the shared state")
        assertFalse(box.cleared)
        // and must not drop the current run either
        assertTrue(box.current === fresh)
    }

    @Test
    fun `a second cleanup of the same run clears nothing`() {
        val box = Box()
        val run = Any()
        box.current = run
        assertTrue(box.cleanup(run))
        assertFalse(box.cleanup(run), "the second cleanup found no current run")
    }

    @Test
    fun `the split form this replaces fails both cases`() {
        // The exact shape that shipped. Kept as a test so the reason the code
        // looks the way it does is executable rather than a comment.
        fun splitCleanup(current: Any?, run: Any): Boolean {
            var field = current
            if (field === run) field = null
            if (field !== run) return false
            return true
        }

        val run = Any()
        assertFalse(splitCleanup(run, run), "the split form returns even for the current run")
        assertFalse(splitCleanup(Any(), run), "and for a stale one")
    }
}