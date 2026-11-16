import CompanionProtocol
import Foundation

// Media-channel fixtures: media frames, the parameter-set container, AVCC
// access units, and the HEVC clip packaged as the Mac sends it.
//
// Inputs are fixed byte strings chosen here, plus the committed Annex-B clip
// made by fixtures/media/make-hevc-clip.sh. Every expected byte comes from an
// upstream v3.7.7 function: CompanionMediaFrame.encoded(version:) and
// init(decoding:), CompanionHEVCFraming.encodeParameterSets /
// decodeParameterSets / encodeAccessUnit / decodeAccessUnit, and
// CompanionFrameChannel.frameMedia / classify.

// MARK: - Shared stream values

/// The values the live-session samples and the clip share, so one stream reads
/// consistently across files: the first stream on a link (stream IDs start at 1
/// per bridge, CompanionHostBridge.swift:85, 1216-1217), generation 1 (the first
/// config of a stream, CompanionSessionStreamer.swift:109-119, 575-576).
enum LiveStream {
    static let streamID: UInt32 = 1
    static let generationId: UInt32 = 1
    /// The Mac stamps `CACurrentMediaTime() * 1000` (host uptime in ms,
    /// CompanionHostBridge.swift:1338): about six days of uptime here.
    static let firstPTSMilliseconds: UInt64 = 523_118_042
    /// 30 fps, the phone's maxFrameRate, rounded to whole milliseconds.
    static let frameIntervalMilliseconds: UInt64 = 33
    /// A terminal with the default 1000-line scrollback full, 3000 lines
    /// already scrolled off, and 25 rows on screen:
    /// liveTop = numberOfScrollbackLines + totalScrollbackOverflow,
    /// firstAbsLine = totalScrollbackOverflow, totalLines = scrollback + rows
    /// (CompanionTerminalFrameSource.swift:45-51).
    static let scrollbackLines = 1000
    static let scrollbackOverflow: Int64 = 3000
    static let rows = 25
    static let columns = 80
    static var liveTop: Int64 { Int64(scrollbackLines) + scrollbackOverflow }
    static var totalLines: Int { scrollbackLines + rows }
    /// The clip's encoded size: a 640x400 pt window at backing scale 2.
    static let pixelWidth = 1280
    static let pixelHeight = 800
    static let scale = 2.0
}

// MARK: - HEVC clip

struct TerminalClip {
    /// VPS, SPS and PPS as the clip carries them in band, in stream order.
    let parameterSets: [Data]
    /// `encodeParameterSets(parameterSets)`: the config's codecExtradata.
    let codecExtradata: Data
    /// One v2 media frame per access unit, as the Mac's encoder output.
    let frames: [CompanionMediaFrame]
}

func nalType(_ nal: Data) -> Int {
    Int((nal[nal.startIndex] >> 1) & 0x3f)
}

/// Splits an Annex-B byte stream at its start codes. A NAL unit never ends in
/// a zero byte, so trailing zeros are the next 4-byte start code's zero_byte
/// (or trailing_zero_8bits) and are dropped.
func annexBNALUnits(_ stream: Data) -> [Data] {
    let bytes = [UInt8](stream)
    var starts: [Int] = []
    var index = 0
    while index + 2 < bytes.count {
        if bytes[index] == 0, bytes[index + 1] == 0, bytes[index + 2] == 1 {
            starts.append(index + 3)
            index += 3
        } else {
            index += 1
        }
    }
    require(!starts.isEmpty && starts[0] <= 4, "clip does not begin with a start code")
    return starts.enumerated().map { position, start in
        var end = position + 1 < starts.count ? starts[position + 1] - 3 : bytes.count
        while end > start && bytes[end - 1] == 0 {
            end -= 1
        }
        return Data(bytes[start..<end])
    }
}

