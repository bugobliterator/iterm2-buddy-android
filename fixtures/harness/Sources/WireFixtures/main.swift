import CompanionProtocol
import Crypto
import Foundation

// Entry point for `swift run wire-fixtures <out-dir>`.
//
// Every input below is a fixed byte string chosen here. Every output field is
// produced by an upstream iTerm2 v3.7.7 function; nothing expected is computed
// in this file. The only values decided here are the Android app's QR policy
// (`androidAccepts`, `androidReason`), which is app behaviour, not protocol.

let arguments = CommandLine.arguments
guard arguments.count == 2 else {
    let stderr = FileHandle.standardError
    stderr.write(Data("usage: wire-fixtures <out-dir>\n".utf8))
    exit(64)
}

let outputDirectory = URL(fileURLWithPath: arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

func require(_ condition: Bool, _ message: String) {
    if !condition {
        FileHandle.standardError.write(Data("wire-fixtures: \(message)\n".utf8))
        exit(1)
    }
}

// MARK: - Fixed inputs

func bytes(_ count: Int, startingAt first: UInt8, step: UInt8 = 1) -> Data {
    Data((0..<count).map { first &+ UInt8(truncatingIfNeeded: $0) &* step })
}

func hexBytes(_ hex: String) -> Data {
    var data = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        data.append(UInt8(hex[index..<next], radix: 16)!)
        index = next
    }
    return data
}

/// base64url of an input key, for building QR inputs. Unpadded unless asked.
func base64URL(_ data: Data, padded: Bool = false) -> String {
    var text = data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
    if !padded {
        text = text.replacingOccurrences(of: "=", with: "")
    }
    return text
}

/// The Mac's X25519 static public key as the QR carries it. Bytes 0xf8...
/// make its base64url contain both '-' and '_'.
let macStaticKey = bytes(32, startingAt: 0xf8)
let shortMacStaticKey = bytes(31, startingAt: 0x40)
let otherMacStaticKey = bytes(32, startingAt: 0x00)
let pairingID = "f7c2a1b9e05d4c3a"
let projectRelayOrigin = "https://relay.example.ts.net"
let exampleRelay = "https://relay.example.com"
let protocolName = PairingCode.supportedProtocol

func directQR(rs: String = base64URL(macStaticKey),
              pid: String? = pairingID,
              proto: String = protocolName,
              version: String = "1",
              extra: String) -> String {
    var query = "v=\(version)&proto=\(proto)&rs=\(rs)"
    if let pid {
        query += "&pid=\(pid)"
    }
    return "iterm2://pair?\(query)\(extra)"
}

// MARK: - Output records

struct CanonicalVector: Encodable {
    let name: String
    let domain: String
    let fields: [String]
    let encoded: String
}

struct PairingCodeVector: Encodable {
    let name: String
    let qr: String
    let upstreamOk: Bool
    let upstreamError: String?
    let responderStaticPublicKey: String?
    let pairingID: String?
    let relayOrigin: String?
    let resolverURL: String?
    let androidAccepts: Bool
    let androidReason: String?
}

struct RoomNameVector: Encodable {
    let responderStaticPublicKey: String
    let pairingID: String
    let roomName: String
}

struct SASVector: Encodable {
    let name: String
    let handshakeHash: String
    let code: String
}

struct JoinVector: Encodable {
    let role: String
    let roomSecret: String
    let seed: String
    let verifier: String
    let nonce: String
    let roomName: String
    let origin: String
    let transcript: String
    let signature: String
}

struct PrologueVector: Encodable {
    let pairingID: String
    let prologue: String
}

struct CryptoVectors: Encodable {
    let canonical: [CanonicalVector]
    let pairingCodes: [PairingCodeVector]
    let roomNames: [RoomNameVector]
    let sas: [SASVector]
    let joins: [JoinVector]
    let prologues: [PrologueVector]
}

// MARK: - Canonical encoding

let canonicalInputs: [(name: String, domain: String, fields: [Data])] = [
    ("domainOnly", "iterm2-test", []),
    ("emptyField", "iterm2-test", [Data()]),
    ("boundaryABThenC", "iterm2-test", [Data("AB".utf8), Data("C".utf8)]),
    ("boundaryAThenBC", "iterm2-test", [Data("A".utf8), Data("BC".utf8)]),
    ("roomPreimage", "iterm2-room", [macStaticKey, Data(pairingID.utf8)]),
    ("binaryAndUnicode", "iterm2-relay-join", [Data([0x01]), Data([0x02]), bytes(32, startingAt: 0x80),
                                              Data("é ☃".utf8)]),
    ("longField", "iterm2-test", [bytes(300, startingAt: 0x00)])
]
let canonical = canonicalInputs.map { input in
    CanonicalVector(name: input.name,
                    domain: input.domain,
                    fields: input.fields.map { $0.hexEncodedString() },
                    encoded: CanonicalEncoding.encode(domain: input.domain, input.fields).hexEncodedString())
}

