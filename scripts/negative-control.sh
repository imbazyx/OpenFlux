#!/usr/bin/env bash
# Negative controls for wsl-audit.sh. An audit that cannot fail is worthless, so
# prove every check actually fails on a tampered dist/.
# Everything happens on a COPY under /root; the real dist is never touched.
# Not /tmp: this host wipes /tmp mid-session, which silently deleted the copy
# between setup and the first case, and cases then failed for no real reason.
set -uo pipefail
# Derived, not hardcoded. A path to one build machine committed here breaks
# the script for everyone else, and breaks silently: it copies nothing and the
# first case then fails for a reason that has nothing to do with the audit.
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$SELF/.." && pwd)
# /tmp is deliberately avoided, see the note above.
H=${OF_CTRL_DIR:-$HOME/of-ctrl}
# Also outside $H, and derived the same way. /root is not a location that
# belongs in a committed script; the default keeps the payload out of the
# audited tree without naming anyone's home directory.
UNIV_DIR=${OF_CTRL_UNIV_DIR:-$HOME/of-ctrl-outside}

# Refuse to delete anything that does not look like a scratch directory of this
# script's own making.
#
# `${VAR:-default}` blocks an unset or empty variable and nothing else. It does
# not block a value someone chose, so `OF_CTRL_DIR=/root sh negative-control.sh`
# reached `rm -rf /root` with no prompt, on the line below, on a machine where
# /root holds ofsign.env and the build environment. The comment two lines above
# names /root as the location this script expects, so the value was already one
# slip away from the default. Every later `rm -rf "$H"` inherits the same risk.
#
# GNU rm refuses a bare "/" on its own, so the realistic damage was a home or a
# root directory rather than the filesystem - but "unlikely" is not a guard.
require_scratch_dir() {
    case "$1" in
        */of-ctrl|*/of-ctrl-outside|*/of-ctrl-*) : ;;
        *)
            echo "ОТКАЗ: $1 не похож на каталог этого скрипта и не будет удалён" >&2
            echo "Ожидается путь, оканчивающийся на of-ctrl или of-ctrl-outside." >&2
            exit 1
            ;;
    esac
    case "$1" in
        /|/root|/home|"$HOME") echo "ОТКАЗ: $1 - это не временный каталог" >&2; exit 1 ;;
    esac
}
require_scratch_dir "$UNIV_DIR"
require_scratch_dir "$H"

rm -rf "$UNIV_DIR"; mkdir -p "$UNIV_DIR"
rm -rf "$H"; mkdir -p "$H"
cp -r "$SRC/scripts" "$H/"
# cp -a keeps mtimes; plain cp would stamp everything "now" and make the
# freshness check (correctly) call every artifact stale.
cp -a "$SRC/androidApp" "$H/androidApp" 2>/dev/null
cp -a "$SRC/shared" "$H/shared" 2>/dev/null
mkdir -p "$H/gradle"
cp -a "$SRC/gradle/libs.versions.toml" "$H/gradle/" 2>/dev/null
cp -a "$SRC/androidApp/build.gradle.kts" "$H/androidApp/" 2>/dev/null
find "$H/androidApp" -maxdepth 1 -name build -type d -exec rm -rf {} + 2>/dev/null
find "$H/scripts" -name '*.sh' -exec sed -i 's/\r$//' {} +

# The fork itself needs a repository too, and not only for tidiness. The audit
# treats "cannot read HEAD" as a FAILURE rather than a skip - so a harness with
# no .git turned the baseline red, and every case after it meaningless. Copy the
# way a worktree is copied never works: clone the gitdir.
if [ -d "$SRC/.git" ]; then
  rm -rf "$H/.fork"
  if git clone -q --shared --no-checkout "$SRC/.git" "$H/.fork" 2>/dev/null; then
    # `git clone <dst>` puts the repository at <dst>/.git; move that into place.
    mv "$H/.fork/.git" "$H/.git"
    rm -rf "$H/.fork"
    git -C "$H" config --unset core.worktree 2>/dev/null
    git -C "$H" config core.bare false 2>/dev/null
    # --no-checkout leaves the index full and the worktree empty, so git calls
    # every tracked file deleted. dist/ is untracked and survives this.
    git -C "$H" reset --hard -q 2>/dev/null
  else
    echo "!! клон форка не удался, базовая линия будет красной" >&2
  fi
