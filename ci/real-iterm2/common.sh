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

# The AI plugin iTerm2 3.7.7 installs itself (CompanionPluginInstaller.swift:52-55); without it
# iTerm2 reports AI off to the phone (CompanionPairingController.aiAvailable).
AI_PLUGIN_ZIP_URL="https://iterm2.com/downloads/ai-plugin/iTermAI-1.1.zip"
# Empty until the first run that installs it prints its sha256; pin that value here, and from
# then on a different zip fails the job. Either way its JavaScript is checked against its
# Ed25519 signature with the key iTerm2 3.7.7 embeds for the AI plugin (AIPluginClient.swift:115).
AI_PLUGIN_ZIP_SHA256="6e5547505909df1b1e9355e61f97c4ddc575373d8ef24074c320788426f99f42"
AI_PLUGIN_SIGNING_KEY="fYLUx58QwucuPJRYxBjp7M//uVM0vTfgUo7d6u4TQR8="
AI_PLUGIN_BUNDLE_ID="com.googlecode.iterm2.iTermAI"

# The relay scripts/relay-it.sh tests against, fetched the same way.
RELAY_REPO="https://github.com/gnachman/iterm2-companion-relay.git"
RELAY_COMMIT="b8aa2f90d2bc8b617d1b9c97043aa0b171885310"

# The fake model (fake-model.mjs). The tool it calls on the approval trigger, and the sha256 of
# that tool's schema as iTerm2 sends it (canonical JSON, keys sorted). Empty until the first
# run that reaches it prints the value; pin it here and RealIterm2AgentChatTest then fails when
# iTerm2 sends a different schema.
FAKE_MODEL_TOOL="session_execute_command"
FAKE_MODEL_TOOL_SCHEMA_SHA256="a75daa48174aa34a7dc1de035de1d0ace002a2f034275bcce64ac5a7dcc955ce"

# --- Topology -----------------------------------------------------------------------------

RELAY_PORT="8787"
TLS_PROXY_PORT="8443"
FAKE_MODEL_PORT="8790"
# iTerm2's manual model points here, with no credential (AIModelCredential.none).
FAKE_MODEL_URL="http://127.0.0.1:${FAKE_MODEL_PORT}/v1/chat/completions"
FAKE_MODEL_NAME="buddy-ci-fake-model"
# iTerm2 probes the built-in Ollama endpoint, localhost:11434, whenever it reads its model list
# (LLMMetadata.swift:543-564, 627); fake-model.mjs answers that probe with no models.
FAKE_MODEL_OLLAMA_PORT="11434"
# iTerm2 only accepts an https origin and has no trust override, so it goes through the TLS
# proxy. The relay signs joins against this exact string (RELAY_ORIGIN).
RELAY_ORIGIN="https://localhost:${TLS_PROXY_PORT}"
KEYCHAIN_SERVICE="com.googlecode.iterm2.companion"
MAC_PRIVATE_KEY_ACCOUNT="mac-noise-static-private-key"
PHONE_PUBLIC_KEY_ACCOUNT="paired-phone-noise-static-public-key"
ITERM2_APP_SUPPORT="$HOME/Library/Application Support/iTerm2"
# The Claude Code status probe (seed-claude-status.sh, RealIterm2ClaudeStatusTest): the Dynamic
# Profile whose Subtitle is \(status), made the default, and the it2 iTerm2 bundles
# (Contents/Resources/utilities, project.pbxproj:5036-5042). The test names the same path.
STATUS_PROFILE_GUID="buddy-ci-status"
STATUS_PROFILE_FILE="$ITERM2_APP_SUPPORT/DynamicProfiles/buddy-ci-status.json"
ITERM2_IT2="$ITERM2_APP/Contents/Resources/utilities/it2"
# "Allow all apps to connect": the fixed UUID in the root-owned disable-automation-auth file
# (iTermAPIHelperDisableApplescriptAuthMagic, iTermAPIHelper.m:70).
API_NOAUTH_MAGIC="61DF88DC-3423-4823-B725-22570E01C027"

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
FAKE_MODEL_LOG="$DIAGNOSTICS_DIR/fake-model.log"

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

# Writes iTerm2's secure setting $1 with the value "$2" as iTerm2's own privileged write does: a
# root-owned file in Application Support holding hex(path) " " hex(sha256(key)) "\n" hex(value),
# with no newline after the value (SecureUserDefaults.swift:279-305, 594-641, 679-688). iTerm2
# ignores the file unless root owns it.
install_secure_setting() {
    local key="$1" value="$2"
    local file="$ITERM2_APP_SUPPORT/$key.secureSetting"
    local staged="$WORK_DIR/$key.secureSetting"
    mkdir -p "$ITERM2_APP_SUPPORT"
    printf '%s %s\n%s' "$(hex_of "$file")" "$(printf '%s' "$key" | shasum -a 256 | awk '{ print $1 }')" "$(hex_of "$value")" > "$staged"
    sudo install -o root -g wheel -m 0644 "$staged" "$file" || die "could not install $file as root"
    rm -f "$staged"
    local owner
    owner="$(stat -f '%u' "$file")"
    [[ "$owner" == 0 ]] || die "$file is owned by uid $owner, not root; iTerm2 would ignore it"
    log "wrote $file (root-owned, $(wc -c < "$file" | tr -d ' ') bytes):"
    indent < "$file"
    echo
}

hex_of() {
    printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'
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
