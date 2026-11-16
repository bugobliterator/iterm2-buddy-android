# iTerm2 Buddy protocol notes: Sessions (phone side)

Research notes for the Sessions work (session list, live video, zoom, typing, fit-to-phone), then
scrollback tiles, select/copy, and browser sessions. Companion to `2026-11-15-protocol-notes.md`
(pairing, envelope, Swift enum coding rules; not repeated here).

Read from iTerm2 v3.7.7 (sparse clone). Path prefixes:
`SH` = `sources/Companion/Shared`, `MC` = `sources/Companion` (Mac only),
`HB` = `MC/CompanionHostBridge.swift`, `ST` = `MC/CompanionSessionStreamer.swift`,
`CPV` = `Companion/CompanionCore/Sources/CompanionProtocol/CompanionProtocolVersion.swift`,
`MSG` = `SH/CompanionMessages.swift`,
`IOS` = `Companion/iTerm2Companion`, `AM` = `IOS/App/AppModel.swift`, `SV` = `IOS/Screens/SessionView.swift`,
`CC` = `IOS/Connection/CompanionClient.swift`,
`AND` = `app/src/main/java/dev/bugobliterator/itermbuddy/core` (this repo).

Wire reminders (from the pairing notes): envelope `{"requestID"?: n, "payload": {"<case>": body}}`;
labelled associated values are an object; a single unlabelled value is `{"_0": …}`; `Data` = std base64;
nil optionals omitted. Fire-and-forget messages carry no `requestID` (`SH/CompanionSession.swift:83-86`).
**Synthesized Swift `Decodable` ignores property defaults**: every non-optional field in a struct the
phone sends must be present, even if it has a default in Swift (e.g. all four `CompanionKeyModifiers` bools).

## 1. Revisions and gates

Constants (`CPV`): `current = 15` (158), `minimumPeer = 11` (166), `streamingRevision = 3` (171),
`selectionGeometryRevision = 4` (176), `sessionResizeRevision = 6` (192), `keyInputRevision = 10` (213),
`aiDecouplingRevision = 13` (230), `modalAlertRevision = 14` (238), `browserInputRevision = 15` (246).
Rev 9 (alt screen + `reportScrollWheel`) has no constant; the config booleans self-gate (78-86).

| Feature | Mac must be ≥ | Who gates | Source |
|---|---|---|---|
| Live stream, media channel | 3 | phone (`supportsStreaming`) | `CPV:32-35`, `CC:73`, `AM:4587` |
| `cellGeometry`, media frame v2 (`generationId`, `liveTop`), selection | 4 | phone (`macRevision ≥ 4 && cellGeometry != nil`) | `CPV:37-40`, `AM:1082-1084` |
| `resizeSession` | 6 | phone (`sessionResizeSupported`) | `CPV:55-60`, `AM:2692` |
| `altScreen`/`scrollWheelReporting`, `reportScrollWheel` | 9 | config bools | `CPV:78-86` |
| `sendKey` | 10 | phone (`keyInputSupported`) | `CPV:88-95`, `AM:2705`, `AM:5177` |
| History tiles, `streamExtent`, `selectionRange` | no constant; ride rev 4 geometry | — | `MSG:347-351`, `MSG:1116-1129` |
| Browser input, `isBrowser`, `browser*` | 15 | phone (`browserInputSupported`) | `CPV:143-157`, `AM:4671-4679` |

Since every Mac the phone can pair with is ≥ 11 (`minimumPeer`), all terminal features are available
on any compatible Mac; only browser input needs a rev-15 Mac.

What the Mac gates on the **phone's** advertised revision (`CompanionPushRegistry.peerRevision`,
persisted from the last hello, `MC/CompanionPushRegistry.swift:128-134`):
- Stream start, media, `sendKey`, `pasteText`, `resizeSession`, tiles, selection: **not gated** (`HB:551-603`).
- Unsolicited `sessionTree` push: peer ≥ 13 (`HB:240-243`).
- `browserEditingChanged` / `browserNavigationChanged`: peer ≥ 15 (`HB:748-751`, `HB:776-779`).
- `macStatus` in the hello reply: peer ≥ 14 (`MC/CompanionLink.swift:523`).
- Media frame version: negotiated per stream by `maxMediaFrameVersion`, not by revision (`HB:1228-1229`).

Android today: `REVISION = 11`, `MINIMUM_PEER = 11` (`AND/wire/ProtocolRevision.kt:11-14`).
- Terminal Sessions work at 11 unchanged. Raise to **13** to get live session-tree pushes; that also
  claims rev 12 (`messagesRemoved`) and 13 (`aiAvailabilityChanged`, typed `aiUnavailable` error), both
  harmless if ignored (unknown cases decode to `Unsupported`).
- Browser phase: raise to **15**, which also claims 14 (hello reply gains `macStatus`; `macStatusChanged`,
  `modalAlertAnswerRejected` events). Android keeps the hello reply as raw JSON, so the extra field is
  tolerated; holding request timeouts while `mainBlocked` is optional.
- Hello carries only `{revision, minimumPeer}` (`CC:76-78`); there are no capability fields. Per-stream
  capabilities ride in `CompanionStreamParams` (section 3).

## 2. Session tree

- Request `{"fetchSessionTree":{}}` with a requestID. Reply `{"sessionTree":{"_0":{"windows":[…]}}}`
  (`MSG:765`, `MSG:1055`, `HB:525-526`).
