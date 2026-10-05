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

    /**
     * The activity is going away.
     *
     * [recreating] is true for a configuration change - a rotation, or a
     * multi-window resize - and the two cases are not the same.
     *
     * A destroyed activity's launchers are NOT gone. registerForActivityResult
     * keeps its launcher in the ActivityResultRegistry, which outlives the
     * activity and replays a pending result to the launcher registered by the
     * recreated one. That is the entire reason to use it instead of
     * startActivityForResult.
     *
     * Completing the slots here anyway threw that away. On a rotation during
     * the VPN consent: onDestroy cleared the slot and completed it with null,
     * so prepareVpn returned false and the screen said "Android не разрешил
     * OpenFlux включить VPN" - while the user was looking at the consent dialog
     * and about to grant it. The granted answer then arrived, found a null slot
     * and was dropped. A permission the user gave was reported as refused, and
     * on the path the owner requires to work.
     *
     * When the activity is really finishing, nothing will answer and the
     * waiters must be released or the caller's coroutine parks forever holding
     * the connection lock.
     */
    @Synchronized
    fun detach(host: MainActivity, recreating: Boolean) {
        if (activity !== host) return
        activity = null
        if (recreating) return
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
        fallback: T,
        slot: () -> CompletableDeferred<T>?,
        store: (CompletableDeferred<T>?) -> Unit,
        launch: (MainActivity) -> Unit,
    ): T? {
        val result = CompletableDeferred<T>()
        // Completed outside the lock below: completing resumes a waiter, and
        // that waiter may come straight back here.
        var displaced: CompletableDeferred<T>? = null
        val host = synchronized(this) {
            val current = activity ?: return fallback
            displaced = slot()
            store(result)
            current
        }
        // A second request of the SAME kind while the first is outstanding -
        // "Сканировать QR" and "QR из файла" are plain buttons with no in-flight
        // guard, so a double tap reaches here. The old slot was simply
        // overwritten, and nothing else held a reference to it, so its
        // `await()` never returned and that coroutine stayed parked for the
        // rest of the dialog's life. Released now rather than abandoned.
        displaced?.complete(fallback)
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
