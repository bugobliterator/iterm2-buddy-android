# History and Selection on device

The owner fills this in on the Mac with the Pixel, after Selection and Copy. Write what happened
under each **Result** line: pass or fail, plus anything unexpected (screen text, timing, relay
log lines).

- Date:
- Phone and Android version: Pixel 9,
- iTerm2 version: 3.7.7
- Relay commit: `b8aa2f9`
- App build: (the newest 0.1.0-<sha> in App Tester at or after `005b371`)

## Prerequisites

- [ ] Tailscale is connected on the Pixel, and the relay is running on the Mac behind
      `tailscale serve`.
- [ ] The phone is paired and shows the Sessions home screen.
- [ ] Start a log capture before the first check and keep it running through the last:

      adb logcat -c
      adb logcat > history-and-selection-logcat.txt

## Checks

### Scroll back

In a session with long output (`seq 1 5000`), drag down. Older lines appear, the numbers are
continuous across tile boundaries and into the live area, and **↓ Live** returns to the bottom.

**Result:**

### Flick the scrollback

In a session with long output (`seq 1 20000`), flick down and lift: the history keeps coasting
and slows smoothly to a stop, as an Android list does. A hard flick stops at the top of the
history with no bounce; a flick back down stops at the live bottom. Touching the screen while it
coasts stops it where it is, and that tap does nothing else (the keyboard does not open). Tiles ahead of the coast are already drawn, or fill in quickly
(dark placeholders at most for a moment). Zoomed in, a flick pans and coasts too.

**Result:**

### Growing output while scrolled up

Run `while true; do date; sleep 1; done` and scroll up: the view stays put. Scroll back to the
bottom: it follows the new lines.

**Result:**

### Clear

Run `clear`, then `printf '\e[3J'` (clears scrollback). Older lines vanish, with no stale lines
left behind.

**Result:**

### Full-screen apps

In `less`, and in `vim` with `:set mouse=a`, dragging scrolls the app. In `htop` it scrolls the
process list.

Flick in each (and in Claude Code's transcript): after the lift the app keeps scrolling, fast at
first and then slower, and stops within a few screens however hard the flick (at most 120 lines).
A touch or any key stops it at once. A slow drag still moves one line per line of finger travel.

**Result:**

### Select and copy

Long press a word, drag across two lines, tap **Copy**, and paste into another Android app: the
text matches. **Select All** then **Copy** copies the scrollback.

**Result:**

### Selection in history

Start a selection in older (scrolled-back) lines and end it in the live area.

**Result:**

### Paste

Copy text in another Android app and **Paste** it into the shell: it arrives as one paste.

**Result:**

### Fit then scroll

After **Fit to phone**, scrollback still lines up.

**Result:**

### No content in logs

Grep `history-and-selection-logcat.txt` for a distinctive part of the text you copied. Expected:
not found.

**Result:**
