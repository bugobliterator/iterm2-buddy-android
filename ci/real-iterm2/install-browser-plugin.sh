#!/usr/bin/env bash
# Prepares the browser probe (RealIterm2BrowserTest) before iTerm2's first launch: iTerm2's
# Browser Plugin, which browser sessions need, and a browser Dynamic Profile the test opens a
# window with.
#
# 1. The Browser Plugin. iTerm2 3.7.7 offers browser profiles only when NSWorkspace finds the app
#    with bundle ID com.googlecode.iterm2.iTermBrowserPlugin and its code signature satisfies
#    `anchor apple generic and certificate leaf[subject.OU] = "H7V7XYVQ7D"`
#    (iTermBrowserGateway.swift:43-45, 85-99, 165-232). Without it a browser Dynamic Profile is
#    skipped when the profiles load (iTermDynamicProfileManager.m:563-565). The zip is the one
#    https://iterm2.com/browser-plugin.html links (the page iTerm2 opens to offer it,
#    iTermBrowserGateway.swift:116), pinned by sha256; its entries are checked to stay inside the
#    archive before extraction. The app is checked against the Developer ID requirement for
#    iTerm2's team, installed in ~/Applications, registered with LaunchServices and looked up with
#    NSWorkspace, as iTerm2 looks it up.
# 2. The Dynamic Profile buddy-ci-browser: "Custom Command" Browser, which is what makes a profile
#    a browser in 3.7.7 (profileIsBrowser, NSDictionary+iTerm.m:73-75, compares KEY_CUSTOM_COMMAND
#    with kProfilePreferenceCommandTypeBrowserValue, ITAddressBookMgr.m:70; "Profile Type
#    (Phony)" is synthetic and never stored, ITAddressBookMgr.h:51), whose "Initial URL" is the
#    test's page server on 127.0.0.1:$BROWSER_PAGE_PORT. The test opens it with
#    `it2 window new --profile buddy-ci-browser` (the API's createTab by profile name,
#    iTermAPIHelper.m:2564-2590; the session factory makes a browser session from the profile,
#    iTermSessionFactory.m:92-93).
#
# Runs before iTerm2 launches: the gateway's answer and the Dynamic Profiles are read at launch.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
source "$REAL_ITERM2_SCRIPTS/plugins.sh"
require_macos
require_tools curl ditto unzip codesign osascript plutil pgrep /usr/libexec/PlistBuddy
[[ -x "$LSREGISTER" ]] || die "lsregister is not at $LSREGISTER"

if pgrep -x iTerm2 >/dev/null; then
    die "iTerm2 is already running; it must find the browser plugin and read the browser profile at its first launch"
fi

# --- 1. The Browser Plugin ----------------------------------------------------------------------

zip="$DOWNLOADS_DIR/browser-plugin.zip"
download "$BROWSER_PLUGIN_ZIP_URL" "$zip"
actual="$(sha256_of "$zip")"
[[ "$actual" == "$BROWSER_PLUGIN_ZIP_SHA256" ]] ||
    die "browser plugin zip sha256 is $actual, expected the pinned $BROWSER_PLUGIN_ZIP_SHA256; if iterm2.com replaced it on purpose, check it and update the pin in ci/real-iterm2/common.sh"
log "browser plugin zip matches the pinned sha256"
check_zip_entries "$zip"

unpacked="$DOWNLOADS_DIR/browser-plugin-unpacked"
rm -rf "$unpacked"
mkdir -p "$unpacked"
ditto -x -k "$zip" "$unpacked"
app="$(find "$unpacked" -maxdepth 3 -name '*.app' -type d -not -path '*/__MACOSX/*' -print -quit)"
[[ -n "$app" ]] || die "the browser plugin zip holds no .app: $(find "$unpacked" -maxdepth 2 | head -n 20)"
found_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist")"
[[ "$found_id" == "$BROWSER_PLUGIN_BUNDLE_ID" ]] || die "the browser plugin's bundle ID is $found_id, expected $BROWSER_PLUGIN_BUNDLE_ID"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist" 2>/dev/null || echo unknown)"
log "found $(basename "$app") ($found_id, version $version)"

# iTerm2 asks for `anchor apple generic and certificate leaf[subject.OU] = "H7V7XYVQ7D"`; the
# Developer ID form (as install-iterm2.sh checks iTerm2) implies it.
requirement="$(developer_id_requirement "$ITERM2_TEAM_ID")"
codesign --verify --deep --strict -R="$requirement" "$app" ||
    die "the browser plugin does not satisfy the Developer ID requirement for team $ITERM2_TEAM_ID; iTerm2 would refuse browser profiles"
log "the browser plugin is signed with a Developer ID certificate of team $ITERM2_TEAM_ID"

applications="$HOME/Applications"
destination="$applications/$(basename "$app")"
mkdir -p "$applications"
rm -rf "$destination"
ditto "$app" "$destination"
xattr -dr com.apple.quarantine "$destination" 2>/dev/null || true
"$LSREGISTER" -f "$destination" || die "lsregister could not register $destination"
codesign --verify --deep --strict -R="$requirement" "$destination" || die "the installed copy at $destination no longer verifies"
resolved=""
deadline=$((SECONDS + 60))
until resolved="$(plugin_resolved_path "$BROWSER_PLUGIN_BUNDLE_ID")" && [[ -n "$resolved" ]]; do
    ((SECONDS < deadline)) || die "NSWorkspace does not resolve $BROWSER_PLUGIN_BUNDLE_ID after lsregister; iTerm2 would offer no browser"
    sleep 1
done
log "NSWorkspace resolves $BROWSER_PLUGIN_BUNDLE_ID to $resolved"
[[ "$resolved" == "$destination" ]] || warn "NSWorkspace resolves the browser plugin to $resolved, not $destination"

# --- 2. The browser Dynamic Profile -------------------------------------------------------------

mkdir -p "$(dirname "$BROWSER_PROFILE_FILE")"
printf '%s\n' '{"Profiles":[{"Guid":"'"$BROWSER_PROFILE_NAME"'","Name":"'"$BROWSER_PROFILE_NAME"'","Custom Command":"Browser","Initial URL":"'"$BROWSER_PAGE_URL"'"}]}' \
    > "$BROWSER_PROFILE_FILE"
command_type="$(plutil -extract 'Profiles.0.Custom Command' raw -o - "$BROWSER_PROFILE_FILE")" ||
    die "$BROWSER_PROFILE_FILE does not parse"
[[ "$command_type" == "Browser" ]] || die "the browser profile's Custom Command reads back as '$command_type'"
log "wrote $BROWSER_PROFILE_FILE:"
indent < "$BROWSER_PROFILE_FILE"
