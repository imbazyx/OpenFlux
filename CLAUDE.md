# CLAUDE.md

Project instructions for any assistant or developer working in this tree. Read
this first, then the files it names. Everything the project knows about itself
lives in this repository — if you learned something that is not written down
here, write it down here.

## Where things are

This directory, `D:\project\OpenFluxAndroid`, is the whole project. There is no
second checkout beside it and no other tree to compare against; if one appears,
do not build from it (see `OPERATIONS.md`).

| Path | What |
|---|---|
| `OpenFlux/` | the Go core — gomobile `.aar` for Android, `.exe` for the PC |
| `shared/` | Compose Multiplatform UI, one codebase, two front ends |
| `androidApp/`, `OpenFluxPC/` | the two front ends |
| `scripts/` | build, sign, audit. Everything procedural lives here |
| `reserve/` | gitignored: backup of the release signing key, and the exit-node units |

## Read before acting

| File | What it holds |
|---|---|
| `HANDOVER.md` | what was done, what was deliberately not done, what is still open |
| `OPERATIONS.md` | how it is built, signed, published and checked, plus the house rules |
| `OPERATIONS.local.md` | credential locations and node addresses. Not committed. Read from disk |
| `TODO.local.md` | unfinished work and the owner's private decisions. Not committed |
| `PASTE-IN-NEW-CHAT.txt` | the short version, for starting a cold session |

`OPERATIONS.local.md`, `TODO.local.md` and `reserve/` are in `.gitignore` on
purpose. They are on this disk and nowhere else: do not commit them, and do not
run `git clean -xfd` without reading that file first — it deletes all three.

## Do not, without asking

- **Do not bump the version to republish a fix.** Users install over the top.
  The version is declared once, in `gradle.properties`.
- **Do not rewrite history or move a published tag.** Ask first, always.
- **Do not commit `reserve/`, `TODO.local.md` or `OPERATIONS.local.md`.** A
  signing key in the git history is extractable by anyone who clones, and
  cannot be revoked.
- **Do not claim verification you did not do.** Say what was checked and what
  was assumed.

## Things that cost a release to learn

- **The MSI is Windows-only.** `gradlew.bat :OpenFluxPC:collectDist`. From WSL,
  `packageMsi` prints `SKIPPED` and the build still says `BUILD SUCCESSFUL`.
- **A core change means rebuilding both clients.** `androidApp/libs/*.aar` and
  `OpenFluxPC/resources/windows/`. PC alone leaves the phone on the old core —
  exactly the state 2.3.1 was cut to fix.
- **Signing names do not match.** The build reads `ANDROID_KEYSTORE_*`; the
  passwords live in `$HOME/ofsign.env` as `OF_*`. Miss the mapping and the
  build does not fail, it produces an unsigned APK.
- **The duplicate-packet guard is not a proven cure.** It closes a mechanism
  captured on live traffic, but it never fired during the runs that succeeded,
  and the outage ended on its own first. Do not present it as a fix that worked.
- **Do not chase throughput without a measurement.** Every suspected cause
  inside the tunnel was measured and cleared on 2026-10-06: the L3 path loses
  nothing, the dedupe guard costs 699 ns/packet, and every diff since 2.3.1 on
  the data path is message-size caps. The ceiling is the Mail.ru relay and the
  client's own link. See *Throughput* in `HANDOVER.md` before proposing another.
- **`go test ./...` must run natively.** Under `GOOS=windows` it cannot see
  WSL's `/tmp`. `TestSessionPrefersHigherPriorityCarrier` is flaky on its own —
  check it against a stashed tree before calling it a regression.
