# Operations

How this project is actually built, signed, published and checked. Written down
because each of these cost a rebuild to learn, and none of it is visible from
the code.

Nothing here is a secret. The signing key and its password are deliberately
absent, for the reason `SECURITY.md` gives: a key in the repository is a key
anyone who clones it can sign your updates with. Where they live and how to
confirm you have the right one is here; what they are is not.

Machine-specific paths, the exit-node inventory and the credentials' locations
are in `OPERATIONS.local.md`, which is not committed.

For project state — what has been done, what was deliberately left undone, and
what is still open — read `HANDOVER.md`. This file is how to build; that one is
what to build.

---

## First: which tree is which

There used to be two full copies of the core on this machine, and they were not
the same code. The upstream checkout has since been deleted, so the trap is
closed — but the knowledge is kept, because it cost a release to acquire, and
because a fresh `git clone` still lands wherever it lands.

| Path | What it is |
|---|---|
| `<drive>:/project/OpenFluxAndroid/` | **This fork, and the only tree that has it.** The git repository everything ships from. |

The fork used to live one directory deeper, inside a checkout of the upstream
core. Both were separate git repositories with separate
remotes. Building from the parent produced the upstream core — no duplicate-packet
guard, none of this fork's fixes — and nothing in the build complained. The tell
was `OpenFlux/tunnel/dedupe.go`: present here, absent there. The parent's
`main.go` was also roughly a third the size of this fork's.

If a copy of upstream's core ever reappears next to this one, do not build from
it. Build from the fork root.

---

## Things that must never be committed

`.gitignore` carries the guard, but the reasoning matters more than the line,
because both of these are inside the working tree now rather than one level
outside it:

- `reserve/openflux-release.jks` — a backup of the release signing key. A
  signing key in the history is extractable by anyone who clones, and a key
  that signs your updates is worse than no key at all: it cannot be revoked.
- `OPERATIONS.local.md`, `TODO.local.md` — node addresses and the owner's
  private decisions.

The exit-node unit files under `reserve/exit-nodes/` are not secret, but they
name document URLs that are, so the folder is ignored whole rather than
partly.

---

## Toolchain

One script installs everything: Go, JDK, the Android SDK, NDK 27, gomobile.

```bash
scripts/wsl-toolchain.sh      # -> TOOLCHAIN_OK
```

It writes `$HOME/ofbuild.env` with `ANDROID_HOME`, `ANDROID_NDK_HOME` and
`PATH`. Source that before any Android step; without `ANDROID_NDK_HOME`,
`build-android-core.sh` stops with *Android SDK not found*.

`gomobile init` does **not** fetch the NDK any more — it exits 0 having done
nothing, which looks like success. Use the script above.

---

## Signing

`wsl-build.sh` refuses to produce an unsigned release APK, and
`wsl-audit.sh` fails any `dist/` whose certificate differs from the one named
in `SECURITY.md`. Both are deliberate and both have caught things.

The credentials live in `$HOME/ofsign.env` (mode 600), and the names in that
file are **not** the names the build reads. Nothing maps them for you:

| In `ofsign.env` | The build reads |
|---|---|
| `OF_KEYSTORE` | `ANDROID_KEYSTORE_FILE` |
| `OF_KS_PASS` | `ANDROID_KEYSTORE_PASSWORD` |
| `OF_KS_ALIAS` | `ANDROID_KEY_ALIAS` |
| — (same value) | `ANDROID_KEY_PASSWORD` |

Miss this and the build does not fail: it produces an unsigned APK, or the
build's own guard stops it. Either way you will not notice unless you read the
output.

`wsl-build.sh` sources `ofsign.env` itself and exports the `ANDROID_*` names, so
the normal path is just to run the script. Anything built by hand has to do the
mapping above first.

Confirm the key is the right one before trusting a build:

```
apksigner verify --print-certs <apk>
```

prints `certificate SHA-256 digest:` — note the word order. The digest must
equal the fingerprint in `SECURITY.md`. A build signed with a different key
cannot update an installed copy, and no other check in the build would notice.

---

## What builds where

