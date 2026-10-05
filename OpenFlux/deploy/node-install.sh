#!/bin/sh
# Managed by OpenFlux node-install.sh
#
# Installs one OpenFlux exit channel on a Linux VDS. The "Своя нода" wizard
# of the OpenFlux apps downloads this file by a pinned commit, checks
# its SHA-256 and runs it over SSH. Every channel is independent: its own
# document, key, port and systemd instance (openflux-node@<channel>).
# Nothing outside the paths below is touched, so existing services (other
# OpenFlux installs, Docker, VPNs) keep running.
#
#   /opt/openflux-node/bin/            core binaries (from GitHub Releases)
#   /etc/openflux-node/<channel>/      node.conf, encryption-key (0640), port,
#                                      firewall; the directory is 0751 so a
#                                      plain user's plan sees the channel and
#                                      its port but not its secrets
#   /var/lib/openflux-node/<channel>/  cookie store (systemd StateDirectory)
#   /etc/systemd/system/openflux-node@.service
#
# Usage: node-install.sh probe
#        node-install.sh plan|status              (config on stdin)
#        node-install.sh apply|remove CONFIG_FILE (run as root)
#        node-install.sh upgrade                  (run as root: move every
#                                                 channel to this core)
#        node-install.sh set-cookies CONFIG_FILE  (run as root: replace a
#                                                 channel's Yandex login)
# The config is "key=value" lines: channel, url, key, port, and optionally
# cookies: the channel's Yandex sign-in as the core's cookie store JSON,
# base64-encoded. It goes to /var/lib/openflux-node/<channel>/cookies.json
# (0600, owned by the node user) and is never printed. apply and remove
# take it from a 0600 temp file, which they delete after reading, so that
# stdin stays free for `sudo -S` (a wrong sudo password would otherwise make
# sudo read the config as further password attempts). Secrets never appear
# in arguments, so they stay out of ps and shell history.
# Output is one JSON object on stdout.

set -u
umask 077

CORE_VERSION="node-v1.0.0"
# This fork's repository, not the one the script came from. The binaries it
# fetches are built from the core next to this file, so a node installed from
# here runs the code that the phone and the PC client run, rather than a
# different build that happens to share a name.
CORE_BASE="https://github.com/imbazyx/OpenFlux/releases/download/$CORE_VERSION"
SHA_amd64="f099a88bcac36565990ce84bc0ca3516d7baa06e4d6fb74ce1e6c6cfca536877"
SHA_arm64="a1ed5da92632f67408e43d99b9e0cb9bd6c13206a71c56e642024ee142d34770"
SHA_arm="0df77e692c1c876eb82c4e13e6a5542839226bdecf5a7e3c7dd2757f3d29c1cc"

BIN_DIR="/opt/openflux-node/bin"
CONF_ROOT="/etc/openflux-node"
STATE_ROOT="/var/lib/openflux-node"
UNIT_FILE="/etc/systemd/system/openflux-node@.service"
NODE_USER="openflux-node"
MARKER="# Managed by OpenFlux node-install.sh"

# ---- output -----------------------------------------------------------------

# fail STEP MESSAGE: prints the error object and exits. Messages are fixed
# strings or validated values, never secrets.
fail() {
    printf '{"ok":false,"step":"%s","error":"%s"}\n' "$1" "$(json_escape "$2")"
    exit 1
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\t\r' '   '
}

json_list() {
    first=1
    printf '['
    for item in "$@"; do
        [ "$first" = 1 ] || printf ','
        printf '"%s"' "$(json_escape "$item")"
        first=0
    done
    printf ']'
}

# ---- environment ------------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo amd64 ;;
        aarch64|arm64) echo arm64 ;;
        armv7l|armv6l|armhf) echo arm ;;
        *) echo "" ;;
    esac
}

core_sha() {
    case "$1" in
        amd64) echo "$SHA_amd64" ;;
        arm64) echo "$SHA_arm64" ;;
        arm) echo "$SHA_arm" ;;
    esac
}

downloader() {
    if have curl; then echo curl; elif have wget; then echo wget; else echo ""; fi
}

fetch() { # URL DEST
    case "$(downloader)" in
        curl) curl -fsSL --retry 3 --connect-timeout 20 -o "$2" "$1" ;;
        wget) wget -q -T 20 -t 3 -O "$2" "$1" ;;
        *) return 1 ;;
    esac
}

sha256_of() {
    if have sha256sum; then sha256sum "$1" | cut -d' ' -f1
    else openssl dgst -sha256 "$1" | sed 's/.*= //'
    fi
}

firewall_kind() {
    # `ufw-inactive` is its own value, and BOTH the plan case and the apply case
    # handle it. Adding the value without the two arms would be worse than the
    # bug: an unhandled `case` value falls through silently and
    # `[ -n "$CREATED_FW" ]` would then be false, so no record is written -
    # which is exactly the leaked-rule defect the record write was fixed for.
    if have ufw && ufw status 2>/dev/null | grep -q '^Status: active'; then echo ufw
    elif have firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then echo firewalld
    # ufw is installed but not enabled. `ufw allow` still persists to
    # /etc/ufw/user.rules and exits 0 while inactive, so running it now means a
    # later `ufw enable` cannot lock this channel out. Reporting this as "none"
    # instead - which is what happened - wrote no rule and no record, and the
    # first ordinary `ufw enable` on a fresh Debian box then dropped the node's
    # direct transport with nothing in $CONF_ROOT/$CHANNEL to diagnose it with.
    elif have ufw; then echo ufw-inactive
    else echo none
    fi
}

