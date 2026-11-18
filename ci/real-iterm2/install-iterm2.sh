#!/usr/bin/env bash
# Installs the official iTerm2 3.7.7 build into /Applications.
#
# Downloads the release zip from iterm2.com, checks its pinned sha256, unpacks it with ditto,
# then refuses the app unless codesign verifies it strictly, its signing team is iTerm2's, it
# satisfies a Developer ID requirement for that team, Gatekeeper (spctl) accepts it, and its
# version is 3.7.7. Nothing is launched here.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools curl ditto codesign spctl shasum /usr/libexec/PlistBuddy

log "macOS $(sw_vers -productVersion) ($(sw_vers -buildVersion)) on $(uname -m)"

zip="$DOWNLOADS_DIR/iTerm2-${ITERM2_VERSION}.zip"
download "$ITERM2_ZIP_URL" "$zip"
actual="$(sha256_of "$zip")"
[[ "$actual" == "$ITERM2_ZIP_SHA256" ]] ||
    die "iTerm2 zip sha256 is $actual, expected the pinned $ITERM2_ZIP_SHA256 ($ITERM2_ZIP_URL)"
log "iTerm2 zip matches the pinned sha256"

unpacked="$DOWNLOADS_DIR/iterm2-unpacked"
rm -rf "$unpacked"
mkdir -p "$unpacked"
ditto -x -k "$zip" "$unpacked"
[[ -d "$unpacked/iTerm.app" ]] || die "the zip has no iTerm.app at its top level: $(ls "$unpacked")"

if [[ -e "$ITERM2_APP" ]]; then
    log "replacing an existing $ITERM2_APP"
    rm -rf "$ITERM2_APP" 2>/dev/null || sudo rm -rf "$ITERM2_APP"
fi
ditto "$unpacked/iTerm.app" "$ITERM2_APP" 2>/dev/null || sudo ditto "$unpacked/iTerm.app" "$ITERM2_APP"
# curl sets no quarantine flag, so there is no first-launch Gatekeeper prompt; make sure.
xattr -dr com.apple.quarantine "$ITERM2_APP" 2>/dev/null || true
log "installed $ITERM2_APP"

version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$ITERM2_APP/Contents/Info.plist")"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$ITERM2_APP/Contents/Info.plist")"
[[ "$version" == "$ITERM2_VERSION" ]] || die "installed iTerm2 reports version $version, expected $ITERM2_VERSION"
[[ "$bundle_id" == "$ITERM2_BUNDLE_ID" ]] || die "installed iTerm2 has bundle ID $bundle_id, expected $ITERM2_BUNDLE_ID"
log "version $version, bundle ID $bundle_id"

log "codesign --verify --deep --strict"
verification="$(codesign --verify --deep --strict --verbose=2 "$ITERM2_APP" 2>&1)" ||
    die "codesign does not verify $ITERM2_APP: $verification"
indent <<<"$verification"

details="$(codesign --display --verbose=4 "$ITERM2_APP" 2>&1)"
grep -E '^(Identifier|Authority|TeamIdentifier|Timestamp|Runtime Version)=' <<<"$details" | indent
team="$(sed -n 's/^TeamIdentifier=//p' <<<"$details")"
[[ "$team" == "$ITERM2_TEAM_ID" ]] || die "iTerm2 is signed by team '$team', expected $ITERM2_TEAM_ID"
requirement="anchor apple generic and certificate 1[field.1.2.840.113635.100.6.2.6] and certificate leaf[field.1.2.840.113635.100.6.1.13] and certificate leaf[subject.OU] = \"$ITERM2_TEAM_ID\""
codesign --verify --deep --strict -R="$requirement" "$ITERM2_APP" ||
    die "iTerm2 does not satisfy the Developer ID requirement for team $ITERM2_TEAM_ID"
log "signed with a Developer ID certificate of team $ITERM2_TEAM_ID"

log "Gatekeeper: $(spctl --status 2>&1 || true)"
assessment="$(spctl --assess --type execute --verbose=4 "$ITERM2_APP" 2>&1)" ||
    die "Gatekeeper rejects $ITERM2_APP: $assessment"
indent <<<"$assessment"
grep -q 'accepted' <<<"$assessment" || die "Gatekeeper did not accept $ITERM2_APP: $assessment"
if grep -q 'override=security disabled' <<<"$assessment"; then
    warn "Gatekeeper assessments are disabled on this runner, so spctl's 'accepted' does not vouch for the notarization; the codesign checks above still hold"
elif ! grep -q 'Notarized Developer ID' <<<"$assessment"; then
    warn "spctl accepted iTerm2 but did not report 'Notarized Developer ID'"
fi
if command -v xcrun >/dev/null && xcrun --find stapler >/dev/null 2>&1; then
    xcrun stapler validate "$ITERM2_APP" 2>&1 | indent || warn "no stapled notarization ticket found"
fi
log "iTerm2 $ITERM2_VERSION is installed and verified"
