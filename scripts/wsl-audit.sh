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

[ -f "$HOME/ofbuild.env" ] || { echo "run wsl-toolchain.sh first" >&2; exit 1; }
# shellcheck disable=SC1090
source "$HOME/ofbuild.env"
# Re-check after the source rather than trusting the one above: the source is
# untrusted input, and readonly only makes a violation loud in shells that are
# still running. If the pin survived, this is a no-op.
if ! [[ "$EXPECTED_CERT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "pin повреждён после source ofbuild.env: $EXPECTED_CERT" >&2
  exit 2
fi
BT=$ANDROID_HOME/build-tools/35.0.0

# Private scratch: a fixed /tmp/audit path collides between concurrent runs
# and between users on the same machine.
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

FAIL=0
ok()  { echo "  OK    $1"; }
bad() { echo "  FAIL  $1"; FAIL=1; }

echo "== 0. набор артефактов =="
echo "  ожидается версия $EXPECTED_VER (versionCode $EXPECTED_CODE)"
[ -d "$D" ] || { echo "no dist/ at $D" >&2; exit 1; }
# Newline-delimited, but every consumer reads it back with `IFS= read -r`, so a
# space in a filename is still handled. A NUL-delimited list would be stricter,
# but command substitution silently drops NUL bytes, which is worse.
APK_LIST=$(find "$D" -maxdepth 1 -type f -name '*.apk' | sort)
COUNT=$(printf '%s\n' "$APK_LIST" | grep -c .)
[ "$COUNT" -eq 5 ] && ok "5 APK в dist/" || bad "APK в dist/: $COUNT (ожидается 5)"

if [ "$COUNT" -eq 0 ]; then echo "no APKs in $D" >&2; exit 1; fi

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
done <<< "$(apks)"

echo "== 1b. пять APK действительно разные =="
UNIQ=$(apks | xargs -d '\n' sha256sum 2>/dev/null | awk '{print $1}' | sort -u | grep -c .)
[ "$UNIQ" -eq "$COUNT" ] && ok "все $COUNT APK различны" \
  || bad "уникальных содержимых: $UNIQ из $COUNT — есть копии"

echo "== 2. версия и подпись (каждый APK) =="
while IFS= read -r apk; do
  name=$(basename "$apk")
  B=$($BT/aapt2 dump badging "$apk" 2>/dev/null)
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
    [ "$sz" -gt 1000000 ] || { bad_so=$((bad_so+1)); echo "       $abi: .so всего $sz байт"; }
    # A real ELF, not a bag of strings prefixed with the magic. readelf -h has
    # to parse the header and readelf -d the program headers; 1.5 MB of "B"
    # behind \x7fELF satisfied every marker check below while exporting
    # nothing. If readelf is missing this degrades to the old behaviour rather
    # than failing the build on a missing optional tool.
    if command -v readelf >/dev/null 2>&1; then
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
    fi
    strings "$so" > "$T/so.strings"
    for sym in 'Java_io_openflux' '_cgoexp'; do
      grep -qF "$sym" "$T/so.strings" || { bad_so=$((bad_so+1)); echo "       $abi: нет символа $sym"; }
    done
    # The control strings prove the .so really unpacked; without them the
    # marker results below would be meaningless.
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
  git -C "$d" diff --quiet --ignore-cr-at-eol -- . 2>/dev/null || return 1
  git -C "$d" diff --cached --quiet --ignore-cr-at-eol -- . 2>/dev/null || return 1
  [ -z "$(git -C "$d" ls-files --others --exclude-standard 2>/dev/null)" ] || return 1
  return 0
}
for mod in OpenFlux shared; do
  if [ -e "$ROOT/$mod/.git" ]; then
    if tree_clean "$ROOT/$mod"; then
      ok "модуль $mod: рабочее дерево совпадает с HEAD"
    else
      bad "модуль $mod: есть незакоммиченные изменения — APK им не соответствует"
      git -C "$ROOT/$mod" ls-files --others --exclude-standard 2>/dev/null | head -3 | sed 's/^/        новый: /'
      git -C "$ROOT/$mod" diff --name-only --ignore-cr-at-eol 2>/dev/null | head -3 | sed 's/^/        изменён: /'
      echo "        (сначала закоммитьте, потом пересоберите: отпечаток берётся с HEAD)"
    fi
  fi
done

echo "== 5b. исходники не выпотрошены =="
# A clean tree proves nothing about how much of it there is. Emptying
# androidApp/src and shared/src to zero files still passed, because the >=200
# floor was met entirely by the core tree and its .git directory. Reducing every
# .go to a bare "package X" passed too, and go vet is vacuously clean on an
# empty package - 19 lines standing in for 40k.
kt=$(find "$ROOT/androidApp/src" "$ROOT/shared/src" -name '*.kt' -type f 2>/dev/null | grep -c . || true)
[ "${kt:-0}" -ge 60 ] && ok "Kotlin-исходники на месте ($kt .kt)" \
                     || bad "Kotlin-исходников всего ${kt:-0} — ожидалось не меньше 60"
gof=$(find "$ROOT/OpenFlux" -name '*.go' -type f -not -path '*/.git/*' 2>/dev/null | grep -c . || true)
[ "${gof:-0}" -ge 80 ] && ok "Go-исходники на месте ($gof .go)" \
                      || bad "Go-исходников всего ${gof:-0} — ожидалось не меньше 80"
golines=$(find "$ROOT/OpenFlux" -name '*.go' -type f -not -path '*/.git/*' -exec cat {} + 2>/dev/null | wc -l)
[ "${golines:-0}" -ge 20000 ] && ok "Go-кода достаточно ($golines строк)" \
                             || bad "Go-кода всего ${golines:-0} строк — похоже на заглушки"

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
    if command -v dexdump >/dev/null 2>&1; then
      if dexdump -f "$d" 2>/dev/null | grep -q 'class_defs_size'; then
        :
      else
        badmagic=$((badmagic + 1))
        echo "       $(basename "$d"): dexdump не разобрал dex"
      fi
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
    # Per file, never concatenated. `find ... -exec xxd -p {} \; | tr -d '\n'`
    # glued the last bytes of one resource to the first bytes of the next, so a
    # colour absent from the APK entirely was "found" across the seam - and
    # whether it was found depended on readdir order, so the check was flaky
    # even when it passed.
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
    n=$(find "$ROOT/androidApp/src" "$ROOT/shared/src" "$ROOT/OpenFlux" \
            -type f -newer "$apk" 2>/dev/null | grep -c .)
    [ "$n" -eq 0 ] && ok "$(basename "$apk"): новее исходников" \
                   || bad "$(basename "$apk"): $n файлов исходников новее — сборка устарела"
  done <<< "$(apks)"
fi

echo
[ "$FAIL" -eq 0 ] && echo "AUDIT_OK" || echo "AUDIT_FAILED"
exit $FAIL