sudo_mode() {
    if [ "$(id -u)" = 0 ]; then echo root
    elif ! have sudo; then echo none
    elif sudo -n true 2>/dev/null; then echo nopasswd
    else echo password
    fi
}

list_channels() {
    [ -d "$CONF_ROOT" ] || return 0
    for d in "$CONF_ROOT"/*/; do
        [ -d "$d" ] && basename "$d"
    done
}

port_busy() { # PORT
    if have ss; then
        [ -n "$(ss -Hltn "sport = :$1" 2>/dev/null)" ]
    elif have netstat; then
        netstat -ltn 2>/dev/null | awk '{print $4}' | grep -q "[:.]$1\$"
    else
        return 1
    fi
}

port_claimed() { # PORT: another of our channels is configured for it
    [ -d "$CONF_ROOT" ] || return 1
    grep -qsx "$1" "$CONF_ROOT"/*/port
}

pick_port() {
    seed=$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')
    i=0
    while [ $i -lt 200 ]; do
        p=$(( 20000 + (seed + i * 7919) % 40000 ))
        if ! port_busy "$p" && ! port_claimed "$p"; then echo "$p"; return 0; fi
        i=$((i + 1))
    done
    echo ""
}

# ---- input ------------------------------------------------------------------

CHANNEL=""; URL=""; KEY=""; PORT=""; COOKIES=""

# read_config [FILE]: reads stdin, or FILE and then deletes it.
read_config() {
    if [ $# -gt 0 ]; then
        [ -f "$1" ] || fail input "нет файла конфигурации"
        # Считать и снести ДО разбора. Раньше `rm -f` стоял после рекурсивного
        # вызова, а `fail` из любой проверки делает exit - и файл, в котором
        # лежит ключ канала и cookies, оставался на диске. Комментарий выше
        # обещал удаление, и обещал верно только на успешном пути.
        cfg_data="$(cat "$1")" || fail input "не читается файл конфигурации"
        rm -f "$1"
        read_config <<EOF
$cfg_data
EOF
        cfg_data=""
        return
    fi
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            channel=*) CHANNEL=${line#channel=} ;;
            url=*) URL=${line#url=} ;;
            key=*) KEY=${line#key=} ;;
            port=*) PORT=${line#port=} ;;
            cookies=*) COOKIES=${line#cookies=} ;;
            "") ;;
            *) fail input "неизвестная строка конфигурации" ;;
        esac
    done
}

