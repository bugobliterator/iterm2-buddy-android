# Refresh companion-streamed sessions at the visible cadence

Branch: `companion-streamed-session-full-cadence` (bugobliterator/iTerm2), one commit on master 03ec0f2.

## Problem

A companion stream of a session that is in a background tab (or in a miniaturized window, or in a hidden hotkey window) updates at about 1 fps, while a stream of a session in the frontmost tab is smooth.

Evidence: on the Android companion, background-tab sessions are visibly slow on the phone and foreground ones are fine. Switching the Mac to that tab makes the phone stream smooth immediately.

## Cause

- The companion stream ticks at the requested frame rate (`sources/Companion/CompanionHostBridge.swift:1269`), but it only emits a frame when `CompanionTerminalFrameSource.contentLastChangedAt` advances (`sources/Companion/CompanionTerminalFrameSource.swift:68`, checked at `CompanionHostBridge.swift:1307`). The session only syncs new content into `VT100Screen` when its update cadence fires: `-[PTYSession updateDisplayBecause:]` calls `[_textview refresh]` (`sources/PTYSession/PTYSession.m:8330`, `:8341`).
- `-[PTYSession updateCadenceControllerState]` sets `state.visible` only when the session belongs to the visible tab, its window is visible, and the window is not miniaturized (`sources/PTYSession/PTYSession.m:24211`).
- When `!state.visible`, `iTermUpdateCadenceController` uses `kBackgroundUpdateCadence`, which is 1 second (`sources/PTYSession/iTermUpdateCadenceController.m:20`, `:156`).

So a streamed session that is not visible on the Mac only gets new content once a second, and the stream can't go faster than that.

## Fix

- `PTYSession` gets `-companionStreamDidStart` / `-companionStreamDidEnd`, which keep a count of live companion streams and call `[_cadenceController changeCadenceIfNeeded]`. `updateCadenceControllerState` reports `visible` when that count is nonzero. This covers background tabs, miniaturized windows, and ordered-out (hidden hotkey) windows. Everything else in the cadence decision (idle, adaptive frame rate, low power mode, ProMotion) still applies.
- `CompanionHostBridge` calls `companionStreamDidStart()` when a stream starts and `companionStreamDidEnd()` in `endStream`. Every teardown path (`stop`, a remote close, unpair, superseded, session closed, data limit) goes through `endStream`. The stream context holds the session weakly, and the dependency only runs Companion → PTYSession, so this adds no cycle.
- Speeding up takes effect at once. Slowing down when the stream ends uses the existing deferred path, so it happens on the next cadence tick.
- Release note added to `docs/notes-3.7.txt` (Bug Fixes).

## How to test

1. Open two tabs. In tab 2, run something that prints continuously, e.g. `while :; do date +%T.%N; sleep 0.05; done`.
2. Select tab 1 on the Mac. Open tab 2's session on the phone (iTerm2 Buddy for iOS, or iTerm2 Buddy for Android).
   - Before: the clock on the phone advances about once a second.
   - After: it updates smoothly, at the stream's frame rate.
3. Repeat with the window miniaturized and with the session in a hotkey window that is hidden. All should be smooth.
4. Stop streaming (leave the session on the phone) and confirm with debug logging that the session goes back to the 1 s background cadence (`select background update cadence` in the log).
5. Regression check: with no phone connected, a background tab still refreshes once a second.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem, because the cause is entirely on the Mac side.
- Not built or run: this was written without Xcode. There are no unit tests because the change is in PTYSession and cadence state, which ModernTests can't easily isolate.
- Not changed: `-[PTYTextView refresh]` returns early when the textview's data source is detached, as it is for buried or parked sessions. If those sessions are streamed, they may need a separate fix.