/// Groups NAL units into access units: a new one starts at the first slice
/// segment of a picture (first_slice_segment_in_pic_flag) or at a prefix
/// non-VCL unit (VPS, SPS, PPS, AUD, prefix SEI) that follows a picture.
func accessUnits(_ nalUnits: [Data]) -> [[Data]] {
    var units: [[Data]] = []
    var current: [Data] = []
    for nal in nalUnits {
        require(nal.count >= 3, "NAL unit shorter than its header")
        let type = nalType(nal)
        let currentHasPicture = current.contains { nalType($0) < 32 }
        let startsPicture = type < 32 && (nal[nal.startIndex + 2] & 0x80) != 0
        let isPrefix = (32...39).contains(type) || (41...47).contains(type)
        if currentHasPicture && (startsPicture || isPrefix) {
            units.append(current)
            current = []
        }
        current.append(nal)
    }
    if !current.isEmpty {
        units.append(current)
    }
    return units
}

let parameterSetTypes = 32...34
let idrTypes = 19...20

func loadTerminalClip(_ url: URL) throws -> TerminalClip {
    guard let stream = try? Data(contentsOf: url) else {
        require(false, "\(url.path) is missing: run fixtures/media/make-hevc-clip.sh")
        fatalError()
    }
    let units = accessUnits(annexBNALUnits(stream))
    require(units.count == 15, "clip has \(units.count) access units, expected 15")

    var parameterSets: [Data]?
    var frames: [CompanionMediaFrame] = []
    for (index, unit) in units.enumerated() {
        let inBandSets = unit.filter { parameterSetTypes.contains(nalType($0)) }
        let rest = unit.filter { !parameterSetTypes.contains(nalType($0)) }
        // The first VCL NAL after stripping: x265's informational prefix SEI
        // (type 39) may come before it.
        guard let firstPicture = rest.first(where: { nalType($0) < 32 }) else {
            require(false, "access unit \(index) has no picture")
            fatalError()
        }
        let isIDR = idrTypes.contains(nalType(firstPicture))
        let expectIDR = index == 0 || index == 10
        require(isIDR == expectIDR,
                "access unit \(index): first VCL NAL is type \(nalType(firstPicture)), IDR expected \(expectIDR)")
        require(isIDR == !inBandSets.isEmpty, "access unit \(index): parameter sets must come with an IDR only")
        if !inBandSets.isEmpty {
            require(inBandSets.map(nalType) == [32, 33, 34],
                    "access unit \(index): expected one VPS, SPS and PPS in that order")
            if let parameterSets {
                // One encoder for the whole clip, so frame 10 repeats frame 0's
                // sets; terminal-clip.json carries one codecExtradata.
                require(inBandSets == parameterSets, "access unit \(index): parameter sets changed mid-clip")
            } else {
                parameterSets = inBandSets
            }
        }
        // The Mac sets keyframe on a sync sample and configChanged on the
        // keyframe whose parameter sets it built a config from, the next media
        // frame on the wire after that config (CompanionSessionStreamer.swift:561-597).
        // Frame 0 is the stream's first keyframe (config generation 1). Frame 10
        // is an insurance keyframe, 0.3 s after the last P-frame with nothing new
        // (CompanionSessionStreamer.swift:374-382): same encoder, so the same
        // parameter sets, no config resend and so no configChanged.
        var flags: CompanionMediaFrame.Flags = []
        if isIDR {
            flags.insert(.keyframe)
        }
        if index == 0 {
            flags.insert(.configChanged)
        }
        frames.append(CompanionMediaFrame(
            streamID: LiveStream.streamID,
            sequence: UInt32(index),
            ptsMilliseconds: LiveStream.firstPTSMilliseconds + UInt64(index) * LiveStream.frameIntervalMilliseconds,
            flags: flags,
            payload: CompanionHEVCFraming.encodeAccessUnit(nalUnits: rest),
            generationId: LiveStream.generationId,
            liveTop: LiveStream.liveTop))
    }
    guard let parameterSets else {
        require(false, "clip has no parameter sets")
        fatalError()
    }
    let codecExtradata = CompanionHEVCFraming.encodeParameterSets(parameterSets)

    // Checks on what is packaged, read back with upstream's decoders.
    let extradataTypes = try CompanionHEVCFraming.decodeParameterSets(codecExtradata).map(nalType)
    for type in parameterSetTypes {
        require(extradataTypes.filter { $0 == type }.count == 1, "codecExtradata lacks exactly one NAL of type \(type)")
    }
    for (index, frame) in frames.enumerated() {
        let decoded = try CompanionMediaFrame(decoding: frame.encoded())
        require(decoded == frame, "clip frame \(index) does not round-trip")
        let nalUnits = try CompanionHEVCFraming.decodeAccessUnit(decoded.payload)
        require(!nalUnits.contains { parameterSetTypes.contains(nalType($0)) },
                "clip frame \(index) still holds a parameter set")
        if index == 0 || index == 10 {
            let firstPicture = nalUnits.first { nalType($0) < 32 }
            require(firstPicture.map { idrTypes.contains(nalType($0)) } ?? false,
                    "clip frame \(index): first VCL NAL is not IDR after stripping")
            require(decoded.flags.contains(.keyframe), "clip frame \(index) is not flagged keyframe")
            require(decoded.flags.contains(.configChanged) == (index == 0),
                    "clip frame \(index): configChanged only on the first keyframe")
        }
    }
    return TerminalClip(parameterSets: parameterSets, codecExtradata: codecExtradata, frames: frames)
}

