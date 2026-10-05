#!/usr/bin/env bash
# Builds the Go core as a linux program for the exit nodes, and writes a
# .version file beside it recording which commit produced it.
#
#   scripts/build-exit-core.sh              # amd64, the architecture the fleet runs
#   scripts/build-exit-core.sh arm64        # the other one node-install.sh pins
#   scripts/build-exit-core.sh --dirty-ok   # for reproducing an old build only
#
# Why this exists: the exit-node binary used to be built by hand, and the one
# running on the production box carried `vcs.modified=true` in its build info -
# built from a dirty tree, so no commit reproduces it and nothing on disk can
# say what the fleet is actually executing. A build that records the commit and
# refuses a dirty tree is the fix; the fleet's next binary should carry a
# revision that can be checked against this repository.
#
# Refuses a dirty tree by default. A binary whose provenance is unknown is the
# problem this script is here to stop, so it must not be able to produce one by
# accident. --dirty-ok exists only to reproduce a specific old binary and is not
# for anything else.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH=amd64
ALLOW_DIRTY=0
for arg in "$@"; do
  case "$arg" in
    --dirty-ok) ALLOW_DIRTY=1 ;;
    amd64 | arm64 | arm) ARCH="$arg" ;;
    *) echo "usage: $0 [amd64|arm64|arm] [--dirty-ok]" >&2; exit 2 ;;
  esac
done

# Tracked changes only. Untracked files cannot change the build of the package
# being compiled unless they are Go files in the package, and those would be
# reported by go build itself; this asks git about what it is responsible for.
DIRTY="$(git -C "$ROOT" status --porcelain --untracked-files=no)"
if [ -n "$DIRTY" ] && [ "$ALLOW_DIRTY" -eq 0 ]; then
  echo "!! рабочее дерево изменено - сборка будет помечена как vcs.modified" >&2
  echo "$DIRTY" | head -5 >&2
  echo "   закоммитьте, либо --dirty-ok для воспроизведения старой сборки" >&2
  exit 1
fi

export PATH="$PATH:/usr/local/go/bin"

OUT="$ROOT/dist/exit"
mkdir -p "$OUT"
cd "$ROOT/OpenFlux"

REV="$(git -C "$ROOT" rev-parse HEAD)"
SHORT="$(git -C "$ROOT" rev-parse --short HEAD)"
BIN="$OUT/openflux-linux-$ARCH"

echo "== Go $(go version | cut -d' ' -f3) =="
echo "== $SHORT -> $BIN =="
# CGO_ENABLED=0 so the node needs nothing from the host's libc; -trimpath so a
# panic on the node reports repository-relative paths; -s -w to match the sizes
# the release assets are pinned at.
#
# No -X stamp: Go embeds vcs.revision into every binary it builds from a git
# tree, and that is the provenance record, checked below. Inventing a second
# one would be a symbol that has to exist in main.go to mean anything.
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$BIN" .

if go version -m "$BIN" 2>/dev/null | grep -q 'vcs.modified=true'; then
  echo "!! в бинаре vcs.modified=true: дерево было грязным на момент сборки" >&2
  exit 1
fi

printf '%s\n' "$SHORT" > "$OUT/openflux-core.version"

echo "== версия ядра: $(cat "$OUT/openflux-core.version") =="
# The point of the whole script: these two lines are what `go version -m` on
# the node has to agree with. The second one absent is the whole problem.
go version -m "$BIN" | grep -E 'vcs\.(revision|modified)'
echo "== собранный бинарь =="
ls -la "$BIN"
sha256sum "$BIN"