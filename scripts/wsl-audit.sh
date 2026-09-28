#!/usr/bin/env bash
# Audit of the built APKs. Exits non-zero if any check fails, so a silent
# regression cannot pass as success.
#
#   wsl-audit.sh [X.Y.Z]     (default 1.2.0)
#
# Design rules this file follows, each one earned by a review finding:
#  - `set -uo pipefail` WITHOUT -e on purpose. Every content check is
#    `n=$(... | grep -c ...)`, and grep exits 1 on no-match - the exact failure
#    path. Under -e the script would abort before `bad` ran and print nothing.
#  - `grep -c` (reads all input, so the producer never gets SIGPIPE) and
#    `grep -F` (needles are literal, never patterns).
#  - every check runs against the ARTIFACT, never the source tree;
#  - every check runs against EVERY apk in dist/, not one representative:
#    five copies of the universal build satisfied the earlier one-file audit;
#  - a check that can silently match nothing must assert it saw something.
set -uo pipefail

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SELF/.." && pwd)
D=$ROOT/dist

DEFAULT_VER=1.2.0
EXPECTED_VER=${1:-$DEFAULT_VER}
if ! [[ $EXPECTED_VER =~ ^[0-9]{1,3}\.[0-9]{1,2}\.[0-9]{1,2}$ ]]; then
  echo "version '$EXPECTED_VER' is not X.Y.Z (e.g. $DEFAULT_VER)" >&2
  exit 2
fi
EXPECTED_CODE=$(echo "$EXPECTED_VER" | awk -F. '{printf "%d", $1*10000 + $2*100 + $3}')
# The applicationId, checked against every APK. Overridable for a genuinely
# different fork, but the same rule as the cert pin applies: an override is
# reported at the verdict line, not only here in a comment.
EXPECTED_PKG=${OF_EXPECTED_PKG:-io.openflux.client}

