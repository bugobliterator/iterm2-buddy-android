#!/usr/bin/env bash
# Gives iTerm2 the Mac half of this run's pairing before its first launch, so it parks at the
# local relay as an already-paired Mac and no QR code, SAS entry or Touch ID is needed:
#
# 1. The secure setting EnableCompanionPairing=true: a root-owned file in Application Support
#    holding hex(path) " " hex(sha256(key)) "\n" hex("true") (SecureUserDefaults.swift:279-305,
#    594-641, 679-688), which iTerm2 would otherwise write behind an administrator prompt.
# 2. User defaults: the relay origin, direct mode (an empty resolver URL), the paired pairing ID,
#    and the switches that keep first-launch windows and prompts away.
# 3. The keychain: the Mac's static private key and the phone's static public key, 32 raw bytes
#    each, service com.googlecode.iterm2.companion (CompanionMacIdentity.swift:16-22). They go
#    into a CI keychain with a known password at the front of the search list; iTerm2 reads the
#    data-protection keychain first, falls back to this one and migrates the items
#    (iTermUpgradeSafeKeychain.swift:66-120). The partition list names iTerm2's team, so the
#    read does not need the confirmation prompt a GUI user would click.
#
# The room secret is not seeded: the phone sends it on every connect and iTerm2 stores it.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools security defaults shasum od sudo openssl pgrep stat install

pairing_id="$(pairing_value PAIRING_ID)"
mac_private_hex="$(pairing_value MAC_STATIC_PRIVATE_KEY_HEX)"
phone_public_hex="$(pairing_value PHONE_STATIC_PUBLIC_KEY_HEX)"

if pgrep -x iTerm2 >/dev/null; then
    die "iTerm2 is already running; it must read the seeded state at its first launch"
fi

# --- 1. The secure setting --------------------------------------------------------------------

install_secure_setting EnableCompanionPairing true

# --- 2. User defaults -------------------------------------------------------------------------

domain="$ITERM2_BUNDLE_ID"
defaults write "$domain" CompanionPairingAllowed -bool true
defaults write "$domain" CompanionRelayOrigin -string "$RELAY_ORIGIN"
# Empty means direct mode: no shard-map fetch, the relay origin is used as is.
defaults write "$domain" CompanionResolverURL -string ""
defaults write "$domain" NoSyncCompanionPairedPID -string "$pairing_id"
defaults write "$domain" NoSyncCompanionKeychainHasMaterial -bool true
# Keeps iTerm2 from offering its Claude Code integration in a modal (ClaudeWatcher.swift:13).
defaults write "$domain" NoSyncDisableClaudeWatcher -bool true
# First-launch windows and prompts.
defaults write "$domain" NoSyncOnboardingWindowHasBeenShown34 -bool true
defaults write "$domain" NoSyncTipsDisabled -bool true
defaults write "$domain" NoSyncPermissionToShowTip -bool false
defaults write "$domain" SUEnableAutomaticChecks -bool false
defaults write "$domain" SUAutomaticallyUpdate -bool false
defaults write "$domain" SUHasLaunchedBefore -bool true
log "defaults for $domain:"
for key in CompanionPairingAllowed CompanionRelayOrigin CompanionResolverURL NoSyncCompanionPairedPID \
    NoSyncCompanionKeychainHasMaterial NoSyncDisableClaudeWatcher NoSyncOnboardingWindowHasBeenShown34 \
    NoSyncTipsDisabled NoSyncPermissionToShowTip SUEnableAutomaticChecks; do
    printf '    %s = %s\n' "$key" "$(defaults read "$domain" "$key")"
done
[[ "$(defaults read "$domain" NoSyncCompanionPairedPID)" == "$pairing_id" ]] || die "the paired pairing ID did not stick"
[[ "$(defaults read "$domain" CompanionRelayOrigin)" == "$RELAY_ORIGIN" ]] || die "the relay origin did not stick"

# --- 3. The keychain --------------------------------------------------------------------------

if [[ ! -f "$CI_KEYCHAIN_PASSWORD_FILE" ]]; then
    (umask 077 && openssl rand -hex 24 > "$CI_KEYCHAIN_PASSWORD_FILE")
fi
keychain_password="$(cat "$CI_KEYCHAIN_PASSWORD_FILE")"
if [[ ! -f "$CI_KEYCHAIN" ]]; then
    security create-keychain -p "$keychain_password" "$CI_KEYCHAIN"
fi
# No auto-lock for six hours; unlocked now.
security set-keychain-settings -lut 21600 "$CI_KEYCHAIN"
security unlock-keychain -p "$keychain_password" "$CI_KEYCHAIN"

existing=()
while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%\"}"
    line="${line#\"}"
    [[ -n "$line" && "$line" != "$CI_KEYCHAIN" ]] && existing+=("$line")
done < <(security list-keychains -d user)
# ${a[@]+...}: bash 3.2 (macOS /bin/bash) treats an empty "${a[@]}" as unset under set -u.
security list-keychains -d user -s "$CI_KEYCHAIN" ${existing[@]+"${existing[@]}"}
security default-keychain -d user -s "$CI_KEYCHAIN"
log "user keychain search list:"
security list-keychains -d user | indent

seed_item() {
    local account="$1" hex="$2"
    security delete-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" "$CI_KEYCHAIN" >/dev/null 2>&1 || true
    # -A: any application may read it without a prompt; -X: the data as hex (32 raw bytes).
    security add-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" -X "$hex" -A "$CI_KEYCHAIN" ||
        die "could not add $account to $CI_KEYCHAIN"
    security set-generic-password-partition-list -s "$KEYCHAIN_SERVICE" -a "$account" \
        -S "apple-tool:,apple:,teamid:$ITERM2_TEAM_ID" -k "$keychain_password" "$CI_KEYCHAIN" >/dev/null ||
        die "could not set the partition list of $account"
    # -w prints binary data as hex, or raw with a newline; accept either, never print it.
    local as_text as_bytes
    as_text="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" -w "$CI_KEYCHAIN" 2>/dev/null | tr 'A-F' 'a-f' || true)"
    as_bytes="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" -w "$CI_KEYCHAIN" 2>/dev/null | od -An -v -tx1 | tr -d ' \n' || true)"
    [[ "$as_text" == "$hex" || "$as_bytes" == "$hex" || "$as_bytes" == "${hex}0a" ]] ||
        die "$account does not read back as the 32 bytes written (${#as_text} characters as text)"
    log "keychain item $KEYCHAIN_SERVICE / $account: 32 bytes, partition list apple-tool:,apple:,teamid:$ITERM2_TEAM_ID"
}
seed_item "$MAC_PRIVATE_KEY_ACCOUNT" "$mac_private_hex"
seed_item "$PHONE_PUBLIC_KEY_ACCOUNT" "$phone_public_hex"
# No room secret and no push secret: the phone resends the room secret on every connect.
for account in paired-relay-room-secret paired-push-relay-secret; do
    security delete-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" >/dev/null 2>&1 || true
done
log "iTerm2's pairing is seeded: pairing ID $pairing_id at $RELAY_ORIGIN"
