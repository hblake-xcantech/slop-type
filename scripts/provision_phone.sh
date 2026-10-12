#!/usr/bin/env bash
# Provision / control the Slop Type daemon on a rooted (Magisk) phone over adb.
#
# Usage:
#   scripts/provision_phone.sh --github-token X [--openai-key Y] [--jev-key Z] \
#       [--age-recipient age1...] [--device-name NAME]
#   scripts/provision_phone.sh --enable-accessibility
#   scripts/provision_phone.sh --run-daemon
#   scripts/provision_phone.sh --status
#   scripts/provision_phone.sh --pull-geometry OUT.json
#
# Options may be combined; provisioning (if any secret is given) happens first, then
# --enable-accessibility, --run-daemon, --pull-geometry, --status in that order.
#   --package PKG   application id (default dev.sloptype; use dev.sloptype.debug for debug builds)
#   --serial SERIAL adb device serial (or set ANDROID_SERIAL)
#
# Secrets are never echoed: the broadcast command is base64-encoded on the host and decoded on
# the phone, piped straight into a root shell, so no quoting of token characters is needed and
# nothing is printed. (The encoded string is still visible in `ps` on the phone for a moment.)
set -euo pipefail
set +x

PKG="dev.sloptype"
RECEIVER_CLASS="dev.sloptype.app.daemon.DaemonReceiver"
ACCESSIBILITY_SERVICE="dev.sloptype/dev.sloptype.app.context.ScreenContextService"

GITHUB_TOKEN=""
OPENAI_KEY=""
JEV_KEY=""
AGE_RECIPIENT=""
DEVICE_NAME=""
DO_ENABLE_A11Y=0
DO_RUN_DAEMON=0
DO_STATUS=0
GEOMETRY_OUT=""

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --github-token)   GITHUB_TOKEN="${2:?missing value for $1}"; shift 2 ;;
        --openai-key)     OPENAI_KEY="${2:?missing value for $1}"; shift 2 ;;
        --jev-key)        JEV_KEY="${2:?missing value for $1}"; shift 2 ;;
        --age-recipient)  AGE_RECIPIENT="${2:?missing value for $1}"; shift 2 ;;
        --device-name)    DEVICE_NAME="${2:?missing value for $1}"; shift 2 ;;
        --package)        PKG="${2:?missing value for $1}"; shift 2 ;;
        --serial)         export ANDROID_SERIAL="${2:?missing value for $1}"; shift 2 ;;
        --enable-accessibility) DO_ENABLE_A11Y=1; shift ;;
        --run-daemon)     DO_RUN_DAEMON=1; shift ;;
        --status)         DO_STATUS=1; shift ;;
        --pull-geometry)  GEOMETRY_OUT="${2:?missing value for $1}"; shift 2 ;;
        -h|--help)        usage 0 ;;
        *) echo "unknown option: $1" >&2; usage 2 ;;
    esac
done

if [[ -z "$GITHUB_TOKEN$OPENAI_KEY$JEV_KEY$AGE_RECIPIENT$DEVICE_NAME" && $DO_ENABLE_A11Y -eq 0 && $DO_RUN_DAEMON -eq 0 && $DO_STATUS -eq 0 && -z "$GEOMETRY_OUT" ]]; then
    usage 2
fi

