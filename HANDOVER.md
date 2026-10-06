# Handover

State of the project as of 2026-10-06, written for whoever works on it next —
including an assistant starting from a cold session with no memory of any of
this.

Read this file first. Then `OPERATIONS.md` (how to build and publish).
`OPERATIONS.local.md` is not in the repository and holds the credential
locations and the node addresses.

---

## The update path is the priority, and it was broken in three ways

2.3.2 exists so that an installed 2.3.1 updates itself by pressing one button in
the app. Three defects on that path were found by audit in October 2026 and all
are fixed. They are written down here because each one looked like a detail and
each one stopped the feature for every user at once.

**The update check read only the first entry of `releases.atom`.** GitHub orders
that feed by CREATION date, not by version — the live feed has `v1.2.1` above
`v2.0.0` — and this repository publishes its exit-node core into the same feed.
`node-v1.0.0` and `0.0.5` are already in it, carrying no APKs at all. So the
moment any core release was cut, it became the first entry, every ABI probe came
back 404, and a user on 2.3.1 was told the release had nothing for their phone
while the real 2.3.2 sat further down, never examined. `appReleaseTags()` now
reads every tag, keeps the app versions, sorts by version descending and probes
newest-first down to `MAX_RELEASES_TO_CHECK`. `latestRelease()` had the same flaw
and would have shown `node-v1.0.0` to a user as the app's version.

**A refusal by the system installer was silent.** Android enforces the package
name, the signing certificate and the versionCode, and says nothing before
declining. The app has no result listener, so a release built from another key
just vanished into another app. `apkRefusal()` now names the cause before the
hand-off. Note that reading the archive's certificates needs
`GET_SIGNATURES`: with flags `0`, `PackageManagerService` skips certificate
collection and `PackageInfo.signatures` is always null, which made the whole
comparison inert while a test still passed.

**A granted VPN consent could be reported as refused.** `registerForActivityResult`
keeps its launcher in the `ActivityResultRegistry`, which outlives the activity
and replays a pending result to the recreated one. `ActivityBridge.detach()`
was clearing its slots anyway, so a pending consent was completed with `null`
and the granted answer, arriving later, found nothing to complete. It now keeps
the slots when `isChangingConfigurations` is true.

Scope, and the first version of this note was wrong about it: `AndroidManifest`
declares `configChanges` for `orientation`, `screenSize`, `screenLayout`,
`smallestScreenSize`, `keyboardHidden`, `uiMode` and `density`, so **a rotation
never destroys this activity and never reaches `detach` at all**. What is
actually recreated is a configuration change outside that list — locale,
`fontScale`, keyboard or navigation mode, `colorMode`, and OEM additions. The
flag is right for those; rotation was the wrong example, and a commit message
claimed otherwise.

Still open on this path, and deliberately so: whether the installer actually
finished is not observable (`TODO.local.md` predicts HyperOS will refuse an APK
installed by the app). A real `PackageInstaller` session with a status
`IntentSender` would answer it, and it is not written blind.

## A settings write that failed could not start the app

`FileSettingsRepository.update` ran `store.write` inside `MutableStateFlow.update`'s
transform and let it throw. Since the transform runs before `compareAndSet`, the
in-memory value was not updated either; since `update` is the one choke point
every settings write passes through, and fifteen of its callers are Compose click
handlers on the main thread, a full disk or a locked `settings.json` threw
straight through the UI handler and killed the application. In the connection
lifecycle it threw from `stopRun()` before `killTree()`, leaving the core alive
on the SOCKS port with the connect button dead until restart.

The write now happens outside the transform and a failure is recorded in
`writeFailure`. The settings still apply in memory — that is the existing policy
for an unreadable file, and `SettingsCorruptionTest` enforces it.

One consequence is load-bearing and easy to undo by accident: `applySystemProxy`
now REFUSES to take the Windows system proxy over when it could not record what
the proxy was. The takeover happens either way, and a missing record means
`restoreSystemProxy` returns early on disconnect, leaving Windows pointed at
`127.0.0.1` on a dead port with nothing to put back — unrecoverable without a
registry editor, and strictly worse than the throw it replaced.

