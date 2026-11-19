#!/usr/bin/env bash
# Prints into the job log what a screenshot of iTerm2 would show, since the uploaded artifact may
# be out of reach: usage `dump-iterm2-state.sh <label>`.
#
# 1. Every on-screen window owned by iTerm2 (CGWindowListCopyWindowInfo): window ID, owner PID,
#    layer, name, bounds, and the pixel size of a `screencapture -l` of each window that is not
#    the terminal (the image stays in the diagnostics). A layer-25 window of 88 points' height
#    in the screen's middle is CompanionToast ("iTerm2 Buddy connected", CompanionToast.swift:30,
#    53, 84-97): it fades out on a main-queue timer, so it stays up only while the main thread is
#    blocked.
# 2. Through System Events (Accessibility), each iTerm2 window's role and title, and the static
#    texts and button titles of each dialog, sheet or alert: iTerm2's own UI strings. A terminal
#    window's text areas are not read. Accessibility is served by the app's main thread: "windows:
#    0" while CGWindowList lists windows means the main thread is not running its run loop (a
#    hang, not a modal: NSApplication.runModal keeps serving it).
# 3. The main thread's stack (function names only): `sudo sample` for 2 s (iTerm2's hardened
#    runtime refuses an unprivileged sample), else `sudo spindump`.
# 4. The last 10 minutes of iTerm2's unified log, filtered to AI, LLM, chat, URLSession, ATS,
#    plugin and companion lines, with long quoted strings redacted and lines cut at 300 chars.
#
# Never fails: every part is best effort and bounded by a timeout.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
set +e
label="${1:-now}"
safe_label="$(printf '%s' "$label" | tr -c 'A-Za-z0-9_-' '-')"
out="$DIAGNOSTICS_DIR/iterm2-state-$safe_label.txt"
: > "$out"
say() {
    printf '%s\n' "$*" | tee -a "$out"
}
bounded() {
    local seconds="$1"
    shift
    perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"
}

say "=== iTerm2 state: $label ($(date -u +%H:%M:%S)) ==="
if [[ "$(uname -s)" != "Darwin" ]]; then
    say "not macOS"
    exit 0
fi
pid="$(pgrep -x iTerm2 | head -n 1)"
if [[ -z "$pid" ]]; then
    say "iTerm2 is NOT running"
    exit 0
fi
say "iTerm2 pid $pid"

say "--- on-screen windows (CGWindowList): id | pid | layer | name | x,y wxh"
windows="$(bounded 20 osascript -l JavaScript -e '
ObjC.import("CoreGraphics");
const list = ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly, $.kCGNullWindowID)));
const lines = [];
for (const w of list || []) {
  if (w.kCGWindowOwnerName !== "iTerm2") continue;
  const b = w.kCGWindowBounds || {};
  lines.push(`${w.kCGWindowNumber} | pid ${w.kCGWindowOwnerPID} | layer ${w.kCGWindowLayer} | ${w.kCGWindowName === undefined ? "(no name)" : JSON.stringify(w.kCGWindowName)} | ${b.X},${b.Y} ${b.Width}x${b.Height} | alpha ${w.kCGWindowAlpha}`);
}
lines.length ? lines.join("\n") : "no on-screen iTerm2 windows";
' 2>&1)"
printf '%s\n' "$windows" | sed 's/^/    /' | tee -a "$out"
# A picture of each window that is not a terminal window (layer 0 named after its session) or the
# status item; only its size reaches the log.
printf '%s\n' "$windows" | while IFS='|' read -r id _ layer name _; do
    id="$(printf '%s' "$id" | tr -d ' ')"
    [[ "$id" =~ ^[0-9]+$ ]] || continue
    [[ "$layer" == *"layer 0"* || "$name" == *Item-* ]] && continue
    shot="$DIAGNOSTICS_DIR/window-$safe_label-$id.png"
    if bounded 15 screencapture -x -o -l"$id" "$shot" 2>/dev/null && [[ -s "$shot" ]]; then
        say "    window $id captured: $(sips -g pixelWidth -g pixelHeight "$shot" 2>/dev/null | awk '/pixel/ { printf "%s=%s ", $1, $2 }')($(wc -c < "$shot" | tr -d ' ') bytes, $(basename "$shot"))"
    else
        say "    window $id: screencapture -l failed"
    fi
done

say "--- AI defaults as iTerm2 sees them now (JSON)"
exported="$WORK_DIR/iterm2-defaults-$safe_label.plist"
if defaults export "$ITERM2_BUNDLE_ID" "$exported" 2>/dev/null; then
    for key in UseRecommendedAIModel AiModel AIVendor AIEconomyModelName AIManualModelConfigurations AICustomHeadersEnabled; do
        say "    $key = $(plutil -extract "$key" json -o - "$exported" 2>&1 | cut -c1-600)"
    done
    say "    NoSyncOllamaDiscoveredModels present: $(plutil -extract NoSyncOllamaDiscoveredModels json -o /dev/null "$exported" 2>/dev/null && echo yes || echo no)"
    rm -f "$exported"