# The certificate that has signed every personal build. A regenerated local
# keystore produces a different one, and Android then refuses to update any
# installed copy - silently, because the old APKs are still self-consistent.
# Override only when deliberately switching to the project's CI key.
EXPECTED_CERT=${OF_EXPECTED_CERT:-a33486233b8c50e4ddffd09c13622aafe5ca9d97a5c4a2e655505abb6e9b9144}
# A regular assignment is not a pin. ofbuild.env is sourced further down and can
# reassign it - that is exactly how a dist re-signed with a rogue key got
# AUDIT_OK with no warning at all. readonly is the whole fix: the source now
# fails loudly instead of quietly voiding the check.
if ! [[ "$EXPECTED_CERT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "OF_EXPECTED_CERT is not a 64-char lowercase hex digest: $EXPECTED_CERT" >&2
  exit 2
fi
if [ -n "${OF_EXPECTED_CERT:-}" ]; then
  echo "!! ВНИМАНИЕ: OF_EXPECTED_CERT переопределён: $EXPECTED_CERT"
fi
readonly EXPECTED_CERT

# An override is not a warning, it is the whole gate. Exporting
# OF_EXPECTED_CERT=<digest of a rogue key> used to print a yellow line and then
# return exit 0 over a dist signed by that rogue key, so any CI runner that set
# it once had a permanently green release gate for an arbitrary certificate.
# The variable still exists - switching to the project's CI key is a real need -
# but it now has to be said out loud at the end, and it fails the run.
CERT_OVERRIDDEN=0
if [ -n "${OF_EXPECTED_CERT:-}" ]; then
  CERT_OVERRIDDEN=1
fi
readonly CERT_OVERRIDDEN

[ -f "$HOME/ofbuild.env" ] || { echo "run wsl-toolchain.sh first" >&2; exit 1; }
# Remember the SDK before the source runs. Everything downstream is built on
# $BT - aapt2, apksigner, and therefore the entire certificate check - and
# $BT came out of a file this script calls untrusted input. Appending
# `export ANDROID_HOME=<fake>` to ofbuild.env, with a stub apksigner that
# prints the pinned digest and exits 0, made a rogue-signed dist pass. If the
# source changes a path we then trust, that is the attack, not the setup.
SDK_BEFORE=${ANDROID_HOME:-}
# shellcheck disable=SC1090
source "$HOME/ofbuild.env"
# Re-check after the source rather than trusting the one above: the source is
# untrusted input, and readonly only makes a violation loud in shells that are
# still running. If the pin survived, this is a no-op.
if ! [[ "$EXPECTED_CERT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "pin повреждён после source ofbuild.env: $EXPECTED_CERT" >&2
  exit 2
fi
if [ -z "$SDK_BEFORE" ]; then
  # Normal: ofbuild.env is where ANDROID_HOME comes from, and the audit is run
  # from a shell that never had it. Failing here would be red on every run,
  # which is the same as not having the check. The tool-shape test below is what
  # actually defends against substitution.
  SDK_UNTRUSTED=0
elif [ "$SDK_BEFORE" != "${ANDROID_HOME:-}" ]; then
  echo "!! ВНИМАНИЕ: ofbuild.env подменил ANDROID_HOME: $SDK_BEFORE -> $ANDROID_HOME"
  SDK_UNTRUSTED=1
else
  SDK_UNTRUSTED=0
fi
BT=$ANDROID_HOME/build-tools/35.0.0
# Everything downstream - the certificate check above all - runs $BT/aapt2 and
# $BT/apksigner, and $BT came out of a file this script calls untrusted input.
# Appending `export ANDROID_HOME=<fake>` with a stub apksigner that prints the
# pinned digest and exits 0 made a rogue-signed dist pass, so the pin was only
# ever as strong as an env var.
#
# The path cannot be pinned; the tools' shape can. The real aapt2 is a 6.3 MB
# ELF, and the real apksigner is a small script that loads a multi-megabyte jar
# from the same SDK. A directory an attacker invented will have a 100-byte
# script and no jar, whatever it is called.
if [ -x "$BT/aapt2" ] && [ "$(stat -c%s "$BT/aapt2" 2>/dev/null || echo 0)" -gt 1000000 ] \
   && [ "$(head -c 4 "$BT/aapt2" 2>/dev/null | od -An -c | tr -d ' \n')" = '177ELF' ]; then
  :
else
  echo "!! ВНИМАНИЕ: aapt2 в $BT не похож на настоящий (ожидается ELF > 1 МБ)"
  SDK_UNTRUSTED=1
fi
if [ -s "$BT/apksigner" ] && [ -s "$BT/lib/apksigner.jar" ] \
   && [ "$(stat -c%s "$BT/lib/apksigner.jar" 2>/dev/null || echo 0)" -gt 1000000 ]; then
  :
else
  echo "!! ВНИМАНИЕ: apksigner в $BT не похож на настоящий (нет lib/apksigner.jar)"
  SDK_UNTRUSTED=1
fi

# Private scratch: a fixed /tmp/audit path collides between concurrent runs
# and between users on the same machine.
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

FAIL=0
ok()  { echo "  OK    $1"; }
bad() { echo "  FAIL  $1"; FAIL=1; }

echo "== 0. набор артефактов =="
echo "  ожидается $EXPECTED_PKG версии $EXPECTED_VER (versionCode $EXPECTED_CODE)"
[ -d "$D" ] || { echo "no dist/ at $D" >&2; exit 1; }
# Newline-delimited, but every consumer reads it back with `IFS= read -r`, so a
# space in a filename is still handled. A NUL-delimited list would be stricter,
# but command substitution silently drops NUL bytes, which is worse.
APK_LIST=$(find "$D" -maxdepth 1 -type f -name '*.apk' | sort)
COUNT=$(printf '%s\n' "$APK_LIST" | grep -c .)
[ "$COUNT" -eq 5 ] && ok "5 APK в dist/" || bad "APK в dist/: $COUNT (ожидается 5)"

if [ "$COUNT" -eq 0 ]; then echo "no APKs in $D" >&2; exit 1; fi

# An artifact dated in the future is not evidence of anything, it is a deleted
# timestamp. `find -newer $apk` compares against the APK's own mtime and nothing
# bounds that value, so `touch -d 2030-01-01 dist/*.apk` made every source file
# in the tree look older than the build - which is the enabler that let a
# backdated edit, an assume-unchanged bit and a chmod all pass section 5a.
# The fix is not to trust mtime, it is to stop accepting a nonsense one.
nfuture=$(find "$D" -maxdepth 1 -name '*.apk' -newermt '+1 day' 2>/dev/null | grep -c . || true)
[ "${nfuture:-0}" -eq 0 ] \
  || { echo "  FAIL  ${nfuture} APK имеют будущую дату — mtime не является доказательством"; FAIL=1; }

# Reading the list back with `IFS= read -r` keeps each path intact.
apks() { printf '%s\n' "$APK_LIST" | grep .; }

echo "== 0. набор имён APK зафиксирован =="
# Counting five files is not enough: five files all named "universal", each
# carrying the universal payload, satisfied every other check - the per-ABI
# branch only looked for the named ABI, and a name containing "universal" is
# never checked for extra ABIs. Pin the exact set instead of counting.
want_names=$(printf '%s\n' \
  "OpenFluxAndroid-$EXPECTED_VER-androidApp-arm64-v8a-release.apk" \
  "OpenFluxAndroid-$EXPECTED_VER-androidApp-armeabi-v7a-release.apk" \
  "OpenFluxAndroid-$EXPECTED_VER-androidApp-universal-release.apk" \
  "OpenFluxAndroid-$EXPECTED_VER-androidApp-x86-release.apk" \
  "OpenFluxAndroid-$EXPECTED_VER-androidApp-x86_64-release.apk" | sort)
have_names=$(apks | xargs -r -n1 basename | sort)
if [ "$have_names" = "$want_names" ]; then
  ok "ровно пять ожидаемых имён"
else
  bad "состав dist/ не тот:"
  diff <(printf '%s\n' "$want_names") <(printf '%s\n' "$have_names") | sed 's/^/       /' || true
fi

echo "== 1. содержимое: у каждого APK своя архитектура =="
# A filename is not evidence. Without this, the universal build copied five
# times - or swapped for any other per-ABI file - passed every other check,
# since universal contains all four lib/<abi>/ directories.
while IFS= read -r apk; do
  name=$(basename "$apk")
  contents=$(unzip -Z1 "$apk" 2>/dev/null)
  case "$name" in
    *-universal-*)   abis="arm64-v8a armeabi-v7a x86 x86_64" ;;
    *-arm64-v8a-*)   abis="arm64-v8a" ;;
    *-armeabi-v7a-*) abis="armeabi-v7a" ;;
    *-x86_64-*)      abis="x86_64" ;;
    *-x86-*)         abis="x86" ;;
    *)               abis="" ;;
  esac
  if [ -z "$abis" ]; then
    bad "$name: в имени не распознан ABI"
    continue
  fi
  miss=0
  want=0
  for abi in $abis; do
    want=$((want + 1))
    # grep -cF "lib/$abi/" is a substring search over the whole listing, so
    # "res/zdecoy/lib/x86/keep.txt" satisfied it. An x86 APK with every lib/
    # entry moved to lib/mips/ was certified "архитектура на месте (x86)" while
    # shipping no x86 code at all. The exact zip entry is what has to exist.
    if printf '%s\n' "$contents" | grep -qxF "lib/$abi/libgojni.so"; then
      :
    else
      miss=$((miss + 1)); echo "       нет lib/$abi/libgojni.so"
    fi
  done
  [ "$miss" -eq 0 ] && ok "$name: архитектура на месте ($abis)" \
                    || bad "$name: не хватает $miss из $want библиотек lib/<abi>/libgojni.so"
  # Presence alone is not enough: a universal APK re-signed and dropped into an
  # arm64 slot satisfies every line above, because the named ABI is there - it
  # is just there alongside the other three. A single-ABI build must carry the
  # named ABI and nothing else.
  if [ "$want" -eq 1 ]; then
    extra=""
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
      case " $abis " in *" $abi "*) continue ;; esac
      printf '%s\n' "$contents" | grep -qxF "lib/$abi/libgojni.so" && extra="$extra $abi"
    done
    [ -z "$extra" ] && ok "$name: посторонних ABI нет" \
                    || bad "$name: кроме $abis внутри есть$extra — это universal под чужим именем"
  fi
  # That loop only ever looks for libgojni.so, so a directory belonging to
  # another ABI walks past it entirely: `lib/arm64-v8a/libsupport.so`, a copy of
  # the x86 library, shipped inside a single-ABI APK and loaded at runtime.
  # Extra libraries inside the CORRECT abi are normal - this build really does
  # ship libandroidx.graphics.path.so - so the rule is about directories, not
  # about file names.
  if [ "$want" -eq 1 ]; then
    dirs=$(printf '%s\n' "$contents" | grep '^lib/' | cut -d/ -f2 | sort -u | tr '\n' ' ')
    for d in $dirs; do
      if [ "$d" != "$abis" ]; then
        bad "$name: каталог lib/$d не соответствует заявленной архитектуре $abis"
      fi
    done
    ok "$name: каталоги lib/ соответствуют $abis"
  fi
