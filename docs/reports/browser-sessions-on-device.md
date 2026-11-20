# Browser Sessions on device

The owner fills this in on the Mac with the Pixel, after Browser View. Write what happened under
each **Result** line: pass or fail, plus anything unexpected (screen text, timing, relay log
lines).

- Date:
- Phone and Android version: Pixel 9,
- iTerm2 version: 3.7.7
- Relay commit: `b8aa2f9`
- App build: (the newest 0.1.0-<sha> in App Tester at or after `a15af24`)

## Prerequisites

- [ ] Tailscale is connected on the Pixel, and the relay is running on the Mac behind
      `tailscale serve`.
- [ ] The phone is paired and shows the Sessions home screen.
- [ ] Start a log capture before the first check and keep it running through the last:

      adb logcat -c
      adb logcat > browser-sessions-logcat.txt

## Checks

### Open

Open an iTerm2 browser session (globe row). The page shows below the bar with the page's URL in
the address field.

**Result:**

### Open while reconnecting

Lock the phone for a minute, unlock, and tap a browser row before the connection banner clears.
Once connected, the view switches to the browser view with its bar (not a view-only page).

**Result:**

### Tap and type

Tap a search box on a page: the keyboard rises. Type "kittens" and Return: results load and the
address field updates. Tap quickly twice on a link: it acts as two clicks, not a zoom.

**Result:**

### Address bar

Tap the address field: it selects all. Tap again: the cursor moves. Type `example.com`, Go: the
page loads. Type `iterm2 browser`, Go: a search runs. With Gboard, suggestions and the
composing underline behave normally while typing.

**Result:**

### Navigation

Back, Forward, Reload; Stop (⟳ shows ✕) while a slow page loads.

**Result:**

### Fill the screen

The page opens zoomed to fill the screen below the bar, with no empty band. A page wider than the
phone is cropped at both sides: a horizontal drag pans to them and a vertical drag scrolls the
page. Pinch out shows the whole page (fit); pinch back in. Rotate: a page left at the fill fills
the new shape; a page pinched to fit stays at fit. With the keyboard up the page stays put (its
bottom covered); dragging up first reveals the bottom, then scrolls.

**Result:**

### Scroll with keys (Settings: "Scroll web pages with keys", on by default)

iTerm2 3.7.7 places a `browserScroll`'s wheel event off by the browser window's origin on screen
(`iterm2-companion-browser-scroll-offset.md`), so with the switch on, drags and flings scroll with
arrow and Page Up/Down keys instead. Move the iTerm2 browser window to the middle of the Mac's
screen first.

- Switch on: a slow drag scrolls the page a line or so at a time, a long drag or a fling by
  pages, and the page stops soon after the finger lifts. A tap stops it. A horizontal drag still
  pans at the fill.
- With a text field focused on the page (the keyboard up), a drag scrolls without moving the
  caret (it falls back to scroll events).
- On a page that scrolls inside a panel, tap the panel first; then drags scroll it.
- Switch off: drags send scroll events as before. Note whether they scroll with the window in
  the middle of the screen, and with it at the top-left corner.

**Result:**

### Scroll and fling (switch off)

At fit, a drag scrolls the page and a fling coasts and slows. A tap stops the fling. Pinch in: no
fling follows the pinch. Zoomed in past the fill, a horizontal drag only pans; a vertical drag
pans, then scrolls the page once the view reaches its top or bottom edge. Leaving the view
mid-fling stops it.

**Result:**

### Keyboard

On a page whose editor is not detected (a code editor in the page), ⌨ raises the keyboard and
typing reaches it. With a page text field focused: hide the keyboard with ⌨ and rotate, it stays
hidden; background the app and return, the keyboard comes back. Type, then tap another field and
type at once: the letters land in the right fields.

**Result:**

### Mac alert banner

Trigger an iTerm2 alert on the Mac (for example a multi-line paste warning): "Your Mac needs a
response" shows under the bar, in the browser view, the terminal view and Sessions. Dismissing
it on the Mac clears it.

**Result:**

### Alert already up

With an iTerm2 alert showing, force-stop and reopen the app: the banner shows right after
connecting.

**Result:**

### Terminal unaffected

A terminal session still streams, types, scrolls back and selects as before at revision 15.

**Result:**

### Screen and rotation

The screen stays on while the browser view shows; rotating keeps the same page streaming without
"Starting…".

**Result:**

### Multi-line paste (known)

Paste two lines into a page text field: each line break presses Return in the page. Note whether
that is acceptable.

**Result:**

### No content in logs

Grep `browser-sessions-logcat.txt` for the typed search and the URLs visited. Expected: not found.

**Result:**