valid_channel() { printf '%s' "$1" | grep -Eq '^[a-z0-9][a-z0-9-]{0,30}$'; }
valid_key() { printf '%s' "$1" | grep -Eq '^[0-9a-f]{64}$'; }
valid_url() {
    printf '%s' "$1" | grep -Eq '^https://(docs|disk)\.yandex\.(ru|com|by|kz|uz)/edit/d/[A-Za-z0-9_-]{16,200}$'
}
valid_port() {
    printf '%s' "$1" | grep -Eq '^[0-9]{4,5}$' && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

# write_cookies: decodes COOKIES into the channel's cookie store, which the
# node loads at start. Needs the node user to exist.
write_cookies() {
    printf '%s' "$COOKIES" | grep -Eq '^[A-Za-z0-9+/]+={0,2}$' || return 1
    [ ${#COOKIES} -le 65536 ] || return 1
    # Its own variable, not the global `dir`. Assigning the global here is what
    # put 5691f59's bug in the world: cmd_apply sets dir="$CONF_ROOT/$CHANNEL",
    # calls write_cookies, and then writes the firewall record through `dir` -
    # which by then pointed at STATE. `local` is not POSIX, and this script runs
    # under whatever /bin/sh is, so the name is distinct rather than scoped.
    cookies_dir="$STATE_ROOT/$CHANNEL"
    # umask 077 would make the parent 0700 and lock the node user out of
    # its own state directory (systemd only creates it when missing).
    mkdir -p "$STATE_ROOT" && chmod 0755 "$STATE_ROOT" || return 1
    mkdir -p "$cookies_dir" || return 1
    # Only if this run is what brought it into existence. The sign-in written
    # here is the one thing on the node there is no way to get back, and
    # rollback deletes the directory when the install fails later.
    [ "$STATE_PREEXISTED" = 0 ] && CREATED_STATE=1
    tmp="$cookies_dir/.cookies.json.new"
    if have base64; then
        printf '%s' "$COOKIES" | base64 -d > "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    else
        printf '%s' "$COOKIES" | openssl base64 -d -A > "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    fi
    head -c 1 "$tmp" | grep -q '{' || { rm -f "$tmp"; return 1; }
    chown "$NODE_USER:$NODE_USER" "$cookies_dir" "$tmp" && chmod 0600 "$tmp" && mv -f "$tmp" "$cookies_dir/cookies.json"
}

check_channel() {
    [ -n "$CHANNEL" ] || fail input "не указан канал"
    valid_channel "$CHANNEL" || fail input "имя канала: только a-z, 0-9 и дефис, до 31 символа"
}

# ---- commands ---------------------------------------------------------------

cmd_probe() {
    arch=$(detect_arch)
    systemd=false
    [ -d /run/systemd/system ] && have systemctl && systemd=true
    os=""
    [ -r /etc/os-release ] && os=$(. /etc/os-release && printf '%s %s' "${ID:-linux}" "${VERSION_ID:-}")
    # shellcheck disable=SC2046
    printf '{"ok":true,"arch":"%s","os":"%s","systemd":%s,"sudo":"%s","downloader":"%s","firewall":"%s","core":"%s","channels":%s}\n' \
        "$arch" "$(json_escape "$os")" "$systemd" "$(sudo_mode)" "$(downloader)" "$(firewall_kind)" \
        "$CORE_VERSION" "$(json_list $(list_channels))"
}

# plan: read-only. Tells the app exactly what apply will change.
cmd_plan() {
    read_config
    check_channel
    arch=$(detect_arch)
    [ -n "$arch" ] || fail plan "архитектура $(uname -m) не поддерживается"
    [ -d /run/systemd/system ] && have systemctl || fail plan "на сервере нет systemd"
    [ -n "$(downloader)" ] || fail plan "на сервере нет curl или wget"
    [ -d "$CONF_ROOT/$CHANNEL" ] && fail plan "канал $CHANNEL уже существует на сервере"
    if [ -n "$PORT" ]; then
        valid_port "$PORT" || fail plan "порт должен быть в диапазоне 1024-65535"
        if port_busy "$PORT" || port_claimed "$PORT"; then fail plan "порт $PORT занят"; fi
    else
        PORT=$(pick_port)
        [ -n "$PORT" ] || fail plan "не удалось найти свободный порт"
    fi
    if [ -f "$UNIT_FILE" ] && ! grep -qF "$MARKER" "$UNIT_FILE"; then
        fail plan "$UNIT_FILE создан не мастером OpenFlux, не трогаю его"
    fi

    set --
    id "$NODE_USER" >/dev/null 2>&1 || set -- "$@" "Создать системного пользователя $NODE_USER (без входа и домашней папки)"
    if [ -x "$BIN_DIR/openflux-$CORE_VERSION" ]; then
        set -- "$@" "Использовать уже установленное ядро OpenFlux $CORE_VERSION"
    else
        set -- "$@" "Скачать ядро OpenFlux $CORE_VERSION (linux-$arch) с GitHub и сверить SHA-256 в $BIN_DIR"
    fi
    set -- "$@" "Создать $CONF_ROOT/$CHANNEL: node.conf и ключ шифрования канала (права 0640)"
    [ -n "$COOKIES" ] && set -- "$@" "Сохранить вход в Яндекс для этого канала в $STATE_ROOT/$CHANNEL/cookies.json (права 0600, только для ноды)"
    [ -f "$UNIT_FILE" ] || set -- "$@" "Установить шаблон systemd $UNIT_FILE"
    set -- "$@" "Запустить openflux-node@$CHANNEL: Яндекс Документ (основной) и direct на порту $PORT/tcp (резерв)"
    case "$(firewall_kind)" in
        ufw) set -- "$@" "Разрешить входящий $PORT/tcp в ufw" ;;
        firewalld) set -- "$@" "Разрешить входящий $PORT/tcp в firewalld" ;;
        # Said out loud, not omitted. The apply case has no `none` branch either,
        # so without this the plan lists the direct transport and never mentions
        # that nothing will be filtering it.
        none) set -- "$@" "Брандмауэр не активен: $PORT/tcp будет доступен из сети без ограничений" ;;
        ufw-inactive)
            set -- "$@" "ufw установлен, но выключен: правило для $PORT/tcp будет сохранено и сработает при 'ufw enable'" ;;
    esac

    # shellcheck disable=SC2046
    printf '{"ok":true,"channel":"%s","port":%s,"arch":"%s","core":"%s","actions":%s,"untouched":%s}\n' \
        "$CHANNEL" "$PORT" "$arch" "$CORE_VERSION" "$(json_list "$@")" "$(json_list $(list_channels))"
}

# Rollback state for apply: what this run created.
CREATED_USER=0; CREATED_UNIT=0; CREATED_BIN=0; CREATED_CONF=0; CREATED_FW=""; STARTED=0
# Where $BIN_DIR/openflux pointed before this run touched it, so rollback can put
# it back. Empty means there was no symlink, and rollback removes ours.
PREV_LINK=""
# What was already there before this run touched it. 1 = it existed. Rollback
# removes nothing that was not created here.
CONF_PREEXISTED=0; STATE_PREEXISTED=0; FW_PREEXISTED=0
CREATED_STATE=0

rollback() {
    [ "$STARTED" = 1 ] && systemctl disable --now "openflux-node@$CHANNEL" >/dev/null 2>&1
    # Only a rule this run actually added. `ufw allow` on a port that is
    # already allowed is a no-op that still returns 0, and `ufw delete allow`
    # matches on the rule spec, not on the comment we wrote - so unconditionally
    # deleting removed the admin's own dormant `ufw allow 8443/tcp` for a
    # service that happened to be down. port_busy only rejects a LISTENING
    # socket, so such a port was freely pickable.
    case "$CREATED_FW" in
        ufw) [ "$FW_PREEXISTED" = 0 ] && ufw delete allow "$PORT/tcp" >/dev/null 2>&1 ;;
        firewalld) [ "$FW_PREEXISTED" = 0 ] && firewall-cmd --permanent --remove-port="$PORT/tcp" >/dev/null 2>&1 && firewall-cmd --reload >/dev/null 2>&1 ;;
    esac
    # Two separate guards, because the two directories have different histories.
    # $CONF_ROOT/$CHANNEL is checked for existence before it is created; the
    # state directory never was, so a channel whose config was removed out of
    # band kept its Yandex sign-in - the one thing there is no way to get back -
    # and the next failed install deleted it. Nothing is removed that was not
    # created by this run.
    [ "$CREATED_CONF" = 1 ] && [ "$CONF_PREEXISTED" = 0 ] && rm -rf "${CONF_ROOT:?}/$CHANNEL"
    [ "$CREATED_STATE" = 1 ] && rm -rf "${STATE_ROOT:?}/$CHANNEL"
    [ "$CREATED_UNIT" = 1 ] && rm -f "$UNIT_FILE" && systemctl daemon-reload >/dev/null 2>&1
    if [ "$CREATED_BIN" = 1 ]; then
        rm -f "$BIN_DIR/openflux-$CORE_VERSION"
        # The symlink, not just the file it named. Every unit this installer
        # wrote runs ExecStart=$BIN_DIR/openflux, so deleting the core we
        # pointed it at and leaving the link pointing there bricks every
        # channel that was already installed - rollback made things worse than
        # not having run at all.
        if [ -n "$PREV_LINK" ]; then
            ln -sfn "$PREV_LINK" "$BIN_DIR/openflux"
        else
            rm -f "$BIN_DIR/openflux"
        fi
    fi
    [ "$CREATED_USER" = 1 ] && userdel "$NODE_USER" >/dev/null 2>&1
}

