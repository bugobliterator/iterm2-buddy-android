# Real iTerm2 CI

`.github/workflows/real-iterm2.yml` runs the app's own `CompanionClient`, as the phone, against a
real iTerm2 3.7.7 on a GitHub-hosted macOS runner (`macos-15`). No secrets and no model key are
involved: every key is minted for the run and thrown away with the runner, and Agent Chat runs on
a local fake model.

## Stage 1: pairing and the session tree

`RealIterm2PairingTest` (in `app/src/test/.../realiterm2/`, run by the `realIterm2Test` Gradle
task):

- reaches `Connected` from a pairing the Mac has already confirmed (no QR code, SAS or Touch ID);
- gets `pong` for `ping`;
- gets a session tree with at least one session;
- gets a PNG of a terminal session's lines from `fetchSessionContent`.

## Stage 2: typing into a session and its live stream

`RealIterm2SessionInputTest` types into the first terminal session through the app's
`InputSender.typeThenReturn` (the composer's Paste ⏎: `sendKey` text events, then Return). The
command is `seq -f buddy-ci-<random>-%g 100`, a marker unique to the run that prints more lines
than a default window has rows. The test cannot read the PNG, so within the request timeout:

- the session's line count (`fetchSessionScreenInfo`, scrollback plus screen) must grow, which
  shows that the shell ran the command;
- the PNG of the session's last 10 lines (`fetchSessionContent`) must change (SHA-256 compared);
- the input sender must report no discarded, failed or timed-out input.

`RealIterm2LiveStreamTest` opens the same session in the app's `StreamController`, wired to the
client as the app wires it. Its video output records what it gets instead of decoding, because
the JVM has no HEVC decoder. The test checks that:

- `streamStarted` comes back for the start request with codec `hevc`, and the controller adopts it;
- a `streamConfig` for that stream arrives, its `codecExtradata` parses, it holds a VPS, an SPS
  and a PPS, and the controller goes `Live`;
- a keyframe media frame of that stream reaches the video output, its access unit parses
  (AVCC to Annex-B), and it holds an IDR NAL (type 19 or 20). Every media frame must parse;
- after `close()` (`stopSessionStream`), iTerm2 answers `streamEnded(stoppedByClient)`, and the
  client is still connected and answers `ping`.

This is the first check that VideoToolbox can encode HEVC on the hosted (virtual) Mac.

## The Claude Code status probe

The owner's plan: the Sessions rows show Working, Waiting, Done and Error chips parsed from the
session subtitle, once the owner's profile has `\(status)` in its Subtitle, and the phone
re-fetches the session tree about every 4 s while the Sessions list is visible.
`RealIterm2ClaudeStatusTest` proves that path on the real Mac.

What carries the state, in iTerm2 3.7.7:

- Claude Code's hook is `it2 handle-agent-hook` (`cc-status` is a shim for it). It reads the
  event's JSON on stdin and runs `it2 set-status` for the session it runs in, found by
  `TERM_SESSION_ID` (AgentHookAddress.swift:44-102). Its decision table
  (AgentHookEvent.swift:229-455): UserPromptSubmit, PreToolUse and PostToolUse say `working`;
  PermissionRequest and Notification `waiting`; Stop `done`; StopFailure `error`; SessionStart
  and SessionEnd `idle`.
- `status` is the session's tab status text (PTYSession.m:27470-27480, iTermVariables.m:103),
  and the session's subtitle is its profile's Subtitle evaluated as an interpolated string in
  the session scope (PTYSession.m:6644, 6783-6807, 6880-6881). An escape sequence cannot set an
  interpolated subtitle (PTYSession.m:17149-17156), so the format comes from a profile.
- A subtitle change does not push a session tree: iTerm2 pushes one only when a session ends, the
  session count changes or a presentation name changes (CompanionHostBridge.swift:191-201). The
  phone must re-fetch.

`seed-claude-status.sh` sets it up before launch:

- the Dynamic Profile `~/Library/Application Support/iTerm2/DynamicProfiles/buddy-ci-status.json`,
  `{"Profiles":[{"Guid":"buddy-ci-status","Name":"buddy-ci-status","Subtitle":"\\(status)"}]}`;
- `Default Bookmark Guid` = `buddy-ci-status`, so the window iTerm2 opens at launch uses it.
  ITAddressBookMgr makes a Dynamic Profile the default when it has the GUID the defaults name
  (ITAddressBookMgr.m:308, 369-376). This needs no AppleScript (Automation permission) and no
  API call to open a window. Every test's first terminal session is therefore on this profile;
  its subtitle stays empty until a status is set;
- what the bundled it2 needs to reach iTerm2 from inside the session. it2 talks to the API
  server's unix socket, `~/Library/Application Support/iTerm2/private/socket`
  (SocketConnection.swift:8-42, 131-150), which exists only with the Python API on, so
  `EnableAPIServer` is on (read at launch, iTermApplicationDelegate.m:1806-1808; no "Enable
  Python API?" prompt when it is already on, iTermAPIHelper.m:681-688). Without the socket
  handle-agent-hook does nothing and says nothing (AgentHookAddress.swift:85-101). it2 takes its
  cookie from `ITERM2_COOKIE` and otherwise asks iTerm2 for one over AppleScript
  (CookieAuth.swift:7-15), which needs the Automation permission; the advanced setting
  `SetCookie` puts `ITERM2_COOKIE` in new sessions (PTYSession.m:3501-3504). That cookie is single
  use (iTermWebSocketCookieJar.m:35-53) and it2 asks for no auth UI (WebSocketClient.swift:27), so
  the later connections are let in by "Allow all apps to connect": the root-owned
  `disable-automation-auth` file in Application Support, hex(its path), a space and a fixed UUID
  (iTermAPIHelper.m:70, 410-462, 566-576, 1634-1654), written with sudo as iTerm2 would write it
  behind an administrator prompt;
- a check that `/Applications/iTerm.app/Contents/Resources/utilities/it2` exists and has
  `handle-agent-hook` and `set-status`. iTerm2 copies it2 into `Contents/Resources/utilities`
  (project.pbxproj:5036-5042, the CopyFiles phase with `dstPath = utilities` and
  `dstSubfolderSpec = 7`, from `it2cli/bin/it2`, :8413). Claude Code's installer only symlinks
  it as `<dot dir>/it2`, usually `~/.config/iterm2/it2` (ClaudeCodeOnboarding.swift:1966-1983,
  2042-2077); the Claude watcher is off here, so the test calls the bundle's copy.

The test runs after stages 1 and 2 and before Agent Chat, which can leave the main thread
blocked. It types, through the app's `InputSender` as the input test does, one line per hook
event into the first terminal session: `printf '%s' '<event JSON>' | "<it2>" handle-agent-hook`,
with it2's stderr appended to `claude-status-it2-stderr.txt` in the diagnostics. The hook runs in
the session, so it finds the session and authenticates exactly as Claude Code's would. After
each line it polls `fetchSessionTree` until the session's subtitle, trimmed and lowercased, reads:

| Event | Subtitle |
| --- | --- |
| `SessionStart` (a session boundary: a hook that cannot reach iTerm2 says why on stderr) | `idle` |
| `UserPromptSubmit` | `working` |
| `PermissionRequest`, `tool_name` `Bash` | `waiting` |
| `Stop` | `done` |
| `StopFailure` | `error` |

Typing the StopFailure line turns `done` to `idle` first (a key press is the owner seeing the
result, iTermTabStatusController.swift:256-272); the hook then says `error`. The test also logs
every unsolicited session tree that arrives while it runs and what it said about the session.
None is expected from the status itself; a job name in the session's title can still push one.

The fallback to `it2 set-status --status working` was not needed as the method: it uses the same
socket and cookie as the hook, so it would not avoid any of the setup. It is used only to
diagnose a failed step. Then the test runs `it2 session get-status --session <guid>` (is the
status stored?) and `it2 set-status --session <guid> --status probe` (does the subtitle follow
a status set without the hook?) in the session, and puts both answers and it2's stderr in the
failure message.

## The key-bytes probe

On the owner's phone, `/` and `*` typed into Claude Code show nothing, while letters, digits, `.`
and `,` do, and `echo /tmp/*` works in plain zsh. `RealIterm2KeyBytesTest` measures what reaches the
program. It is a probe: it reports and does not assert on the bytes; it fails only when the rig
broke (no connection, no `probe.env`, or the plain capture never started).

`install-claude-code.sh` writes `$WORK_DIR/key-bytes/probe.env` (node, the repository), installs
Claude Code (pinned in the script) globally with pnpm, letting its postinstall put the native binary
in place, and seeds `~/.claude.json` (onboarding done, the probe's project trusted, the dummy key
approved). The test types `bash key-probe.sh <probe.env> …` into the first terminal session for each
mode:

- `key-capture.mjs` (raw tty, every chunk logged as hex, the modes restored on exit) with no mode,
  bracketed paste (`CSI ? 2004 h`), modifyOtherKeys 2 (`CSI > 4 ; 2 m`), kitty flags 1, 3 and 31
  (`CSI > N u`), and Claude Code's own pair, kitty flags 5 with modifyOtherKeys 2. It also logs the
  terminal's answer to `CSI ? u`.
- Claude Code under `script -q -t 0 -r` (input and output with timestamps; `script -p` plays
  `key-bytes-claude.rec` back), with a dummy key, `ANTHROPIC_BASE_URL` at a closed local port and
  no nonessential traffic.

It sends characters one at a time through the app's `InputSender` (`sendKey` text, the phone's
path): `a / * - + = .` in most capture modes, every printable ASCII character and space under kitty
flags 5, where each CSI u is decoded and marked WRONG when its key number is not the character's US
ANSI base key (`*` → `8`, `A` → `a`) or an unshifted character carries Shift, and `a / * . 1 ? A ~ |
- + =` in Claude Code. Then it sends `pasteText` of `/*`. In Claude Code it first sends `/` alone on
the empty prompt (slash-command menu?) by `sendKey` and `pasteText`, and `*` by `pasteText`, erasing
each with Backspace, and ends with Return and Ctrl-C twice. A System Events key press baseline was
tried and removed: on the runner it delivered nothing, with iTerm2 frontmost.

The report, `key-bytes:` lines in the job log and `key-bytes-report.txt` in the diagnostics, gives
the Mac's keyboard input source, then per mode and character the bytes as hex and escapes, the
WRONG set under kitty flags 5, and for Claude Code every keyboard-mode sequence it wrote (in order)
and whether each character appeared in what it drew next (or the screen text, when it never reached
its prompt). This probe prints terminal content: only the probe characters and Claude Code's UI in a
throwaway CI terminal.

## The latency report

The owner finds live sessions slow, typing echo and scrolling alike. `RealIterm2LatencyTest`
measures them on the real Mac. It reports and does not assert on the numbers; it fails only when a
round could not connect or stream. It runs after the key-bytes probe, in its own step that
continues on error.

It opens a live stream of the first terminal session in the app's own `StreamController` (acks
included, as the live view runs it; the video output records instead of decoding), clears the
prompt (Ctrl-C twice, `clear`), and then measures, in two rounds on fresh connections: the phone's
relay socket with `TCP_NODELAY` (as the app has it, `relayHttpClient`) and without.

