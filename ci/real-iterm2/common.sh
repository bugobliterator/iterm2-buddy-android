# Sourced by every ci/real-iterm2 script: the pins, the paths and the logging helpers.
# shellcheck shell=bash
# The settings below are read by the scripts that source this file.
# shellcheck disable=SC2034

set -euo pipefail

REAL_ITERM2_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_NAME="$(basename "${0%.sh}")"

# --- Pins ---------------------------------------------------------------------------------

# iTerm2 3.7.7, the release the app ports. The hash is the Homebrew cask's for this version
# (Casks/i/iterm2.rb); the signature checks below are independent of it.
ITERM2_VERSION="3.7.7"
ITERM2_ZIP_URL="https://iterm2.com/downloads/stable/iTerm2-3_7_7.zip"
ITERM2_ZIP_SHA256="549eb2b21464d7f4606165e51c36a6b1a252ac9a79958228a240152412759da2"
ITERM2_BUNDLE_ID="com.googlecode.iterm2"
# George Nachman's Developer ID team: the signer every official iTerm2 build carries.
ITERM2_TEAM_ID="H7V7XYVQ7D"
ITERM2_APP="/Applications/iTerm.app"

# The companion plugin iTerm2 3.7.7 installs itself (CompanionPluginInstaller.swift:59).
COMPANION_PLUGIN_ZIP_URL="https://iterm2.com/downloads/companion-plugin/iTermCompanion-1.0.zip"
# Pinned from run 44406337389, the first to install it; a different zip fails the job. The
# plugin's JavaScript is also checked the way iTerm2 checks it: an Ed25519 signature made with
# the key iTerm2 3.7.7 embeds.
COMPANION_PLUGIN_ZIP_SHA256="2a6cea5e99a75d53ae673476ea5042ced6e4b2cad3ccbdbf5e54c89065f6e748"
COMPANION_PLUGIN_SIGNING_KEY="GHS0N02DR3kF7u3gqB5GF52dmre/oUkJuarQzEv8crw="
COMPANION_PLUGIN_BUNDLE_ID="com.googlecode.iterm2.iTermCompanion"

# The relay scripts/relay-it.sh tests against, fetched the same way.
RELAY_REPO="https://github.com/gnachman/iterm2-companion-relay.git"
RELAY_COMMIT="b8aa2f90d2bc8b617d1b9c97043aa0b171885310"

# --- Topology -----------------------------------------------------------------------------

RELAY_PORT="8787"
TLS_PROXY_PORT="8443"
# iTerm2 only accepts an https origin and has no trust override, so it goes through the TLS
# proxy. The relay signs joins against this exact string (RELAY_ORIGIN).
RELAY_ORIGIN="https://localhost:${TLS_PROXY_PORT}"
KEYCHAIN_SERVICE="com.googlecode.iterm2.companion"
MAC_PRIVATE_KEY_ACCOUNT="mac-noise-static-private-key"
PHONE_PUBLIC_KEY_ACCOUNT="paired-phone-noise-static-public-key"
ITERM2_APP_SUPPORT="$HOME/Library/Application Support/iTerm2"

WORK_DIR="${REAL_ITERM2_WORK_DIR:-${RUNNER_TEMP:-/tmp}/real-iterm2}"
DOWNLOADS_DIR="$WORK_DIR/downloads"
DIAGNOSTICS_DIR="$WORK_DIR/diagnostics"
PAIRING_FILE="$WORK_DIR/pairing.env"
CI_KEYCHAIN="$WORK_DIR/companion-ci.keychain-db"
CI_KEYCHAIN_PASSWORD_FILE="$WORK_DIR/companion-ci.keychain-password"
RELAY_DIR="$WORK_DIR/relay"
RELAY_LOG="$DIAGNOSTICS_DIR/relay.log"
TLS_PROXY_LOG="$DIAGNOSTICS_DIR/tls-proxy.log"
TLS_DIR="$WORK_DIR/tls"

mkdir -p "$WORK_DIR" "$DOWNLOADS_DIR" "$DIAGNOSTICS_DIR"

# --- Helpers ------------------------------------------------------------------------------

log() {
    printf '[%s] real-iterm2/%s: %s\n' "$(date -u +%H:%M:%S)" "$SCRIPT_NAME" "$*"
}

warn() {
    printf '::warning title=real-iterm2/%s::%s\n' "$SCRIPT_NAME" "$*"
}

die() {
    printf '::error title=real-iterm2/%s::%s\n' "$SCRIPT_NAME" "$*" >&2
    exit 1
}

require_macos() {
    [[ "$(uname -s)" == "Darwin" ]] || die "this script needs macOS (uname -s is $(uname -s))"
}

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null || die "$tool is not on PATH"
    done
}

# Indents stdin, for quoting tool output under a log line.
indent() {
    sed 's/^/    /'
}

sha256_of() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

# Downloads $1 to $2 with retries, failing on an HTTP error.
download() {
    local url="$1" destination="$2"
    log "downloading $url"
    curl --fail --location --silent --show-error --retry 4 --retry-delay 5 --retry-all-errors \
        --output "$destination" "$url" || die "could not download $url"
    log "downloaded $(wc -c < "$destination" | tr -d ' ') bytes, sha256 $(sha256_of "$destination")"
}

# Prints the value of KEY from the pairing file without sourcing it.
pairing_value() {
    local key="$1" value
    [[ -f "$PAIRING_FILE" ]] || die "no pairing file at $PAIRING_FILE; run generate-pairing.sh first"
    value="$(awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$PAIRING_FILE")"
    [[ -n "$value" ]] || die "$PAIRING_FILE has no $key"
    printf '%s' "$value"
}

# Waits up to $1 seconds for the command in the remaining arguments to succeed.
wait_for() {
    local seconds="$1"
    shift
    local deadline=$((SECONDS + seconds))
    until "$@"; do
        ((SECONDS < deadline)) || return 1
        sleep 1
    done
}