done <<< "$(apks)"

echo "== 1b. пять APK действительно разные =="
UNIQ=$(apks | xargs -d '\n' sha256sum 2>/dev/null | awk '{print $1}' | sort -u | grep -c .)
[ "$UNIQ" -eq "$COUNT" ] && ok "все $COUNT APK различны" \
  || bad "уникальных содержимых: $UNIQ из $COUNT — есть копии"

echo "== 2. версия и подпись (каждый APK) =="
while IFS= read -r apk; do
  name=$(basename "$apk")
  B=$($BT/aapt2 dump badging "$apk" 2>/dev/null)
  # The package name was never checked, and everything else in this section is
  # about "is this really our release": a same-length edit of the AXML string
  # pool (io.openflux.client -> io.openflux.c1ient) was reported by aapt2 as a
  # different package and the audit still said AUDIT_OK. The version, the signer
  # and the provenance SHA all survive that, so it is a silent identity change.
  n=$(printf '%s' "$B" | grep -cF "package: name='$EXPECTED_PKG'")
  [ "$n" -gt 0 ] && ok "$name package=$EXPECTED_PKG" || bad "$name: пакет не $EXPECTED_PKG"
  n=$(printf '%s' "$B" | grep -cF "versionName='$EXPECTED_VER'")
  [ "$n" -gt 0 ] && ok "$name versionName=$EXPECTED_VER" || bad "$name versionName != $EXPECTED_VER"
  n=$(printf '%s' "$B" | grep -cF "versionCode='$EXPECTED_CODE'")
  [ "$n" -gt 0 ] && ok "$name versionCode=$EXPECTED_CODE" || bad "$name versionCode != $EXPECTED_CODE"
  if $BT/apksigner verify "$apk" >/dev/null 2>&1; then
    ok "$name подпись валидна"
  else
    bad "$name подпись НЕ проходит apksigner (unsigned build?)"
    continue
  fi
  # All signers, not just the first: an APK carrying a second signer would
  # otherwise report the first one and look identical to a clean build. Counting
  # is not enough on its own - the check used to print the count and only assert
  # "-gt 0", so a rogue second signer passed. Exactly one, and every digest
  # must be the pinned one.
  digests=$($BT/apksigner verify --print-certs "$apk" 2>/dev/null | grep -F 'SHA-256 digest:' | sed 's/.*digest: //')
  signers=$(printf '%s' "$digests" | grep -c .)
  if [ "$signers" -eq 1 ]; then
    ok "$name подписантов: $signers"
  else
    bad "$name подписантов: $signers (ожидается ровно 1) — лишний подписант"
  fi
  foreign=0
  while IFS= read -r d; do
    [ "$d" = "$EXPECTED_CERT" ] || foreign=$((foreign + 1))
  done <<< "$digests"
  if [ "$foreign" -eq 0 ] && [ "$signers" -eq 1 ]; then
    ok "$name сертификат ожидаемый"
  else
    bad "$name сертификат не совпадает с $EXPECTED_CERT ($foreign из $signers) — обновление поверх установленного не встанет"
  fi
done <<< "$(apks)"

echo "== 3. SHA256SUMS покрывает ровно все APK =="
# Verifying dist/ against a manifest that lives inside dist/ only proves the
# folder agrees with itself, so also require the manifest to list every APK.
if [ -f "$D/SHA256SUMS.txt" ]; then
  if (cd "$D" && sha256sum -c --quiet SHA256SUMS.txt >/dev/null 2>&1); then
    ok "контрольные суммы совпадают"
  else
    bad "SHA256SUMS не совпадает с файлами в dist/"
    (cd "$D" && sha256sum -c SHA256SUMS.txt 2>&1 | sed 's/^/       /')
  fi
  listed=$(awk 'NF>=2 {print $2}' "$D/SHA256SUMS.txt" | sort)
  present=$(cd "$D" && ls -1 *.apk 2>/dev/null | sort)
  if [ "$listed" = "$present" ]; then
    ok "манифест перечисляет все APK"
  else
    bad "SHA256SUMS.txt перечисляет не те файлы, что лежат в dist/"
    diff <(printf '%s\n' "$listed") <(printf '%s\n' "$present") | sed 's/^/       /'
  fi
  # The APK *name* set was pinned but the folder contents were not, so a
  # release note, a stray script or a second copy of a build sat next to the
  # five APKs and nobody noticed. Nothing else may live here.
  strays=$(cd "$D" && ls -A1 2>/dev/null | grep -v '\.apk$' | grep -v '^SHA256SUMS\.txt$' || true)
  if [ -z "$strays" ]; then
    ok "в dist нет посторонних файлов"
  else
    bad "в dist лежат посторонние файлы: $(printf '%s' "$strays" | tr '\n' ' ')"
  fi
