# Changelog

All notable changes to OpenFluxAndroid. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.3.1] - 2026-10-02

Both clients on one core, and a tag that means what it says.

### Changed

- **The Android app now ships the same core the PC does.** The `.aar` in
  `androidApp/libs/` was built at `f2855a7` and never rebuilt, so the phone ran
  a core from before the duplicate-packet guard while the PC ran one from after
  it. Two clients, one interface, one core — the claim the README makes — was
  not true of the binaries. The Android core is rebuilt from the same tree and
  the version stamp under Settings → About now reports it.
- The version is 2.3.1 rather than another 2.3.0, and the tag points at the
  commit the files were built from.

### Notes

- 2.3.0 was published four times under one version number while the Windows
  fixes were still being found, and its tag was left at the commit the version
  was originally cut at. So for 2.3.0 the tag and the download were two
  different commits. That is the thing 2.3.1 exists to end: here the tag, the
  commit and the artifacts agree, and the release notes say so.
- The Android APKs are rebuilt and therefore have different hashes from the
  2.3.0 ones. They install over an existing 2.3.0 in place — same signing key,
  same application id, and a higher version code — so settings and saved
  documents survive. Signing is checked before publishing: an APK carrying a
  different certificate could not update an installed copy, and nothing else
  in the build would notice.

## [2.3.0] - 2026-09-30

Android connectivity, Wintun delivery, an honest update check — and then three
Windows defects that only a person running the installer could find.

### Fixed

- **The Windows client did not start at all.** The launcher reported *failed to
  launch JVM*, which is what it says when the JVM dies before the first frame.
  It did start. The shipped runtime is a jlink image of eleven modules and
  `java.net.http` is not one of them, so any class naming
  `HttpTimeoutException` anywhere — including in a `catch`, which is part of
  the method's exception table — fails verification on load. The four call
  sites now use `HttpURLConnection` from `java.base`.
- **"В этой сборке нет встроенного ядра".** The installed app has no
  `resources/` directory: the core travels as `windows/` jar entries and
  `CoreBinary` looked only for a file on disk. The `bundle.txt` manifest the
  build already wrote — and nothing read — is now read, and each entry is
  unpacked into the data folder on first use with its size verified, so a
  truncated copy fails here instead of later masquerading as a network fault.
- **The window stayed on «Подключение» while the tunnel was already carrying
  traffic.** Readiness was detected by watching for `Running as CLIENT`, which
  the core prints only on the legacy gVisor SOCKS5 path; the Wintun path
  announces itself with `Tunnel active`. Everything up to the first payload
  worked — the node's address was already the one an IP echo service showed —
  and nothing on screen said so. All three of the core's announcements are now
  one named predicate, tested against the log of the real run that showed it.

  No blind timeout was added as a safety net: a core that is alive but has not
  authenticated looks exactly like one that is starting, and reporting
  «Подключено» there would trade a hang for a lie.

- **Duplicated frames from the carrier no longer reach the TCP stack.** Mail.ru
  Docs relays a document update as a cursor event and does not promise
  exactly-once. Captured on both ends at once: the client sent one SYN in one
  batch, the exit received it twice with the same sequence number, 5ms apart.
  Everything downstream dispatched it once, so the second copy was injected as
  a fresh packet — and a duplicate SYN for a live connection resets it. The
  answer arrived as an RST and no payload ever crossed. Duplicates inside a
  250ms window are dropped before injection, which is the safe direction: TCP
  already treats a lost segment as normal and retransmits on its own timer.

  The duplication is intermittent and had stopped on its own before this was
  exercised — the guard logged zero drops during the runs that succeeded. It is
  insurance for the mechanism above, not a demonstrated cure for that outage.

### Added

- The repository avatar. It is rendered from the same vector drawables the
  launcher icon is built from, so the two cannot drift, and the palette stays
  the one the fork has carried since 1.2.0 — purple to red, repainted then so
  it would not be mistaken for upstream's blue.
- Release topics, so the repository is findable.

### Changed

- An unsigned release APK is refused outright. The check runs when the task
  graph is ready, not while configuring, so an ordinary debug build and
  `:shared:jvmTest` still work with no key present.