## The installer's rollback used to make things worse

Three separate ways, all found by audit and all fixed:

- It deleted the core it had pointed `$BIN_DIR/openflux` at and left the symlink
  pointing there. Every unit it writes runs `ExecStart=$BIN_DIR/openflux`, so a
  failed apply bricked every channel already installed. `PREV_LINK` now restores
  the previous target.
- It deleted a firewall rule the run had not added, and `rm -rf`'d the state
  directory unconditionally while the config directory was properly guarded. The
  state directory holds the Yandex sign-in — the one thing there is no way to get
  back.
- `cmd_upgrade` reported every channel restarted and then deleted the older
  cores, without checking that any of them had come back.

`chown`/`chmod` on the node's configuration were also unchecked, and `apply`
answered `{"ok":true}` anyway: a node that cannot read its own config was
reported as a successful install.

## Comments in this codebase have lied about the code

Worth knowing before trusting one. `cleanup()`'s comment described an atomicity
the code did not have, then a rewrite made the guard inverted and the whole tail
unreachable — so `_socksAddress`, the traffic counters, the captcha state and
`captchaBrowser.close()` were dead for every input. A comment in `tunnel.go` said
"Packetf, not Debugf" while the code called `Debugf` on the per-packet path.
`readApk` asked for flags `0` and a test asserted the resulting null signer was
acceptable. `TestPinnedCommitIsReachable` skipped itself in the exact CI
configuration that produces the failure it exists to catch.

When a comment claims a property — atomicity, a window that does not exist, a
contract the platform does not honour — check the code before believing it.

---

## Read this if you are a new session

Copy `PASTE-IN-NEW-CHAT.txt` into the first message. It names the directory,
the three files to read, and the two things not to do.

## Where things are

| | |
|---|---|
| Project root | `D:\project\OpenFluxAndroid` — the only tree with the project in it |
| Repository | `git@github.com:imbazyx/OpenFlux.git`, branch `main` |
| Version | 2.3.2, declared once in `gradle.properties` |

There was once a second checkout of the upstream core beside this one. The
fork also used to live one directory deeper, inside *that* tree.
Both were git repositories, so building from the wrong one produced the upstream
core — no duplicate-packet guard, none of this fork's fixes — with nothing in the
build complaining. The upstream checkout has since been deleted; everything the
project needs is inside this directory.

---

## Current state

| | |
|---|---|
| Branch | `main`, in step with `origin/main` |
| Tag `v2.3.1` | `9e6b3a7` — the last published tag. This file used to say `f73b98c`; that hash is not the tag, and nothing in the tree explains where it came from. |
| Tag `v2.3.2` | **not cut yet.** `gradle.properties` declares 2.3.2 and `dist/` is built, but until the tag exists the in-app update check correctly reports "2.3.1 · последняя версия" and the button finds nothing. Cutting it is what actually publishes 2.3.2. |
| Author | every commit is `imbazyx`; `.git/hooks/pre-push` refuses any push URL without it |
| Latest release | `OpenFlux 2.3.1`, 8 assets |
| Exit nodes | six, all `active` (`exit`, `-2`, `-3`, `-4`, `-6`, `-7`) |

**The tag is behind HEAD, and the shipped core is no longer the HEAD core.**
`v2.3.1` points at `9e6b3a7`. The diff since then is not one commit: it is
**63 files under `OpenFlux/` and `shared/`** — the core, the tunnel, the
transport and the shared UI — 3 528 insertions and 235 deletions in that subset
alone, and 86 files / 5 819 insertions across the whole tree. The released
2.3.1 artifacts therefore contain a substantially older core and must not be
described as matching HEAD.

This text used to say the diff "names no build input", with the property to
verify being that it was empty. It has not been empty for a long time, and a
reader who trusted it would have concluded no rebuild was needed. The check is
real; the claim about its result was false.

```bash
git rev-list -n1 v2.3.1          # 9e6b3a7 as of this writing
git diff --stat v2.3.1..HEAD     # any OpenFlux/ or shared/ hit means a rebuild
                                  # 63 such files as of 2026-10-06; re-run it,
                                  # do not trust this line later
```

---

