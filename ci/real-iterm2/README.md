# Real iTerm2 CI

`.github/workflows/real-iterm2.yml` runs the app's own `CompanionClient`, as the phone, against a
real iTerm2 3.7.7 on a GitHub-hosted macOS runner (`macos-15`). No secrets and no model key are
involved: every key is minted for the run and thrown away with the runner.

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

Each test connects on its own with the same pairing, so iTerm2 sees the phone reconnect between
tests. `realIterm2Test` runs them in one JVM, one at a time, because the Mac serves one phone.
Every test prints states, counts, sizes, stream IDs and NAL types, never terminal content or
what was typed.

## Steps

| Step | Script | What it does |
| --- | --- | --- |
| Install iTerm2 | `install-iterm2.sh` | Downloads the official 3.7.7 zip and checks its pinned sha256. Then it checks the app with `codesign --verify --deep --strict`, checks the signing team and the Developer ID requirement, assesses it with `spctl`, and checks the version. |
| Install the plugin | `install-companion-plugin.sh` | Downloads `iTermCompanion-1.0.zip` and checks the plugin's Ed25519 JavaScript signature with iTerm2's embedded key (`VerifyPluginSignature.java`). It installs the plugin where iTerm2 would, registers it with LaunchServices, and confirms that NSWorkspace finds it. |
| Mint the pairing | `generate-pairing.sh` | Runs `GenerateCiPairing.java` to make a Mac X25519 key pair, a phone X25519 key pair, a room secret and a pairing ID. It writes them to `$RUNNER_TEMP/real-iterm2/pairing.env` with mode 600. |
| Seed iTerm2 | `seed-pairing.sh` | Writes the root-owned `EnableCompanionPairing` secure setting with sudo and sets the defaults (relay origin, an empty resolver for direct mode, the paired ID, the Claude watcher off, no tips or update prompts). It also puts the Mac private key and the phone public key in a CI keychain whose partition list names iTerm2's team. |
| Start the relay | `start-relay.sh` | Fetches the relay at `b8aa2f9` the way `scripts/relay-it.sh` does and makes a throwaway CA and a `localhost` certificate. It trusts the CA system-wide and starts the relay on `127.0.0.1:8787` (open mode, `RELAY_ORIGIN=https://localhost:8443`). Then it starts `tls-proxy.mjs` on `localhost:8443`. |
| Launch iTerm2 | `launch-iterm2.sh` | Runs `open -a iTerm`, then waits for the relay to log `hello role=mac` (iTerm2 parked) and takes a screenshot. |
| Test | `./gradlew realIterm2Test` | Runs stages 1 and 2. The phone connects to the relay directly at `ws://127.0.0.1:8787/` and signs for `https://localhost:8443`. |
| Diagnostics | `collect-diagnostics.sh` | Always runs. It collects screenshots, the relay and proxy logs, the companion defaults, the secure setting, which seeded keychain items were migrated, the plugin lookup, iTerm2's unified log and crash reports. They are uploaded with the test reports as `real-iterm2-<run>-<attempt>`. |

`common.sh` holds every pin, port and path.

## Running it

- **Actions → Real iTerm2 → Run workflow**, on any branch.
- While the job is being brought up, a push to `claude/pairing-milestone-handover-8y7390` that
  touches the workflow or `ci/real-iterm2/` also runs it. Remove that trigger once it is stable.
  It never runs on pushes to main.

The tests read `REAL_ITERM2=1`, `REAL_ITERM2_PAIRING_FILE` and `REAL_ITERM2_RELAY_URL`. They also
read two optional timeouts, `REAL_ITERM2_CONNECT_TIMEOUT_SECONDS` (default 180) and
`REAL_ITERM2_REQUEST_TIMEOUT_SECONDS` (default 30). The second bounds each request and each wait
for iTerm2 to show what a test did. Without `REAL_ITERM2=1` they skip, and `testDebugUnitTest`
never runs them. `RealIterm2Rig` holds the setup they share.

## Pins

- iTerm2: `https://iterm2.com/downloads/stable/iTerm2-3_7_7.zip`, sha256 `549eb2b2…9da2` (from the
  Homebrew cask), signing team `H7V7XYVQ7D`.
- Companion plugin: `https://iterm2.com/downloads/companion-plugin/iTermCompanion-1.0.zip`,
  sha256 `2a6cea5e…f748` (printed by run 44406337389). A different zip fails the job. Its
  JavaScript is also checked against its Ed25519 signature with iTerm2's embedded key.
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
- **Later stages.** Agent Chat with a fake model is not tested here.
