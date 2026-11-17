import CompanionProtocol
import Foundation

// History tile and selection message samples (messages/history-and-selection/*.json),
// in the same `{"direction", "swiftValue", "json"}` format as messages/session.
//
// They continue the live session of LiveSessionSamples.swift: stream 1 of the
// terminal session, generation 1, 3000 lines of overflow, 1000 lines of
// scrollback and 25 rows (liveTop 4000). Phone-to-Mac envelopes are built the
// way iOS CompanionClient builds them (`request` for fetchHistoryTile and
// copySelection, `send` for the rest); Mac-to-phone envelopes the way
// CompanionHostBridge builds them. Paths are upstream v3.7.7 paths; the Mac and
// iOS sources are not compiled here, so the call sites are cited instead.

/// Request IDs continue the Android client's order after the live session's
/// (fetchSessionTree 4, startSessionStream 5, the browser start 6).
let fetchHistoryTileRequestID: UInt64 = 7
let copySelectionRequestID: UInt64 = 8

/// iOS asks for 50-line tiles (SessionView.swift:1370); the Mac renders at
/// most 200 lines per tile (CompanionHostBridge.swift:2044).
let historyLinesPerTile = 50
let hostMaxTileLines = 200

/// The smallest valid PNG: one 8-bit grayscale black pixel, with a stored
/// (uncompressed) deflate block, so it is byte-identical on every platform.
///
/// Not upstream's output: the Mac renders a tile with AppKit
/// (`PTYTextView.renderImage(withLines:...)` then `NSImage.dataForFile(of: .png)`,
/// CompanionHostBridge.swift:2145-2155), which does not exist off macOS. A real
/// tile is `covered.count` lines of `cellHeight` encoded pixels tall and
/// `columns * cellWidth` wide (no margins); only the PNG container matters here.
func minimalPNG() -> Data {
    func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1
            }
        }
        return ~crc
    }
    func adler32(_ bytes: [UInt8]) -> UInt32 {
        var a: UInt32 = 1
        var b: UInt32 = 0
        for byte in bytes {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }
    func bigEndian(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24), UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
    }
    func chunk(_ type: String, _ body: [UInt8]) -> [UInt8] {
        let typed = Array(type.utf8) + body
        return bigEndian(UInt32(body.count)) + typed + bigEndian(crc32(typed))
    }
    // Width 1, height 1, bit depth 8, grayscale, deflate, adaptive filter, no interlace.
    let header = bigEndian(1) + bigEndian(1) + [8, 0, 0, 0, 0]
    let scanlines: [UInt8] = [0, 0]  // filter type None, one black pixel
    let length = UInt16(scanlines.count)
    let stored: [UInt8] = [0x01, UInt8(length & 0xFF), UInt8(length >> 8), UInt8(~length & 0xFF), UInt8(~length >> 8)]
    let zlib: [UInt8] = [0x78, 0x01] + stored + scanlines + bigEndian(adler32(scanlines))
    let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    return Data(signature + chunk("IHDR", header) + chunk("IDAT", zlib) + chunk("IEND", []))
}

/// Every `CompanionSelectionMode` raw value (CompanionMessages.swift:262-268).
let selectionModeRawValues = ["character", "word", "line", "smart"]

