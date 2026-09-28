# OpenFluxClientShared

The Compose Multiplatform UI and business logic shared by
[OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) and
[OpenFluxDesktop](https://github.com/p1neappleXpress/OpenFluxDesktop): profile
and session models, the connection screens, the node-deployment wizard, and
the service interfaces each platform implements (core process/IPC, the
built-in browser, settings storage).

This repository has no build of its own — it's meant to be used as a git
submodule at `shared/` from a consumer project that already has its own
Gradle root (wrapper, version catalog, `:androidApp`/`:desktopApp` modules),
matching the source-set layout below:

```
src/
  commonMain/      Models (Profile, CoreConfig, ConnectionState, NodeWizard),
                    service interfaces, the design system, and the screens -
                    shared across every platform
  jvmSharedMain/    Code both desktop and Android use (java.io, java.util.zip)
  androidMain/      Android-only implementations
  jvmMain/
    core/           Core process launch, IPC status, Yandex checks (desktop)
    node/           This platform's own node wizard (core --node-wizard over
                     stdin/stdout)
    web/            The built-in browser: off-screen KCEF, a proxy router,
                     a log
    platform/       Windows system proxy (WinInet), admin rights
    data/           Settings and profile storage
```

## Origin and credit

This module's code, architecture, and design come from
[meepo161/OpenFluxClient](https://github.com/meepo161/OpenFluxClient), used
here with the author's agreement. Full commit history and authorship are
preserved as extracted from that repository. **Huge thanks to
[@meepo161](https://github.com/meepo161)** for building it.

It talks to the [OpenFlux](https://github.com/p1neappleXpress/OpenFlux) core
(the tunnel/transports/protocol) through the service interfaces in
`commonMain`, implemented per platform in the consuming app. That core's own
`openflux://` links and QR codes, and its cups.online transport, were
contributed by [@damnurmum](https://github.com/damnurmum) (of
[OpenFlux-Android](https://github.com/damnurmum/OpenFlux-Android)) — this
module's share screens and QR scanning build on that work. Thank you!

## License

GNU General Public License v3.0 or later — see [LICENSE](LICENSE). The
original repository this was extracted from carried no explicit license;
adopting the core project's GPL-3.0 here keeps licensing consistent across
the OpenFlux ecosystem, with the author's agreement.
