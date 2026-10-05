package io.openflux.android

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An activity that goes away must release every waiter, and must not release
 * the wrong one.
 *
 * The bridge kept its slots in plain fields, written by the caller on whatever
 * dispatcher it ran on and read on the main thread, so visibility was not
 * guaranteed and nothing ever cleared them: a result arriving after the
 * activity was gone was written into a deferred nobody was waiting on.
 *
 * The worse case was a gap between "read the activity" and "store the deferred".
 * A detach landing in that gap cleared the previous slot - or none - and the
 * deferred just stored had nobody left to answer it. The caller's coroutine
 * stayed parked forever holding the connection lock, so the connect button did
 * nothing for the rest of the process's life. The window was a few instructions
 * wide; the consequence was the whole feature.
 *
 * The bridge is reproduced here with the same slot discipline rather than
 * mocked, because the defect lives in the ordering of two statements.
 */
class ActivityBridgeSlotTest {

    /** The same shape as the production bridge, minus MainActivity. */
    private class Bridge {
        private var vpn: CompletableDeferred<Boolean?>? = null
        private var pick: CompletableDeferred<Uri?>? = null
        private var scan: CompletableDeferred<String?>? = null
        var attached = false
            private set

        @Synchronized fun attach() {
            attached = true
        }

        @Synchronized fun detach(recreating: Boolean = false) {
            attached = false
            // A configuration change is not a death: the ActivityResultRegistry
            // outlives the activity and replays the pending result to the
            // launcher the recreated one registers. Releasing here reported a
            // VPN consent the user was about to grant as refused.
            if (recreating) return
            val a = vpn
            val b = pick
            val c = scan
            vpn = null
            pick = null
            scan = null
            a?.complete(null)
            b?.complete(null)
            c?.complete(null)
        }

        @Synchronized fun beginVpn(): Boolean {
            if (!attached) return false
            vpn = CompletableDeferred()
            return true
        }

        @Synchronized fun beginPick(): Boolean {
            if (!attached) return false
            pick = CompletableDeferred()
            return true
        }

        fun vpnSlot(): CompletableDeferred<Boolean?>? = vpn
        fun pickSlot(): CompletableDeferred<Uri?>? = pick

        fun onVpnConsent(granted: Boolean) {
            answer<Boolean?>({ vpn }) { vpn = null }?.complete(granted)
        }

        private fun <T> answer(slot: () -> CompletableDeferred<T>?, clear: () -> Unit): CompletableDeferred<T>? =
            synchronized(this) { slot()?.also { clear() } }
    }

    @Test
    fun `detach releases a waiter rather than leaving it parked`() = runBlocking {
        val bridge = Bridge()
        bridge.attach()
        assertTrue(bridge.beginVpn())

        val waiter = bridge.vpnSlot()
        bridge.detach()

        // The whole point: this must return, and must return "no answer".
        val answered = withTimeoutOrNull(2_000) { waiter?.await() }
        assertEquals(null, answered, "the waiter was not released by detach")
    }

    @Test
    fun `a detached bridge refuses new requests instead of parking them`() = runBlocking {
        val bridge = Bridge()
        bridge.attach()
        bridge.detach()
        assertTrue(!bridge.beginVpn(), "beginVpn must report refusal when detached")
        assertNull(bridge.vpnSlot(), "no slot may be registered when detached")
    }

    @Test
    fun `a VPN result does not complete the picker's wait`() {
        val bridge = Bridge()
        bridge.attach()
        assertTrue(bridge.beginVpn())
        assertTrue(bridge.beginPick())

        val vpnWaiter = bridge.vpnSlot()
        val pickWaiter = bridge.pickSlot()

        bridge.onVpnConsent(granted = true)

        assertTrue(vpnWaiter!!.isCompleted, "the VPN wait was not answered")
        assertTrue(!pickWaiter!!.isCompleted, "the picker was completed by a VPN result")
    }

    @Test
    fun `the slot is cleared once answered, so a late duplicate changes nothing`() {
        val bridge = Bridge()
        bridge.attach()
        bridge.beginVpn()
        val waiter = bridge.vpnSlot()

        bridge.onVpnConsent(granted = true)
        assertTrue(waiter!!.isCompleted)

        // The user rotates the screen: a duplicate delivery arrives afterwards.
        bridge.onVpnConsent(granted = false)
        assertEquals(true, waiter.getCompleted(), "a late duplicate overwrote the answer")
    }

    @Test
    fun `a rotation keeps the waiter so the granted consent still lands`() {
        val bridge = Bridge()
        bridge.attach()
        assertTrue(bridge.beginVpn())
        val waiter = bridge.vpnSlot()

        // Rotation: destroyed and recreated within the same second.
        bridge.detach(recreating = true)
        assertTrue(!waiter!!.isCompleted, "the waiter was released by a rotation")
        assertTrue(bridge.vpnSlot() === waiter, "the slot was dropped by a rotation")

        // The recreated activity's launcher delivers the result the user chose.
        bridge.attach()
        bridge.onVpnConsent(granted = true)

        assertTrue(waiter.isCompleted, "the granted consent never arrived")
        assertEquals(true, waiter.getCompleted(), "a consent the user granted was reported as refused")
    }

    @Test
    fun `an activity that really finishes still releases the waiter`() = runBlocking {
        val bridge = Bridge()
        bridge.attach()
        assertTrue(bridge.beginVpn())
        val waiter = bridge.vpnSlot()

        // Leaving the app: nothing will ever answer, and a parked waiter holds
        // the connection lock for the rest of the process's life.
        bridge.detach(recreating = false)

        val value = withTimeoutOrNull(1_000) { waiter?.await() }
        assertTrue(waiter!!.isCompleted, "a finishing activity left the waiter parked")
        assertNull(value, "a finishing activity must not report a granted consent")
        assertNull(bridge.vpnSlot(), "the slot must be cleared")
    }
}