- **ping**: 20 `ping` → `pong` round trips, 100 ms apart, with the screen quiet.
- **key → frame**: 20 letters typed at the prompt, one `sendKey` each, one every 250 ms (under the
  300 ms after which iTerm2 re-sends a quiet screen as a keyframe). Each key's time runs from its
  send returning to the arrival from the relay of the first non-key frame of the stream after it.
  The letters are erased with Ctrl-U.
- **scrolling**: a command that prints a line every 10 ms (400 lines). It reports the frames
  received until the output stops: count, frames per second, kbit/s, the gaps between frames
  (min/median/p90/max and a histogram), the time from Return to the first frame, the frame sizes
  and ping round trips taken alongside every 250 ms.
- **stream parameters**: what the phone asks for (`maxFrameRate` 30, no `maxBitrate`, media
  version 2, as iOS), the config the Mac sent (pixel size, scale, grid), and the frame-rate cap,
  effective frame rate and target bitrate the Mac derives from the pixel size. The last three are
  recomputed from iTerm2's formulas (CompanionHostBridge.swift:1218,
  CompanionSessionStreamer.swift:224-265); the wire does not carry them.

Arrival times are the relay queue's stamps (`StreamInput.Media.receivedAtNanos`), the same the
app's video stats overlay uses. Distributions are min/median/p90 (nearest rank)/max in ms. The
relay runs on the runner, on localhost, so the network adds almost nothing: the numbers are the
Mac's and the phone's own share, and the `TCP_NODELAY` difference is a lower bound of what a real
link shows. The report is `latency:` lines in the job log, ending with a summary per round. It
prints numbers only.