apply_fail() {
    rollback
    fail "$1" "$2"
}

# install_core ARCH: puts this script's core version into BIN_DIR, checked
# against its pinned SHA-256, and points BIN_DIR/openflux at it. Sets
# CREATED_BIN when it downloaded, CORE_ERROR on failure.
CORE_ERROR=""
install_core() {
    mkdir -p "$BIN_DIR" && chmod 0755 /opt/openflux-node "$BIN_DIR"
    core="$BIN_DIR/openflux-$CORE_VERSION"
    want=$(core_sha "$1")
    if [ ! -x "$core" ] || [ "$(sha256_of "$core")" != "$want" ]; then
        # Named for what it is, not `tmp`. write_cookies used the global `tmp`
        # as well, and so does anything else added later; install_core happens
        # to run first today, which is the only reason a downloaded core has
        # never landed in the cookies path. One global renamed away from a
        # collision that only the current call order prevents.
        tmp_core=$(mktemp "$BIN_DIR/.download.XXXXXX") || { CORE_ERROR="не удалось создать временный файл"; return 1; }
        if ! fetch "$CORE_BASE/openflux-linux-$1" "$tmp_core"; then
            rm -f "$tmp_core"
            CORE_ERROR="не удалось скачать ядро с GitHub"
            return 1
        fi
        if [ "$(sha256_of "$tmp_core")" != "$want" ]; then
            rm -f "$tmp_core"
            CORE_ERROR="SHA-256 скачанного ядра не совпал, установка остановлена"
            return 1
        fi
        # CREATED_BIN means "this run brought the file into existence", not
        # "this run downloaded something". The branch above is entered when the
        # file is present but has lost its executable bit or fails its hash, so
        # mv -f here OVERWRITES a core that was already installed and set
        # CREATED_BIN=1 regardless. A later failure then deleted that core in
        # rollback() and re-pointed PREV_LINK - captured as its name - at the
        # file just deleted, leaving a dangling link that bricked every channel
        # already using it. The one thing the comment below says this prevents.
        [ -e "$core" ] || CREATED_BIN=1
        # Checked. This is the LAST statement of the `if` body, so its status was
        # the function's - until the symlink line below became the last one and
        # took over. A failed chmod or mv (immutable file, ENOSPC, read-only
        # mount) then produced a missing or non-executable core, a symlink
        # repointed at it, and a ZERO from install_core - so `|| apply_fail
        # download` and `|| fail upgrade` could never fire. It surfaced only as
        # "нода не запустилась" with a journal excerpt, which names neither the
        # file nor the cause.
        chmod 0755 "$tmp_core" && mv -f "$tmp_core" "$core" || {
            CORE_ERROR="не удалось установить ядро в $core"
            return 1
        }
    fi
    # Recorded before the link moves, and only the first time, so the target that
    # must be restored is the one from before the run started.
    [ -n "$PREV_LINK" ] || PREV_LINK=$(readlink "$BIN_DIR/openflux" 2>/dev/null || true)
    ln -sfn "openflux-$CORE_VERSION" "$BIN_DIR/openflux" || {
        CORE_ERROR="не удалось переключить симлинк на $CORE_VERSION"
        return 1
    }
    # And the symlink must now resolve to something executable. Every unit runs
    # this one path, so a link that dangles takes all channels down at once -
    # which is what the PREV_LINK restore exists to undo.
    [ -x "$BIN_DIR/openflux" ] || {
        CORE_ERROR="симлинк $BIN_DIR/openflux не указывает на исполняемый файл"
        return 1
    }
}

write_unit() {
    cat > "$UNIT_FILE" <<EOF || return 1
$MARKER
[Unit]
Description=OpenFlux node channel %i
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$NODE_USER
Group=$NODE_USER
StateDirectory=openflux-node/%i
WorkingDirectory=$STATE_ROOT/%i
ExecStart=$BIN_DIR/openflux --config $CONF_ROOT/%i/node.conf
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "$UNIT_FILE" || return 1
}

