# Handover: Pairing Milestone build

Date: 2026-11-15. Branch: `pairing-milestone`, made from the approved `design-android-companion`.

## What this is

This is a Kotlin Android port of the iOS iTerm2 Buddy companion app, for the owner's Pixel (Android 15+). It pairs with iTerm2 3.7.7 on the owner's Mac through a self-hosted relay reached over Tailscale.

- **Main feature:** talking to the AI agents running in iTerm2.
- **This milestone:** pairing only, which covers the QR scan, relay admission, Noise XK, the 6-digit code, registration and reconnect.

## Read first

- Spec (approved): `docs/superpowers/specs/2026-11-15-android-companion-design.md`
- Protocol notes: `docs/superpowers/specs/2026-11-15-protocol-notes.md`
- Plan (approved after four adversarial rounds by Codex Astra and Mythos): `docs/superpowers/plans/2026-11-15-pairing-milestone.md`

## Owner's working rules for this build

- **Execution:** subagent-driven. Opus subagents (Opus 5.5 or better, with the model passed explicitly) write code, and only they do. One task at a time; never two implementers on the same branch at once.
- **Per-task review:** Codex Sol (`gpt-6.1-sol`) through the Codex plugin's companion script (`codex-companion.mjs task --model gpt-6.1-sol`, read-only), not Claude reviewer subagents. The reviewer gets the task section of the plan, the implementer's report, and the diff of the task's commits.
- **Large or critical reviews** (the whole milestone branch, security-sensitive pieces): adversarial review by both Mythos (`claude -p --model claude-mythos-5-1`, prompt on stdin, edit tools disallowed) and Codex Astra (`gpt-6-astra`, via the Codex plugin's `adversarial-review`).
- **Fixing findings:** triage every finding, fix the valid ones, and re-review until clean.
- **Push and merge:** pushing to `pairing-milestone` is allowed; no force-pushes. The lead may fast-forward `main` (`--ff-only`, linear history) once reviews are clean. History rewrites, deletions and releases still need the owner's OK.
- **Commits:** author `bugobliterator <siddharthbharatpurohit@gmail.com>`; check `git config user.email`. Keep messages concise. Never put a Claude session link in commits, PRs or published text.
- **Names:** descriptive, never numbered (phases, tasks, tests, fixtures, branches).
- **After each phase,** ask the owner before starting the next. The phases are Relay Setup, Wire Fixtures, Android Skeleton and Pairing.
- **Tests:** sandboxed (temp dirs, ephemeral ports), run in parallel, and fast. Report what was run and what was skipped.
- **Tooling:** use pnpm, never npm.
- **Waits:** report every wait on the owner to the `master-session` Claude session and to the `tracker` CLI, which runs on the owner's Mac. A cloud session without those should list its waits in its report so the Mac session can relay them.

## Task status

| Phase | Task | State |
|---|---|---|
| Relay Setup | Relay Service on the Mac | **Running on the Mac** (local by design). The implementer stops before the iTerm2 settings steps, which need the owner to quit iTerm2. It pushes `ops/mac/*` when done. Not a cloud task. |
| Wire Fixtures | Upstream Harness | **Done** (`939cd02`). Linux build verified in the cloud (see below). |
| Wire Fixtures | Crypto Vectors | **Done** (`9225e0f`, `1604a6f`). Opus review clean. |
| Wire Fixtures | Session Message Samples | **Done** (`7b1891d`, `7385893`). Opus review clean after fixes. |
| Android Skeleton | Gradle Project | **Done** (`22887e8`, `4831a68`, `449e740`, `58cb693`). Opus review clean after fixes. AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1, SDK 37, min 34. |
| Pairing | Pairing Code and Room Name; SAS and Join Signature; Noise Channel; Wire Envelope and Session Messages; Relay Connection; Pairing Store; Companion Client | **Done.** Opus review per task; Mythos for Noise, Pairing Store and Companion Client. |
| Pairing | Pairing Screens | **Code done**; whole-milestone Mythos review clean (no Critical). Waiting on the owner's on-device check (`docs/reports/pairing-on-device.md`). |

## Sessions: code done, on-device checks pending

All Sessions plan tasks are on `main` (through `e3bd4b2`), each reviewed by Opus and fixed until approved. The app advertises protocol revision 15.

- Plan: `docs/superpowers/plans/2026-11-16-sessions.md`. Design: `docs/superpowers/specs/2026-11-16-sessions-design.md`. Protocol notes: `docs/superpowers/specs/2026-11-16-sessions-protocol-notes.md`.
- Owner on-device checks, in order (reports on `main`):
  1. **Live Session:** `docs/reports/live-session-on-device.md`.
  2. **History and Selection:** `docs/reports/history-and-selection-on-device.md`.
  3. **Browser Sessions:** `docs/reports/browser-sessions-on-device.md`.
- `/` and `*` in Claude Code: measured on the real-Mac CI (iTerm2 3.7.7, Claude Code 2.1.327), the phone sends them correctly, but iTerm2's key injection turns exactly four of the 95 printable characters, `/ * + =`, into the wrong CSI-u key under Claude Code's kitty flags 5 and modifyOtherKeys 2 (`/` arrives as `ESC[30:47;2u`), and Claude draws none of them. Pasted, `/` opens the slash menu and `*` is drawn; with the switch on, `a/b*c+d=e` typed in Claude drew all four. Settings → **Send / * + = as paste** (on by default, reset on unpair) sends each, typed with no modifier, as a one-character paste; turn it off for an app that needs them as keys (vim's `/`). Upstream report draft: `docs/reports/iterm2-companion-key-injection-bug.md`. The opt-in typed-key trace (tag `IB.Input`) is Settings › Diagnostics › **Include typed-key trace** (off by default, not saved; its lines go to the app's log only, and stay there until Clear log, which turning it off offers), and Settings › Diagnostics › **Share log** sends the app's rolling on-device log with Android's share sheet, so no adb is needed; `adb shell setprop log.tag.IB.Input VERBOSE` still turns the trace on too. See the "Input trace" section of the Live Session report.
- `PressGestureDeviceTest` (androidTest) needs the Pixel: `./gradlew connectedDebugAndroidTest --tests '*PressGestureDeviceTest*'`.
- Known, accepted: rotation resets scrollback to live; Select All keeps the menu open; no selection handles or loupe; the Mac can leave a live selection if the stream drops mid-drag (iOS too); a multi-line paste in a browser page presses Return per line; no `macWatchdog` (revision 15 behaves as 13 with a blocked Mac, plus the banner).

## Next phase: Agent Chat (plan approved, starts after the Live Session device check)

- Plan: `docs/superpowers/plans/2026-11-18-agent-chat.md` (`e7104d7`), revised after a Mythos review (23 findings, all addressed). 16 tasks.
- Owner decisions: watch all chats for Working/Waiting/Done; 8 MiB chat history cap ("too large" above it, not resubscribed for the process); port `markdownDescription` now and show the full command argument (Android addition); `fetchSessionScreenInfo` existence check for "Go to session"; switching tabs away from a live session closes the stream; Chats is the default tab when AI is available; two concurrent subscribes; AI off moves from the Chats root to Sessions, as iOS.

## Closed: Linux build of the harness

Codex Sol's review of Upstream Harness has one Important finding: the Linux build ran in local Docker (`swift:6.2-noble` on the Mac), not in a cloud Linux session. In the cloud session:

```
cd fixtures/harness && ./sync-upstream.sh && swift build && swift run wire-fixtures /tmp/out
```

Expected: "Synced 39 files from v3.7.7 (e0025340…)", "Build complete!" with zero `warning:` lines, and the stub message. Record the toolchain version and output. That closes the task.

Result (cloud session, 2026-11-15): Swift 6.2.4 (`swift:6.2-noble`) on Ubuntu 24.04, kernel 6.18. `sync-upstream.sh` synced 39 files from v3.7.7 (e0025340…), a clean `swift build` completed with 0 warnings, and `wire-fixtures` printed its stub message. The cloud container has no Swift toolchain (download.swift.org is blocked), so it ran in Docker after starting `dockerd`.

Notes from that task:
- The plan's CompanionProtocol file list was missing three upstream files, now included and commented in `upstream-files.txt`: `DataHex`, `CompanionSyncItem` and `Wire/CompanionError`.
- Linux needs two small shims, under `Sources/WireFixtures/Shims/` and only on non-Darwin: `String(localized:defaultValue:comment:)` and `NSMutableString()`.
- `Sources/*/Upstream/` is generated and not committed; run `sync-upstream.sh` first.

## Review setup from 2026-11-15 (owner's instruction)

- Work lands on `main` (fast-forward) once reviewed.
- Per-task review is by an Opus subagent (read-only), in place of Codex Sol. Mythos is reserved for critical or large reviews.
- Codex is not used (owner, 2026-11-16).
- After a clean review, the lead fast-forwards `main`. If `main` moved (the owner's Mac session pushes reports), rebase the session branch's own commits onto `origin/main`, fast-forward `main`, and `--force-with-lease` only the session branch.
- Ask the owner questions with options. Name any extra network domains needed as a copyable list.
- Terminal content never goes in logs or `toString` (lengths only), except the opt-in `IB.Input` trace.
- Every log call goes through `applog.AppLog` (logcat with the same tags, plus the rolling file in `filesDir/logs`, about 2 MB, that Share log sends with a header of versions, revisions, pairing yes/no and the switches). What is logged persists on the device, so the rule above applies to it; the file keeps stack traces without exception messages. Unpair keeps the log.

## Cloud environment notes

- The network policy must allow `dl.google.com` (Google Maven and SDK). The full domain list used so far is in the session report; `scripts/cloud-setup.sh` installs the SDK and JDK 17 into `$HOME`.
- Swift runs in Docker (`swift:6.2-noble`); start `dockerd` first if it is not running.
- Maven Central (`repo.maven.apache.org`) intermittently answers 429 or a spurious 404 through the proxy. Gradle caches those misses, so a failed resolve may need `--refresh-dependencies` and a retry.

## Follow-ups found in review

- Enable R8 for release builds (`isMinifyEnabled = true` with `proguard-android-optimize.txt`). BouncyCastle (`bcprov-jdk18on`, 7 MB) ships whole without it; a trial R8 build kept about 30 BouncyCastle classes, gave a 1.8 MB release APK and no warnings.
- Check the relay challenge nonce is 32 bytes where it is base64-decoded (Relay Connection).
- Session layer (Companion Client): a frame that fails to decode (`WireDecodeException`) or is empty is dropped and logged, and the session stays up, as upstream does (`CompanionSession.swift:120-127`). Test that a bad frame does not close the session.
- Session layer: every frame `NoiseChannel.encryptMessage` returns must be sent in order; if a WebSocket send fails or returns false, close the channel (the nonce has already advanced).
- Companion Client: `requiresRescan(close)` (relay close 1000 "pairing cycle cap") means the QR is dead; show Scan again, do not back off. Pass `nextDelayMillis` only the relay's close frame from the connection that just ended.
- Companion Client: `RegisterResult.Refused` mixes fatal 4xx and retryable 5xx (502 when the relay is down behind `tailscale serve`); branch on status. `Unreachable(TLS)` is a configuration fault (for example an expired tailnet certificate), not "Waiting for your Mac".
- Keep one project debug keystore, restored by `scripts/cloud-setup.sh`, so device installs of a new cloud build do not need an uninstall that deletes the pairing (Mythos milestone review M4). Each cloud session currently generates its own `~/.android/debug.keystore`.
- CI (the "Android build" workflow) restores the shared debug key from the `DEBUG_KEYSTORE_BASE64` secret when it is set.

## Browser sessions: scrolling and fill (2026-11-20)

- **Owner report:** on a browser session, drags did nothing while taps worked, and the page sat at
  fit with an empty band below it.
- **Fill:** a browser page now rests zoomed to fill the screen (`browserFillZoom`, the canvas's base
  zoom). Pinch out shows the whole page.
- **Cause of the scrolling bug:** the phone's touch path is fine. `BrowserCanvasDeviceTest` passes
  on the emulator (`android-device-tests.yml`). iTerm2 accepts `browserScroll` and scrolls with
  it, but places the wheel event off by the browser window's origin on screen (real-Mac runs
  19-20, `docs/reports/iterm2-companion-browser-scroll-offset.md`, an upstream draft). With the
  window away from the screen's top-left corner, the wheel misses what is under the finger, or
  the page.
- **Owner decision: "Drags send keys, via a switch", on by default.** Settings' "Scroll web pages
  with keys" (`ScrollKeysSetting`). While it is on and the page is not editing text,
  `BrowserDriver` turns vertical drag and fling travel into arrows (40 CSS px a line) and
  Page Up/Down (the view less 40 px), at most one every 120 ms, with at most two pages of backlog.
  Horizontal travel still goes as `browserScroll`.
- **Calibration:** the real-Mac probe's `calibrate:` and `key drag` lines measure the step sizes
  and the gap. Set `DEFAULT_KEY_*` in `BrowserDriver.kt` from them.
- **Open:** the on-device checks in `docs/reports/browser-sessions-on-device.md` (Fill the
  screen, Scroll with keys).

## Fit to phone as a toggle (2026-11-20)

- **Owner decision:** ⤢ is an on/off toggle (`FitToPhoneSetting`, a `StoredSwitch`, key
  `fit-to-phone` in `input-settings`), off by default, one global value, back to off on unpair (as
  the other per-owner switches, `wireHome`'s unpair list). It replaces the one-shot Fit; the
  too-large overlay's Fit turns it on. Off never undoes a resize.
- **`AutoFitter`** (`core/stream/AutoFit.kt`, built in `CompanionApp`): while on, fits the live
  terminal session (`fitGrid`) when its view opens or its stream goes live (reopen, reconnect,
  another session), when the toggle turns on, and after a rotation or other viewport change once
  the viewport holds still for 400 ms (`FIT_SETTLE_DELAY`). The keyboard never resizes: the fit
  viewport is measured with the keyboard hidden. It acts once per target per stream (it never
  fights a later Mac-side resize), skips a grid already at the target or where the last fit of
  that session to the same target ended, and never touches browser sessions or `canResize` false.
- **Owner report, the fit filled only ~75 % of a tall phone:** iTerm2 caps row growth to its
  screens (`PseudoTerminal.m` `safelySetSessionSize:rows:columns:`, rows only), and a split pane
  shares the window's rows. After a fit the `AutoFitter` reads the next config; when the rows came
  back more than 5 % short it sends one corrective resize with fewer columns
  (`correctedFitGrid`: `floor(rows × viewportW/viewportH × cellH/cellW)`, at least 40) and keeps
  the rows. One step per fit, never a loop. When the grid does not change within 4 s, or
  `canResize` is false, the view shows a brief message. Requested and resulting grids go to the
  app log (`fit to phone for <guid>: …`).
- **Real-Mac report:** `RealIterm2FitTest` (`fit:` lines) runs the fitter on the runner's small
  screen for 1080×2200, landscape, and a split pane.
- **Open:** the on-device check (Fit to phone in `docs/reports/live-session-on-device.md`), and
  the first real-Mac `fit:` lines.

## Plan deviations already ruled on

- Plan tasks are named, not numbered. Hand each implementer only "Global Constraints", "Review Focus" and its own `### Task:` section, never the whole plan.
- The app advertises protocol revision 11 for this milestone; `fixtures/messages/revision.json` is reference only.
- Spec harness item (c), where Swift decodes Kotlin output, is met by Kotlin encodings matching Swift's as JSON trees.

## Needs the owner (on the Mac, not the cloud)

- After Relay Service reports: quit iTerm2, then apply `defaults write com.googlecode.iterm2 CompanionRelayOrigin -string https://relay.example.ts.net` and `defaults write com.googlecode.iterm2 CompanionResolverURL -string ""`. Then check that iTerm2 parks on the relay.
- The Sessions on-device checks above, then tell the cloud session so Agent Chat can start.
