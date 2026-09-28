package io.openflux.android.core

import android.content.Context
import io.openflux.android.platform.AndroidPlatformServices

/**
 * Which applications go through the tunnel. [CoreService.establish] reads it
 * every time the interface is built, so a change takes effect on the next
 * connection and never mid-flight.
 *
 * The rule is an allow list of the packages stored here, so anything not named
 * - including this app, whose control channel must stay outside the tunnel -
 * keeps using the network as if the VPN were not running. See the note on
 * CoreService.applyAppSelection.
 */
object AppSelection {
    private const val PREFS = "openflux-app-selection"
    private const val KEY_ONLY = "only-selected"
    private const val KEY_PACKAGES = "packages"

    /** True when the tunnel should carry only the packages in [packages]. */
    fun onlySelected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLY, false)

    fun packages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun save(context: Context, only: Boolean, packages: Set<String>) {
        prefs(context).edit()
            .putBoolean(KEY_ONLY, only)
            // A copy: SharedPreferences keeps this reference, and the caller's
            // set keeps changing while the picker is open.
            .putStringSet(KEY_PACKAGES, packages.toSet())
            .apply()
        // Anyone showing the rule (the settings screen) keys its recomposition on
        // this, so a write from anywhere is picked up - the picker and the
        // service's own degrade path both land here.
        AndroidPlatformServices.instance?.appSelectionChanged()
    }

    /**
     * The package names to put on the allow list. Blank entries are dropped.
     *
     * An empty result does NOT mean the tunnel carries everything: an allow
     * list that is never built is read by Android as "no list", which does mean
     * exactly that. The caller therefore has to check this and fall back to a
     * full tunnel deliberately, which is what CoreService.applyAppSelection
     * does. Names for apps that are no longer installed are resolved and
     * dropped there too, since only it can see what the system still has.
     */
    fun selected(context: Context): List<String> =
        packages(context).filter { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