fi

# Restore the whole tree to HEAD. OpenFlux/ and shared/ used to be submodules
# whose .git is a FILE pointing at ../.git/modules/<name>, so `cp -a` copied the
# pointer and not its target: `git rev-parse` in the copy failed, the audit
# skipped its one provenance check, and every control below ran with that check
# already disabled - precisely the blind spot this file exists to close. It
# needed a shared-object clone grafted into place to work around that. All three
# directories are ordinary files in one repository now, so HEAD is the one
# repository and a reset is both the restore and the strongest possible check
# that the baseline is honest.
restore_tree() {
  git -C "$H" reset --hard -q 2>/dev/null
}

reset_dist() {
  rm -rf "$H/dist"
  cp -a "$SRC/dist" "$H/dist"
  touch "$H"/dist/*.apk "$H/dist/SHA256SUMS.txt"
}
# Cases that destroy the source tree cannot be undone, so they run last.
reset_src() {
  rm -rf "$H/androidApp/src" "$H/shared/src" "$H/OpenFlux"
  restore_tree
  [ -d "$H/androidApp/src" ] || cp -a "$SRC/androidApp/src" "$H/androidApp/src" 2>/dev/null
  [ -d "$H/shared/src" ]     || cp -a "$SRC/shared/src"     "$H/shared/src"     2>/dev/null
  [ -d "$H/OpenFlux" ]       || cp -a "$SRC/OpenFlux"       "$H/OpenFlux"       2>/dev/null
}

reset_dist
# One version, read from the tree the controls actually operate on. It used to
# be written out eleven times, so when the project moved to 2.0.0 every copy
# silently failed and the cases still reported green.
VER=$(ls "$H/dist" 2>/dev/null | grep -oP '^OpenFluxAndroid-\K[0-9]+(\.[0-9]+)*(?=-androidApp-)' | sort -u | head -1)
if [ -z "$VER" ]; then
  echo "FATAL: no OpenFluxAndroid-*-androidApp-*.apk under $H/dist -" >&2
  echo "       build it first: scripts/wsl-build.sh $DEFAULT_VER" >&2
  exit 2
fi
echo "контролирую версию: $VER"
# Outside $H, not inside it. This is a payload for case 1, and the audit now
# fails a tree that has untracked files - correctly. It used to live at
# $H/universal.apk and turn the baseline red for a reason that had nothing to do
# with any case under test. $H is itself a git repository, so a payload left
# inside it would be an untracked file and the baseline would be red again.
UNIV="$UNIV_DIR/of-ctrl-universal.apk"
cp -a "$H/dist/OpenFluxAndroid-$VER-androidApp-universal-release.apk" "$UNIV" \
  || { echo "FATAL: нет universal APK версии $VER" >&2; exit 2; }
run() {
  if [ -n "${OF_DEBUG:-}" ]; then
    # Show WHY, not just the verdict: a control that fails for a reason unrelated
    # to its own vector proves nothing, and that is invisible from the last line.
    bash "$H/scripts/wsl-audit.sh" "$VER" 2>&1 | grep -E 'FAIL|AUDIT' | sed 's/^/       /'
  else
    bash "$H/scripts/wsl-audit.sh" "$VER" 2>&1 | tail -1
  fi
}
# A tampered dist also needs fresh sums, or the SHA256SUMS check fires first and
# the case proves nothing about its own vector.
resum() { (cd "$H/dist" && sha256sum *.apk > SHA256SUMS.txt); }

echo "--- baseline (must be AUDIT_OK)"
echo "  $(run)"
# Leave the tree in place for inspection. A red baseline is a harness bug, not
# a finding, and the way to find out which one is to look at $H while it still
# exists - by the time the suite finishes it has been torn down.
if [ -n "${OF_STOP_AFTER_BASELINE:-}" ]; then
  echo "(OF_STOP_AFTER_BASELINE: останавливаюсь, дерево осталось в $H)"
  exit 0
fi

echo "--- 1. five copies of universal, sums regenerated"
reset_dist
for a in "$H"/dist/*.apk; do cp "$UNIV" "$a"; done
resum
echo "  $(run)"

echo "--- 2. x86_64 replaced by a copy of x86, sums regenerated"
reset_dist
cp -a "$H/dist/OpenFluxAndroid-$VER-androidApp-x86-release.apk" \
      "$H/dist/OpenFluxAndroid-$VER-androidApp-x86_64-release.apk"
resum
echo "  $(run)"

echo "--- 3. one byte appended, sums regenerated"
reset_dist
printf 'X' >> "$H/dist/OpenFluxAndroid-$VER-androidApp-arm64-v8a-release.apk"
resum
echo "  $(run)"

echo "--- 4. SHA256SUMS lists only 4 of 5"
reset_dist
(cd "$H/dist" && sha256sum *.apk | grep -v x86-release > SHA256SUMS.txt)
echo "  $(run)"

echo "--- 4b. universal renamed to arm64-v8a, sums regenerated"
reset_dist
mv "$H/dist/OpenFluxAndroid-$VER-androidApp-universal-release.apk" \
   "$H/dist/OpenFluxAndroid-$VER-androidApp-arm64-v8a-release.apk"
resum
echo "  $(run)"

echo "--- 6. wrong version argument"
reset_dist
echo "  $(bash "$H/scripts/wsl-audit.sh" 9.9.9 2>&1 | tail -1)"

echo "--- 7. non-numeric version argument"
# This one has to compare the exit code itself. It used to print it and move
# on, so an audit that had stopped rejecting bad versions would have produced a
# tidy "rc=0 (expect 2)" line and the run would still have finished green - it
# was the only control of the 27 that could not fail, and it is why 27 headers
# used to produce only 26 reds. A control that reports without asserting is
# decoration.
bash "$H/scripts/wsl-audit.sh" abc >/dev/null 2>&1
rc7=$?
if [ "$rc7" -eq 2 ]; then
  echo "  КРАСНЫЙ rc=$rc7 (ожидался 2) - аудит отверг мусорную версию"
else
  echo "  ЗЕЛЁНЫЙ rc=$rc7 (ожидался 2) - аудит ПРИНЯЛ нечисловую версию, проверка мертва"
fi
# Same for a version that is X.Y.Z-shaped but wrong, and for the valid one: a
# script that rejects everything proves the rejection by rejecting something
# legitimate too.
bash "$H/scripts/wsl-audit.sh" "$VER-rc1" >/dev/null 2>&1
rc7b=$?
bash "$H/scripts/wsl-audit.sh" "$VER" >/dev/null 2>&1
rc7c=$?
echo "  $VER-rc1 -> $rc7b (ожидался 2), $VER -> $rc7c (ожидался 0)"
[ "$rc7" -eq 2 ] && [ "$rc7b" -eq 2 ] && [ "$rc7c" -eq 0 ] \
  || echo "  ^ КОНТРОЛЬ 7 НЕ СРАБОТАЛ: отказ и на мусоре, и на живом прогоне обязателен"

echo "--- 12. wrong APK name set (5 files, one renamed)"
reset_dist
mv "$H/dist/OpenFluxAndroid-$VER-androidApp-x86-release.apk" \
   "$H/dist/OpenFluxAndroid-$VER-androidApp-x86_64-release.apk.renamed.apk"
resum
echo "  $(run)"

echo "--- 8. every classes*.dex replaced by a text file of the marker strings"
# The strings land there verbatim, so the "our strings are in the dex" check still
# passes; only a real dex header and a real size can catch this.
reset_dist
sha=$(git -C "$H" rev-parse HEAD 2>/dev/null)
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  rm -f "$T2"/classes*.dex
  printf 'Lio/openflux/android/platform/AppSelectionActivity;per-app rule: none of;cannot allow;openflux-core@%s' "$sha" > "$T2/classes.dex"
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 9. every libgojni.so replaced by a text file of the marker strings"
reset_dist
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  find "$T2/lib" -name libgojni.so -exec sh -c \
    'printf "M-DOCS\nAuth OK\nconnectToDoc\none-way channel\nsession rotation\ndoc key rotated\nrx ping\n" > "$1"' _ {} \;
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 10. five copies of the universal payload under per-ABI names"
# Signing is deterministic, so byte-distinctness is bought with a per-file pad.
reset_dist
for a in "$H"/dist/*.apk; do
  cp "$UNIV" "$a"
  T2=$(mktemp -d); mkdir -p "$T2/assets"; echo "pad$RANDOM" > "$T2/assets/auditpad.txt"
  (cd "$T2" && zip -q "$a" assets/auditpad.txt); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 11. provenance check disabled: the only .git in the tree removed"
reset_dist
# Used to remove the CORE's .git, which existed only because OpenFlux/ was a
# submodule. There is one repository now, so the honest version of this attack
# is to take that repository away - the audit must refuse to certify anything
# when it cannot read the commit the APK was built from.
mv "$H/.git" "$H/.git.hidden"
echo "  $(run)"
mv "$H/.git.hidden" "$H/.git"

echo "--- 13. uncommitted edit inside the shipped core (mtime backdated)"
reset_dist
echo "// tamper" >> "$H/OpenFlux/utils/logging.go"
touch -d "2020-01-01" "$H/OpenFlux/utils/logging.go" 2>/dev/null
echo "  $(run)"
git -C "$H" checkout -- OpenFlux/utils/logging.go 2>/dev/null

# The cases below cover the checks added after the red-team report. Each one is
# a bypass that returned AUDIT_OK on a corrupted tree; a check with no case
# against it is a check that quietly stops working.
echo "--- 14. x86 ABI renamed to mips (decoy substring used to satisfy the check)"
reset_dist
T2=$(mktemp -d)
(cd "$T2" && unzip -o -q "$H/dist/OpenFluxAndroid-$VER-androidApp-x86-release.apk")
mkdir -p "$T2/res/zdecoy/lib/x86"; echo decoy > "$T2/res/zdecoy/lib/x86/keep.txt"
mv "$T2/lib/x86" "$T2/lib/mips"
(cd "$T2" && zip -qr "$H/dist/OpenFluxAndroid-$VER-androidApp-x86-release.apk" .)
rm -rf "$T2"
resum
echo "  $(run)"

echo "--- 15. stray file in dist/"
reset_dist
echo "notes" > "$H/dist/extra-notes.txt"
echo "  $(run)"
rm -f "$H/dist/extra-notes.txt"

echo "--- 16. every classes*.dex replaced by a padded string bag"
reset_dist
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  find "$T2" -name 'classes*.dex' -delete
  { printf 'dex\n035\0'; head -c 2500000 /dev/zero | tr '\0' 'A';
    printf 'openflux-core@%s;per-app rule: none of;cannot allow;Lio/openflux/' "$sha"; } > "$T2/classes.dex"
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 17. every libgojni.so replaced by a padded ELF-magic string bag"
reset_dist
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  find "$T2/lib" -name libgojni.so | while read -r f; do
    { printf '\177ELF'; head -c 1500000 /dev/zero | tr '\0' 'B';
      printf 'M-DOCS;Auth OK;connectToDoc;one-way channel;session rotation;doc key rotated;rx ping;Java_io_openflux;_cgoexp;'; } > "$f"
  done
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 18. Kotlin and Go sources hollowed out"
reset_dist
find "$H/androidApp/src" "$H/shared/src" -name '*.kt' -delete 2>/dev/null
find "$H/OpenFlux" -name '*.go' -not -path '*/.git/*' -exec sh -c 'echo "package stub" > "$1"' _ {} \;
echo "  $(run)"
restore_tree
# rm -rf first, always. `cp -a src src` where the destination already exists
# copies INTO it, creating src/src and leaving the .kt files deleted - so every
# case after this one "failed" with missing sources rather than with its own
# defect. Two controls were green for the wrong reason and nobody noticed,
# which is this file's whole failure mode applied to the file itself.
rm -rf "$H/androidApp/src" "$H/shared/src"
cp -a "$SRC/androidApp/src" "$H/androidApp/src"
cp -a "$SRC/shared/src" "$H/shared/src"
# Prove the restore worked. If the tree is not back, every later case inherits a
# broken baseline and its AUDIT_FAILED means nothing.
nkt=$(find "$H/androidApp/src" "$H/shared/src" -name '*.kt' 2>/dev/null | wc -l)
if [ "$nkt" -lt 60 ]; then
  echo "   СТОП: после восстановления только $nkt .kt — дальнейшие контроли бессмысленны"
  exit 1
fi

echo "--- 19. launcher icon removed, junk res xml carries the colour bytes"
reset_dist
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  icon=$(find "$T2/res" -maxdepth 1 -name '*.xml' -size +100c | head -1)
  rm -f "$icon"
  printf '\xd9\x2f\x8b\xff\x3c\x1b\xe0\xff' > "$T2/res/zz-junk.xml"
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 20. a lateinit field shadowed by a local val (shipped once already)"
reset_dist
F="$H/androidApp/src/main/kotlin/io/openflux/android/platform/AppSelectionActivity.kt"
cp "$F" "$F.bak"
# Say so loudly if the tamper did not land. A control that silently did nothing
# reports whatever verdict the tree already had, and looks like a pass.
if [ ! -f "$F" ]; then
  echo "   СТОП: $F не существует — контроль ничего не проверяет"
elif ! sed -i 's/^\( *\)resetBtn = Button(this)/\1val resetBtn = Button(this)/' "$F"; then
  echo "   СТОП: sed не отработал"
elif ! grep -q 'val resetBtn' "$F"; then
  echo "   СТОП: подмена не применилась — контроль ничего не проверяет"
fi
echo "  $(run)"
mv "$F.bak" "$F" 2>/dev/null

echo "--- 21. libgojni.so cut down to a stub-sized blob (real ones are 15-16 MB)"
reset_dist
for a in "$H"/dist/*.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  find "$T2/lib" -name libgojni.so | while read -r f; do
    head -c 1400000 "$f" > "$f.cut"
    { cat "$f.cut"
      printf 'M-DOCS;Auth OK;connectToDoc;one-way channel;session rotation;doc key rotated;rx ping;Java_io_openflux;_cgoexp;'
      head -c 600000 /dev/zero | tr '\0' 'C'; } > "$f"
    rm -f "$f.cut"
  done
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

echo "--- 22. cert pin overridden via OF_EXPECTED_CERT (must NOT be a warning)"
reset_dist
echo "  $(OF_EXPECTED_CERT=$(printf '%064d' 3) run)"

echo "--- 23. Kotlin tampered, mtime backdated"
reset_dist
F="$H/androidApp/src/main/kotlin/io/openflux/android/core/PacketTunnel.kt"
cp "$F" "$F.bak"; echo "// tampered" >> "$F"
touch -d 2020-01-01 "$F"; touch -d 2030-01-01 "$H"/dist/*.apk
echo "  $(run)"
mv "$F.bak" "$F"; touch "$F"

echo "--- 24. all artifacts dated in the future (mtime as deleted evidence)"
reset_dist
touch -d 2030-01-01 "$H"/dist/*.apk
echo "  $(run)"

echo "--- 25. a foreign-arch library dropped into a single-ABI APK"
reset_dist
for a in "$H"/dist/*arm64-v8a-release.apk; do
  T2=$(mktemp -d); unzip -o -q "$a" -d "$T2"
  mkdir -p "$T2/lib/x86"; cp "$T2/lib/arm64-v8a/libgojni.so" "$T2/lib/x86/libsupport.so"
  (cd "$T2" && zip -qr "$a" .); rm -rf "$T2"
done
resum
echo "  $(run)"

# --- source-destroying cases last: irreversible, and everything above needs the
# --- trees intact.
echo "--- 5. source tree removed"
reset_dist
reset_src
rm -rf "$H/androidApp/src" "$H/shared/src" "$H/OpenFlux"
echo "  $(run)"

echo "--- 5b. source tree replaced by a single old file"
# `-e` plus `find -type f` accepted a regular file standing where a directory
# belongs, so the scan still counted one "source" and passed.
reset_dist
reset_src
rm -rf "$H/androidApp/src"
touch -d '2020-01-01' "$H/androidApp/src"
echo "  $(run)"

rm -rf "$H"
echo "--- real dist untouched:"
(cd "$SRC/dist" && sha256sum -c --quiet SHA256SUMS.txt && echo "  intact")
