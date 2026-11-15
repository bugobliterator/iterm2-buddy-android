# Pairing Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner's Pixel pairs with iTerm2 3.7.7 through a self-hosted relay on the Mac (over Tailscale) and reconnects on its own afterwards.

**Architecture:** A Node relay (`gnachman/iterm2-companion-relay`, open admission) runs on the Mac under launchd behind `tailscale serve`. A Swift harness built from iTerm2 `v3.7.7` sources produces golden vectors and JSON samples. A single-module Kotlin app implements QR parse, relay admission, Noise XK, SAS, room-secret registration and reconnect, tested against those fixtures and against the real relay with a fake Mac built from the same Kotlin core.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, AGP (current stable), JDK 17, OkHttp 4.12 (WebSocket). noise-java comes from `org.signal.forks:noise-java:0.1.1`, because upstream `com.southernstorm:noise-java` was never published to Maven Central. Also BouncyCastle `bcprov-jdk18on` (Ed25519, HKDF), kotlinx-serialization-json and the Google code scanner (`com.google.android.gms:play-services-code-scanner`). Tests use JUnit 4, OkHttp `mockwebserver` and `kotlinx-coroutines-test`. The harness uses Swift 6 and swift-crypto. The relay needs Node 20+ and pnpm (11.x on the Mac).

**Spec:** `docs/superpowers/specs/2026-11-15-android-companion-design.md`, with byte-level detail in `docs/superpowers/specs/2026-11-15-protocol-notes.md` (cited below as *notes*).

**Scope:** This plan covers the spec's first four phases: Relay Setup, Wire Fixtures, Android Skeleton and Pairing. Agent Chat, Approvals and Mac Alerts, Notifications and Terminal each get their own plan once the phase before them has been checked with the owner, because their message shapes come from the fixtures this plan produces. For that reason Wire Fixtures here covers the envelope and the session messages that pairing uses. Samples for chat, approval and alert messages are added to the same harness by the Agent Chat plan.

**Reviewed by:** Codex Astra (`gpt-6-astra`) and Mythos (`claude-mythos-5-1`), adversarially, on 2026-11-15. Both found the protocol bytes correct against `v3.7.7` and relay `b8aa2f9`. This revision applies all of their findings.

## Global Constraints

- Package `dev.bugobliterator.itermbuddy`; `minSdk 34`; target Pixel on Android 15+; single Gradle module `app`; `android:allowBackup="false"`.
- Target iTerm2 **3.7.7**; upstream sources from tag `v3.7.7` (`e0025340b49a2fc6415f490d77bbead5d3e0583e`). Master differs from the tag only in `AppModel.swift` on the relevant paths.
- Relay origin, byte-identical in all three places: `https://siddharths-macbook-pro.tailnet-99bb.ts.net`.
- Relay pinned to `gnachman/iterm2-companion-relay` commit `b8aa2f9`; `ATTEST_REQUIRED=false`; listens on `127.0.0.1` only; reachable on the tailnet only (`tailscale serve`, never `funnel`).
- Noise suite `Noise_XK_25519_ChaChaPoly_BLAKE2s`, phone is initiator, prologue `"iterm2-companion/v1/pid:" + pid`.
- No `Origin` header on any relay request.
- Advertise protocol revision **11** with minimum peer **11** for this milestone. That is the Mac's floor and covers no unbuilt feature. Later plans raise it as features land. Revision 15 (browser streaming) is a non-goal.
- Sends to the Mac must match golden samples; a mismatch is a test failure, not a warning.
- License GPL-2.0. Commit author `bugobliterator <siddharthbharatpurohit@gmail.com>`; no session links in commits.
- Builds, SDK installs and tests run in cloud sessions. Only the Relay Service task and the on-device checks run on the Mac.
- Use pnpm, never npm. Tests are sandboxed (temp dirs, ephemeral ports) and run in parallel (`maxParallelForks` = available processors). Every timeout in the client is injectable so tests run in milliseconds.
- Names are descriptive: no numbered phases, fixtures, tests or branches.

## Review Focus

1. **Origin spelling.** A QR whose `relay=` has an explicit `:443`, a trailing `/`, or a mixed-case host must canonicalise exactly as upstream: keep the port, keep case, drop a bare `/`. A wrong form connects and then fails every signed join with "bad signature". Three such rejections in a row end in a visible failure, never a silent retry loop. Pinned in *Pairing Code and Room Name* and *Companion Client*.
2. **Mac not there.** There are two cases: iTerm2 closed (the relay answers `mac offline`), and Mac asleep (the relay runs on the Mac, so the connection or DNS lookup fails). For a stored pairing, both show "Waiting for your Mac" and retry with backoff. For a fresh pairing they retry for up to 120 s; iTerm2 replaces an idle QR after 120 s (`CompanionPairingController.swift:262, 799-823`). After that the phone shows **Scan again**. Every waiting screen has Cancel. Pinned in *Companion Client*.
3. **Code rejected or never typed.** The Mac sends `{"pairing":"rejected"}`, or 180 s pass with no confirmation. The phone says so, wipes the half-made pairing and offers to scan again. Pinned in *Companion Client*.
4. **App killed mid-pairing.** Android can kill the process after the Mac accepts the code but before registration. The phone must still reconnect, because the Mac already pinned it (`CompanionPairingController.swift:1511-1531`). That holds even if the kill lands between the Mac's "accepted" and the phone saving it. Pending credentials are recovered, not wiped, unless the Mac is gone, or still holding the old code dialog and so not parked. Re-scanning replaces every old secret. Pinned in *Pairing Store* and *Companion Client*.
5. **Relay lost its state.** The relay's database can be deleted, re-cloned or reaped after 30 idle days. With attestation off, the relay then admits signed joins in open mode and never says "ticket required" (`room.js:410-458`). The phone re-registers on every connect, so the room is locked again on the next connect. The test for this case is `relayLostVerifierIsRelocked`, pinned in *Companion Client*.

