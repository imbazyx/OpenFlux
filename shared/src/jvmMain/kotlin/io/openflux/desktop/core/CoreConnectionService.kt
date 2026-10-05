package io.openflux.desktop.core

import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.data.restrictToOwner
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.ui.BrowserPage
import io.openflux.desktop.platform.WindowsElevation
import io.openflux.desktop.web.BrowserLog
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.CoreConfig
import io.openflux.desktop.model.CorePaths
import io.openflux.desktop.model.CoreSource
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.isActive
import io.openflux.desktop.platform.WindowsSystemProxy
import io.openflux.desktop.service.ConnectionService
import io.openflux.desktop.service.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the OpenFlux core as a child process for one profile at a time and
 * turns what it reports (IPC status, captcha requests, log lines) into the
 * UI's state flows.
 */
class CoreConnectionService(
    private val settings: SettingsRepository,
    private val binary: CoreBinary,
) : ConnectionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _traffic = MutableStateFlow(TrafficStats())
    override val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()
    private val _exitAddress = MutableStateFlow<ExitAddress>(ExitAddress.Unknown)
    override val exitAddress: StateFlow<ExitAddress> = _exitAddress.asStateFlow()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()
    private val _captcha = MutableStateFlow<CaptchaPrompt?>(null)
    override val captcha: StateFlow<CaptchaPrompt?> = _captcha.asStateFlow()
    private val _exitShareLink = MutableStateFlow<String?>(null)
    override val exitShareLink: StateFlow<String?> = _exitShareLink.asStateFlow()
    private val _socksAddress = MutableStateFlow<String?>(null)
    override val socksAddress: StateFlow<String?> = _socksAddress.asStateFlow()

    private val lineIds = AtomicLong()
    private val lock = Any()

    /**
     * Guards the whole stop-then-start swap in [connect].
     *
     * Separate from [lock] because [stopRun] suspends, and Kotlin will not let
     * a suspension point live inside a monitor block. `lock` still guards the
     * individual field accesses; this one serialises the sequence.
     */
    private val swapMutex = Mutex()
    private var run: Run? = null
    private val captchaBrowser = CaptchaBrowser()
    override val captchaPage: StateFlow<BrowserPage?> = captchaBrowser.page
    private var pendingCaptcha: IpcCookiesRequest? = null

    /** One started core: its process, files and the settings it began with. */
    private class Run(
        val profile: Profile,
        val settings: AppSettings,
        val process: Process,
        val files: List<File>,
        val httpProxy: String?,
        val usesIpc: Boolean,
        val jobs: MutableList<Job> = mutableListOf(),
    ) {
        @Volatile var ipc: CoreIpc? = null
        @Volatile var stopping = false
        @Volatile var lastProblem: String? = null
        @Volatile var connectedSince: Long = 0
        /** The core printed its start banner (a fallback sign of life). */
        @Volatile var bannerSeen = false
        /** The IPC socket never answered; status comes from the log instead. */
        @Volatile var ipcUnavailable = false
    }

    init {
        // A killed app leaves its run files behind, the key among them. One
        // instance runs at a time (main holds a lock), so none are in use.
        AppDirs.runtime.listFiles()?.filter { it.name.startsWith("key-") || it.name.startsWith("profile-") }
            ?.forEach { runCatching { it.delete() } }
        // A crash while the system proxy pointed at OpenFlux leaves Windows
        // without Internet; put the saved values back on the next start.
        BrowserLog.listener = { text, problem -> log(if (problem) LogLevel.Warning else LogLevel.Info, text) }
        settings.settings.value.savedSystemProxy?.let { saved ->
            if (isWindows) {
                // Same rule as restoreSystemProxy: a failed restore keeps its
                // record, so this is retried on the next launch instead of
                // being forgotten - and the failure is written down, because
                // the user cannot otherwise tell a restored proxy from a broken
                // one.
                runCatching { WindowsSystemProxy.restore(saved) }
                    .onSuccess { log(LogLevel.Info, "Системный прокси Windows восстановлен при запуске") }
                    .onFailure {
                        log(LogLevel.Error, "Не удалось вернуть системный прокси при запуске: ${it.message}")
                    }
                    .onSuccess { settings.update { it.copy(savedSystemProxy = null) } }
            } else {
                settings.update { it.copy(savedSystemProxy = null) }
            }
        }
        scope.launch {
            settings.settings.distinctUntilChangedBy { it.systemProxy }.collect { applySystemProxy() }
        }
    }

    override fun connect(profile: Profile) {
        // The whole swap is inside the lock, not just the read of `run`.
        //
        // It used to read the current run under the lock, release it, stop that
        // run and start a new one - so two coroutines on Dispatchers.IO could
        // both read the same run, both stop it and both start, with the second
        // assignment overwriting the first. The first process was then never
        // killed and never tracked: it kept its port, and shutdown() would not
        // kill it either. The user saw "the port is already in use by another
        // program", or an orphaned core until reboot. The full-traffic toggle
        // calls this on purpose while a connection is live, so this was a
        // double-click away, not a theoretical interleaving.
        scope.launch {
            // A coroutine Mutex, not synchronized: stopRun suspends (waitFor,
            // delay), and Kotlin forbids a suspension point inside a monitor
            // block, which is why this used to read `run` under the lock and
            // then release it before doing anything - leaving the swap itself
            // unguarded. Two coroutines could read the same run, both stop it
            // and both start, the second assignment overwriting the first, and
            // the first process was then never killed and never tracked: it
            // kept its port, and shutdown() would not kill it either. The user
            // saw "the port is already in use by another program", or an
            // orphaned core until reboot. The full-traffic toggle calls this
            // on purpose while a connection is live, so a double click was
            // enough - not a theoretical interleaving.
            swapMutex.withLock {
                run?.let { stopRun(it, restart = true) }
                start(profile)
            }
        }
    }

    override fun disconnect() {
        scope.launch { synchronized(lock) { run }?.let { stopRun(it, restart = false) } }
    }

    private fun start(profile: Profile) {
        val current = settings.settings.value
        _exitShareLink.value = null
        _exitAddress.value = ExitAddress.Unknown
        _traffic.value = TrafficStats()
        _captcha.value = null
        val files = mutableListOf<File>()
        try {
            val core = binary.resolve(current) ?: throw IllegalStateException(
                if (current.coreSource == CoreSource.Custom) "Файл ядра не найден: ${current.customCorePath}"
                else "В этой сборке нет встроенного ядра: установите релиз с GitHub, соберите приложение с Go " +
                    "(./gradlew соберёт ядро сам) или укажите файл ядра в настройках",
            )
            if (current.fullTunnel && current.mode == ConnectionMode.Client) checkFullTunnel(core)
            val runtime = AppDirs.runtime
            val tag = profile.id.take(8)
            val keyFile = if (profile.secret.isNotEmpty()) File(runtime, "key-$tag").also {
                it.writeText(profile.secret)
                restrictToOwner(it)
                files += it
            } else null
            val confFile = File(runtime, "profile-$tag.conf")
            // AF_UNIX sockets do not work under every folder on Windows
            // (AppData\Roaming and \Local fail with EINVAL); the temp folder does.
            val ipcSocket = if (profile.session) {
                val dir = Files.createTempDirectory("openflux-ipc-").toFile()
                files += dir
                File(dir, "core.sock").also { files += it }
            } else null
            val paths = CorePaths(
                keyFile = keyFile?.absolutePath,
                confFile = confFile.absolutePath,
                cookieStore = File(AppDirs.config, "cookies/$tag.json").also { it.parentFile.mkdirs() }.absolutePath,
                ipcSocket = ipcSocket?.absolutePath,
            )
            val launch = CoreConfig.build(profile, current, paths)
            if (launch.conf != null) {
                confFile.writeText(launch.conf)
                restrictToOwner(confFile)
                files += confFile
            }
            log(LogLevel.Info, "Запуск ядра: ${profile.name} (${current.mode.label})")
            val process = ProcessBuilder(listOf(core.absolutePath) + launch.arguments)
                .directory(AppDirs.runtime)
                .redirectErrorStream(true)
                .start()
            val newRun = Run(profile, current, process, files, launch.httpProxyAddress, launch.usesIpc)
            synchronized(lock) { run = newRun }
            _socksAddress.value = launch.socksAddress
            _state.value = ConnectionState.Connecting(profile, current.mode, System.currentTimeMillis())
            newRun.jobs += scope.launch { readOutput(newRun) }
            newRun.jobs += scope.launch { awaitExit(newRun) }
            if (ipcSocket != null) newRun.jobs += scope.launch { readIpc(newRun, ipcSocket) }
        } catch (e: Exception) {
            files.sortedBy { it.isDirectory }.forEach { it.delete() }
            val message = e.message ?: "Не удалось запустить ядро"
            log(LogLevel.Error, message)
            _state.value = ConnectionState.Failed(profile, message)
        }
    }

    /** What the core's Wintun client needs, said before it fails on its own. */
    private fun checkFullTunnel(core: File) {
        check(isWindows) { "Режим «Весь трафик» пока есть только в Windows" }
        check(WindowsElevation.elevated) {
            "Режиму «Весь трафик» нужны права администратора: перезапустите OpenFlux от имени администратора (кнопка на главной)"
        }
        check(File(core.parentFile, "wintun.dll").isFile) {
            "Рядом с ядром нет wintun.dll (${core.parentFile}): он нужен для режима «Весь трафик», см. scripts/build-core.sh"
        }
    }

    private fun readOutput(run: Run) {
        run.process.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trimEnd()
                if (line.isEmpty()) continue
                val level = levelOf(line)
                val shareLink = SHARE_LINK.find(line)?.value
                if (shareLink != null) _exitShareLink.value = shareLink
                // The share line carries the node's encryption key, and every
                // line read here goes into the app's log pane. The core has to
                // print it - that is where the app gets it from - but it must
                // not be archived: [shareLink] is the value, kept for the UI.
                log(level, if (shareLink != null) line.substringBefore(shareLink) + "<share link hidden>" else line)
                friendlyProblem(line)?.let { run.lastProblem = it }
                SHARE_LINK.find(line)?.let { _exitShareLink.value = it.value }
                // Readiness, and it is announced differently per inbound. The
                // TUN path has no SOCKS5 listener: it prints "Tunnel active"
                // once the default route is inside the tunnel. The legacy path
                // prints "Running as CLIENT (SOCKS5 ...)". Watching only the
                // latter left the window on "Подключение" for ever with a
                // working tunnel behind it - the traffic was already going
                // out through the node, and nothing said so.
                if (announcesReady(line)) {
                    run.bannerSeen = true
                    // "Tunnel active" is the strongest signal there is, and it
                    // is worth acting on even when IPC is up: the status feed
                    // may never carry a connected=true message.
                    if (!run.usesIpc || run.ipcUnavailable || line.contains("Tunnel active")) markConnected(run)
                }
            }
        }
    }

    private fun readIpc(run: Run, socket: File) {
        var lastIn = 0L
        var lastOut = 0L
        var lastAt = 0L
        val deadline = System.currentTimeMillis() + IPC_WAIT_MS
        while (run.process.isAlive && !run.stopping) {
            val ipc = runCatching { CoreIpc.connect(socket.toPath()) }.getOrNull()
            if (ipc == null) {
                if (!run.ipcUnavailable && System.currentTimeMillis() > deadline) {
                    run.ipcUnavailable = true
                    log(LogLevel.Warning, "Нет связи с ядром по IPC: статистика и проверки Яндекса недоступны, состояние берётся из журнала")
                    if (run.bannerSeen) markConnected(run)
                }
                Thread.sleep(150)
                continue
            }
            if (run.ipcUnavailable) {
                run.ipcUnavailable = false
                log(LogLevel.Info, "Связь с ядром по IPC восстановлена")
            }
            run.ipc = ipc
            try {
                ipc.readMessages { message ->
                    when (message) {
                        is IpcMessage.Status -> {
                            val status = message.status
                            val now = System.currentTimeMillis()
                            val seconds = if (lastAt == 0L) 1.0 else ((now - lastAt) / 1000.0).coerceAtLeast(0.2)
                            val up = if (lastAt == 0L) 0 else ((status.bytesOut - lastOut) / seconds).toLong().coerceAtLeast(0)
                            val down = if (lastAt == 0L) 0 else ((status.bytesIn - lastIn) / seconds).toLong().coerceAtLeast(0)
                            lastIn = status.bytesIn; lastOut = status.bytesOut; lastAt = now
                            _traffic.value = TrafficStats(up, down, status.bytesOut, status.bytesIn, status.active, live = true)
                            if (run.settings.mode == ConnectionMode.Client) {
                                if (status.connected) markConnected(run) else markReconnecting(run)
                            }
                        }
                        is IpcMessage.Cookies -> onCaptchaRequest(message.request)
                    }
                }
            } catch (e: Exception) {
                if (run.process.isAlive && !run.stopping) log(LogLevel.Warning, "IPC ядра прервался: ${e.message}")
            } finally {
                ipc.close()
                run.ipc = null
            }
        }
    }

    private fun awaitExit(run: Run) {
        val code = run.process.waitFor()
        cleanup(run)
        if (run.stopping) return
        val message = run.lastProblem ?: "Ядро остановилось (код $code)"
        log(LogLevel.Error, message)
        _state.value = ConnectionState.Failed(run.profile, message)
    }

    private fun markConnected(run: Run) {
        if (synchronized(lock) { this.run } !== run || run.stopping) return
        val first = run.connectedSince == 0L
        if (first) run.connectedSince = System.currentTimeMillis()
        val current = _state.value
        if (current !is ConnectionState.Connected) {
            _state.value = ConnectionState.Connected(run.profile, run.settings.mode, run.connectedSince)
            if (first) log(LogLevel.Success, if (run.settings.mode == ConnectionMode.Exit) "Нода запущена" else "Подключено к ноде")
            applySystemProxy()
            if (run.settings.mode == ConnectionMode.Client) refreshExitAddress()
        }
    }

    private fun markReconnecting(run: Run) {
        if (synchronized(lock) { this.run } !== run || run.stopping) return
        if (_state.value is ConnectionState.Connected) {
            _state.value = ConnectionState.Reconnecting(run.profile, run.settings.mode, run.connectedSince)
            log(LogLevel.Warning, "Связь с нодой потеряна, ядро переподключается")
        }
    }

    private suspend fun stopRun(run: Run, restart: Boolean) {
        run.stopping = true
        _state.value = ConnectionState.Disconnecting(run.profile)
        restoreSystemProxy()
        killTree(run.process)
        run.process.waitFor(5, TimeUnit.SECONDS)
        cleanup(run)
        run.jobs.forEach { it.cancel() }
        if (!restart) {
            log(LogLevel.Info, "Отключено")
            _state.value = ConnectionState.Idle
        }
        delay(100)
    }

    private fun cleanup(run: Run) {
        run.ipc?.close()
        // Files first, then the folders that held them.
        run.files.sortedBy { it.isDirectory }.forEach { it.delete() }
        // The shared state is only cleared if this run is still the current one.
        //
        // `run` itself was guarded by identity and nothing else was:
        // awaitExit calls cleanup() before checking run.stopping, and
        // process.waitFor() is a blocking call that coroutine cancellation does
        // not interrupt, so a stale awaitExit from the previous core can reach
        // here after connect() has already started the next one. It then nulled
        // the fresh run's SOCKS address - the home screen falls back to the
        // configured port and shows a wrong or empty address while the screen
        // says "connected" - and on the desktop it restored the Windows system
        // proxy in the middle of a working core.
        //
        // The check and the clearing are ONE step: the decision is snapshotted
        // inside the monitor and the snapshot is what is tested afterwards.
        //
        // `if (synchronized(lock) { this.run === run }) { ... }` does NOT do
        // that. It is an expression: the monitor is released as soon as the
        // condition has been evaluated, and the whole then-branch runs outside
        // it. That was the first version of this fix.
        //
        // The second version was worse and much harder to see. It nulled the
        // field under the monitor and then re-derived the condition from the
        // field:
        //
        //     synchronized(lock) { if (this.run === run) this.run = null }
        //     if (synchronized(lock) { this.run !== run }) return
        //
        // `run` is non-null, so once the field has been nulled it is trivially
        // `!== run`; and when the nulling did not happen, it was already a
        // different run and also `!== run`. Both paths returned. Everything
        // below - the SOCKS address, the traffic counters, the captcha state,
        // pendingCaptcha, captchaBrowser.close() and restoreSystemProxy() - was
        // unreachable on every input. A user who answered a Yandex captcha,
        // disconnected, and came back found the captcha browser still on screen
        // with no way to dismiss it, and pendingCaptcha still pointing at the
        // previous core's check, so the next "open the check page" offered the
        // new core the dead run's URL and cookies.
        //
        // One branch, one decision, taken where the state is still held.
        //
        // Both calls below are made with the monitor released, deliberately. Two reasons,
        // and the first is a deadlock:
        //
        // restoreSystemProxy() is @Synchronized on `this`, and it is called here
        // while holding `lock`. applySystemProxy() is @Synchronized on `this` and
        // takes `lock`. That is AB-BA: one thread holding `lock` waiting for
        // `this`, another holding `this` waiting for `lock`. Both run on
        // Dispatchers.IO, so it can deadlock. (captchaBrowser.close() is not
        // @Synchronized at all - an earlier version of this comment claimed it
        // was, and used that false premise to justify holding `lock`.)
        //
        // The second is that both touch the Windows registry and a browser
        // process and are far too slow to sit inside a monitor that the connect
        // path also needs.
        val mine = synchronized(lock) {
            if (this.run === run) {
                this.run = null
                true
            } else {
                false
            }
        }
        if (!mine) return

        _socksAddress.value = null
        _exitAddress.value = ExitAddress.Unknown
        _traffic.value = TrafficStats()
        _captcha.value = null
        pendingCaptcha = null
        captchaBrowser.close()
        restoreSystemProxy()
    }

    private fun killTree(process: Process) {
        if (!process.isAlive) return
        runCatching {
            if (isWindows) {
                ProcessBuilder("taskkill", "/F", "/T", "/PID", process.pid().toString())
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } else {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }.onFailure { process.destroyForcibly() }
    }

    // ---- system proxy ----

    /**
 * A settings write, reported rather than thrown.
 *
 * JsonFile.write throws on a full disk, a locked settings.json, or a move the
 * file system refuses. Two callers sat directly on that, both in the connection
 * lifecycle:
 *
 * - restoreSystemProxy() is called from stopRun() BEFORE killTree(). A throw
 *   there skipped the kill: the core stayed alive holding the SOCKS port,
 *   `stopping` was already true and _state stuck at Disconnecting. The next
 *   connect found the same dead-but-alive run, threw in the same place, and
 *   never reached start() - the connect button was dead until restart.
 * - applySystemProxy() runs from markConnected, which is in the readOutput
 *   coroutine - the only reader of the core's stdout. A throw killed it, and
 *   useLines closed the pipe, so the core then died on SIGPIPE.
 *
 * Returns false when the value did not stick, because one caller has to be able
 * to tell. applySystemProxy must NOT take the system proxy over when it has
 * failed to record what the proxy was: the takeover happens either way, and a
 * missing record means restoreSystemProxy returns early on disconnect and
 * Windows is left pointing at 127.0.0.1 on a dead port with nothing to put
 * back. Unrecoverable without a registry editor - strictly worse than the
 * throw this replaced.
 */
private fun persist(block: (AppSettings) -> AppSettings): Boolean {
    // Asks the repository whether the change stuck, rather than waiting for an
    // exception.
    //
    // It used to do the opposite, and that made the guard below dead code:
    // `update` absorbs a failed write so the app cannot be killed by an
    // unwritable file, which also means it never throws - so a try/catch here
    // always took the success path. The takeover it was written to prevent
    // happened exactly when it must not, and the machine was left pointing at
    // a dead port with the original proxy settings on no medium at all.
    runCatching { settings.update(block) }.onFailure {
        log(LogLevel.Error, "Не удалось сохранить настройки: ${it.message ?: it.javaClass.simpleName}")
    }
    if (!settings.unsaved) return true
    log(
        LogLevel.Error,
        settings.writeFailureHint ?: "Настройки не сохранены на диск",
    )
    return false
}

/** Points Windows at the core while a client is connected and the setting is on. */
    @Synchronized
    private fun applySystemProxy() {
        if (!isWindows) return
        val current = synchronized(lock) { run }
        // The full tunnel carries everything already; the proxies do not run then.
        val wanted = settings.settings.value.systemProxy && current?.settings?.fullTunnel != true
        val connected = _state.value is ConnectionState.Connected || _state.value is ConnectionState.Reconnecting
        val address = current?.httpProxy
        if (!wanted || !connected || address == null || current.settings.mode != ConnectionMode.Client) {
            restoreSystemProxy()
            return
        }
        if (settings.settings.value.savedSystemProxy == null) {
            // Read BEFORE enabling, and refuse the takeover if the read failed.
            //
            // WindowsSystemProxy.read() throws when the registry could not be
            // queried, which is different from "the user has no proxy". Saving
            // the second when we only know the first is how the restore ends
            // up deleting a proxy that was configured all along - and it reports
            // success while doing it. Nothing is enabled in that case, so the
            // tunnel is simply not taken over this session.
            val previous = runCatching { WindowsSystemProxy.read() }.getOrElse {
                log(LogLevel.Error, "Не удалось прочитать текущий системный прокси - он остаётся нетронутым")
                return
            }
            // Nothing is enabled unless the original settings were recorded. A
            // takeover with no record is the one unrecoverable outcome here:
            // restoreSystemProxy returns early on a null savedSystemProxy, so
            // Windows stays pointed at a dead 127.0.0.1 on disconnect and the
            // startup recovery has nothing to put back.
            if (!persist { it.copy(savedSystemProxy = previous) }) {
                log(LogLevel.Error, "Системный прокси Windows не перехвачен: не удалось сохранить текущие настройки")
                return
            }
        }
        runCatching { WindowsSystemProxy.enable(address) }
            .onSuccess { log(LogLevel.Info, "Системный прокси Windows: $address") }
            .onFailure { log(LogLevel.Error, it.message ?: "Не удалось включить системный прокси") }
    }

    @Synchronized
    private fun restoreSystemProxy() {
        val saved = settings.settings.value.savedSystemProxy ?: return
        // The record is cleared ONLY when the restore actually worked.
        //
        // It used to be cleared unconditionally, one line below the
        // runCatching, and the restore is not atomic: WindowsSystemProxy.set
        // throws on the first failing `reg add`, so a timeout or a locked-down
        // policy leaves ProxyServer/ProxyEnable half-written and Windows still
        // pointing at 127.0.0.1. Erasing the one copy of the original settings
        // in that state is unrecoverable - the startup recovery finds nothing
        // to put back and the browser is offline with no record of why.
        // Keeping a failed restore means the next launch tries again.
        val ok = runCatching { WindowsSystemProxy.restore(saved) }
            .onSuccess { log(LogLevel.Info, "Системный прокси Windows восстановлен") }
            .onFailure { log(LogLevel.Error, "Не удалось вернуть системный прокси: ${it.message}") }
            .isSuccess
        if (ok) persist { it.copy(savedSystemProxy = null) }
    }

    // ---- exit address ----

    override fun refreshExitAddress() {
        val checked = synchronized(lock) { run } ?: return
        val exitProxy = checked.httpProxy
        // Full tunnel: this app's own traffic goes through it like any other.
        if (exitProxy == null && !checked.settings.fullTunnel) return
        _exitAddress.value = ExitAddress.Checking
        scope.launch {
            val result = runCatching {
                // HttpURLConnection rather than java.net.http: the shipped
                // runtime is a jlink image without that module, and a class
                // referring to it fails to verify and takes the app down at
                // startup. Proxy is set explicitly either way, because with a
                // full tunnel this request has to go out the same way the user
                // does - or, with no tunnel, around the system proxy the app
                // itself may have set, which would otherwise be asking the
                // core for the address it just gave it.
                val route = if (exitProxy != null) {
                    val (host, port) = exitProxy.split(":").let { it[0] to it[1].toInt() }
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
                } else {
                    Proxy.NO_PROXY
                }
                val connection = URI("https://api.ipify.org").toURL().openConnection(route) as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "OpenFlux-Desktop")
                val body = try {
                    connection.inputStream.bufferedReader().readText().trim()
                } finally {
                    connection.disconnect()
                }
                require(IP.matches(body)) { "неожиданный ответ" }
                ExitAddress.Known(body)
            }.getOrElse { ExitAddress.Unavailable(it.message ?: "нет ответа") }
            // A disconnect during the check leaves the address unknown.
            if (synchronized(lock) { run } === checked) _exitAddress.value = result
        }
    }

    // ---- captcha ----

    private fun onCaptchaRequest(request: IpcCookiesRequest) {
        if (pendingCaptcha == request) return
        pendingCaptcha = request
        log(LogLevel.Warning, if (request.remote) "Нода просит пройти проверку Яндекса" else "Яндекс просит пройти проверку")
        _captcha.value = CaptchaPrompt(request.url, request.reason, request.remote)
        openCaptcha()
    }

    override fun openCaptcha() {
        val request = pendingCaptcha ?: return
        scope.launch {
            _captcha.update { it?.copy(error = "", progress = "Открываю страницу проверки…") }
            val error = runCatching {
                captchaBrowser.open(request) { step -> _captcha.update { it?.copy(progress = step) } }
            }.exceptionOrNull()
            _captcha.update { it?.copy(error = error?.message.orEmpty(), progress = "") }
            if (error == null && captchaBrowser.awaitPassed() && pendingCaptcha == request) {
                log(LogLevel.Info, "Страница Яндекса открылась без проверки, передаю cookies")
                submitCaptcha()
            }
        }
    }

    override fun submitCaptcha() {
        val request = pendingCaptcha ?: return
        if (_captcha.value?.busy == true) return
        _captcha.update { it?.copy(busy = true, error = "") }
        scope.launch {
            try {
                val jar = captchaBrowser.collect(request.url)
                require(jar.isNotEmpty()) { "Нет cookies для ${URI(request.url).host}: пройдите проверку на странице выше" }
                val ipc = synchronized(lock) { run }?.ipc ?: throw IllegalStateException("Ядро не на связи")
                ipc.offerCookies(IpcCookiesOffer(request.transport, jar, remote = request.remote))
                log(LogLevel.Success, "Проверка пройдена, cookies переданы ${if (request.remote) "ноде" else "ядру"}")
                pendingCaptcha = null
                _captcha.value = null
                captchaBrowser.close()
            } catch (e: Exception) {
                _captcha.update { it?.copy(busy = false, error = e.message ?: "Не удалось передать cookies") }
            }
        }
    }

    override fun dismissCaptcha() {
        pendingCaptcha = null
        _captcha.value = null
        captchaBrowser.close()
    }

    // ---- logs ----

    override fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun log(level: LogLevel, text: String) {
        val line = LogLine(lineIds.incrementAndGet(), System.currentTimeMillis(), text, level)
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    override fun shutdown() {
        val current = synchronized(lock) { run }
        if (current != null) {
            current.stopping = true
            killTree(current.process)
            cleanup(current)
        }
        restoreSystemProxy()
        captchaBrowser.close()
    }

    companion object {
        private const val MAX_LOG_LINES = 5000
        /** How long the core has to open its IPC socket before the log takes over. */
        private const val IPC_WAIT_MS = 8000L
        private val SHARE_LINK = Regex("""openflux://v1/[A-Za-z0-9_-]+""")
        private val IP = Regex("""^[0-9a-fA-F:.]{3,45}$""")
        /** The core's --debug lines carry microseconds: "23:33:27.443294 [VOLGA]". */
        private val DEBUG_STAMP = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\.\d{6} """)

        fun levelOf(line: String): LogLevel {
            val lower = line.lowercase()
            return when {
                lower.contains("[error]") || lower.contains("fatal") || lower.contains("panic") -> LogLevel.Error
                lower.contains("warning") || lower.contains("[warn") || lower.contains("captcha required") -> LogLevel.Warning
                lower.contains("[success]") || lower.contains("running as") || lower.contains("authenticated peer") -> LogLevel.Success
                DEBUG_STAMP.containsMatchIn(line) -> LogLevel.Debug
                else -> LogLevel.Info
            }
        }

        /** A plain-language reason for common fatal core errors. */
        fun friendlyProblem(line: String): String? {
            val lower = line.lowercase()
            return when {
                lower.contains("only one usage of each socket address") || lower.contains("address already in use") ->
                    "Порт уже занят другой программой. Смените порт в настройках"
                lower.contains("read encryption key file") -> "Ядро не смогло прочитать ключ шифрования"
                lower.contains("ipc listen") -> "Ядро не смогло открыть канал связи с приложением"
                lower.contains("--config:") -> "Ядро не приняло конфигурацию: ${line.substringAfter("--config:").trim()}"
                lower.contains("failed to start transport") -> "Транспорт не запустился: ${line.substringAfter("transport:").trim().take(160)}"
                lower.contains("fatal") || lower.contains("log.fatal") -> line.substringAfter(": ").take(200)
                else -> null
            }
        }
    }
}

/**
 * Does this line from the core mean the tunnel is up?
 *
 * The three strings below are the core's own readiness announcements
 * (main.go). Kept in one place and tested, because the two paths print
 * different words and matching only one of them leaves the window on
 * "Подключение" while everything already works.
 */
internal fun announcesReady(line: String): Boolean =
    line.contains("Running as CLIENT") ||
        line.contains("Running as EXIT NODE") ||
        line.contains("Tunnel active")

/** Finds the core binary: the user's file or the one shipped with the app. */
class CoreBinary {
    private val os = System.getProperty("os.name").lowercase()
    private val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "amd64"
    }

    private val fileName: String = when {
        os.contains("win") -> "openflux-windows-$arch.exe"
        os.contains("mac") -> "openflux-darwin-$arch"
        else -> "openflux-linux-$arch"
    }

    /** The folder under desktopApp/resources the build packs for this OS (Compose's appResources layout). */
    private val resourceDir: String = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }

    /** Unpacking happens once; a second connect must not redo 15 MB of I/O. */
    private val once = java.util.concurrent.atomic.AtomicReference<File?>()

    private fun bundledCandidates(): List<File> {
        val explicit = System.getProperty("compose.application.resources.dir")
        if (explicit != null) return listOf(File(explicit, fileName))

        // The two user.dir entries are development fallbacks and are consulted
        // ONLY from a source checkout. They used to be in the same list as the
        // packaged path, at a HIGHER precedence than the jar: user.dir is
        // whatever folder the exe was launched from, the ZIP is unzipped into
        // Downloads, and anything able to write one file there -
        // `resources\windows\openflux-windows-amd64.exe` - got executed with
        // the user's privileges, unverified, before fromJar() was even
        // consulted. The jar path at least checks the size.
        val tree = File(System.getProperty("user.dir") ?: return emptyList())
        if (!File(tree, "gradlew").isFile) return emptyList()
        return listOf(
            File(tree, "resources/$resourceDir/$fileName"),
            File(tree, "desktopApp/resources/$resourceDir/$fileName"),
        )
    }

    /**
     * The core that shipped inside the app, unpacked on first use.
     *
     * A packaged build has no resources folder next to the launcher: the core,
     * wintun.dll and the version stamp are entries of the app jar, under
     * `windows/`, and nothing on disk is named `openflux-windows-amd64.exe`
     * until somebody writes it out. The search above therefore finds nothing in
     * an installed app, and the app reports that it was built without a core
     * when it is carrying one.
     *
     * bundle.txt is the list of what belongs next to the core and how large
     * each file is; it is written by the build for exactly this. Sizes are
     * checked after writing, because a jar entry that came out truncated
     * produces a core that fails later with something far less obvious than
     * "the download was damaged".
     */
    private fun fromJar(): File? = once.get() ?: synchronized(this) {
        once.get() ?: unpack().also { once.set(it) }
    }

    private fun unpack(): File? {
        val base = "/$resourceDir/"
        val expected = readBundle(base) ?: return null
        val target = AppDirs.runtime
        target.mkdirs()
        for ((name, size) in expected) {
            val stream = CoreBinary::class.java.getResourceAsStream("$base$name") ?: return null
            val bytes = stream.use { it.readBytes() }
            // A jar entry that came out short produces a core that fails much
            // later with something that looks like a network problem, so the
            // size the build recorded is checked here.
            if (bytes.size.toLong() != size) return null
            File(target, name).writeBytes(bytes)
        }
        return File(target, fileName).takeIf { it.isFile }
    }

    /**
     * What belongs beside the core, and how large the build made each file.
     *
     * bundle.txt is written as `name<TAB>size`, one per line.
     */
    private fun readBundle(base: String): Map<String, Long>? {
        val text = CoreBinary::class.java.getResourceAsStream("${base}bundle.txt")
            ?.use { it.readBytes().decodeToString() } ?: return null
        val entries = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[1].toLongOrNull()?.let { parts[0] to it } else null
        }.toMap()
        // The core itself has to be listed, whatever else the manifest says.
        return entries.takeIf { fileName in it }
    }

    fun bundled(): File? = bundledCandidates().firstOrNull { it.isFile } ?: fromJar()

    fun resolve(settings: AppSettings): File? = when (settings.coreSource) {
        CoreSource.Custom -> File(settings.customCorePath.trim()).takeIf { settings.customCorePath.isNotBlank() && it.isFile }
        CoreSource.Bundled -> bundled()
    }?.let(::runnable)

    /**
     * On Linux and macOS the core must be executable. A package may lose the
     * bit, and an installed app's folder is not ours to change: then run a
     * copy from the app's data folder.
     */
    private fun runnable(file: File): File {
        if (os.contains("win") || file.canExecute()) return file
        if (runCatching { file.setExecutable(true) }.getOrDefault(false) && file.canExecute()) return file
        val copy = File(AppDirs.runtime, file.name)
        if (!copy.isFile || copy.length() != file.length() || copy.lastModified() < file.lastModified()) {
            file.copyTo(copy, overwrite = true)
        }
        copy.setExecutable(true, true)
        return copy
    }

    /** The version file shipped next to the bundled core. */
    fun version(): String = bundled()?.let { File(it.parentFile, "openflux-core.version") }
        ?.takeIf { it.isFile }?.readText()?.trim()
        ?: if (bundled() != null) "встроенное" else "не найдено"
}