// MARK: - Pairing codes

/// The Android app's policy for a scanned QR. Only direct (v=1) codes that
/// upstream parses and that carry a relay origin are accepted; resolved (v=2)
/// codes are refused because Android does not implement the shard resolver;
/// every upstream parse failure is refused with the upstream error's name.
/// The canonical relay origin's host must also be a plain hostname
/// (`[A-Za-z0-9._-]+`) or a bracketed IP literal (`[0-9A-Fa-f:.]+`), with an
/// optional port: upstream percent-decodes the host, so it can otherwise hold
/// '@', '/', '#', control or non-ASCII characters (`invalidRelay`).
let plainRelayOrigin = try! Regex("https://([A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+\\])(:[0-9]+)?")

func androidPolicy(_ result: Result<PairingCode, Error>) -> (accepts: Bool, reason: String?) {
    switch result {
    case .failure(let error):
        return (false, upstreamErrorName(error))
    case .success(let code):
        if code.version == PairingCode.resolvedVersion || code.resolverURL != nil {
            return (false, "resolverUnsupported")
        }
        if code.relayOrigin == nil {
            return (false, "missingRelay")
        }
        if code.relayOrigin?.wholeMatch(of: plainRelayOrigin) == nil {
            return (false, "invalidRelay")
        }
        return (true, nil)
    }
}

func upstreamErrorName(_ result: Result<PairingCode, Error>) -> String? {
    guard case .failure(let error) = result else {
        return nil
    }
    return upstreamErrorName(error)
}

func upstreamErrorName(_ error: Error) -> String {
    guard let parseError = error as? PairingCode.ParseError else {
        return "unknown"
    }
    switch parseError {
    case .unsupportedVersion: return "unsupportedVersion"
    case .unsupportedProtocol: return "unsupportedProtocol"
    case .invalidResponderKey: return "invalidResponderKey"
    case .malformedURL: return "malformedURL"
    case .missingPairingID: return "missingPairingID"
    case .invalidRelay: return "invalidRelay"
    case .invalidResolver: return "invalidResolver"
    }
}

/// A QR rendered by upstream `urlString()`, with a pid that needs
/// percent-encoding. Rendered here by swift-foundation's URLComponents on
/// Linux; the Darwin rendering is unverified until the on-device check.
let macRenderedQR = PairingCode(responderStaticPublicKey: macStaticKey,
                                pairingID: "f7c2 a+b&c=d/é?",
                                relayOrigin: projectRelayOrigin).urlString()

