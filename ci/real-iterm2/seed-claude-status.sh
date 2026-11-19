#!/usr/bin/env bash
# Prepares the Claude Code status probe (RealIterm2ClaudeStatusTest) before iTerm2's first launch:
# Claude Code's hook, `it2 handle-agent-hook`, sets the session's status, and a profile whose
# Subtitle is `\(status)` carries it to the phone in the session tree.
#
# 1. A Dynamic Profile, buddy-ci-status, whose Subtitle is the interpolated string `\(status)`.
#    PTYSession.subtitle evaluates the profile's KEY_SUBTITLE in the session scope
#    (PTYSession.m:6644, 6783-6807, 6880-6881), and `status` is the tab status text
#    (PTYSession.m:27470-27480, iTermVariables.m:103). It has to be a profile: an escape sequence
#    cannot set an interpolated subtitle (PTYSession.m:17149-17156).
# 2. That profile is the default ("Default Bookmark Guid"), so the window iTerm2 opens at launch
#    uses it, and so does the first terminal session every test drives. ITAddressBookMgr's init
#    reads the default GUID before it loads the Dynamic Profiles and, when a Dynamic Profile has
#    that GUID, makes it the default (ITAddressBookMgr.m:308, 369-376). This needs no AppleScript
#    (Automation permission) and no API call to open a window.
# 3. What the bundled it2 needs to reach iTerm2 from inside the session (it2cli at v3.7.7):
#    - EnableAPIServer=YES: it2 talks to the API server's unix socket,
#      ~/Library/Application Support/iTerm2/private/socket (SocketConnection.swift:8-42, 131-150),
#      which iTerm2 opens at launch only when this is on (iTermApplicationDelegate.m:1806-1808);
#      with it on, no "Enable Python API?" prompt comes up (iTermAPIHelper.m:681-688).
#      handle-agent-hook also says nothing at all when that socket is missing
#      (AgentHookAddress.swift:85-101).
#    - SetCookie=YES (an advanced setting, iTermAdvancedSettingsModel.m:988): new sessions get
#      ITERM2_COOKIE (PTYSession.m:3501-3504), so it2 takes its credential from the environment
#      instead of asking iTerm2 for one over AppleScript (CookieAuth.swift:7-15), which would
#      need the Automation permission. That cookie is single use (iTermWebSocketCookieJar.m:35-53).
#    - So every later it2 connection is let in by "Allow all apps to connect": the root-owned
#      file disable-automation-auth in Application Support holding hex(its path) " " and a fixed
#      UUID (iTermAPIHelper.m:70, 410-462, 566-576). it2 asks for no auth UI
#      (x-iterm2-disable-auth-ui, WebSocketClient.swift:27), and without this file a connection
#      with a spent cookie is refused (iTermAPIHelper.m:1634-1654). iTerm2 would write the file
#      behind an administrator prompt; this writes it with sudo, as it would.
# 4. Checks the bundled it2: iTerm2 copies it into Contents/Resources/utilities (the CopyFiles
#    phase with dstPath utilities and dstSubfolderSpec 7, project.pbxproj:5036-5042, built from
#    it2cli/bin/it2, :8413). Claude Code's installer only symlinks it as <dot dir>/it2, usually
#    ~/.config/iterm2/it2 (ClaudeCodeOnboarding.swift:1966-1983, 2042-2077); the Claude watcher
#    is off here, so the test calls the bundle's copy.
#
# Reads back what it wrote and fails when a value did not stick.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools defaults plutil sudo stat install pgrep

if pgrep -x iTerm2 >/dev/null; then
    die "iTerm2 is already running; it must read the status profile and the API settings at its first launch"
fi

# --- 1. The Dynamic Profile ---------------------------------------------------------------------

mkdir -p "$(dirname "$STATUS_PROFILE_FILE")"
# The JSON escapes the backslash, so the Subtitle string iTerm2 reads is \(status).
printf '%s\n' '{"Profiles":[{"Guid":"'"$STATUS_PROFILE_GUID"'","Name":"'"$STATUS_PROFILE_GUID"'","Subtitle":"\\(status)"}]}' \
    > "$STATUS_PROFILE_FILE"
subtitle="$(plutil -extract Profiles.0.Subtitle raw -o - "$STATUS_PROFILE_FILE")" ||
    die "$STATUS_PROFILE_FILE does not parse"
[[ "$subtitle" == '\(status)' ]] || die "the profile's Subtitle reads back as '$subtitle', not '\\(status)'"
log "wrote $STATUS_PROFILE_FILE:"
indent < "$STATUS_PROFILE_FILE"

# --- 2. The default profile ---------------------------------------------------------------------

domain="$ITERM2_BUNDLE_ID"
defaults write "$domain" "Default Bookmark Guid" -string "$STATUS_PROFILE_GUID"

# --- 3. The API socket, the session cookie and "Allow all apps to connect" ----------------------

defaults write "$domain" EnableAPIServer -bool true
defaults write "$domain" SetCookie -bool true

noauth_file="$ITERM2_APP_SUPPORT/disable-automation-auth"
staged="$WORK_DIR/disable-automation-auth"
mkdir -p "$ITERM2_APP_SUPPORT"
printf '%s %s' "$(hex_of "$noauth_file")" "$API_NOAUTH_MAGIC" > "$staged"
sudo install -o root -g wheel -m 0644 "$staged" "$noauth_file" || die "could not install $noauth_file as root"
rm -f "$staged"
owner="$(stat -f '%u' "$noauth_file")"
[[ "$owner" == 0 ]] || die "$noauth_file is owned by uid $owner, not root; iTerm2 would ignore it"
log "wrote $noauth_file (root-owned, $(wc -c < "$noauth_file" | tr -d ' ') bytes)"

log "defaults for $domain:"
for key in "Default Bookmark Guid" EnableAPIServer SetCookie; do
    printf '    %s = %s\n' "$key" "$(defaults read "$domain" "$key")"
done
[[ "$(defaults read "$domain" "Default Bookmark Guid")" == "$STATUS_PROFILE_GUID" ]] || die "the default profile GUID did not stick"
[[ "$(defaults read "$domain" EnableAPIServer)" == 1 ]] || die "EnableAPIServer did not stick"
[[ "$(defaults read "$domain" SetCookie)" == 1 ]] || die "SetCookie did not stick"

# --- 4. The bundled it2 -------------------------------------------------------------------------

[[ -x "$ITERM2_IT2" ]] || die "no executable it2 at $ITERM2_IT2"
# --help parses only; it does not connect to iTerm2.
"$ITERM2_IT2" handle-agent-hook --help >/dev/null 2>&1 || die "$ITERM2_IT2 has no handle-agent-hook command"
"$ITERM2_IT2" set-status --help >/dev/null 2>&1 || die "$ITERM2_IT2 has no set-status command"
log "it2 at $ITERM2_IT2 has handle-agent-hook and set-status"
