#!/usr/bin/env bash
# Publish the fork, in the only order that works.
#
# The fork records exact submodule commits (OpenFlux 5e714cd, shared ba86d07).
# If they are not on GitHub when the fork lands, `git clone --recurse-submodules`
# fails to resolve them and aborts - so the submodules go first, always.
#
# Run with --dry-run first: it reports what would be created and pushed and
# touches nothing.
set -uo pipefail

DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

F=/mnt/d/project/OpenFlux/OpenFluxAndroid
GH="/mnt/c/Program Files/GitHub CLI/gh.exe"

say() { printf '%s\n' "$*"; }

ensure_repo() {
  local repo=$1 desc=$2
  if [ "$DRY" = 1 ]; then
    say "  [было бы] создать $repo"
    return
  fi
  if "$GH" repo view "$repo" >/dev/null 2>&1; then
    say "  $repo уже существует, создание пропущено"
  else
    "$GH" repo create "$repo" --public --description "$desc" >/dev/null \
      || { say "  НЕ СМОГ создать $repo" >&2; exit 1; }
    say "  создан $repo"
  fi
}

push_repo() {
  local dir=$1 repo=$2 tags=$3
  if [ "$DRY" = 1 ]; then
    say "  [было бы] отправить main в $repo$([ -n "$tags" ] && echo " + теги: $tags")"
    return
  fi
  if git -C "$dir" push -u origin main >/dev/null 2>&1; then
    say "  отправлена ветка main в $repo"
  else
    say "  push main в $repo НЕ УДАЛСЯ" >&2; exit 1
  fi
  if [ -n "$tags" ]; then
    if git -C "$dir" push origin "$tags" >/dev/null 2>&1; then
      say "  отправлены теги ($tags) в $repo"
    else
      say "  ВНИМАНИЕ: теги ($tags) в $repo не отправлены"
    fi
  fi
}

say "=== 1. репозитории: подмодули создаются первыми ==="
ensure_repo imbazyx/OpenFlux \
  "OpenFlux core: tunnel, transports, negotiation protocol (fork with fixes)"
ensure_repo imbazyx/OpenFluxClientShared \
  "Compose Multiplatform models, services and design system (fork)"

say "=== 2. отправка подмодулей ==="
push_repo "$F/OpenFlux" imbazyx/OpenFlux "0.0.1 0.0.2 0.0.3 0.0.4 0.0.5 v0.1.0 node-v1.0.0"
push_repo "$F/shared"   imbazyx/OpenFluxClientShared ""

say "=== 3. форк приложения ==="
ensure_repo imbazyx/OpenFluxAndroid \
  "Android VPN client for OpenFlux: per-app tunnel selection, multi-transport sessions, AES-256-GCM"
push_repo "$F" imbazyx/OpenFluxAndroid "v1.2.0"

say "=== 4. проверка ==="
if [ "$DRY" = 1 ]; then
  say "  пробный прогон, ничего не изменено"
else
  for r in imbazyx/OpenFlux imbazyx/OpenFluxClientShared imbazyx/OpenFluxAndroid; do
    say "  $r -> $("$GH" repo view "$r" --json name,visibility -q '.name + " " + .visibility')"
  done
  say ""
  say "  ПРОВЕРЬТЕ клонирование (главный риск был именно тут):"
  say "    git clone --recurse-submodules https://github.com/imbazyx/OpenFluxAndroid.git /root/clone-test"
fi
say "готово"