- The core README names this repository.

### Notes

- **The `v2.3.0` tag still points at `237b553`**, the commit the version was
  originally cut at. The Windows artifacts have since been re-published four
  times from later commits, because the fixes above were found after the tag
  and the decision was to ship them under the same version rather than bump it.
  The tag is left where it is: moving a published tag would rewrite history,
  which was explicitly declined. What the tag means and what the download
  contains are therefore not the same commit — read this section, not the tag.
- Android APKs are byte-identical to the previous publication. The change is
  confined to the core and the Windows client, so there was nothing to rebuild
  for Android.

## [2.2.0] - 2026-09-30

The Windows client, and a set of things the Android one was quietly doing
wrong.

### Added
- The Windows client checks for a newer release and installs it from inside
  the app, on the same terms as Android: newest release carrying an `.msi`,
  checked against the release's own `SHA256SUMS.txt` before `msiexec` starts.
- The portable Windows build is produced by a Gradle task, and both Windows
  artifacts are collected into `OpenFluxPC/dist/`.
- The SOCKS5 server has a read limit, a read deadline, and stops logging
  things that are not diagnostics.

### Fixed
- The user's MAX contacts - names and phone numbers - were printed to the log
  on every login, and the tunnel's own payload was dumped on every packet.
  Both land in the log buffer the apps show on their Logs screen. The count is
  logged; the people and the traffic are not.
- The MAX call token leaked into the log through the endpoint URL and through
  gorilla's dial errors, which embed that URL.
- A malformed call frame could panic the read loop, and a peer could pin it
  forever: `oneme` was the only transport with neither a read limit nor a
  deadline.
- A wrong MAX token used to look exactly like a working transport - connect
  and login errors were discarded and `IsConnected` answered `true` - so the
  failover logic had nothing to react to.
- SOCKS5 defaulted to `:1080`, which is every interface, and the server has no
  authentication unless the caller sets one. It now defaults to loopback.
- The HTTP proxy ran with no timeouts, which is a Slowloris target.
- The "close to the notification area" switch is gone. There is no tray; it
  defaulted to on and promised a menu that does not exist.
- "About" on Windows pointed at a repository the Windows build was never
  published to.
- Windows helper processes hung the calling thread forever: the pipe was read
  to EOF before `waitFor`, so the timeout could not fire.
- The release scripts were pinned to 1.2.0 while the project was at 2.0.0, and
  the negative-control's copies failed silently, so its 27 tampered-tree
  checks were passing over an empty tree.

### Changed
- The Windows build is published from this repository, and `node-v1.0.0` is a
  prerelease so the client release is the one GitHub shows as latest.
- Node install scripts now pin to this repository, so a node runs the same core
  as the apps rather than the upstream build.
- The oneme transport logs through the project logger, so its output respects
  a level like every other transport.

### Fixed - Windows client, found by running the installer

The MSI installed cleanly, opened a window and could not connect. Every step of
that looked healthy, which is what let these through a verified build.

- **The core is now in the application jar.** Windows Installer takes only the
  jars it is handed; a 14 MB executable dropped beside them, or in the
  application image, is not in the installed program. The MSI shipped with no
  core at all, and `CoreBinary` looked for it in a `resources` folder that
  jpackage also dropped — and in a path that omitted the platform segment, so
  it would not have found the core in the zip either. `checkCoreInInstaller`
  now opens the jar the packages are built from and fails if the core is
  missing; `checkCore` only proved it had been built, which is what let this
  ship.
- **Start menu and desktop shortcuts.** `windows { menu, menuGroup, shortcut }`
  were never set: the installer put the program in Program Files and left
  nothing to click. `upgradeUuid` is fixed rather than generated, because
  Windows Installer keys upgrades on it and a fresh UUID installs each version
  beside the last one.
- The build now fails when the Windows client does not open a window, instead
  of reporting success for a package that cannot start.
- `Wintun.dll` travels beside the core rather than relying on PATH.
- The Windows client points at this repository, not at where the code came
  from.

