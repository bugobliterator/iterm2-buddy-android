#!/usr/bin/env bash
# Prints into the job log what a screenshot of iTerm2 would show, since the uploaded artifact may
# be out of reach: usage `dump-iterm2-state.sh <label>`.
#
# 1. Every on-screen window owned by iTerm2 (CGWindowListCopyWindowInfo): layer, name, bounds.
# 2. Through System Events (Accessibility), each iTerm2 window's role and title, and the static
#    texts and button titles of each dialog, sheet or alert: iTerm2's own UI strings. A terminal
#    window's text areas are not read.
# 3. A 2-second `sample` of iTerm2 and the top of its main thread's call graph (function names
#    only), which tells a modal (NSApplication runModal) from a hang.
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

say "--- on-screen windows (CGWindowList): layer | name | x,y wxh"
bounded 20 osascript -l JavaScript -e '
ObjC.import("CoreGraphics");
const list = ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly, $.kCGNullWindowID)));
const lines = [];
for (const w of list || []) {
  if (w.kCGWindowOwnerName !== "iTerm2") continue;
  const b = w.kCGWindowBounds || {};
  lines.push(`layer ${w.kCGWindowLayer} | ${w.kCGWindowName === undefined ? "(no name)" : JSON.stringify(w.kCGWindowName)} | ${b.X},${b.Y} ${b.Width}x${b.Height} | alpha ${w.kCGWindowAlpha}`);
}
lines.length ? lines.join("\n") : "no on-screen iTerm2 windows";
' 2>&1 | sed 's/^/    /' | tee -a "$out"

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

say "--- main thread (sample, 2 s; function names only)"
sample_file="$DIAGNOSTICS_DIR/iterm2-sample-$safe_label.txt"
if bounded 30 sample "$pid" 2 -file "$sample_file" >/dev/null 2>&1 && [[ -f "$sample_file" ]]; then
    awk '/^Call graph:/ { graph = 1; next }
         graph && /^ *[0-9]+ Thread_/ { if (main) exit; if ($0 ~ /main-thread|DispatchQueue_1:/) main = 1 }
         main { print; if (++n >= 70) exit }' "$sample_file" |
        cut -c1-200 | sed 's/^/    /' | tee -a "$out"
else
    say "    sample failed"
fi

say "--- iTerm2 unified log, last 10 min, AI/chat/network/plugin/companion lines (last 250)"
bounded 90 log show --last 10m --info --debug --style compact --predicate 'process == "iTerm2"' 2>/dev/null |
    grep -E '\bAI\b|AI[A-Z]|LLM|ChatAgent|ChatService|ChatBroker|ChatClient|Orchestrat|URLSession|NSURLError|App ?Transport|ATS|[Pp]lugin|[Cc]ompanion|iTermWarning|[Mm]odal|[Ww]arning|[Cc]onsent|Registration|stall|blocked' |
    sed -E "s/\"[^\"]{24,}\"/\"<redacted>\"/g; s/'[^']{24,}'/'<redacted>'/g" |
    cut -c1-300 | tail -n 250 | sed 's/^/    /' | tee -a "$out"
say "=== end of iTerm2 state: $label ==="
exit 0