- Shape (`MSG:93-115`): `windows:[{title, tabs:[{title, panes:[{session, peers:[{roleName, session}]}]}]}]`.
  `session` = `CompanionSessionSummary{guid, name, subtitle, isBrowser}` (`MSG:37-60`; `isBrowser` absent
  from old Macs decodes false).
- Built by `CompanionSessionLister.tree()` (`MC/CompanionSessionLister.swift:36-59`): window title falls back
  to `"Window N"`, tab title to `"Tab N"` (1-based). `peers` is empty unless the pane's peer group has
  more than one realized member; peers are sorted by workgroup position, `roleName` = workgroup label
  or the session name (64-83).
- **Identity**: `guid` is `PTYSession.stableID`, durable across a shell reload; treat it as opaque and
  send it back verbatim (`MC/CompanionSessionLister.swift:23-31`). The Mac resolves it with
  `anySession(forReference:)` (`HB:577`, `HB:649`, `HB:675`).
- **Browser sessions**: same tree, same node kinds, marked only by `session.isBrowser == true`
  (`MC/CompanionSessionLister.swift:31`). iOS shows a globe icon instead of a terminal icon
  (`IOS/Views/SessionTreeList.swift:16-20`).
- iOS rendering (`IOS/Views/SessionTreeList.swift:73-119`): one section per window, a tab row, a row per
  pane. A pane with peers is a container: shown as a non-tappable "Split Pane" row with peers nested only
  when the tab has other panes, else peers are hoisted to tab level. Peer title
  `"<role>: <name>"`, or just `<role>` when the name is empty or equals the role.
- **Refresh**: iOS fetches on tab appear (`.task`), pull-to-refresh, "Try Again", and after reconnect if a
  tree was loaded or the tab is selected (`IOS/Views/SessionTreeList.swift:42,53,60-62,107`, `AM:2409-2414`).
  15 s timeout (`AM:1384`). Pushed: the Mac observes `PTYSessionTerminated`,
  `iTermNumberOfSessionsDidChange`, `PTYSessionPresentationNameDidChange` and sends an unsolicited
  `sessionTree` (no requestID), leading + trailing rate limit, min 2 s apart, byte-identical trees dropped
  (`HB:96-112`, `HB:182-272`). iOS replaces its tree in place (`AM:4456-4466`). Peer ≥ 13 only.
- No `sessionTreeChanged` event; the push reuses the `sessionTree` case. A solicited reply and a push are
  told apart only by requestID.

User-facing: Sessions tab lists "Window 1 > Tab 1 > zsh"; closing a tab on the Mac removes its row
within about 2 s without a pull-to-refresh (needs phone revision ≥ 13).

## 3. Live stream lifecycle

### Start
P→M `{"startSessionStream":{"sessionGuid":"…","params":{…}}}` with a requestID (`MSG:821`).
`CompanionStreamParams` (`MSG:189-214`): `supportedCodecs: ["hevc"|"h264"]` (required), `maxFrameRate: Double`
(required), `maxBitrate: Int?`, `maxMediaFrameVersion: Int?`. iOS sends
`{"supportedCodecs":["hevc"],"maxFrameRate":30,"maxMediaFrameVersion":2}` (`AM:4592-4593`).

Mac handling (`HB:1199-1296`):
1. No `hevc` in `supportedCodecs` → `error{code:"badRequest", message:"No supported video codec (HEVC required)"}` (1202-1207).
2. Session lookup via `contentSession` → `error{code:"unknownSession"}` if gone or its textview has zero width
   ("hasn't started running yet") (`HB:2012-2027`).
3. **One stream per session guid**: an existing one is ended with `superseded` (1211-1214). Several
   sessions can stream at once (`streams` is a dictionary); iOS runs one at a time (`AM:552-557`).
4. `streamID = nextStreamID++`, starting at 1 **per bridge**, and a new bridge is made per link
   (`HB:85`, `HB:1216-1217`, `MC/CompanionPairingController.swift:1579`): IDs repeat across reconnects.
5. `frameRate = maxFrameRate > 0 ? min(maxFrameRate, 60) : 30`; `bitrateCeiling = maxBitrate` clamped to
   [100 000, 40 000 000], else 24 000 000 (1218-1224).
6. `mediaVersion = min(2, max(1, maxMediaFrameVersion ?? 1))` (1228-1229).
7. Source: `CompanionBrowserFrameSource` if `isBrowserSession()`, else `CompanionTerminalFrameSource` (1237-1240).
8. A main-thread `Timer` at `1/frameRate` drives `driveStream` (1269-1274); acks are routed off-main
   (1277-1279).
9. Reply `{"streamStarted":{"_0":{"streamID":N,"codec":"hevc"}}}` (1288-1289). Always `hevc`.
10. Terminal only: an unsolicited `selectionRange` for any pre-existing selection (1293-1295).

### Ordering after start
`streamStarted` is enqueued during the start call; the first `streamConfig` + keyframe come from a later
timer tick, so on the wire: `streamStarted` → (`selectionRange`) → `streamConfig` → media keyframe
(flags `keyframe|configChanged`). Outbox lanes drain **control > media > coalescing**
(`MC/CompanionPriorityOutbox.swift:5-22, 84-95`): `streamConfig`, `streamEnded`, replies are control;
`streamExtent` and `selectionRange` are coalescing (latest wins per key `streamExtent.<id>` /
`selectionRange.<id>`, `HB:1252-1257`, `HB:1177-1178`) and can arrive **after** newer media frames.
A resent config can overtake older media frames still queued; that is why media frames carry
`generationId` (`SH/CompanionMediaFrame.swift:26-30`).

