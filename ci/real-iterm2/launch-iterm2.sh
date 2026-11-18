#!/usr/bin/env bash
# Launches the seeded iTerm2 and waits until it parks at the local relay as the paired Mac.
#
# Launches through LaunchServices (open), as a user would, then waits for the iTerm2 process
# and for the relay to log "hello role=mac", the Mac's join of its room. A screenshot is taken
# either way. Gives up after ITERM2_PARK_TIMEOUT_SECONDS (default 180) with the likely causes.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools open pgrep screencapture

park_timeout="${ITERM2_PARK_TIMEOUT_SECONDS:-180}"
[[ -f "$RELAY_LOG" ]] || die "no relay log at $RELAY_LOG; run start-relay.sh first"

screenshot() {
    if screencapture -x "$DIAGNOSTICS_DIR/screen-$1.png" 2>/dev/null; then
        log "screenshot: screen-$1.png"
    else
        warn "screencapture failed ($1); the runner may lack the Screen Recording grant"
    fi
}

log "launching $ITERM2_APP"
open -a "$ITERM2_APP" || die "open could not launch $ITERM2_APP"
iterm2_running() { pgrep -x iTerm2 >/dev/null; }
wait_for 60 iterm2_running || { screenshot launch-failed; die "no iTerm2 process 60 s after open"; }
log "iTerm2 is running (pid $(pgrep -x iTerm2 | head -n 1))"

mac_parked() { grep -q 'hello role=mac' "$RELAY_LOG"; }
if ! wait_for "$park_timeout" mac_parked; then
    screenshot park-timeout
    log "relay log so far:"
    sed 's/^/    relay: /' "$RELAY_LOG" | tail -n 40
    log "TLS proxy log so far:"
    sed 's/^/    tls-proxy: /' "$TLS_PROXY_LOG" | tail -n 40
    die "iTerm2 did not join its relay room within ${park_timeout} s. No 'upgrade' line in the TLS proxy log: iTerm2 never connected (gate closed: plugin not found, secure setting not read, pairing ID missing; or a modal is up, see the screenshot). 'TLS handshake failed': the CA is not trusted. An upgrade but no 'hello role=mac': the relay refused the join (look for 'reject')."
fi
log "iTerm2 parked at the relay: $(grep -m1 'hello role=mac' "$RELAY_LOG")"
# A moment for any first-launch window to settle before the picture.
sleep 5
screenshot parked
pgrep -x iTerm2 >/dev/null || die "iTerm2 exited after parking"