## What was done

### 1. The proxy outage was root-caused

Symptom: every new connection failed, while connections opened long before
carried traffic fine. Push connections in the same node log (ports 5228, 5222)
were healthy and moving 1400-byte segments.

Mail.ru Docs relays a document update as a cursor event and does **not** promise
exactly-once delivery. Captured on both ends simultaneously:

```
клиент -> [SYN] 10.10.10.2:31735 -> 104.26.12.205:443  seq=1936294202
          один пакет, одна отправка (flushLoop: sending batch of 1 packets)
нода   -> [SYN] ... seq=1936294202
нода   -> [SYN] ... seq=1936294202     тот же seq, через 5 мс
```

Everything downstream dispatched it once — `ReadMessage` → `handleMessage` →
`decodeBatch` → callback — verified by reading the code. So the second copy was
injected as a genuinely new packet, and a duplicate SYN against a live
connection resets it. The peer answered RST and no payload ever crossed:
transport healthy, tunnel up, every new connection refused.

### 2. The guard, and what it does not prove

`OpenFlux/tunnel/dedupe.go` drops byte-identical packets inside a 250 ms /
64-entry window before `InjectInbound`, wired in at the ingress in
`tunnel/tunnel.go`. Five tests in `tunnel/dedupe_test.go`, plus a concurrency
test added when a data race on the window was found and fixed.

Dropping is the safe direction: TCP already treats a lost segment as normal and
retransmits on its own timer, so it cannot invent a fault that did not exist.

**It does not run on the l3 exit nodes.** It is wired in `NewTCPTunnelMode`,
which the l4 exit and the clients use. The six production nodes run
`--role=exit --mode=l3 --transport=mailru` with a single carrier and no
`--negotiate`, so their transport is a plain `EncryptedTransport` rather than a
`Session`: no challenge echo, no sequence window, no session-level replay guard
either. What stands between a repeated frame and an l3 node is the 4096-entry
nonce FIFO in `EncryptedTransport` alone. The exposure there is much smaller -
the kernel ignores a second SYN on a live session, and the UDP NAT re-sends a
duplicate datagram rather than opening a second flow - but it should be named as
uncovered rather than counted as fixed.

**Honest limit, and it matters:** the duplication is intermittent. It had
already stopped on its own before the guard was exercised. Across the runs that
*succeeded*, the drop counter logged zero. The guard closes a mechanism proven
on live traffic; it is not a demonstrated cure for that particular outage.
During the A/B the old binary actually scored 6/6 while the fix scored 3/6 —
because the relay had recovered, not because the fix failed.

### 3. Exit nodes

Six units run `--role=exit` from one binary, `/usr/local/bin/openflux`, all
`--mode=l3` on their own Mail.ru document. One unit per person:

| Unit | Whose | Document | Encrypted |
|---|---|---|---|
| `openflux-exit` | owner | `fFWK/Wnf2LLrQp` | yes |
| `openflux-exit-2` | wife | `4Eii/4VdC79L7i` | no |
| `openflux-exit-3` | father | `HMM5/MF7YGi5B6` | no |
| `openflux-exit-4` | son | `ABzg/hzJorfqb6` | yes |
| `openflux-exit-6` | Анна | `fwC9/UQZve9mTr` | yes |
| `openflux-exit-7` | Арам | `FB7a/zrdqSDHtD` | yes |

Each secret is its own file under `/etc/openflux`, mode 600, and the key goes
into that person's own profile in the app — pasted, never generated there.
Encryption covers nodes 1, 4, 6 and 7; 2 and 3 are left plain until their
owners' phones are set up. `exit-5` was removed on 2026-10-03 and is kept on
the server as `openflux-exit-5.service.disabled`.

`l3` needs kernel RST suppression or every connection dies right after the
handshake. The scoped rule matching only RSTs sourced from the egress address is
used, rather than the host-wide one the project README describes — that one
changes behaviour for the whole machine, Docker included, and makes closed ports
look filtered.

Verified by a series: 4/4 successful, RST count zero.

