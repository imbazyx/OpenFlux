# Handover

State of the project as of 2026-10-02, written for whoever works on it next —
including an assistant starting from a cold session with no memory of any of
this.

Read this file first. Then `OPERATIONS.md` (how to build and publish).
`OPERATIONS.local.md` is not in the repository and holds the credential
locations and the node addresses.

---

## Read this if you are a new session

Copy `PASTE-IN-NEW-CHAT.txt` into the first message. It names the directory,
the three files to read, and the two things not to do.

## Where things are

| | |
|---|---|
| Project root | `D:\project\OpenFluxAndroid` — the only tree with the project in it |
| Repository | `git@github.com:imbazyx/OpenFlux.git`, branch `main` |
| Version | 2.3.1, declared once in `gradle.properties` |

There was once a second checkout of upstream `p1neappleXpress/OpenFlux` beside
this one. The fork also used to live one directory deeper, inside *that* tree.
Both were git repositories, so building from the wrong one produced the upstream
core — no duplicate-packet guard, none of this fork's fixes — with nothing in the
build complaining. The upstream checkout has since been deleted; everything the
project needs is inside this directory.

---

## Current state

| | |
|---|---|
| Branch | `main`, in step with `origin/main` |
| Tag `v2.3.1` | `f73b98c` |
| Latest release | `OpenFlux 2.3.1`, 8 assets |
| Exit nodes | five, all `active` |

**The tag is behind HEAD, and that is correct.** Every commit since `v2.3.1` is
`.gitignore` or documentation — no source, no build input. The property that
matters holds: `v2.3.1` points at the commit the released artifacts were built
from. Verify it with:

```bash
git rev-list -n1 v2.3.1          # must equal the commit in the release notes
git log --oneline v2.3.1..HEAD   # docs only, nothing that affects a build
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
`tunnel/tunnel.go`. Five tests in `tunnel/dedupe_test.go`.

Dropping is the safe direction: TCP already treats a lost segment as normal and
retransmits on its own timer, so it cannot invent a fault that did not exist.

**Honest limit, and it matters:** the duplication is intermittent. It had
already stopped on its own before the guard was exercised. Across the runs that
*succeeded*, the drop counter logged zero. The guard closes a mechanism proven
on live traffic; it is not a demonstrated cure for that particular outage.
During the A/B the old binary actually scored 6/6 while the fix scored 3/6 —
because the relay had recovered, not because the fix failed.

### 3. Exit nodes

Five units run `--role=exit` from one binary, `/usr/local/bin/openflux`:
four in `l4`, the fifth (`exit5`) in `l3` on a Mail.ru document key.

`l3` needs kernel RST suppression or every connection dies right after the
handshake. A scoped rule matching only RSTs sourced from the egress address was
added, rather than the host-wide one the project README describes — that one
changes behaviour for the whole machine, Docker included, and makes closed ports
look filtered.

Verified by a series: 4/4 successful, RST count zero.

**Open risk: that iptables rule does not survive a reboot.** The unit will
start, report itself healthy, and refuse new connections again.

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

- **No Android change to the icon.** The launcher icon was already correct and
  identical to what ships; the published APKs are rebuilt from it.
- **The repository avatar.** GitHub's REST API accepts `avatar_url` on a
  repository and silently ignores it. It is a web-UI operation: Settings →
  General. `branding/icon.png` is committed and ready to upload.
- **No persistence for the iptables rule.** It would need to move into a unit
  or `rc.local`, and that is a decision about the machine, not the code.
- **No history rewrite, no moved tags.** Declined explicitly by the owner. The
  consequence is documented in the 2.3.0 release notes instead.

---

## Open items

1. Repository avatar — Settings → General, upload `branding/icon.png`.
2. Make the RST rule survive a reboot.
3. Keep a third copy of `reserve/` off this machine. `D:\project` and
   `D:\Backup` are the same physical disk, so both existing copies die with it.

---

## Verifying this state yourself

```bash
git rev-parse HEAD                      # the commit you are on
git rev-list -n1 v2.3.1                 # f73b98c, unchanged by any commit since
git status --porcelain                  # empty
grep appVersion gradle.properties       # 2.3.1
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