## Stage 3: Agent Chat on a fake model

iTerm2's AI runs on `fake-model.mjs`, a local OpenAI Chat Completions server (Node built-ins only)
on `127.0.0.1:8790`. `seed-ai.sh` turns AI on (the `EnableAI` secure setting) and makes it iTerm2's
only model: a manual model at `http://127.0.0.1:8790/v1/chat/completions`, Chat Completions,
function calling and streaming on, credential `none` (iTerm2 sends its self-hosted placeholder, no
key). The fake answers:

- by default, a fixed 73-character reply, streamed as server-sent events in six chunks 250 ms
  apart when the request asks for `stream` (one JSON object otherwise), the way OpenAI streams:
  a role chunk, content chunks, an empty chunk with `finish_reason: stop`, then `[DONE]`;
- when a user message after the conversation's last assistant or tool message holds
  `buddy-ci-approval-trigger` and the request offers `session_execute_command`, one call of that
  tool (in one delta, then `finish_reason: tool_calls`) with `session_guid` and the harmless
  `command` `echo buddy-ci-approval-probe`. The session is the `session_guid=<id>` written after
  the trigger, else the first one in the orchestrator's `<workgroups>` snapshot. A request whose
  last message is a tool result gets the plain reply, so every turn ends.

`RealIterm2AgentChatTest` builds the home graph with the app's own `wireHome`, so the
`ChatRepository` is fed by the client's chat sink through its `ChatInbox`, as on the phone. Then:

