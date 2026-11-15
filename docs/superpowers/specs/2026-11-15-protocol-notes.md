# iTerm2 Buddy protocol notes (phone side)

Research notes behind `2026-11-15-android-companion-design.md`, read from:

- iTerm2 (GPL-2.0): https://gitlab.com/gnachman/iterm2 (master, 2026-11-15)
- Relay: https://github.com/gnachman/iterm2-companion-relay (main @ b8aa2f9)

Path prefixes:
`CP` = `Companion/CompanionCore/Sources/CompanionProtocol`,
`CT` = `Companion/CompanionCore/Sources/CompanionTransport`,
`CN` = `Companion/CompanionCore/Sources/CompanionNoise`,
`AM` = `Companion/iTerm2Companion/App/AppModel.swift`,
`SH` = `sources/Companion/Shared`,
`AI` = `sources/AITerm` (read with `git show HEAD:sources/AITerm/<file>`),
`room.js` = relay `src/room.js`. Line numbers are as of those revisions.

## Connection stack

### QR pairing code (`CP/Pairing/PairingCode.swift`)
- `iterm2://pair?v=1&proto=Noise_XK_25519_ChaChaPoly_BLAKE2s&rs=<b64url 32B>&pid=<string>&relay=https://host[:port]` (12-16).
- Parse: scheme `iterm2`, host `pair` (179-183); `v` ∈ {1,2} (192-196); `proto` exact match (198-201); `rs` base64url, padding optional, exactly 32 bytes = Mac X25519 static public key (204-209); `pid` non-empty (211). Suite is **BLAKE2s**.
- Direct mode (`v=1`): `relay=` canonicalised by `canonicalRelayOrigin` (134-148): https, has host, no userinfo/query/fragment, path empty or "/"; output `"https://" + host + (":" + port if a port was given)`. A given port is kept even if 443. Host is not lowercased. This exact string is signed and must equal the relay's `RELAY_ORIGIN`.
- Resolved mode (`v=2`): reads `resolver=` (220-227). Not supported on Android; reject cleanly.
- Noise prologue: UTF-8 `"iterm2-companion/v1/pid:" + pid` (250-252); `v1` is constant.
- Room name (`CP/Pairing/RelayRoom.swift:19-35`): `hexLower(SHA256(canonical("iterm2-room", [rs, utf8(pid)])))`, 64 chars.

### Canonical encoding (`CP/Wire/CanonicalEncoding.swift:23-36`, room.js:128-143)
`u32be(len(domain)) || domain || for each field: u32be(len(field)) || field`.

### Relay admission (`CP/Transport/RelayAdmission.swift`, `CT/RelayTransport.swift:457-576`, room.js)
- WebSocket `wss://<origin without https://>/` (RelayTransport 465-475). Headers `x-relay-room: <roomName>`, `User-Agent`. **No `Origin` header**: the relay answers 403 to any request carrying one (relay `src/index.js:17-29`). Room name must match `^[0-9a-f]{64}$`. Connect timeout 30 s (AM:1815).
- Text frames, max 8 KB before admission (room.js:52, 326):
  1. Phone Hello `{"v":1,"role":"phone"}` (optional `"nonDisplacing":true`, used only by the iOS notification extension).
  2. Relay Challenge `{"nonce":"<std b64, 32B>"}` (room.js:376-394).
  3. Phone Proof: `{}` (open/fresh), `{"ticket":...}` (App Attest only, unused), or `{"sig":"<std b64 Ed25519>"}` (established).
  4. Result `{"ok":true,"registrationToken":"<b64>"}` (phone always gets a token, room.js:545-561), or `{"ok":false,"error":"..."}` then close 1008 with the reason (room.js:878-891).
