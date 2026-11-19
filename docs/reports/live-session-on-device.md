# Live Session on device

The owner fills this in on the Mac with the Pixel, after Typing and Composer. Write what happened
under each **Result** line: pass or fail, plus anything unexpected (screen text, timing, relay
log lines).

- Date:
- Phone and Android version: Pixel 9,
- iTerm2 version: 3.7.7
- Relay commit: `b8aa2f9`
- App build: 0.1.0-cc024f5 (Firebase App Distribution, CI run 44084255050)

## Prerequisites

- [ ] Tailscale is connected on the Pixel, and the relay is running on the Mac behind
      `tailscale serve`.
- [ ] The phone is paired and shows the Sessions home screen.
- [ ] The APKs are the `pairing-apks-cc024f5` artifact of CI run 44084255050:

      14804283aec878c76b6038d09e737699228f48b545eae2e80d88eead8e104ce2  app-debug.apk
      7771bc2231a3095e956c786554fc53601753bb75174f22dc3a69aa3ecf9a5dee  app-debug-androidTest.apk

  Both are signed with the shared debug key, so `adb install -r` keeps the pairing.

  **Result:**

- Start a log capture before the first check and keep it running through the last:

      adb logcat -c
      adb logcat > live-session-logcat.txt

- Relay log: `~/Library/Logs/iterm2-companion-relay.log`.

## Checks

### Instrumented decoder test

    adb install -r app-debug.apk
    adb install -r app-debug-androidTest.apk
    adb shell am instrument -w -e class dev.bugobliterator.itermbuddy.video.HevcDecodePathDeviceTest dev.bugobliterator.itermbuddy.test/androidx.test.runner.AndroidJUnitRunner

Expected: `OK (7 tests)`. Record the logged last-frame latency. If
`lastFrameOfBurstIsOutputWithoutMoreInput` fails, record it; no drain logic is added without
asking the owner.

**Result:**

### Session list

Sessions is the home screen after launch. Windows, tabs and sessions match the Mac, and a
browser session has the globe icon. Close a tab on the Mac: its row goes within about 2 s
without pulling. Pull to refresh works.

**Result:**

### Claude Code state on Sessions

1. Before setting anything up, with the phone connected: the Sessions list shows the one-line
   tip "To see Claude Code's state here, add \(status) to your iTerm2 profile's Subtitle
   (Settings › Profiles › General)". Tap ✕: it goes, and stays gone after force-stopping and
   reopening the app.
2. On the Mac, set the profile's Subtitle (Settings › Profiles › General) to `\(status)`.
3. Run `claude` in a tab of that profile. With the Sessions list on screen, the row shows no
   chip and no subtitle while Claude Code is idle.
4. Send a prompt: the row shows a green **Working** chip with a spinner.
5. Ask for something that needs a permission prompt: the chip turns orange, **Waiting for you**.
6. Answer the prompt and let the turn finish: the chip turns blue, **Done**.
7. Each change reaches the row within about 5 s, with no spinner, flash or reordering of the
   list. A browser session's row never shows a chip. A session whose subtitle is not a status
   (for example a path) shows that subtitle as before.
8. Go to Chats, or lock the phone, for a minute; come back: the chip catches up at once. (While
   away the phone asks nothing; this needs the Mac's side or a log of `fetchSessionTree`
   requests to confirm.)
9. Unpair and pair again: the tip shows again after the first connect, until a status is seen
   or it is dismissed.

**Result:**

### Live picture

Open a session running `top`. The live view fills the screen on a dark background. The picture updates about once a second. Record the time from
tap to first picture.

**Result:**

### Idle last frame

In the session, run `sleep 2; echo done` five times. Expected: `done` appears on the phone
within half a second of appearing on the Mac each time, with no touch.

**Result:**

### Static screen is silent

Leave an idle shell for 60 s with the Video stats overlay on. Frames in stops growing within a
second of the last change (no keyframe storm), and the relay log shows no stream traffic after
that.

**Result:**

### Frozen picture is labelled

Not provoked on purpose. If "Picture stalled" ever shows during these checks, record when and
what followed.

**Result:**

### Long watch

Watch a Claude Code session working for 5 minutes. Expected: no freeze, and the Video stats
overlay shows frames in ≈ frames out.

**Result:**

### Zoom and pan

Pinch into the bottom-right corner, pan, then double tap back to fit.

**Result:**

### Typing

- `ls` + Return runs.
- `sleep 100`, then **^C** interrupts.
- ↑ recalls history.
- `vim`, then **esc** `:q` Return quits.
- Tab completes.
- Ctrl one-shot, then `c` interrupts.
- F-keys work in `htop` (F10 quits).
- With Gboard, type a word with suggestions showing and hold backspace: no doubled or lost
  characters.

**Result:**

### Composer paste

In a Claude Code prompt, compose three lines and tap **Paste**. They land as one paste and are
not submitted; tap Return on the key bar to submit. Record whether iTerm2 showed a multi-line
paste warning on the Mac.

**Result:**

### Fit to phone

On a wide window, **Fit to phone** shrinks the Mac window to 60 columns (portrait), and the text
is readable at fit. Go back to the list: the Mac window stays at the fitted size (never
restored).

**Result:**

### Oversize window

- Resize a normal (not full-screen) iTerm2 window to fill the largest display and record the
  frame size. If the too-large message shows, it offers **Fit to phone**, and Fit recovers the
  picture.
- Make the window full-screen and record the frame size. If too large, the message explains the
  Mac-side change and offers no Fit (the Mac refuses resize in full screen).

**Result:**

### Lock and unlock

Lock for 10 s and unlock: the picture returns by itself. Lock for 60 s (the connection drops):
after unlocking, "Reconnecting…" shows, then the picture returns. Record the times.

**Result:**

### Background

Press Home while streaming, wait 10 s, and return: the picture returns, and the relay log shows
no traffic for the stream while backgrounded.

**Result:**

### Rotation

Rotate while streaming, both ways. The picture returns within a second without "Starting…",
the view stays on the same session, and the Mac's log shows no stream stop or start.

**Result:**

### Screen stays on

Watch for 60 s without touching: the screen stays on. Go back to the session list and wait: the
phone locks at its normal timeout (30 s).

**Result:**

### Settings

⋮ → Settings shows the connection and the decoder. **Unpair** asks first, then returns to Scan
to pair. Re-pair afterwards for the remaining phases.

**Result:**

### No content in logs

    grep -iE 'top|sleep 100|kittens|<a word you typed>' live-session-logcat.txt

Expected: no typed or pasted text is found.

**Result:**

## Input trace

An opt-in trace of everything the keyboard sends, for debugging characters that do not arrive
(for example `/` and `*` in Claude Code). It is off by default in every build. While it is on,
logcat shows each InputConnection call, key event, translated key, sender decision and the
`sendKey`/`pasteText` envelope, with every typed character written as a code point
(`U+002F[/]`), never as plain text.

1. Turn it on (takes effect within a second; no restart needed):

       adb shell setprop log.tag.IB.Input VERBOSE

2. Capture it:

       adb logcat -c
       adb logcat -s IB.Input:V > input-trace.txt

3. In a live Claude Code session, type `a/b`, then Return; then `a*b`, then Return.
4. Stop the capture (Ctrl-C) and send `input-trace.txt`.
5. Turn it off afterwards:

       adb shell setprop log.tag.IB.Input SUPPRESS

**Result:**