cmd_apply() {
    [ "$(id -u)" = 0 ] || fail apply "нужны права root (sudo)"
    read_config "$@"
    check_channel
    valid_key "$KEY" || fail input "ключ канала должен быть 64 hex-символа"
    valid_url "$URL" || fail input "адрес документа должен быть вида https://docs.yandex.ru/edit/d/..."
    valid_port "$PORT" || fail input "не указан порт из плана"
    [ -z "$COOKIES" ] || printf '%s' "$COOKIES" | grep -Eq '^[A-Za-z0-9+/]+={0,2}$'         || fail input "cookies должны быть в base64"
    arch=$(detect_arch)
    [ -n "$arch" ] || fail apply "архитектура $(uname -m) не поддерживается"
    [ -d "$CONF_ROOT/$CHANNEL" ] && fail apply "канал $CHANNEL уже существует на сервере"
    if port_busy "$PORT" || port_claimed "$PORT"; then fail apply "порт $PORT занят"; fi
    if [ -f "$UNIT_FILE" ] && ! grep -qF "$MARKER" "$UNIT_FILE"; then
        fail apply "$UNIT_FILE создан не мастером OpenFlux"
    fi

    if ! id "$NODE_USER" >/dev/null 2>&1; then
        nologin=/usr/sbin/nologin
        [ -x "$nologin" ] || nologin=/sbin/nologin
        [ -x "$nologin" ] || nologin=/bin/false
        if have useradd; then
            useradd --system --no-create-home --home-dir /nonexistent --shell "$nologin" "$NODE_USER" \
                || apply_fail user "не удалось создать пользователя $NODE_USER"
        else
            adduser -S -H -D -s "$nologin" "$NODE_USER" 2>/dev/null \
                || apply_fail user "не удалось создать пользователя $NODE_USER"
        fi
        CREATED_USER=1
    fi

    install_core "$arch" || apply_fail download "$CORE_ERROR"

    mkdir -p "$CONF_ROOT" && chmod 0755 "$CONF_ROOT"
    dir="$CONF_ROOT/$CHANNEL"
    # Recorded BEFORE the mkdir, because after it the answer is always the same.
    # The same question is asked of the state directory: it is created on demand
    # further down and may well have survived from an earlier install whose
    # config was removed by hand, in which case it holds the only copy of the
    # Yandex sign-in.
    [ -e "$dir" ] && CONF_PREEXISTED=1
    [ -e "$STATE_ROOT/$CHANNEL" ] && STATE_PREEXISTED=1
    mkdir "$dir" || apply_fail config "не удалось создать $dir"
    CREATED_CONF=1
    # Checked, and this one matters more than the `port` write two statements
    # below it - which round 26 did check, and which sits directly after this
    # pair. A truncated-but-non-empty key is the worst case of all the writes
    # here: the node starts fine, on the WRONG key, `is-active` passes, apply
    # prints ok:true, and nothing in this script or in the start check can see
    # it. Verified relevant rather than theoretical - the host is at 72%.
    printf '%s\n' "$KEY" > "$dir/encryption-key" \
        || apply_fail config "не удалось записать ключ шифрования в $dir/encryption-key"
    cat > "$dir/node.conf" <<EOF || apply_fail config "не удалось записать $dir/node.conf"
# OpenFlux node channel $CHANNEL, written by node-install.sh
Role = exit
Mode = l4
EncryptionKeyFile = $dir/encryption-key
CookieStore = $STATE_ROOT/$CHANNEL/cookies.json
URL = $URL

[Transport vyandex]
Type = vyandex
Priority = 100
URL = $URL

[Transport direct]
Type = direct
Priority = 50
Listen = 0.0.0.0:$PORT
EOF
    printf '%s
' "$PORT" > "$dir/port" || apply_fail config "не удалось записать порт в $dir/port"
    # The write above is checked for the same reason the chown below is. This
    # file is what `port_claimed` greps to keep two channels off one port, so a
    # failed or short write does not fail loudly - it silently stops matching
    # that channel, and the NEXT install can be handed the same port. Both units
    # then bind, the second crash-loops, and `apply` still prints ok:true: the
    # per-channel start check only covers the channel being installed.
    # Verified relevant rather than theoretical: the host this runs on is at 74%
    # disk, where a short write is a real outcome.
    # Checked, because unchecked they are silent and the answer is still
    # ok:true. A chown that fails leaves node.conf root:root 0640, the node
    # user is not in group root, and the node cannot read its own
    # configuration - a bricked node reported as a successful install. This was
    # observed: chown printed "invalid group" and the script answered
    # {"ok":true,"channel":"t1"} in the same run.
    chown -R "root:$NODE_USER" "$dir" || apply_fail perms "не удалось передать конфигурацию пользователю $NODE_USER: группа не найдена или прав нет"
    chmod 0751 "$dir" || apply_fail perms "не удалось выставить права на каталог конфигурации"
    chmod 0640 "$dir/encryption-key" "$dir/node.conf" || apply_fail perms "не удалось закрыть ключ и конфигурацию"
    chmod 0644 "$dir/port" || apply_fail perms "не удалось выставить права на файл порта"
    if [ -n "$COOKIES" ]; then
        write_cookies || apply_fail cookies "не удалось сохранить вход в Яндекс на сервере"
    fi

    if [ ! -f "$UNIT_FILE" ]; then
        # Checked. This writes the ONE shared unit template every channel's
        # ExecStart comes from, and a `cat >` that fails after truncation leaves
        # it truncated. daemon-reload then succeeds on the broken file, and
        # rollback restores it only when CREATED_UNIT=1 - so a failure in the
        # already-existed branch silently destroys every other channel's unit
        # definition for the next restart. Reported ok:true throughout.
        write_unit || apply_fail systemd "не удалось записать $UNIT_FILE"
        CREATED_UNIT=1
    fi
    systemctl daemon-reload || apply_fail systemd "systemctl daemon-reload не удался"

    case "$(firewall_kind)" in
        ufw)
            # Asked before the rule is added. `ufw allow` on a port that is
            # already permitted is a no-op that still exits 0, and `ufw delete
            # allow` matches on the rule spec rather than on the comment we
            # write - so an admin's own dormant `ufw allow 8443/tcp` for a
            # service that was down at the time was picked up by port_busy
            # (which only rejects a LISTENING socket) and then deleted by
            # rollback, taking a rule this run never added.
            ufw status | grep -qE "^$PORT/tcp[[:space:]]" && FW_PREEXISTED=1
            # Set BEFORE, for the reason the firewalld branch below spells out,
            # and it applies here too. ufw persists the rule to user.rules FIRST
            # and only then applies it to the live chains, so a failing ufw-init
            # returned non-zero with the rule already written. rollback()
            # switches on CREATED_FW, so a flag set after the command matched
            # nothing, cleaned up nothing, and the failed install reported a
            # clean failure with the port open in the config - opening again at
            # the next reload or reboot.
            CREATED_FW=ufw
            ufw allow "$PORT/tcp" comment "openflux-node $CHANNEL" >/dev/null 2>&1 \
                || apply_fail firewall "не удалось открыть порт в ufw" ;;
        ufw-inactive)
            # The FW_PREEXISTED probe is NOT optional here, exactly as in the
            # ufw arm above, and `ufw status` lists rules while inactive just as
            # it does when active - so the probe works identically. Without it
            # rollback() runs `ufw delete allow $PORT/tcp` unconditionally and
            # removes a rule this install never added: an admin's own dormant
            # rule for a service that happened to be down. `ufw delete allow`
            # matches the rule spec, not the comment we write, so the comment
            # does not save it.
            # `ufw status` CANNOT be probed here: with ufw disabled it prints only
            # "Status: inactive" and no rule list at all - verified on ufw 0.36.
            # So the probe below reads the persisted rules directly, which is
            # where ufw keeps them across the enable/disable cycle and where an
            # admin's own rule survives. `ufw delete allow` matches the tuple,
            # not the comment we write, so without this rollback removes a rule
            # this install never added.
            grep -qE "^### tuple ### allow tcp $PORT[[:space:]]" /etc/ufw/user.rules 2>/dev/null \
                && FW_PREEXISTED=1
            CREATED_FW=ufw
            ufw allow "$PORT/tcp" comment "openflux-node $CHANNEL" >/dev/null 2>&1 \
                || apply_fail firewall "не удалось сохранить правило в ufw (брандмауэр выключен)" ;;
        firewalld)
            firewall-cmd --permanent --query-port="$PORT/tcp" >/dev/null 2>&1 && FW_PREEXISTED=1
            # CREATED_FW is set BEFORE the commands, not after. --permanent
            # --add-port writes the permanent zone straight away, so a failure
            # in the --reload that follows left the port open in the config,
            # survived every reboot, and rollback() - which switches on
            # CREATED_FW - matched nothing and cleaned up nothing. A failed
            # install reported a clean failure while quietly opening a port.
            CREATED_FW=firewalld
            { firewall-cmd --permanent --add-port="$PORT/tcp" && firewall-cmd --reload; } >/dev/null 2>&1 \
                || apply_fail firewall "не удалось открыть порт в firewalld" ;;
        none)
            # With neither ufw nor firewalld there is nothing to configure, and
            # the `case` above had no branch for it - so this channel's direct
            # transport went live on 0.0.0.0:$PORT with no rule, no record, and
            # ok:true. VERIFIED: the server these nodes run on has no ufw and no
            # firewall-cmd at all, so this is not a corner case.
            # Recorded rather than refused: the port genuinely is reachable, and
            # the operator has to be told which port and on what grounds. The
            # record is what makes `remove` able to say it closed something.
            CREATED_FW=none ;;
    esac
    # The record belongs in CONF, not in whatever `$dir` holds at this point.
    # PAST TENSE: `write_cookies()` used to assign `dir="$STATE_ROOT/$CHANNEL"`
    # to the GLOBAL dir (this script uses no `local` anywhere), so on any
    # install with cookies this line landed in the state directory while
    # cmd_remove looked in CONF - and the rule survived the channel forever,
    # with the only copy of the record deleted along with the state dir. The
    # same global-d aliasing happened a second time further down, and
    # write_cookies() now uses its own `cookies_dir` instead of touching `dir`.
    # So the reason the path below is spelled out rather than reusing `$dir` is
    # that `$dir` once moved; the reason it stays spelled out is that nothing
    # here should depend on where `$dir` points.
    # install/remove cycle.
    #
    # And it is CHECKED. That file is the only record of the rule: `remove`
    # closes the port by reading it, and nothing else knows which one was opened.
    # A write that failed left the rule open with no trace, and `remove` then
    # reported ok:true while the port stayed reachable forever.
    [ -n "$CREATED_FW" ] && {
        printf '%s %s\n' "$CREATED_FW" "$PORT" > "$CONF_ROOT/$CHANNEL/firewall" \
            || apply_fail firewall "не удалось записать правило брандмауэра в $CONF_ROOT/$CHANNEL/firewall"
    }

    # STARTED before the command, not after: `systemctl enable --now` runs both
    # jobs and returns non-zero if EITHER failed. Enable failing while start
    # succeeded used to reach rollback with STARTED unset, which does not stop
    # or disable the unit - and the very next lines delete the unit file anyway,
    # leaving a running node with no unit and a dangling
    # multi-user.target.wants/openflux-node@<channel>.service symlink.
    STARTED=1
    systemctl enable --now "openflux-node@$CHANNEL" >/dev/null 2>&1 \
        || apply_fail start "не удалось запустить openflux-node@$CHANNEL"
    sleep 4
    if ! systemctl is-active --quiet "openflux-node@$CHANNEL"; then
        logs=$(journalctl -u "openflux-node@$CHANNEL" -n 8 -o cat --no-pager 2>/dev/null | tail -n 8)
        apply_fail start "нода не запустилась: $logs"
    fi
    printf '{"ok":true,"channel":"%s","port":%s,"core":"%s"}\n' "$CHANNEL" "$PORT" "$CORE_VERSION"
}

