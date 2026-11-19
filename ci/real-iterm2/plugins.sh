# Sourced by install-companion-plugin.sh and install-ai-plugin.sh, after common.sh: installs one
# of iTerm2's signed plugins exactly where and how iTerm2 3.7.7 installs it itself
# (CompanionPluginInstaller.swift:93-146): unpacked into Application Support/iTerm2/Plugins,
# quarantine stripped, registered with LaunchServices.
#
# Before installing, the zip is checked against its pinned sha256 (a mismatch fails the job; an
# empty pin prints the hash to pin and goes on), and the plugin's JavaScript against its Ed25519
# signature with the key iTerm2 embeds for that plugin, which is the check iTerm2 makes before it
# runs it. Afterwards NSWorkspace is asked for the bundle ID, as iTerm2 asks, so a plugin iTerm2
# would not find fails here and not as a silent "plugin missing".
#
# Needs java (JDK 11+) on PATH for the signature check.
# shellcheck shell=bash

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

# Prints the path NSWorkspace resolves bundle ID $1 to, or nothing.
plugin_resolved_path() {
    osascript -l JavaScript -e "ObjC.import('AppKit');
        var url = \$.NSWorkspace.sharedWorkspace.URLForApplicationWithBundleIdentifier('$1');
        url.isNil() ? '' : url.path.js" 2>/dev/null
}

# install_signed_plugin NAME ZIP_URL PINNED_SHA256 SIGNING_KEY BUNDLE_ID SCRIPT_BASENAME
#   NAME             what the logs call it ("companion plugin")
#   PINNED_SHA256    the zip's pinned sha256; empty prints the hash to pin (first run only)
#   SIGNING_KEY      the raw Ed25519 public key iTerm2 embeds for it, base64
#   SCRIPT_BASENAME  Contents/Resources/<this>.js and .sig inside the plugin app
install_signed_plugin() {
    local name="$1" url="$2" pinned="$3" key="$4" bundle_id="$5" script="$6"
    require_tools curl ditto java osascript codesign shasum /usr/libexec/PlistBuddy
    [[ -x "$LSREGISTER" ]] || die "lsregister is not at $LSREGISTER"

    local slug zip actual
    slug="$(printf '%s' "$name" | tr ' ' '-')"
    zip="$DOWNLOADS_DIR/$slug.zip"
    download "$url" "$zip"
    actual="$(sha256_of "$zip")"
    if [[ -z "$pinned" ]]; then
        warn "the $name zip is not pinned yet; its sha256 is $actual. Check it and pin it in ci/real-iterm2/common.sh"
    elif [[ "$actual" != "$pinned" ]]; then
        die "$name zip sha256 is $actual, expected the pinned $pinned; if iterm2.com replaced the plugin on purpose, check it and update the pin in ci/real-iterm2/common.sh"
    else
        log "$name zip matches the pinned sha256"
    fi

    local unpacked app found_id version
    unpacked="$DOWNLOADS_DIR/$slug-unpacked"
    rm -rf "$unpacked"
    mkdir -p "$unpacked"
    ditto -x -k "$zip" "$unpacked"
    app="$(find "$unpacked" -maxdepth 3 -name '*.app' -type d -not -path '*/__MACOSX/*' -print -quit)"
    [[ -n "$app" ]] || die "the $name zip holds no .app: $(find "$unpacked" -maxdepth 2 | head -n 20)"
    found_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist")"
    [[ "$found_id" == "$bundle_id" ]] || die "the $name's bundle ID is $found_id, expected $bundle_id"
    version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist" 2>/dev/null || echo unknown)"
    log "found $(basename "$app") ($found_id, version $version)"

    local js="$app/Contents/Resources/$script.js" sig="$app/Contents/Resources/$script.sig"
    [[ -f "$js" && -f "$sig" ]] || die "the $name has no Contents/Resources/$script.{js,sig}"
    log "$name JavaScript sha256 $(sha256_of "$js"), signature sha256 $(sha256_of "$sig")"
    java "$REAL_ITERM2_SCRIPTS/VerifyPluginSignature.java" "$key" "$js" "$sig" ||
        die "the $name's JavaScript does not verify against iTerm2 3.7.7's key for it; iTerm2 would refuse it"

    local team_line
    if team_line="$(codesign --display --verbose=2 "$app" 2>&1 | grep '^TeamIdentifier=')"; then
        log "$name bundle signature: $team_line"
        [[ "$team_line" == "TeamIdentifier=$ITERM2_TEAM_ID" ]] ||
            warn "the $name bundle is signed by '$team_line', not team $ITERM2_TEAM_ID (iTerm2 itself checks only the JavaScript signature)"
    else
        warn "the $name bundle carries no code signature (iTerm2 itself checks only the JavaScript signature)"
    fi

    local plugins_dir="$ITERM2_APP_SUPPORT/Plugins" destination
    destination="$plugins_dir/$(basename "$app")"
    mkdir -p "$plugins_dir"
    rm -rf "$destination"
    ditto "$app" "$destination"
    xattr -dr com.apple.quarantine "$destination" 2>/dev/null || true
    "$LSREGISTER" -f "$destination" || die "lsregister could not register $destination"
    log "installed and registered $destination"

    local resolved=""
    local deadline=$((SECONDS + 60))
    until resolved="$(plugin_resolved_path "$bundle_id")" && [[ -n "$resolved" ]]; do
        ((SECONDS < deadline)) ||
            die "NSWorkspace does not resolve $bundle_id after lsregister; iTerm2 would treat the $name as missing"
        sleep 1
    done
    log "NSWorkspace resolves $bundle_id to $resolved"
    [[ "$resolved" == "$destination" ]] || warn "NSWorkspace resolves the $name to $resolved, not the copy just installed at $destination"
}
