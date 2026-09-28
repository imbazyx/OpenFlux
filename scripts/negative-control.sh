#!/usr/bin/env bash
# Negative controls for wsl-audit.sh. An audit that cannot fail is worthless, so
# prove every check actually fails on a tampered dist/.
# Everything happens on a COPY under /root; the real dist is never touched.
# Not /tmp: this host wipes /tmp mid-session, which silently deleted the copy
# between setup and the first case, and cases then failed for no real reason.
set -uo pipefail
SRC=/mnt/d/project/OpenFlux/OpenFluxAndroid
H=/root/of-ctrl
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

# A submodule's .git is a FILE pointing at ../.git/modules/<name>, so `cp -a` of
# the submodule copies the pointer and not its target: `git rev-parse` in the copy
# fails and the audit skips its one provenance check. Every control would then run
# with that check already disabled, which is exactly the blind spot this file
# exists to close. Clone --shared --no-checkout instead: a real HEAD (so the
# baseline passes for the right reason), no checkout, objects borrowed not copied.
# Clone from the GITDIR, not the worktree - the submodule's own .git is a FILE
# pointing elsewhere and `git clone <worktree>` refuses it.
install_core() {
  rm -rf "$H/OpenFlux" "$H/.core"
  cp -a "$SRC/OpenFlux" "$H/OpenFlux" 2>/dev/null
  rm -f "$H/OpenFlux/.git"
  # `git clone <dst>` puts the repository at <dst>/.git, so move THAT, not <dst>
  # - moving the outer directory yields OpenFlux/.git/.git, which git does not
  # recognise, so every control runs with the provenance check already disabled.
  if git clone -q --shared --no-checkout "$SRC/.git/modules/OpenFlux" "$H/.core" 2>/dev/null; then
    mv "$H/.core/.git" "$H/OpenFlux/.git"
    # The clone inherited core.worktree from the submodule's gitdir, pointing at
    # ../../../OpenFlux - a path that means nothing in the copy.
    git -C "$H/OpenFlux" config --unset core.worktree 2>/dev/null
    git -C "$H/OpenFlux" config core.bare false 2>/dev/null
  else
    echo "!! clone не удался, контроли пойдут с отключённой проверкой HEAD" >&2
    git clone --shared --no-checkout "$SRC/.git/modules/OpenFlux" "$H/.core" 2>&1 | head -5 >&2
  fi
  rm -rf "$H/.core"
  if [ -n "${OF_DEBUG:-}" ]; then
    echo "!! install_core: HEAD копии = $(git -C "$H/OpenFlux" rev-parse HEAD 2>&1)" >&2
  fi
}
install_core

reset_dist() {
  rm -rf "$H/dist"
  cp -a "$SRC/dist" "$H/dist"
  touch "$H"/dist/*.apk "$H/dist/SHA256SUMS.txt"
}
# Cases that destroy the source tree cannot be undone, so they run last.
reset_src() {
  rm -rf "$H/androidApp/src" "$H/shared/src" "$H/OpenFlux"
  cp -a "$SRC/androidApp/src" "$H/androidApp/src" 2>/dev/null
  cp -a "$SRC/shared/src" "$H/shared/src" 2>/dev/null
  install_core
}

reset_dist
UNIV=$H/universal.apk
cp -a "$H/dist/OpenFluxAndroid-1.2.0-androidApp-universal-release.apk" "$UNIV"
run() {
  if [ -n "${OF_DEBUG:-}" ]; then
    # Show WHY, not just the verdict: a control that fails for a reason unrelated
    # to its own vector proves nothing, and that is invisible from the last line.
    bash "$H/scripts/wsl-audit.sh" 1.2.0 2>&1 | grep -E 'FAIL|AUDIT' | sed 's/^/       /'
  else
    bash "$H/scripts/wsl-audit.sh" 1.2.0 2>&1 | tail -1
  fi
}
# A tampered dist also needs fresh sums, or the SHA256SUMS check fires first and
# the case proves nothing about its own vector.
resum() { (cd "$H/dist" && sha256sum *.apk > SHA256SUMS.txt); }

echo "--- baseline (must be AUDIT_OK)"
echo "  $(run)"

echo "--- 1. five copies of universal, sums regenerated"
reset_dist
for a in "$H"/dist/*.apk; do cp "$UNIV" "$a"; done
resum
echo "  $(run)"

echo "--- 2. x86_64 replaced by a copy of x86, sums regenerated"
reset_dist
cp -a "$H/dist/OpenFluxAndroid-1.2.0-androidApp-x86-release.apk" \
      "$H/dist/OpenFluxAndroid-1.2.0-androidApp-x86_64-release.apk"
resum
echo "  $(run)"

echo "--- 3. one byte appended, sums regenerated"
reset_dist
printf 'X' >> "$H/dist/OpenFluxAndroid-1.2.0-androidApp-arm64-v8a-release.apk"
resum
echo "  $(run)"

echo "--- 4. SHA256SUMS lists only 4 of 5"
reset_dist
(cd "$H/dist" && sha256sum *.apk | grep -v x86-release > SHA256SUMS.txt)
echo "  $(run)"

echo "--- 4b. universal renamed to arm64-v8a, sums regenerated"
reset_dist
mv "$H/dist/OpenFluxAndroid-1.2.0-androidApp-universal-release.apk" \
   "$H/dist/OpenFluxAndroid-1.2.0-androidApp-arm64-v8a-release.apk"
resum
echo "  $(run)"

echo "--- 6. wrong version argument"
reset_dist
echo "  $(bash "$H/scripts/wsl-audit.sh" 9.9.9 2>&1 | tail -1)"

echo "--- 7. non-numeric version argument"
bash "$H/scripts/wsl-audit.sh" abc >/dev/null 2>&1
echo "  rc=$? (expect 2)"

echo "--- 12. wrong APK name set (5 files, one renamed)"
reset_dist
mv "$H/dist/OpenFluxAndroid-1.2.0-androidApp-x86-release.apk" \
   "$H/dist/OpenFluxAndroid-1.2.0-androidApp-x86_64-release.apk.renamed.apk"
resum
echo "  $(run)"

echo "--- 8. every classes*.dex replaced by a text file of the marker strings"
# The strings land there verbatim, so the "our strings are in the dex" check still
# passes; only a real dex header and a real size can catch this.
reset_dist
sha=$(git -C "$H/OpenFlux" rev-parse HEAD 2>/dev/null)
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

echo "--- 11. provenance check disabled by removing the submodule's git"
reset_dist
rm -rf "$H/OpenFlux/.git"
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