cmd_remove() {
    [ "$(id -u)" = 0 ] || fail remove "нужны права root (sudo)"
    read_config "$@"
    check_channel
    dir="$CONF_ROOT/$CHANNEL"
    [ -d "$dir" ] || fail remove "канала $CHANNEL нет на сервере"
    # Checked, and this one is not cosmetic. Everything below this line deletes
    # the channel's config, its unit file and its state - so a unit that failed
    # to stop kept running with none of them, and `remove` printed ok:true.
    # A live openflux-node with no configuration is a process nobody has a
    # handle on any more, not a removed channel.
    # The message deliberately does NOT claim "юнит работает". `systemctl
    # disable --now` also exits non-zero for a masked unit or one whose file
    # vanished, and 2>&1 to /dev/null discards the reason, so the only
    # honest statement here is that the stop failed and the operator must look.
    # Claiming the unit is live would send them after a running process when the
    # real state is a masked unit - and, since `fail` exits before the firewall
    # record is read, the rule stays open too. Re-run and read the systemd
    # error directly.
    systemctl disable --now "openflux-node@$CHANNEL" >/dev/null 2>&1 \
        || fail remove "не удалось остановить openflux-node@$CHANNEL; конфигурация и правило брандмауэра оставлены как есть, канал не удалён"
    # Look in CONF first, then in STATE, because the record moved there.
    #
    # Before this was corrected, the record was written to `$dir/firewall` while
    # `dir` had already been reassigned by write_cookies() to the STATE
    # directory - PAST TENSE: write_cookies() now uses its own `cookies_dir`
    # and never touches the global `dir`. Every channel installed by that script
    # WITH cookies - the ordinary case - kept its record in STATE, so remove
    # looked in CONF, found nothing, left the ufw/firewalld rule open forever,
    # and then deleted the channel directory including the only copy of the
    # record. There is no migration anywhere else: nothing in the repo mentions
    # those channels, so this fallback is the only thing that can close their
    # rules.
    fw_record=""
    if [ -f "$dir/firewall" ]; then
        fw_record="$dir/firewall"
    elif [ -f "$STATE_ROOT/$CHANNEL/firewall" ]; then
        fw_record="$STATE_ROOT/$CHANNEL/firewall"
    fi
    if [ -n "$fw_record" ]; then
        read -r kind port < "$fw_record" || port=""
        # Checked, which is the exact mirror of the fix on the APPLY side: the
        # write of this record was made checked because a lost record leaves a
        # rule open forever, and a silently failing DELETE leaves the same
        # result while `remove` prints ok:true. Both halves have to be honest
        # or the guarantee is only half a guarantee.
        #
        # A truncated record also reads an empty `port` here, which would run
        # `ufw delete allow "/tcp"` - a silent no-op - and then report success.
        case "$kind" in
            ufw)
                [ -n "$port" ] || fail remove "запись брандмауэра пуста, порт неизвестен; правило осталось открытым"
                ufw delete allow "$port/tcp" >/dev/null 2>&1 \
                    || fail remove "не удалось закрыть $port/tcp в ufw; правило осталось открытым" ;;
            firewalld)
                [ -n "$port" ] || fail remove "запись брандмауэра пуста, порт неизвестен; правило осталось открытым"
                { firewall-cmd --permanent --remove-port="$port/tcp" && firewall-cmd --reload; } >/dev/null 2>&1 \
                    || fail remove "не удалось закрыть $port/tcp в firewalld; правило осталось открытым" ;;
        esac
    fi
    rm -rf "${CONF_ROOT:?}/$CHANNEL" "${STATE_ROOT:?}/$CHANNEL"
    if [ -z "$(list_channels)" ]; then
        # The last channel is gone: remove everything this script installed.
        rm -f "$UNIT_FILE"
        systemctl daemon-reload >/dev/null 2>&1
        rm -rf /opt/openflux-node "$CONF_ROOT" "$STATE_ROOT"
        userdel "$NODE_USER" >/dev/null 2>&1
    fi
    printf '{"ok":true,"channel":"%s"}\n' "$CHANNEL"
}