else
  bad "нет SHA256SUMS.txt"
fi

echo "== 4. правки ядра в каждой libgojni.so =="
# Presence per marker, not a total line count: "session rotation" also appears
# in transport/yandex, so counting lines made a correct build look wrong.
while IFS= read -r apk; do
  name=$(basename "$apk")
  rm -rf "$T/so" && mkdir -p "$T/so"
  unzip -o -q "$apk" 'lib/*/libgojni.so' -d "$T/so" 2>/dev/null
  nso=$(find "$T/so" -name libgojni.so | grep -c .)
  if [ "$nso" -eq 0 ]; then
    bad "$name: libgojni.so не извлёкся"
    continue
  fi
  bad_so=0
  while IFS= read -r so; do
    abi=$(basename "$(dirname "$so")")
    # A file containing the marker STRINGS is not a shared library. `strings`
    # happily reads a 7-line text file, so without this an APK whose entire
    # native payload was "M-DOCS\nAuth OK\nconnectToDoc\n..." passed every
    # marker check and shipped zero native code. Require an ELF header, a
    # plausible size, and the exported JNI entry points.
    magic=$(head -c4 "$so" | xxd -p)
    [ "$magic" = "7f454c46" ] || { bad_so=$((bad_so+1)); echo "       $abi: не ELF (magic $magic) — вместо .so текст?"; continue; }
    sz=$(stat -c%s "$so")
    # 1 MB floor was a suggestion, not a threshold. A 1.4 MB C stub - gcc, one
    # function called Java_io_openflux_Auth_start, the seven marker strings, a
    # padding array - passed every check here, re-signed with the real key. The
    # real gomobile libraries are 15-16 MB, so the floor goes where it costs a
    # padding array nothing to fake and a stub has to do real work to reach.
    [ "$sz" -gt 10000000 ] || { bad_so=$((bad_so+1)); echo "       $abi: .so всего $sz байт — настоящая gomobile-библиотека весит 15-16 МБ"; }
    # A real ELF, not a bag of strings prefixed with the magic. readelf -h has
    # to parse the header and readelf -d the program headers; 1.5 MB of "B"
    # behind \x7fELF satisfied every marker check below while exporting
    # nothing.
    #
    # readelf and go are REQUIRED, not optional. `command -v readelf` guarding
    # this block was the same shape as the dexdump bug: the block skips itself
    # when the tool is missing and the .so is declared good. A missing tool now
    # fails the run instead of passing it.
    for need in readelf go; do
      command -v "$need" >/dev/null 2>&1 \
        || { bad_so=$((bad_so+1)); echo "       $abi: нет $need — проверка .so пропущена"; }
    done
    if command -v readelf >/dev/null 2>&1 && command -v go >/dev/null 2>&1; then
      if ! readelf -h "$so" >/dev/null 2>&1; then
        bad_so=$((bad_so+1)); echo "       $abi: readelf -h не разобрал файл — это не ELF"
        continue
      fi
      # The JNI entry points must be real dynamic symbols, not string data.
      # (No DT_SONAME check: a Go-built library legitimately has none, so
      # requiring it failed every honest APK in the suite.)
      # Count, do not use `grep -q`. grep -q exits on the first match, readelf
      # dies of SIGPIPE on the closed pipe, and under `set -o pipefail` the
      # pipeline reports that failure - so the check reported "no symbols" on
      # libraries that have 44 of them. grep -c reads the whole stream.
      nsym=$(readelf --dyn-syms "$so" 2>/dev/null | grep -c 'Java_io_openflux' || true)
      if [ "${nsym:-0}" -gt 0 ] 2>/dev/null; then
        :
      else
        bad_so=$((bad_so+1)); echo "       $abi: Java_io_openflux нет в таблице динамических символов"
      fi
      # And it has to be a GO library, not C that happens to export a JNI name.
      # Grepping the byte stream for three symbol NAMES is not a provenance
      # check: three `const char*` in a C file satisfy it, and an 11.5 MB gcc
      # stub with one Java_io_openflux_Auth_start and a padding array was
      # accepted for all five APKs. `go version -m` reads the real build info
      # block a Go linker writes, and has nothing to say about a C object.
      if ! go version -m "$so" >/dev/null 2>&1; then
        bad_so=$((bad_so+1))
        echo "       $abi: go version -m не читает файл — это не сборка Go"
      fi
    fi
    strings "$so" > "$T/so.strings"
    for m in 'M-DOCS' 'Auth OK' 'connectToDoc'; do
      n=$(grep -cF "$m" "$T/so.strings")
      [ "$n" -gt 0 ] || { bad_so=$((bad_so+1)); echo "       $abi: нет контрольной строки $m"; }
    done
    for m in 'one-way channel' 'session rotation' 'doc key rotated' 'rx ping'; do
      n=$(grep -cF "$m" "$T/so.strings")
      [ "$n" -gt 0 ] || { bad_so=$((bad_so+1)); echo "       $abi: НЕТ маркера $m"; }
    done
  done <<< "$(find "$T/so" -name libgojni.so)"
  [ "$bad_so" -eq 0 ] && ok "$name: $nso .so, все маркеры на месте" \
                      || bad "$name: в $nso .so найдено $bad_so проблем с маркерами"
done <<< "$(apks)"

