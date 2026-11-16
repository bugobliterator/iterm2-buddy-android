# Sessions: design

Date: 2026-11-16
Status: revised after the Codex Astra and Gemini 4 Argon reviews and the owner's two decisions (screen on in session views; Fit never undone)

Byte-level facts and upstream citations are in `2026-11-16-sessions-protocol-notes.md` (cited below as
*notes*, by section name). The pairing-level wire rules (envelope, Swift enum coding) are in
`2026-11-15-protocol-notes.md`. The app spec is `2026-11-15-android-companion-design.md`.

## Intent

The owner runs AI agents in iTerm2 sessions on the Mac and wants to keep an eye on them from the Pixel:
see what each agent is doing right now, and step in by typing when one gets stuck (answer a prompt,
press ^C, paste a longer instruction). This phase gives the app its main screen: a list of the Mac's
sessions, a live picture of the one the owner opens, and a keyboard into it. It comes before Agent Chat
by the owner's choice, because watching and nudging the terminal is what the owner does most when away
from the desk.

Why video: the Mac's companion protocol has exactly one live view, an HEVC stream of the session's
rendered pixels, and the iOS app uses it. Matching it gives the same picture the owner sees on the Mac
(colours, agent TUIs, cursor, selection) with change-driven updates and no request per refresh.

## What the owner experiences

- **Opening the app (paired):** the home screen is **Sessions**. It reads:

      Sessions                                   ● Connected   ⋮
      ─────────────────────────────────────────────────────────
      Window 1
        Tab 1 · claude
          ▸ claude — ~/src/relay               (terminal icon)
        Tab 2
          ▸ zsh — ~/src/iterm2-buddy-android
      Window 2
        Tab 1
          ▸ GitHub — pull requests             (globe icon)

  Closing a tab on the Mac removes its row within about 2 s, with no pull-to-refresh.
- **Watching:** tapping `claude — ~/src/relay` opens the live view. The agent's TUI appears within a
  second and updates as the agent prints. When the agent goes quiet, the last line it printed is on
  screen, not a frame behind.
- **Zooming:** pinching in on the bottom-right corner shows the prompt at readable size; dragging pans.
- **Typing:** tapping the terminal raises the keyboard with a key bar above it. The agent asks
  "Proceed? (y/n)"; the owner types `y` and Return; the Mac's session receives `y` then Return.
  Tapping **^C** interrupts a runaway command.
- **Composer:** tapping the compose key opens a text box; the owner writes three lines of instructions
  and taps **Paste**. The agent's input box shows the three lines as one paste, not three submitted
  lines; the owner then taps **Return** on the key bar to submit.
- **Fit to phone:** the Mac window is 220 columns wide and unreadable at fit. **Fit to phone** in the
  live view's menu resizes the session to 60 columns (portrait); the iTerm2 window on the Mac shrinks
  and the text on the phone becomes legible without zoom. Going back to the list leaves the Mac window
  at that size. If the Mac window is full screen, the menu has no Fit; a too-large picture says
  "Make the iTerm2 window smaller on the Mac (leave full screen) to watch it here."
- **Watching without touching:** while a session (or browser) view is open the screen stays on; on the
  session list the phone locks at its normal timeout.
- **Rotating:** the picture carries on in the new orientation without "Starting…".
- **Locking the phone** (power button): the stream stops. On unlock the picture comes back by itself (after a
  reconnect if the connection dropped meanwhile).
- **History (second sub-phase):** dragging down past the top of the live screen shows older output;
  a long press selects text, **Copy** puts it on the Android clipboard.
- **Browser (third sub-phase):** opening the globe row shows the web page with a URL bar, back/forward
  and reload; tapping a search box raises the keyboard; typing "kittens" and Go loads results.

## Goals

Three named sub-phases, each ending with the owner's on-device check on the Pixel 9.

**Live Session**
- Sessions home: the window → tab → session tree, refreshed on open, on pull, on reconnect, and on the
  Mac's own pushes.
- Live HEVC video of the opened session, decoded in hardware by MediaCodec.
- Pinch-to-zoom and pan.
- Typing: soft keyboard plus the iOS key bar (compact row and expanded tray).
- Composer for longer text, sent with `pasteText`.
- Fit to phone (`resizeSession`).
- Clean stop on background or lock, automatic restart on return or reconnect.

**History and Selection**
- Scroll back through history (history tiles above the live picture, kept in step with `streamExtent`).
- In full-screen apps (alternate screen with mouse reporting), a drag sends `reportScrollWheel`.
- Select by long press and drag, Select All, Copy to the Android clipboard, Paste from it.

**Browser Sessions**
- Stream iTerm2's built-in browser sessions; tap, scroll and fling; back, forward, reload, stop; URL
  entry; keyboard input that follows the page's focused field.