- the hello says AI is on and the chat list loads;
- `create(Orchestrator)` makes a chat, and `open` subscribes it (never set visible, so a finished
  turn latches Done, as for an owner not looking at it);
- `send` sends a message: its echo comes back once, delivered; the chat's activity goes Working,
  then Done; the fake's reply lands as exactly one agent message with exactly the fake's text;
- a second message carries the trigger and the first terminal session's GUID. In an orchestrator
  chat iTerm2 parks the tool call on the owner's approval to control that session
  (`OrchestratorDispatcher.ensureSessionClaim`): the activity goes Working, then Waiting, and is
  still Waiting 5 s later; the newest visible message is a workgroup-permission request card for
  `session:<id>` that `isBlockingRequest`. Nobody approves it, so the command never runs;
- the fake's counts show one tool call, and that iTerm2 offered `session_execute_command`; once
  `FAKE_MODEL_TOOL_SCHEMA_SHA256` is pinned, the tool's schema must hash to it;
- `delete` removes the chat, and a fresh list from the Mac no longer has it.

The phone could answer the approval (iOS publishes `userCommand(workgroupPermissionResponse)`),
but this app does not encode approvals yet, and the fake model cannot approve for the owner, so
the test stops at Waiting.

The fake logs one line per request with the body's shape only: roles, counts, lengths, the number
of tools, flags and what it answered. It writes to the diagnostics `fake-model-first-request.json`
and `fake-model-first-tools-request.json` (the first chat request, and the first one offering
tools, with every message's content and tool arguments replaced by their length; the tools are
verbatim), `fake-model-tool-names.txt`, and `fake-model-tool-schema-session_execute_command.json`
(that tool as iTerm2 sent it, keys sorted, which is what the pinned hash covers).
`GET /fake/stats` returns its counts. The test prints chat IDs, states, counts and lengths, never
message text.

Each test connects on its own with the same pairing, so iTerm2 sees the phone reconnect between
tests. `realIterm2Test` runs them in one JVM, one at a time, because the Mac serves one phone.
Every test prints states, counts, sizes, stream IDs and NAL types, never terminal content or
what was typed.

## Steps

