# Changelog

All notable changes to OpenFluxAndroid. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.0.0] - 2026-09-29

In-app updates. The app now checks GitHub for a newer release, says so next to
its own version, and installs it without leaving the app.

### Added

- **Update check against this repository's releases.** Settings shows the
  running version and, beside it, either «последняя версия» or «вышло
  обновление X.Y.Z» — so the one question the screen answers (is this current?)
  is answered without pressing anything first.
- **In-app update install.** The APK matching the device's ABI is downloaded,
  checked against the release's published SHA-256, and handed to the system
  installer. It is signed with the same release key, so it updates in place:
  the app's settings and saved documents survive.
- Version comparison that will not offer a downgrade: numeric parts, not a
  string compare, so 10.0.0 correctly beats 9.0.0. A pre-release is treated as
  its own release, so a release candidate is never pushed at a user already on
  the final.
- ABI selection that matches whole ABI names, so an x86-only device is not
  offered the x86_64 build.

### Fixed

- The repository link under Settings → About still read
  `github.com/p1neappleXpress/OpenFlux`. The URL it opened was already the
  fork's, so the link worked and the label under it was simply wrong.
- A failed update check and "no update available" showed the same thing, so a
  check that could not reach GitHub was indistinguishable from being up to
  date. A failed check now says so.

### Notes

- 2.0.0 is a version bump, not a rewrite: the tunnel, transports and
  reconnect fix are those of 1.2.1, unchanged.
- `versionCode` is 20000, above 1.2.1's 10201, so Android accepts the update
  without an uninstall.

## [1.2.1] - 2026-09-29

### Fixed

- **The tunnel could die and never come back.** Only the goroutine blocked in
  `ReadMessage` was able to schedule a reconnect. Every other watchdog that
  decided a session was dead - the keep-alive write, the rx-idle watchdog,
  session rotation, the one-way detector - called `closeSession`, which only
  cleared the flag and closed the socket, trusting the reader to notice. When
  no reader was left to notice, the transport stayed down while its TUN
  interface stayed up: Android kept reporting "connected" and kept routing
  into it, DNS resolved, TCP connected, and then pages hung on a loading bar
  until the user toggled the VPN by hand. Observed on a phone over LTE.
  Every watchdog now schedules recovery itself, via `requestReconnect`.
- Exhausting `MaxReconnectAttempts` returned in silence. It now logs that it
  is giving up, because that silence is what made the failure
  undiagnosable: nothing in the log separated "reconnecting" from "dead until
  you restart".
- Yandex's keep-alive path did not close the socket on failure, leaving the
  reader parked on a connection nobody could write to.
- `ReconnectGuard` admits exactly one reconnect at a time. One network drop is
  noticed by the reader, the keep-alive writer and the health watchdog
  simultaneously, and unguarded they would each dial a session that displaces
  and closes the previous one - the loop `closeSession`'s identity check
  exists to prevent, and one that never reaches `MaxReconnectAttempts`.

  The guard is released as soon as the attempt starts, so a failed attempt
  can schedule the next one instead of stranding the tunnel.

### Added

- Reconnect regression tests for both Mail.ru and Yandex. Mail.ru, the
  transport this was seen on, previously had no tests at all. The keep-alive
  test drives the real loop against a socket whose peer is gone, with no
  reader goroutine: red without the fix, green with it.

## [1.2.0] - 2026-09-28

Per-app VPN selection, and a release-hardening pass over the artifacts and
the core.

### Added

- **Per-app VPN**: choose whether the whole phone's traffic goes through the
  tunnel or only a picked list of apps. Stored by package name and re-applied
  on every (re)connect.
- `wsl-toolchain.sh` / `wsl-build.sh` / `wsl-audit.sh`: a WSL build and an
  artifact audit that verifies package name, embedded core provenance, the
  native library's Go build ID, the launcher icon, ABI layout, timestamps and
  checksums, failing on any mismatch.
- `negative-control.sh`: 25 tampered trees that attack the audit, so a green
  `AUDIT_OK` means something.

### Fixed

- A `lateinit var` in the app-selection screen was shadowed by a local `val`,
  so the Save and Reset buttons were never enabled and the screen was dead.