echo "== 5. версия ядра в приложении =="
# Guards the About screen naming the real submodule rather than the synthetic
# commit a freshly initialised staging repo produces.
while IFS= read -r apk; do
  name=$(basename "$apk")
  rm -rf "$T/dex" && mkdir -p "$T/dex"
  unzip -o -q "$apk" 'classes*.dex' -d "$T/dex" 2>/dev/null
  cat "$T/dex"/*.dex > "$T/all.dex" 2>/dev/null
  [ -s "$T/all.dex" ] || { bad "$name: dex не извлёкся"; continue; }
  strings "$T/all.dex" > "$T/dex.strings"
  n=$(grep -cE '[A-Za-z0-9_./-]+@[0-9a-f]{40}' "$T/dex.strings")
  [ "$n" -gt 0 ] && ok "$name: CORE_VERSION содержит реальный SHA ($n)" \
                || bad "$name: CORE_VERSION без 40-символьного SHA — ядро собрано как попало"
  # A 40-hex string is not proof it is the CURRENT one: an APK built against an
  # older submodule commit matches just as well. Compare against the checkout.
  # If git cannot answer, that is a FAILURE, not a reason to skip: the check
  # used to be wrapped in `if [ -n "$head_sha" ]` with no else, so deleting
  # OpenFlux/.git - or running on a box without git - silently disabled the
  # only provenance check there was.
  head_sha=$(git -C "$ROOT/OpenFlux" rev-parse HEAD 2>/dev/null)
  if [ -z "$head_sha" ]; then
    bad "$name: не удалось определить HEAD подмодуля — проверка происхождения ядра пропущена"
  elif grep -qF "$head_sha" "$T/dex.strings"; then
    ok "$name: ядро соответствует HEAD (${head_sha:0:9})"
  else
    bad "$name: в CORE_VERSION нет текущего HEAD ${head_sha:0:9} — APK старше подмодуля"
  fi
done <<< "$(apks)"

echo "== 5a. рабочее дерево ядра неприкосновенно =="
# A matching HEAD proves the APK was built from SOME committed state. It says
# nothing about the files in front of the build: appending one line to
# logging.go, backdating its mtime and shipping it gave AUDIT_OK, because the
# whole chain was "40-hex in the dex" + "rev-parse HEAD" + mtime, and touch
# -d breaks the last link. The build already stamps +patched for a dirty tree;
# this is the same fact asserted on the tree itself.
#
# --ignore-cr-at-eol is not leniency, it is correctness. The audit runs under
# WSL git against a checkout written by Windows git: every file differs by CRLF
# and `git status --porcelain` calls the entire tree dirty, which made this
# check fail every honest build in the suite.
tree_clean() {
  local d="$1"
  git -C "$d" diff --quiet --ignore-cr-at-eol -- . ':(exclude)OpenFlux' ':(exclude)shared' 2>/dev/null || return 1
  git -C "$d" diff --cached --quiet --ignore-cr-at-eol -- . ':(exclude)OpenFlux' ':(exclude)shared' 2>/dev/null || return 1
  # The two gitlinks are excluded above and checked here instead. At this level
  # git reports a submodule as `-dirty` for CRLF-only differences between WSL
  # git and a Windows-written checkout, and --ignore-cr-at-eol does not reach
  # that judgement - so the fork read as permanently dirty and the new
  # androidApp check failed every honest build. Comparing the recorded gitlink
  # SHA with the submodule's real HEAD is both immune to that and stricter: it
  # pins the exact commit, which the old diff did not.
  local p want_sha have_sha
  for p in OpenFlux shared; do
    [ -e "$d/$p/.git" ] || continue
    want_sha=$(git -C "$d" ls-tree HEAD "$p" 2>/dev/null | awk '{print $3}')
    have_sha=$(git -C "$d/$p" rev-parse HEAD 2>/dev/null)
    if [ -z "$want_sha" ] || [ "$want_sha" != "$have_sha" ]; then
      return 1
    fi
  done
  [ -z "$(git -C "$d" ls-files --others --exclude-standard 2>/dev/null)" ] || return 1
  # core.filemode=false is set in the real submodule config, so a `chmod 755` on
  # a tracked .go is invisible to a plain diff. Forcing core.fileMode=true does
  # surface it - and also reports EVERY file as 100644 => 100755, because the
  # tree lives on a Windows mount that marks everything executable. That is not
  # a change anyone made, and treating it as one turns the check permanently
  # red, which is how checks get deleted. So: ignore exactly that one pattern,
  # fail on any other mode change.
  local modes
  modes=$(git -C "$d" -c core.fileMode=true diff --summary 2>/dev/null \
    | grep -v 'mode change 100644 => 100755' | grep -c 'mode change' || true)
  [ "${modes:-0}" -eq 0 ] || return 1
  # ... but that listing honours .git/info/exclude, which is untracked and is
  # never itself checked: one `echo backdoor.go >> .git/info/exclude` hides a
  # whole file from the check above. info/exclude is not tracked, so refuse to
  # trust the tree while it says anything.
  local gd ex
  # --absolute-git-dir, not --git-dir: the plain form returns a path relative to
  # the repository (".git"), so "$gd/info/exclude" resolved against this
  # script's working directory rather than the module's. The count came out 0
  # here by luck, and 0 is the answer that always passes.
  gd=$(git -C "$d" rev-parse --absolute-git-dir 2>/dev/null) || return 1
  [ -n "$gd" ] || gd=$(cd "$d" && git rev-parse --absolute-git-dir 2>/dev/null) || return 1
  ex=$(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$gd/info/exclude" 2>/dev/null | grep -c . )
  [ "${ex:-0}" -eq 0 ] || return 1
  # assume-unchanged / skip-worktree hide a tracked file from both diff and
  # status. A lowercase letter in `ls-files -v` is exactly that flag.
  local flags
  flags=$(git -C "$d" ls-files -v 2>/dev/null | grep -c '^[a-z]')
  [ "${flags:-0}" -eq 0 ] || return 1
  return 0
}
for mod in OpenFlux shared androidApp; do
  # androidApp is NOT a submodule. It is a plain directory inside this repo, so
  # $ROOT/androidApp/.git does not exist and `[ -e "$d/.git" ]` is false - which
  # made the androidApp entry a check that reads correctly and checks nothing,
  # committed as the fix for exactly that gap. The whole Kotlin application was
  # still outside the tree check while the audit reported it as covered.
  # For a plain directory the repository to compare against is the fork itself.
  if [ "$mod" = androidApp ]; then d="$ROOT"; else d="$ROOT/$mod"; fi
  if [ -e "$d/.git" ]; then
    if tree_clean "$d"; then
      ok "модуль $mod: рабочее дерево совпадает с HEAD"
    else
      bad "модуль $mod: есть незакоммиченные изменения — APK им не соответствует"
      git -C "$d" ls-files --others --exclude-standard 2>/dev/null | head -3 | sed 's/^/        новый: /'
      git -C "$d" diff --name-only --ignore-cr-at-eol 2>/dev/null | head -3 | sed 's/^/        изменён: /'
      echo "        (сначала закоммитьте, потом пересоберите: отпечаток берётся с HEAD)"
    fi
  else
    bad "модуль $mod: нет репозитория по пути $d — дерево не проверено"
  fi
done

echo "== 5b. исходники не выпотрошены =="
# A clean tree proves nothing about how much of it there is. Emptying
# androidApp/src and shared/src to zero files still passed, because the >=200
# floor was met entirely by the core tree and its .git directory. Reducing every
# .go to a bare "package X" passed too, and go vet is vacuously clean on an
# empty package - 19 lines standing in for 40k.
kt=$(find "$ROOT/androidApp/src" "$ROOT/shared/src" -name '*.kt' -type f 2>/dev/null | grep -c . || true)
# 60 against a real 73 was a standing invitation: delete the whole Kotlin
# application, drop in 60 one-line `package stub` files, backdate them, and the
# count still clears. No .go lives under androidApp/shared, so all 29793 Go
# lines are in OpenFlux - a tree-checked module. This file count is therefore
# the ONLY structural constraint on the application itself. 71 leaves room to
# delete two files and no more.
[ "${kt:-0}" -ge 71 ] && ok "Kotlin-исходники на месте ($kt .kt)" \
                     || bad "Kotlin-исходников всего ${kt:-0} — ожидалось не меньше 71"
gof=$(find "$ROOT/OpenFlux" -name '*.go' -type f -not -path '*/.git/*' 2>/dev/null | grep -c . || true)
[ "${gof:-0}" -ge 80 ] && ok "Go-исходники на месте ($gof .go)" \
                      || bad "Go-исходников всего ${gof:-0} — ожидалось не меньше 80"
golines=$(find "$ROOT/OpenFlux" -name '*.go' -type f -not -path '*/.git/*' -exec cat {} + 2>/dev/null | wc -l)
[ "${golines:-0}" -ge 20000 ] && ok "Go-кода достаточно ($golines строк)" \
                             || bad "Go-кода всего ${golines:-0} строк — похоже на заглушки"

echo "== 5c. lateinit-поля не затенены локальными переменными =="
# This exact bug shipped once: `val resetBtn = Button(this)` inside build()
# shadowed `lateinit var resetBtn`, so the field was never assigned,
# enableActions() bailed on its ::isInitialized guard, and Сбросить/Сохранить
# were dead on every path. It compiled clean, passed the build and passed this
# audit - because the root project has no allWarningsAsErrors, so Kotlin's
# "name shadowed" warning was never fatal and nothing else looked for it.
# Eight lines of grep are cheaper than the one-button picker.
shadow=0
while IFS= read -r f; do
  while IFS= read -r nm; do
    [ -n "$nm" ] || continue
    # grep -v 'lateinit' matters: the declaration line itself is
    # "lateinit var NAME" and so matches the pattern, which reported all eight
    # fields as shadowed on a tree where none of them is.
    hits=$(grep -nE "(^|[^A-Za-z0-9_.])(val|var)[[:space:]]+$nm([^A-Za-z0-9_]|$)" "$f" 2>/dev/null | grep -v 'lateinit')
    if [ -n "$hits" ]; then
      shadow=$((shadow + 1))
      echo "        $f: локальная переменная затеняет lateinit $nm"
      printf '%s\n' "$hits" | head -2 | sed 's/^/            /'
    fi
  done < <(grep -oE 'lateinit[[:space:]]+var[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' "$f" 2>/dev/null \
           | awk '{print $3}')
done < <(find "$ROOT/androidApp/src" "$ROOT/shared/src" -name '*.kt' -type f 2>/dev/null)
if [ "$shadow" -eq 0 ]; then
  ok "затенений lateinit не найдено"
else
  bad "найдено $shadow затенений lateinit — поля остаются неприсвоенными"
fi

echo "== 5b. go vet по ядру =="
# mobile/ is a SEPARATE module with its own go.mod, so a plain ./... in the
# core skips it - and it is the module holding the JNI bridge actually shipped
# inside the APK. Vet both, or the check means less than it looks like.
for mod in OpenFlux OpenFlux/mobile; do
  if (cd "$ROOT/$mod" && go vet ./... >"$T/vet.log" 2>&1); then
    ok "go vet ./... чист: $mod"
  else
    bad "go vet нашёл замечания в $mod:"
    head -10 "$T/vet.log" | sed 's/^/       /'
  fi
done

echo "== 6. функция выбора приложений и иконка (каждый APK) =="
# Strings that exist only in the current sources. If one is absent, the APK
# predates that edit no matter what the timestamps say.
# grep the raw dex, NOT `strings`: by default strings extracts 7-bit ASCII only,
# and dex keeps the Russian UI text as MUTF-8, so every Cyrillic needle would
# report "missing" and prove nothing.
NEEDLES='Не удалось открыть список приложений
Приложения через прокси
Будет применено:
Сейчас сохранено:
per-app rule: none of
cannot allow'
while IFS= read -r apk; do
  name=$(basename "$apk")
  rm -rf "$T/dx" "$T/rs" && mkdir -p "$T/dx" "$T/rs"
  unzip -o -q "$apk" 'classes*.dex' -d "$T/dx" 2>/dev/null
  unzip -o -q "$apk" 'res/*.xml' -d "$T/rs" 2>/dev/null
  # Grepping the dex for our strings proves nothing about it being an app: an
  # APK with every classes*.dex replaced by a 360-byte text file holding exactly
  # those strings passed the whole gate and could not even install. Require every
  # dex to carry a real header, and a plausible total size. Each file has to be
  # checked on its own: `head -c4 f1 f2` concatenates, so a valid first file
  # would mask a garbage second one.
  ndex=0
  dexsz=$(cat "$T/dx"/*.dex 2>/dev/null | wc -c)
  badmagic=0
  while IFS= read -r d; do
    ndex=$((ndex + 1))
    m=$(head -c4 "$d" | xxd -p)
    [ "$m" = "6465780a" ] || { badmagic=$((badmagic + 1)); echo "       $(basename "$d"): magic $m, не dex"; continue; }
    # The magic and the size are still a string bag's best friends: 2.5 MB of
    # "A" behind a valid "dex\n035\0" header, carrying the SHA and the marker
    # strings, certified the whole APK while containing zero runnable code.
    # The dex header stores its own file_size at 0x20 and the number of class
    # definitions at 0x60; a real dex has both, a padded one does not.
    fsz=$(od -An -tu4 -j32 -N4 "$d" | tr -d ' \n')
    asz=$(stat -c%s "$d")
    if [ -n "$fsz" ] && [ "$fsz" -gt 0 ] 2>/dev/null && [ "$fsz" -eq "$asz" ]; then
      :
    else
      badmagic=$((badmagic + 1))
      echo "       $(basename "$d"): размер в заголовке $fsz != фактический $asz"
    fi
    # A dex with no classes is a dex that cannot run anything.
    # Use $BT/dexdump, not bare dexdump: dexdump ships in build-tools and is
    # NOT on PATH here, so `command -v dexdump` was false and this whole block
    # never ran - a check that looks real and checks nothing. If the tool is
    # genuinely absent, say so rather than skipping quietly.
    if [ -x "$BT/dexdump" ]; then
      # grep -c, not grep -q: under `set -o pipefail` a `grep -q` that exits at
      # the first match kills dexdump with SIGPIPE and the pipeline reports
      # failure - so this reported "dexdump не разобрал" on a perfectly good
      # dex. It is the same trap readelf fell into above.
      ncd=$("$BT/dexdump" -f "$d" 2>/dev/null | grep -c 'class_defs_size' || true)
      if [ "${ncd:-0}" -gt 0 ] 2>/dev/null; then
        :
      else
        badmagic=$((badmagic + 1))
        echo "       $(basename "$d"): dexdump не разобрал dex"
      fi
    else
      echo "       ВНИМАНИЕ: dexdump не найден в $BT — проверка структуры dex пропущена"
    fi
  done <<< "$(find "$T/dx" -name 'classes*.dex' | sort)"
  # Real Kotlin, not a shell of empty packages: every .go reduced to a bare
  # "package X" still vetted clean, because go vet on an empty package is
  # vacuously clean.
  nclasses=$(cat "$T/dx"/*.dex 2>/dev/null | grep -ao 'Lio/openflux/\|Landroidx/\|Lkotlin/' | wc -l)
  if [ "$nclasses" -lt 100 ]; then
    badmagic=$((badmagic + 1))
    echo "       всего $nclasses дескрипторов типов — dex без кода приложения"
  fi
  if [ "$ndex" -ge 1 ] && [ "$badmagic" -eq 0 ] && [ "$dexsz" -gt 2000000 ]; then
    ok "$name: настоящий dex ($ndex шт., $dexsz байт, $nclasses дескрипторов)"
  else
    bad "$name: dex подозрителен ($ndex шт., $dexsz байт, битых $badmagic)"
  fi
  cat "$T/dx"/*.dex > "$T/dx/all.dex" 2>/dev/null
  cat "$T/dx"/*.dex 2>/dev/null | strings > "$T/dx/all.strings"
  ncls=0
  for cls in "Lio/openflux/android/platform/AppSelectionActivity;" "Lio/openflux/android/core/AppSelection;"; do
    n=$(grep -cF "$cls" "$T/dx/all.strings")
    [ "$n" -gt 0 ] && ncls=$((ncls+1)) || echo "       нет класса $cls"
  done
  [ "$ncls" -eq 2 ] && ok "$name: классы выбора приложений в dex" \
                    || bad "$name: нет $((2-ncls)) из 2 классов"
  nstr=0
  while IFS= read -r needle; do
    [ -n "$needle" ] || continue
    n=$(grep -acF "$needle" "$T/dx/all.dex" 2>/dev/null)
    [ "$n" -gt 0 ] && nstr=$((nstr+1)) || echo "       нет свежей строки: $needle"
  done <<< "$NEEDLES"
  [ "$nstr" -eq 6 ] && ok "$name: все 6 строк текущей сборки в dex" \
                   || bad "$name: нет $((6-nstr)) из 6 строк — APK старше исходников"
  # The gradient lives in the compiled binary XML, not in resources.arsc and
  # not in the vector's xmltree: aapt2 compiles an aapt:attr gradient to an
  # opaque reference. ARGB little-endian: #8B2FD9 -> d9 2f 8b ff,
  # #E01B3C -> 3c 1b e0 ff; the old blues must be gone.
  nxml=$(find "$T/rs" -name '*.xml' | grep -c .)
  if [ "$nxml" -gt 0 ]; then
    # The gradient is NOT in the launcher icon's own file: aapt2 compiles the
    # icon to a reference and the two colour ints live in the drawable it points
    # at (res/ea.xml in this build). Following that chain from shell would mean
    # writing a linker. What C1 actually allowed was an APK with no launcher
    # icon at all - every res xml deleted, one 8-byte junk file holding the four
    # colour bytes - so the icon has to exist for real, and the colours are then
    # required somewhere in the resources.
    icon_path=$("$BT/aapt2" dump badging "$apk" 2>/dev/null \
      | sed -n "s/.*application-icon-160: *'\([^']*\)'.*/\1/p" | head -1)
    icon_file=""
    [ -n "$icon_path" ] && icon_file="$T/rs/res/$(basename "$icon_path")"
    if [ -z "$icon_file" ]; then
      bad "$name: не удалось определить иконку запуска"
    elif [ ! -s "$icon_file" ]; then
      # Two res/ in the path on purpose: the zip entries are "res/BW.xml" and
      # they are unpacked under $T/rs, so the file lands at $T/rs/res/BW.xml.
      bad "$name: иконка запуска $icon_path не извлеклась"
    else
      isz=$(stat -c%s "$icon_file")
      [ "$isz" -gt 100 ] && ok "$name: иконка запуска на месте ($isz байт)" \
                        || bad "$name: иконка запуска подозрительно мала ($isz байт)"
    fi
    # Per file, never concatenated. `find ... -exec xxd -p {} \; | tr -d '\n'`
    # glued the last bytes of one resource to the first bytes of the next, so a
    # colour absent from the APK entirely was "found" across the seam - and
    # whether it was found depended on readdir order, so the check was flaky
    # depended on readdir order, so the check was flaky even when it passed.
    : > "$T/rs/hits.txt"
    while IFS= read -r d; do
      [ -f "$d" ] || continue
      xxd -p "$d" >> "$T/rs/hits.txt" 2>/dev/null && printf '\n' >> "$T/rs/hits.txt"
    done < <(find "$T/rs" -name '*.xml')
    if [ ! -s "$T/rs/hits.txt" ]; then
      bad "$name: xxd не отработал (не установлен?)"
    else
      miss=0
      for pat in d92f8bff 3c1be0ff; do
        n=$(grep -cF "$pat" "$T/rs/hits.txt")
        [ "$n" -gt 0 ] || { miss=$((miss+1)); echo "       нет цвета $pat"; }
      done
      for pat in ff7c4fff 9e4d31ff; do
        n=$(grep -cF "$pat" "$T/rs/hits.txt")
        [ "$n" -eq 0 ] || { miss=$((miss+1)); echo "       остался старый синий $pat"; }
      done
      [ "$miss" -eq 0 ] && ok "$name: градиент иконки фиолетовый→красный" \
                        || bad "$name: $miss проблем с цветами иконки"
    fi
  else
    bad "$name: res/*.xml не извлёкся"
  fi
