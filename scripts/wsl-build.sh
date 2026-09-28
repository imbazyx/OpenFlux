#!/usr/bin/env bash
# Builds OpenFluxAndroid from the Windows checkout inside WSL (ext4, not /mnt/d):
#   1. copy the tree out of the Windows filesystem
#   2. gomobile AAR from the patched OpenFlux core (scripts/build-android-core.sh)
#   3. shared:jvmTest, exactly as CI runs it
#   4. signed release APK at the requested version
# The APK is copied back to the Windows checkout under dist/.
set -euo pipefail

# Version must be strict X.Y.Z, with each part bounded. build.gradle.kts derives
# versionCode as major*10000 + minor*100 + patch, so unbounded parts collide:
# 1.100.0 and 2.0.0 would both be 20000, and 1.2.100 / 1.3.0 both 10300.
# "1.2", "1.2.0" and "1.2.0-rc1" all collapse to 10200 too, and Android refuses an
# update whose versionCode did not grow; a non-numeric version ships as 0.
DEFAULT_VER=1.2.0
VER=${1:-$DEFAULT_VER}
[[ $VER =~ ^[0-9]{1,3}\.[0-9]{1,2}\.[0-9]{1,2}$ ]] || {
  echo "version '$VER' is not X.Y.Z with bounded parts (e.g. $DEFAULT_VER)" >&2; exit 1; }
# Derive the checkout from this script's own location, so moving the project
# folder never breaks the build (scripts/ -> the fork's root).
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$SELF/.." && pwd)
# Not a hard error: the /mnt/* assumption is only about speed (reading a Windows
# mount is slow) and line endings, neither of which the staging handles anyway.
case "$SRC" in /mnt/*) ;; *) echo "note: $SRC is not a /mnt path; building anyway" >&2 ;; esac
WORK=$HOME/build/OpenFluxAndroid
KEY=$HOME/build/openflux-local.jks
KS_PASS=${KS_PASS:-openflux-local}
KS_ALIAS=${KS_ALIAS:-openflux}

[ -f "$HOME/ofbuild.env" ] || { echo "run wsl-toolchain.sh first" >&2; exit 1; }
# shellcheck disable=SC1090
source "$HOME/ofbuild.env"

# The real identity of the hand-patched core, read from the checkout BEFORE
# staging. build-android-core.sh derives its stamp from `git describe` in a
# freshly initialised repo, which yields a synthetic commit whose hash changes
# with the commit timestamp - i.e. the app would show a different "core version"
# on every build and never the submodule's actual SHA.
CORE_SHA=$(git -C "$SRC/OpenFlux" rev-parse HEAD 2>/dev/null || echo "?")
# A submodule is DETACHED by default, so `rev-parse --abbrev-ref HEAD` returns
# the literal string "HEAD" and the About screen would read HEAD@cde597c9a28...
# with no branch at all. Ask git which branch actually contains the commit.
CORE_BRANCH=$(git -C "$SRC/OpenFlux" for-each-ref --count=1 --format='%(refname:short)' \
  --contains HEAD refs/heads/ 2>/dev/null || true)
[ -n "$CORE_BRANCH" ] || CORE_BRANCH=$(git -C "$SRC/OpenFlux" describe --tags --always 2>/dev/null || echo "?")
[ -n "$CORE_BRANCH" ] || CORE_BRANCH="?"
CORE_REF="$CORE_BRANCH@$CORE_SHA"
# --porcelain covers staged and untracked changes too, not just worktree edits
# the way `git diff --quiet` does.
DIRTY=""
if [ -n "$(git -C "$SRC/OpenFlux" status --porcelain 2>/dev/null)" ]; then DIRTY="+patched"; fi
echo "== core: $CORE_REF$DIRTY =="
if [ "$CORE_SHA" = "?" ]; then
  echo "   WARNING: cannot read the core's git SHA; the About screen will be vague" >&2
fi

echo "== staging repo (excluding build output) =="
echo "   source: $SRC"
rm -rf "$WORK"
mkdir -p "$WORK"
# ./dist holds the previous build's APKs (100+ MB) and is not tracked: copying
# it wastes minutes and would commit the binaries into the staged repo.
tar -C "$SRC" --exclude='./.git' --exclude='./dist' --exclude='*/build' --exclude='*/.gradle' \
    -cf - . | tar -C "$WORK" -xf -
# The core build stamps branch@commit into the app's About screen; keep a real
# git repo so `git describe` in scripts/build-android-core.sh succeeds. The
# submodules' .git entries are files pointing at ../.git/modules/<name>, which
# the tar excluded, so drop them before re-initializing.
rm -f "$WORK/OpenFlux/.git" "$WORK/shared/.git"
# The Windows checkout has CRLF line endings; a stray \r breaks `set -euo pipefail`
# in the shell scripts, and leaks into the HTML that WebPage.kt embeds. CI
# checks out LF, so normalize the staged copy.
find "$WORK" -type f \( -name '*.sh' -o -name '*.kts' -o -name '*.properties' \
    -o -name '*.toml' -o -name '*.xml' -o -name '*.kt' \) \
    -exec sed -i 's/\r$//' {} +
