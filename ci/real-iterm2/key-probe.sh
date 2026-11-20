#!/usr/bin/env bash
# Typed into the iTerm2 session by RealIterm2KeyBytesTest: runs one key-bytes probe program in
# the foreground of the session's tty, then restores the terminal and writes <dir>/done.
#
#   bash key-probe.sh <probe.env> capture <label> <mode>...   key-capture.mjs with those modes
#   bash key-probe.sh <probe.env> claude                       Claude Code under `script -r`
#
# <probe.env> is what install-claude-code.sh wrote. Each run works in $KEY_BYTES_DIR/<label>
# (`claude` for Claude Code), emptied first. Claude Code runs in the probe's trusted project with
# a dummy key, a closed local ANTHROPIC_BASE_URL and no nonessential traffic, under macOS
# `script -q -t 0 -r`, which records the tty's input and output with timestamps, flushed at once,
# to claude.rec (`script -p claude.rec` plays it back). The process that becomes `script` writes
# its pid to <dir>/pid, so the test can stop it if Ctrl-C does not.

set -u
# shellcheck disable=SC1090
source "$1"
kind="$2"
shift 2

if [[ "$kind" == "capture" ]]; then
    label="$1"
    shift
else
    label="claude"
fi
dir="$KEY_BYTES_DIR/$label"
rm -rf "$dir"
mkdir -p "$dir"

status=0
# key-capture.mjs exits on its own after this long if the test never asks it to stop. Well past
# the longest mode (kitty5 sends 95 keys, about 75 s): at 60 s it exited mid-run, the rest of its
# keys went to the shell's line, and the next probe command typed after them never ran.
export KEY_CAPTURE_TIMEOUT_SECONDS=300
case "$kind" in
    capture)
        "$KEY_BYTES_NODE" "$KEY_BYTES_REPO/ci/real-iterm2/key-capture.mjs" "$dir" "$@" 2>>"$dir/stderr.txt" || status=$?
        ;;
    claude)
        if [[ -z "${KEY_BYTES_CLAUDE:-}" ]]; then
            echo "Claude Code is not installed" > "$dir/stderr.txt"
            status=127
        else
            (
                cd "$KEY_BYTES_PROJECT" || exit 1
                export ANTHROPIC_API_KEY="$KEY_BYTES_API_KEY"
                export ANTHROPIC_BASE_URL="http://127.0.0.1:9"
                export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
                export DISABLE_AUTOUPDATER=1
                export DISABLE_TELEMETRY=1
                export DISABLE_ERROR_REPORTING=1
                node_dir="$(dirname "$KEY_BYTES_NODE")"
                export PATH="$node_dir:$PATH"
                # macOS bash is 3.2 (no BASHPID): sh writes its own pid, then becomes `script`.
                # shellcheck disable=SC2016
                exec sh -c 'echo $$ > "$1"; shift; exec "$@"' sh "$dir/pid" \
                    script -q -t 0 -r "$dir/claude.rec" "$KEY_BYTES_CLAUDE"
            ) 2>>"$dir/stderr.txt" || status=$?
        fi
        ;;
    *)
        echo "unknown probe kind $kind" > "$dir/stderr.txt"
        status=2
        ;;
esac

# Whatever the program left on: kitty flags (reset to 0 and pop), bracketed paste,
# modifyOtherKeys, focus reporting, the alternate screen; a visible cursor; a sane tty.
printf '\033[=0;1u\033[<u\033[<u\033[<u\033[?2004l\033[>4m\033[?1004l\033[?1049l\033[?25h'
stty sane 2>/dev/null
echo "$status" > "$dir/done"