// MARK: - Output records

struct ValidMediaFrame: Encodable {
    let name: String
    let version: UInt8
    let flags: UInt8
    let streamID: UInt32
    let sequence: UInt32
    let ptsMilliseconds: UInt64
    let generationId: UInt32?
    let liveTop: Int64?
    let payloadHex: String
    let encodedHex: String
    let channelHex: String
}

struct InvalidMediaFrame: Encodable {
    let name: String
    let encodedHex: String
    let upstreamError: String
    let channelHex: String
}

struct MediaFrameFixtures: Encodable {
    let valid: [ValidMediaFrame]
    let invalid: [InvalidMediaFrame]
}

struct ValidParameterSets: Encodable {
    let name: String
    let setsHex: [String]
    let containerHex: String
}

struct InvalidContainer: Encodable {
    let name: String
    let containerHex: String
    let upstreamError: String
}

struct ParameterSetFixtures: Encodable {
    let valid: [ValidParameterSets]
    let invalid: [InvalidContainer]
}

struct ValidAccessUnit: Encodable {
    let name: String
    let nalUnitsHex: [String]
    let payloadHex: String
}

struct InvalidAccessUnit: Encodable {
    let name: String
    let payloadHex: String
    let upstreamError: String
}

struct AccessUnitFixtures: Encodable {
    let valid: [ValidAccessUnit]
    let invalid: [InvalidAccessUnit]
}

struct ClipFrame: Encodable {
    let flags: UInt8
    let ptsMilliseconds: UInt64
    let generationId: UInt32
    let mediaFrameBase64: String
}

struct ClipFixture: Encodable {
    let source: String
    let pixelWidth: Int
    let pixelHeight: Int
    let codecExtradataBase64: String
    let frames: [ClipFrame]
}

// MARK: - Generators

/// Upstream's error as `String(describing:)` writes it, e.g. `truncated` or
/// `payloadLengthMismatch(declared: 5, actual: 4)`.
func errorName(_ error: Error) -> String {
    String(describing: error)
}