- If the installed-app list failed to load, saving silently wrote "all
  traffic" over a stored "selected apps only" rule and reported success.
  Both buttons now stay disabled until the list actually loads, and `onResume`
  retries the load.
- The selection screen displayed the raw stored value rather than what was
  actually applied, so it could claim "all phone traffic" for a stored
  per-app rule.
- The core's batch transport wrote five log lines per batch through the
  Android stderr bridge, and three more per **packet** on the failure paths —
  continuous ERROR-level logcat while the tunnel was up, and an avalanche of
  noise precisely when the transport was already broken. These are now behind
  the existing packet-log gate.
- The batched writer could stay dead across a restart, leaving every send
  reporting a full queue until the app was force-stopped.

### Changed

- Launcher icon recoloured purple to red.
- **One repository.** `OpenFlux/` and `shared/` are ordinary directories here
  instead of submodules. The pointer to a single exact commit broke twice: once
  naming commits that existed in no repository, once going stale after a rebase.
  Both failures surfaced only when a push was rejected. A clone is now buildable
  immediately, with no `--recurse-submodules`.
- Line endings are pinned to LF by `.gitattributes`, and `core.autocrlf` is left
  unset. A CRLF in a shell script made bash reject it outright
  (`set: pipefail: invalid option name`) and killed the build on its first line.
- Fixed the core's `.gitignore`: the rule `openflux`, meant for the built
  binary, also matched any path component named `openflux` — including the iOS
  app's source directory, dropping twelve files. Because `core.ignorecase` is
  true on Windows and false on Linux, the same repository carried those sources
  on one platform and not the other.
- The audit now fails (rather than warns) on a certificate-pin override, and
  verifies the package name, that the working tree — core sources included —
  matches HEAD, and that no artifact is future-dated.

### Known limitations

- The external-IP field shows "не удалось: откройте api.ipify.org в браузере"
  in Client mode. This is by design, not a fault: the app deliberately stays
  outside its own VPN to avoid a routing loop, so it cannot query an IP echo
  service through the tunnel. It resolves in exit-node mode.

## [2.0.0] - 2026-09-27

> **Never published.** This was the version number the app carried before the
> project settled on `1.2.x`. No release was ever cut under it: the `v2.0.0`
> tag pointed at the repository's first commit, so it described none of the
> work below, and it is no longer reachable after the history root was
> repaired. Everything here shipped as `1.2.0` and later. Kept as a record of
> what the first release contained, not as a version anyone can install.

First release of this app. Replaces the previous single-transport native
app (tun2socks + pdnsd JNI, preserved at the
[`legacy-native-app`](../../tree/legacy-native-app) tag) with the Compose
Multiplatform app originally built by [@meepo161](https://github.com/meepo161)
in [OpenFluxClient](https://github.com/meepo161/OpenFluxClient), moved here
with his agreement.

### Added

- System VPN or local SOCKS5.
- Multi-transport sessions with automatic failover and priority-based
  switching (including `direct`).
- AES-256-GCM session encryption.
- SmartCaptcha and login handling in a built-in browser, including a
  transport check on the exit node, passed through the tunnel from its
  own address.
- A node-deployment wizard: install an exit node on your own VPS over SSH
  from the app.
- `shared/` and `OpenFlux/` as git submodules ([OpenFluxClientShared](https://github.com/p1neappleXpress/OpenFluxClientShared)
  and the [OpenFlux](https://github.com/p1neappleXpress/OpenFlux) core), so
  this app always builds against one pinned, single copy of each instead of
  a vendored one. *(Both became ordinary directories in 1.2.0 — see above.)*
- `.github/workflows/release.yml`: a `v*` tag builds and signs a release
  APK and publishes it here.

### Credits

- [@meepo161](https://github.com/meepo161) — this app's UI and logic.
- [@p1neappleXpress](https://github.com/p1neappleXpress) — the OpenFlux
  core it embeds.
- [@damnurmum](https://github.com/damnurmum) — `openflux://` links/QR codes
  and the cups.online transport in the core, which this app's share and
  scan screens build on.