# upgrade: switches every channel to this script's core and restarts the
# running ones. Configs, keys and ports stay as they are.
cmd_upgrade() {
    [ "$(id -u)" = 0 ] || fail upgrade "нужны права root (sudo)"
    [ -n "$(list_channels)" ] || fail upgrade "на сервере нет каналов OpenFlux"
    arch=$(detect_arch)
    [ -n "$arch" ] || fail upgrade "архитектура $(uname -m) не поддерживается"
    # The link is put back by hand here, because `fail` exits and rollback()
    # never runs on this path. It has to: install_core re-points the ONE global
    # symlink before it can still fail (the -x check that follows ln -sfn), and
    # every unit's ExecStart is that path. The PREV_LINK restore in rollback is
    # additionally nested inside `if [ "$CREATED_BIN" = 1 ]`, and CREATED_BIN is
    # set only when this run DOWNLOADED the core - so upgrading to a core already
    # on disk would leave every channel on a broken link with no recovery.
    install_core "$arch" || {
        [ -n "$PREV_LINK" ] && ln -sfn "$PREV_LINK" "$BIN_DIR/openflux"
        fail upgrade "$CORE_ERROR"
    }
    set --
    failed=""
    stopped=0
    for ch in $(list_channels); do
        # Once one channel has failed, the rest are neither restarted nor
        # polled - they stay on whatever is in memory. A `break` here would
        # have stopped the loop, and every remaining channel would then appear
        # in NEITHER `restarted` nor `failed`, so the operator would read a
        # complete-looking JSON while three channels were never touched. They
        # are named as failed instead, which is the true thing about them.
        if [ "$stopped" = 1 ]; then
            failed="$failed $ch"
            continue
        fi
        # A channel that is NOT running counts as failed. It used to be skipped
        # by this guard entirely, so it appeared in neither `restarted` nor
        # `failed`, did not block the prune, and produced no hint line: a node
        # whose services were stopped upgraded to ok:true with restarted:[] and
        # every old core deleted. Nothing had checked that the new core works on
        # a channel that is down.
        if ! systemctl is-active --quiet "openflux-node@$ch"; then
            failed="$failed $ch"
            continue
        fi
        systemctl restart "openflux-node@$ch" 2>/dev/null \
            || { failed="$failed $ch"; continue; }
        # systemctl restart returns as soon as the job is done, which for a
        # core that dies on startup is a success followed by "failed" a moment
        # later. So the channel is polled, not assumed. Without this
        # the loop below reported every channel as restarted and the prune
        # then deleted the old core that was the only working one.
        ok=0
        i=0
        while [ "$i" -lt 20 ]; do
            # One settle interval before the first look. The unit is Type=simple,
            # so it reports active the moment exec succeeds and the loop above
            # used to break at i=0 - passing exactly the case this exists for,
            # a core that dies just after systemd saw it start.
            sleep 0.2
            if systemctl is-active --quiet "openflux-node@$ch"; then ok=1; break; fi
            # Stop early if it has already given up rather than waiting out
            # the full four seconds on a unit that is clearly not coming.
            systemctl is-failed --quiet "openflux-node@$ch" && break
            i=$((i + 1))
        done
        if [ "$ok" = 1 ]; then
            set -- "$@" "$ch"
        else
            failed="$failed $ch"
            stopped=1
        fi
    done
    if [ -n "$failed" ]; then
        # The old cores stay exactly where they are. A failed upgrade must leave
        # the server able to start, and it can: the symlink still points at a
        # core that is present.
        #
        # The hint says what is actually true. It used to say "откатите канал на
        # предыдущем ядре", and NO dispatcher command can do that: `upgrade`
        # always installs the single global $CORE_VERSION, `apply` refuses an
        # existing channel, and the unit template has one ExecStart. An operator
        # following that hint would re-point the global symlink by hand - taking
        # every healthy channel off the new core to fix one.
        # Honest about WHICH core each failed channel is on. Only the channel
        # that restarted and then failed is on the new one; the ones skipped
        # after it, and the ones that were not running, were never restarted and
        # still hold the previous core in memory - while the global symlink has
        # already moved. Saying they all "stayed on $CORE_VERSION" was true of
        # strictly fewer channels than the old `break` covered, because the
        # stopped-flag change puts more channels in this list.
        printf '{"ok":false,"core":"%s","restarted":%s,"failed":%s,"hint":"отката по каналу нет: из перечисленных только канал, который перезапустился и не поднялся, остался на ядре %s; остальные не перезапускались и держат в памяти прежнее ядро, хотя %s/openflux уже указывает на новое. Вернуть все каналы можно, переключив %s/openflux на openflux-node-v<прежняя> и перезапустив их. Прежние ядра сохранены в %s"}\n' \
            "$CORE_VERSION" "$(json_list "$@")" "$(json_list $failed)" "$CORE_VERSION" "$BIN_DIR" "$BIN_DIR" "$BIN_DIR"
        return 1
    fi
    # Older cores nothing points at any more - only once every channel is up.
    for old in "$BIN_DIR"/openflux-node-v*; do
        [ "$old" = "$BIN_DIR/openflux-$CORE_VERSION" ] || rm -f "$old"
    done
    printf '{"ok":true,"core":"%s","restarted":%s}
' "$CORE_VERSION" "$(json_list "$@")"
}