iOS drops configs/media whose `streamID != activeStreamID` (`AM:4368`, `AM:4509`), and only sets
`activeStreamID` after the start reply resolves (`AM:4596-4600`). On Android, set the active stream ID in the
receive loop when the reply is dispatched, so the config that follows is not dropped.

### Config: `{"streamConfig":{"_0":{…}}}` (`MSG:330-419`, built at `ST:575-596`)
| Field | Type | Meaning |
|---|---|---|
| `streamID` | UInt32 | |
| `generationId` | UInt32 | bumped only when `pixelWidth/Height, columns, rows, cellGeometry` change (`ST:93-119`); first config of a stream is 1 |
| `codecExtradata` | base64 | parameter-set container (section 4) |
| `pixelWidth`, `pixelHeight` | Int | encoded frame size = rendered image size (`ST:566-567`) |
| `scale` | Double | encoded px per Mac point (window backing scale; browser: snapshot px / web view pt) |
| `columns`, `rows` | Int | terminal grid; **0/0 for a browser** |
| `cellGeometry?` | `{cellWidth, cellHeight, leftMargin, topMargin}` (Double, encoded px) | terminal: `charWidth*scale`, `lineHeight*scale`, `sideMargins*scale`, `0` (`MC/CompanionTerminalFrameSource.swift:32-41`); omitted for a browser |
| `firstAbsLine` | Int64 | oldest available absolute line (= total scrollback overflow) |
| `totalLines` | Int | scrollback + screen lines |
| `canResize?` | Bool | window can take an arbitrary grid now (not fullscreen/maximized/edge-attached/width-locked); browser false |
| `altScreen` | Bool | soft alternate screen active |
| `scrollWheelReporting` | Bool | app wants mouse-wheel reports |
| `isBrowser` | Bool | rev 15 |

Resends: on geometry change (new generation), on a fresh encoder (new parameter sets), on `requestKeyframe`,
and on a `canResize`/`altScreen`/`scrollWheelReporting` flip, the last three **keeping** the generation
(`ST:319-324`, `ST:350-367`, `ST:553-557`). A config is only emitted alongside an encoded keyframe that
carries parameter sets (`ST:565`); the frame right after it has `configChanged` set (`ST:595`).

### Stop, end, restart
- P→M `{"stopSessionStream":{"streamID":N}}`, fire-and-forget; Mac replies unsolicited `streamEnded` (`HB:553-554`, `HB:1341-1351`).
- `{"streamEnded":{"streamID":N,"reason":"…"}}`; reasons (`MSG:620-627`) and when the Mac sends them:
  `stoppedByClient` (stop), `sessionClosed` (session lookup fails in `driveStream`, bridge teardown, unpair;
  `HB:300`, `HB:341`, `HB:1300-1302`), `superseded` (second start for the same guid), `dataLimitReached`
  (per-stream budget spent, `HB:1259-1262`). `error` is defined but **never emitted** in this build.
- Media flag `endOfStream` (bit 2) is defined but **never set** by the Mac; `streamEnded` is the only end signal.
- iOS on `streamEnded` for the active stream: clears stream state and **drops the watch intent** (no auto
  restart); shows "Stream Ended" only for `sessionClosed`, `error`, `dataLimitReached` (`AM:4440-4455`,
  `SV:985-1005`). `unknownSession` on start is treated as `sessionClosed` (`AM:4605-4615`); any other start
  error is retried on the next reconnect/resume (`AM:4616-4618`).
- Background/foreground: `scenePhase .background` → `pauseLiveStream` (sends stop, keeps intent);
  `.active` → `resumeLiveStream` (new `startSessionStream`, new streamID, fresh keyframe) (`SV:951-961`,
  `AM:4570-4581`). Leaving the view → `stopWatchingSessionLive` (stop + drop intent) (`SV:950`, `AM:4561-4566`).
- Reconnect: `connectionLost` drops `activeStreamID` but keeps the intent (`AM:2308-2322`); after the new
  hello, `restartLiveStreamAfterReconnect` resets generation to 0, clears tile cache, starts a new stream
  (`AM:2463`, `AM:4641-4653`). The Mac ended all streams on its side when the old link died (`HB:331-341`).
  A start racing a close/switch is stopped right after its reply (`AM:4599-4603`).

## 4. Media channel

### Channel tag
There is no tag inside the Noise layer. After Noise reassembly (cont byte chunking), byte 0 of the
**application frame** decides: `0x01` = media, anything else = JSON control (`SH/CompanionFrameChannel.swift:26-60`).
The Mac prepends `0x01` when it sends from the media lane (`MC/CompanionLink.swift:351-357`). Media frames
go only to a phone that started a stream. Large keyframes span several Noise chunks; reassembly is unchanged.