**Resolved: the iptables rule now survives a reboot.** Every l3 unit carries
`ExecStartPre=/usr/local/sbin/openflux-rst-guard`, and the script derives the
egress address from the routing table instead of hardcoding it. Verified by
deleting the rule and restarting all six nodes — the first reinstalled it, the
rest found it present, exactly one rule. A real reboot was not performed.

### 4. Repository, icon and documentation

- The repository had no topics and no avatar. Topics added (14), description
  rewritten to say what the fork is.
- The launcher icon is the fork's own — purple to red, `#8B2FD9 → #E01B3C`,
  since 1.2.0 — not upstream's blue. It was verified against the shipped APK
  rather than assumed: unpacking it shows the adaptive icon resolving to
  `drawable/ic_openflux_background` and
  `drawable/ic_openflux_launcher_foreground`, the same two files in this
  repository, and regenerating from source yields an `openflux.ico`
  byte-identical to the committed one.
- README: fixed a wrong download filename that sent people to a 404 on the
  most-taken file, gave a nameless table its column headers, pointed the core
  section at the release that actually holds it.
- CHANGELOG: entries for 2.2.0 and 2.3.0 were **missing entirely** despite both
  releases existing. Written from the actual commits.
- Release notes for 2.3.0 had a corrupted character mid-word and omitted all
  three Windows fixes the release was re-uploaded for. Both corrected.

### 5. 2.3.1, built so that the tag and the artifacts agree

2.3.0 went out four times under one number while the Windows fixes were still
being found, and its tag stayed at the commit the version was cut at. The tag
and the download were two different commits, and the notes had to explain the
discrepancy.

2.3.1 exists to end that, and to close a real divergence: `openflux.aar` had
been built at `f2855a7` and never rebuilt, so the phone carried a core from
before the guard while the PC carried one from after it. "Two front ends, one
interface, one core" was true of the source and false of the binaries.

Both clients were rebuilt and the tag set on the build commit. The core is
confirmed inside the shipped APK by a string from the change, not by a size
difference:

```bash
unzip -p <apk> lib/arm64-v8a/libgojni.so > lib.so
strings lib.so | grep "dropped duplicate packet"
```

---

## Mistakes made along the way

Kept deliberately, because each of them nearly produced a wrong conclusion.

- **A false "no duplicates" result.** Grepping for `[PSH]` misses `[ACK PSH]`.
  The counts were wrong and the claim built on them was wrong.
- **A stale artifact read as a shipping defect.** A ship-check read an
  extraction from the start of the session while the real build output sat
  elsewhere. It looked like a packaging bug.
- **A Windows binary installed on Linux.** `status=203/EXEC`, node down. Caught
  in a minute by running `--help` on the server *before* `install`.
- **An inverted test assertion**, and a flaky transport test that passed 5/5
  both with and without the change — verified by stashing.
- **An invented app icon**, then a second wrong one: the dark plate was my
  invention, and both were replaced by the icon that actually ships. The
  CHANGELOG entries claiming otherwise were corrected in the same commit.
- **A false alarm about the signing key.** `apksigner` prints `certificate
  SHA-256 digest:`, not `SHA-256 certificate`; reading for the latter finds
  nothing, which is indistinguishable from a mismatched key. The key was the
  same one all along.
- **`packageMsi` reported `SKIPPED` in WSL, silently**, between
  `BUILD SUCCESSFUL` and a zip — a release missing its most-taken download
  that looked like a clean build.
- **A private signing key inside the git working tree for about a minute**
  after the project moved, before the ignore rule was extended. Nothing was
  committed. `git add -A --dry-run` now stages `.gitignore` alone.

---

## Deliberately not done

- **The per-packet copy in `l3.go` stays.** One heap allocation and a full copy
  per packet in both directions, purely so `reportSendError` can quote the
  pre-SNAT header. It cannot move under `if err != nil`: `rewriteSNAT` and
  `fixChecksums` mutate the packet in place first, and `udp.send` does the same,
  so by the time the error is known the original is already gone. Removing it
  needs either the few header fields ICMP quoting actually uses reserved before
  the rewrite, or a `PacketTooBigError`-only copy taken on an assumption. Both
  belong under a load test on a live node, not as a drive-by edit to the L3
  dataplath of six running exit nodes.
