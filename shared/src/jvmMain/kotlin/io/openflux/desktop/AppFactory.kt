package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.core.CoreConnectionService
import io.openflux.desktop.data.FileProfileRepository
import io.openflux.desktop.data.FileSettingsRepository
import io.openflux.desktop.data.JvmShareLinkCodec
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.platform.JvmPlatformServices
import io.openflux.desktop.platform.WindowsElevation
import io.openflux.desktop.service.AppContainer

/** Wires the desktop implementations together; called once from main. */
fun createAppContainer(appVersion: String): AppContainer {
    val settings = FileSettingsRepository(AppDirs.config)
    val binary = CoreBinary()
    val connection = CoreConnectionService(settings, binary)
    // Nothing else can be relied on to run when the window closes. Left undone,
    // the core keeps running as an orphan and Windows keeps pointing its
    // system proxy at a SOCKS port nobody is listening on - the machine is
    // then offline until the next launch restores the saved settings.
    Runtime.getRuntime().addShutdownHook(Thread({ connection.shutdown() }, "openflux-shutdown"))
    // Off the UI thread: this spawns `net session` with a 10 second timeout,
    // and the screens that read the result do so while drawing.
    WindowsElevation.warm()
    return AppContainer(
        profiles = FileProfileRepository(AppDirs.config),
        settings = settings,
        connection = connection,
        platform = JvmPlatformServices(appVersion) { binary.version() },
        shareCodec = JvmShareLinkCodec(),
        nodeWizard = CoreNodeWizard(settings, binary),
    )
}
