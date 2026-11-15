# Pairing Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner's Pixel pairs with iTerm2 3.7.7 through a self-hosted relay on the Mac (over Tailscale) and reconnects on its own afterwards.

**Architecture:** A Node relay (`gnachman/iterm2-companion-relay`, open admission) runs on the Mac under launchd behind `tailscale serve`. A Swift harness built from iTerm2 `v3.7.7` sources produces golden vectors and JSON samples. A single-module Kotlin app implements QR parse, relay admission, Noise XK, SAS, room-secret registration and reconnect, tested against those fixtures and against the real relay with a fake Mac built from the same Kotlin core.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, AGP (current stable), JDK 17, OkHttp 4.12 (WebSocket), noise-java 1.0 (`com.southernstorm:noise-java`), BouncyCastle `bcprov-jdk18on` (Ed25519, HKDF), kotlinx-serialization-json, Google code scanner (`com.google.android.gms:play-services-code-scanner`), JUnit 4. Swift 6 + swift-crypto for the harness. Node 20+ and pnpm for the relay.

**Spec:** `docs/superpowers/specs/2026-11-15-android-companion-design.md`, with byte-level detail in `docs/superpowers/specs/2026-11-15-protocol-notes.md` (cited below as *notes*).

**Scope:** This plan covers the spec's first four phases: Relay Setup, Wire Fixtures, Android Skeleton, Pairing. Agent Chat, Approvals and Mac Alerts, Notifications and Terminal each get their own plan once the phase before them has been checked with the owner, because their message shapes come from the fixtures this plan produces. For that reason Wire Fixtures here covers the envelope and the session messages that pairing uses. Samples for chat, approval and alert messages are added to the same harness by the Agent Chat plan.

## Global Constraints

- Package `dev.bugobliterator.itermbuddy`; `minSdk 34`; target Pixel on Android 15+; single Gradle module `app`.
- Target iTerm2 **3.7.7**; upstream sources from tag `v3.7.7` (`e0025340b49a2fc6415f490d77bbead5d3e0583e`).
- Relay origin, byte-identical in all three places: `https://relay.example.ts.net`.
- Relay pinned to `gnachman/iterm2-companion-relay` commit `b8aa2f9`; `ATTEST_REQUIRED=false`; listens on `127.0.0.1` only; reachable on the tailnet only (`tailscale serve`, never `funnel`).
- Noise suite `Noise_XK_25519_ChaChaPoly_BLAKE2s`, phone is initiator, prologue `"iterm2-companion/v1/pid:" + pid`.
- No `Origin` header on any relay request.
- Sends to the Mac must match golden samples; a mismatch is a test failure, not a warning.
- License GPL-2.0. Commit author `bugobliterator <siddharthbharatpurohit@gmail.com>`; no session links in commits.
- Builds, SDK installs and tests run in cloud sessions. Only the Relay Service task and the on-device checks run on the Mac.
- Use pnpm, never npm. Tests are sandboxed (temp dirs, ephemeral ports) and run in parallel (`maxParallelForks` = available processors).
- Names are descriptive: no numbered phases, fixtures, tests or branches.

## Review Focus

1. **Origin spelling.** A QR whose `relay=` has an explicit `:443`, a trailing `/`, or mixed-case host must canonicalise exactly as upstream: keep the port, keep case, drop a bare `/`. A wrong canonical form connects and then fails every signed join with "bad signature". Pinned in *Pairing Code and Room Name*.
2. **Mac not there.** If iTerm2 is closed or the Mac is asleep, the relay answers `mac offline`. The phone shows "Waiting for Mac" and retries with backoff; it does not show a pairing error or wipe state. Pinned in *Companion Client*.
3. **Code rejected or never typed.** The Mac sends `{"pairing":"rejected"}`, or 180 s pass with no confirmation. The phone says so, wipes the half-made pairing and offers to scan again. Pinned in *Companion Client*.
4. **Re-scan over an existing pairing.** Scanning a new QR replaces everything: a new phone static key and room secret, and the registered marker is cleared. Nothing from the old pairing is reused. Pinned in *Pairing Store* and *Companion Client*.
5. **Displaced or dropped connection.** A second phone connection displaces the first (close 1000 "displaced"), or the network drops. The phone reconnects signed after backoff, waiting 60 s after "displaced". It never falls back to a fresh pairing on its own unless the relay says "ticket required". Pinned in *Reconnect Backoff* and *Companion Client*.