## Non-goals

- A PNG snapshot or text fallback for the live view (see Approaches).
- H.264. The Mac never produces it (*notes* → Codec negotiation).
- A foreground service, background streaming or notifications. When the phone locks or the app goes
  to the background the stream stops; the Pixel's 30 s screen timeout drops the connection today and
  that is accepted for this phase.
- Agent Chat, approvals, Mac modal alerts (answering them), Chats tab. Room is left for a Chats tab;
  nothing of it is built.
- Watching several sessions at once. The Mac allows it; the app runs one stream at a time, as iOS does.
- `updateStreamParams` frame-rate adaptation. iOS never sends it and the Mac only honours lowering
  the rate (*notes* → Flow control).
- Restoring the Mac window's size after Fit to phone (owner decision; iOS never restores it either).
- Dictation beyond what the Android keyboard's own microphone gives (it works in the composer).
- Tablet layouts, multiple Macs, external keyboards beyond what Android's IME path already delivers.

## Anti-goals (failure even if it "works")

- **Dropping or reordering media.** A media frame that is silently discarded, or a frame decoded
  before the config that precedes it, corrupts the picture or freezes it. The media path never drops a
  frame before the stream controller has seen and acknowledged it.
- **A frozen picture that looks live.** If the stream stalls (acks stopped, decoder wedged, link lost)
  the view says so; it never shows an old frame as if it were current with no indication. A decoder
  that takes input and gives no output for a second is shown as "Picture stalled" with Try again.
- **Keystrokes out of order or doubled.** `ls` must never arrive as `sl` or `lls`. A wrong keystroke in
  an agent's prompt can approve an action the owner did not mean.
- **Logging terminal content.** Frames, typed text, pasted text, selection text, page URLs and session
  names never go to logcat. Session GUIDs and stream IDs may.
- **Resizing the Mac window behind the owner's back.** Fit to phone is an explicit action, never
  automatic.
- **A malformed message the Mac misreads.** Every message the phone sends matches an upstream sample
  as a JSON tree (as in the pairing milestone).

## Constraints

- Mac: iTerm2 3.7.7 (upstream tag `v3.7.7`, `e0025340`). The installed binary does contain the browser
  stream messages (`browserTap`, `browserNavigationChanged`, `browserEditingChanged`, `startSessionStream`,
  `fetchHistoryTile` were all found in `~/Applications/iTerm.app/Contents/MacOS/iTerm2` on 2026-11-16),
  so the app spec's "no browser stream" constraint is out of date: the Mac speaks revision 15.
- Phone: Pixel 9, Android 15+, `minSdk 34`. Hardware HEVC decode through MediaCodec.
- Protocol revisions (*notes* → Revisions and gates): the Mac's floor is 11 and every terminal feature
  works at 11; session-tree pushes need the phone at 13; browser input needs both sides at 15.
- Relay: self-hosted, `RELAY_DAILY_BYTE_QUOTA=8589934592` (8 GiB) on the owner's relay (the relay's
  default is 512 MB/day/room); 500 frames/s/room; frames ≤ 256 KB. The Mac's per-stream budget is
  400 MiB per rolling 24 h, fresh for every new stream.
- Builds, SDK installs and JVM tests run in cloud sessions; on-device checks and instrumented tests run
  on the Mac with the Pixel over adb.

## Approaches

| Approach | How | Trade-off | Decision |
|---|---|---|---|
| **A. Video** | `startSessionStream`, HEVC access units on the media channel, decoded by MediaCodec onto a surface | Matches iOS and the Mac's only live path; change-driven, up to 30 fps; needs an HEVC decoder and care with acks and decoder latency | **Chosen** (owner) |
| B. PNG snapshots | Poll the session's screen as images | Laggy (a request per refresh), heavy on the relay, no change notification; not what the Mac is built for | Rejected |
| C. Text | Fetch the screen as text and render it locally | No protocol support for a live text screen in 3.7.7; agent TUIs lose colour and layout | Rejected |

No fallback from A: if the phone has no usable HEVC decoder the live view shows an error.

## Decisions

### Owner decisions (binding)

- Purpose: watch AI agents in iTerm2 sessions and type into a session when an agent is stuck.
- Live view is video, as on iOS: HEVC from the Mac, MediaCodec hardware decode, no PNG polling fallback.
- Build order: **Live Session**, then **History and Selection**, then **Browser Sessions**, each ending in
  an owner on-device check on the Pixel.
- Agent Chat, Approvals and background Notifications come after this phase.
- No foreground service: on lock or background, stop the stream cleanly and resume on return. The 30 s
  screen-timeout drop is accepted.
- **Keep the screen on** while a live or browser session view is showing, scoped to that screen; the
  session list and Settings let the phone lock. (Decided 2026-11-16; iOS does not do this.)
