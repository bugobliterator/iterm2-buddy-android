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
let projectRelayOrigin = "https://siddharths-macbook-pro.tailnet-99bb.ts.net"
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
    ("wrongHost", "iterm2://pairing?v=1&proto=\(protocolName)&rs=\(base64URL(macStaticKey))&pid=\(pairingID)")
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
let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
var json = try encoder.encode(vectors)
json.append(0x0a)
let destination = outputDirectory.appendingPathComponent("crypto-vectors.json")
try json.write(to: destination, options: .atomic)
print("wire-fixtures: wrote \(destination.path): canonical=\(canonical.count) pairingCodes=\(pairingCodes.count) "
      + "roomNames=\(roomNames.count) sas=\(sas.count) joins=\(joins.count) prologues=\(prologues.count)")
