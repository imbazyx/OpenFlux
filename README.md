# OpenFluxAndroid

Android client for [OpenFlux](https://github.com/imbazyx/OpenFlux):
system VPN or local SOCKS5, multi-transport sessions with automatic
failover, AES-256-GCM encryption, and the in-app flow for passing a
transport's check (SmartCaptcha, a login wall) through the built-in browser.

You can also choose **which apps go through the tunnel** — everything, or only
a list you pick — from the app-selection screen.

> **Note before you publish:** the two submodules (`OpenFlux/`, `shared/`) are
> deliberately not the originals' repositories — this fork carries its own
> commits in both, and they must exist under `imbazyx` before this repository
> can be cloned with `--recurse-submodules`. See
> [`docs/PUBLISH-CHECKLIST.md`](docs/PUBLISH-CHECKLIST.md).

This repository's app code and UI (the `androidApp/` and `shared/` modules)
come from [meepo161/OpenFluxClient](https://github.com/meepo161/OpenFluxClient),
used here with the author's agreement. **Huge thanks to
[@meepo161](https://github.com/meepo161)** — see [Credits](#credits) below.
The previous, simpler single-transport app that used to live in this
repository is preserved at the [`legacy-native-app`](../../tree/legacy-native-app)
tag.

## Getting the code

```bash
git clone --recurse-submodules https://github.com/imbazyx/OpenFluxAndroid.git
```

Already cloned without `--recurse-submodules`?

```bash
git submodule update --init --recursive
```

This checks out two submodules:

- `shared/` → [OpenFluxClientShared](https://github.com/imbazyx/OpenFluxClientShared),
  the Compose Multiplatform UI and models shared with
  [OpenFluxDesktop](https://github.com/imbazyx/OpenFluxDesktop).
- `OpenFlux/` → [OpenFlux](https://github.com/imbazyx/OpenFlux), the
  core this app embeds as a library (gomobile).

## Building

Needs JDK 17, Go, the Android SDK and NDK 27, and `gomobile`
(`go install golang.org/x/mobile/cmd/gomobile@latest`).

```bash
scripts/build-android-core.sh          # builds androidApp/libs/openflux.aar
                                        # from the OpenFlux/ submodule
./gradlew :androidApp:assembleDebug    # APK, split per ABI
```

`scripts/build-android-core.sh` also accepts an explicit path
(`scripts/build-android-core.sh ../OpenFlux`) if you'd rather build against a
separate checkout than the submodule.

### Building on WSL (the reference path)

The toolchain this project is actually built with lives in `scripts/`:
`wsl-toolchain.sh` (Go, JDK, Android SDK, NDK, gomobile), `wsl-build.sh`
(assembles the five per-ABI release APKs plus `SHA256SUMS.txt` into `dist/`),
and `wsl-audit.sh` (verifies those artifacts). Everything runs from WSL; the
Windows checkout is not used for building.

```bash
scripts/wsl-build.sh 1.2.0     # -> dist/, five signed APKs
scripts/wsl-audit.sh 1.2.0     # -> AUDIT_OK / AUDIT_FAILED
```

`wsl-audit.sh` is not a formality: it re-checks each APK's package name,
embedded core provenance (the commit the core was built from), the native
library's Go build ID, the launcher icon, ABI layout, timestamps and checksums,
and fails on any mismatch. `scripts/negative-control.sh` attacks the audit
with 25 tampered trees to prove the checks actually fire.

## Per-app VPN

From the app-selection screen you can route **all** of the phone's traffic
through the tunnel, or pick exactly which apps go through it. The choice is
stored by package name and read the next time the tunnel is established, so it
survives reconnects.

Two Android rules shape this, and the code depends on both:

- `addAllowedApplication` and `addDisallowedApplication` **cannot be mixed**
  in one `VpnService.Builder`;
- a builder with **no** application list allows every app, so "all traffic" is
  the absence of a rule, not an empty one.

## Structure

```
androidApp/   VPN service, WebView-based check flow, camera (QR), settings
shared/       Submodule: models, service interfaces, design system, screens
OpenFlux/     Submodule: the core (CLI + the mobile/ gomobile bridge)
scripts/      wsl-toolchain.sh, wsl-build.sh, wsl-audit.sh,
              negative-control.sh, build-android-core.sh
```

## Credits

- **[meepo161](https://github.com/meepo161)** — author of
  [OpenFluxClient](https://github.com/meepo161/OpenFluxClient), the source of
  this app's UI and logic (`androidApp/`, `shared/`): multi-transport
  sessions with failover, AES-256-GCM encryption, the in-app check/captcha
  flow, the node-deployment wizard, and the Compose design system. Thank you!
- **[p1neappleXpress](https://github.com/p1neappleXpress)** — author of the
  [OpenFlux](https://github.com/p1neappleXpress/OpenFlux) core this app
  embeds: the tunnel, transports, and negotiation protocol. This fork carries
  its own patches on top of that core (see `CHANGELOG.md`).
- **[damnurmum](https://github.com/damnurmum)** — author of
  [OpenFlux-Android](https://github.com/damnurmum/OpenFlux-Android) and,
  in the core, `openflux://` links and QR codes, and the cups.online
  transport, which this app's share and scan screens build on. Thank you!

## License

GNU General Public License v3.0 or later — see [LICENSE](LICENSE).
