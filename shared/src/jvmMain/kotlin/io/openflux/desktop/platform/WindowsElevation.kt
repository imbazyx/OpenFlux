package io.openflux.desktop.platform

import java.util.concurrent.TimeUnit

/**
 * Administrator rights, which the full tunnel needs to create its Wintun
 * adapter and routes. OpenFlux asks for them only when that mode is used:
 * it restarts itself through UAC.
 */
object WindowsElevation {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    /** Whether this process runs elevated; checked once. */
    val elevated: Boolean by lazy {
        // "net session" needs an elevated token; it is the usual check without JNA.
        windows && ProcessRunner.run(10, TimeUnit.SECONDS, "net", "session") != null
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