done <<< "$(apks)"

echo "== 7. APK новее исходников =="
# A deleted source tree made this pass with zero files seen, and `-e` plus
# `find -type f` also accepted a plain FILE standing where a tree belongs:
# replacing androidApp/src with one old file left the scan reporting "323 files"
# and the check green. So require -d for trees, -f for files, and a floor on the
# count rather than merely "more than zero".
SRC_FOUND=0
for d in "$ROOT/androidApp/src" "$ROOT/shared/src" "$ROOT/OpenFlux"; do
  if [ -d "$d" ]; then
    SRC_FOUND=$((SRC_FOUND + 1))
  else
    bad "нет каталога $d — проверка свежести неполна"
  fi
done
for f in "$ROOT/androidApp/build.gradle.kts" "$ROOT/gradle/libs.versions.toml"; do
  if [ -f "$f" ]; then
    SRC_FOUND=$((SRC_FOUND + 1))
  else
    bad "нет файла $f — проверка свежести неполна"
  fi
done
NSRC=$(find "$ROOT/androidApp/src" "$ROOT/shared/src" "$ROOT/OpenFlux" -type f 2>/dev/null | grep -c .)
if [ "$NSRC" -lt 200 ]; then
  bad "в исходниках всего $NSRC файлов — проверка свежести не может быть полной"