let pairingCodeInputs: [(name: String, qr: String)] = [
    ("projectRelay", directQR(extra: "&relay=\(projectRelayOrigin)")),
    ("exampleRelay", directQR(extra: "&relay=\(exampleRelay)")),
    ("explicitDefaultPort", directQR(extra: "&relay=https://relay.example.com:443")),
    ("explicitOtherPort", directQR(extra: "&relay=https://relay.example.com:8443")),
    ("trailingSlash", directQR(extra: "&relay=https://relay.example.com/")),
    ("mixedCaseHost", directQR(extra: "&relay=https://Relay.Example.COM")),
    ("uppercaseScheme", directQR(extra: "&relay=HTTPS://relay.example.com")),
    ("percentEncodedRelay", directQR(extra: "&relay=https%3A%2F%2Frelay.example.com")),
    ("paddedBase64URLKey", directQR(rs: base64URL(macStaticKey, padded: true), extra: "&relay=\(exampleRelay)")),
    ("percentEncodedPaddingKey", directQR(rs: base64URL(macStaticKey, padded: true)
                                            .replacingOccurrences(of: "=", with: "%3D"),
                                          extra: "&relay=\(exampleRelay)")),
    ("unpaddedBase64URLKey", directQR(rs: base64URL(otherMacStaticKey), extra: "&relay=\(exampleRelay)")),
    ("standardBase64Key", directQR(rs: macStaticKey.base64EncodedString(), extra: "&relay=\(exampleRelay)")),
    ("macRenderedByUrlString", macRenderedQR),
    ("resolverVersionTwo", directQR(version: "2",
                                    extra: "&resolver=https://resolver.example.com/shardmap.json")),
    ("resolverVersionTwoWithoutResolver", directQR(version: "2", extra: "&relay=\(exampleRelay)")),
    ("duplicateRelay", directQR(extra: "&relay=\(exampleRelay)&relay=\(projectRelayOrigin)")),
    ("directWithStrayResolver", directQR(extra: "&relay=\(exampleRelay)&resolver=https://resolver.example.com/")),
    ("directWithoutRelay", directQR(extra: "")),
    ("wrongProtocol", directQR(proto: "Noise_XK_25519_ChaChaPoly_SHA256", extra: "&relay=\(exampleRelay)")),
    ("shortResponderKey", directQR(rs: base64URL(shortMacStaticKey), extra: "&relay=\(exampleRelay)")),
    ("invalidBase64Key", directQR(rs: "not*base64", extra: "&relay=\(exampleRelay)")),
    ("unsupportedVersion", directQR(version: "3", extra: "&relay=\(exampleRelay)")),
    ("missingVersion", "iterm2://pair?proto=\(protocolName)&rs=\(base64URL(macStaticKey))&pid=\(pairingID)"
        + "&relay=\(exampleRelay)"),
    ("missingPairingID", directQR(pid: nil, extra: "&relay=\(exampleRelay)")),
    ("emptyPairingID", directQR(pid: "", extra: "&relay=\(exampleRelay)")),
    ("plainHTTPRelay", directQR(extra: "&relay=http://relay.example.com")),
    ("relayWithPath", directQR(extra: "&relay=https://relay.example.com/room")),
    ("relayWithUserinfo", directQR(extra: "&relay=https://user@relay.example.com")),
    ("wrongScheme", "https://pair?v=1&proto=\(protocolName)&rs=\(base64URL(macStaticKey))&pid=\(pairingID)"),
    ("wrongHost", "iterm2://pairing?v=1&proto=\(protocolName)&rs=\(base64URL(macStaticKey))&pid=\(pairingID)"),
    // Relay origin edge forms. Their results are URLComponents' in swift-foundation
    // (FoundationEssentials) with Swift 6.2 on Linux; Darwin may differ on exotic hosts.
    ("relayEmptyPort", directQR(extra: "&relay=https://h:")),
    ("relayLeadingZeroPort", directQR(extra: "&relay=https://h:0443")),
    ("relayIPv6Literal", directQR(extra: "&relay=https://[::1]:8443")),
    ("relayPortOutOfRange", directQR(extra: "&relay=https://h:65536")),
    ("relayEmptyFragment", directQR(extra: "&relay=https://h%23")),
    ("relayEmptyQuery", directQR(extra: "&relay=https://h%3F")),
    ("relayUnderscoreHost", directQR(extra: "&relay=https://my_relay.lan")),
    ("relayEmptyLabelHost", directQR(extra: "&relay=https://h..com")),
    ("relayLeadingHyphenHost", directQR(extra: "&relay=https://-h.com")),
    ("relayPercentEncodedHost", directQR(extra: "&relay=https://relay%252Eexample.com")),
    ("relayEmptyUserinfo", directQR(extra: "&relay=https://@h")),
    ("unrelatedInvalidUTF8Item", directQR(extra: "&relay=\(exampleRelay)&x=%FF")),
    ("relayInvalidUTF8", directQR(extra: "&relay=%FF")),
    // Two faults: the result names the one upstream checks first.
    ("wrongProtocolAndInvalidKey", directQR(rs: "not*base64", proto: "Noise_XK_25519_ChaChaPoly_SHA256",
                                            extra: "&relay=\(exampleRelay)")),
    ("missingPairingIDAndInvalidRelay", directQR(pid: nil, extra: "&relay=http://relay.example.com")),
    // The last base64url character carries two unused bits; here they are not zero.
    ("nonZeroTrailingBitsKey", directQR(rs: String(base64URL(macStaticKey).dropLast()) + "d",
                                        extra: "&relay=\(exampleRelay)")),
    // Upstream accepts these hosts once decoded; the Android policy refuses them.
    ("relayEncodedAtSignHost", directQR(extra: "&relay=https://good.com%2540evil.com")),
    ("relayEncodedSlashHost", directQR(extra: "&relay=https://evil.com%252Fgood")),
    ("relayEncodedNewlineHost", directQR(extra: "&relay=https://h%250A.evil")),
    ("relayEncodedNulHost", directQR(extra: "&relay=https://h%2500")),
    ("relayEncodedHashHost", directQR(extra: "&relay=https://a%2523b")),
    ("relayNonASCIIHost", directQR(extra: "&relay=https://r%25C3%25A9lay.com")),
    // A query character RFC 3986 does not allow makes Foundation re-encode the
    // whole query, so every value in it comes back undecoded.
    ("rawQueryEncodedRelay", directQR(pid: "a|b", extra: "&relay=https%3A%2F%2Fh")),
    ("rawQueryPairingID", directQR(pid: "%41|", extra: "&relay=\(projectRelayOrigin)")),
    ("relayUndecodableUserinfo", directQR(extra: "&relay=https://%25FF@h")),
    ("relayPortOverflow", directQR(extra: "&relay=https://h:9223372036854775808")),
    ("relayUndecodablePath", directQR(extra: "&relay=https://h/%25FF")),
    // Check order: v before proto, rs before pid.
    ("unsupportedVersionAndProtocol", directQR(proto: "X", version: "3", extra: "&relay=\(exampleRelay)")),
    ("invalidKeyAndMissingPairingID", directQR(rs: "not*base64", pid: nil, extra: "&relay=\(exampleRelay)"))
]
let pairingCodes = pairingCodeInputs.map { input -> PairingCodeVector in
    let result = Result { try PairingCode.parse(input.qr) }
    let policy = androidPolicy(result)
    let code = try? result.get()
    return PairingCodeVector(name: input.name,
                             qr: input.qr,
                             upstreamOk: code != nil,
                             upstreamError: code == nil ? upstreamErrorName(result) : nil,
                             responderStaticPublicKey: code?.responderStaticPublicKey.hexEncodedString(),
                             pairingID: code?.pairingID,
                             relayOrigin: code?.relayOrigin,
                             resolverURL: code?.resolverURL,
                             androidAccepts: policy.accepts,
                             androidReason: policy.reason)
}

