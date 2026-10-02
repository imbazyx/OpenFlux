# OpenFlux

<img src="branding/icon.png" alt="OpenFlux" width="96" align="right">

[![Releases](https://img.shields.io/github/v/release/imbazyx/OpenFlux?label=release&color=blue)](https://github.com/imbazyx/OpenFlux/releases/latest)
[![License](https://img.shields.io/github/license/imbazyx/OpenFlux?color=blue)](LICENSE)
[![Go](https://img.shields.io/badge/core-Go%201.26-00ADD8?logo=go&logoColor=white)](OpenFlux/)
[![Kotlin](https://img.shields.io/badge/client-Kotlin%202.4-7F52FF?logo=kotlin&logoColor=white)](shared/)
[![Compose](https://img.shields.io/badge/ui-Compose%20Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](shared/)
[![Platforms](https://img.shields.io/badge/client-Android%20%7C%20Windows%2010--11-3DDC84)](https://github.com/imbazyx/OpenFlux/releases)

A VPN client that tunnels TCP over document-collaboration services, so it
works on networks where an ordinary VPN is blocked.

Two front ends, one interface, one core:

| Platform | What it does |
|---|---|
| **Android** | System VPN or a local SOCKS5 proxy, per-app routing, camera QR |
| **Windows 10–11** | System proxy or a local SOCKS5 proxy, the same screens |

## Which file do I download?

Every release carries files for several platforms. Pick the row, not the one
that happens to be first.

**Releases:** <https://github.com/imbazyx/OpenFlux/releases>

### Android — a phone

| Download | What it is |
|---|---|
| `OpenFluxAndroid-*-arm64-v8a-release.apk` | **Almost every phone made since 2017.** Take this unless you are told otherwise. |
| `OpenFluxAndroid-*-armeabi-v7a-release.apk` | Very old or very cheap 32-bit phones |
| `OpenFluxAndroid-*-x86_64-release.apk` | Intel phone or emulator |
| `OpenFluxAndroid-*-x86-release.apk` | 32-bit emulator |
| `OpenFluxAndroid-*-universal-release.apk` | Every ABI in one file, the largest download — only if you cannot tell which one you have |

Which one is your phone, if you are unsure: `arm64-v8a` covers virtually all
modern Android phones, and if the install fails with a wrong-architecture
error, install the universal one.

### Windows — a PC

| Download | What it is |
|---|---|
| `OpenFlux-<version>.msi` | **The normal choice.** A standard installer; it puts the app in the Start menu. |
| `OpenFlux-<version>-windows-amd64.zip` | No installer: unpack anywhere and run `OpenFlux.exe`. |

The two names are not interchangeable: the installer carries **no**
`-windows-amd64`, while the portable zip does. Pick the row, not the one that
looks similar.

Windows 10 or 11, 64-bit. Windows 7 is not supported — the interface is built
with Compose Multiplatform, which needs Windows 10 or newer, and the reason is
written up in [OpenFluxPC/README.md](OpenFluxPC/README.md).

### Everything else

Exit-node operators do not install anything from here. The core is a separate
pre-release — [`node-v1.0.0`](https://github.com/imbazyx/OpenFlux/releases/tag/node-v1.0.0)
— carrying `openflux-linux-{386,amd64,arm,arm64}`, built `-s -w` and statically
linked. It is marked pre-release on purpose: it is not a client, and it is not
covered by the same update path the two apps use. See [Building](#building) to
compile it yourself from `OpenFlux/`.

### Updating

Both clients check this page for a newer version and can install it from
inside the app, so you do not have to come back here after the first install.

## About this repository

> **Everything is in this one repository.** `OpenFlux/` (the core), `shared/`
> (the Compose Multiplatform UI and models), `androidApp/` and `OpenFluxPC/`
> (the two front ends) are ordinary directories here, not submodules. They were
> submodules, and the pointer to one exact commit broke twice during
> development — once naming commits that existed nowhere, once going stale
> after a rebase. A directory has no such state: a clone is buildable the
> moment it lands, with no extra steps.

The app code and UI come from [meepo161/OpenFluxClient](https://github.com/meepo161/OpenFluxClient),
used here with the author's agreement. **Huge thanks to
[@meepo161](https://github.com/meepo161)** — see [Credits](#credits) below.
The previous, simpler single-transport Android app is preserved at the
[`legacy-native-app`](../../tree/legacy-native-app) tag.

## Getting the code

```bash
git clone https://github.com/imbazyx/OpenFlux.git
cd OpenFlux
```

That is the whole procedure. What the clone contains:

- `OpenFlux/` — the core this app embeds as a library (gomobile). The same
  sources are released separately as a pre-release for exit-node operators,
  under the tag [`node-v1.0.0`](https://github.com/imbazyx/OpenFlux/releases/tag/node-v1.0.0)
  (Linux 386/amd64/arm/arm64, `-s -w`, statically linked).
- `shared/` — the Compose Multiplatform UI and models. Originally vendored
  from the upstream `OpenFluxClientShared`, and now maintained here: this fork
  carries its own fixes to it, so the copy in this repository is the one that
  both the Android app and the Windows client are built from.

## Building

Needs JDK 17, Go, the Android SDK and NDK 27, and `gomobile`
(`go install golang.org/x/mobile/cmd/gomobile@latest`).

```bash
scripts/build-android-core.sh          # builds androidApp/libs/openflux.aar
                                        # from the OpenFlux/ directory
./gradlew :androidApp:assembleDebug    # APK, split per ABI
```

`scripts/build-android-core.sh` also accepts an explicit path
(`scripts/build-android-core.sh ../OpenFlux`) if you'd rather build the library
against a separate core checkout.

### Building on WSL (the reference path)

The toolchain this project is actually built with lives in `scripts/`:
`wsl-toolchain.sh` (Go, JDK, Android SDK, NDK, gomobile), `wsl-build.sh`
(assembles the five per-ABI release APKs plus `SHA256SUMS.txt` into `dist/`),
and `wsl-audit.sh` (verifies those artifacts). Everything runs from WSL; the
Windows checkout is not used for building.

```bash
scripts/wsl-build.sh            # -> dist/, five signed APKs
scripts/wsl-audit.sh            # -> AUDIT_OK / AUDIT_FAILED
```

Both read the version from `appVersion` in the root `gradle.properties`, so the
Android build and the Windows client cannot end up at different numbers. Pass a
version to build something else and the script says so on stderr rather than
quietly producing artifacts that disagree with the release page.

`wsl-audit.sh` is not a formality: it re-checks each APK's package name,
embedded core provenance (the commit the core was built from), the native
library's Go build ID, the launcher icon, ABI layout, timestamps and checksums,
and fails on any mismatch. `scripts/negative-control.sh` attacks the audit
with 27 tampered trees to prove the checks actually fire.

[`OPERATIONS.md`](OPERATIONS.md) records the parts that are not visible from
the code: which of the two core checkouts on a machine is this fork, that the
Windows installer is only produced on a Windows host, that the NDK comes from
`wsl-toolchain.sh` and not from `gomobile init`, how the signing credentials'
variable names map onto the ones the build reads, and how to check a release
before publishing it.

### Line endings

The whole tree is committed with LF, and `.gitattributes` says so — `*.sh` and
`gradlew` explicitly. A CRLF in a shell script is not a formatting nit: bash
rejects it outright with

```
scripts/wsl-build.sh: line 8: set: pipefail: invalid option name
```

and the build dies before doing any work. `core.autocrlf` is left unset on
purpose; it applies one blanket rule to files that need opposite ones.

### Building the Windows client

The core first, as a plain `.exe` rather than through gomobile:

```bash
scripts/build-pc-core.sh                          # -> OpenFluxPC/resources/windows/
./gradlew :OpenFluxPC:createDistributable         # portable folder
./gradlew :OpenFluxPC:packageMsi                  # installer
```

Two things about this one, both learned the hard way:

- The last two steps must run **on Windows**. `jpackage` cannot produce a
  Windows package from another operating system, and it is the only thing that
  produces the `.exe` launcher at all.
- Do not build for two operating systems into the same output directory. The
  distributable path is not separated by OS, so a Linux build followed by a
  Windows one leaves a mixture behind: the build is green and the app dies on
  launch with `UnsatisfiedLinkError`. Run `:OpenFluxPC:clean` when switching.

`OpenFluxPC/README.md` has the details, including why Windows 7 is not on the
list.

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
androidApp/   Android client: VPN service, check flow, camera (QR), settings
OpenFluxPC/   Windows client: the window, and the packaging
shared/       models, service interfaces, design system, screens (both clients)
OpenFlux/     the core: CLI, the mobile/ gomobile bridge, transports
scripts/      wsl-build.sh, wsl-audit.sh, negative-control.sh,
              build-android-core.sh, build-pc-core.sh
```

`shared/` is what makes the two clients look and behave the same: the screens,
the design system and the models live there once, and each front end supplies
only a window and the handful of things the platform owns.

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
