#!/usr/bin/env bash
# Builds the Go core as a Windows program and drops it where the desktop app
# expects it. Kept apart from the Android core build on purpose: gomobile wraps
# the same Go code in an .aar for the phone and this is a bare .exe for the PC,
# and one script doing both would grow a branch only one caller uses.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/OpenFluxPC/resources/windows"
VER="$OUT/openflux-core.version"

export PATH="$PATH:/usr/local/go/bin"

mkdir -p "$OUT"
cd "$ROOT/OpenFlux"

echo "== Go $(go version | cut -d' ' -f3) =="
# CGO_ENABLED=0: the core needs no libc, and a static binary is one fewer thing
# that can be missing on the user's machine. -trimpath so the build does not
# leak the builder's directory into the panic traces we ask users to send.
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" \
  -o "$OUT/openflux-windows-amd64.exe" .
echo "== собрано =="
ls -la "$OUT/openflux-windows-amd64.exe"

# Wintun, the driver the full tunnel needs.
#
# The core loads it with LOAD_LIBRARY_SEARCH_APPLICATION_DIR, which means the
# folder the core itself is in, and nothing in this repository ever put it
# there. The app checked for it and correctly refused: the full tunnel could
# not have worked, and the reason was printed in the error. Two things had to
# be true at once and neither was - the file was absent, and the message named
# a script, scripts/build-core.sh, that does not exist in this repository.
#
# The archive hash is the one wintun.net publishes for this release, so this
# verifies rather than trusts. LICENSE.txt travels with the DLL because that
# licence requires the notice to stay with it, and because the terms permit
# redistribution exactly in the shape used here: shipped alongside software
# that talks to it only through the published wintun.h API.
WINTUN_VERSION=0.14.1
WINTUN_SHA256=07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51
WINTUN_URL="https://www.wintun.net/builds/wintun-$WINTUN_VERSION.zip"

fetch_wintun() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  echo "== wintun $WINTUN_VERSION =="
  curl -sS -L --max-time 180 -o "$tmp/wintun.zip" "$WINTUN_URL"
  local got
  got="$(sha256sum "$tmp/wintun.zip" | cut -d' ' -f1)"
  if [ "$got" != "$WINTUN_SHA256" ]; then
    echo "!! архив wintun не совпал с опубликованным хешем" >&2
    echo "   ожидалось $WINTUN_SHA256" >&2
    echo "   получено  $got" >&2
    exit 1
  fi
  echo "   хеш совпал"
  unzip -o -q "$tmp/wintun.zip" -d "$tmp/w"
  # arm64 is not built for the PC client, so only the architecture it ships.
  install -m 0644 "$tmp/w/wintun/bin/amd64/wintun.dll" "$OUT/wintun.dll"
  install -m 0644 "$tmp/w/wintun/LICENSE.txt" "$OUT/wintun-LICENSE.txt"
  echo "   $(ls -la "$OUT/wintun.dll" | awk '{print $5}') байт, лицензия рядом"
}

fetch_wintun

# The desktop app shows this next to its own version; without it the field
# would read "встроенное" for a core that is plainly a specific build.
git -C "$ROOT" rev-parse --short HEAD > "$VER"
echo "== версия ядра: $(cat "$VER") =="
echo "== содержимое $OUT =="
ls -la "$OUT"