### Changed - package size
- The core is no longer copied into the application image as well as the jar.
  With it in the jar it was in both the MSI and the zip, so a second copy in
  the image meant three 14 MB copies in one download.

## [2.1.0] - 2026-09-30

The Windows client, and a set of things the Android one was quietly doing
wrong.

### Added
- The Windows client checks for a newer release and installs it from inside
  the app, on the same terms as Android: newest release carrying an `.msi`,
  checked against the release's own `SHA256SUMS.txt` before `msiexec` starts.
- The portable Windows build is produced by a Gradle task, and both Windows
  artifacts are collected into `OpenFluxPC/dist/`.
- The SOCKS5 server has a read limit, a read deadline, and stops logging
  things that are not diagnostics.

### Fixed
- The user's MAX contacts - names and phone numbers - were printed to the log
  on every login, and the tunnel's own payload was dumped on every packet.
  Both land in the log buffer the apps show on their Logs screen. The count is
  logged; the people and the traffic are not.
- The MAX call token leaked into the log through the endpoint URL and through
  gorilla's dial errors, which embed that URL.
- A malformed call frame could panic the read loop, and a peer could pin it
  forever: `oneme` was the only transport with neither a read limit nor a
  deadline.
- A wrong MAX token used to look exactly like a working transport - connect
  and login errors were discarded and `IsConnected` answered `true` - so the
  failover logic had nothing to react to.
- SOCKS5 defaulted to `:1080`, which is every interface, and the server has no
  authentication unless the caller sets one. It now defaults to loopback.
- The HTTP proxy ran with no timeouts, which is a Slowloris target.
- The "close to the notification area" switch is gone. There is no tray; it
  defaulted to on and promised a menu that does not exist.
- "About" on Windows pointed at a repository the Windows build was never
  published to.
- Windows helper processes hung the calling thread forever: the pipe was read
  to EOF before `waitFor`, so the timeout could not fire.
- The release scripts were pinned to 1.2.0 while the project was at 2.0.0, and
  the negative-control's copies failed silently, so its 27 tampered-tree
  checks were passing over an empty tree.

### Changed
- The Windows build is published from this repository, and `node-v1.0.0` is a
  prerelease so the client release is the one GitHub shows as latest.
- Node install scripts now pin to this repository, so a node runs the same core
  as the apps rather than the upstream build.
- The oneme transport logs through the project logger, so its output respects
  a level like every other transport.

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

- The repository link under Settings → About still read the upstream address.
  The URL it opened was already this repository's, so the link worked and the
  label under it was simply wrong.
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

- Launcher icon recoloured purple to red. *(This is still the palette — it is
  what ships, and the repository avatar is rendered from the same vectors.)*
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

## [Unreleased 2.0.0] - 2026-09-27

> **Never published, and the number has since been reused.** The app carried
> this version before the project settled on `1.2.x`, but no release was ever
> cut under it: the `v2.0.0` tag pointed at the repository's first commit, so
> it described none of the work below, and it is no longer reachable after the
> history root was repaired. Everything here shipped as `1.2.0` and later.
>
> This entry is kept under a name that is not a version, because
> [2.0.0] above is now a real release and two different releases cannot share
> one heading. What is written below is a record of what the first release
> contained, not something anyone can install.

First release of this app. Replaces the previous single-transport native
app (tun2socks + pdnsd JNI, preserved at the
[`legacy-native-app`](../../tree/legacy-native-app) tag) with the Compose
Multiplatform app this repository has carried ever since.

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
- `shared/` and `OpenFlux/` as git submodules (the shared Compose app and the
  [OpenFlux](https://github.com/imbazyx/OpenFlux) core), so
  this app always builds against one pinned, single copy of each instead of
  a vendored one. *(Both became ordinary directories in 1.2.0 — see above.)*
- `.github/workflows/release.yml`: a `v*` tag builds and signs a release
  APK and publishes it here.

### Credits

- [@imbazyx](https://github.com/imbazyx) — this app and the OpenFlux core
  it embeds.
- [@damnurmum](https://github.com/damnurmum) — `openflux://` links/QR codes
  and the cups.online transport in the core, which this app's share and
  scan screens build on.