func mediaFrameFixtures() throws -> MediaFrameFixtures {
    let pts = LiveStream.firstPTSMilliseconds
    let inputs: [(name: String, version: UInt8, frame: CompanionMediaFrame)] = [
        ("v1-keyframe-config-changed", CompanionMediaFrame.legacyVersion,
         CompanionMediaFrame(streamID: 1, sequence: 0, ptsMilliseconds: pts, flags: [.keyframe, .configChanged],
                             payload: bytes(9, startingAt: 0x10))),
        ("v1-delta", CompanionMediaFrame.legacyVersion,
         CompanionMediaFrame(streamID: 1, sequence: 1, ptsMilliseconds: pts + 33, flags: [],
                             payload: bytes(5, startingAt: 0x20))),
        ("v2-keyframe-config-changed", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 1, sequence: 0, ptsMilliseconds: pts, flags: [.keyframe, .configChanged],
                             payload: bytes(9, startingAt: 0x10), generationId: 1, liveTop: LiveStream.liveTop)),
        ("v2-delta", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 1, sequence: 1, ptsMilliseconds: pts + 33, flags: [],
                             payload: bytes(5, startingAt: 0x20), generationId: 1, liveTop: LiveStream.liveTop)),
        ("v2-keyframe-only", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 1, sequence: 7, ptsMilliseconds: pts + 600, flags: [.keyframe],
                             payload: bytes(6, startingAt: 0x30), generationId: 2, liveTop: LiveStream.liveTop + 3)),
        ("v2-negative-live-top", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 1, sequence: 2, ptsMilliseconds: pts + 66, flags: [],
                             payload: bytes(4, startingAt: 0x40), generationId: 1, liveTop: -1_234_567_890_123)),
        ("v2-ids-above-2-31", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 0x8000_0001, sequence: 0xffff_fffe, ptsMilliseconds: pts + 99, flags: [],
                             payload: bytes(3, startingAt: 0x50), generationId: 0x8000_0002, liveTop: 0)),
        ("v2-empty-payload", CompanionMediaFrame.version,
         CompanionMediaFrame(streamID: 1, sequence: 3, ptsMilliseconds: pts + 132, flags: [],
                             payload: Data(), generationId: 1, liveTop: LiveStream.liveTop)),
        ("v1-empty-payload", CompanionMediaFrame.legacyVersion,
         CompanionMediaFrame(streamID: 1, sequence: 3, ptsMilliseconds: pts + 132, flags: [],
                             payload: Data()))
    ]
    let valid = try inputs.map { input -> ValidMediaFrame in
        let encoded = input.frame.encoded(version: input.version)
        let decoded = try CompanionMediaFrame(decoding: encoded)
        require(decoded == input.frame, "\(input.name): does not round-trip")
        let channel = CompanionFrameChannel.frameMedia(encoded)
        require(CompanionFrameChannel.classify(channel) == .media(encoded), "\(input.name): channel does not classify")
        let isV2 = input.version == CompanionMediaFrame.version
        return ValidMediaFrame(name: input.name,
                               version: input.version,
                               flags: decoded.flags.rawValue,
                               streamID: decoded.streamID,
                               sequence: decoded.sequence,
                               ptsMilliseconds: decoded.ptsMilliseconds,
                               generationId: isV2 ? decoded.generationId : nil,
                               liveTop: isV2 ? decoded.liveTop : nil,
                               payloadHex: decoded.payload.hexEncodedString(),
                               encodedHex: encoded.hexEncodedString(),
                               channelHex: channel.hexEncodedString())
    }

    let baseFrame = inputs[3].frame
    let v2 = baseFrame.encoded(version: CompanionMediaFrame.version)
    let v1 = baseFrame.encoded(version: CompanionMediaFrame.legacyVersion)
    func withVersion(_ data: Data, _ version: UInt8) -> Data {
        var copy = data
        copy[copy.startIndex] = version
        return copy
    }
    let invalidInputs: [(name: String, bytes: Data)] = [
        ("empty", Data()),
        ("one-byte", Data([CompanionMediaFrame.version])),
        ("version-0", withVersion(v2, 0)),
        ("version-3", withVersion(v2, 3)),
        ("v2-header-one-byte-short", v2.prefix(CompanionMediaFrame.headerSizeV2 - 1)),
        ("v1-header-one-byte-short", v1.prefix(CompanionMediaFrame.headerSizeV1 - 1)),
        ("v2-length-one-more-than-payload", v2.dropLast()),
        ("v2-length-one-less-than-payload", v2 + Data([0x00])),
        ("v1-length-one-more-than-payload", v1.dropLast()),
        ("v1-length-one-less-than-payload", v1 + Data([0x00])),
        ("v2-bytes-read-as-v1", withVersion(v2, CompanionMediaFrame.legacyVersion))
    ]
    let invalid = invalidInputs.map { input -> InvalidMediaFrame in
        let encoded = Data(input.bytes)
        var upstreamError = ""
        do {
            _ = try CompanionMediaFrame(decoding: encoded)
            require(false, "\(input.name): upstream accepts it")
        } catch {
            upstreamError = errorName(error)
        }
        return InvalidMediaFrame(name: input.name,
                                 encodedHex: encoded.hexEncodedString(),
                                 upstreamError: upstreamError,
                                 channelHex: CompanionFrameChannel.frameMedia(encoded).hexEncodedString())
    }
    return MediaFrameFixtures(valid: valid, invalid: invalid)
}