// MARK: - Room names

let roomNameInputs: [(rs: Data, pid: String)] = [
    (macStaticKey, pairingID),
    (otherMacStaticKey, pairingID),
    (macStaticKey, "f7c2 a+b&c=d/é?"),
    (bytes(32, startingAt: 0xff, step: 0xff), "1")
]
let roomNames = roomNameInputs.map { input in
    RoomNameVector(responderStaticPublicKey: input.rs.hexEncodedString(),
                   pairingID: input.pid,
                   roomName: RelayRoom.name(responderStaticPublicKey: input.rs, pairingID: input.pid))
}

// MARK: - SAS

let sasInputs: [(name: String, hash: Data)] = [
    ("allZero", Data(count: 32)),
    ("ascending", bytes(32, startingAt: 0x00)),
    ("allOnes", Data(repeating: 0xff, count: 32)),
    // The handshake hash of cacophony's Noise_XK_25519_ChaChaPoly_BLAKE2s vector
    // (fixtures/noise/xk-25519-chachapoly-blake2s.json).
    ("cacophonyXKHandshakeHash", hexBytes("899891a0f1a8db67f8bfa46b8bced371c1c25de377f20cf882fdd06fc15517fd")),
    // Chosen offline so the code starts with zeros; checked below.
    ("leadingZeros", hexBytes("037102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
]
let sas = sasInputs.map { input in
    SASVector(name: input.name,
              handshakeHash: input.hash.hexEncodedString(),
              code: PairingSAS.code(handshakeHash: input.hash))
}

// MARK: - Relay joins

let joinRoomSecret = bytes(32, startingAt: 0x20, step: 3)
let joinNonce = bytes(32, startingAt: 0xa0, step: 5)
let joinRoomName = RelayRoom.name(responderStaticPublicKey: macStaticKey, pairingID: pairingID)
let joinOrigin = try PairingCode.canonicalRelayOrigin(projectRelayOrigin)
let joinRoles: [(name: String, role: RelayJoin.Role)] = [("phone", .phone), ("mac", .mac)]
let joins = joinRoles.map { entry -> JoinVector in
    let key = RelayJoin.signingKey(roomSecret: joinRoomSecret)
    let transcript = RelayJoin.transcript(role: entry.role, nonce: joinNonce, roomName: joinRoomName, origin: joinOrigin)
    // swift-crypto's BoringSSL backend signs deterministically (RFC 8032), so
    // the signature is stable on Linux. Darwin CryptoKit randomizes it, which
    // would silently change the vectors, so sign twice and require equality.
    let signature = try! key.signature(for: transcript)
    let repeatSignature = try! key.signature(for: transcript)
    require(signature == repeatSignature, "Ed25519 signing is not deterministic; generate on Linux")
    let verifier = RelayJoin.verifier(roomSecret: joinRoomSecret)
    precondition(RelayJoin.verify(signature: signature, transcript: transcript, verifier: verifier))
    return JoinVector(role: entry.name,
                      roomSecret: joinRoomSecret.hexEncodedString(),
                      seed: key.rawRepresentation.hexEncodedString(),
                      verifier: verifier.hexEncodedString(),
                      nonce: joinNonce.hexEncodedString(),
                      roomName: joinRoomName,
                      origin: joinOrigin,
                      transcript: transcript.hexEncodedString(),
                      signature: signature.hexEncodedString())
}

// MARK: - Prologues

let prologuePairingIDs = [pairingID, "f7c2 a+b&c=d/é?", "1"]
let prologues = prologuePairingIDs.map { pid in
    PrologueVector(pairingID: pid,
                   prologue: PairingCode(responderStaticPublicKey: macStaticKey, pairingID: pid)
                       .handshakePrologue().hexEncodedString())
}

// MARK: - Checks and output

require(!canonical.isEmpty, "canonical is empty")
require(!pairingCodes.isEmpty, "pairingCodes is empty")
require(!roomNames.isEmpty, "roomNames is empty")
require(!sas.isEmpty, "sas is empty")
require(!joins.isEmpty, "joins is empty")
require(!prologues.isEmpty, "prologues is empty")
require(Set(joins.map(\.role)) == ["phone", "mac"], "joins must cover both roles")
require(sas.contains { $0.code.hasPrefix("00") }, "no SAS code with leading zeros")
require(pairingCodes.contains { $0.upstreamOk && $0.resolverURL != nil && !$0.androidAccepts },
        "no accepted-upstream resolver QR")
require(pairingCodes.filter(\.androidAccepts).allSatisfy { $0.relayOrigin != nil }, "accepted QR without relay")

let vectors = CryptoVectors(canonical: canonical,
                            pairingCodes: pairingCodes,
                            roomNames: roomNames,
                            sas: sas,
                            joins: joins,
                            prologues: prologues)
/// The encoder for every file the harness writes itself (not message samples).
func fixtureEncoder() -> JSONEncoder {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
    return encoder
}

let encoder = fixtureEncoder()
var json = try encoder.encode(vectors)
json.append(0x0a)
let destination = outputDirectory.appendingPathComponent("crypto-vectors.json")
try json.write(to: destination, options: .atomic)
print("wire-fixtures: wrote \(destination.path): canonical=\(canonical.count) pairingCodes=\(pairingCodes.count) "
      + "roomNames=\(roomNames.count) sas=\(sas.count) joins=\(joins.count) prologues=\(prologues.count)")

// MARK: - Session message samples
//
// Each sample is an envelope exactly as the real flow builds it, encoded with
// upstream `WireCoding`. Request IDs follow the Android client's order on one
// connection, numbered from 1 as the phone's `CompanionSession` numbers them:
// `hello` first, then `relayRoomSecret`, then a `ping`. The Mac does not
// depend on that order: CompanionLink `handle` answers both whenever they
// arrive (iOS sends `relayRoomSecret` first, from AppModel `lockRelayRoom`).
// The Mac copies the request's ID onto its reply (CompanionLink `send(_:requestID:)`).

/// The revision the Android app advertises in this milestone (Global
/// Constraints: "Advertise protocol revision 11 with minimum peer 11"). It is
/// the Mac's floor, `CompanionProtocolVersion.minimumPeer` at v3.7.7.
let appAdvertisedRevision = 11
let appAdvertisedMinimumPeer = 11
require(appAdvertisedRevision == CompanionProtocolVersion.minimumPeer, "app revision is not the Mac's floor")
require(CompanionProtocolVersion.evaluate(peerRevision: appAdvertisedRevision,
                                          peerMinimumPeer: appAdvertisedMinimumPeer) == .compatible,
        "a v3.7.7 Mac refuses the app's revision")
require(appAdvertisedRevision < CompanionProtocolVersion.modalAlertRevision,
        "the app's revision now gets macStatus; update hello-mac")

let helloRequestID: UInt64 = 1
let relayRoomSecretRequestID: UInt64 = 2
let pingRequestID: UInt64 = 3

/// The phone-minted room secret couriered in `relayRoomSecret`. Chosen so its
/// standard base64 contains both '+' and '/' (which upstream escapes as "\/").
let sampleRoomSecret = bytes(32, startingAt: 0xf0, step: 3)

/// A modal alert as CompanionLink `currentStatus()` reports it. The id is a
/// UUID's `uuidString` (uppercase), as the Mac sends it.
let sampleModalAlert = CompanionModalAlert(
    id: "6F1C2B3A-4D5E-4F60-8172-93A4B5C6D7E8",
    heading: "Close Session?",
    body: "A job is still running in this session.",
    buttons: [
        CompanionModalAlert.Button(title: "OK", isCancel: false, isDestructive: true, rememberable: true),
        CompanionModalAlert.Button(title: "Cancel", isCancel: true, isDestructive: false, rememberable: false),
        CompanionModalAlert.Button(title: "Show Details", isCancel: false, isDestructive: false,
                                   rememberable: false, offered: false)
    ],
    suppressionLabel: "Do not ask again",
    inputs: [
        CompanionModalAlert.Input(id: "name", label: "Name", kind: CompanionModalAlert.Input.textKind,
                                  value: "build"),
        CompanionModalAlert.Input(id: "password", label: nil, kind: CompanionModalAlert.Input.secretKind,
                                  value: ""),
        CompanionModalAlert.Input(id: "count", label: "Count", kind: CompanionModalAlert.Input.integerKind,
                                  value: "3", minimum: 1, maximum: 10)
    ],
    hasAccessory: false,
    isAppModal: true,
    sessionGuids: ["w0t0p0:2B5D1E7A-0C9F-4E3B-A1D2-3C4B5A697887"],
    suppressionDefault: false)

enum SampleDirection: String {
    case phoneToMac
    case macToPhone
}

/// Upstream's own encoder (`WireCoding.makeEncoder()`) with sorted keys.
/// `WireCoding.encode` emits keys in hash order, which changes from run to run
/// (Swift seeds its hasher per process), so the samples store the same tree
/// with keys sorted. Every value encoding (dates, Data, escaping) is upstream's.
func sortedWireEncoder() -> JSONEncoder {
    let encoder = WireCoding.makeEncoder()
    encoder.outputFormatting.insert(.sortedKeys)
    return encoder
}

struct SessionSample {
    let name: String
    let direction: SampleDirection
    let swiftValue: String
    /// Exactly what `WireCoding.encode` sent, in this run's key order.
    let wire: Data
    /// The same JSON with keys sorted; stored in the sample's `json`.
    let sortedWire: Data
    /// Decodes with upstream `WireCoding` and returns the decoded envelope's
    /// debug description and its sorted re-encoding.
    let roundTrip: (Data) throws -> (swiftValue: String, sortedWire: Data)
}

func makeSample<Payload>(_ name: String, _ direction: SampleDirection,
                         _ envelope: CompanionEnvelope<Payload>) throws -> SessionSample {
    SessionSample(name: name,
                  direction: direction,
                  swiftValue: String(reflecting: envelope),
                  wire: try WireCoding.encode(envelope),
                  sortedWire: try sortedWireEncoder().encode(envelope),
                  roundTrip: { data in
                      let decoded = try WireCoding.decode(CompanionEnvelope<Payload>.self, from: data)
                      return (String(reflecting: decoded), try sortedWireEncoder().encode(decoded))
                  })
}

func clientSample(_ name: String, _ envelope: ClientEnvelope) throws -> SessionSample {
    try makeSample(name, .phoneToMac, envelope)
}

func hostSample(_ name: String, _ envelope: HostEnvelope) throws -> SessionSample {
    try makeSample(name, .macToPhone, envelope)
}

// CompanionClient.handshakeVersion (iOS), with the app's revision.
let helloPhone = ClientEnvelope(requestID: helloRequestID,
                                payload: .hello(revision: appAdvertisedRevision,
                                                minimumPeer: appAdvertisedMinimumPeer))

/// The `macStatus` CompanionLink.handleHello puts in its reply: only for a
/// compatible phone at `modalAlertRevision` or later.
func macStatusRule(peerRevision: Int, status: CompanionMacStatus) -> CompanionMacStatus? {
    let compatible = CompanionProtocolVersion.evaluate(peerRevision: peerRevision,
                                                       peerMinimumPeer: appAdvertisedMinimumPeer) == .compatible
    return (compatible && peerRevision >= CompanionProtocolVersion.modalAlertRevision) ? status : nil
}

func macHello(requestID: UInt64, peerRevision: Int, wantsNotificationPermission: Bool, aiAvailable: Bool,
              status: CompanionMacStatus) -> HostEnvelope {
    // CompanionLink.handleHello: the Mac always sets both booleans.
    HostEnvelope(requestID: requestID,
                 payload: .hello(revision: CompanionProtocolVersion.current,
                                 minimumPeer: CompanionProtocolVersion.minimumPeer,
                                 wantsNotificationPermission: wantsNotificationPermission,
                                 aiAvailable: aiAvailable,
                                 macStatus: macStatusRule(peerRevision: peerRevision, status: status)))
}

// The Mac's real reply to the app's hello: no macStatus below revision 14.
let helloMac = macHello(requestID: helloRequestID, peerRevision: appAdvertisedRevision,
                        wantsNotificationPermission: false, aiAvailable: true,
                        status: CompanionMacStatus(modalAlerts: [], mainBlocked: false))
// Reference: the reply to a phone at modalAlertRevision or later (not this milestone).
let helloMacWithAlerts = macHello(requestID: helloRequestID,
                                  peerRevision: CompanionProtocolVersion.modalAlertRevision,
                                  wantsNotificationPermission: true, aiAvailable: false,
                                  status: CompanionMacStatus(modalAlerts: [sampleModalAlert], mainBlocked: true))
// CompanionClient.registerRoomSecret (iOS) and its ack in CompanionLink.handle.
let relayRoomSecret = ClientEnvelope(requestID: relayRoomSecretRequestID,
                                     payload: .relayRoomSecret(sampleRoomSecret))
let relayRoomSecretStored = HostEnvelope(requestID: relayRoomSecret.requestID, payload: .relayRoomSecretStored)
// CompanionClient.ping (iOS) and CompanionLink.handle's `.pong`.
let ping = ClientEnvelope(requestID: pingRequestID, payload: .ping)
let pong = HostEnvelope(requestID: ping.requestID, payload: .pong)
// CompanionLink.handle, `.relayRoomSecret` failure: the store throws
// RoomSecretStorageStopped once an unpair has begun, and the Mac replies with
// `"\(error)"`, that error's description.
let errorReply = HostEnvelope(requestID: relayRoomSecret.requestID,
                              payload: .error(CompanionError(code: .internalError,
                                                             message: "This Mac is no longer paired.")))
// CompanionClient.sendUnpairing (iOS): `session.send`, so no requestID.
let unpairing = ClientEnvelope(requestID: nil, payload: .unpairing)
// CompanionLink.sendFarewellAndClose: unsolicited, no requestID.
let unpaired = HostEnvelope(requestID: nil, payload: .unpaired)

func hasMacStatus(_ envelope: HostEnvelope) -> Bool {
    if case .hello(_, _, _, _, let status) = envelope.payload {
        return status != nil
    }
    return false
}

// Call-site rules.
require(!hasMacStatus(helloMac), "hello-mac: macStatus sent to a phone below modalAlertRevision")
require(hasMacStatus(helloMacWithAlerts), "hello-mac-with-alerts: macStatus missing")
require(helloMac.requestID == helloPhone.requestID && helloMacWithAlerts.requestID == helloPhone.requestID,
        "hello replies must echo the hello's requestID")
require(pong.requestID == ping.requestID, "pong must echo the ping's requestID")
require(relayRoomSecretStored.requestID == relayRoomSecret.requestID, "ack must echo the relayRoomSecret's requestID")
require(errorReply.requestID == relayRoomSecret.requestID, "error-reply must echo the relayRoomSecret's requestID")
require(unpairing.requestID == nil && unpaired.requestID == nil, "unpairing and unpaired carry no requestID")

let sessionSamples: [SessionSample] = [
    try clientSample("hello-phone", helloPhone),
    try hostSample("hello-mac", helloMac),
    try hostSample("hello-mac-with-alerts", helloMacWithAlerts),
    try clientSample("relay-room-secret", relayRoomSecret),
    try hostSample("relay-room-secret-stored", relayRoomSecretStored),
    try clientSample("ping", ping),
    try hostSample("pong", pong),
    try hostSample("error-reply", errorReply),
    try clientSample("unpairing", unpairing),
    try hostSample("unpaired", unpaired)
]

/// A JSON string literal, as JSONEncoder writes a String.
func jsonString(_ text: String) throws -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.withoutEscapingSlashes]
    return String(decoding: try encoder.encode(text), as: UTF8.self)
}

