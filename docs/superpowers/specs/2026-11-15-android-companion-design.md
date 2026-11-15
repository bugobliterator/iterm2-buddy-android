# iTerm2 Buddy for Android: design

Date: 2026-11-15
Status: draft for review

## Intent

An Android app for the owner's daily Android phone that does what the iOS
iTerm2 Buddy app does, with **talking to the AI agents running in iTerm2 on the
Mac** as the main feature. The owner wants to step away from the Mac and keep
working with agents from the phone: read replies as they stream, answer
approval prompts, start new chats, and get notified when an agent finishes or
needs a decision. The live terminal comes after that.

This is a personal tool, not a Play Store product. Polish, onboarding for
strangers, and store compliance are out of scope.

## Goals

1. Pair the phone with iTerm2 by scanning the Mac's QR code and confirming a
   6-digit code, then reconnect automatically after that.
2. Agent chat: list chats, open one, see history, watch agent replies stream in,
   send messages (with @-mentions of sessions and workgroups), create chats
   (orchestrator or bound to a session), delete and mute chats, truncate a chat.
3. Approvals: answer every in-chat approval the iOS app supports (remote
   command allow/deny once/always, session picker, link offer, workgroup
   permission, enable orchestration, revoke orchestration) and the Mac's modal
   alerts (buttons, inputs, "don't ask again").
4. Notifications while the app is in the background: agent turn finished,
   approval needed, Mac modal alert, terminal alerts.
5. Later: session tree, live terminal (HEVC video), key bar and composer.

## Non-goals

- Pairing through iTerm2's public relays (`relay1`/`relay2.iterm2.com`). They
  require Apple App Attest for fresh pairings, which Android cannot produce.
- Push through Apple's push service, or a new push path (FCM) on the Mac.
  Background delivery comes from a persistent connection instead.
- Voice dictation (on-device Whisper on iOS). Android's keyboard mic covers it.
- Browser-session streaming (protocol revision 15), video of browser panes.
- iPad/tablet layouts, multiple paired Macs, Play Store release.

## Anti-goals (failure even if it "works")

- Any change that weakens end-to-end encryption, or a relay that can read
  content. The relay must only ever see Noise ciphertext.
- Exposing the relay to the public internet. It is reachable on the tailnet
  only.
- Sending anything the Mac could misread: a malformed message that iTerm2
  decodes into the wrong action (e.g. a wrong approval decision) is worse than
  a crash. Everything the phone sends is checked against golden samples.
- Notifications that arrive late. The owner chose **instant notifications over
  battery**: the connection stays open; battery use is measured on the phone
  for a day and reported, not traded away for latency.

## Constraints

- **Wire compatibility with iTerm2 on the Mac**, byte for byte where signed or
  hashed, JSON-shape compatible elsewhere. The Mac side is not changed.
- **Target iTerm2: 3.7.7** (installed at `~/Applications/iTerm.app`; its binary
  contains `companionRelayOrigin`, `turnLifecycle`, `macStatusChanged`,
  `syncSince`, `answerModalAlert`, `remoteCommandDecision`; no browser
  stream). The port follows upstream tag `v3.7.7` (`e0025340`). The protocol
  notes were read from master; the builder diffs the Companion paths between
  master and `v3.7.7` and follows the tag where they differ. The revision to
  advertise comes from the Mac's `hello` reply.
- **Target phone: Google Pixel, Android 15+.** The foreground service needs a
  type that is not time-capped on Android 15 (`dataSync` is capped at 6 h/day;
  `remoteMessaging` or `specialUse` are not). Battery-optimization exemption is
  requested once.
- **iTerm2 settings** (Advanced): `companionRelayOrigin` =
  `https://<mac>.<tailnet>.ts.net` (exact string), `companionResolverURL` =
  empty (direct mode).