- Relay decision (room.js:397-458): verifier present → must have valid `sig` ("signature required" / "bad signature"). No verifier and `ATTEST_REQUIRED=false` → admit with any proof. Phone rejected "mac offline" unless a Mac is parked (474-482): retry. Unestablished room: more than 8 phone cycles per Mac park tears the room down (48, 491-499). Newest connection per role wins; the displaced socket closes 1000 "displaced" (525-531).
- Join signature (`CP/Pairing/RelayJoin.swift:34-60`, `CT/CompanionTransports.swift:24-39`): seed = `HKDF-SHA256(ikm=roomSecret, salt=empty, info="relay-auth-ed25519", L=32)` used as Ed25519 private key; transcript = `canonical("iterm2-relay-join", [0x01, 0x02 (phone), nonce, utf8(roomName), utf8(origin)])`; relay recomputes with `env.RELAY_ORIGIN` (room.js:147-156, 417). Verifier = 32-byte Ed25519 public key, std base64.
- Fresh vs established (AM:1713-1738, 1914-1960): sign only if the "registered room" marker for this room exists; else send `{}` (in open mode `/attest` answers `400 {"error":"attestation disabled"}`, skip it). Self-heal: fresh connect rejected "signature required" with a room secret present → set marker, retry signed (AM:1816-1830); signed connect rejected "ticket required" → clear marker, retry fresh (AM:1831-1845).
- `/register` (AM:2006-2066, room.js:648-724): after Noise, SAS confirmation and the Mac's `relayRoomSecretStored`. `POST <origin>/register`, headers `Content-Type: application/json`, `x-relay-room`; body `{"registrationToken":..., "verifier":"<b64>"}`. 2xx or 403 "already registered" = success → persist marker. Token TTL 5 min.
- `/delete` (unpair, optional): signs `canonical("iterm2-relay-delete", [0x01, challenge, room, origin])` with a challenge from `POST /attest/challenge` (room.js:173-185, 730-756).

### Noise (`CN/NoiseHandshake.swift`, `CN/NoiseChannel.swift`)
- `Noise_XK_25519_ChaChaPoly_BLAKE2s`; phone = initiator; Mac static known from `rs`. `-> e, es` / `<- e, ee` / `-> s, se`. Prologue above. Empty handshake payloads (168-182); one binary WS frame per handshake message (48, 48, 64 bytes). Handshake hash `h` (32 B) captured before split (111-137).
- Phone static: X25519 keypair, regenerated on every fresh pairing (AM:1257-1263); the Mac pins it from message 3. iOS keychain account `noise-static-private-key`, service `com.googlecode.iterm2.companion` (`Companion/iTerm2Companion/Connection/PhoneIdentity.swift:47-54`).
- SAS (`CP/Pairing/PairingSAS.swift:26-41`): `okm = HKDF-SHA256(ikm=h, salt=empty, info="iterm2-sas-v1", L=8)`; big-endian u64 mod 1,000,000, zero-padded 6 digits. Phone displays, user types on the Mac.
- Confirmation (`CP/Pairing/PairingConfirmation.swift`): fresh pairing only, first decrypted frame `{"pairing":"accepted"}` or `{"pairing":"rejected"}`; accept only exact "accepted"; wait up to 180 s (AM:1860-1890, 1691). Reconnects skip it.
- Handshake timeout 15 s fresh, 6 s reconnect (AM:1672-1673).

### Framing and lifecycle
- After admission: one binary WS message = one Noise transport message (RelayTransport 13-16, 211-219). A text frame after admission is fatal. `LengthPrefixedFramer` is not used on the relay path.
- Chunking (NoiseChannel 106-175): plaintext `[cont: 1 more / 0 last] || chunk`, chunk ≤ 65535-16-1 = 65518; reassemble until cont=0. Sends strictly serialised (implicit nonces).
- Relay limits: frame ≤ 256 KB, 500 frames/s/room, 512 MB/day/room; over → close 1009 or 1008 "daily quota exceeded" (room.js:51-61).
- Keepalive: WS ping every 15 s, pong timeout 10 s, cancel on failure (RelayTransport 25, 38, 48-77).
- Backoff (`CT/RelayReconnectBackoff.swift:24-46`, AM:2288): full jitter `rand(0, n==1 ? 3 : min(30, 2^n))` s; "displaced" → 60 s; "daily quota" → 30 min (AM:1678, 1687). Pre-admission HTTP 403/413/other 4xx fatal; 429/5xx retry (`CT/RelaySignal.swift:47-82`). Fresh pairing does not auto-retry; reconnects loop (AM:1592-1623).