# set-cookies: replaces a channel's Yandex sign-in and restarts it.
cmd_set_cookies() {
    [ "$(id -u)" = 0 ] || fail set-cookies "нужны права root (sudo)"
    read_config "$@"
    check_channel
    [ -d "$CONF_ROOT/$CHANNEL" ] || fail set-cookies "канала $CHANNEL нет на сервере"
    [ -n "$COOKIES" ] || fail set-cookies "нет cookies"
    write_cookies || fail set-cookies "не удалось сохранить вход в Яндекс на сервере"
    systemctl restart "openflux-node@$CHANNEL" || fail set-cookies "не удалось перезапустить openflux-node@$CHANNEL"
    printf '{"ok":true,"channel":"%s"}
' "$CHANNEL"
}

cmd_status() {
    read_config
    check_channel
    [ -d "$CONF_ROOT/$CHANNEL" ] || fail status "канала $CHANNEL нет на сервере"
    state=$(systemctl is-active "openflux-node@$CHANNEL" 2>/dev/null)
    printf '{"ok":true,"channel":"%s","state":"%s"}\n' "$CHANNEL" "$(json_escape "$state")"
}

case "${1:-}" in
    probe) cmd_probe ;;
    plan) cmd_plan ;;
    apply) shift; cmd_apply "$@" ;;
    remove) shift; cmd_remove "$@" ;;
    status) cmd_status ;;
    upgrade) cmd_upgrade ;;
    set-cookies) shift; cmd_set_cookies "$@" ;;
    *) fail usage "usage: node-install.sh probe|plan|apply|remove|status|upgrade|set-cookies" ;;
esac
