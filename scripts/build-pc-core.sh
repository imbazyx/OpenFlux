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

# The desktop app shows this next to its own version; without it the field
# would read "встроенное" for a core that is plainly a specific build.
git -C "$ROOT" rev-parse --short HEAD > "$VER"
echo "== версия ядра: $(cat "$VER") =="