- **`udp_nat.go` was not restructured** for the same reason: moving
  `net.ListenUDP` and the raw write outside `n.mu` needs per-port in-flight
  tracking and a load test.
- **No Android change to the icon.** The launcher icon was already correct and
  identical to what ships; the published APKs are rebuilt from it.
- **The repository avatar.** GitHub's REST API accepts `avatar_url` on a
  repository and silently ignores it. It is a web-UI operation: Settings →
  General. `branding/icon.png` is committed and ready to upload.
- **No history rewrite, no moved tags.** Declined explicitly by the owner. The
  consequence is documented in the 2.3.0 release notes instead.
- **The tag `v2.3.2` was not cut unattended.** See Open items.

---

## Open items

1. **`release.yml` does not run, and has not since 2026-09-30.** This is
   pre-existing, not caused by the 2.3.2 work. Every run since run #5 fails
   instantly with zero jobs and no log, and GitHub reports *"This run likely
   failed because of a workflow file issue."* Releases 2.3.0 and 2.3.1 were
   therefore published **by hand**, as 2.3.2 was.

   What has been ruled out, so nobody repeats it:

   - **Not the account or the minutes.** A two-line `probe.yml` pushed to a
     throwaway branch ran `completed/success` and had its `name: Probe` parsed
     correctly. The same push that carried it also produced the failing
     `release.yml` run. Jobs do start; Actions does start.
   - **Not the bytes.** No BOM, no CRLF, no tabs, no non-printables. The file on
     GitHub is 15140 bytes and identical to the local one.
   - **Not duplicate keys.** PyYAML's default loader silently accepts them, so
     the file was re-parsed with a loader that raises on duplicates: none.
   - **Not missing files.** GitHub's contents API returns exactly one file in
     `.github/workflows/`: `release.yml`, sha `61e89b02`, 15140 bytes. The
     Actions API additionally advertises a `CI` workflow at `ci.yml` that does
     not exist in the repository at all - a stale registration, and a red
     herring.
   - **Not a YAML parse failure.** PyYAML parses it and recovers `name: Release`
     plus the tag trigger.

   The signal that it is `release.yml`'s content specifically is that GitHub
   displays its name as the *file path* rather than `Release`, while the probe's
   name came through. GitHub falls back to the path when it cannot read the
   workflow. The remaining step is empirical: bisect the file by pushing
   truncated versions and watching which one stops being rejected. It needs a
   token with `workflow` scope and roughly one push per iteration.

2. **Tag `v2.3.2` is cut and the release exists** — 5 APKs, MSI and zip,
   published by hand. See item 1 for why CI did not do it. Before the next
   release, check the tag/artifact agreement the Version step performs: the tag
   must name the version `gradle.properties` declares.

2. **The two L3 ceilings are unchanged and are a policy call, not a bug.**
   `maxUDPMappings = 256` and `ctMaxEntries = 65536` are counted node-wide, not
   per-peer, and both fail by dropping a packet with no signal to the client. A
   SYN flood or a patch-Tuesday fan-out crosses the conntrack cap, and then every
   new flow on that node fails silently until the 30 s sweep catches up. Raising
   them trades memory for a slower failure; making them per-peer is a different
   structure. Neither was changed without the owner seeing the trade.

3. Repository avatar — Settings → General, upload `branding/icon.png`.
4. Keep a third copy of `reserve/` off this machine. `D:\project` and
   `D:\Backup` are the same physical disk, so both existing copies die with it.
5. Server, none of it mine to change: `mtg.service` (NRestarts≈2 415 614,
   `status=203/EXEC`, restarts ~1/7 s, largest journal writer); `needrestart`
   absent from `override_rc`, and the apt window at 06:30 could restart all six
   units; `/var/log/btmp` 1.5 GB; `StartLimitIntervalSec=10s` against
   `RestartSec=5s`×5 = 25 s, so the limiter can never fire;
   `of-watchdog.sh` rotates only `openflux-exit`, `-2`, `-3`, leaving 4, 6 and 7
   unwatched while 1, 2, 3 get deliberate ~7 h rotations.
