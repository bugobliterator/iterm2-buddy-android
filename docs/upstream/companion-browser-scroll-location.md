# Fix where companion scrolls land in a browser session

Branch: `companion-browser-scroll-location` (bugobliterator/iTerm2), one commit on master 03ec0f2.

## Problem

Scrolling a web browser session from the companion does nothing unless the Mac window is at the screen's origin. The scroll lands somewhere other than the point the phone aimed at.

Measured: aiming the wheel at CSS x = 512 in the page.

| Window origin | x where the wheel landed |
|---|---|
| (0, 0) | 512 |
| (200, 150) | 712 |
| (400, 300) | 912 |

y is offset by the window origin as well. Once the window has been moved, the page doesn't scroll at all, or the wrong element scrolls.

## Cause

`CompanionBrowserInput.scroll(_:at:deltaX:deltaY:)` (`sources/Companion/CompanionBrowserInput.swift:255`) does three things:

1. Builds a `CGEvent(scrollWheelEvent2Source:)`.
2. Converts the target window point to the screen (`:270`), flips it into CG global coordinates, and wraps it with `NSEvent(cgEvent:)` (`:273`).
3. Calls `webView.scrollWheel(with:)`.

The NSEvent made from a fresh CGEvent has no window, and for such an event `locationInWindow` is the location in screen coordinates. WebKit reads `locationInWindow` as a point in the web view's window, so the point it uses is the window point plus the window's origin.

`click()` doesn't have this problem because `NSEvent.mouseEvent(with:location:...windowNumber:...)` takes a window location and window number directly. There is no such constructor for scroll-wheel events.

## Fix

The CGEvent now gets the window point itself, flipped into CG's top-left global space using the primary screen's height: `scrollEventLocation(windowPoint:primaryScreenHeight:)`. As a result, `NSEvent(cgEvent:).locationInWindow` equals the intended window point no matter where the window is or which screen it's on. This also holds for a web view with no window (a browser in a background tab), whose window point is in its own coordinates, matching the case that already worked. A comment explains why, and a DLog records the location and the event's window for diagnosis in the field.

Tests (`ModernTests/CompanionBrowserInputTests.swift`):

- A pure test of the location helper.
- An end-to-end test that puts a WKWebView in a window at (300, 200) and scrolls over an inner `overflow: scroll` box. It checks that the box scrolled by 50 and the page did not scroll. With the old code, the wheel would land outside the box.

Release note added to `docs/notes-3.7.txt` (Bug Fixes).

## How to test

1. Open a web browser session in iTerm2 and move its window away from the top-left of the screen.
2. Open the session on the phone and drag to scroll.
   - Before: nothing scrolls, or something to the right of / below the finger scrolls.
   - After: whatever is under the finger scrolls.
3. Repeat with the window on a second display, and with the browser in a background tab.
4. Run `tools/run_tests.expect ModernTests/CompanionBrowserInputTests`.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem, because the bug is in the Mac's input delivery.
- Not built and tests not run (no Xcode). The fix depends on two things: `NSEvent(cgEvent:)` attaching no window to a synthesized scroll event, and WebKit treating `locationInWindow` as a window point. The measurements above confirm both, and the DLog will show it if AppKit ever attaches a window.
- The new test embeds a few lines of HTML in Swift, as the existing tests in that file already do.