/// The sample file. Written by hand so `json` holds the sorted upstream bytes
/// verbatim, including upstream's escaping of '/' as "\/".
func sampleFile(_ sample: SessionSample) throws -> Data {
    let text = "{\n"
        + "  \"direction\" : \(try jsonString(sample.direction.rawValue)),\n"
        + "  \"swiftValue\" : \(try jsonString(sample.swiftValue)),\n"
        + "  \"json\" : \(String(decoding: sample.sortedWire, as: UTF8.self))\n"
        + "}\n"
    return Data(text.utf8)
}

func jsonTree(_ data: Data) throws -> NSDictionary? {
    try JSONSerialization.jsonObject(with: data) as? NSDictionary
}

/// Checks every sample: upstream's bytes and the sorted bytes are one JSON
/// tree, both decode with upstream `WireCoding` to the same value, and the
/// file's embedded `json` decodes to it too.
func checkSessionSamples(_ samples: [SessionSample]) throws {
    require(!samples.isEmpty, "a session sample list is empty")
    for sample in samples {
        // Upstream's own bytes and the sorted bytes are the same JSON tree.
        // NSDictionary equality conflates true and 1; the decode round-trip below
        // compares typed values and catches that.
        let wireTree = try jsonTree(sample.wire)
        let sortedTree = try jsonTree(sample.sortedWire)
        require(wireTree != nil && wireTree == sortedTree,
                "\(sample.name): sorted encoding differs from WireCoding.encode")
        // Both decode with upstream WireCoding back to the same value.
        for data in [sample.wire, sample.sortedWire] {
            let decoded = try sample.roundTrip(data)
            require(decoded.swiftValue == sample.swiftValue, "\(sample.name): decoded value differs from the sample")
            require(decoded.sortedWire == sample.sortedWire, "\(sample.name): re-encoding differs from the sample")
        }
        require(!sample.swiftValue.contains(".unsupported"), "\(sample.name): sample is unsupported")
        // The file parses, and its `json` is the same tree, and decodes the same.
        let file = try JSONSerialization.jsonObject(with: try sampleFile(sample)) as? NSDictionary
        let embedded = file?["json"] as? NSDictionary
        require(embedded != nil && embedded == wireTree, "\(sample.name): embedded json differs from the wire bytes")
        let embeddedData = try JSONSerialization.data(withJSONObject: embedded ?? [:])
        let embeddedValue = try sample.roundTrip(embeddedData).swiftValue
        require(embeddedValue == sample.swiftValue,
                "\(sample.name): embedded json does not decode to the sample")
    }
    require(Set(samples.map(\.name)).count == samples.count, "duplicate session sample name")
}