fi

say "--- windows, sheets and alerts (System Events): role | title, then texts and buttons of dialogs"
bounded 40 osascript <<'APPLESCRIPT' 2>&1 | sed 's/^/    /' | tee -a "$out"
on describeContainer(container, depth)
    set report to ""
    set pad to ""
    repeat depth times
        set pad to pad & "  "
    end repeat
    tell application "System Events"
        try
            repeat with t in (static texts of container)
                set report to report & pad & "text: " & (value of t as text) & linefeed
            end repeat
        end try
        try
            repeat with b in (buttons of container)
                set report to report & pad & "button: " & (title of b as text) & linefeed
            end repeat
        end try
        try
            repeat with c in (checkboxes of container)
                set report to report & pad & "checkbox: " & (title of c as text) & linefeed
            end repeat
        end try
        try
            repeat with s in (sheets of container)
                set report to report & pad & "sheet:" & linefeed & my describeContainer(s, depth + 1)
            end repeat
        end try
        try
            repeat with g in (groups of container)
                set report to report & my describeContainer(g, depth)
            end repeat
        end try
        try
            repeat with sa in (scroll areas of container)
                set report to report & my describeContainer(sa, depth)
            end repeat
        end try
    end tell
    return report
end describeContainer

with timeout of 30 seconds
    tell application "System Events"
        if not (exists process "iTerm2") then return "no iTerm2 process in System Events"
        tell process "iTerm2"
            set report to "frontmost: " & (frontmost as text) & ", windows: " & (count of windows) & linefeed
            repeat with w in windows
                set wRole to ""
                set wTitle to ""
                try
                    set wRole to (subrole of w) as text
                end try
                try
                    set wTitle to (name of w) as text
                end try
                set report to report & "window: " & wRole & " | " & wTitle & linefeed
                set hasSheet to false
                try
                    set hasSheet to ((count of sheets of w) > 0)
                end try
                if wRole is not "AXStandardWindow" or hasSheet then
                    set report to report & my describeContainer(w, 1)
                end if
            end repeat
            return report
        end tell
    end tell
end timeout
APPLESCRIPT

say "--- main thread (function names only)"
sample_file="$DIAGNOSTICS_DIR/iterm2-sample-$safe_label.txt"
rm -f "$sample_file"
if bounded 40 sudo -n /usr/bin/sample "$pid" 2 -file "$sample_file" > "$sample_file.err" 2>&1 && [[ -s "$sample_file" ]]; then
    say "    (sudo sample, 2 s)"
    awk '/^Call graph:/ { graph = 1; next }
         graph && /^ *[0-9]+ Thread_/ { if (main) exit; if ($0 ~ /main-thread|DispatchQueue_1:/) main = 1 }
         main { print; if (++n >= 90) exit }' "$sample_file" |
        cut -c1-220 | sed 's/^/    /' | tee -a "$out"
else
    say "    sudo sample failed: $(tr '\n' ' ' < "$sample_file.err" | cut -c1-300)"
    spin_file="$DIAGNOSTICS_DIR/iterm2-spindump-$safe_label.txt"
    if bounded 150 sudo -n /usr/sbin/spindump "$pid" 2 -file "$spin_file" > "$spin_file.err" 2>&1 && [[ -s "$spin_file" ]]; then
        sudo -n chmod a+r "$spin_file" 2>/dev/null
        say "    (sudo spindump, 2 s)"
        awk '/^ *Thread 0x/ { if (main) exit; if ($0 ~ /main-thread/) main = 1 }
             main { print; if (++n >= 90) exit }' "$spin_file" |
            cut -c1-220 | sed 's/^/    /' | tee -a "$out"
    else
        say "    sudo spindump failed: $(tr '\n' ' ' < "$spin_file.err" | cut -c1-300)"
    fi
fi

say "--- iTerm2 unified log, last 10 min, AI/chat/network/plugin/companion lines (last 250)"
bounded 90 log show --last 10m --info --debug --style compact --predicate 'process == "iTerm2"' 2>/dev/null |
    grep -E '\bAI\b|AI[A-Z]|LLM|ChatAgent|ChatService|ChatBroker|ChatClient|Orchestrat|URLSession|NSURLError|App ?Transport|ATS|[Pp]lugin|[Cc]ompanion|iTermWarning|[Mm]odal|[Ww]arning|[Cc]onsent|Registration|stall|blocked' |
    sed -E "s/\"[^\"]{24,}\"/\"<redacted>\"/g; s/'[^']{24,}'/'<redacted>'/g" |
    cut -c1-300 | tail -n 250 | sed 's/^/    /' | tee -a "$out"
say "=== end of iTerm2 state: $label ==="
exit 0
