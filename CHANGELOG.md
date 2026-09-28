# Changelog

All notable changes to OpenFluxAndroid. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
- The audit now fails (rather than warns) on a certificate-pin override, and
  verifies the package name, the core submodule's cleanliness, and that no
  artifact is future-dated.

### Known limitations

- The external-IP field shows "не удалось: откройте api.ipify.org в браузере"
  in Client mode. This is by design, not a fault: the app deliberately stays
  outside its own VPN to avoid a routing loop, so it cannot query an IP echo
  service through the tunnel. It resolves in exit-node mode.

## [2.0.0] - 2026-09-27

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
  a vendored one.
- `.github/workflows/release.yml`: a `v*` tag builds and signs a release
  APK and publishes it here.

### Credits

- [@meepo161](https://github.com/meepo161) — this app's UI and logic.
- [@p1neappleXpress](https://github.com/p1neappleXpress) — the OpenFlux
  core it embeds.
- [@damnurmum](https://github.com/damnurmum) — `openflux://` links/QR codes
  and the cups.online transport in the core, which this app's share and
  scan screens build on.