func writeSessionSamples(_ samples: [SessionSample], to directory: URL) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    for sample in samples {
        try sampleFile(sample).write(to: directory.appendingPathComponent("\(sample.name).json"), options: .atomic)
    }
}

try checkSessionSamples(sessionSamples)
try writeSessionSamples(sessionSamples,
                        to: outputDirectory.appendingPathComponent("messages/session", isDirectory: true))

// MARK: - Pairing confirmation

struct ConfirmationSample: Encodable {
    let bytes: String
}

let confirmationDirectory = outputDirectory.appendingPathComponent("messages/confirmation", isDirectory: true)
try FileManager.default.createDirectory(at: confirmationDirectory, withIntermediateDirectories: true)
let confirmations: [(name: String, value: PairingConfirmation)] = [("accepted", .accepted), ("rejected", .rejected)]
for confirmation in confirmations {
    let wire = confirmation.value.encoded()
    require(PairingConfirmation.decode(wire) == confirmation.value, "\(confirmation.name): does not round-trip")
    var file = try encoder.encode(ConfirmationSample(bytes: String(decoding: wire, as: UTF8.self)))
    file.append(0x0a)
    try file.write(to: confirmationDirectory.appendingPathComponent("\(confirmation.name).json"), options: .atomic)
}

// MARK: - Protocol revision