---

## Relay Setup

### Task: Relay Service on the Mac

Runs **locally** on the Mac (it is the Mac's own service). Needs the owner first: HTTPS certificates enabled for the tailnet (Tailscale admin console → DNS → HTTPS Certificates).

**Files:**
- Create: `ops/mac/install-relay.sh` (clone/pin, `pnpm install --prod`, write env file, load launchd job, `tailscale serve`)
- Create: `ops/mac/dev.bugobliterator.itermbuddy.relay.plist` (template; `KeepAlive`, `RunAtLoad`, stdout/stderr to `~/Library/Logs/iterm2-companion-relay.log`)
- Create: `ops/mac/check-relay.mjs` (admission probe, Node built-ins plus `ws` from the relay install)
- Create: `ops/mac/README.md` (what it installs, where, how to undo)

**Interfaces:**
- Produces: relay reachable at `wss://relay.example.ts.net/` in open mode. iTerm2 points at it.

Install locations: relay checkout `~/Library/Application Support/iterm2-companion-relay` (commit `b8aa2f9`). Env file `~/.config/iterm2-companion-relay/relay.env`, mode 0600, holding `RELAY_HOST=127.0.0.1`, `RELAY_PORT=8787`, `RELAY_DB=<checkout>/state/relay.db`, `ATTEST_REQUIRED=false`, `RELAY_ORIGIN=https://relay.example.ts.net`, `RELAY_DAILY_BYTE_QUOTA=8589934592` and `RELAY_TRUST_PROXY=true`, because `tailscale serve` sets `X-Forwarded-For`. The plist runs `node bin/relay.js` with that env.

- [ ] **Step: Preflight.** `lsof -iTCP:8787 -sTCP:LISTEN` is empty (else pick a free port and use it everywhere). `tailscale status --json | jq .Self.CapMap` / `tailscale cert --help` shows certs are allowed. `node -v` ≥ 20.
- [ ] **Step: Run the relay's own tests in the checkout.** `pnpm install && pnpm test`. Expected: all pass on Node 24; if any fail only on 24, install Node 20 via nvm and point the plist at it.
- [ ] **Step: Run `ops/mac/install-relay.sh`.** Expected log line: `relay: listening on 127.0.0.1:8787 (db=…, mode=direct, attest=false)`.
- [ ] **Step: Publish on the tailnet.** `tailscale serve --bg --https=443 http://127.0.0.1:8787`; `tailscale serve status` shows the mapping, and `tailscale funnel status` shows nothing.
- [ ] **Step: Probe admission.** `node ops/mac/check-relay.mjs https://relay.example.ts.net`. The probe opens a WebSocket with `x-relay-room` set to 64 random hex characters, sends `{"v":1,"role":"phone"}`, receives `{"nonce":…}`, sends `{}`, and expects `{"ok":false,"error":"mac offline"}`. That proves TLS, admission and open mode. It exits 0 on that, non-zero on anything else.
- [ ] **Step: Point iTerm2 at it.** Quit iTerm2 first (ask the owner, since the agent sessions run in it). Then `defaults write com.googlecode.iterm2 CompanionRelayOrigin -string https://relay.example.ts.net`. Find the user-defaults key behind `companionResolverURL` in `sources/Settings/iTermAdvancedSettingsModel.m` at `v3.7.7` and set it to the empty string. Read both back with `defaults read`.
- [ ] **Step: Check iTerm2 parks.** With iTerm2 running and companion pairing started, re-run the probe against the room in iTerm2's QR. Expected: the probe is admitted (`"ok":true`) instead of `mac offline`. Close the probe at once.
- [ ] **Step: Commit** `ops/mac/*` ("Add Mac relay install and admission probe").

---

## Wire Fixtures

### Task: Upstream Harness

Try in a cloud session on Linux first. Fall back to the Mac only if the shared sources cannot compile on Linux.

**Files:**
- Create: `fixtures/harness/Package.swift` (executable `wire-fixtures`, dependency `apple/swift-crypto`)
- Create: `fixtures/harness/sync-upstream.sh` (sparse-checkout `v3.7.7` into a temp dir, copy the listed files into `Sources/Upstream/`, rewrite `import CryptoKit` → `import Crypto` when not on Darwin)
- Create: `fixtures/harness/upstream-files.txt` (the exact file list)
- Create: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create: `fixtures/README.md` (how to regenerate; files are generated, never hand-edited)

**Interfaces:**
- Produces: `swift run wire-fixtures <out-dir>`, which writes the files described in the next two tasks.

`upstream-files.txt` holds the CompanionProtocol pairing and wire files cited in *notes*: `CanonicalEncoding.swift`, `PairingCode.swift`, `RelayRoom.swift`, `PairingSAS.swift`, `RelayJoin.swift`, `WireCoding.swift`, `CompanionProtocolVersion.swift`. Add `sources/Companion/Shared/CompanionMessages.swift` with whatever it pulls in from `sources/AITerm/`, plus the shims in `Companion/iTerm2Companion/Satellites/`. Take that set from the iOS target's source list in the companion Xcode project at `v3.7.7`, so the harness compiles the same files the iOS app does. Do not hand-port Swift into the harness.

- [ ] **Step: Diff master against `v3.7.7`** for `Companion/` and `sources/Companion/` and record every protocol-relevant difference from *notes* in `fixtures/README.md` under "v3.7.7 vs master".
- [ ] **Step: `sync-upstream.sh` then `swift build`.** Expected: builds. Keep shims for Darwin-only APIs (AppKit/UIKit types referenced by the models) in `Sources/Shims/`, each with a one-line `// shim:` comment naming what it stands in for.
- [ ] **Step: Commit** ("Add upstream wire-fixture harness").

### Task: Crypto Vectors

**Files:**
- Modify: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create (generated): `fixtures/crypto-vectors.json`
- Create: `fixtures/noise/xk-25519-chachapoly-blake2s.json` (the `Noise_XK_25519_ChaChaPoly_BLAKE2s` entries copied from cacophony's `vectors/cacophony.txt`, with the source URL and commit in a `source` field)

**Interfaces:**
- Produces: `crypto-vectors.json`, an object with these fields (all bytes as lowercase hex):
  - `canonical`: `[{domain, fields:[hex], encoded}]`
  - `pairingCodes`: `[{qr, ok, responderStaticPublicKey?, pairingID?, relayOrigin?, error?}]` covering explicit `:443`, trailing `/`, mixed-case host, `v=2`, wrong `proto`, 31-byte `rs`, padded and unpadded base64url
  - `roomNames`: `[{responderStaticPublicKey, pairingID, roomName}]`
  - `sas`: `[{handshakeHash, code}]`, including one hash whose code has leading zeros
  - `joins`: `[{roomSecret, seed, verifier, nonce, roomName, origin, transcript, signature}]`
  - `prologues`: `[{pairingID, prologue}]`

Inputs are fixed bytes chosen in `main.swift`; outputs come only from upstream functions.

- [ ] **Step: Generate** `swift run wire-fixtures ../` and check each array is non-empty and every `pairingCodes` error case has `ok:false`.
- [ ] **Step: Commit** ("Add crypto vectors from v3.7.7").

### Task: Session Message Samples

**Files:**
- Modify: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create (generated): `fixtures/messages/session/*.json`

**Interfaces:**
- Produces: one file per sample named after its content (`hello-phone.json`, `hello-mac-with-alerts.json`, `relay-room-secret.json`, `relay-room-secret-stored.json`, `ping.json`, `pong.json`, `error-reply.json`, `unpairing.json`, `unpaired.json`, `pairing-accepted.json`, `pairing-rejected.json`). Each file is `{"direction":"phoneToMac"|"macToPhone", "swiftValue":"<debug description>", "json":<exact JSONEncoder output as a JSON value>}`, encoded with upstream `WireCoding` including the envelope (`requestID` set where the real flow sets it).
- Produces: `fixtures/messages/revision.json` = `{"revision":…, "minimumPeer":…}` from `CompanionProtocolVersion` at `v3.7.7`. The app uses these values.

- [ ] **Step: Generate and commit** ("Add session message samples from v3.7.7").

---

## Android Skeleton

### Task: Gradle Project

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle wrapper
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/bugobliterator/itermbuddy/MainActivity.kt`
- Create: `scripts/cloud-setup.sh` (installs JDK 17, Android cmdline-tools, platform and build-tools into `$HOME/android-sdk`, idempotent)
- Create: `app/src/test/java/dev/bugobliterator/itermbuddy/FixturesTest.kt`

**Interfaces:**
- Produces: `Fixtures.read(path: String): String` in `app/src/test/.../Fixtures.kt`, which reads from the repo `fixtures/` dir. Set it through the Gradle test task's `systemProperty("fixtures.dir", rootProject.file("fixtures").path)`.

Unit tests: `tasks.withType<Test> { maxParallelForks = Runtime.getRuntime().availableProcessors() }`. Manifest: `INTERNET` permission only for now.

- [ ] **Step: Failing test** `FixturesTest.cryptoVectorsAreReadable`, which asserts `Fixtures.read("crypto-vectors.json")` parses and has a non-empty `roomNames`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest`. FAIL until `Fixtures.kt` exists; then PASS.
- [ ] **Step: Run** `./gradlew lint assembleDebug`. Expected: BUILD SUCCESSFUL. Record serial and parallel test times in the commit body.
- [ ] **Step: Commit** ("Add Android project skeleton").

---

## Pairing

All paths below are under `app/src/main/java/dev/bugobliterator/itermbuddy/` (main) and `app/src/test/java/dev/bugobliterator/itermbuddy/` (test). Each vector-driven test loops over every entry in its fixture array and names the failing entry.

### Task: Pairing Code and Room Name

**Files:**
- Create: `core/pairing/Canonical.kt`, `core/pairing/PairingCode.kt`, `core/pairing/RoomName.kt`
- Test: `core/pairing/PairingCodeTest.kt`

**Interfaces:**
- Produces: `fun canonical(domain: String, fields: List<ByteArray>): ByteArray`
- Produces: `data class PairingCode(val responderStaticPublicKey: ByteArray, val pairingID: String, val relayOrigin: String) { val prologue: ByteArray }`
- Produces: `sealed interface PairingCodeResult { data class Ok(val code: PairingCode); data class Rejected(val reason: String) }` and `fun parsePairingCode(qr: String): PairingCodeResult`. A `v=2` QR is rejected with the reason "This QR uses iTerm2's resolver mode. Set companionResolverURL empty in iTerm2's Advanced settings."
- Produces: `fun roomName(code: PairingCode): String` (64 lowercase hex)

- [ ] **Step: Failing tests** `canonicalMatchesVectors`, `pairingCodesMatchVectors` (ok entries: all three fields equal; error entries: `Rejected`), `roomNamesMatchVectors`, `prologuesMatchVectors`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*PairingCodeTest*'`. FAIL.
- [ ] **Step: Implement.** Parse with `java.net.URI`; canonical origin is `"https://" + host + (if port given: ":" + port)`, case preserved. `SHA-256` via `MessageDigest`.
- [ ] **Step: Run.** PASS.
- [ ] **Step: Commit** ("Add QR pairing code parsing and room name").

### Task: SAS and Join Signature

**Files:**
- Create: `core/pairing/Sas.kt`, `core/relay/JoinSigner.kt`
- Test: `core/pairing/SasTest.kt`, `core/relay/JoinSignerTest.kt`

**Interfaces:**
- Produces: `fun sasCode(handshakeHash: ByteArray): String` (6 digits)
- Produces: `class JoinSigner(roomSecret: ByteArray) { val verifier: ByteArray; fun sign(nonce: ByteArray, roomName: String, origin: String): ByteArray }`. The seed is `HKDF-SHA256(roomSecret, salt=empty, info="relay-auth-ed25519", 32)` via BouncyCastle `HKDFBytesGenerator`, and the key is `Ed25519PrivateKeyParameters(seed)`. Phone role byte `0x02`.

- [ ] **Step: Failing tests** `sasMatchesVectors`, `joinSeedVerifierTranscriptSignatureMatchVectors`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add SAS and relay join signature").

### Task: Noise Channel

**Files:**
- Create: `core/noise/NoiseInitiator.kt`, `core/noise/NoiseChannel.kt`
- Test: `core/noise/NoiseVectorsTest.kt`, `core/noise/NoiseChannelTest.kt`

**Interfaces:**
- Produces: `class NoiseInitiator(responderStatic: ByteArray, localStaticPrivate: ByteArray, prologue: ByteArray) { fun writeMessage1(): ByteArray; fun readMessage2(msg: ByteArray); fun writeMessage3(): ByteArray; val handshakeHash: ByteArray; fun split(): NoiseChannel }`, plus `NoiseResponder` with the mirror methods, used by the fake Mac in tests.
- Produces: `class NoiseChannel { fun encryptMessage(plaintext: ByteArray): List<ByteArray>; fun decryptFrame(frame: ByteArray): ByteArray? }`. Encryption chunks to `[cont] || chunk` with chunk ≤ 65518 and returns one ciphertext per WebSocket frame. `decryptFrame` returns the reassembled plaintext when `cont == 0`, else null. Callers serialise sends.
- Produces: `fun newStaticKeyPair(): Pair<ByteArray /*private*/, ByteArray /*public*/>`

Use noise-java's `HandshakeState("Noise_XK_25519_ChaChaPoly_BLAKE2s", INITIATOR)`, with `getHandshakeHash()` read before `split()`. If noise-java cannot do this (no BLAKE2s, or no hash before split), stop and report. The spec's fallback, Noise XK on BouncyCastle, is a scope change to confirm first.

- [ ] **Step: Failing tests.** `cacophonyXkVectorsPass` sets fixed ephemerals through `getFixedEphemeralKey()` and checks every message and handshake hash. `channelRoundTripsAcrossChunkBoundary` sends 0 bytes, 65518 bytes, 65519 bytes and 200 000 bytes and checks they decrypt equal, with frame counts of 1, 1, 2 and 4. `handshakeFrameSizesAre48_48_64`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add Noise XK channel with chunking").

### Task: Wire Envelope and Session Messages

**Files:**
- Create: `core/wire/Envelope.kt`, `core/wire/SessionMessages.kt`, `core/wire/ProtocolRevision.kt`
- Test: `core/wire/SessionMessagesTest.kt`

**Interfaces:**
- Produces: `sealed interface PhoneMessage { fun payload(): JsonObject }` with `Hello(revision: Int, minimumPeer: Int)`, `RelayRoomSecret(secret: ByteArray)`, `Ping`, `Unpairing`.
- Produces: `sealed interface MacMessage` with `HelloReply(revision: Int, minimumPeer: Int, raw: JsonObject)`, `RelayRoomSecretStored`, `Pong`, `Unpaired`, `Error(raw: JsonObject)`, `Unsupported(case: String, raw: JsonObject)`. Unknown cases become `Unsupported`; a known case with a bad body throws `WireDecodeException`.
- Produces: `fun encodeEnvelope(message: PhoneMessage, requestID: Long?): ByteArray` and `fun decodeEnvelope(bytes: ByteArray): Pair<Long?, MacMessage>`. A first byte `0x01` is a media frame and is returned as `MacMessage.Unsupported("media", …)` for now.
- Produces: `fun decodePairingConfirmation(bytes: ByteArray): Boolean?`. True only for exactly `{"pairing":"accepted"}`, false for `rejected`, null otherwise.
- Produces: `object ProtocolRevision { const val REVISION: Int; const val MINIMUM_PEER: Int }`, copied from `fixtures/messages/revision.json` with a comment naming the file.

Swift enum coding rules are in *notes* → Encoding: `_0` for unlabelled values, `{}` for empty cases, nil omitted, `Data` as standard base64.

- [ ] **Step: Failing tests.** `phoneSamplesEncodeExactly` compares each phone→Mac sample's `json` with our encoding as parsed JSON trees (equal trees, key order ignored). `macSamplesDecode` decodes every Mac→phone sample to the expected type. `unknownCaseIsUnsupported`. `badBodyThrows`. `confirmationAcceptsOnlyExactAccepted`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add wire envelope and session messages").

### Task: Relay Connection

**Files:**
- Create: `core/relay/RelayConnection.kt`, `core/relay/ReconnectBackoff.kt`, `core/relay/RelayRegistrar.kt`
- Test: `core/relay/ReconnectBackoffTest.kt`, `core/relay/RelayConnectionTest.kt` (OkHttp `MockWebServer` for the WebSocket)

**Interfaces:**
- Produces: `sealed interface Proof { object Fresh; data class Signed(val signer: JoinSigner) }`
- Produces: `class RelayConnection(client: OkHttpClient, origin: String, roomName: String, transportUrl: String = origin.replace("https://", "wss://") + "/")`. The signature always uses `origin`; tests point `transportUrl` at `ws://127.0.0.1:<port>/`.
  - `suspend fun admit(proof: Proof): AdmissionResult`, where `AdmissionResult` is `Admitted(registrationToken: String)` or `Rejected(error: String)`, and `Closed(code: Int, reason: String)` or `HttpFailure(status: Int)` for anything else.
  - `suspend fun send(frame: ByteArray)`, `val incoming: ReceiveChannel<ByteArray>`, `fun close()`
  - Headers: only `x-relay-room` and `User-Agent: iTermBuddyAndroid`; `pingInterval(15, SECONDS)`. A text frame after admission is fatal.
- Produces: `fun nextDelayMillis(attempt: Int, lastCloseReason: String?, random: Random): Long`. Full jitter is `rand(0, if attempt == 1 then 3 else min(30, 2^attempt))` seconds. "displaced" gives 60 s and "daily quota exceeded" gives 30 min. Pre-admission HTTP 403, 413 and other 4xx are fatal (`null`); 429 and 5xx retry.
- Produces: `class RelayRegistrar(client: OkHttpClient, origin: String) { suspend fun register(roomName: String, registrationToken: String, verifier: ByteArray): Boolean }`. Success is 2xx, or 403 with "already registered".

- [ ] **Step: Failing tests.**
  - `backoffBoundsPerAttempt` checks attempts 1, 2, 5 and 9 with a seeded `Random`.
  - `displacedWaitsSixtySeconds`, `quotaWaitsThirtyMinutes`, `http403IsFatal`.
  - `noOriginHeaderSent` asserts on the recorded request.
  - `freshProofSendsEmptyObject`, `signedProofSendsSig`.
  - `textFrameAfterAdmissionIsFatal`.
  - `registerTreatsAlreadyRegisteredAsSuccess`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add relay connection, backoff and registrar").

### Task: Pairing Store

**Files:**
- Create: `store/PairingStore.kt`, `store/KeystorePairingStore.kt`
- Test: `store/InMemoryPairingStoreTest.kt`

**Interfaces:**
- Produces: `data class PairingRecord(val code: PairingCode, val phoneStaticPrivate: ByteArray, val roomSecret: ByteArray, val registered: Boolean)`
- Produces: `interface PairingStore { fun load(): PairingRecord?; fun save(record: PairingRecord); fun clear() }`, `class InMemoryPairingStore : PairingStore` (tests), `class KeystorePairingStore(context: Context) : PairingStore`. The Keystore store encrypts a JSON blob with an AES-256-GCM key held in `AndroidKeyStore` under alias `itermbuddy-pairing` and keeps it in a private SharedPreferences file.
- Produces: `fun beginFreshPairing(store: PairingStore, code: PairingCode): PairingRecord`. It clears the store, then mints a new static key pair and a new 32-byte room secret, with `registered = false`.

- [ ] **Step: Failing tests.** `freshPairingReplacesEveryOldSecret` saves a record, calls `beginFreshPairing` with a different code, and asserts the static key, room secret, code and `registered` all differ from the old record. `roundTrip`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS. `KeystorePairingStore` is exercised in the on-device check.
- [ ] **Step: Commit** ("Add pairing store").

### Task: Companion Client

The connect flow, owned later by the foreground service. This task has no UI.

**Files:**
- Create: `core/CompanionClient.kt`
- Test: `core/CompanionClientTest.kt` (fake relay in-process), `fakemac/FakeMac.kt`, `fakemac/RelayEndToEndTest.kt`
- Create: `scripts/relay-it.sh`. It clones the relay at `b8aa2f9` into a temp dir, runs `pnpm install --prod`, and runs `./gradlew testDebugUnitTest --tests '*RelayEndToEndTest*'` with `RELAY_DIR` set.

**Interfaces:**
- Consumes: everything above.
- Produces: `class CompanionClient(store: PairingStore, http: OkHttpClient, scope: CoroutineScope, transportUrlOverride: String? = null)`
  - `val state: StateFlow<ClientState>` with `ClientState` = `Unpaired`, `Connecting`, `WaitingForMac`, `ShowCode(code: String)`, `Connected(macRevision: Int)`, `Failed(message: String, rescanNeeded: Boolean)`.
  - `fun pair(code: PairingCode)` (fresh: no auto-retry), `fun start()` (reconnect loop for a stored pairing), `fun stop()`, `suspend fun request(message: PhoneMessage): MacMessage`, `val events: SharedFlow<MacMessage>` for unsolicited messages.
- Produces: `class FakeMac(relayUrl: String, origin: String, code: PairingCode, macStaticPrivate: ByteArray)` in test sources. It admits as `{"v":1,"role":"mac"}` with proof `{}`, runs the Noise responder, sends `pairing` accepted or rejected after a configurable delay, answers `hello`, `relayRoomSecret` and `ping`, and can drop its socket.

Flow (*notes* → Relay admission, Noise, After pairing):
1. Admit fresh (`{}`) unless `registered`; otherwise signed. On "mac offline" go to `WaitingForMac` and retry with backoff, during both fresh and reconnect.
2. Self-heal: if a fresh admit is rejected with "signature required" and a room secret exists, set `registered=true` and retry signed. If a signed admit is rejected with "ticket required", set `registered=false` and retry fresh.
3. Noise handshake, with a 15 s timeout fresh and 6 s on reconnect. A fresh pairing shows `ShowCode(sasCode(h))` and waits up to 180 s for the confirmation. Rejection or timeout means `store.clear()` and `Failed(rescanNeeded=true)`.
4. `hello` request. If our revision < the Mac's `minimumPeer`, fail with "Update the app"; if the Mac's revision < our `MINIMUM_PEER`, fail with "Update iTerm2".
5. `relayRoomSecret` → wait for `relayRoomSecretStored`. If not `registered`: `register(...)`, then save with `registered=true`. This runs on every connect.
6. `Connected`. On socket loss, `nextDelayMillis` and loop to step 1, signed.

- [ ] **Step: Failing tests (in-process fake relay).**
  - `freshPairingReachesConnectedAndRegisters`.
  - `macOfflineShowsWaitingThenConnects`.
  - `rejectedCodeWipesStoreAndAsksRescan`.
  - `confirmationTimeoutWipesStore`, with an injected 180 s timeout set to 200 ms in the test.
  - `signatureRequiredSelfHealsToSigned`, `ticketRequiredSelfHealsToFresh`.
  - `droppedSocketReconnectsSigned`, `displacedWaitsBeforeReconnect` (virtual time).
  - `macTooOldFailsWithUpdateITerm2`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Failing end-to-end test** `RelayEndToEndTest.pairsThroughRealRelay`. It is skipped via `Assume` when `RELAY_DIR` is unset. It starts `node bin/relay.js` with `RELAY_PORT=0`, a temp `RELAY_DB`, `ATTEST_REQUIRED=false` and `RELAY_ORIGIN=https://relay.test`, then parses the port from the `listening on` line. The `FakeMac` and the client pair through it, the client reconnects signed after the relay is restarted on the same DB, and `ping`/`pong` round-trips.
- [ ] **Step: Run** `scripts/relay-it.sh`. PASS.
- [ ] **Step: Commit** ("Add companion client connect flow and relay end-to-end test").

### Task: Pairing Screens

**Files:**
- Create: `ui/PairingScreen.kt`, `ui/ConnectionStatus.kt`, `CompanionApp.kt` (Application holding the single `CompanionClient`)
- Modify: `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` (code scanner, Compose)

**Interfaces:**
- Consumes: `CompanionClient.state`, `pair`, `start`; `parsePairingCode`; `KeystorePairingStore`.

Screens, one per `ClientState`:
- `Unpaired`: a **Scan to pair** button. It opens `GmsBarcodeScanning.getClient(context)` restricted to `FORMAT_QR_CODE`, so no camera permission is needed. The scanned text goes to `parsePairingCode`; a `Rejected` result shows its reason.
- `Connecting`: "Connecting…".
- `WaitingForMac`: "Waiting for your Mac. Is iTerm2 running?"
- `ShowCode`: "Type this code on your Mac", with the code as `482 913` (3+3, monospace, large).
- `Connected`: "Connected to iTerm2", with Unpair. Unpair sends `Unpairing`, calls `store.clear()` and returns to `Unpaired`.
- `Failed`: the message, plus **Scan again** when `rescanNeeded`.

The app calls `start()` on launch when a pairing is stored. No foreground service yet; that comes in Notifications.

- [ ] **Step: Build** `./gradlew lint assembleDebug` in the cloud and attach the APK as a build artifact (or push it on a branch the Mac can pull).
- [ ] **Step: On-device check (local, owner).** Prerequisites: Tailscale is connected on the Pixel and the relay is running. Then:
  1. `adb install -r app-debug.apk`; start pairing in iTerm2; scan; type the code on the Mac. Expected: **Connected to iTerm2**.
  2. Force-stop the app and reopen it. Expected: Connected without scanning.
  3. Quit iTerm2. Expected: **Waiting for your Mac**. Relaunch iTerm2. Expected: Connected.
  4. Pair again and type a wrong code on the Mac. Expected: failure with **Scan again**.
  5. Run `relay` log grep for `bad signature`. Expected: none.

  Record the results in `docs/reports/pairing-on-device.md`.
- [ ] **Step: Commit** ("Add pairing screens").

---

## After this plan

Report to the owner, the tracker and `master-session`, then ask before starting Agent Chat. The Agent Chat plan extends the harness with chat, approval and alert samples, and adds the debug frame recorder that captures real Mac traffic.
