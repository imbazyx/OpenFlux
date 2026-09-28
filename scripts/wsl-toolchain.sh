#!/usr/bin/env bash
# Toolchain for building OpenFluxAndroid exactly as .github/workflows/release.yml does:
#   Go (from OpenFlux/go.mod) + JDK 17 + Android SDK (platforms 35/36, build-tools 35,
#   NDK 27.0.12077973) + gomobile/gobind at the pinned revision.
set -euo pipefail

# The Go version is whatever the core's go.mod asks for, so the two cannot drift.
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CORE_GOMOD=$(cd "$SELF/.." && pwd)/OpenFlux/go.mod
GOVER=$(awk '/^go /{print $2; exit}' "$CORE_GOMOD")
[ -n "$GOVER" ] || { echo "cannot read the Go version from $CORE_GOMOD" >&2; exit 1; }
MOBREV=v0.0.0-20260908204917-8b95e45f8d3e
NDK=27.0.12077973
BT=35.0.0

export DEBIAN_FRONTEND=noninteractive
echo "== apt =="
sudo apt-get update -qq
# xxd and binutils (strings) are not used by the build but ARE used by
# wsl-audit.sh; without them an audit step silently produces nothing.
sudo apt-get install -y -qq openjdk-17-jdk-headless unzip zip curl git ca-certificates xxd binutils >/dev/null

echo "== java =="
# `|| true`: head exits after one line and java dies of SIGPIPE, which
# pipefail would otherwise turn into a failed toolchain setup.
java -version 2>&1 | head -1 || true

# Compare the INSTALLED version, not just that a binary exists: an old Go left
# over from a previous project would otherwise be reused silently.
have_go=$(/usr/local/go/bin/go version 2>/dev/null | awk '{print $3}' | sed 's/^go//')
if [ "$have_go" != "$GOVER" ]; then
  echo "== go $GOVER (found: ${have_go:-none}) =="
  curl -fsSL "https://go.dev/dl/go${GOVER}.linux-amd64.tar.gz" -o /tmp/go.tgz
  # go.dev publishes a checksum next to every release; TLS alone would not
  # notice a tampered mirror.
  curl -fsSL "https://go.dev/dl/go${GOVER}.linux-amd64.tar.gz.sha256" -o /tmp/go.sha256
  echo "$(cat /tmp/go.sha256)  /tmp/go.tgz" | sha256sum -c -
  sudo rm -rf /usr/local/go
  sudo tar -C /usr/local -xzf /tmp/go.tgz
else
  echo "== go $GOVER already installed =="
fi
export PATH=/usr/local/go/bin:$PATH
go version

SDK=$HOME/android-sdk
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== cmdline-tools =="
  # ponytail: this one download is verified by TLS only. Google publishes
  # checksums inside repository2-*.xml rather than as a sidecar file, so
  # pinning would mean parsing that manifest; add it here if that ever matters.
  curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/ct.zip
  rm -rf /tmp/ct && mkdir -p /tmp/ct
  unzip -q /tmp/ct.zip -d /tmp/ct
  rm -rf "$SDK/cmdline-tools/latest"
  mv /tmp/ct/cmdline-tools "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

echo "== sdk components =="
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-35" "platforms;android-36" \
          "build-tools;$BT" "ndk;$NDK" >/dev/null
ls -d "$SDK"/ndk/* "$SDK"/build-tools/* | sed 's|.*/Sdk/||'

echo "== gomobile =="
export GOPATH=$HOME/go
go install golang.org/x/mobile/cmd/gomobile@$MOBREV
go install golang.org/x/mobile/cmd/gobind@$MOBREV
export PATH=$GOPATH/bin:$PATH
gomobile version

echo "== env for later steps =="
cat > $HOME/ofbuild.env <<EOF
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export ANDROID_NDK_HOME=$SDK/ndk/$NDK
export PATH=/usr/local/go/bin:$GOPATH/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:\$PATH
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
EOF
echo "TOOLCHAIN_OK"
