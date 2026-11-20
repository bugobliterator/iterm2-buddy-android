# Select a session on the Mac when the companion streams it

Branch: `companion-activate-streamed-session` (bugobliterator/iTerm2), one commit on master 03ec0f2.

## Problem

When the phone opens a session that isn't the selected tab or the active split pane on the Mac, two things go wrong:

- **Slow.** A session in a background tab refreshes once a second, so its stream runs at about 1 fps. On the Android companion, background-tab sessions are visibly slow on the phone, while foreground ones are fine.
- **Faded.** With "Dim inactive split panes" on, an inactive pane's colors are dimmed. The companion renders the session with those colors, so the phone shows it faded.

The phone user is looking at that session, so the Mac should treat it as the one in use.

## Cause

- **Cadence.** `-[PTYSession updateCadenceControllerState]` (`sources/PTYSession/PTYSession.m:24211`) counts a session as visible only when it belongs to the selected tab. `iTermUpdateCadenceController` drops a session that isn't visible to `kBackgroundUpdateCadence`, which is 1 s (`sources/PTYSession/iTermUpdateCadenceController.m:20`, `:156`–`159`). The stream only gets content when the session syncs at that cadence.
- **Dimming.** Dimming comes from the session's color map, which the companion's offscreen render uses. An inactive split pane is dimmed.
- **Stream start.** `CompanionHostBridge.startStream` (`sources/Companion/CompanionHostBridge.swift:1211`) leaves the Mac's selection alone.

## Fix

This is an owner decision, and it is always on. There is no protocol change.

When the companion starts a stream of a terminal or browser session, `CompanionHostBridge` calls a new method, `-[PTYSession selectWithoutActivatingWindow]`. It does the tab-and-pane half of `-reveal`:

- `setActiveSessionPreservingMaximization:` makes the session its tab's active pane (`sources/TerminalView/PTYTab.m:1302`). If a different pane is maximized, the streamed pane is swapped into the maximized slot, so the tab stays maximized and shows this session. A tmux zoom is kept the same way `-reveal` keeps it. A floating pane is brought to the front within its tab.
- `sessionSelectContainingTab` (→ `-[PTYTab makeActive]`, `PTYTab.m:1028`) selects the session's tab in its window.

Unlike `-reveal`, it never calls `makeKeyAndOrderFront:`, `activateIgnoringOtherApps:`, or the hotkey-window show path. The window isn't raised or made key, the app isn't activated, and a hidden hotkey window stays hidden. Inside that window, the selected pane's view becomes first responder, as when the user clicks a pane. The key window doesn't change.

It does nothing for:

- a buried session (selecting it would mean showing it in a window)
- a session that isn't in a tab, such as a workgroup peer that isn't currently in its pane.

A tmux client tab is selected like any other; tmux learns about the window or pane change as usual.

Release note added to `docs/notes-3.7.txt` (Bug Fixes).

## What this fixes, and what it doesn't

- For the session the phone opens, this fixes the slowness in the common background-tab case: the session is now in the selected tab of its window, so it refreshes at the normal rate.
- It also fixes the inactive-pane fading on the phone: the opened session is now its tab's active pane, so "Dim inactive split panes" no longer applies to it.
- Out of scope: a session in a hidden (ordered-out) hotkey window, or in a miniaturized window, still refreshes about once a second while streamed. Selecting its tab doesn't make the window visible, and the cadence controller still treats it as not visible. With the companion, windows aren't expected to be minimized, so this branch doesn't handle these cases.
- Not addressed: iTerm2's "Dim background windows" when iTerm2 isn't the front app, or the session's window isn't key. This branch doesn't make the window key, so that dimming still reaches the phone. It is left as is.

## How to test

1. **Background tab.** Open two tabs and run something that prints continuously in tab 2, e.g. `while :; do date +%T.%N; sleep 0.05; done`. Select tab 1 and work in another app. Open tab 2's session on the phone.
   - Tab 2 becomes selected in its window.
   - The window doesn't come forward and the other app stays active.
   - The phone stream is smooth.
2. **Split panes.** Turn on Settings > Appearance > Dimming > Dim inactive split panes. Split a tab, focus the left pane, and open the right pane on the phone. The right pane becomes active on the Mac and isn't faded on the phone.
3. **Maximized pane.** Maximize the left pane, then open the right pane on the phone. The tab stays maximized, now showing the right pane.
4. **Browser session.** Open a web browser session in a background tab on the phone. Its tab is selected and the page isn't faded.
5. **Window state.** Hide a hotkey window, then open one of its sessions on the phone. The window stays hidden. The stream is still slow, which is out of scope.
6. **Key window.** While typing in iTerm2 window A, open a session in window B on the phone. Window A stays key. If the session is in window A itself, its pane and tab are selected, by design.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem and gets the same fix, because the change is Mac-side at stream start.
- Not built or run (no Xcode).
- No ModernTests: the change is a small composition of existing PTYTab/PTYSession calls with no logic that can be tested in isolation. It reuses the calls `-reveal` already makes, in the same order.