struct RevisionSample: Encodable {
    let revision: Int
    let minimumPeer: Int
}

var revisionFile = try encoder.encode(RevisionSample(revision: CompanionProtocolVersion.current,
                                                     minimumPeer: CompanionProtocolVersion.minimumPeer))
revisionFile.append(0x0a)
let messagesDirectory = outputDirectory.appendingPathComponent("messages", isDirectory: true)
try revisionFile.write(to: messagesDirectory.appendingPathComponent("revision.json"), options: .atomic)
print("wire-fixtures: wrote \(messagesDirectory.path): session=\(sessionSamples.count) "
      + "confirmation=\(confirmations.count) revision=\(CompanionProtocolVersion.current)")

// MARK: - Live session samples and media fixtures
//
// The HEVC clip comes first: the stream configs carry its parameter sets.
// Generators are in MediaSamples.swift and LiveSessionSamples.swift.

let terminalClip = try loadTerminalClip(outputDirectory.appendingPathComponent("media/hevc/terminal-clip.hevc"))
let liveSessionSampleList = try liveSessionSamples(clip: terminalClip)
try checkSessionSamples(liveSessionSampleList)
// Older-Mac samples are checked in olderMacSamples: upstream decodes them to
// the full sample's value, but re-encoding adds the omitted key back.
let olderMacSampleList = try olderMacSamples(liveSamples: liveSessionSampleList)
require(!olderMacSampleList.isEmpty, "the older-Mac sample list is empty")
let allLiveSessionSamples = liveSessionSampleList + olderMacSampleList
require(Set(allLiveSessionSamples.map(\.name)).count == allLiveSessionSamples.count, "duplicate live-session sample name")
try writeSessionSamples(allLiveSessionSamples,
                        to: outputDirectory.appendingPathComponent("messages/live-session", isDirectory: true))
print("wire-fixtures: wrote \(messagesDirectory.path)/live-session: samples=\(liveSessionSampleList.count) "
      + "olderMac=\(olderMacSampleList.count)")
try writeMediaFixtures(clip: terminalClip, to: outputDirectory.appendingPathComponent("media", isDirectory: true))
