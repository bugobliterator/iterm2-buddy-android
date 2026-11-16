#!/bin/sh
# Checks hevc/terminal-clip.json as the phone would use it: rebuilds an
# Annex-B stream from codecExtradata (each parameter set behind 00 00 00 01)
# and the media frames from frame 10 on (each AVCC length replaced by
# 00 00 00 01), then decodes it with ffmpeg. Expects 5 frames and no error.
# Run after `swift run wire-fixtures ../`.
set -eu
cd "$(dirname "$0")/hevc"
stream="$(mktemp)"
trap 'rm -f "$stream"' EXIT
python3 -I - terminal-clip.json "$stream" <<'PY'
import base64, json, struct, sys
clip = json.load(open(sys.argv[1]))
start = b"\x00\x00\x00\x01"
out = bytearray()
extradata = base64.b64decode(clip["codecExtradataBase64"])
(count,) = struct.unpack_from(">H", extradata, 0)
cursor = 2
for _ in range(count):
    (length,) = struct.unpack_from(">I", extradata, cursor)
    cursor += 4
    out += start + extradata[cursor:cursor + length]
    cursor += length
for frame in clip["frames"][10:]:
    data = base64.b64decode(frame["mediaFrameBase64"])
    assert data[0] == 2, "expected a version 2 media frame"
    payload = data[34:]
    cursor = 0
    while cursor < len(payload):
        (length,) = struct.unpack_from(">I", payload, cursor)
        cursor += 4
        out += start + payload[cursor:cursor + length]
        cursor += length
open(sys.argv[2], "wb").write(out)
PY
errors="$(ffmpeg -nostdin -v error -f hevc -i "$stream" -f framemd5 - 2>&1 >/dev/null)" || true
frames="$(ffmpeg -nostdin -v error -f hevc -i "$stream" -f framemd5 - 2>/dev/null | grep -vc '^#')"
echo "check-terminal-clip: decoded $frames frames from frame 10${errors:+, errors: $errors}"
[ -z "$errors" ] && [ "$frames" -eq 5 ]