| Step | Script | What it does |
| --- | --- | --- |
| Install iTerm2 | `install-iterm2.sh` | Downloads the official 3.7.7 zip and checks its pinned sha256. Then it checks the app with `codesign --verify --deep --strict`, checks the signing team and the Developer ID requirement, assesses it with `spctl`, and checks the version. |
| Install the plugin | `install-companion-plugin.sh` | Downloads `iTermCompanion-1.0.zip` and checks the plugin's Ed25519 JavaScript signature with iTerm2's embedded key (`VerifyPluginSignature.java`). It installs the plugin where iTerm2 would, registers it with LaunchServices, and confirms that NSWorkspace finds it (`plugins.sh`). |
| Install the AI plugin | `install-ai-plugin.sh` | The same for `iTermAI-1.1.zip`, with iTerm2's AI plugin key. Until its zip is pinned, it prints the zip's sha256. |
| Mint the pairing | `generate-pairing.sh` | Runs `GenerateCiPairing.java` to make a Mac X25519 key pair, a phone X25519 key pair, a room secret and a pairing ID. It writes them to `$RUNNER_TEMP/real-iterm2/pairing.env` with mode 600. |
| Seed iTerm2 | `seed-pairing.sh` | Writes the root-owned `EnableCompanionPairing` secure setting with sudo and sets the defaults (relay origin, an empty resolver for direct mode, the paired ID, the Claude watcher off, no tips or update prompts). It also puts the Mac private key and the phone public key in a CI keychain whose partition list names iTerm2's team. |
| Seed AI | `seed-ai.sh` | Writes the root-owned `EnableAI` secure setting and the AI defaults (`UseRecommendedAIModel` off, one `AIManualModelConfigurations` entry for the fake model, `AiModel` naming it, the token budget `AiMaxTokens`/`AiResponseMaxTokens`), answers iTerm2's one-time AI prompts (model catalog updates, automatic terminal sharing, the safety-check migration), and reads them back typed. |
| Seed the status profile | `seed-claude-status.sh` | Installs the `buddy-ci-status` Dynamic Profile (Subtitle `\(status)`) and makes it the default, turns on `EnableAPIServer` and `SetCookie`, writes the root-owned `disable-automation-auth` file, and checks the bundled it2. It continues on error: a failure here fails only `RealIterm2ClaudeStatusTest`. |
| Install Claude Code | `install-claude-code.sh` | Writes the key-bytes probe's `probe.env`, installs the pinned Claude Code with pnpm and seeds `~/.claude.json`. It runs after the status probe and continues on error: without Claude Code the probe still runs its capture modes. |
| Start the relay | `start-relay.sh` | Fetches the relay at `b8aa2f9` the way `scripts/relay-it.sh` does and makes a throwaway CA and a `localhost` certificate. It trusts the CA system-wide and starts the relay on `127.0.0.1:8787` (open mode, `RELAY_ORIGIN=https://localhost:8443`). Then it starts `tls-proxy.mjs` on `localhost:8443`. |
| Start the fake model | `start-fake-model.sh` | Starts `fake-model.mjs` on `127.0.0.1:8790` and waits for `/health`. |
| Launch iTerm2 | `launch-iterm2.sh` | Runs `open -a iTerm`, then waits for the relay to log `hello role=mac` (iTerm2 parked) and takes a screenshot. |
| Test | `./gradlew realIterm2Test --tests …` | Runs stages 1 and 2, then, each in its own step that runs even when the ones before fail, the Claude Code status probe, the key-bytes probe and the latency report (both continue on error) and stage 3, so a main thread that Agent Chat leaves blocked cannot fail the others. The phone connects to the relay directly at `ws://127.0.0.1:8787/` and signs for `https://localhost:8443`. |
| Diagnostics | `collect-diagnostics.sh` | Always runs. It collects screenshots, the relay, proxy and fake model logs, the companion and AI defaults, both secure settings, the status probe's defaults and files (the profile, `disable-automation-auth`, the API socket, it2), which seeded keychain items were migrated, both plugin lookups, the fake model's counts and captured request shape, iTerm2's unified log and crash reports. They are uploaded with the test reports as `real-iterm2-<run>-<attempt>`. |