### Persisted per pairing (`PhoneIdentity.swift`)
Noise static private key; `relay-room-secret` (32 random bytes minted by the phone, 85-97); `paired-code` JSON (`responderStaticPublicKey`, `pairingID`, `relayOrigin`, `resolverURL`; 111-120); `relay-registered-room` marker (136-143); `push-relay-secret`; watermarks (`CP/WatermarkStore.swift`, keyed by `hex(HMAC-SHA256(roomSecret, chatID)[0:16])`, `CP/CompanionThreadKey.swift`). Everything wiped on a fresh scan (AM:1257-1283).

### Android replacements
CryptoKit → BouncyCastle or Tink (`Ed25519PrivateKeyParameters(seed)` matches CryptoKit's seed form); noise-c → noise-java (confirm it exposes the handshake hash); Keychain → Android Keystore; URLSession WS → OkHttp (`pingInterval(15s)`); AVFoundation QR → CameraX + ML Kit or ZXing. App Attest skipped; it must stay disabled on the relay.

## App layer

### Encoding (`CP/Wire/WireCoding.swift:15-25`, `SH/CompanionMessages.swift:1200-1238`)
- JSON (Swift `JSONEncoder`), one document per (reassembled) Noise message. Dates = ms since 1970 (number). `Data` = std base64. `UUID` = uppercase string. nil optionals omitted.
- Frame type: first byte `0x01` = media frame, else JSON control (`SH/CompanionFrameChannel.swift:27-31, 53-58`).
- Envelope `{"requestID": <UInt64, optional>, "payload": {"<case>": {...}}}`. `request()` uses increasing IDs from 1; the Mac copies it on the reply; fire-and-forget omits it. A host message with no or unknown `requestID` is an unsolicited event (`SH/CompanionSession.swift:47-75, 128-136`).
- Swift synthesized enum coding: no values → `{"ping":{}}`; labelled → object; unlabelled → `_0`, `_1` (`{"plainText":{"_0":"hi"}}`, `{"relayRoomSecret":{"_0":"<b64>"}}`); `Result` → `{"success":{"_0":…}}` / `{"failure":{"_0":…}}`. Exception: `CompanionSyncItem` is flat `{"message":{…}}` / `{"alert":{…}}`.
- Forward compatibility: unknown discriminator → `.unsupported`; known key with a bad body → error (`SH/CompanionMessages.swift:950-965, 1169-1179`; `AI/Message.swift:44-55`).

### Version (`CP/CompanionProtocolVersion.swift:158-246`)
- First RPC: `hello{revision, minimumPeer}` (iOS: 15, 11); reply `hello{revision, minimumPeer, wantsNotificationPermission?, aiAvailable?, macStatus?}`. `localRev < peerMin` → phone must upgrade; `peerRev < localMin` → Mac must upgrade. Re-sent on every reconnect.
- Feature revisions: 3 streaming, 5 chat mute, 7 auto-provide consent, 8 `turnLifecycle`, 12 message deletion, 13 AI availability, 14 Mac modal alerts, 15 browser stream. Advertise only what is implemented.
- Then `relayRoomSecret{_0}` → `relayRoomSecretStored` on every connect (`Companion/iTerm2Companion/Connection/CompanionClient.swift:35-45`).

### Message catalogue (`SH/CompanionMessages.swift:680-1166`; P→M phone to Mac, M→P Mac to phone)

Session: `hello` (above); `ping`/`pong`; `relayRoomSecret`/`relayRoomSecretStored`; `unpairing` (P→M); `unpaired` (M→P).

Chats:
- `listChatsAndSessions` → `chatsAndSessions{chats:[{chat, snippet?, muted?}], sessions:[{guid,name,subtitle,isBrowser}]}`. `Chat` (`AI/Chat.swift:14-87`): `id, title, creationDate, lastModifiedDate, orchestrationEnabled, terminalSessionGuid?, browserSessionGuid?, permissions, modelName?, claimedScopes, watchers, icon?(Data)…`.
- `createChat{title, mode}` → `chatCreated{entry}`; mode `{"orchestrator":{}}` or `{"session":{"guid":…}}` (:650).
- `deleteChat{chatID}`, `setChatMuted{chatID,muted}` (reply only on `error`).
- M→P `chatListChanged{chats}`: full replacement.

History and live events:
- `subscribe{chatID}` → `history{chatID, messages, maxSeq}`; then unsolicited `typingStatus` / `turnLifecycle(started)` if a turn is running (`sources/Companion/CompanionHostBridge.swift:1410-1440`). Multiple subscriptions allowed. `unsubscribe{chatID}`.
- M→P `delivery{message, chatID, partial}`, `typingStatus{isTyping, participant, chatID}`, `turnLifecycle{event:"started"|"ended", chatID}` (subscribed chats only).
- Streaming: first chunk a full Message with uniqueID U; then `delivery` with content `append(string, uuid:U)` / `appendAttachment(attachment, uuid:U)`, `partial:true`; `commit` never sent. Turn end: `turnLifecycle(ended)` (rev ≥ 8) or `typingStatus(false)`. Merge logic AM:5222-5260; accumulator `CP/StreamingReplyAccumulator.swift`.
- `deleteMessages{chatID, messageIDs}` (suffix truncation); M→P `messagesRemoved{chatID, messageIDs}`.
- `syncSince{messageSeq, alertSeq, limit, nonce?}` → `syncSince{items:[{"message":{chatID,chatName,uniqueID,author,body,seq}} | {"alert":{alertID,threadKey,title,body,seq}}], maxMessageSeq, maxAlertSeq, messageReset, alertReset, truncated}`. Legacy `messagesSince`.

`Message` (`AI/Message.swift:242-525, 712`): `chatID, author("user"|"agent"), content, sentDate, uniqueID, inResponseTo?, responseID?, agentReasoning?, configuration?, firstBlobRef?`. Content (`:287-336`): `plainText(_0, context?)`, `markdown`, `explanationRequest`, `explanationResponse`, `remoteCommandRequest(_0: RemoteCommandPayload, safe?)`, `watcherEvent`, `remoteCommandResponse`, `selectSessionRequest(_0: Message, terminal)`, `clientLocal(ClientLocal)`, `renameChat`, `append`, `appendAttachment`, `commit`, `userCommand`, `setPermissions`, `vectorStoreCreated`, `terminalCommand`, `multipart([Subpart], vectorStoreID?)`. `Subpart`: `plainText | markdown | attachment(LLM.Message.Attachment) | context` (`AI/LLM.swift`). `hiddenFromClient` (`:549`) is never forwarded.

Sending:
- `publish{message, toChatID, partial:false}` with `author:user`, content `{"plainText":{"_0":"text"}}` (AM:3107-3157). The Mac drops non-user messages and echoes the same uniqueID in a `delivery`: de-duplicate by uniqueID.
- Mentions are inline tokens `@<guid>`, `@session:<uuid>`, `@wg-<uuid>` (`Companion/iTerm2Companion/Views/MentionComposerView.swift`); `resolveMentions{identifiers}` → `mentionsResolved([{identifier, displayName?, sessionGuid?, workgroupID?}])` (:76).
- Session-bound consent: `fetchAutoProvideConsent{sessionGuid}` → `autoProvideConsent{satisfied}`; `grantAutoProvideConsent{chatID}`.

Approvals (request arrives as a delivered Message; `AI/Message.swift:131-239`, AM:3825-3910, `Views/MessageBubbleView.swift:330-375`):

| Request | Phone UI | Phone sends |
|---|---|---|
| `remoteCommandRequest` with `classic` payload (`AI/RemoteCommand.swift`, Markdown in `markdownDescription`) | 4 buttons | `remoteCommandDecision{chatID, messageUniqueID, decision: allowOnce\|allowAlways\|denyOnce\|denyAlways}` (:642) |
| `selectSessionRequest(original, terminal)` | session picker | `selectSessionResponse{chatID, originalMessage, sessionGuid? (nil = cancel), terminal}` |
| `clientLocal.offerLink(terminal, guid, name)` | link button | `linkSession{chatID, sessionGuid, terminal}` |
| `clientLocal.workgroupPermissionRequest(requestID, workgroupID, workgroupName, summary)` | Approve / Deny | `publish` user Message `userCommand.workgroupPermissionResponse{requestID, approved}` |
| `clientLocal.enableOrchestrationRequest(requestID)` | Enable / Not now | `publish` `userCommand.enableOrchestrationResponse{requestID, approved}` |
| `clientLocal.orchestrationPermissionGranted(scope, name)` | Revoke | `publish` `userCommand.revokeOrchestrationPermission{scope}` |

`external` remote-command requests run automatically; no answer.

Mac modal alerts (rev ≥ 14, :423-588):
- M→P `macStatusChanged{status:{modalAlerts, mainBlocked}}` (also in the `hello` reply); full state each time.
- Alert: `{id, heading, body, buttons:[{title,isCancel,isDestructive,rememberable,offered}], suppressionLabel?, inputs:[{id,label?,kind:text|integer|secret,value,minimum?,maximum?}], hasAccessory, isAppModal, sessionGuids, suppressionDefault}`. Only the last alert is answerable; hide `offered == false` buttons.
- P→M `answerModalAlert{alertID, buttonIndex, suppress, inputs?:{id:value}}`; M→P `modalAlertAnswerRejected{alertID}` then fresh status. While `mainBlocked`, hold request timeouts (`Connection/MacRequestWatchdog.swift`).

Sessions, workgroups, AI:
- `fetchSessionTree` → `sessionTree{windows:[{title, tabs:[{title, panes:[{session, peers:[{roleName, session}]}]}]}]}` (:93).
- `fetchWorkgroupInfo{workgroupID}` → `workgroupInfo{workgroupID, name, members:[{roleName, sessionGuid?, sessionName?, statusText?, detailText?, state}]}` (`state` is `SessionState`, defined in Mac code; look it up when porting).
- M→P `aiAvailabilityChanged{available}`; `hello.aiAvailable` (nil = true).

Notifications control: P→M `pushStatus{authorization: notDetermined|denied|authorized, token?:Data, relaySecret?:Data, sandbox}` every connect; M→P `requestNotificationPermission{requestID}` → `notificationPermissionResponse{requestID, authorization}`.

Errors: M→P `error(_0: CompanionError)` correlated by `requestID` (`CP/Wire/CompanionError.swift`).

Terminal/video (later): `startSessionStream`, `stop`, `requestKeyframe`, `updateStreamParams`, `streamAck`, selection, `copy`/`paste`, `sendKey`, `resizeSession`, `reportScrollWheel`, `fetchSessionScreenInfo`/`Content`/`HistoryTile`. HEVC: the Mac sends a stream config (parameter sets) then access units (`Companion/iTerm2Companion/Screens/CompanionVideoView.swift`, `SH/CompanionHEVCFraming.swift`).

### Notifications on the Mac and iOS
- The Mac notifies when an agent turn completes (debounced per chat) or a classic permission / select-session request arrives (`sources/Companion/CompanionAgentActivityNotifier.swift`); terminal alerts go through `CompanionAlertBridge.postTerminalAlert` (62-87) into a DB with a global alert seq, only when `canNotify` (authorized + token + relaySecret, `CompanionPushRegistry.swift:111-157`). Muted chats skipped. **Pushes are suppressed while an interactive phone is connected** (Notifier 155-163). Wakeups are content-free APNs pushes via the push relay.
- iOS NSE: non-displacing connection, `syncSince` with stored floors, one notification per item (message: title chatName, body; alert: title, body), advance floors, lower on reset (`Companion/PushService/NotificationService.swift:96-455`, `NSEFetcher.swift:75-136`).

### What Android needs for the same notifications
1. `subscribe` to every chat (refresh from `chatListChanged`); merge `delivery`; notify on `turnLifecycle(ended)` with accumulated text; notify immediately on deliveries carrying classic `remoteCommandRequest`, `selectSessionRequest`, or `clientLocal.enableOrchestrationRequest` (AM:3519-3580).
2. Terminal alerts have no live event: poll `syncSince` (nonce null) on connect, after `turnLifecycle`/`chatListChanged`, and on a timer; keep floors like `WatermarkStore`.
3. Send `pushStatus{authorization:"authorized", token:<placeholder>, relaySecret:<random 32B>, sandbox:false}` and advertise revision ≥ 2 so the Mac stores alerts. Its APNs attempts fail harmlessly.
4. `macStatusChanged` / `hello.macStatus` → alert card and notification.
5. Answer `requestNotificationPermission`.

### Size (iOS Swift lines)
Chat/approval UI ~2.6k; notification/sync ~2.0k; model types for Codable ~2.4k; message definitions ~1.2k; terminal video/input ~5.0k. Hardest part for compatibility: Swift's enum coding for `Message.Content`, `RemoteCommand.Content`, `LLM.Message.Attachment`; golden JSON captured from a real Mac is required.