| Artifact | Host | Command |
|---|---|---|
| Core for the PC | WSL | `scripts/build-pc-core.sh` |
| Core for Android (`.aar`, 4 ABIs) | WSL | `scripts/build-android-core.sh` |
| Five signed APKs + `SHA256SUMS.txt` | WSL | `scripts/wsl-build.sh` → `dist/` |
| `*.msi` **and** `*.zip` | **Windows** | `gradlew.bat :OpenFluxPC:collectDist` |

### The MSI is a Windows-only artifact

This is the one that costs an hour if forgotten. Run the PC build from WSL and
`:OpenFluxPC:packageMsi` prints `SKIPPED` — silently, with `BUILD SUCCESSFUL`
and a zip sitting in `dist/`. Compose Desktop only creates installers on a
Windows host. The MSI is the download most people take, so a WSL-only build is
a release missing its main file while reporting success.

`collectDist` also copies whatever else it finds in the build directory, so
`OpenFluxPC/dist/` accumulates `2.2.0` and `2.3.0` artifacts between runs.
Delete them before publishing; nothing removes them for you.

---

## When the Gradle daemon crashes

```
Gradle build daemon disappeared unexpectedly
JVM crash log found: hs_err_pid<NNN>.log
#  SIGSEGV ... G1ParScanThreadState::trim_queue_to_threshold
```

That is a crash in the G1 garbage collector of the JDK Gradle auto-provisioned
for itself (Zulu 21), typically during `lintVitalAnalyzeRelease`. It is not
caused by the project, and it is usually transient — **re-run the build before
changing anything**. The first build of 2.3.1 died this way and the second
succeeded untouched.

If it keeps happening, `-XX:+UseSerialGC` in `org.gradle.jvmargs` is the
workaround. Do not commit that: it papers over an environment problem, and the
project should not carry it for everyone.

The project itself targets JDK 17 (`jvmToolchain(17)`); only Gradle's own
daemon runs on 21.

---

## Checking a release before it goes out

The chain that has to hold: **the tag, the commit and the artifacts are the same
thing.** In 2.3.0 they were not — the tag stayed at the commit the version was
cut at while the files were re-uploaded from later commits, four times. The
notes had to explain the discrepancy. 2.3.1 exists to end that.

```bash
# 1. The project's own audit. Checks package name, core provenance, Go build ID,
#    launcher icon, ABI layout, timestamps and checksums.
scripts/wsl-audit.sh          # -> AUDIT_OK

# 2. Tag and build commit agree
git rev-parse origin/main
git rev-list -n1 v2.3.1

# 3. What is published is what was built. The CDN caches release assets by URL,
#    so a plain download can return a previous upload - always add ?cb=<random>
#    when re-fetching something you just uploaded.
curl -fsSL "<asset-url>?cb=$(date +%s%N)" -o file
sha256sum file                # compare against SHA256SUMS.txt
```

`SHA256SUMS.txt` covers the five APKs and nothing else. `wsl-build.sh` and
`release.yml` both run `sha256sum *.apk`, and `release.yml` has only an Android
job, so **no Windows artifact ever gets a line** - the two Windows files are
uploaded by hand after the fact and are not in the manifest. This text used to
claim seven files, Windows first. That was never true, and the desktop updater
is built on the gap: `publishedSha256` finds no MSI line, installs the installer
without checking it, and now says so in the log.

Adding the line by hand is not enough on its own - `wsl-build.sh` empties
`dist/` and the workflow truncates the manifest with `>`, so anything not
present when that runs is gone. The real fix is a desktop job in `release.yml`
that builds the MSI, stages it in `dist/` before line 136, and is covered by
the same manifest. Until that exists, check the Windows artifacts by hand:

```
sha256sum OpenFluxPC/dist/OpenFlux-<version>.msi
```

### Making the desktop installer actually get verified

Until a desktop job exists in `release.yml`, the MSI line has to be added by
hand — and because the workflow truncates the manifest with `>`, the manifest
has to be **re-uploaded** as well, otherwise the corrected copy never reaches
the release and the client keeps finding nothing:

```
tag=v2.3.2
sha256sum "OpenFluxPC/dist/OpenFlux-${tag#v}.msi" >> dist/SHA256SUMS.txt
gh release upload "$tag" dist/SHA256SUMS.txt --clobber
```

`--clobber` is required: the asset already exists from `gh release create`, and
without it the upload fails and the release keeps the manifest that is missing
the MSI line — which looks exactly like not having done the step at all.

