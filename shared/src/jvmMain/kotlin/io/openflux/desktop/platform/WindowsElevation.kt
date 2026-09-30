package io.openflux.desktop.platform

import java.util.concurrent.TimeUnit

/**
 * Administrator rights, which the full tunnel needs to create its Wintun
 * adapter and routes. OpenFlux asks for them only when that mode is used:
 * it restarts itself through UAC.
 */
object WindowsElevation {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Whether this process runs elevated; checked once.
     *
     * The check spawns `net session` with a 10 second timeout, and it used to
     * run on whichever thread touched the property first - which, because two
     * screens read it to decide whether to show a banner, is the UI thread.
     * The window froze for up to ten seconds on first draw. It is warmed on a
     * background thread by [warm] instead.
     */
    val elevated: Boolean by lazy {
        // "net session" needs an elevated token; it is the usual check without JNA.
        windows && ProcessRunner.run(10, TimeUnit.SECONDS, "net", "session") != null
    }

    /**
     * Starts the check without waiting for it.
     *
     * The value is still read synchronously by callers that need it now, but
     * by the time a screen is showing it the result is normally already in, so
     * the cost lands off the UI thread.
     */
    fun warm() {
        if (!windows) return
        Thread({ elevated }, "openflux-elevation-check").apply { isDaemon = true }.start()
    }

    /**
     * Starts this program again as administrator (the user confirms in UAC).
     * Returns false when it could not be started or UAC was declined; on
     * true the caller should exit so the new copy can take over.
     */
    fun restartElevated(extraArg: String): Boolean {
        if (!windows) return false
        val info = ProcessHandle.current().info()
        val command = info.command().orElse(null) ?: return false
        val args = info.arguments().orElse(emptyArray()).toList().filter { it != extraArg } + extraArg
        fun quote(s: String) = "'" + s.replace("'", "''") + "'"
        val list = if (args.isEmpty()) "" else " -ArgumentList @(" + args.joinToString(",") { quote(it) } + ")"
        val script = "Start-Process -FilePath ${quote(command)}$list -Verb RunAs"
        // Start-Process fails when UAC is declined, which is a non-zero exit.
        return ProcessRunner.run(
            60, TimeUnit.SECONDS,
            "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script,
        ) != null
    }
}
