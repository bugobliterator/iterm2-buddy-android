#!/usr/bin/env bash
# Gathers what the first look at a failed (or passed) run needs into the diagnostics directory,
# which the workflow uploads: a screenshot, the relay and TLS proxy logs (already there), the
# companion defaults, the secure setting file, which seeded keychain items are still in the CI
# keychain (iTerm2 moves them to its data-protection keychain when it reads them), whether
# NSWorkspace finds the plugins, the AI settings and the fake model's counts (its log and captured
# request shape are already there), iTerm2's unified log and any iTerm2 crash reports.
#
# Never fails: every step is best effort. Prints no key material.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
set +e
summary="$DIAGNOSTICS_DIR/summary.txt"
: > "$summary"
note() {
    log "$*"
    printf '%s\n' "$*" >> "$summary"
}

if [[ "$(uname -s)" != "Darwin" ]]; then
    note "not macOS; nothing to collect"
    exit 0
fi

if screencapture -x "$DIAGNOSTICS_DIR/screen-final.png" 2>/dev/null; then
    note "screenshot: screen-final.png"
else
    note "screencapture failed"
fi

if pgrep -x iTerm2 >/dev/null; then
    note "iTerm2 is running (pid $(pgrep -x iTerm2 | head -n 1))"
else
    note "iTerm2 is NOT running"
fi
for name in relay tls-proxy fake-model; do
    pid_file="$WORK_DIR/$name.pid"
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        note "$name is running (pid $(cat "$pid_file"))"
    else
        note "$name is NOT running"
    fi
done

note "companion defaults (a missing NoSyncCompanionPairedPID means iTerm2 dropped the pairing):"
for key in CompanionPairingAllowed CompanionRelayOrigin CompanionResolverURL NoSyncCompanionPairedPID \
    NoSyncCompanionKeychainHasMaterial NoSyncDisableClaudeWatcher; do
    note "    $key = $(defaults read "$ITERM2_BUNDLE_ID" "$key" 2>&1)"
done
defaults read "$ITERM2_BUNDLE_ID" 2>/dev/null | grep -iE 'companion|NoSyncDisableClaude' > "$DIAGNOSTICS_DIR/defaults-companion.txt"

for setting in EnableCompanionPairing EnableAI; do
    setting_file="$ITERM2_APP_SUPPORT/$setting.secureSetting"
    if [[ -f "$setting_file" ]]; then
        note "secure setting: $(ls -l "$setting_file")"
        cp "$setting_file" "$DIAGNOSTICS_DIR/$setting.secureSetting.txt"
    else
        note "secure setting: $setting_file is missing"
    fi
done

note "AI defaults (UseRecommendedAIModel 0 and the fake model's entry are expected):"
for key in GenerativeAIAllowed UseRecommendedAIModel AiModel; do
    note "    $key = $(defaults read "$ITERM2_BUNDLE_ID" "$key" 2>&1)"
done
defaults read "$ITERM2_BUNDLE_ID" AIManualModelConfigurations > "$DIAGNOSTICS_DIR/defaults-ai-models.txt" 2>&1

if [[ -f "$CI_KEYCHAIN" ]]; then
    for account in "$MAC_PRIVATE_KEY_ACCOUNT" "$PHONE_PUBLIC_KEY_ACCOUNT" paired-relay-room-secret; do
        if security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" "$CI_KEYCHAIN" >/dev/null 2>&1; then
            note "CI keychain: $account present (not migrated, or written back by iTerm2)"
        else
            note "CI keychain: $account absent (migrated to the data-protection keychain, or never written)"
        fi
    done
fi

for bundle_id in "$COMPANION_PLUGIN_BUNDLE_ID" "$AI_PLUGIN_BUNDLE_ID"; do
    plugin="$(osascript -l JavaScript -e "ObjC.import('AppKit');
        var url = \$.NSWorkspace.sharedWorkspace.URLForApplicationWithBundleIdentifier('$bundle_id');
        url.isNil() ? 'not found' : url.path.js" 2>&1)"
    note "NSWorkspace $bundle_id: $plugin"
done

# Counts and the captured tool facts only; the fake model never reports content.
if curl --silent --fail --max-time 5 "http://127.0.0.1:${FAKE_MODEL_PORT}/fake/stats" -o "$DIAGNOSTICS_DIR/fake-model-stats.json"; then
    note "fake model stats: $(cat "$DIAGNOSTICS_DIR/fake-model-stats.json")"
else
    note "fake model stats: not reachable on 127.0.0.1:${FAKE_MODEL_PORT}"
fi
if [[ -f "$FAKE_MODEL_LOG" ]]; then
    note "fake model log: $(grep -c ' POST ' "$FAKE_MODEL_LOG") chat requests"
fi

if [[ -f "$RELAY_LOG" ]]; then
    note "relay log: $(wc -l < "$RELAY_LOG" | tr -d ' ') lines; joins: $(grep -c 'hello role=' "$RELAY_LOG") (mac $(grep -c 'hello role=mac' "$RELAY_LOG"), phone $(grep -c 'hello role=phone' "$RELAY_LOG")); rejects: $(grep -c ' reject ' "$RELAY_LOG")"
fi

# iTerm2's own unified-log lines (os_log and NSLog), last hour, capped.
log show --last 60m --style compact --info --predicate 'process == "iTerm2"' 2>/dev/null |
    tail -n 20000 > "$DIAGNOSTICS_DIR/iterm2-unified.log"
note "iTerm2 unified log: $(wc -l < "$DIAGNOSTICS_DIR/iterm2-unified.log" | tr -d ' ') lines"

mkdir -p "$DIAGNOSTICS_DIR/crash-reports"
find "$HOME/Library/Logs/DiagnosticReports" /Library/Logs/DiagnosticReports -maxdepth 1 -iname 'iTerm*' \
    -newer "$PAIRING_FILE" -exec cp {} "$DIAGNOSTICS_DIR/crash-reports/" \; 2>/dev/null
note "iTerm2 crash reports: $(find "$DIAGNOSTICS_DIR/crash-reports" -type f | wc -l | tr -d ' ')"

note "diagnostics in $DIAGNOSTICS_DIR:"
find "$DIAGNOSTICS_DIR" -type f -exec ls -l {} \; | indent
exit 0