6. `TestSessionPrefersHigherPriorityCarrier` is genuinely flaky — 125/125 bare,
   1 failure in 30 under `-race`. Do not call it a regression without stashing.

## Throughput: what was measured, and what it rules out

Investigated 2026-10-06 after a report that download had roughly halved. **No
defect was found in the tunnel.** These are measurements, not reasoning, so do
not re-open them without something that contradicts them:

| What | Result |
|---|---|
| Server's own internet link | 91 Mbps (`speed.cloudflare.com`) |
| Node → Mail.ru relay, during a test | 1.3× the inbound rate, i.e. it pushes everything it receives |
| L3 exit path | delivers ~110% of legitimate inbound; every drop counter is 0 |
| Duplicate guard | `BenchmarkDedupeFullWindow`: 699 ns/packet, ~0.09% CPU at 1340 pkt/s |
| Every post-2.3.1 diff | message-size caps and log sanitisation; nothing on the data path |
| `maxExitTCPFlows = 1024` | l4-only; these nodes run `--mode=l3` |

Two things that looked like causes and are not:

- **`sendto` → EPERM.** Real, and 1664 of them in 25 minutes — but *every one*
  is a bare 40-byte TCP RST (`flags=0x04`) to addresses that answer the node's
  public IP. `openflux-rst-guard` drops all of them anyway, so they cannot
  account for throughput. The cause is still unknown and **not** the environment:
  a standalone Go program on the same host, same uid, same capabilities, same
  socket options, sent 547 600 identical RSTs with **zero** failures, and so did
  one with a deliberately broken TCP checksum. An equivalent Python probe failed
  100% of the time — language, not kernel; that result was discarded.
- **`koara.io` in the logs.** That is the server's own hostname, which journald
  prefixes to every line. Not a transport.

So the ceiling is the Mail.ru relay plus the client's own link, which is
consistent with 9 Mbps on WiFi and 15 Mbps on 5G.

**And the ceiling is not the thing to optimise.** The owner's constraint is that
the traffic must look like someone editing a document, because a visible data
stream gets the room banned — and everyone on that node loses it. That makes
throughput the *wrong* objective. Raising the batch ceiling from 8 KiB to 32 KiB
was tried on one node: jitter fell, which is a genuine improvement, and download
did not rise. It was reverted, because larger, faster writes are a *worse*
disguise, not a better one. Do not propose it again, and do not propose
spreading load across several documents to dodge a per-document rate limit.

What the log does show is that the relay is already pushing back:

```
07:49:33  close 1005  reconnecting in 623ms
07:49:34  close 1005  reconnecting in 1.35s
07:49:36  close 1005  reconnecting in 2.51s
07:49:39  close 1005  reconnecting in 5.34s
```

Four closures in six seconds, during a speedtest, with escalating backoff.
Close code 1005 means the peer sent no status at all. Whether that is a
per-document rate limit or the relay noticing a data channel is **not
established** — and the difference decides everything, so do not guess. If it
is the latter, the current write rate is already too high and throughput has to
come down, not up.

Note the batch loop drains its queue immediately whenever data is queued and
otherwise waits a fixed 5 ms. Under load the cadence is therefore "as fast as
the socket accepts", which is the least human-shaped thing it could be. If this
work continues, that is the line to look at — not the byte ceiling.

---

## Verifying this state yourself

```bash
git rev-parse HEAD                      # the commit you are on
git rev-list -n1 v2.3.1                 # 9e6b3a7
git status --porcelain                  # empty
grep appVersion gradle.properties       # 2.3.2
scripts/wsl-audit.sh                    # AUDIT_OK
```

HEAD is not written down here on purpose: it moves every time documentation is
committed, and a literal hash in this file is a claim that decays silently. The
one that must hold is the tag — `v2.3.1` points at the commit the released
artifacts were built from, and every commit after it is documentation or
`.gitignore`.

`scripts/wsl-audit.sh` is the project's own audit: package name, embedded core
provenance, native library build ID, launcher icon, ABI layout, timestamps and
checksums. `scripts/negative-control.sh` attacks it with 27 tampered trees to
prove the checks fire. Treat `AUDIT_OK` as the minimum, not the answer.