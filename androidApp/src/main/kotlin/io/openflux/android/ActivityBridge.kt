package io.openflux.android

import android.content.Context
import android.net.Uri
import android.net.VpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What only an activity can do (system dialogs and pickers), for services
 * that outlive it. Each request waits for its result; without an activity
 * on screen it fails at once.
 */
class ActivityBridge {
    @Volatile private var activity: MainActivity? = null

    // One slot per kind, all guarded by this. They used to be plain fields read
    // on the main thread and written from whatever dispatcher the caller ran on,
    // so visibility was never guaranteed - and nothing ever cleared them, so a
    // result that arrived late was written into a deferred nobody was waiting
    // on any more. One shared list was the obvious fix and was wrong: with a
    // VPN consent and a file picker outstanding at once, a VPN result would
    // complete the picker's wait and the file picker's would complete the VPN's.
    private var vpn: CompletableDeferred<Boolean?>? = null
    private var pick: CompletableDeferred<Uri?>? = null
    private var scan: CompletableDeferred<String?>? = null

    @Synchronized
    fun attach(host: MainActivity) {
        activity = host
    }

    @Synchronized
    fun detach(host: MainActivity) {
        if (activity !== host) return
        activity = null
        // The activity's launchers die with it; nobody will answer these.
        // Completed outside the lock: completing resumes the waiters, and a
        // waiter may well come back here.
        val strandedVpn = vpn
        val strandedPick = pick
        val strandedScan = scan
        vpn = null
        pick = null
        scan = null
        strandedVpn?.complete(null)
        strandedPick?.complete(null)
        strandedScan?.complete(null)
    }

    /** Android's consent to run a VPN; asks the user the first time. */
    suspend fun prepareVpn(context: Context): Boolean {
        val intent = VpnService.prepare(context) ?: return true
        return awaitResult<Boolean?>(null, { vpn }, { vpn = it }) { it.launchVpnConsent(intent) } ?: false
    }

    suspend fun pickDocument(types: Array<String>): Uri? =
        awaitResult<Uri?>(null, { pick }, { pick = it }) { it.launchPicker(types) }

    suspend fun scanQr(): String? =
        awaitResult<String?>(null, { scan }, { scan = it }) { it.launchScanner() }

    /**
     * Registers a result slot and launches the thing that will fill it, or
     * answers [fallback] at once when there is no activity.
     *
     * The check and the registration are one synchronized step on purpose. They
     * used to be two statements: read the activity, then store the deferred.
     * An activity that finished in between detached first, found the previous
     * deferred - or none - and cleared only that one. The deferred just stored
     * then had nobody left to answer it, so `await()` never returned and the
     * caller's coroutine stayed parked forever holding the connection lock: the
     * connect button stopped working for the rest of the process's life. The
     * window is a few instructions wide, and the cost of landing in it is the
     * whole feature.
     */
    private suspend fun <T> awaitResult(
        fallback: T?,
        slot: () -> CompletableDeferred<T>?,
        store: (CompletableDeferred<T>?) -> Unit,
        launch: (MainActivity) -> Unit,
    ): T? {
        val result = CompletableDeferred<T>()
        val host = synchronized(this) {
            val current = activity ?: return fallback
            store(result)
            current
        }
        withContext(Dispatchers.Main) { launch(host) }
        return result.await()
    }

    /** Asks for the notification permission (Android 13+) without waiting. */
    fun requestNotifications() {
        activity?.let { host -> host.runOnUiThread { host.requestNotificationPermission() } }
    }

    internal fun onVpnConsent(granted: Boolean) { answer<Boolean?>({ vpn }) { vpn = null }?.complete(granted) }

    internal fun onPicked(uri: Uri?) {
        answer<Uri?>({ pick }) { pick = null }?.complete(uri)
    }

    internal fun onScanned(text: String?) {
        answer<String?>({ scan }) { scan = null }?.complete(text)
    }

    private fun <T> answer(
        slot: () -> CompletableDeferred<T>?,
        clear: () -> Unit,
    ): CompletableDeferred<T>? = synchronized(this) {
        slot()?.also { clear() }
    }
}