# gradlew has no extension, so the find above misses it.
sed -i 's/\r$//' "$WORK/gradlew"
chmod +x "$WORK/gradlew"
# A real git repo is still needed: build-android-core.sh calls `git describe`
# in the core. Any submodule discovered at depth 2, not just today's two.
find "$WORK" -mindepth 2 -maxdepth 2 -name .git -type f -delete
for r in "$WORK/OpenFlux" "$WORK/shared"; do
  [ -d "$r" ] || continue
  git -C "$r" init -q
  # No `|| true` here: a silent failure here resurfaces much later as
  # "fatal: ambiguous argument 'HEAD'" inside build-android-core.sh.
  git -C "$r" add -A
  git -C "$r" -c user.email=build@local -c user.name=build commit -qm "staged $CORE_REF$DIRTY"
done

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WORK/local.properties"

echo "== gomobile AAR (patched core) =="
cd "$WORK"
bash scripts/build-android-core.sh
# ls of an empty dir succeeds, so assert the AAR is really there.
[ -s androidApp/libs/openflux.aar ] || { echo "openflux.aar was not produced" >&2; exit 1; }
# Replace the synthetic stamp with the core's real identity.
printf '%s%s\n' "$CORE_REF" "$DIRTY" > androidApp/libs/openflux-core.version
ls -l androidApp/libs/

echo "== tests (CI parity) =="
./gradlew --no-daemon :shared:jvmTest

echo "== signing key =="
# Minting a key silently is the dangerous case: a wiped $HOME or a second machine
# would produce APKs signed by a different certificate, Android would then refuse
# to update any installed copy, and every self-consistency check would still
# pass because those APKs agree with each other. Creating one is an explicit act.
if [ ! -f "$KEY" ]; then
  if [ "${OF_ALLOW_NEW_KEY:-0}" = "1" ]; then
    echo "   creating a NEW signing key at $KEY - existing installs will NOT be updatable"
    # -storepass:env keeps the password out of the process list.
    KS_PASS=$KS_PASS keytool -genkeypair -v -keystore "$KEY" \
      -storepass:env KS_PASS -keypass:env KS_PASS \
      -alias "$KS_ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
      -dname "CN=OpenFlux Local, OU=Personal, O=OpenFlux, L=-, ST=-, C=RU" >/dev/null 2>&1
    chmod 600 "$KEY"
  else
    echo "no signing key at $KEY" >&2
    echo "it is what every personal build is signed with, and losing it means the" >&2
    echo "next build cannot be installed over the current one. Re-run with" >&2
    echo "OF_ALLOW_NEW_KEY=1 only if a brand new identity is intended." >&2
    exit 1
  fi
fi

# Without this, build.gradle.kts quietly produces an UNSIGNED release APK: the
# signing config is only attached when storeFile != null, and the failure would
# then surface as an install error on the phone instead of a build error.
[ -f "$KEY" ] || { echo "no keystore at $KEY" >&2; exit 1; }

echo "== assembleRelease $VER =="
export ANDROID_KEYSTORE_FILE=$KEY
export ANDROID_KEYSTORE_PASSWORD=$KS_PASS
export ANDROID_KEY_ALIAS=$KS_ALIAS
export ANDROID_KEY_PASSWORD=$KS_PASS
./gradlew --no-daemon -PappVersion="$VER" :androidApp:assembleRelease

echo "== collect =="
# A partial split build used to print BUILD_OK with whatever it got.
NBUILT=$(find androidApp/build/outputs/apk/release -name '*.apk' | grep -c .)
[ "$NBUILT" -eq 5 ] || { echo "expected 5 APKs, gradle produced $NBUILT" >&2; exit 1; }
# Clear the CONTENTS, not the directory. On /mnt/d a Windows process (Explorer,
# Total Commander) can hold a handle on dist/, and then `rm -rf dist` fails on
# the directory itself even when it is already empty - which under set -e killed
# the whole build at the last step, after a successful gradle assemble. The
# directory is recreated immediately below, so only its contents matter, and
# emptying them is what keeps the audit's rule true: dist/ holds the five APKs
# of this build and their checksums, nothing older.
find "$SRC/dist" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$SRC/dist"
# Name after the version like CI does, so a 1.1 and a 1.2 artifact are not
# indistinguishable on disk. find's {} expands the WHOLE matched path even
# mid-word, so the basename has to be taken explicitly.
find androidApp/build/outputs/apk/release -name '*.apk' -print | while read -r apk; do
  cp "$apk" "$SRC/dist/OpenFluxAndroid-$VER-$(basename "$apk")"
done
cd "$SRC/dist"
ls *.apk >/dev/null 2>&1 || { echo "no APK collected into $SRC/dist" >&2; exit 1; }
sha256sum *.apk > SHA256SUMS.txt
ls -l
echo BUILD_OK
