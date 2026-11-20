# Render companion frames without inactive-pane dimming

Branch: `companion-frames-without-dimming` (bugobliterator/iTerm2), one commit on master 03ec0f2.

## Problem

When a streamed session is an inactive split pane, or its window is in the background (with "Dim inactive split panes" / "Dim background windows" on), the phone shows its frames faded. They are dimmed toward gray exactly as on the Mac, even though the phone shows that one session full screen and it is the one the user is looking at. The scrollback tiles the phone fetches are dimmed too.

## Cause

`CompanionTerminalFrameSource.renderCurrentScreen` (`sources/Companion/CompanionTerminalFrameSource.swift:85`, `:99`) renders through `-[PTYTextView renderImageWithLines:...]` → `renderOffscreenImageForLines:` (`sources/TerminalView/PTYTextView+ARC.m:2422`). That method builds an offscreen drawing helper configured with the textview's color map (`sources/TerminalView/PTYTextView.m:2081`, `helper.colorMap = _colorMap`).

All dimming lives in that color map: `iTermColorMap.dimmingAmount`, applied in `processedTextColorForTextColor:` / `processedBackgroundColorForBackgroundColor:` (`sources/ExternalSessionState/iTermColorMap.m:321`, `:503`). It gets there from `SessionView`'s dimming via `sessionViewDimmingAmountDidChange` and the screen configuration.

The margin fill uses `session.processedBackgroundColor`, which is dimmed too. The history-tile and content-fetch renders in `CompanionHostBridge.swift` (`:2086`, `:2148`) do the same.

## Fix

- `+[iTermColorMap undimmedCopyOf:]` returns a copy of the color map with `dimmingAmount = 0`. It clears the copy's delegate before changing it, because a copy shares the original's delegate, so the change is never reported to the real session.
- A new `renderImageWithLines:...cursorFocused:undimmed:` passes `undimmed` to `renderOffscreenImageForLines:`. When it is set and the map is dimmed, the renderer gives only its own offscreen drawing helper the undimmed copy. The view's color map and its on-screen drawing are untouched, so nothing changes or flickers on the Mac.
- The existing `renderImageWithLines:` variants pass `undimmed:NO`. Snapshots, the minimap, and the rendering comparer are unchanged.
- `PTYSession.undimmedProcessedBackgroundColor` gives the matching margin fill.
- All three companion renders use `undimmed: true`: the live frame source, history tiles, and content fetch. That way the scrollback tiles match the live band.
- Release note added to `docs/notes-3.7.txt` (Bug Fixes).

Alternatives I rejected:

- Temporarily setting the session's dimming to 0 around the render. This goes through the session's color map delegate, and it would invalidate state and redraw the Mac view, which is a flicker and reentrancy risk.
- Un-dimming colors after rendering. Dimming isn't invertible once colors are clamped.

## How to test

1. Turn on Settings > Appearance > Dimming > Dim inactive split panes and Dim background windows, with a noticeable amount.
2. Split a tab, focus the left pane on the Mac, and open the right pane on the phone.
   - Before: the phone shows washed-out colors.
   - After: the colors match the pane as it looks when focused.
3. Scroll back on the phone. History tiles should match the live area, with no visible seam.
4. Watch the Mac: the inactive pane must stay dimmed and must not flicker while the phone streams.
5. Repeat with the Mac window in the background, and with "Dim only text" on.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem, because the rendering is done on the Mac.
- Not built or run (no Xcode). APIs I couldn't compile-check:
  - The Swift import of the new 7-argument selector as `renderImage(withLines:includeMargins:backgroundColor:showCursor:includeSelection:cursorFocused:undimmed:)`.
  - `+undimmedCopyOf:` is called from MRC `PTYSession.m`. Its name is not in the `copy` family, so ARC returns it autoreleased and the MRC caller doesn't need to release it.
- I didn't add a ModernTests test. Checking pixel output needs a configured PTYTextView and session, which is heavier than the existing tests here. A focused test of `undimmedCopyOf:` (dimmingAmount 0, nil delegate, same colors) would be easy to add if wanted.
