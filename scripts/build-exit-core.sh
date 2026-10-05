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
REPRODUCIBLE=0
for arg in "$@"; do
  case "$arg" in
    --dirty-ok) ALLOW_DIRTY=1 ;;
    --reproducible) REPRODUCIBLE=1 ;;
    amd64 | arm64 | arm) ARCH="$arg" ;;
    *) echo "usage: $0 [amd64|arm64|arm] [--reproducible] [--dirty-ok]" >&2; exit 2 ;;
  esac
done

# Tracked changes only. --untracked-files=no has two real blind spots, measured
# on a scratch repo: an untracked new file is invisible to it, and a file
# marked `git update-index --assume-unchanged` is invisible to it too. This
# check is a fast refusal, not the safety net. The safety net is `vcs.modified`
# in the built binary further down, which go derives from the real build inputs
# and which does catch the untracked case; --reproducible passes
# -buildvcs=false and carries no such stamp, and is covered instead by the
# sha256 comparison against the pin.
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
# -tags exitnode is not optional: it is what the published node asset is built
# with, and without it the desktop node wizard and provision/pin.go are compiled
# into a binary that has no business carrying them. Building without the tag
# produced a DIFFERENT binary from the one node-install.sh pins - same tree,
# same version, not the same file, which is worse than not building it at all.
#
# CGO_ENABLED=0 so the node needs nothing from the host's libc; -trimpath so a
# panic on the node reports repository-relative paths.
#
# The VCS stamp is kept on purpose. node-release.yml passes -buildvcs=false and
# an empty -buildid= so two builds of one commit are byte-identical; that is
# right for an asset that gets hashed, and wrong for the binary that runs on
# the fleet, because a binary that can name its commit is one nobody has to
# guess at from an mtime. --reproducible builds the published form instead, for
# checking the pin; the two outputs are meant to differ.
if [ "$REPRODUCIBLE" -eq 1 ]; then
  echo "== воспроизводимая форма (как публикуемый актив) =="
  CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" GOARM=7 go build \
    -trimpath -buildvcs=false -tags exitnode -ldflags "-s -w -buildid=" -o "$BIN" .
else
  CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" go build -trimpath -tags exitnode \
    -ldflags "-s -w" -o "$BIN" .
fi

if [ "$REPRODUCIBLE" -eq 0 ] && go version -m "$BIN" 2>/dev/null | grep -q 'vcs.modified=true'; then
  echo "!! в бинаре vcs.modified=true: дерево было грязным на момент сборки" >&2
  exit 1
fi

printf '%s\n' "$SHORT" > "$OUT/openflux-core.version"

GOT="$(sha256sum "$BIN" | cut -d' ' -f1)"
echo "== версия ядра: $(cat "$OUT/openflux-core.version") =="
# The point of the whole script: this is what `go version -m` on the node has to
# agree with. A modified=true here is the whole problem.
if [ "$REPRODUCIBLE" -eq 0 ]; then
  go version -m "$BIN" | grep -E 'vcs\.(revision|modified)' || true
fi
echo "== собранный бинарь =="
ls -la "$BIN"
echo "sha256: $GOT"

# Compared against the value deploy/node-install.sh pins, so "this build matches
# what a fresh node would download" is a fact rather than an assumption. Only
# the published form can match: the fleet build deliberately keeps its VCS
# stamp, which the published one drops, so the two differ by design.
PINNED=""
case "$ARCH" in
  amd64) PINNED="$(sed -n 's/^SHA_amd64="\(.*\)"$/\1/p' "$ROOT/OpenFlux/deploy/node-install.sh")" ;;
  arm64) PINNED="$(sed -n 's/^SHA_arm64="\(.*\)"$/\1/p' "$ROOT/OpenFlux/deploy/node-install.sh")" ;;
  arm)   PINNED="$(sed -n 's/^SHA_arm="\(.*\)"$/\1/p' "$ROOT/OpenFlux/deploy/node-install.sh")" ;;
esac
if [ -z "$PINNED" ]; then
  echo "   закреплённый хеш в deploy/node-install.sh не найден - не сверяем" >&2
elif [ "$REPRODUCIBLE" -eq 0 ]; then
  echo "   сверено бы с закреплённым: $PINNED (здесь VCS-штамп, поэтому не равны)"
elif [ "$GOT" = "$PINNED" ]; then
  echo "   СОВПАДАЕТ с закреплённым в node-install.sh"
else
  echo "!! НЕ СОВПАДАЕТ с закреплённым в node-install.sh" >&2
  echo "   закреплено $PINNED" >&2
  echo "   получено  $GOT" >&2
  echo "   сверка осмысленна только на закреплённом теге: дерево сейчас" >&2
  echo "   $SHORT, а пин относится к $(sed -n 's/^CORE_VERSION="\(.*\)"$/\1/p' "$ROOT/OpenFlux/deploy/node-install.sh")." >&2
  echo "   Второй возможный повод - другая версия Go." >&2
  exit 1
fi