func historySelectionSamples() throws -> [SessionSample] {
    let streamID = LiveStream.streamID
    let generation = LiveStream.generationId
    let watched = SyntheticMac.shell.guid
    var samples: [SessionSample] = []

    // MARK: Phone to Mac

    // The first history slot: iOS keys tiles at firstAbsLine + 50 * index and
    // asks for min(50, history lines left) (SessionView.swift:2496-2525), at the
    // stream's current generation (AppModel.swift:4926-4944);
    // CompanionClient.swift:254-258 sends a request.
    let slotFirstAbsLine = LiveStream.scrollbackOverflow
    let slotLineCount = min(historyLinesPerTile, Int(LiveStream.liveTop - slotFirstAbsLine))
    samples.append(try clientSample("fetch-history-tile",
                                    ClientEnvelope(requestID: fetchHistoryTileRequestID,
                                                   payload: .fetchHistoryTile(streamID: streamID,
                                                                              firstAbsLine: slotFirstAbsLine,
                                                                              lineCount: slotLineCount,
                                                                              generationId: generation))))

    // SessionView.swift:2422-2448: one notch per line of past-edge travel, `up`
    // for a finger moving down at the top; AppModel.swift:4657-4660 and
    // CompanionClient.swift:321-323 (`send`).
    samples.append(try clientSample("report-scroll-wheel-up",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .reportScrollWheel(streamID: streamID, up: true, lines: 3))))
    samples.append(try clientSample("report-scroll-wheel-down",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .reportScrollWheel(streamID: streamID, up: false, lines: 1))))

    // A long press on live row 10 at column 2, dragged to row 11 column 41.
    // iOS maps touches with CompanionTouchMapper (visual columns, absolute
    // lines) and sends through AppModel.swift:4765-4779 and
    // CompanionClient.swift:305-311 (`send`). iOS itself only begins in
    // character mode (SessionView.swift:2749-2837); the Mac also accepts word,
    // line and smart (CompanionHostBridge.swift:822-847), and reads `mode` only
    // on begin (CompanionMessages.swift:852-857).
    let anchor = CompanionSelectionPoint(absLine: LiveStream.liveTop + 10, column: 2)
    let live = CompanionSelectionPoint(absLine: LiveStream.liveTop + 11, column: 41)
    for raw in selectionModeRawValues {
        guard let mode = CompanionSelectionMode(rawValue: raw) else {
            require(false, "\(raw) is not a CompanionSelectionMode")
            continue
        }
        samples.append(try clientSample("selection-gesture-begin-\(raw)",
                                        ClientEnvelope(requestID: nil,
                                                       payload: .selectionGesture(streamID: streamID, phase: .begin,
                                                                                  mode: mode, point: anchor))))
    }
    samples.append(try clientSample("selection-gesture-move",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .selectionGesture(streamID: streamID, phase: .move,
                                                                              mode: .character, point: live))))
    samples.append(try clientSample("selection-gesture-end",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .selectionGesture(streamID: streamID, phase: .end,
                                                                              mode: .character, point: live))))
    // AppModel.swift:5089-5094, CompanionClient.swift:314-316 (`send`).
    samples.append(try clientSample("clear-selection",
                                    ClientEnvelope(requestID: nil, payload: .clearSelection(streamID: streamID))))
    // AppModel.swift:5105-5110, CompanionClient.swift:366-369 (`send`).
    samples.append(try clientSample("select-all-in-stream",
                                    ClientEnvelope(requestID: nil, payload: .selectAllInStream(streamID: streamID))))
    // AppModel.swift:5096-5103 (by session guid, not stream),
    // CompanionClient.swift:410-419 (`request`).
    samples.append(try clientSample("copy-selection",
                                    ClientEnvelope(requestID: copySelectionRequestID,
                                                   payload: .copySelection(sessionGuid: watched))))

    // MARK: Mac to phone

    // CompanionHostBridge.swift:2109-2161: the request is clamped to the
    // current window [overflow, overflow + numberOfLines) and capped at 200
    // lines; the reply echoes the request's stream, generation and ID and
    // reports the covered range plus the window.
    let window = CompanionHistoryWindow(firstAbsLine: LiveStream.scrollbackOverflow, lineCount: LiveStream.totalLines)
    guard let covered = window.clamped(absLine: slotFirstAbsLine, count: min(slotLineCount, hostMaxTileLines)) else {
        require(false, "the first history slot is not in the window")
        return []
    }
    require(covered.absLine == slotFirstAbsLine && covered.count == slotLineCount, "the first slot is clamped")
    samples.append(try hostSample("history-tile",
                                  HostEnvelope(requestID: fetchHistoryTileRequestID,
                                               payload: .historyTile(CompanionHistoryTile(
                                                   streamID: streamID, generationId: generation,
                                                   firstAbsLine: covered.absLine, lineCount: covered.count,
                                                   windowFirstAbsLine: window.firstAbsLine,
                                                   windowLineCount: window.lineCount,
                                                   pngData: minimalPNG())))))

    // 100 more lines scrolled off before the Mac rendered the slot: the
    // request is entirely evicted, so the reply is a 0-line tile with empty
    // PNG data at max(requested, overflow) (CompanionHostBridge.swift:2124-2134).
    let trimmed = CompanionHistoryWindow(firstAbsLine: LiveStream.scrollbackOverflow + 100,
                                         lineCount: LiveStream.totalLines)
    require(trimmed.clamped(absLine: slotFirstAbsLine, count: slotLineCount) == nil, "the evicted slot overlaps")
    samples.append(try hostSample("history-tile-evicted",
                                  HostEnvelope(requestID: fetchHistoryTileRequestID,
                                               payload: .historyTile(CompanionHistoryTile(
                                                   streamID: streamID, generationId: generation,
                                                   firstAbsLine: max(slotFirstAbsLine, trimmed.firstAbsLine), lineCount: 0,
                                                   windowFirstAbsLine: trimmed.firstAbsLine,
                                                   windowLineCount: trimmed.lineCount,
                                                   pngData: Data())))))

    // CompanionSessionStreamer.swift:330-340 fires on a firstAbsLine advance
    // or a totalLines drop; CompanionHostBridge.swift:1252-1257 sends it with
    // no requestID on the coalescing lane. Here: the same 100-line trim, with
    // the scrollback still full.
    samples.append(try hostSample("stream-extent",
                                  HostEnvelope(requestID: nil,
                                               payload: .streamExtent(streamID: streamID,
                                                                      firstAbsLine: trimmed.firstAbsLine,
                                                                      totalLines: trimmed.lineCount))))

    // CompanionHostBridge.swift:1109-1179: after the drag above, the span in
    // visual columns with an inclusive end (inclusiveSelectionEnd, :984-988),
    // unsolicited on the coalescing lane.
    samples.append(try hostSample("selection-range",
                                  HostEnvelope(requestID: nil,
                                               payload: .selectionRange(streamID: streamID,
                                                                        range: CompanionSelectionRange(start: anchor,
                                                                                                       end: live)))))
    // After clearSelection (CompanionHostBridge.swift:921-931): no selection,
    // so `range` is nil and Swift omits the key.
    samples.append(try hostSample("selection-range-cleared",
                                  HostEnvelope(requestID: nil,
                                               payload: .selectionRange(streamID: streamID, range: nil))))

    // CompanionHostBridge.swift:1181-1184: the selected text, or "" when
    // nothing is selected, echoing the copySelection's ID.
    samples.append(try hostSample("selection-text",
                                  HostEnvelope(requestID: copySelectionRequestID,
                                               payload: .selectionText(text: "make test\ngit status --short"))))
    samples.append(try hostSample("selection-text-empty",
                                  HostEnvelope(requestID: copySelectionRequestID,
                                               payload: .selectionText(text: ""))))

    // CompanionHostBridge.swift:2115-2120: a tile request for a stream the Mac
    // no longer has (ended, or from an earlier link).
    samples.append(try hostSample("error-no-such-stream",
                                  HostEnvelope(requestID: fetchHistoryTileRequestID,
                                               payload: .error(CompanionError(code: .badRequest,
                                                                              message: "No such stream.")))))

    require(samples.filter { $0.name.hasPrefix("selection-gesture-begin-") }.count == selectionModeRawValues.count,
            "a selection mode sample is missing")
    return samples
}