- **Relay**: self-hosted `gnachman/iterm2-companion-relay` (Node 20) on the Mac,
  `ATTEST_REQUIRED=false`, `RELAY_ORIGIN` = the same exact string, published on
  the tailnet with `tailscale serve` (real Let's Encrypt certificate).
- The origin string must be byte-identical in three places: iTerm2's
  `companionRelayOrigin`, the relay's `RELAY_ORIGIN`, and what the phone signs.
  A mismatch connects fine and then fails every signed join with "bad
  signature".
- The phone has the Tailscale app connected (it takes Android's single VPN
  slot).
- The Mac's tailnet name is `relay.example.ts.net`, so
  the origin is `https://relay.example.ts.net`. HTTPS
  certificates are currently **off** for the tailnet (`CertDomains` empty);
  the owner enables them once in the Tailscale admin console (DNS → HTTPS
  Certificates) before Relay Setup.
- Node on the Mac is v24 (relay docs ask for Node 20 LTS; 24 is treated as
  compatible unless the relay's tests say otherwise).
- License GPL-2.0 (port of iTerm2 code).
- Builds, SDK installs, tests and implementer agents run in cloud sessions;
  the code lives in `github.com/bugobliterator/iterm2-buddy-android`. Only
  things that need local hardware (the Mac's iTerm2 and relay, the phone over
  adb) run on the Mac.

## Approaches

| Approach | Trade-off | Decision |
|---|---|---|
| **Kotlin port** (Jetpack Compose UI, Kotlin core, noise-java, OkHttp, MediaCodec) | Normal Android tooling, easy to debug; keeping in step with upstream protocol changes is manual | **Chosen** by the owner |
| Swift CompanionCore compiled for Android, Kotlin UI over JNI | Compatible by construction, easier upstream sync; young toolchain, CryptoKit swap, JNI glue, harder debugging | Rejected |
| Flutter / React Native | Core still needs a full rewrite; video and keyboard need native code anyway | Rejected |

Network to reach the relay: **Tailscale** (chosen) over Tailcat, because
`tailscale serve` gives the relay a valid HTTPS origin and Tailscale has an
official Android app; Tailcat has no Android support and would need TLS
workarounds.

Build order (owner's choice): **pairing, then agent chat**, then approvals and
Mac alerts, then notifications, then the terminal.

## Architecture

```
Android app (Kotlin, single module)
├─ ui/          Compose: Pairing, Home (chat list), Conversation, Create,
│               Workgroup, MacAlert card, Settings; later Sessions, Terminal
├─ core/
│   ├─ pairing   QR parse, room name, SAS, persisted pairing state
│   ├─ relay     OkHttp WebSocket admission, /register, backoff, keepalive
│   ├─ noise     Noise_XK_25519_ChaChaPoly_BLAKE2s via noise-java, chunking
│   └─ wire      envelope + message types with Swift-compatible JSON
├─ service/     foreground service owning the one live connection
└─ video/       (later) MediaCodec HEVC decoder
```

One connection, owned by a foreground service, used by both the UI and the
notifier. The UI binds to the service and observes state (Kotlin flows). The
Mac suppresses its own pushes while an interactive phone is connected, so the
always-on connection is the notification path.

## Protocol summary (from reading the iOS and relay sources)

Detailed references with file:line pointers are in
`docs/superpowers/specs/2026-11-15-protocol-notes.md` (the two research
reports, committed with this doc).

**QR code**: `iterm2://pair?v=1&proto=Noise_XK_25519_ChaChaPoly_BLAKE2s&rs=<b64url 32B Mac static key>&pid=<id>&relay=https://host[:port]`.
Reject `v=2` (resolver mode) with a clear message. Origin is canonicalised
exactly as `PairingCode.canonicalRelayOrigin` (keep port, no lowercasing).

**Room name**: `hexLower(SHA256(canonical("iterm2-room", [rs, utf8(pid)])))`,
where `canonical(domain, fields) = u32be(len(domain)) || domain || Σ u32be(len(f)) || f`.

**Relay admission** (text frames, `wss://host/`, header `x-relay-room`, no
`Origin` header): Hello `{"v":1,"role":"phone"}` → Challenge `{"nonce":b64}` →
Proof `{}` (fresh) or `{"sig":b64}` (established) → Result
`{"ok":true,"registrationToken":...}` or `{"ok":false,"error":...}` + close 1008.
Signature: Ed25519 key from `HKDF-SHA256(roomSecret, salt="", info="relay-auth-ed25519", 32)`,
transcript `canonical("iterm2-relay-join", [0x01, 0x02, nonce, utf8(room), utf8(origin)])`.
"mac offline" → retry. Self-heal rules as in AppModel (switch fresh↔signed on
"signature required" / "ticket required").

**Noise**: XK, phone is initiator, prologue `"iterm2-companion/v1/pid:" + pid`,
empty handshake payloads, one binary WS frame per Noise message. SAS:
`HKDF-SHA256(h, "", "iterm2-sas-v1", 8)` → big-endian u64 mod 1,000,000, 6
digits; the phone shows it, the user types it on the Mac. Fresh pairing: first
decrypted frame `{"pairing":"accepted"|"rejected"}` (wait up to 180 s).
Transport chunking: plaintext `[cont byte] || chunk`, chunk ≤ 65518, strictly
serialised sends.

**After pairing**: phone mints a 32-byte room secret, sends
`relayRoomSecret{_0}` (every connect), waits for `relayRoomSecretStored`, then
`POST /register {registrationToken, verifier}` (2xx or 403 "already
registered" = success) and stores the "registered room" marker.

**App frames**: first byte `0x01` = media, otherwise JSON
`{"requestID"?:u64,"payload":{"<case>":{...}}}` using Swift's synthesized enum
coding (`_0` for unlabelled values, `{}` for empty cases, nil omitted, dates
in ms, Data as standard base64, UUIDs uppercase). First RPC is
`hello{revision, minimumPeer}`.

**Chat messages** (full catalogue in the notes): `listChatsAndSessions`,
`chatListChanged`, `subscribe`/`history`, `delivery` (streaming = first full
Message, then `append`/`appendAttachment` with the same uniqueID),
`typingStatus`, `turnLifecycle`, `publish`, `createChat`, `deleteChat`,
`setChatMuted`, `deleteMessages`/`messagesRemoved`, `resolveMentions`,
`fetchAutoProvideConsent`/`grantAutoProvideConsent`, `fetchSessionTree`,
`fetchWorkgroupInfo`, `aiAvailabilityChanged`.

**Approvals**: `remoteCommandDecision`, `selectSessionResponse`,
`linkSession`, and `publish` of `userCommand` responses for workgroup /
orchestration requests. Mac modal alerts: `macStatusChanged` →
`answerModalAlert` (only the last alert is answerable).

**Notifications**: `subscribe` to every chat; notify on `turnLifecycle(ended)`
(reply text accumulated), immediately on approval-request deliveries, and on
`macStatusChanged` with alerts. Terminal alerts have no live event: poll
`syncSince` with persisted `messageSeq`/`alertSeq` floors on connect, after
`turnLifecycle`/`chatListChanged`, and on a timer. Send
`pushStatus{authorization:"authorized", token:<placeholder>, relaySecret:<random 32B>, sandbox:false}`
so the Mac stores alerts; answer `requestNotificationPermission`.

## What the owner experiences

- **Pairing**: iTerm2 shows a QR code. On the phone: "Scan to pair" → camera →
  the phone shows `482 913`; the owner types it on the Mac; the phone moves to
  the chat list.
- **Chat**: Home lists "Fix flaky test — Running tests now…". Tapping it shows
  the conversation; the agent's reply streams in word by word; a typing
  indicator shows while the turn runs. The owner types "also check the lint
  errors in @dev-server" and taps send.
- **Approval**: a card in the conversation reads "Run `rm -rf build/`?" with
  **Allow once / Always allow / Deny once / Always deny**. The Mac proceeds the
  moment the owner taps one.
- **Notification** (phone in pocket): "Fix flaky test: All 214 tests pass. The
  flake was a shared temp dir…" Tapping opens the conversation. An approval
  request notifies immediately: "Fix flaky test needs approval: run
  `rm -rf build/`".
- **Mac alert**: iTerm2 pops "Paste 3 lines into session?" on the Mac; the
  phone shows a notification and, when open, a card with the same buttons.

## Phases (named by the work)

In order:

- **Relay Setup**: install and run the relay on the Mac under launchd,
  `tailscale serve` it, set the two iTerm2 advanced settings, verify with a
  small script that admission answers. Local (it is the Mac's own service).
- **Wire Fixtures**: a small Swift harness built from iTerm2 `v3.7.7` sources
  (CompanionCore, plus the shared message and model types compiled with the
  iOS app's own shims in `Companion/iTerm2Companion/Satellites/`) that
  (a) emits crypto test vectors with fixed keys: room name, SAS, join
  transcript and signature, Noise handshake; (b) encodes sample messages
  of every type the Mac sends; (c) decodes JSON the Kotlin code produces for
  every message the phone sends and checks it round-trips to the expected
  values. Vectors and samples are committed as fixtures. Later, a debug
  build of the Android app records decrypted frames from the real Mac to add
  real captured samples. Runs in the cloud if the harness builds on Linux,
  otherwise on the Mac (my call: try the cloud first).
- **Android Skeleton**: Gradle project (Kotlin, Compose, JDK 17, AGP current),
  build and unit-test run in the cloud.
- **Pairing**: QR scan, admission, Noise, SAS, room secret, /register,
  reconnect with backoff; persisted state in Android Keystore-backed storage.
- **Agent Chat**: chat list, conversation with streaming, send with mentions,
  create/delete/mute/truncate, AI-unavailable state.
- **Approvals and Mac Alerts**: every approval type, modal alert card.
- **Notifications**: foreground service, subscriptions to all chats,
  `syncSince` polling, notification channels, tap-through.
- **Terminal**: session tree, HEVC video via MediaCodec, key bar, composer.

The owner is asked after each phase before the next starts.

## Testing

- Unit tests (JVM, run in parallel) for every byte-level piece against the
  fixtures: canonical encoding, room name, SAS, Ed25519 join signature, Noise
  handshake and chunking, JSON encode/decode of every message the phone sends
  and receives.
- A relay integration test in the cloud: run the relay (Node, open mode) and a
  fake Mac built from the same Kotlin core playing the responder, then pair and
  exchange messages end to end. Ephemeral ports, temp dirs.
- On-device checks with the real Mac at the end of each phase (owner's phone
  over adb, or the owner tapping through), recorded in the phase report.

## Calls made without the owner

- Placeholder push token is safe: iTerm2 clears the stored token only on
  unpair or re-pair (`CompanionPairingController.swift:910, 970`), not on push
  failures. The cost: while the phone is disconnected, the Mac still sends
  content-free push attempts to iTerm2's public push relay, which reject them.
  No message content is in those requests.
- The phone does not need the owner's iPhone (if any) for fixtures; the Swift
  harness is the reference.
- Muted chats: no notifications, still listed. Notifications for approvals
  carry no inline action buttons in the first version (tap opens the chat).

## Left to the builder

- Library picks within the chosen approach (noise-java vs a small in-house
  Noise XK on BouncyCastle if noise-java does not expose the handshake hash;
  ML Kit vs ZXing for QR; Tink vs BouncyCastle for Ed25519/HKDF).
- Which protocol revision to advertise, as long as it is at least the Mac's
  `minimumPeer` and only covers features implemented (14 includes Mac modal
  alerts).
- `minSdk` (my call: 34, since the only target is a Pixel on Android 15+),
  package name (my call: `dev.bugobliterator.itermbuddy`).
- Notification channel layout and text.
- Chunking of the work inside each phase.