command -v adb >/dev/null || { echo "adb not found in PATH" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no adb device (is the phone connected and authorized?)" >&2; exit 1; }

RECEIVER="$PKG/$RECEIVER_CLASS"
# The app uses device-protected storage (defaultToDeviceProtectedStorage), so filesDir may be
# under /data/user_de/0 instead of /data/data; resolve at runtime.
DATA_DIR_CANDIDATES="/data/data/$PKG/files/sloptype /data/user_de/0/$PKG/files/sloptype"

# sh_quote STR -> single-quoted POSIX shell word
sh_quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

# root_sh SCRIPT: run SCRIPT (a shell snippet) as root on the phone without exposing it to quoting.
root_sh() {
    local b64
    b64=$(printf '%s' "$1" | base64 | tr -d '\n')
    adb shell "echo $b64 | base64 -d | su"
}

# root_cmd CMD...: simple commands with no secrets (visible in logs); still run as root.
root_cmd() { adb shell "su -c $(sh_quote "$*")"; }

data_dir() {
    local d
    for d in $DATA_DIR_CANDIDATES; do
        if [[ "$(root_cmd "[ -d $d ] && echo yes || echo no" | tr -d '\r')" == "yes" ]]; then echo "$d"; return 0; fi
    done
    # default: first candidate (the receiver creates it on first run)
    echo "${DATA_DIR_CANDIDATES%% *}"
}

broadcast() {
    # broadcast ACTION [extra-args...]; extra args are already shell-quoted words
    local action="$1"; shift
    root_sh "am broadcast -n $(sh_quote "$RECEIVER") -a $(sh_quote "$action") $* >/dev/null 2>&1; echo \"broadcast $action: exit=\$?\""
}

# ---------------------------------------------------------------- provision
if [[ -n "$GITHUB_TOKEN$OPENAI_KEY$JEV_KEY$AGE_RECIPIENT$DEVICE_NAME" ]]; then
    extras=""
    [[ -n "$GITHUB_TOKEN" ]]  && extras+=" --es github_token $(sh_quote "$GITHUB_TOKEN")"
    [[ -n "$OPENAI_KEY" ]]    && extras+=" --es openai_api_key $(sh_quote "$OPENAI_KEY")"
    [[ -n "$JEV_KEY" ]]       && extras+=" --es jev_api_key $(sh_quote "$JEV_KEY")"
    [[ -n "$AGE_RECIPIENT" ]] && extras+=" --es age_recipient $(sh_quote "$AGE_RECIPIENT")"
    [[ -n "$DEVICE_NAME" ]]   && extras+=" --es device_name $(sh_quote "$DEVICE_NAME")"
    echo "provisioning $PKG ($(printf '%s' "$extras" | grep -o -- '--es [a-z_]*' | sed 's/--es //' | tr '\n' ' '))"
    broadcast dev.sloptype.PROVISION "$extras"
    echo "secrets stored on the phone; periodic daemon scheduled and a run enqueued"
fi

# ---------------------------------------------------------------- accessibility
if [[ $DO_ENABLE_A11Y -eq 1 ]]; then
    svc="$ACCESSIBILITY_SERVICE"
    [[ "$PKG" != "dev.sloptype" ]] && svc="$PKG/dev.sloptype.app.context.ScreenContextService"
    current="$(root_cmd "settings get secure enabled_accessibility_services" | tr -d '\r')"
    if [[ -z "$current" || "$current" == "null" ]]; then
        new="$svc"
    elif [[ ":$current:" == *":$svc:"* ]]; then
        new="$current"
    else
        new="$current:$svc"
    fi
    root_cmd "settings put secure enabled_accessibility_services $(sh_quote "$new") && settings put secure accessibility_enabled 1"
    echo "accessibility services: $new"
fi

# ---------------------------------------------------------------- run daemon
if [[ $DO_RUN_DAEMON -eq 1 ]]; then
    broadcast dev.sloptype.DAEMON_RUN_NOW
    echo "daemon run enqueued (needs network); follow with: adb logcat -s SlopDaemon"
fi

# ---------------------------------------------------------------- geometry
if [[ -n "$GEOMETRY_OUT" ]]; then
    broadcast dev.sloptype.EXPORT_GEOMETRY
    sleep 2
    dir="$(data_dir)"
    if root_cmd "cat $dir/bench/geometry.json" > "$GEOMETRY_OUT" 2>/dev/null && [[ -s "$GEOMETRY_OUT" ]]; then
        echo "geometry written to $GEOMETRY_OUT"
    else
        rm -f "$GEOMETRY_OUT"
        echo "no geometry exported yet: open the keyboard once (so the app process has seen it), then retry" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------- status
if [[ $DO_STATUS -eq 1 ]]; then
    dir="$(data_dir)"
    if ! root_cmd "cat $dir/state/daemon-status.json"; then
        echo "no daemon-status.json yet at $dir/state (the daemon has not run)" >&2
        exit 1
    fi
    echo
fi