func parameterSetFixtures(clip: TerminalClip) throws -> ParameterSetFixtures {
    let inputs: [(name: String, sets: [Data])] = [
        ("none", []),
        ("one", [bytes(4, startingAt: 0x40)]),
        ("three", [bytes(3, startingAt: 0x40), bytes(5, startingAt: 0x42), bytes(2, startingAt: 0x44)]),
        ("zero-length-set", [bytes(3, startingAt: 0x40), Data(), bytes(2, startingAt: 0x44)]),
        ("terminal-clip", clip.parameterSets)
    ]
    let valid = try inputs.map { input -> ValidParameterSets in
        let container = CompanionHEVCFraming.encodeParameterSets(input.sets)
        require(try CompanionHEVCFraming.decodeParameterSets(container) == input.sets, "\(input.name): does not round-trip")
        return ValidParameterSets(name: input.name,
                                  setsHex: input.sets.map { $0.hexEncodedString() },
                                  containerHex: container.hexEncodedString())
    }
    let three = CompanionHEVCFraming.encodeParameterSets(inputs[2].sets)
    let invalidInputs: [(name: String, bytes: Data)] = [
        ("empty", Data()),
        ("count-one-byte", Data([0x00])),
        ("count-larger-than-data", hexBytes("0002") + CompanionHEVCFraming.encodeParameterSets([bytes(4, startingAt: 0x40)]).dropFirst(2)),
        ("truncated-length", hexBytes("0001") + hexBytes("0000")),
        ("length-overruns-data", hexBytes("0001") + hexBytes("00000005") + bytes(4, startingAt: 0x40)),
        ("last-set-one-byte-short", three.dropLast())
    ]
    let invalid = invalidInputs.map { input -> InvalidContainer in
        let container = Data(input.bytes)
        var upstreamError = ""
        do {
            _ = try CompanionHEVCFraming.decodeParameterSets(container)
            require(false, "\(input.name): upstream accepts it")
        } catch {
            upstreamError = errorName(error)
        }
        return InvalidContainer(name: input.name, containerHex: container.hexEncodedString(), upstreamError: upstreamError)
    }
    return ParameterSetFixtures(valid: valid, invalid: invalid)
}

