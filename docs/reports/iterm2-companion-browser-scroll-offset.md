# Companion browserScroll lands the wheel event away from the point the phone aimed at

A draft for the iTerm2 issue tracker.

## Versions

- iTerm2 3.7.7 (source at commit `e0025340`), with the Browser Plugin 1.0, on a GitHub Actions
  macOS runner (`macos-15`, one display).
- Companion `browserScroll` from the phone (protocol revision 15), sent by this project's real-Mac
  probe (`RealIterm2BrowserTest`, run 19).

## Summary

`browserScroll` does scroll the page: raw scrolls, a phone drag, a fling, steps of 1 to 4 px, and a
page that scrolls in an inner `overflow: auto` element all moved it. But every wheel event reached
the page at a different point from the one the phone named. The page recorded each `wheel`
event's `clientX`/`clientY`:

| | x (CSS px) | y (CSS px) |
| --- | --- | --- |
| Aimed at (the image point the phone sent ÷ the frame scale) | 512 | 306 |
| Where WebKit delivered the wheel event | 512 | 246 |

The stream was 1024×611 px at frame scale 1.0. A tap at the same kind of point (`browserTap`)
clicked exactly where it was aimed, so the image-to-view mapping is right. Only the scroll's
location is wrong: 60 points too high, with x exact.

The wheel scrolls whatever is under the point it lands on. On a real page that point can be a
fixed header, a side panel, or a different scroll container from the one under the finger. If the
offset is larger, the point can land outside the web view.

## Cause (inferred from the source, not confirmed in a debugger)

`CompanionBrowserInput.scroll` (`sources/Companion/CompanionBrowserInput.swift:255-277`):

1. converts the point to window coordinates (`windowLocation`);
2. converts that to screen coordinates with `window.convertPoint(toScreen:)`;
3. flips it with the main screen's height into a CoreGraphics global point, and sets it as the
   `CGEvent`'s location;
4. makes the `NSEvent` with `NSEvent(cgEvent:)` and hands it to `webView.scrollWheel(with:)`.

The `CGEvent` names no window, so the `NSEvent` has none either. For a window-less event,
`locationInWindow` is the location in screen coordinates. WebKit reads `locationInWindow` as a
point in the web view's window, so the point it uses is the intended one moved by the window's
origin on screen. In Cocoa's bottom-left coordinates, a larger y is higher. On the runner the
window's origin was presumably (0, 60): x exact, y 60 points too high, which matches the numbers
above.

On a Mac where the window is not at the bottom-left corner of the main screen, the error is the
window's whole origin. That can be hundreds of points in both x and y.

Run 20 tested this. The browser window was moved with `it2 window move` and one raw scroll was
sent at the same image point each time. The aim was CSS (512, 306):

| Window origin asked for (`it2 window move x y`) | Where the wheel event landed (CSS px) | Offset |
| --- | --- | --- |
| (0, 0) | (512, 305) | none (y within a pixel) |
| (200, 150) | (712, 246) | +200, −60 |
| (400, 300) | (912, 246) | +400, −60 |
| (where iTerm2 opened it, run 19) | (512, 246) | 0, −60 |

The x offset is exactly the window's x origin. With the window at (0, 0) the event landed where
it was aimed. y was off by the same 60 points at both other positions. That suggests macOS kept
the window at the same height (for example, clamped below the menu bar), so its y origin did not
change. The probe prints the window frames `it2 window list` reports, which can confirm this.
Either way, the offset follows the window's place on screen, as inferred.

## Suggested fix

Give the synthetic event its window, so `locationInWindow` is a window location. For example,
before creating the `NSEvent`, set the CGEvent's window fields to the web view's window number:
`.mouseEventWindowUnderMousePointer` and
`.mouseEventWindowUnderMousePointerThatCanHandleThisEvent`.

Alternatively, build the event the way `click` already does. `click` uses
`NSEvent.mouseEvent(with:location:...windowNumber:...)` with a window location, and taps land
exactly.