- **Fit to phone is never undone**: leaving the view does not restore the Mac window (iOS parity).

### Lead's rulings, as carried into this design

- **Navigation.** Once paired, **Sessions** is the home screen. Pairing status, Unpair and app info move to
   a **Settings** screen reached from the top bar's overflow menu. The home is a `Scaffold` keyed on a
   `HomeTab` enum with one value (`SESSIONS`); a bottom bar appears only when a second tab exists, so a
   Chats tab later is one enum value and one destination.

   *Refinement, with reason:* "once Connected" is read as "once a pairing is confirmed". A cold start
   with a stored pairing begins in `Connecting`; bouncing between the pairing status screen and Sessions
   on every reconnect (and every 30 s screen-timeout drop) would be jarring. So Sessions stays home in
   `Connecting`, `WaitingForMac` and `Idle` for a stored pairing, with a status banner, and the pairing
   screens show only for unpaired, fresh-pairing, code and failure states. This needs one new flow on the
   client, `paired: StateFlow<Boolean>` (true once the stored record is `CONFIRMED` or `REGISTERED`).
- **Protocol revision.** Live Session raises `ProtocolRevision.REVISION` from 11 to **13** for session-tree
   pushes. That also claims 12 (`messagesRemoved`) and 13 (`aiAvailabilityChanged`, typed `aiUnavailable`
   error); none is sent to a phone that subscribes to no chat and sends no AI request, and each decodes
   to `Unsupported` and is dropped. History and Selection keeps 13. Browser Sessions raises it to **15**,
   which claims 14: the hello reply gains `macStatus`, and `macStatusChanged` / `modalAlertAnswerRejected`
   may arrive. Those are handled: `macStatus.mainBlocked` and the alert count drive a banner ("iTerm2 is
   showing an alert on the Mac"); answering alerts is the Approvals phase. `MINIMUM_PEER` stays 11.
   Every message a raised revision lets the Mac send has an upstream sample and a test that it decodes
   and leaves the session up.
- **Media channel.** A decrypted application frame whose byte 0 is `0x01` never reaches `decodeEnvelope`
   or the 64-slot event flow. The session's receive loop hands it (marker stripped) to a **stream sink**
   with a suspending, bounded inbox, in the same order as the stream's control messages. The sink is
   non-dropping: when full, the receive loop waits (back-pressure to the socket) rather than discard.
   One application message is capped at 32 MiB during Noise reassembly (over it the link closes and the
   watch ends with "Stream ended"; a 5K keyframe is about 3 MiB). Memory is bounded by byte budgets at each stage: the relay transport queue (64 MiB; over it the
   connection is closed and reconnects, never a dropped encrypted chunk), the stream inbox (256 items
   and 32 MiB), and the decoder's pending input (16 MiB, counted synchronously before any hand-off to the decoder
   thread; a keyframe replaces everything pending, over budget already-acked P-frames are discarded and a
   keyframe is awaited, and a single frame larger than the budget is refused and counted as a decoder failure).
   The custom parameter-set container (`u16 count`, then `u32 length || bytes` per set) becomes MediaCodec
   `csd-0` (each set prefixed with `00 00 00 01`); each access unit's 4-byte big-endian NAL lengths become
   `00 00 00 01` start codes.
- **Known risks**, each with a test or device check (see Risks below):
   *decoder missing or too small*: capabilities checked per config, Fit to phone offered when the
   window can resize, clear error otherwise; *held last frame*: MediaCodec holding the last frame on an
   idle stream: low-latency decoder setup and immediate render, relying on the Mac's insurance keyframe,
   verified by an instrumented test and the owner's check (no idle drain, as iOS); *ack discipline*: after the first `streamAck`,
   every media frame is acknowledged, including frames the phone drops, always with `queueDepth 0`
   as iOS, or the Mac freezes P-frames.
- **Testing.** JVM unit tests for all parsing, framing and state logic, sandboxed and parallel. The Swift
   fixture harness gains media-frame, parameter-set, access-unit and session-message samples from
   upstream code, and packages a real HEVC clip into the Mac's framing, so Kotlin parity tests run on
   real bytes. Decoder and rendering are verified on the Pixel (an instrumented test plus the owner's
   check). **RelayEndToEndTest** gains one test, `streamsLargeKeyframeThroughRealRelay`: a multi-megabyte
   keyframe and a burst of P-frames through the real relay, because only the real relay enforces the
   256 KB frame cap and the 500 frames/s room limit that a 5K keyframe meets. Every other Sessions test
   uses the in-process fake relay.
- **Names.** Phases, sub-phases, tasks, tests, fixtures and files are descriptive, never numbered.

### Other decisions made here