func accessUnitFixtures(clip: TerminalClip) throws -> AccessUnitFixtures {
    let clipDelta = try CompanionHEVCFraming.decodeAccessUnit(clip.frames[1].payload)
    let inputs: [(name: String, nalUnits: [Data])] = [
        ("empty", []),
        ("one", [bytes(6, startingAt: 0x26)]),
        ("two", [bytes(4, startingAt: 0x4e), bytes(7, startingAt: 0x26)]),
        ("zero-length-nal", [bytes(4, startingAt: 0x4e), Data(), bytes(3, startingAt: 0x26)]),
        ("terminal-clip-delta", clipDelta)
    ]
    let valid = try inputs.map { input -> ValidAccessUnit in
        let payload = CompanionHEVCFraming.encodeAccessUnit(nalUnits: input.nalUnits)
        require(try CompanionHEVCFraming.decodeAccessUnit(payload) == input.nalUnits, "\(input.name): does not round-trip")
        return ValidAccessUnit(name: input.name,
                               nalUnitsHex: input.nalUnits.map { $0.hexEncodedString() },
                               payloadHex: payload.hexEncodedString())
    }
    let two = CompanionHEVCFraming.encodeAccessUnit(nalUnits: inputs[2].nalUnits)
    let invalidInputs: [(name: String, bytes: Data)] = [
        ("length-prefix-three-bytes", hexBytes("000000")),
        ("length-overruns-by-one", hexBytes("00000005") + bytes(4, startingAt: 0x26)),
        ("last-nal-one-byte-short", two.dropLast()),
        ("trailing-partial-length", two + hexBytes("0000"))
    ]
    let invalid = invalidInputs.map { input -> InvalidAccessUnit in
        let payload = Data(input.bytes)
        var upstreamError = ""
        do {
            _ = try CompanionHEVCFraming.decodeAccessUnit(payload)
            require(false, "\(input.name): upstream accepts it")
        } catch {
            upstreamError = errorName(error)
        }
        return InvalidAccessUnit(name: input.name, payloadHex: payload.hexEncodedString(), upstreamError: upstreamError)
    }
    return AccessUnitFixtures(valid: valid, invalid: invalid)
}

func clipFixture(clip: TerminalClip) -> ClipFixture {
    ClipFixture(source: "terminal-clip.hevc",
                pixelWidth: LiveStream.pixelWidth,
                pixelHeight: LiveStream.pixelHeight,
                codecExtradataBase64: clip.codecExtradata.base64EncodedString(),
                frames: clip.frames.map { frame in
                    ClipFrame(flags: frame.flags.rawValue,
                              ptsMilliseconds: frame.ptsMilliseconds,
                              generationId: frame.generationId,
                              mediaFrameBase64: frame.encoded().base64EncodedString())
                })
}

func writeFixture<Value: Encodable>(_ value: Value, to url: URL) throws {
    var data = try fixtureEncoder().encode(value)
    data.append(0x0a)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try data.write(to: url, options: .atomic)
}

func writeMediaFixtures(clip: TerminalClip, to directory: URL) throws {
    let frames = try mediaFrameFixtures()
    let parameterSets = try parameterSetFixtures(clip: clip)
    let units = try accessUnitFixtures(clip: clip)
    let clipFile = clipFixture(clip: clip)
    require(!frames.valid.isEmpty && !frames.invalid.isEmpty, "frames.json has an empty list")
    require(!parameterSets.valid.isEmpty && !parameterSets.invalid.isEmpty, "parameter-sets.json has an empty list")
    require(!units.valid.isEmpty && !units.invalid.isEmpty, "access-units.json has an empty list")
    require(!clipFile.frames.isEmpty, "terminal-clip.json has no frames")
    try writeFixture(frames, to: directory.appendingPathComponent("frames.json"))
    try writeFixture(parameterSets, to: directory.appendingPathComponent("parameter-sets.json"))
    try writeFixture(units, to: directory.appendingPathComponent("access-units.json"))
    try writeFixture(clipFile, to: directory.appendingPathComponent("hevc/terminal-clip.json"))
    print("wire-fixtures: wrote \(directory.path): frames=\(frames.valid.count)+\(frames.invalid.count) "
          + "parameterSets=\(parameterSets.valid.count)+\(parameterSets.invalid.count) "
          + "accessUnits=\(units.valid.count)+\(units.invalid.count) clipFrames=\(clipFile.frames.count)")
}