`dump-iterm2-state.sh <label>` prints, as text in the job log, what a screenshot would show:
iTerm2's on-screen windows (CGWindowList: ID, owner PID, layer, bounds, and the pixel size of a
`screencapture -l` of each non-terminal window), each dialog's or sheet's texts and buttons
(System Events), the AI defaults as iTerm2 holds them, the top of iTerm2's main thread (`sudo
sample`, else `sudo spindump`) and its unified log filtered to AI, chat, network, plugin and
companion lines. A centered layer-25 window 88 points high is CompanionToast ("iTerm2 Buddy
connected"), which outlives its 1.8 s timer only while the main thread is blocked; System Events
reporting no windows while CGWindowList lists some also means a blocked main thread, not a modal. It runs after launch, in the diagnostics, and from
`RealIterm2AgentChatTest` when it fails. The chat test also fails within 30 s of a send when the
fake model gets no chat request, and prints the Mac's `macStatus` (main thread blocked, open
alerts) as it changes.

`common.sh` holds every pin, port and path. The three stage 3 setup steps continue on error, so a
failure there fails `RealIterm2AgentChatTest` (AI off, or the fake unreachable) but not stages 1
and 2.

## Running it

- **Actions → Real iTerm2 → Run workflow**, on any branch.
- While the job is being brought up, a push to `claude/pairing-milestone-handover-8y7390` that
  touches the workflow or `ci/real-iterm2/` also runs it. Remove that trigger once it is stable.
  It never runs on pushes to main.

The tests read `REAL_ITERM2=1`, `REAL_ITERM2_PAIRING_FILE` and `REAL_ITERM2_RELAY_URL`. They also
read three optional timeouts: `REAL_ITERM2_CONNECT_TIMEOUT_SECONDS` (default 180),
`REAL_ITERM2_REQUEST_TIMEOUT_SECONDS` (default 30), which bounds each request and each wait for
iTerm2 to show what a test did, and `REAL_ITERM2_CHAT_TIMEOUT_SECONDS` (default 120), which bounds
each Agent Chat step. `RealIterm2AgentChatTest` also reads `REAL_ITERM2_FAKE_MODEL_URL`, the fake
model's base URL. Without `REAL_ITERM2=1` they skip, and `testDebugUnitTest`
never runs them. `RealIterm2Rig` holds the setup they share.

## Pins

- iTerm2: `https://iterm2.com/downloads/stable/iTerm2-3_7_7.zip`, sha256 `549eb2b2…9da2` (from the
  Homebrew cask), signing team `H7V7XYVQ7D`.
- Companion plugin: `https://iterm2.com/downloads/companion-plugin/iTermCompanion-1.0.zip`,
  sha256 `2a6cea5e…f748` (printed by run 44406337389). A different zip fails the job. Its
  JavaScript is also checked against its Ed25519 signature with iTerm2's embedded key.
- AI plugin: `https://iterm2.com/downloads/ai-plugin/iTermAI-1.1.zip`, not pinned yet: the first
  run that installs it prints its sha256; set `AI_PLUGIN_ZIP_SHA256` to it, and from then on a
  different zip fails the step. Its JavaScript is always checked against its Ed25519 signature
  with iTerm2's embedded AI plugin key.
- The `session_execute_command` schema: not pinned yet. The first run that reaches the trigger
  prints its sha256 (fake model log and test output); set `FAKE_MODEL_TOOL_SCHEMA_SHA256` to it.
- Relay: `gnachman/iterm2-companion-relay` at `b8aa2f90d2bc8b617d1b9c97043aa0b171885310`.
- Actions: pinned by commit SHA, the same as `android-build.yml`.

## Known risks