- **Composer sends `pasteText`, not `sendKey`.** iOS's composer types the draft as one `text` key event,
  so each newline becomes a Return. In an agent's prompt (Claude Code, Codex) that submits each line
  separately. The owner asked for `pasteText`, which goes through iTerm2's paste path (bracketed paste
  when the app enabled it), so a multi-line draft lands as one paste. The composer has **Paste** and
  **Paste and Return** (the latter follows with `text "\n"`). Risk: iTerm2 may show its multi-line paste
  warning on the Mac (`PTYSession` paste path is not in the sparse clone); the owner's check records it.
- **Surface: TextureView-backed** (`AndroidEmbeddedExternalSurface`), so Compose transforms (zoom, pan)
  apply to the video. A `SurfaceView` would be lower latency but does not take view transforms. The
  decoder renders each output buffer immediately (`releaseOutputBuffer(index, true)`).
- **Decoder ownership.** The stream controller is app-scoped (survives rotation). All codec calls and
  callbacks run on one decoder thread. The decoder lives only
  while a surface is attached and a config is known; when the surface goes (rotation, background) the
  decoder is released, and when it returns a new one is built from the last config and a keyframe is
  requested.
- **Lifecycle source.** `ProcessLifecycleOwner` (app foreground/background, debounced over configuration
  changes) drives pause/resume, so rotation does not restart the stream. Foreground is tracked apart from
  the connection and the watch intent: a reconnect while backgrounded starts nothing until the app returns.
- **Navigation drives the stream.** An app-scoped navigator holds the current destination (list,
  Settings, live session, browser session); tapping a row opens the stream and Back (system back, the
  top bar's ←, the overlay's Back) closes it. Compose only renders the destination, so Activity
  recreation (rotation) neither stops nor restarts the stream.
- **Keep the screen on** while a live or browser session view is showing (owner decision), so watching an
  agent is not cut off by the 30 s timeout.
- **One stream at a time**, as iOS. Starts are serialised as iOS does (`AppModel.swift:4587-4628`): one
  `startSessionStream` in flight, tied to the link and its request ID and adopted only while the watched session is still the one asked for (as iOS); a `streamStarted`
  that is late, stale or for a session no longer wanted is answered with `stopSessionStream`, and the
  current intent starts after the attempt finishes.
- **Stream parameters:** `{"supportedCodecs":["hevc"],"maxFrameRate":30,"maxMediaFrameVersion":2}`, as iOS.
- **Acks are serialised and newest-wins.** Each media frame produces an ack with its PTS; a single sender
  coroutine sends them in order, and if a send is still in flight the newest pending ack replaces older
  pending ones (the Mac only reads `lastPTSMilliseconds`; *notes* → Flow control). `queueDepth` is
  always 0, exactly as iOS (`SessionView.swift:983`): a real depth above 4 blocks the Mac, and nothing
  would send the lower depth once the decoder drained, freezing the stream.
- **Keystrokes go through one ordered sender** (a single-consumer channel), never a coroutine per key.
  Each item carries its target (link, watch epoch, session); input for a target that is no longer current
  is discarded, and Paste and Return is one item whose Return is sent only if the paste went out.
- **Fit to phone** uses iOS's formula: 60 columns in portrait, 120 in landscape, rows from the cell
  aspect ratio and the live canvas measured with the keyboard hidden. When the decoder cannot take the
  Mac's frame size, Fit caps the columns first, derives the rows from the capped columns, then caps the
  rows, so the new frame fits the decoder. Fit is offered only when the config says `canResize == true` (absent counts as no); the Mac
  refuses resize for full-screen, width-locked and similar windows (`HB:652-660`).
- **No user content in `toString()`.** Every type that carries typed or pasted text, session names,
  titles or URLs overrides `toString()` to print lengths only, with a test per type.

## Architecture

```
core/
  wire/        Envelope (media frames no longer decoded here), SessionMessages,
               StreamMessages (stream, input, tree), ProtocolRevision 13 → 15
  stream/      StreamInput (sink items), MediaFrame (header parse), HevcFraming
               (csd-0, Annex-B), StreamController (watch intent, link scoping,
               generations, acks, keyframe requests, lifecycle), AckSender,
               FitToPhone; later HistoryTiles, SelectionModel, BrowserScroll
  sessions/    SessionTree model, SessionTreeRepository (fetch, push, refresh)
  input/       KeyInput (special keys, modifiers, sticky state), ImeTranslator,
               InputSender (ordered)
  CompanionSession   receive loop branches on byte 0; stream-scoped control
                     and media go to the StreamSink, in order
  CompanionClient    paired flow, link ids, StreamSink wiring
video/
  HevcDecoderCatalog (MediaCodecList query), HevcVideoOutput (MediaCodec async,
  low latency; mirrors iOS CompanionVideoView), PendingInputQueue (byte budget),
  CodecHandle (thin MediaCodec seam for tests)
ui/
  AppRoot (loading vs pairing vs home), SessionNavigator (app-scoped), home/HomeScaffold, sessions/SessionListScreen,
  live/LiveSessionScreen, live/VideoCanvas (zoom/pan), live/KeyBar,
  live/Composer, live/TerminalInputView (InputConnection), settings/SettingsScreen;
  later live/ScrollbackCanvas, live/SelectionMenu, browser/BrowserBar
```

