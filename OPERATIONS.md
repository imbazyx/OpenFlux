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

---

## First: which tree is which

This matters more than it sounds, because there are two full copies of the core
on a typical machine and they are **not** the same code.

| Path | What it is |
|---|---|
| `<project>/OpenFluxAndroid/` | **This fork.** The git repository everything ships from. |
| `<project>/` (one level up) | An upstream checkout of `p1neappleXpress/OpenFlux`. Reference only. |

They are separate repositories with separate remotes. Building from the parent
directory produces the upstream core — without this fork's fixes, including the
duplicate-packet guard — and nothing in the build will complain. The parent's
`main.go` is roughly a third the size of this fork's, and it has no
`OpenFlux/tunnel/dedupe.go` at all, which is the quickest way to tell them
apart.

Always build from the fork root.

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

`SHA256SUMS.txt` covers all seven files: the two Windows artifacts and the five
APKs, Windows first, then Android alphabetically. Verify the manifest against
the files on disk *before* uploading, not after.

### Confirming the Android core is really current

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