---

## Relay Setup

### Task: Relay Service on the Mac

Runs **locally** on the Mac (it is the Mac's own service). Tailnet HTTPS certificates are already enabled.

**Files:**
- Create: `ops/mac/install-relay.sh`. It pins the checkout, installs, renders the plist, loads launchd, runs `tailscale serve` and checks the relay starts.
- Create: `ops/mac/dev.bugobliterator.itermbuddy.relay.plist.template`
- Create: `ops/mac/check-relay.mjs` (admission probe)
- Create: `ops/mac/README.md` (what it installs, where, how to undo)

**Interfaces:**
- Produces: relay reachable at `wss://siddharths-macbook-pro.tailnet-99bb.ts.net/` in open mode. iTerm2 points at it.

Locations:
- **Checkout:** `~/Library/Application Support/iterm2-companion-relay`, at commit `b8aa2f9`.
- **State:** `~/Library/Application Support/iterm2-companion-relay-state/relay.db`. It sits outside the checkout so a re-clone cannot wipe registrations.
- **Env file:** `~/.config/iterm2-companion-relay/relay.env`, mode 0600, holding:
  - `RELAY_HOST=127.0.0.1`, `RELAY_PORT=8787`, `RELAY_DB=<state path>`
  - `ATTEST_REQUIRED=false`, `RELAY_ORIGIN=https://siddharths-macbook-pro.tailnet-99bb.ts.net`
  - `RELAY_DAILY_BYTE_QUOTA=8589934592`
  - `RELAY_TRUST_PROXY=true`, because `tailscale serve` sets `X-Forwarded-For`
- **launchd:** launchd cannot source an env file and has no nvm `node` on its PATH. The install script renders the env values into the plist's `EnvironmentVariables`, writes the absolute `node` path into `ProgramArguments`, and sets `KeepAlive`, `RunAtLoad` and a log at `~/Library/Logs/iterm2-companion-relay.log`.

- [ ] **Step: Preflight.** `lsof -iTCP:8787 -sTCP:LISTEN` should be empty; otherwise pick a free port and use it everywhere. `tailscale status --json | jq .CertDomains` should list `siddharths-macbook-pro.tailnet-99bb.ts.net`. `node -v` should be ≥ 20.
- [ ] **Step: Install with the native build allowed.** pnpm 11 (11.3.0 on the Mac) refuses unlisted dependency builds (`strictDepBuilds`), and `better-sqlite3` needs its build to fetch the native binary. `install-relay.sh` writes `pnpm-workspace.yaml` in the checkout containing `allowBuilds:` / `  better-sqlite3: true`, which is valid on pnpm ≥ 10.26 and 11. It then runs `pnpm install --prod`; `pnpm install` has no `--allow-build` flag, and `onlyBuiltDependencies` was removed in pnpm 11. Then `node -e "require('better-sqlite3')"` must exit 0.
- [ ] **Step: Run the relay's own tests.** In a throwaway copy of the checkout, write `pnpm-workspace.yaml` with `allowBuilds` set to true for `better-sqlite3`, `esbuild` and `fsevents`, since the dev dependencies (Vitest/Vite) bring build scripts too. Then run `pnpm install && pnpm test`. Expected: all pass on Node 24. If any fail only on 24, install Node 20 via nvm and use its absolute path in the plist. Production installs list only `better-sqlite3`.
- [ ] **Step: Run `ops/mac/install-relay.sh`.** Expected log line: `relay: listening on 127.0.0.1:8787 (db=…, mode=direct, attest=false)`.
- [ ] **Step: Publish on the tailnet.** Run `tailscale serve --bg --https=443 http://127.0.0.1:8787`. `tailscale serve status` should show the mapping and `tailscale funnel status` nothing.
- [ ] **Step: Probe admission.** Run `node ops/mac/check-relay.mjs https://siddharths-macbook-pro.tailnet-99bb.ts.net`. It loads `ws` with `createRequire(<checkout>/package.json)`, because Node's global `WebSocket` cannot set `x-relay-room`. It sends `x-relay-room` set to 64 random hex characters, then `{"v":1,"role":"phone"}`, receives `{"nonce":…}`, sends `{}`, and expects `{"ok":false,"error":"mac offline"}`. It exits 0 on that, non-zero otherwise.
- [ ] **Step: Point iTerm2 at it.** Quit iTerm2 first; ask the owner, since the agent sessions run in it. Then run `defaults write com.googlecode.iterm2 CompanionRelayOrigin -string https://siddharths-macbook-pro.tailnet-99bb.ts.net` and `defaults write com.googlecode.iterm2 CompanionResolverURL -string ""` (keys from `iTermAdvancedSettingsModel.m:929-930`). Read both back with `defaults read`.
- [ ] **Step: Check iTerm2 parks.** Start companion pairing in iTerm2, check its QR has `v=1`, and pass the QR string to the probe (`--qr '<iterm2://pair?…>'`). The probe computes the room as `SHA256(canonical("iterm2-room",[rs,pid]))` and should be admitted (`"ok":true`). Close it at once. The probe uses up one of the room's 8 pairing cycles and makes the Mac's parked socket close 1001 "peer gone"; both are harmless. Restart pairing in iTerm2 afterwards.
- [ ] **Step: Commit** `ops/mac/*` ("Add Mac relay install and admission probe").

---

## Wire Fixtures

### Task: Upstream Harness

Try in a cloud session on Linux first. Fall back to the Mac if the patched sources still do not compile on Linux.

**Files:**
- Create: `fixtures/harness/Package.swift` with two targets:
  - `CompanionProtocol`: the CompanionCore protocol files, which are public.
  - `WireFixtures` (executable): `main.swift` plus `CompanionMessages.swift`, its `sources/AITerm` dependencies and the shims, compiled together because the message enums are `internal` (`CompanionMessages.swift:680, 971, 1190, 1200`).
  - Dependency: `apple/swift-crypto`.
- Create: `fixtures/harness/sync-upstream.sh`. It sparse-checks out `v3.7.7` into a temp dir and copies the listed files into the two target directories. When not on Darwin, it rewrites `import CryptoKit` to `import Crypto` and applies `fixtures/harness/linux.patch`.
- Create: `fixtures/harness/upstream-files.txt` (the exact file list, with the target for each file)
- Create: `fixtures/harness/linux.patch`. It removes `@objc`/`@objc(...)` and `: NSObject` from exactly `sources/AITerm/iTermAIError.swift` and `sources/ClaudeCode/Orchestration/StableSessionID.swift`.
- Create: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create: `fixtures/README.md` (how to regenerate; generated files are never hand-edited)

**Interfaces:**
- Produces: `swift run wire-fixtures <out-dir>`, which writes the files described in the next two tasks.

`upstream-files.txt` holds:
- the CompanionProtocol files cited in *notes*: `CanonicalEncoding.swift`, `PairingCode.swift`, `RelayRoom.swift`, `PairingSAS.swift`, `RelayJoin.swift`, `PairingConfirmation.swift`, `WireCoding.swift`, `CompanionProtocolVersion.swift`, and `Transport/MessageTransport.swift` (for `CompanionLog`)
- `sources/Companion/Shared/CompanionMessages.swift` and `CompanionSession.swift`
- the rest of the iOS target's shared sources at `v3.7.7`, including `sources/Categories/{Data+BigEndian,Result+iTerm,NSDictionaryCodableBox}.swift`, `sources/Companion/{CompanionMessageSubstance,MentionPlainTextRenderer}.swift`, the `sources/AITerm/` and `sources/ClaudeCode/` files, and the shims in `Companion/iTerm2Companion/Satellites/`
- **excluded:** `sources/Companion/Shared/CompanionHEVCSampleBuilder.swift` (`CoreMedia`) and `CompanionTouchMapper.swift` (`CoreGraphics`). Neither exists on Linux, and `CompanionMessages.swift` needs neither; it defines `CompanionSelectionPoint` itself.

Enumerate every file explicitly in `upstream-files.txt`, starting from the iOS target's source list in the companion Xcode project at `v3.7.7`, so the harness compiles the files the iOS app compiles. The only change to upstream source is `linux.patch`.

- [ ] **Step: `sync-upstream.sh` then `swift build`.** Expected: builds. Any extra shim for a Darwin-only API goes in `Sources/WireFixtures/Shims/`, each with a one-line `// shim:` comment naming what it stands in for.
- [ ] **Step: Commit** ("Add upstream wire-fixture harness").

### Task: Crypto Vectors

**Files:**
- Modify: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create (generated): `fixtures/crypto-vectors.json`
- Create: `fixtures/noise/xk-25519-chachapoly-blake2s.json`. It holds the `Noise_XK_25519_ChaChaPoly_BLAKE2s` entries from cacophony's `vectors/cacophony.txt`, with the source URL and commit in a `source` field.

**Interfaces:**
- Produces: `crypto-vectors.json`, an object with these fields (all bytes as lowercase hex):
  - `canonical`: `[{domain, fields:[hex], encoded}]`
  - `pairingCodes`: `[{qr, upstreamOk, responderStaticPublicKey?, pairingID?, relayOrigin?, androidAccepts, androidReason?}]`
    - Inputs cover explicit `:443`, trailing `/`, mixed-case host, wrong `proto`, 31-byte `rs`, and padded and unpadded base64url.
    - Also included: a valid `v=2` resolver QR (`upstreamOk:true`, `androidAccepts:false` with the resolver reason), and one QR produced by upstream `PairingCode.urlString()` so the Mac's real percent-encoding is covered.
    - `upstreamOk` and the parsed fields come from upstream. `androidAccepts` is the app's policy, written in `main.swift`.
  - `roomNames`: `[{responderStaticPublicKey, pairingID, roomName}]`
  - `sas`: `[{handshakeHash, code}]`, including one hash whose code has leading zeros
  - `joins`: `[{role, roomSecret, seed, verifier, nonce, roomName, origin, transcript, signature}]` for role `phone` (0x02) and `mac` (0x01)
  - `prologues`: `[{pairingID, prologue}]`

Inputs are fixed bytes chosen in `main.swift`; outputs come only from upstream functions.

- [ ] **Step: Generate** with `swift run wire-fixtures ../`. Check each array is non-empty and both roles appear in `joins`.
- [ ] **Step: Commit** ("Add crypto vectors from v3.7.7").

### Task: Session Message Samples

**Files:**
- Modify: `fixtures/harness/Sources/WireFixtures/main.swift`
- Create (generated): `fixtures/messages/session/*.json`, `fixtures/messages/confirmation/*.json`, `fixtures/messages/revision.json`

**Interfaces:**
- Produces: envelope samples in `fixtures/messages/session/`, one file per sample named after its content:
  - `hello-phone.json`, `hello-mac.json`, `hello-mac-with-alerts.json`
  - `relay-room-secret.json`, `relay-room-secret-stored.json`
  - `ping.json`, `pong.json`, `error-reply.json`, `unpairing.json`, `unpaired.json`

  Each file is `{"direction":"phoneToMac"|"macToPhone", "swiftValue":"<debug description>", "json":<exact output as a JSON value>}`, encoded with upstream `WireCoding` including the envelope. `requestID` is set where the real flow sets it; `unpairing` has none (fire-and-forget).
- Produces: `fixtures/messages/confirmation/accepted.json` and `rejected.json`, as `{"bytes":"<utf8 text>"}` from `PairingConfirmation.encoded()`. These are bare JSON with no envelope, as the Mac sends them (`CompanionPairingController.swift:1472-1473`).
- Produces: `fixtures/messages/revision.json` = `{"revision":…, "minimumPeer":…}` from `CompanionProtocolVersion` at `v3.7.7`. This is the reference for what the Mac speaks; the app advertises 11 (see Global Constraints).

The spec's harness item (c) is "Swift decodes Kotlin output". It is met in the equivalent direction: Kotlin's encoding of each phone→Mac message must equal Swift's encoding as a JSON tree. That pins the same bytes without a second toolchain loop.

- [ ] **Step: Generate and commit** ("Add session message samples from v3.7.7").

---

## Android Skeleton

### Task: Gradle Project

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle wrapper
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` (`allowBackup="false"`, `INTERNET` only), `app/src/main/java/dev/bugobliterator/itermbuddy/MainActivity.kt`
- Create: `scripts/cloud-setup.sh`. It installs JDK 17, Android cmdline-tools, the platform and build-tools into `$HOME/android-sdk`, and is safe to re-run.
- Create: `app/src/test/java/dev/bugobliterator/itermbuddy/Fixtures.kt`, `FixturesTest.kt`

**Interfaces:**
- Produces: `Fixtures.read(path: String): String`. It reads from the repo's `fixtures/` dir via `systemProperty("fixtures.dir", rootProject.file("fixtures").path)` on the test task.

Unit tests: `tasks.withType<Test> { maxParallelForks = Runtime.getRuntime().availableProcessors() }`.

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
- Produces: `sealed interface PairingCodeResult { data class Ok(val code: PairingCode); data class Rejected(val reason: String) }` and `fun parsePairingCode(qr: String): PairingCodeResult`. A valid `v=2` QR is rejected with "This QR uses iTerm2's resolver mode. Set companionResolverURL empty in iTerm2's Advanced settings."
- Produces: `fun roomName(code: PairingCode): String` (64 lowercase hex)

- [ ] **Step: Failing tests.**
  - `canonicalMatchesVectors`.
  - `pairingCodesMatchAndroidPolicy`. For `androidAccepts` entries, the result is `Ok` and all three fields equal upstream's. Otherwise the result is `Rejected`, with `androidReason` when given.
  - `roomNamesMatchVectors`, `prologuesMatchVectors`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*PairingCodeTest*'`. FAIL.
- [ ] **Step: Implement.** Parse with `java.net.URI`. The canonical origin is `"https://" + host + (if port given: ":" + port)`, case preserved. Hash with `MessageDigest` `SHA-256`.
- [ ] **Step: Run.** PASS.
- [ ] **Step: Commit** ("Add QR pairing code parsing and room name").

### Task: SAS and Join Signature

**Files:**
- Create: `core/pairing/Sas.kt`, `core/relay/JoinSigner.kt`
- Test: `core/pairing/SasTest.kt`, `core/relay/JoinSignerTest.kt`

**Interfaces:**
- Produces: `fun sasCode(handshakeHash: ByteArray): String` (6 digits)
- Produces: `enum class JoinRole(val byte: Byte) { MAC(0x01), PHONE(0x02) }`
- Produces: `class JoinSigner(roomSecret: ByteArray, role: JoinRole = JoinRole.PHONE) { val verifier: ByteArray; fun sign(nonce: ByteArray, roomName: String, origin: String): ByteArray }`. The seed is `HKDF-SHA256(roomSecret, salt=empty, info="relay-auth-ed25519", 32)` via BouncyCastle `HKDFBytesGenerator`; the key is `Ed25519PrivateKeyParameters(seed)`.

- [ ] **Step: Failing tests** `sasMatchesVectors`, `joinsMatchVectorsForBothRoles`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add SAS and relay join signature").

### Task: Noise Channel

**Files:**
- Create: `core/noise/NoiseInitiator.kt`, `core/noise/NoiseResponder.kt`, `core/noise/NoiseChannel.kt`
- Test: `core/noise/NoiseVectorsTest.kt`, `core/noise/NoiseChannelTest.kt`

**Interfaces:**
- Produces: `class NoiseInitiator(responderStatic: ByteArray, localStaticPrivate: ByteArray, prologue: ByteArray) { fun writeMessage1(): ByteArray; fun readMessage2(msg: ByteArray); fun writeMessage3(): ByteArray; val handshakeHash: ByteArray; fun split(): NoiseChannel }`, and `NoiseResponder` with the mirror methods. The fake Mac uses the responder.
- Produces: `class NoiseChannel { fun encryptMessage(plaintext: ByteArray): List<ByteArray>; fun decryptFrame(frame: ByteArray): ByteArray? }`. Encryption chunks to `[cont] || chunk` with chunk ≤ 65518 and returns one ciphertext per WebSocket frame. `decryptFrame` returns the reassembled plaintext when `cont == 0`, else null. Callers serialise sends.
- Produces: `fun newStaticKeyPair(): Pair<ByteArray /*private*/, ByteArray /*public*/>`

Use `HandshakeState("Noise_XK_25519_ChaChaPoly_BLAKE2s", INITIATOR)` from the `org.signal.forks` artifact. Read `getHandshakeHash()` before `split()` and use `getFixedEphemeralKey()` in tests. Check those three APIs and BLAKE2s exist in the fork before writing code. If the fork lacks any, vendor rweather's MIT noise-java source under `app/src/main/java/com/southernstorm/noise/` with the commit recorded in a `README`.

- [ ] **Step: Failing tests.**
  - `cacophonyXkVectorsPass`: fixed ephemerals, every message and the handshake hash.
  - `channelRoundTripsAcrossChunkBoundary`: 0, 65518, 65519 and 200 000 bytes decrypt equal, in 1, 1, 2 and 4 frames.
  - `handshakeFrameSizesAre48_48_64`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add Noise XK channel with chunking").

### Task: Wire Envelope and Session Messages

**Files:**
- Create: `core/wire/Envelope.kt`, `core/wire/SessionMessages.kt`, `core/wire/ProtocolRevision.kt`
- Test: `core/wire/SessionMessagesTest.kt`

**Interfaces:**
- Produces: `sealed interface PhoneMessage { fun payload(): JsonObject }` with `Hello(revision: Int, minimumPeer: Int)`, `RelayRoomSecret(secret: ByteArray)`, `Ping`, `Unpairing`.
- Produces: `sealed interface MacMessage` with `HelloReply(revision: Int, minimumPeer: Int, raw: JsonObject)`, `RelayRoomSecretStored`, `Pong`, `Unpaired`, `Error(raw: JsonObject)`, `Unsupported(case: String, raw: JsonObject)`. Unknown cases become `Unsupported`; a known case with a bad body throws `WireDecodeException`.
- Produces: `fun encodeEnvelope(message: PhoneMessage, requestID: Long?): ByteArray` and `fun decodeEnvelope(bytes: ByteArray): Pair<Long?, MacMessage>`. A first byte `0x01` is a media frame, returned as `MacMessage.Unsupported("media", …)` for now.
- Produces: `fun decodePairingConfirmation(bytes: ByteArray): Boolean?`, which reads the bare confirmation (no envelope). It returns true only for exactly `{"pairing":"accepted"}`, false for `rejected`, and null otherwise.
- Produces: `object ProtocolRevision { const val REVISION = 11; const val MINIMUM_PEER = 11 }`, with a comment naming the Global Constraint.

Swift enum coding rules are in *notes* → Encoding: `_0` for unlabelled values, `{}` for empty cases, nil omitted, `Data` as standard base64.

- [ ] **Step: Failing tests.**
  - `phoneSamplesEncodeExactly` (JSON-tree equality, key order ignored).
  - `macSamplesDecode`.
  - `unknownCaseIsUnsupported`, `badBodyThrows`.
  - `confirmationFixturesDecode` (`confirmation/accepted.json` gives true, `rejected.json` gives false) and `confirmationRejectsNearMisses` (`{"pairing":"Accepted"}`, extra keys, enveloped form: all null).
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add wire envelope and session messages").

### Task: Relay Connection

**Files:**
- Create: `core/relay/RelayEndpoints.kt`, `core/relay/RelayConnection.kt`, `core/relay/ReconnectBackoff.kt`, `core/relay/RelayRegistrar.kt`
- Test: `core/relay/ReconnectBackoffTest.kt`, `core/relay/RelayConnectionTest.kt` (with `MockWebServer`)

**Interfaces:**
- Produces: `data class RelayEndpoints(val origin: String, val webSocketUrl: String, val httpBaseUrl: String)` and `fun RelayEndpoints.Companion.forOrigin(origin: String)`. In production both URLs derive from `origin`. Tests point both at `127.0.0.1:<port>` while signatures keep `origin`.
- Produces: `sealed interface Proof { object Fresh; data class Signed(val signer: JoinSigner) }`
- Produces: `class RelayConnection(client: OkHttpClient, endpoints: RelayEndpoints, roomName: String)`
  - `suspend fun admit(proof: Proof): AdmissionResult`, where `AdmissionResult` is one of:
    - `Admitted(registrationToken: String)`
    - `Rejected(error: String)`
    - `Closed(code: Int, reason: String)`
    - `HttpFailure(status: Int)`
    - `Unreachable(cause: String)`: DNS, TCP or TLS failure before any HTTP response
  - `suspend fun send(frame: ByteArray)`, `val incoming: ReceiveChannel<ByteArray>`, `fun close()`
  - Headers: only `x-relay-room` and `User-Agent: iTermBuddyAndroid`; `pingInterval(15, SECONDS)`. A text frame after admission is fatal.
- Produces: `fun nextDelayMillis(attempt: Int, lastCloseReason: String?, random: Random): Long`. Full jitter is `rand(0, if attempt == 1 then 3 else min(30, 2^attempt))` seconds. "displaced" gives 60 s and "daily quota exceeded" gives 30 min.
- Produces: `fun isFatalHttp(status: Int): Boolean`. True for 403, 413 and other 4xx; false for 429 and 5xx.
- Produces: `class RelayRegistrar(client: OkHttpClient, endpoints: RelayEndpoints) { suspend fun register(roomName: String, registrationToken: String, verifier: ByteArray): RegisterResult }`, where `RegisterResult` is `Registered` (2xx, or 403 "already registered"), `Refused(status: Int, body: String)` or `TransportError(cause: String)`.

- [ ] **Step: Failing tests.**
  - `backoffBoundsPerAttempt` checks attempts 1, 2, 5 and 9 with a seeded `Random`.
  - `displacedWaitsSixtySeconds`, `quotaWaitsThirtyMinutes`, `http403IsFatal`, `http429Retries`.
  - `noOriginHeaderSent`.
  - `freshProofSendsEmptyObject`, `signedProofSendsSig`.
  - `textFrameAfterAdmissionIsFatal`.
  - `unreachableHostIsUnreachable`.
  - `registerTreatsAlreadyRegisteredAsSuccess`, `registerReportsRefusal`, `registerPostsToHttpBaseUrl`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add relay connection, backoff and registrar").

### Task: Pairing Store

**Files:**
- Create: `store/PairingStore.kt`, `store/KeystorePairingStore.kt`
- Test: `store/InMemoryPairingStoreTest.kt`

**Interfaces:**
- Produces: `enum class PairingStage { PENDING_CONFIRMATION, CONFIRMED, REGISTERED }`
- Produces: `data class PairingRecord(val code: PairingCode, val phoneStaticPrivate: ByteArray, val roomSecret: ByteArray, val stage: PairingStage)`
- Produces: `interface PairingStore { fun load(): PairingRecord?; fun save(record: PairingRecord); fun clear() }`, `class InMemoryPairingStore : PairingStore` (tests), and `class KeystorePairingStore(context: Context) : PairingStore`.
  - The Keystore store encrypts a JSON blob with an AES-256-GCM key in `AndroidKeyStore` under the alias `itermbuddy-pairing`, and keeps it in a private SharedPreferences file.
  - `save` uses `commit()`, not `apply()`, so a checkpoint survives an immediate kill.
  - If decryption fails (key lost, restored blob), `load` clears the store and returns null.
- Produces: `fun beginFreshPairing(store: PairingStore, code: PairingCode): PairingRecord`. It clears the store, then mints a new static key pair and a new 32-byte room secret at `PENDING_CONFIRMATION`, and saves it.

- [ ] **Step: Failing tests.**
  - `freshPairingReplacesEveryOldSecret`: after a `REGISTERED` record, `beginFreshPairing` with a different code changes the static key, room secret and code, and resets the stage.
  - `roundTrip`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS. `KeystorePairingStore`, including the decrypt-failure path, is exercised in the on-device check.
- [ ] **Step: Commit** ("Add pairing store").

### Task: Companion Client

The connect flow, owned later by the foreground service. No UI in this task.

**Files:**
- Create: `core/CompanionClient.kt`
- Test: `core/CompanionClientTest.kt` (fake relay in-process), `fakemac/FakeMac.kt`, `fakemac/RelayEndToEndTest.kt`
- Create: `scripts/relay-it.sh`. It clones the relay at `b8aa2f9` into a temp dir, writes the same `allowBuilds` `pnpm-workspace.yaml`, runs `pnpm install --prod`, checks `better-sqlite3` loads, and runs `./gradlew testDebugUnitTest --tests '*RelayEndToEndTest*'` with `RELAY_DIR` set.

**Interfaces:**
- Consumes: everything above.
- Produces: `data class ClientTimeouts(val handshakeFresh: Duration = 15.s, val handshakeReconnect: Duration = 6.s, val confirmation: Duration = 180.s, val freshMacWait: Duration = 120.s, val rpc: Duration = 15.s, val badSignatureLimit: Int = 3, val roomSecretErrorLimit: Int = 3)`. `rpc` bounds each request/reply, matching upstream's 15 s `hello` race.
- Produces: `class CompanionClient(store: PairingStore, http: OkHttpClient, scope: CoroutineScope, endpointsFor: (String) -> RelayEndpoints = RelayEndpoints::forOrigin, timeouts: ClientTimeouts = ClientTimeouts())`
  - `val state: StateFlow<ClientState>`, where `ClientState` is `Unpaired`, `Connecting`, `WaitingForMac(reason: WaitReason)` (`WaitReason` is `MAC_OFFLINE` or `RELAY_UNREACHABLE`), `ShowCode(code: String)`, `Connected(macRevision: Int)` or `Failed(message: String, rescanNeeded: Boolean)`.
  - `fun pair(code: PairingCode)` and `fun start()`. `start()` resumes a stored pairing. A stored `PENDING_CONFIRMATION` record is kept and goes through **pending recovery** (flow step 3a), because the Mac may already have pinned it.
  - `fun cancel()`: stops the current attempt. A fresh pairing is wiped (`Unpaired`); a stored pairing stays stored and goes idle until `start()`.
  - `suspend fun unpair()`: cancels reconnect work, sends `Unpairing` without waiting for a reply, then clears the store and goes to `Unpaired`, even if the send fails.
  - `suspend fun request(message: PhoneMessage): MacMessage`, `fun notify(message: PhoneMessage)` (fire-and-forget), `val events: SharedFlow<MacMessage>` for unsolicited messages.
- Produces: `class FakeMac(endpoints: RelayEndpoints, code: PairingCode, macStaticPrivate: ByteArray)` in test sources.
  - Before it has a room secret, it admits as `{"v":1,"role":"mac"}` with proof `{}`. After `relayRoomSecret` it keeps the secret and signs with `JoinSigner(secret, JoinRole.MAC)`.
  - It runs the Noise responder and stays silent until a simulated code entry (`fakeMac.enterCode(accept: Boolean)`), then sends the bare confirmation, accepted or rejected, as the real Mac does. Frames that arrive before the verdict are queued and handled once its link starts, matching the plugin's queueing. On reconnects it sends no confirmation.
  - It answers `hello`, `relayRoomSecret` and `ping`, records `unpairing`, can send `unpaired`, and can drop its socket.

Flow (*notes* → Relay admission, Noise, After pairing):
1. **Admit.** Use `Fresh` unless `stage == REGISTERED`, otherwise `Signed`.
   - `mac offline` → `WaitingForMac(MAC_OFFLINE)`; `Unreachable` → `WaitingForMac(RELAY_UNREACHABLE)`. Retry with backoff. A fresh pairing stops after `freshMacWait` with `Failed("iTerm2 isn't showing that pairing code any more. Open pairing on the Mac and scan again.", rescanNeeded=true)`. A stored pairing retries indefinitely.
   - Self-heal: a fresh admit rejected "signature required" → treat as `REGISTERED` and retry signed. A signed admit rejected "ticket required" → treat as `CONFIRMED` and retry fresh.
   - Any other `Rejected` or `Closed` (for example "rate limited", "slot occupied" or "admission timeout"), or an `HttpFailure` where `isFatalHttp` is false (429, 5xx): treat as `mac offline`. Retry with backoff; a fresh pairing is bounded by `freshMacWait`.
   - `HttpFailure` where `isFatalHttp` is true (403, 413, other 4xx): `Failed("The relay refused the connection (HTTP <status>): <body>", rescanNeeded=false)`, with no retry. This catches a `tailscale serve` misroute or a stray `Origin` header.
   - `badSignatureLimit` "bad signature" rejections in a row → `Failed("The relay refused this phone's signature. Check the relay address matches iTerm2's companionRelayOrigin.", rescanNeeded=true)`.
2. **Noise handshake.** The timeout is `handshakeFresh` or `handshakeReconnect`.
3. **Fresh only: confirmation.** Show `ShowCode(sasCode(h))` and wait up to `confirmation` for it. On "accepted", **save `CONFIRMED` immediately**: the Mac has now pinned this phone. On rejection or timeout, clear the store and go to `Failed(rescanNeeded=true)`.
3a. **Pending recovery** (`start()` on a `PENDING_CONFIRMATION` record). The Mac may have sent "accepted" and pinned this phone (`CompanionPairingController.swift:1472-1473, 1516-1531`) before Android saved `CONFIRMED`. In both possible Mac states it sends nothing first:
   - a pinned Mac re-parks, handshakes, checks the pinned key and waits for `hello` (`CompanionParkSupervisor.swift:431-437`);
   - a Mac in SAS mode waits for the code to be typed (`:1034-1053, 1470-1473`).

   So after a fresh admit and the handshake with the stored static key, do both at once: show `ShowCode(sasCode(h))` **and** send `hello`. The plugin queues frames that arrive before the Mac reads them (`iTermCompanionPlugin.js:52-53, 68-69`), so an early `hello` is answered once the link starts. Then wait up to `confirmation` (an exception to the step 4–5 `rpc` rule) for the first Mac frame:
   - **`HelloReply`:** the Mac had pinned this identity. Dismiss the code, save `CONFIRMED`, apply step 4's version checks to that reply, then continue from step 5.
   - **Confirmation "accepted":** the Mac was in SAS mode. Save `CONFIRMED`; the queued `hello`'s reply that follows completes step 4.
   - **Confirmation "rejected", or the `confirmation` timeout:** wipe, as in step 3.
   - **`mac offline` or unreachable past `freshMacWait`** (for example, the Mac is still holding the old code dialog and so isn't parked): clear the store and go to `Failed(rescanNeeded=true)`.

   Pending credentials are only discarded on an explicit rejection, a timeout with the Mac present, or that deadline.
4. **`hello` request.** If our revision < the Mac's `minimumPeer`, fail "Update the app". If the Mac's revision < `MINIMUM_PEER`, fail "Update iTerm2".
5. **`relayRoomSecret`**, then wait for `relayRoomSecretStored`.

   Every request in steps 4–5 is bounded by `rpc`. A timeout, an `error` reply, an unexpected reply or a closed connection all close this attempt, keep the stored stage, and retry with backoff. WebSocket pongs prove only the relay link, not that the Mac is answering. An `error` reply to `relayRoomSecret` (the Mac failed to persist it, `CompanionLink.swift:429-453`) `roomSecretErrorLimit` times in a row ends in `Failed("Your Mac couldn't save the pairing. Check iTerm2, then reconnect.", rescanNeeded=false)`.
6. **`register` on every connect**, with this connection's token. It is idempotent: an established room answers 403 "already registered", which counts as success. This also re-locks a relay that lost its state.
   - `Registered` → save `REGISTERED` if not already.
   - `Refused` or `TransportError` → keep the stage, stay connected, and retry `register` on the next connect. Never save `REGISTERED` on failure.
7. **`Connected`.** On socket loss, wait `nextDelayMillis` and go back to step 1 as a reconnect. On unsolicited `Unpaired`, run the same teardown as `unpair()` without sending, then go to `Failed("Unpaired on the Mac", rescanNeeded=true)`.

- [ ] **Step: Failing tests (in-process fake relay, virtual time).**
  - Pairing basics: `freshPairingReachesConnectedAndRegisters`.
  - Mac not there:
    - `storedPairingWaitsForMacOffline`, `storedPairingWaitsWhenRelayUnreachable`.
    - `freshPairingGivesUpAfterQrExpiry`: `mac offline` for longer than `freshMacWait` gives `Failed(rescanNeeded=true)`.
    - `cancelDuringFreshWaitWipes`, `cancelDuringStoredWaitKeepsPairing`.
  - Confirmation: `rejectedCodeWipesStoreAndAsksRescan`, `confirmationTimeoutWipesStore`.
  - Process death:
    - `killAfterMacAcceptBeforeCheckpointRecovers`: the FakeMac has accepted and pinned, and the store is still `PENDING_CONFIRMATION`. start() recovers with the same static key, replaces `ShowCode` within the `hello` round trip, saves `CONFIRMED` and reaches `Connected`.
    - `pendingRecoveryWhileMacStillInSasShowsCode`: the FakeMac is still in SAS mode and silent until simulated code entry. start() shows the code at once. After `fakeMac.enterCode(accept = true)` it reaches `Connected`, with the queued `hello` answered after the verdict.
    - `pendingRecoveryWithMacGoneClears`: `mac offline` past `freshMacWait` clears the store.
    - `killAfterConfirmationReconnectsWithoutCode`: start() on `CONFIRMED` connects fresh with no `ShowCode`, registers, and reaches `REGISTERED`.
    - `lostRegisterResponseSelfHeals`: the server registered but the phone stayed `CONFIRMED`. The fresh admit gets "signature required", the phone retries signed and reaches `Connected`.
  - Registration:
    - `refusedRegistrationIsNotSaved`: the stage stays `CONFIRMED`, and the next connect registers again.
    - `relayLostVerifierIsRelocked`: the phone is `REGISTERED` and the relay is empty; the signed admit succeeds open and the phone registers again.
  - Self-heal and failures:
    - `signatureRequiredSelfHealsToSigned`, `ticketRequiredSelfHealsToFresh`.
    - `badSignatureSurfacesAfterThreeRejections`.
    - `fatalHttpFailsWithoutRetry`, `unknownRejectionRetries`.
  - Reconnects: `droppedSocketReconnectsSigned`, `displacedWaitsBeforeReconnect`.
  - Version: `macTooOldFailsWithUpdateITerm2`.
  - Stalled Mac:
    - `helloTimeoutRetriesKeepingPairing`: the FakeMac stops answering while pongs continue. The attempt closes after `rpc`, the stage is kept, and the phone retries.
    - `roomSecretErrorRetriesThenFails`.
  - Unpair:
    - `unpairSendsFarewellAndClearsWithoutReply`.
    - `unpairClearsEvenWhenOffline`.
    - `macInitiatedUnpairTearsDown`.
- [ ] **Step: Run.** FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Failing end-to-end test** `RelayEndToEndTest.pairsThroughRealRelay`, skipped via `Assume` when `RELAY_DIR` is unset.
  - It starts `node bin/relay.js` with `RELAY_PORT=0`, a temp `RELAY_DB`, `ATTEST_REQUIRED=false` and `RELAY_ORIGIN=https://relay.test`, and parses the port from the `listening on` line. Endpoints point both URLs at that port, with origin `https://relay.test`.
  - Pairing: `FakeMac` and the client pair through the relay, and `/register` reaches the same relay.
  - Restart: the relay restarts on the same DB, and both sides reconnect signed.
  - Lost state: the relay restarts on an empty DB, and the client re-registers.
  - `ping`/`pong` round-trips throughout.
- [ ] **Step: Run** `scripts/relay-it.sh`. PASS.
- [ ] **Step: Commit** ("Add companion client connect flow and relay end-to-end test").

### Task: Pairing Screens

**Files:**
- Create: `ui/PairingScreen.kt`, `ui/ConnectionStatus.kt`, `CompanionApp.kt` (Application holding the single `CompanionClient`)
- Modify: `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` (code scanner, Compose)

**Interfaces:**
- Consumes: `CompanionClient.state`, `pair`, `start`, `cancel`, `unpair`; `parsePairingCode`; `KeystorePairingStore`.

Screens, one per `ClientState`:
- `Unpaired`: a **Scan to pair** button.
  - It opens `GmsBarcodeScanning.getClient(context)` restricted to `FORMAT_QR_CODE`, so no camera permission is needed.
  - The scanned text goes to `parsePairingCode`; a `Rejected` result shows its reason.
  - If the scanner module is still downloading on first use (`MlKitException.CODE_SCANNER_UNAVAILABLE`; check the constant names against the `play-services-code-scanner` reference), show "The scanner is still installing; try again in a moment" with a **Try again** button. Backing out of the scanner (`addOnCanceledListener`, or `CODE_SCANNER_CANCELLED`) returns quietly to the button with no error.
- `Connecting`: "Connecting…", with **Cancel**.
- `WaitingForMac(MAC_OFFLINE)`: "Waiting for your Mac. Is iTerm2 running?", with **Cancel**.
- `WaitingForMac(RELAY_UNREACHABLE)`: "Can't reach the relay. Is Tailscale connected on this phone, and is the Mac awake?", with **Cancel**.
- `ShowCode`: "Type this code on your Mac", with the code as `482 913` (3+3, monospace, large) and **Cancel**.
- `Connected`: "Connected to iTerm2", with **Unpair**, which calls `client.unpair()`.
- `Failed`: the message, plus **Scan again** when `rescanNeeded`.

On launch the app calls `start()`. Background connection is not part of this milestone; the foreground service comes in Notifications.

- [ ] **Step: Build** with `./gradlew lint assembleDebug` in the cloud, and push the APK as a release asset or on a branch the Mac can pull.
- [ ] **Step: On-device check (local, owner).** Prerequisites: Tailscale is connected on the Pixel and the relay is running.
  1. `adb install -r app-debug.apk`, start pairing in iTerm2, scan, and type the code on the Mac. Expected: **Connected to iTerm2**.
  2. Force-stop the app and reopen it. Expected: Connected without scanning.
  3. Quit iTerm2. Expected: **Waiting for your Mac**. Relaunch iTerm2. Expected: Connected.
  4. Unpair on the phone. Expected: the phone shows Scan to pair, and iTerm2 shows the phone as unpaired.
  5. Pair again, then type a wrong code three times on the Mac, or cancel the Mac's code dialog. One wrong code only shows "That code doesn't match" on the Mac (`CompanionPairingController.swift:1038-1053`). Expected: a failure with **Scan again** on the phone.
  6. Scan a QR, then wait more than 2 minutes with iTerm2's pairing window closed. Expected: **Scan again**, not an endless wait.
  7. Grep the relay log for `bad signature`. Expected: none.

  Record the results in `docs/reports/pairing-on-device.md`.
- [ ] **Step: Commit** ("Add pairing screens").

---

## After this plan

Report to the owner, the tracker and `master-session`, then ask before starting Agent Chat. The Agent Chat plan extends the harness with chat, approval and alert samples, and adds the debug frame recorder that captures real Mac traffic. The Notifications plan must restate the placeholder push token trade-off: while the phone is away, the Mac posts content-free pushes with a placeholder token to iTerm2's public push relay, which rejects them.
