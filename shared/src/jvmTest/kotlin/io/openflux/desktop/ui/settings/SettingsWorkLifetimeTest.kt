package io.openflux.desktop.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Leaving the settings screen must not kill an update that is still running.
 *
 * The install used to run on the composition's own scope, which is cancelled
 * the moment AboutSettings leaves the composition - switching to any other tab
 * does that. `installing` had already been set and belongs to the model, so it
 * survived, while the coroutine that would have cleared it was gone: the button
 * read "Устанавливаю…" and stayed disabled for the rest of the process's life,
 * and the half-finished download was lost.
 *
 * So the scope deliberately outlives the screen. That left the other half
 * unfixed: nothing ever cancelled it. The first version of this test
 * reimplemented the guard locally and proved nothing; this one calls
 * [workShouldBeCollected], which is what the model actually uses.
 */
class SettingsWorkLifetimeTest {

    @Test
    fun `a live install is never collected`() {
        assertFalse(workShouldBeCollected(gone = true, installing = true, checkingRelease = false))
    }

    @Test
    fun `a release check in flight is never collected`() {
        assertFalse(workShouldBeCollected(gone = true, installing = false, checkingRelease = true))
    }

    @Test
    fun `merely navigating away does not collect the scope`() {
        // This is the regression the whole design exists to avoid: the model
        // outlives the composition, so a tab switch must leave the install
        // alone.
        assertFalse(workShouldBeCollected(gone = false, installing = true, checkingRelease = false))
        assertFalse(workShouldBeCollected(gone = false, installing = false, checkingRelease = false))
    }

    @Test
    fun `an idle finished screen is collected`() {
        assertTrue(workShouldBeCollected(gone = true, installing = false, checkingRelease = false))
    }

    @Test
    fun `an install that finishes after the screen is gone is then collected`() {
        // The finally-block clears `installing` first, so this is the state the
        // model reaches a moment after onDispose declined to cancel.
        assertFalse(workShouldBeCollected(gone = true, installing = true, checkingRelease = false))
        assertTrue(workShouldBeCollected(gone = true, installing = false, checkingRelease = false))
    }

    @Test
    fun `an install that survives disposal really does reach its finally`() {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        var gone = false
        work.launch {
            try {
                delay(250)
            } finally {
                finished.set(true)
                if (workShouldBeCollected(gone, installing = false, checkingRelease = false)) {
                    work.coroutineContext.cancelChildren()
                }
            }
        }
        Thread.sleep(60)
        // Screen goes away mid-install: onDispose sets gone but must not cancel.
        gone = true
        if (workShouldBeCollected(gone, installing = true, checkingRelease = false)) {
            work.coroutineContext.cancelChildren()
        }
        Thread.sleep(600)
        assertTrue(finished.get(), "the install was cancelled by the screen going away")
    }
}