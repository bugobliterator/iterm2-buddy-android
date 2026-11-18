#!/usr/bin/env bash
# Installs iTerm2's companion plugin exactly where and how iTerm2 3.7.7 installs it itself
# (CompanionPluginInstaller.swift:93-146): unpacked into Application Support/iTerm2/Plugins,
# quarantine stripped, registered with LaunchServices.
#
# Before installing it checks the zip against COMPANION_PLUGIN_ZIP_SHA256 when that pin is set,
# and always checks the plugin's JavaScript against its Ed25519 signature with the key iTerm2
# embeds (CompanionPlugin.swift:24), which is the check iTerm2 makes before it runs the plugin.
# Afterwards it asks NSWorkspace for the bundle ID, as iTerm2 does, so a plugin iTerm2 would not
# find fails here and not as a silent "plugin missing" (which makes iTerm2 drop the pairing).
#
# Needs java (JDK 11+) on PATH for the signature check.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools curl ditto java osascript codesign shasum /usr/libexec/PlistBuddy

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
[[ -x "$LSREGISTER" ]] || die "lsregister is not at $LSREGISTER"

zip="$DOWNLOADS_DIR/iTermCompanion.zip"
download "$COMPANION_PLUGIN_ZIP_URL" "$zip"
actual="$(sha256_of "$zip")"
if [[ -n "$COMPANION_PLUGIN_ZIP_SHA256" ]]; then
    [[ "$actual" == "$COMPANION_PLUGIN_ZIP_SHA256" ]] ||
        die "companion plugin zip sha256 is $actual, expected the pinned $COMPANION_PLUGIN_ZIP_SHA256"
    log "companion plugin zip matches the pinned sha256"
else
    warn "the companion plugin zip is not pinned yet: set COMPANION_PLUGIN_ZIP_SHA256=\"$actual\" in ci/real-iterm2/common.sh. Its Ed25519 signature is still checked below."
fi

unpacked="$DOWNLOADS_DIR/companion-plugin-unpacked"
rm -rf "$unpacked"
mkdir -p "$unpacked"
ditto -x -k "$zip" "$unpacked"
app="$(find "$unpacked" -maxdepth 3 -name '*.app' -type d -not -path '*/__MACOSX/*' -print -quit)"
[[ -n "$app" ]] || die "the companion plugin zip holds no .app: $(find "$unpacked" -maxdepth 2 | head -n 20)"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist")"
[[ "$bundle_id" == "$COMPANION_PLUGIN_BUNDLE_ID" ]] ||
    die "the plugin's bundle ID is $bundle_id, expected $COMPANION_PLUGIN_BUNDLE_ID"
plugin_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist" 2>/dev/null || echo unknown)"
log "found $(basename "$app") ($bundle_id, version $plugin_version)"

js="$app/Contents/Resources/iTermCompanionPlugin.js"
sig="$app/Contents/Resources/iTermCompanionPlugin.sig"
[[ -f "$js" && -f "$sig" ]] || die "the plugin has no Contents/Resources/iTermCompanionPlugin.{js,sig}"
log "plugin JavaScript sha256 $(sha256_of "$js"), signature sha256 $(sha256_of "$sig")"
java "$REAL_ITERM2_SCRIPTS/VerifyPluginSignature.java" "$COMPANION_PLUGIN_SIGNING_KEY" "$js" "$sig" ||
    die "the companion plugin's JavaScript does not verify against iTerm2 3.7.7's plugin key; iTerm2 would refuse it"

if team_line="$(codesign --display --verbose=2 "$app" 2>&1 | grep '^TeamIdentifier=')"; then
    log "plugin bundle signature: $team_line"
    [[ "$team_line" == "TeamIdentifier=$ITERM2_TEAM_ID" ]] ||
        warn "the plugin bundle is signed by '$team_line', not team $ITERM2_TEAM_ID (iTerm2 itself checks only the JavaScript signature)"
else
    warn "the plugin bundle carries no code signature (iTerm2 itself checks only the JavaScript signature)"
fi

plugins_dir="$ITERM2_APP_SUPPORT/Plugins"
destination="$plugins_dir/$(basename "$app")"
mkdir -p "$plugins_dir"
rm -rf "$destination"
ditto "$app" "$destination"
xattr -dr com.apple.quarantine "$destination" 2>/dev/null || true
"$LSREGISTER" -f "$destination" || die "lsregister could not register $destination"
log "installed and registered $destination"

resolved_path() {
    osascript -l JavaScript -e "ObjC.import('AppKit');
        var url = \$.NSWorkspace.sharedWorkspace.URLForApplicationWithBundleIdentifier('$COMPANION_PLUGIN_BUNDLE_ID');
        url.isNil() ? '' : url.path.js" 2>/dev/null
}
plugin_resolves() {
    [[ -n "$(resolved_path)" ]]
}
wait_for 60 plugin_resolves ||
    die "NSWorkspace does not resolve $COMPANION_PLUGIN_BUNDLE_ID after lsregister; iTerm2 would treat the plugin as missing and drop the pairing"
resolved="$(resolved_path)"
log "NSWorkspace resolves $COMPANION_PLUGIN_BUNDLE_ID to $resolved"
[[ "$resolved" == "$destination" ]] || warn "NSWorkspace resolves the plugin to $resolved, not the copy just installed at $destination"