elif [ "$SRC_FOUND" -ne 5 ]; then
  bad "проверено $SRC_FOUND из 5 путей сборки"
else
  ok "все 5 путей сборки на месте, файлов: $NSRC"
  while IFS= read -r apk; do
    # -not -path '*/.git/*' is not tidiness. `$ROOT/OpenFlux/.git/index` is a file
    # inside the tree, and any git operation rewrites it with a current mtime,
    # so the freshness check reported the build as stale after nothing had
    # changed. .git holds no sources.
    n=$(find "$ROOT/androidApp/src" "$ROOT/shared/src" "$ROOT/OpenFlux" \
            -type f -not -path '*/.git/*' -newer "$apk" 2>/dev/null | grep -c .)
    [ "$n" -eq 0 ] && ok "$(basename "$apk"): новее исходников" \
                   || bad "$(basename "$apk"): $n файлов исходников новее — сборка устарела"
  done <<< "$(apks)"
fi

echo
# The override is reported here, at the single place the verdict is decided, so
# it cannot be a warning that scrolls past in the middle of 200 lines.
if [ "$CERT_OVERRIDDEN" -eq 1 ]; then
  bad "pin сертификата был переопределён через OF_EXPECTED_CERT — этот прогон ничего не доказывает"
fi
if [ "${SDK_UNTRUSTED:-0}" -eq 1 ]; then
  bad "путь SDK менялся при source или aapt2/apksigner отсутствуют — cert-проверка недоверенна"
fi
[ "$FAIL" -eq 0 ] && echo "AUDIT_OK" || echo "AUDIT_FAILED"
exit $FAIL