### Media and control path

```
socket → NoiseChannel.decryptFrame → CompanionSession.handle(plaintext)
   ├─ byte0 == 0x01 ──────────────────────────────► StreamSink.accept(Media(linkId, bytes[1..]))
   └─ decodeEnvelope
        ├─ stream-scoped case (streamStarted reply, streamConfig, streamEnded,
        │   streamExtent, selectionRange, browserEditingChanged,
        │   browserNavigationChanged) ───────────────► StreamSink.accept(Control(linkId, requestID, msg))
        │   (a streamStarted reply is sunk first, then its waiter completes)
        ├─ reply with a waiter ──────────────────────► waiter
        └─ other unsolicited ────────────────────────► events (64-slot flow, as today)
CompanionClient: StreamSink.accept(LinkUp(linkId, macRevision)) before Connected,
                 StreamSink.accept(LinkDown(linkId)) when the session ends.
StreamController: one consumer of a bounded (256 items, 32 MiB) suspending inbox;
                  never awaits a Mac reply inside the consumer (requests run in
                  child coroutines that post their outcome back to the inbox).
```

Why the reply goes through the sink: the Mac sends `streamStarted`, then (on a later tick) `streamConfig`
and the keyframe. If the controller learned the stream ID only when its `request()` returned, the config
could be processed first and dropped as unknown (iOS's ordering trap, *notes* → Ordering after start).
Sinking the reply puts it in the same ordered queue.

### Stream controller states

```
Idle ──open(guid)──► Starting ──streamStarted (adopted)──► WaitingForConfig ──config──► Live
  ▲                     │  error unknownSession ─► Ended(sessionClosed)
  │                     │  other error / timeout ─► Retrying (on next link or resume)
  │   Live/Waiting ──streamEnded──► Ended(reason)        (no auto restart, as iOS)
  │   any ──LinkDown──► Reconnecting (intent kept; Paused if backgrounded)
  │        ──LinkUp──► Starting (new stream) only when foreground
  │   any ──background──► Paused (stop sent, intent kept) ──foreground──► Starting
  └── close() (Back): stop sent, intent dropped
streamStarted not adopted (late, other session, other link, closed, paused) ──► stopSessionStream
Live ──config with new size/extradata──► decoder rebuilt (same stream)
Live ──decoder error / frame not decodable / surface attached──► requestKeyframe (≤ 1 per 500 ms)
Live ──codec exception──► codec released, keyframe requested (as iOS onNeedsKeyframe)
Live ──frames accepted, no output for 1 s──► Live(stalled) label; next output clears it;
       Try again stops the stream and starts a fresh one
Live ──decoder unavailable / size unsupported──► Error(kind) (acks continue; config kept for Fit)
```

Stream state is keyed by `(linkId, streamID)`: stream IDs restart at 1 on every link (*notes* → Start).

### Decode loop (held last frame)

- Choose the decoder: hardware HEVC first, preferring one with `FEATURE_LowLatency`; configure with
  `KEY_LOW_LATENCY = 1` when supported and `KEY_PRIORITY = 0` (real time).
- Async MediaCodec on its own `HandlerThread`. Input: access units in order; a frame waits only for a
  free input buffer. Output: `releaseOutputBuffer(index, render = true)` at once; no PTS scheduling.
- **No idle drain, as iOS.** The last frame of a burst is expected out promptly from the
  low-latency configuration and immediate render; the Mac's 0.3 s insurance keyframe follows the last
  P-frame. `HevcDecodePathDeviceTest.lastFrameOfBurstIsOutputWithoutMoreInput` proves it on the Pixel.
  An earlier draft drained (end-of-stream, flush, keyframe request) after every input gap; on a static
  screen each requested keyframe made a new gap, so the phone and Mac looped on keyframes (both
  reviews). It is gone; if the device test fails, the owner decides before any drain is added.
- **Recovery mirrors iOS `CompanionVideoView`:** no decoder or a decode failure → request a keyframe
  (rate-limited to one per 500 ms on Android, where MediaCodec can fail per frame). The only
  Android-only state is one timestamp, the first frame submitted since the last output, which drives
  the "Picture stalled" label.
- **Robustness.** `KEY_MAX_INPUT_SIZE = max(width × height, 4 MiB)`; the previous codec is released
  before a new one is configured on the same surface; any runtime exception from a codec call releases
  it, and the next keyframe rebuilds it (one is requested).

### Typing

- `TerminalInputView`: an Android `View` hosted in Compose, focusable, with `onCreateInputConnection`
  returning an `InputConnection` configured `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
  TYPE_TEXT_FLAG_NO_SUGGESTIONS` and `IME_FLAG_NO_FULLSCREEN | IME_FLAG_NO_EXTRACT_UI` (no autocorrect,
  suggestions or capitalisation, matching iOS's settings). `ImeTranslator` (pure) turns IME calls into
  key events: `commitText` → `text` per call (a one-shot modifier applies to the first code point only,
  as iOS); composing text is held until committed or finished, then sent once, and is sent **before**
  any editor action, key event or key-bar key (so `ls` then Return is `ls`, Return, never Return, `ls`);
  `deleteSurroundingText` and `KEYCODE_DEL` shorten held composing text without sending anything, and
  otherwise → `special backspace` per character; `KEYCODE_ENTER`, editor action and `"\n"` →
  `text "\n"`; hardware arrow/escape/tab key events → their special keys.
- Key bar (above the IME, `imePadding`): compact row `esc`, `^C`, `tab`, ← ↓ ↑ →, compose, expand,
  hide keyboard; expanded tray `ctrl`, `⌥ Left`, `⌥ Right` (sticky: tap = one-shot, double tap = lock;
  left and right Option exclusive), `home`, `end`, `del`, `PgUp`, `PgDn`, `F1`–`F12`. Exactly iOS's set
  (*notes* → `sendKey`).
- The keyboard is offered only while the stream is live and the session is a terminal (or a browser
  session at revision 15).

## Screens (text sketches)

### Sessions (home)

```
┌──────────────────────────────────────────┐
│ Sessions                 ● Connected   ⋮ │  ⋮ → Settings
├──────────────────────────────────────────┤
│ (banner when not Connected:)             │
│  ⟳ Reconnecting…   /  Waiting for your   │
│    Mac. Is iTerm2 running?               │
├──────────────────────────────────────────┤
│ WINDOW 1                                 │
│   Tab 1 · build                          │
│     ▣ claude                             │
│       ~/src/relay                        │
│     ▣ zsh                                │
│       ~/src/relay                        │
│   Tab 2                                  │
│     ▤ Split Pane                         │
│        ▣ planner: claude                 │
│        ▣ reviewer: codex                 │
│ WINDOW 2                                 │
│   Tab 1                                  │
│     ◍ GitHub                             │  ◍ = browser session
│       github.com/…/pulls                 │
└──────────────────────────────────────────┘
 Pull down to refresh. Empty: "No sessions are open in iTerm2."
 Error: "Couldn't load sessions." [Try again]
```

Row rules follow iOS (*notes* → Session tree): a section per window, a tab row, a row per pane; a pane
with peers is a non-tappable "Split Pane" row with peers nested when the tab has other panes, else peers
are hoisted; peer title `"<role>: <name>"` or `<role>` when the name is empty or equal.

### Settings

```
┌──────────────────────────────────────────┐
│ ← Settings                               │
├──────────────────────────────────────────┤
│ Mac          Connected (iTerm2 rev 15)   │
│ Relay        relay.example.ts.net     │
│ [ Unpair this phone ]                    │  confirm dialog first
│ App version  0.2.0-<sha>                 │
│ Decoder      c2.<vendor>.hevc.decoder    │  (debug builds: + "Video stats" toggle)
└──────────────────────────────────────────┘
```

### Live view (terminal)

```
┌──────────────────────────────────────────┐
│ ← claude              ⌨  ⋮               │  ⌨ show/hide keyboard
├──────────────────────────────────────────┤   ⋮ Fit to phone, (debug) Video stats
│ ┌──────────────────────────────────────┐ │
│ │ ✻ Working on flaky test…             │ │  video, aspect-fit, pinch to zoom
│ │   Read tests/net_test.go             │ │
│ │   Proceed? (y/n) █                   │ │
│ └──────────────────────────────────────┘ │
├──────────────────────────────────────────┤
│ esc  ^C  tab │ ←  ↓  ↑  → │ ✎  ⌃  ⌄      │  key bar (with keyboard up)
├──────────────────────────────────────────┤
│              Android keyboard            │
└──────────────────────────────────────────┘
 Overlays: "Starting…" (spinner), "Reconnecting…" (last frame dimmed),
 "Session closed on the Mac" [Back], "Stream ended: daily data limit reached" [Restart],
 "This phone has no HEVC video decoder", "The Mac window (5120×2880) is too large for
 this phone's decoder (max 4096×2304)." [Fit to phone] (only when the window can resize; else
 "Make the iTerm2 window smaller on the Mac (leave full screen) to watch it here."),
 "Picture stalled" [Try again], "Can't show session" [Try again].
 The screen stays on while this view shows.
 Browser session before revision 15: video only, note "Browser control comes later".
```

Expanded tray (⌃ chevron):

```
│ ⌃ctrl  ⌥Left  ⌥Right │ home  end  del │
│ PgUp  PgDn                            │
│ F1  F2  F3  F4  F5  F6                │
│ F7  F8  F9  F10 F11 F12               │
```

Armed modifiers are highlighted; locked ones show a bar under the glyph.

### Zoom

```
 fit (scale 1)                     zoomed (scale 3, panned to bottom-right)
┌───────────────────────┐          ┌───────────────────────┐
│ tiny 220-col text ... │          │ (y/n) █               │
│ ...                   │   ──►    │                       │
│ Proceed? (y/n) █      │          │                       │
└───────────────────────┘          └───────────────────────┘
 pinch anchored at the centroid; double tap toggles fit ↔ 2.5× at the tap;
 max scale = max(1, 4 × encoded px / fitted view px); pan clamped to the image.
```

### Composer

```
┌──────────────────────────────────────────┐
│ Compose for claude                  ✕    │
├──────────────────────────────────────────┤
│ Please look at the lint errors in        │
│ src/net/ first, then rerun the flaky     │
│ test 20 times.                           │
│                                          │
├──────────────────────────────────────────┤
│ [ Paste ]               [ Paste ⏎ ]      │
└──────────────────────────────────────────┘
 A bottom sheet over the live view. Draft kept until sent or cleared.
 Paste → pasteText(draft); Paste ⏎ → pasteText(draft) then sendKey text "\n".
```

### Scrollback and selection (History and Selection)

```
┌──────────────────────────────────────────┐
│ ← claude              ⌨  ⋮               │
├──────────────────────────────────────────┤
│ [history tile PNG: lines 1150–1199]      │  drag down = older
│ [history tile PNG: lines 1200–1249]      │
│ ┄┄┄┄┄┄┄┄┄┄┄┄ live ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ │
│ [live video band: bottom `rows` lines]   │
│                          [ ↓ Live ]      │  shown when scrolled up
└──────────────────────────────────────────┘
 Long press 0.3 s → selection starts (drawn by the Mac into video and tiles);
 drag extends; floating menu: [Copy] [Select All] [Paste].
 Alternate screen with mouse reporting (vim, less, htop): no tiles; a drag
 sends reportScrollWheel notches instead.
 Tiles hold history only: the newest one is short until more output scrolls off, then it is
 refetched. Tile, extent and selection handling mirror the iOS client (AppModel, SessionView):
 a drop in total lines (or any extent event with an unchanged origin) is a clear, otherwise a trim.
 Leaving or replacing a stream drops its tiles and selection. A selection change or a history clear
 invalidates the affected tiles and drops replies already in flight for them. Tile memory is held to
 byte budgets.
```

### Browser session (Browser Sessions)

```
┌──────────────────────────────────────────┐
│ ←  ‹  ›  [ github.com/gnachman/…  ]  ⟳ ⌨ │  ‹ › disabled per canGoBack/Forward;
├──────────────────────────────────────────┤  ⟳ becomes ✕ (Stop) while loading
│                                          │
│            web page video                │  tap → browserTap; drag → browserScroll
│                                          │  (fling continues); pinch zooms locally
│                                          │
├──────────────────────────────────────────┤
│ esc  tab │ ←  ↓  ↑  →  ⌄                │  key bar (no ^C row change needed)
│              Android keyboard            │  rises when the page reports editing
└──────────────────────────────────────────┘
 Address field shows the page URL unless focused; focus selects all; Go →
 browserLoad(trimmed text), skipped when empty; blur restores the URL.
```

## Risks and how each is closed

| Risk | Design response | Proof |
|---|---|---|
| No HEVC decoder on the phone | `HevcDecoderCatalog` at the first config; Error "This phone has no HEVC video decoder" | JVM test with a fake catalog; Pixel check |
| Frame larger than the decoder | `isSizeSupported(w, h)` on every config; Error with **Fit to phone** (when resizable), whose grid is capped to fit the decoder; Mac-side advice otherwise | JVM tests (`fitGridCapsToDecoderLimit`, `fitGridCapsColumnsBeforeRows`, `tooLargeOffersFitOnlyWhenResizable`); owner check with a large and a full-screen Mac window |
| Last frame held on an idle stream | Low-latency config, immediate render, Mac insurance keyframe, as iOS | `HevcDecodePathDeviceTest`; owner's idle and static-screen checks |
| Acks stop or block → Mac freezes P-frames | Ack every frame on dequeue, including dropped ones, always with `queueDepth 0`; acks never wait on the decoder | FakeMac in-flight limiter tests (`ackQueueDepthIsAlwaysZero`); long-watch owner check |
| Decoder wedged while frames arrive | iOS-style keyframe requests on failure; "Picture stalled" label after 1 s without output; Try again restarts | `overdueOutputIsLabelledStalled`, `idleTerminalStaysLive`, `codecExceptionsReleaseAndRequestKeyframe` |
| Late `streamStarted` bound to the wrong session | Serialised starts tied to link, request ID and the watched session (iOS guard); anything not adopted is stopped | `switchingSessionsSerializesStarts`, `lateStartReplyForOtherSessionIsStopped`, `startAfterTimeoutIsStopped` |
| Memory growth behind a slow consumer | Byte budgets at transport, inbox, decoder input and history tiles | `transportOverBudgetReconnects`, `inboxSuspendsAtByteBudget`, `encodedCacheStaysWithinBudget` |
| Rotation restarts the stream | Navigation-driven open/close from an app-scoped navigator | `SessionNavigatorTest`; owner's rotation check |
| Media dropped by the event flow | Separate suspending sink; `decodeEnvelope` no longer sees media | `mediaBurstIsNeverDropped`, `mediaStaysOrderedWithConfig` |
| Stream ID reuse across reconnects | State keyed by link id | `framesFromAnEndedLinkAreIgnored` |
| Config overtaking older media | Drop frames with an older `generationId`; rebuild only when extradata or size changes | `olderGenerationFramesAreDroppedButAcked`, `identicalConfigKeepsDecoder` |
| Inbox deadlock | Consumer never awaits a reply; requests in child coroutines | `historyTileReplyDuringMediaBurst` (History) and a Review Focus item |
| IME duplicates or loses characters | `ImeTranslator` pure, composing held until commit | JVM tests per IME call sequence; owner check with Gboard |
| Multi-line paste warning on the Mac | Recorded in the owner's check; workaround is iTerm2's own setting | Owner check |
| Relay quota from frequent restarts | 8 GiB on the owner's relay; restart only on return, not on every config | Noted; no test |
| Browser config has 0 rows/columns | Guard every divide; no Fit, selection or tiles for browsers | JVM tests with the browser config sample |

## Testing

- **JVM unit tests** (sandboxed, parallel, virtual time): wire parity for every new phone→Mac message;
  decode of every new Mac→phone sample, including the revision 12–15 extras; media frame parse (v1 and
  v2, rejects); csd-0 and Annex-B conversion; stream controller state machine against a fake decoder and
  an in-process FakeMac that implements the Mac's in-flight limiter; ack sender; fit-to-phone math;
  zoom/pan math; touch-to-cell mapping; IME translation; sticky modifiers; session-tree flattening;
  history tile scheduling and `streamExtent`; browser scroll accumulation and fling.
- **Fixtures** from the Swift harness (upstream v3.7.7 code): `fixtures/messages/live-session/`,
  `fixtures/messages/history-and-selection/`, `fixtures/messages/browser-sessions/`,
  `fixtures/media/frames.json`, `fixtures/media/hevc/terminal-clip.json` (a real HEVC clip encoded by
  x265 in the cloud, packaged with upstream `CompanionHEVCFraming` and `CompanionMediaFrame`).
- **Relay end-to-end:** one new test, `streamsLargeKeyframeThroughRealRelay` (reason under Testing in the rulings).
- **Instrumented on the Pixel:** `HevcDecodePathDeviceTest` decodes the clip through the production
  conversion and decoder (idle drain off) into an `ImageReader` surface, asserting every frame is output
  and that the last frame of a burst appears without further input within 250 ms.
- **Owner on-device checks** at the end of each sub-phase, recorded in `docs/reports/`.

## Open questions for the owner

None open. Answered 2026-11-16: keep the screen on in session views (yes); restore the Mac window size
after Fit to phone (no, never). Only if the device test fails: whether an idle drain is needed.

## Left to the builder

- Compose structure, state holders and whether to use `navigation-compose` or a small hand-rolled back
  stack (only three destinations exist).
- Exact visuals: colours, key cap shapes, icons, banner wording beyond the strings given above.
- The decoder's input-buffer sizing and the `HandlerThread` layout, within the decode-loop rules.
- Zoom behaviour on a geometry change (keep the scale and clamp the pan, or reset to fit).
- Whether hardware keyboard events beyond the special keys (Ctrl+letter on a Bluetooth keyboard) are
  mapped; nice to have, not required.
- History tile rendering details within the plan's byte budgets (decode off the main thread, at most two at once).
- Browser fling tuning within the iOS constants.
