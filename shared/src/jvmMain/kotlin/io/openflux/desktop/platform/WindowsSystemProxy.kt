package io.openflux.desktop.platform

import io.openflux.desktop.model.SavedSystemProxy
import java.util.concurrent.TimeUnit

/**
 * The per-user WinINet proxy (Settings → Network → Proxy), which browsers
 * and most Windows programs follow. OpenFlux points it at the core's HTTP
 * proxy while connected and puts the previous values back afterwards.
 *
 * It is set through InternetSetOption(INTERNET_OPTION_PER_CONNECTION_OPTION),
 * like v2rayN and clash do: WinINet and the Settings page read the binary
 * DefaultConnectionSettings, so the old ProxyEnable/ProxyServer registry
 * values alone did not switch the proxy. The registry values are written
 * too, for programs that still read them.
 */
object WindowsSystemProxy {
    private const val KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings"""
    private const val BYPASS = "<local>;localhost;127.*;10.*;192.168.*"

    fun read(): SavedSystemProxy = SavedSystemProxy(
        enabled = query("ProxyEnable")?.let { it.removePrefix("0x").toIntOrNull(16) == 1 } ?: false,
        server = query("ProxyServer").orEmpty(),
        override = query("ProxyOverride").orEmpty(),
    )

    fun enable(address: String) {
        set("ProxyServer", "REG_SZ", address)
        set("ProxyOverride", "REG_SZ", BYPASS)
        set("ProxyEnable", "REG_DWORD", "1")
        apply(PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY, address, BYPASS)
    }

    fun restore(saved: SavedSystemProxy) {
        if (saved.server.isEmpty()) delete("ProxyServer") else set("ProxyServer", "REG_SZ", saved.server)
        if (saved.override.isEmpty()) delete("ProxyOverride") else set("ProxyOverride", "REG_SZ", saved.override)
        set("ProxyEnable", "REG_DWORD", if (saved.enabled) "1" else "0")
        apply(if (saved.enabled) PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY else PROXY_TYPE_DIRECT, saved.server, saved.override)
    }

    /** Whether the system proxy currently points at [address]. */
    fun pointsAt(address: String): Boolean {
        val current = read()
        return current.enabled && current.server == address
    }

    private fun query(name: String): String? {
        val out = run("reg", "query", KEY, "/v", name) ?: return null
        val line = out.lines().firstOrNull { it.trim().startsWith(name) } ?: return null
        val parts = line.trim().split(Regex("\\s{2,}|\\t"), limit = 3)
        return parts.getOrNull(2)?.trim()
    }

    private fun set(name: String, type: String, value: String) {
        run("reg", "add", KEY, "/v", name, "/t", type, "/d", value, "/f")
            ?: throw IllegalStateException("Не удалось изменить системный прокси ($name)")
    }

    // Absent is the desired end state, not a failure.
    //
    // `reg delete` exits non-zero when the value does not exist, so throwing on
    // every non-zero exit scored "already gone" as an error. Worse, this is
    // the first statement restore() runs for a user who had no proxy, so the
    // throw skipped the two lines that actually matter - ProxyEnable=0 and
    // apply() - leaving Windows still enabled and pointed at a dead 127.0.0.1,
    // with the saved record unable to ever succeed on retry. ProcessRunner
    // cannot tell the two cases apart because it returns null and discards the
    // output on any non-zero exit, so ask the registry whether the value is
    // there before trying to remove it.
    //
    // The limit of that: query() also returns null when `reg query` itself
    // failed - a timeout, a refused access - so a delete that would have failed
    // is now skipped silently. That is the right way round here, because the
    // line that decides whether Windows is left on a dead address is the
    // set("ProxyEnable", "0") that follows, and a leftover unused ProxyServer
    // string harms nothing once ProxyEnable is 0. The failure that matters -
    // that set() throwing - still propagates, still keeps the saved record,
    // and still gets retried.
    private fun delete(name: String) {
        if (query(name) == null) return
        run("reg", "delete", KEY, "/v", name, "/f")
            ?: throw IllegalStateException("Не удалось убрать системный прокси ($name)")
    }

    /**
     * Sets the LAN connection's proxy the way WinINet stores it, then tells
     * running programs (INTERNET_OPTION_SETTINGS_CHANGED, REFRESH).
     */
    private fun apply(flags: Int, server: String, bypass: String) {
        val script = APPLY_TYPE + "\nif (-not [OpenFluxProxy]::Set($flags, ${ps(server)}, ${ps(bypass)})) { exit 3 }"
        // -EncodedCommand: the script has quotes and newlines.
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            ?: throw IllegalStateException("Windows не принял настройки прокси")
    }

    private fun ps(value: String) = "'" + value.replace("'", "''") + "'"

    private const val PROXY_TYPE_DIRECT = 1
    private const val PROXY_TYPE_PROXY = 2

    /** INTERNET_PER_CONN_OPTION_LISTW for the default (LAN) connection. */
    private val APPLY_TYPE = """
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class OpenFluxProxy {
    [StructLayout(LayoutKind.Sequential)]
    struct Option { public int dwOption; public IntPtr value; }
    [StructLayout(LayoutKind.Sequential)]
    struct OptionList { public int dwSize; public IntPtr pszConnection; public int dwOptionCount; public int dwOptionError; public IntPtr pOptions; }
    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool InternetSetOption(IntPtr h, int option, IntPtr buffer, int length);
    public static bool Set(int flags, string server, string bypass) {
        Option[] options = new Option[3];
        options[0].dwOption = 1; options[0].value = new IntPtr(flags);
        options[1].dwOption = 2; options[1].value = Marshal.StringToHGlobalUni(server ?? "");
        options[2].dwOption = 3; options[2].value = Marshal.StringToHGlobalUni(bypass ?? "");
        int size = Marshal.SizeOf(typeof(Option));
        IntPtr buffer = Marshal.AllocCoTaskMem(size * options.Length);
        for (int i = 0; i < options.Length; i++) Marshal.StructureToPtr(options[i], new IntPtr(buffer.ToInt64() + i * size), false);
        OptionList list = new OptionList();
        list.dwSize = Marshal.SizeOf(typeof(OptionList));
        list.pszConnection = IntPtr.Zero;
        list.dwOptionCount = options.Length;
        list.pOptions = buffer;
        IntPtr pointer = Marshal.AllocCoTaskMem(list.dwSize);
        Marshal.StructureToPtr(list, pointer, false);
        bool ok = InternetSetOption(IntPtr.Zero, 75, pointer, list.dwSize);
        InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0);
        Marshal.FreeCoTaskMem(pointer);
        Marshal.FreeCoTaskMem(buffer);
        Marshal.FreeHGlobal(options[1].value);
        Marshal.FreeHGlobal(options[2].value);
        return ok;
    }
}
'@
""".trimIndent()

    private fun run(vararg command: String): String? =
        ProcessRunner.run(15, TimeUnit.SECONDS, *command)
}