That is what makes `publishedSha256` return a hash instead of null, which in
turn is what makes `installUpdate` compare the installer before handing it to
`msiexec`. Until it is done, `expected` is null on **every** install, the
comparison branch never executes, and `HandedOff(verified = false)` is reported
on 100% of them.

Verify the manifest against the files on disk *before* uploading, not after.

## Three workflows in this repository have never run

`ci.yml`, `node-release.yml` and a second `release.yml` live in
`OpenFlux/.github/workflows/`. GitHub executes workflows only from
`.github/workflows/` **at the repository root**; everything in a subdirectory is
a plain folder of YAML files that nothing reads. So the core's CI, its node
releases and its release workflow are dormant, and have always been.

Checked against the published tree, not assumed:

```bash
curl -s https://codeload.github.com/imbazyx/OpenFlux/tar.gz/refs/heads/main \
  | tar -tz | grep '\.github/workflows'
```

Only `.github/workflows/release.yml` — the one for the Android/PC clients — is
live. The other three appear in the listing and are still never executed.

**What this costs.** No job can build the next node release, and nothing can
re-pin the three `SHA_*` values in `OpenFlux/deploy/node-install.sh` when the
core changes. A node release has to be produced by hand. The published asset is
still consistent — `node-v1.0.0/openflux-linux-amd64` is present and its
SHA-256 matches the pin — but nothing keeps it that way.

**To fix.** Move them to the root `.github/workflows/`, renaming the second
`release.yml` (same `name: Release`, so it would collide) and deciding what
`working-directory` each expects, since they were written assuming they run
inside `OpenFlux/`. Do not enable `ci.yml` casually: it triggers on `push`, so it
would run on every commit for the first time, and it has never been exercised
against the current toolchain.

## Three workflows in this repository have never run

The `.aar` is a build artifact and is not committed, so the version stamp alone
does not prove the phone carries the core you just built. Compare the shipped
library against the previous release and look for a string from the change:

```bash
unzip -p <apk> lib/arm64-v8a/libgojni.so > lib.so
strings lib.so | grep "dropped duplicate packet"
```

A size change proves nothing on its own — a rebuild shifts bytes either way.
The string is the evidence.

---

## House rules

These have been decided, not assumed. Breaking one costs the user something
that cannot be recovered automatically.

- **Do not bump the version to re-publish a fix.** The user installs over the
  top. 2.3.0 was uploaded four times under one number, which is what produced
  the tag/content mismatch this document keeps warning about.
- **Do not rewrite history or move a published tag.** An annotated tag is
  cheap to move right up until someone has fetched it; after that, it is a
  rewrite. Ask first, always.
- **Do not commit scratch scripts.** Anything left in the user's home
  directory is not in this repository and will not survive a reinstall. If a
  procedure is worth repeating, it belongs in `scripts/` with its reasoning in
  the comment above the command.
- **Never claim verification without it.** During the 2.3.0 work a stale
  extracted artifact read as a shipping defect, two packet counts were wrong
  because the log format was `[ACK PSH]` and not `[PSH]`, and an A/B test came
  within one observation of reporting a fix that had not fixed anything. State
  what was checked and what was assumed.
- **Say what a fix does not prove.** The duplicate-packet guard closes a
  mechanism captured on live traffic; it never fired during the runs that
  succeeded, and the outage ended on its own first. Both facts belong in the
  notes.

## Cores inside the clients differ, deliberately

`androidApp/libs/openflux.aar` is gitignored, built by
`build-android-core.sh`, and read by `wsl-build.sh` into the APKs.
`OpenFluxPC/resources/windows/` holds the bare `.exe` and is likewise produced,
not committed. Both come from the same `OpenFlux/` tree, so a core change means
**rebuilding both** — the PC core alone leaves the phone on the old one, which
is exactly the state 2.3.1 was cut to fix.

---

## The Go tests need a native run

`go test ./...` under `GOOS=windows` fails with
`testing: open .../testlog.txt`: the test binary is Windows, and `/tmp` is a
WSL mount it cannot see. Unset `GOOS GOARCH CGO_ENABLED` and run it natively.

`TestSessionPrefersHigherPriorityCarrier` in the transport package is flaky
independently of any change here — it passed 5/5 with and without the current
work. Confirm against a stashed tree before believing it is a regression.