Android today: `NoiseChannel.decryptFrame` reassembles correctly (`AND/noise/NoiseChannel.kt:96-108`).
`decodeEnvelope` maps a `0x01` frame to `MacMessage.Unsupported("media", {})` (`AND/wire/Envelope.kt:20-21, 58-61`),
which `CompanionSession.handle` passes to `onEvent` (`AND/CompanionSession.kt:253-288`), then into a
64-slot `SharedFlow` via `tryEmit` that **drops on overflow** (`AND/CompanionClient.kt:192, 967, 986`).
So the media bytes are discarded; nothing parses them. Needed: branch on byte 0 in `handle()` before
`decodeEnvelope`, hand the payload (marker stripped) to a dedicated media sink (not the event flow), and
keep it ordered with control frames.

### Media frame layout (`SH/CompanionMediaFrame.swift:12-24, 109-171`), all integers big-endian
| Offset v1 | Offset v2 | Size | Field |
|---|---|---|---|
| 0 | 0 | 1 | `version` (1 or 2) |
| 1 | 1 | 1 | `flags` |
| 2 | 2 | 4 | `streamID` u32 |
| 6 | 6 | 4 | `sequence` u32 |
| 10 | 10 | 8 | `ptsMilliseconds` u64 |
| — | 18 | 4 | `generationId` u32 |
| — | 22 | 8 | `liveTop` i64 (two's complement) |
| 18 | 30 | 4 | `payloadLength` u32 |
| 22 | 34 | n | payload |

Header 22 (v1) / 34 (v2). Decode rejects other versions, short buffers, and `payloadLength` ≠ remaining
bytes (130-171). Flags (47-60): bit0 `keyframe`, bit1 `configChanged`, bit2 `endOfStream` (unused).
- `sequence` = per-stream frame counter from 0, +1 per emitted frame (`ST:430-431, 599`).
- `ptsMilliseconds` = Mac `CACurrentMediaTime()*1000` at capture; variable frame rate; gaps are real time (`HB:1338`, `ST:454`).
- `liveTop` = absolute line of the top visible row (terminal: `numberOfScrollbackLines + totalScrollbackOverflow`;
  browser 0) (`MC/CompanionTerminalFrameSource.swift:45-47`). iOS stores it per frame (`AM:4508-4513`).
- Ask for v2: send `maxMediaFrameVersion: 2`. Omitting it gets v1 (no generation/liveTop).

### Payload and parameter sets
- Payload = one HEVC access unit in **AVCC/length-prefixed** form: each NAL preceded by a 4-byte big-endian
  length (`SH/CompanionHEVCFraming.swift:16-20, 73-94`; VideoToolbox output copied verbatim,
  `MC/CompanionVideoEncoder.swift:118-142`). Not Annex-B.
- `codecExtradata` is **not** an hvcC box, despite the comment at `MSG:335`. It is a custom container:
  `u16 count` then per set `u32 length || bytes`, big-endian (`SH/CompanionHEVCFraming.swift:37-67`).
  Contents: every HEVC parameter set VideoToolbox reports for the keyframe's format description
  (VPS, SPS, PPS in practice) (`MC/CompanionVideoEncoder.swift:154-176`).
- Parameter sets come only from keyframes (`MC/CompanionVideoEncoder.swift:130-134`) and ride the config,
  not the media payload. Whether VideoToolbox also puts them in-band in the IDR access unit is not stated
  in source; assume not.
- iOS: `decodeParameterSets` → `CMVideoFormatDescriptionCreateFromHEVCParameterSets(nalUnitHeaderLength: 4)`
  → new `VTDecompressionSession` on **every** config (`SV:969-975`, `IOS/Screens/CompanionVideoView.swift:65-100`,
  `SH/CompanionHEVCSampleBuilder.swift:25-57`). Each AU is wrapped as a sample (PTS ms/1000,
  DisplayImmediately) and decoded async; output is drawn as `layer.contents` aspect-fit, with a PTS guard
  against regressions (`IOS/Screens/CompanionVideoView.swift:103-171`). iOS does not check `generationId`
  before decoding.
- Android mapping: MediaCodec `video/hevc`; `csd-0` = each parameter set prefixed with `00 00 00 01`,
  concatenated; convert each AU's 4-byte lengths to `00 00 00 01` start codes before `queueInputBuffer`.
  Rebuild the codec only when `codecExtradata` bytes or pixel size change (a resend with identical bytes
  can keep the decoder, avoiding a decode-error/keyframe loop when older-generation P-frames are still
  queued).

### Codec negotiation
`CompanionStreamCodec` has `hevc` and `h264` (`MSG:183-186`), but the Mac always builds an HEVC
`VTCompressionSession` (`kCMVideoCodecType_HEVC`, Main, auto level) and refuses a start without `hevc`
(`MC/CompanionVideoEncoder.swift:53-72`, `HB:1202-1207`, `HB:1288`). **H.264 is never produced.**
Encoder: real-time, no frame reordering (DTS = PTS), keyframes only on demand (`MaxKeyFrameInterval =
Int.max`), `AverageBitRate` plus `DataRateLimits = [2×averageBitRate, 1]` (`MC/CompanionVideoEncoder.swift:69-82`).

### Frame geometry
- Terminal: image = the live viewport, bottom `rows` lines, rendered without top/bottom margins but with
  side margins, cursor and selection drawn (`MC/CompanionTerminalFrameSource.swift:75-109`). Pixel size is
  the Mac window's content at backing scale; there is no phone-chosen resolution.
- Touch → cell (`SH/CompanionTouchMapper.swift:30-49`): undo aspect-fit letterbox to an encoded pixel, then
  `column = floor((x - leftMargin)/cellWidth)`, `row = floor((y - topMargin)/cellHeight)`, clamp to grid,
  `absLine = liveTop + row` (`MSG:222-230`).
- Browser: image = WKWebView snapshot at backing scale, ≤ 10 snapshots/s, with a drawn blinking caret when
  WebKit does not draw one (`MC/CompanionBrowserFrameSource.swift:27-34, 207-258`).

## 5. Flow control

Mac side:
- **Pacer** (`MC/CompanionStreamPacer.swift:58-68`): emit only when dirty and `now - lastEmit ≥ 1/fps`;
  a requested keyframe bypasses the cap. Dirty = source `contentLastChangedAt` advanced (`HB:1306-1312`).
- **Dedup**: a non-keyframe whose pixel hash equals the last sent frame is skipped (`ST:413-421`).
- **Insurance keyframe**: 0.3 s after the last emitted P-frame with nothing new, a keyframe of the
  current screen is sent (`ST:206`, `ST:374-382`).
- **Resolution throttle**: `effectiveFps = max(min(10, maxFps), min(maxFps, 180e6 / (w*h)))`;
  `bitrate = clamp(w*h*0.02*effectiveFps*multiplier, 1 Mb/s, ceiling)` (`ST:224-265`). Multiplier default 1.0.
- **In-flight limiter** (`MC/CompanionInFlightLimiter.swift:28-58`, defaults from
  `sources/Settings/iTermAdvancedSettingsModel.m:1031-1032`): before the first ack, always allowed. After,
  non-keyframes stop when `lastSentPTS - lastAckedPTS > 500 ms` or reported `queueDepth > 4`; a pending
  keyframe still goes (`ST:369-373`). While blocked, no insurance keyframe either.
- **Byte budget**: 400 MiB per stream in a rolling 24 h window (`ST:271`, `MC/CompanionStreamBudget.swift:15-57`);
  when spent, `streamEnded(dataLimitReached)` (`ST:387-393`). The budget is per streamer instance, so each new
  stream starts fresh; the relay's own quota (512 MB/day/room, pairing notes) still applies to the room.

Phone side:
- `{"streamAck":{"streamID":N,"lastPTSMilliseconds":<u64>,"queueDepth":<Int>}}`, no reply (`MSG:841`).
  Handled off the main actor (`MC/CompanionLink.swift:473-474`). iOS acks **every media frame on receipt**
  with `queueDepth: 0` (`SV:982-983`, `AM:4725-4732`).
  **Rule for Android**: once any ack is sent, keep acking each frame promptly (echo the frame's
  `ptsMilliseconds`); if acks stop, the Mac stops sending P-frames after 500 ms of lead and the view freezes.
  Reporting a real decoder queue depth > 4 also pauses the Mac.
- `{"requestKeyframe":{"streamID":N}}`, no reply (`MSG:830`). Also forces a config resend with the same
  generation (`ST:319-324`). iOS sends it when a frame arrives with no decoder, the format description fails
  to build, or a decode fails (`IOS/Screens/CompanionVideoView.swift:68-70, 104-113, 128-146`, `SV:966`).
- `updateStreamParams{streamID, params}` (`MSG:836`): Mac applies only `maxFrameRate` (≤ 0 → 30), clamped by
  the resolution throttle; `maxBitrate` ignored; raising above the start rate has no effect because the
  driving timer stays at the start rate (`HB:557-565`, `ST:488-500`). **iOS never sends it**; there are no
  adaptation rules to copy.

## 6. Input

### `sendKey` (Mac ≥ 10)
`{"sendKey":{"sessionGuid":"…","event":{"key":<Key>,"modifiers":{"control":b,"shift":b,"leftOption":b,"rightOption":b}}}}`
(`MSG:276-325`, `MSG:881`). Fire-and-forget. `<Key>` is `{"text":{"_0":"…"}}` or `{"special":{"_0":"<raw>"}}`.
All four modifier keys required (synthesized decode).

`CompanionSpecialKey` raw values (`MSG:298-306`): `escape`, `tab`, `backspace`, `forwardDelete`, `up`, `down`,
`left`, `right`, `home`, `end`, `pageUp`, `pageDown`, `insert`, `f1`…`f12`. **No `enter`/`return` and no
`ctrl-c` key**: Return is `text "\n"` (Mac sends CR; `\n`, `\r`, `\r\n` each become one Return,
`MC/CompanionKeyInjection.swift:149-157`); ^C is `text "c"` with `control:true` (`IOS/Views/SessionKeyboardAccessory.swift:105-108`).
An unknown special key fails the whole message's decode on the Mac and it is dropped (`MSG:292-297`).

Mac handling (terminal, `HB:692-706`, `MC/CompanionKeyInjection.swift:128-192`): one synthesized key-down
per character through the session's key mapper (honors CSI-u modes, per-side Option, "Delete sends ^H");
characters with no single key on the Mac's layout are written literally. Special keys carry real kVK codes,
arrows/F-keys add `.function` (63-110). `leftOption`/`rightOption` map to side-specific modifier bits (32-47).

iOS key bar (`IOS/Views/SessionKeyboardAccessory.swift:235-275`) and what each sends:
| Key | Event |
|---|---|
| esc | `special escape` |
| ^C | `text "c"`, `control:true` (ignores armed state, consumes it) |
| tab | `special tab` |
| ← ↓ ↑ → | `special left/down/up/right` |
| compose | opens the local composer (no message) |
| tray: ctrl, ⌥ Left, ⌥ Right | sticky modifiers: tap = one-shot, double tap = lock; left/right Option mutually exclusive (136-160) |
| tray: home, end, del, PgUp, PgDn, F1–F12 | `special home/end/forwardDelete/pageUp/pageDown/f1…f12` |

Modifiers sent = armed/locked state; `shift` is always false (54-62). System keyboard: `insertText` → `text`
(a multi-character insert with a one-shot modifier is split: first char with it, rest without, 77-88);
delete-left → `special backspace` (96-99, `IOS/Views/CompanionKeyboardInputView.swift:295-317`). Keyboard:
ASCII-capable, no autocorrect/autocapitalize/spellcheck/smart quotes/dashes (68-77). iOS serializes key sends
and gives each 2 s before cancelling (`AM:5170-5197`). The keyboard is raised by a tap on the terminal only
when the stream is live and Mac ≥ 10 (`SV:2848-2875`).

User-facing: user taps the terminal, keyboard plus bar appears; types `ls`, taps Return; phone sends
`text "l"`, `text "s"`, `text "\n"` (one event per keystroke); Mac shell runs `ls`. Tapping ^C sends
`{"key":{"text":{"_0":"c"}},"modifiers":{"control":true,"shift":false,"leftOption":false,"rightOption":false}}`.

### Composer
Two different composers:
- Terminal composer (key bar compose button, or dictation): local multi-line draft; Send →
  `sendKey` with the **whole draft as one `text` event, no modifiers, no trailing newline**
  (`IOS/Views/CompanionKeyboardInputView.swift:17-25, 43-45`, `IOS/Views/SessionKeyboardAccessory.swift:110-115`,
  `SV:1845-1846`). The Mac types it keystroke by keystroke (newlines become Returns); it is **not** a
  bracketed paste.
- `AgentComposerBar` ("Message the agent") publishes to an AI chat, not to the terminal
  (`IOS/Views/AgentComposerBar.swift:1-10`, `SV:79`). Chat feature, out of Sessions scope.

### `pasteText`
`{"pasteText":{"sessionGuid":"…","text":"…"}}`, no reply (`MSG:872`). Only used by the edit menu's Paste of
the phone clipboard (`SV:2945`, `AM:5120-5123`). Mac calls `PTYSession paste:text flags:[]` (`HB:576-577`),
iTerm2's normal paste path (bracketed paste when the app enabled it). No size limit in the protocol;
large text is chunked by Noise. Paste-path behaviour (confirmation prompts, chunked pacing) lives in
`PTYSession`, which is not in the sparse clone: unverified.

### `resizeSession` (Mac ≥ 6)
`{"resizeSession":{"sessionGuid":"…","columns":C,"rows":R}}`, no reply (`MSG:887`).
- iOS: manual toolbar button only, hidden for browser streams, disabled when `canResize == false`, the stream
  ended, or the viewport is unknown (`SV:926-938`). Never sent automatically, **never restored** on leaving.
- Computation (`AM:5129-5161`): cell px from `cellGeometry` (fallback image/columns, image/rows);
  `columns = viewport landscape ? 120 : 60`; `cellPtW = viewportW/columns`;
  `cellPtH = cellPtW * cellHeightPx/cellWidthPx`; `rows = floor(viewportH/cellPtH)`. Viewport = the live
  canvas area in points (`SV:899-908`).
- Mac (`HB:648-667`): ignored for browser sessions or when the window cannot be resized; clamps both to
  [1, 4096]; resizes the window via `reallySetCellSize`. The result arrives as a new config (new generation).

### `reportScrollWheel`
`{"reportScrollWheel":{"streamID":N,"up":b,"lines":n}}` (`MSG:850`). Only when the config has
`altScreen && scrollWheelReporting` (`SV:1387-1389`). iOS accumulates a one-finger drag, but only past the
scroll edge, one notch per `pointsPerLine` (fallback 24 pt); `up` = finger moving down = older content
(`SV:2398-2449`, `AM:4664-4667`). Mac re-checks reporting and caps the count (`HB:1191-1197`).

## 7. Zoom and pan

No protocol involvement; scaling is local.
- Terminal canvas: `UIScrollView`, min zoom 1, max `max(1, 4*imageW/(viewW*displayScale))` (up to 4×
  native pixels) (`SV:1908-1909`, `SV:2098`). Document height = `totalLines * pointsPerLine` with the video
  band at the bottom and history tiles above (`SV:2087-2095`); alt screen shows only `rows` lines (2054).
- Layout is frozen while zoomed (`zoomScale > 1.001` skips `applyLayout`), so a new config (resize, scroll)
  is applied after zooming back out (`SV:2040`, `SV:2122`, `SV:2174`).
- Touch mapping always goes through the aspect-fit transform in the video view's own coordinates, so zoom
  does not change wire coordinates (`SH/CompanionTouchMapper.swift:82-100`).
- Interplay with resize: none in protocol. Resizing changes pixel size and grid, bumps the generation, and
  invalidates tiles and selection (`AM:4386-4407`).

## 8. Next phase (brief)

### History tiles
- P→M `{"fetchHistoryTile":{"streamID":N,"firstAbsLine":L,"lineCount":n,"generationId":G}}` with requestID
  (`MSG:757`). Reply `{"historyTile":{"_0":{streamID, generationId, firstAbsLine, lineCount,
  windowFirstAbsLine, windowLineCount, pngData}}}` (`MSG:168-180`).
- Mac (`HB:2115-2168`): unknown stream → `error badRequest "No such stream."`; clamps the request to the window
  `[overflow, overflow+numberOfLines)` and to 200 lines (`HB:2050`); fully evicted → 0-line tile with empty
  PNG; renders PNG with selection highlight, no cursor, no margins. `generationId` is echoed, not checked.
- iOS: 50 lines per tile at `firstAbsLine + 50*i` (`SV:1370`, `SV:2514`); ≤ 6 in flight, ≤ 24 queued, 20 s
  timeout (`AM:647-658`); drops replies from an older epoch or generation; `tile.firstAbsLine` ≠ requested → failed
  (`AM:4924-4997`). Cache keyed by `firstAbsLine`, cleared on a new generation or stream (`AM:4386-4400`).
- `streamExtent{streamID, firstAbsLine, totalLines}` (`MSG:1120`): sent when `firstAbsLine` advances (trim) or
  `totalLines` drops (clear), checked every tick (`ST:336-342`). iOS: on shrink, snap `liveTop` and drop all
  tiles; on trim, drop tiles below the new origin (`AM:4410-4427`). Growth is conveyed by `liveTop` only.
- Alt screen: no history (`SV:2054`).

### Selection and copy (needs `cellGeometry`)
- `selectionGesture{streamID, phase:"begin"|"move"|"end", mode:"character"|"word"|"line"|"smart", point:{absLine, column}}`
  (`MSG:242-269, 856`), no reply; `mode` is only read on `begin`. Columns are **visual**; Mac converts for
  bidi (`HB:795-856`). iOS begins on a 0.3 s long press, drops duplicate `move` points, and serializes sends
  (`SV:1931-1933`, `AM:4772-4802`).
- `clearSelection{streamID}`, `selectAllInStream{streamID}`, no reply; each causes a `selectionRange`.
- M→P `selectionRange{streamID, range?:{start:{absLine,column}, end:{absLine,column}}}` (coalescing lane);
  `end` is inclusive; `range` omitted = no selection (`MSG:1129`, `HB:1109-1179`). Re-pushed after a generation
  bump (`HB:1313-1336`). Highlight is drawn into the video and tiles, not by the phone.
- `copySelection{sessionGuid}` → `selectionText{text}` (empty if none) (`MSG:864, 1124`, `HB:1181-1184`).
  iOS edit menu: Copy, Select All, Paste (`SV:2941-2945`).

## 9. Browser sessions (rev 15)

### Discovery and stream
- In the tree as an ordinary session with `isBrowser: true` (section 2). Started with the same
  `startSessionStream`; the Mac picks `CompanionBrowserFrameSource` (`HB:1237-1240`). A pre-15 phone still gets
  the video (view-only) and `isBrowser` in the config, but no browser events.
- Config differences (`MC/CompanionBrowserFrameSource.swift:69-83`): `columns 0`, `rows 0`, no `cellGeometry`,
  `firstAbsLine 0`, `totalLines 0`, `canResize false`, `liveTop 0`, `isBrowser true`. No history tiles,
  selection, or resize (`HB:658-660`). Guard every divide by `rows`/`columns`.
- Frames: WKWebView snapshots ≤ 10/s, `scale = snapshotPx / webViewPt` (`MC/CompanionBrowserFrameSource.swift:27, 207-241`).
  Focus checks 4/s (10/s while editing) via an injected script in an isolated world (30-31, 175-204).
- iOS browser layout: video fit to the area below the browser bar, max zoom `max(1, 4/(fitScale*displayScale))`
  (`SV:2121-2141`).

### Messages
| Direction | Case | Body | Notes |
|---|---|---|---|
| P→M | `browserTap` | `{streamID, x, y}` Double | encoded-image px, origin top-left; Mac: `point = image/latestScale` → left mouse down+up, click count 1 (`MSG:918`, `HB:724-732`, `MC/CompanionBrowserFrameSource.swift:105-110`, `MC/CompanionBrowserInput.swift:228-249`) |
| P→M | `browserScroll` | `{streamID, x, y, deltaX, deltaY}` Double | (x,y) = gesture location in image px; deltas in image px, positive = content moves right/down (follows the finger); Mac divides by scale, rounds, sends a pixel scroll-wheel event at that point (`MSG:926`, `HB:734-743`, `MC/CompanionBrowserInput.swift:255-277`) |
| P→M | `browserGoBack` / `browserGoForward` / `browserReload` / `browserStopLoading` | `{streamID}` | no reply; state returns via `browserNavigationChanged` (`MSG:930-939`, `HB:594-601`) |
| P→M | `browserLoad` | `{streamID, text}` | Mac treats it like its URL bar: URL-looking text opens, anything else is searched (`MSG:945`, `HB:602-603`); iOS trims and skips empty (`AM:4713-4717`) |
| P→M | `sendKey` | `{sessionGuid, event}` | same message as terminal; delivered to the web view (below) |
| M→P | `browserEditingChanged` | `{streamID, editing}` | a text field has focus; first sent on the first tick, then on change (`MSG:1161`, `HB:748-760`) |
| M→P | `browserNavigationChanged` | `{streamID, state:{url, title, canGoBack, canGoForward, isLoading}}` | first sent on the first tick (once the web view exists), then on change; all fields lenient (`MSG:591-617`, `HB:776-788`, `MC/CompanionBrowserFrameSource.swift:130-141`) |

All P→M browser messages are fire-and-forget; the Mac silently ignores a stream that is not a browser stream.

### Keyboard to a browser
`sendKey` on a browser session goes to `CompanionBrowserInput.send` (`HB:679-690`):
- Special keys → key down/up with Mac key codes (escape, tab, backspace = delete-left, arrows with
  `.numericPad|.function`, etc.) (`MC/CompanionBrowserInput.swift:85-114`).
- Text: `\n`/`\r` → Return key, `\t` → Tab; characters on a US layout → key events (Shift added as needed;
  Control + letter → control character); anything else → `insertText` (36-65, 119-190).
- Options: left/right collapse into one `.option` flag (67-79).
- After each key or tap the Mac re-checks focus promptly (`HB:686-689`, `HB:731`).

### iOS behaviour to copy
- Browser bar: back, forward (disabled from `canGoBack`/`canGoForward`), address field (shows `url` unless
  focused; select-all on focus; restores on blur), Stop while `isLoading` else Reload (`SV:1117-1211`).
- Tap → `browserTap`; if the page is already editing, raise the keyboard (`SV:2192-2205`). Keyboard follows
  `browserEditingChanged`, plus a toolbar keyboard toggle because editors are not always detectable
  (`SV:909-922`, `SV:2213-2238`).
- One-finger drag → `browserScroll`, accumulated in image px (`viewPt * imagePxPerViewPt / zoomScale`),
  flushed by a 20-30 Hz display link, integer deltas only; fling continues with decay `0.998^ms` until
  < 30 px/s; ignored while pinching; when zoomed, the drag pans locally and only scrolls the page past the
  scroll edge (`SV:2247-2378`).

User-facing: user opens a globe row, sees the page with a URL bar; taps a search box, the keyboard rises
(`browserEditingChanged editing:true`), types "kittens", taps Go (`text "\n"` → Return); the page navigates
and the URL bar updates from `browserNavigationChanged`.

## 10. Gaps and risks for Android

1. **HEVC only.** No H.264 fallback exists on the Mac. A device without an HEVC decoder cannot stream; detect
   with `MediaCodecList` and show a clear message.
2. **Decoder size limits.** Frame size is the Mac window at Retina scale (a full-screen 5K window is about
   5120×2880); many phone HEVC decoders top out at 4096 wide. The protocol has no "send smaller" request;
   the only lever is `resizeSession` (fewer cells → smaller window). Check `VideoCapabilities.isSizeSupported`
   on each config. iOS has the same failure mode (`IOS/Screens/CompanionVideoView.swift:95-98`).
3. **Latency of the last frame.** The stream is change-driven: when the screen goes idle the final frame
   must display immediately. iOS dropped `AVSampleBufferDisplayLayer` for this reason
   (`IOS/Screens/CompanionVideoView.swift:10-16`). MediaCodec decoders may hold a frame; use
   `KEY_LOW_LATENCY` (API 30+), render output buffers immediately, and verify on hardware. The 0.3 s insurance
   keyframe limits how long a stale frame can show.
4. **Media path missing.** Android drops media frames today (section 4). The new path must keep order with
   control frames (config before its keyframe) and must not use the lossy event flow.
5. **Ack discipline.** Acks must continue for every frame after the first, or the Mac stops sending P-frames
   (section 5).
6. **Parameter-set format.** `codecExtradata` is a custom container, not hvcC; the shared comment says
   otherwise (`MSG:335` vs `SH/CompanionHEVCFraming.swift:9-14`). Convert to Annex-B `csd-0`.
7. **Stream ID reuse.** IDs restart at 1 on every connection; scope stream state to the connection.
8. **Generation and lane ordering.** A config can overtake older queued media; frames with an older
   `generationId` may follow a new config. `selectionRange`/`streamExtent` can trail media.
9. **Relay limits.** 500 frames/s/room and 512 MB/day/room (pairing notes) apply to all traffic; the Mac's
   400 MiB budget is per stream and resets with every new stream, so frequent restarts (background/foreground)
   can reach the relay quota first (close 1008 "daily quota exceeded").
10. **Ambiguities.** `contentSession` requires a textview with non-zero width (`HB:2020`) even for a browser
    session whose terminal view is hidden; whether that ever rejects a browser start is untested here.
    `pasteText` behaviour depends on `PTYSession paste:flags:`, not in the sparse clone. Whether HEVC IDR
    access units carry in-band parameter sets is not stated.
11. **iOS-only pieces with no direct analogue**: VideoToolbox decode → MediaCodec; `UIScrollView` pinch anchored at
    the centroid → Compose `transformable` or a custom zoom layout; hidden `UITextView` first responder with an
    input accessory and dictation → an `InputConnection`-backed view with a toolbar above the IME
    (`IOS/Views/CompanionKeyboardInputView.swift:5-25`); `UIEditMenuInteraction` → a floating toolbar or
    `ActionMode`; `CADisplayLink` → `Choreographer`. Android IMEs batch and compose text (`setComposingText`), so
    sending per keystroke needs care to avoid duplicate or missing characters.