- **The status probe.** The failure message says which part broke. it2's stderr (in the
  diagnostics, `claude-status-it2-stderr.txt`) names a connection or authentication error when
  it2 cannot reach iTerm2: the socket is missing (`EnableAPIServer` was not read), or the
  connection was refused (`disable-automation-auth` is not root-owned or does not match, which
  can also bring up iTerm2's "Python API Permissions Reset" warning; see the screenshots).
  `get-status` showing the expected status while the subtitle stays empty means the session is
  not on the `\(status)` profile (`Default Bookmark Guid` did not take, or the Dynamic Profile did
  not load). A direct `set-status` that shows while the hook's does not puts the fault in the
  hook's path: it found no `TERM_SESSION_ID`, or decided differently.

- **System trust for the local CA.** `security add-trusted-cert` into the System keychain can
  need interaction on recent macOS. The script relaxes `com.apple.trust-settings.admin` first.
  If trust still fails, iTerm2 never reaches the relay: the TLS proxy log shows
  `TLS handshake failed` and `launch-iterm2.sh` times out.
- **Keychain migration.** iTerm2 reads the data-protection keychain first and falls back to the
  login-style keychain, then migrates. The partition list should stop the "iTerm2 wants to use
  your confidential information" prompt. If it does not, the read blocks on an invisible dialog
  (see the screenshot), and iTerm2 either never parks or shows "Re-pair Your Companion Device".
- **Missing plugin.** If NSWorkspace cannot find the plugin at launch, iTerm2 deletes the seeded
  pairing (`unpairIfPluginMissing`). The diagnostics then show no `NoSyncCompanionPairedPID`.
- **First-launch modals.** A window the defaults do not cover can block the main thread. The
  screenshots show it.
- **Upstream downloads.** `iterm2.com` must be reachable from the runner. If it replaces the
  plugin zip in place, the pin fails the job until the new zip is checked and pinned.
- **HEVC on a virtual Mac.** If VideoToolbox cannot make an HEVC session on the runner, iTerm2
  ends the stream or sends no frames. `RealIterm2LiveStreamTest` then fails at the config or
  keyframe step; the test log shows whether a `streamEnded` came and why, and iTerm2's unified
  log and crash reports are in the diagnostics.
- **The shell.** The input test needs the session's shell at a prompt. If the content changes
  but the line count does not grow, the text arrived but the command did not run. The failure
  message says so, and the screenshot shows the session.
- **AI off.** The test fails first with "the Mac reports AI off" when iTerm2's hello says AI is
  unavailable: the AI plugin is missing or fails its check (version 1.1 expected), or `EnableAI`
  was not read. The diagnostics show the AI plugin lookup and the secure setting.
- **The token budget (an upstream bug).** A chat turn sizes its request from the global
  `AiMaxTokens` and `AiResponseMaxTokens`, not from the manual model's `contextWindowTokens`
  (AIConversation.swift:142-151). When they are unset, `migrateAISettings` writes them from a
  model guessed from `AiModel`'s name, 8192 each for a name iTerm2 does not know
  (iTermMigrationHelper.m:568-624, AIMetadata.swift:99-116). That leaves 4096 tokens, less than
  the orchestrator's system prompt, and `truncate(messages:maxTokens:)` (AITerm.swift:1695-1736)
  then loops forever on the main thread: it never shortens system messages, and its inner loop
  keeps halving a last message that has stopped shrinking. Run 44444477077 caught it there, with
  every sample inside `truncate` under `AIConversation.truncatedMessages`. So any manual model
  with an unrecognised name hangs iTerm2 on its first orchestrator turn, which is worth
  reporting upstream. `seed-ai.sh` sets `AiMaxTokens=128000` and `AiResponseMaxTokens=4096`.
- **The keychain on a chat turn (hardening).** Every turn's AIConversation reads the vendor API
  key on the main thread, even with credential none (AIConversation.swift:165,
  AITerm.swift:267-292). For the absent item that read searches the user's keychain list, so
  `seed-ai.sh` leaves only the unlocked CI keychain there, and a locked login keychain cannot
  bring up a SecurityAgent prompt. It was not the cause on the runner.
- **The Ollama probe.** iTerm2 probes `http://localhost:11434` (the built-in Ollama endpoint)
  whenever it reads its model list, and retries every 5 s when that fails. It is not the chat's
  endpoint; the fake model's stub answers it with no models (`ollama-stub:` lines in its log).
- **The model call.** If the fake log shows no `POST`, iTerm2 never called the model: the manual
  model was not picked up (`defaults-ai-models.txt`), or App Transport Security refused plain http
  to `127.0.0.1`. If the reply step fails while the fake logged `text:no-trigger` answers, the
  stream reached iTerm2 but did not land as one message (the test prints each agent message's
  kind and length).
- **The park.** The trigger only parks when iTerm2 offers `session_execute_command` (the fake logs
  `trigger-without-tool` otherwise) and a session GUID is found (`trigger-without-session-guid`).
  `text:after-tool-result` right after the tool call means iTerm2 answered the call itself (for
  example, no such session) instead of asking the owner.
