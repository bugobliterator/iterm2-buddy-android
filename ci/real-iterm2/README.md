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
| Seed AI | `seed-ai.sh` | Writes the root-owned `EnableAI` secure setting and the AI defaults (`UseRecommendedAIModel` off, one `AIManualModelConfigurations` entry for the fake model, `AiModel` naming it), answers iTerm2's one-time AI prompts (model catalog updates, automatic terminal sharing, the safety-check migration), and reads them back typed. |
| Start the relay | `start-relay.sh` | Fetches the relay at `b8aa2f9` the way `scripts/relay-it.sh` does and makes a throwaway CA and a `localhost` certificate. It trusts the CA system-wide and starts the relay on `127.0.0.1:8787` (open mode, `RELAY_ORIGIN=https://localhost:8443`). Then it starts `tls-proxy.mjs` on `localhost:8443`. |
| Start the fake model | `start-fake-model.sh` | Starts `fake-model.mjs` on `127.0.0.1:8790` and waits for `/health`. |
| Launch iTerm2 | `launch-iterm2.sh` | Runs `open -a iTerm`, then waits for the relay to log `hello role=mac` (iTerm2 parked) and takes a screenshot. |
| Test | `./gradlew realIterm2Test --tests …` | Runs stages 1 and 2, then, in its own step that runs even when they fail, stage 3, so a main thread that Agent Chat leaves blocked cannot fail them. The phone connects to the relay directly at `ws://127.0.0.1:8787/` and signs for `https://localhost:8443`. |
| Diagnostics | `collect-diagnostics.sh` | Always runs. It collects screenshots, the relay, proxy and fake model logs, the companion and AI defaults, both secure settings, which seeded keychain items were migrated, both plugin lookups, the fake model's counts and captured request shape, iTerm2's unified log and crash reports. They are uploaded with the test reports as `real-iterm2-<run>-<attempt>`. |

`dump-iterm2-state.sh <label>` prints, as text in the job log, what a screenshot would show:
iTerm2's on-screen windows (CGWindowList), each dialog's or sheet's texts and buttons (System
Events), the top of iTerm2's main thread (`sample`) and its unified log filtered to AI, chat,
network, plugin and companion lines. It runs after launch, in the diagnostics, and from
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
- **The model call.** If the fake log shows no `POST`, iTerm2 never called the model: the manual
  model was not picked up (`defaults-ai-models.txt`), or App Transport Security refused plain http
  to `127.0.0.1`. If the reply step fails while the fake logged `text:no-trigger` answers, the
  stream reached iTerm2 but did not land as one message (the test prints each agent message's
  kind and length).
- **The park.** The trigger only parks when iTerm2 offers `session_execute_command` (the fake logs
  `trigger-without-tool` otherwise) and a session GUID is found (`trigger-without-session-guid`).
  `text:after-tool-result` right after the tool call means iTerm2 answered the call itself (for
  example, no such session) instead of asking the owner.
