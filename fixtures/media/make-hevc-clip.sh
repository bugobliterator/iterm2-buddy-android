#!/bin/sh
# Generates hevc/terminal-clip.hevc: 15 frames of 1280x800 HEVC Main, Annex-B,
# with VPS/SPS/PPS in-band before each IDR, IDRs (not CRA) at frames 0 and 10,
# P-frames between and no B-frames (the Mac's encoder has no reordering).
# The wire-fixture harness then moves the parameter sets into codecExtradata
# and strips them from the access units. The committed clip is the reference:
# the versions it was made with are in ../README.md.
#
# open-gop=0: with ffmpeg 6.1.1 and x265 3.5 a forced keyframe after the
# first one is otherwise coded as a CRA (NAL type 21), not an IDR, even with
# -forced-idr 1.
set -eu
cd "$(dirname "$0")/hevc"
ffmpeg -hide_banner -nostdin -y \
    -f lavfi -i testsrc2=size=1280x800:rate=30 -frames:v 15 \
    -c:v libx265 -forced-idr 1 \
    -x265-params bframes=0:keyint=10000:min-keyint=10000:repeat-headers=1:aud=0:open-gop=0 \
    -force_key_frames 0,0.333 -profile:v main \
    -f hevc terminal-clip.hevc
ffprobe -v error -show_frames -show_entries frame=pict_type -of csv=p=0 terminal-clip.hevc \
    | cut -d, -f1 \
    | tr -d '\n' | grep -qx 'IPPPPPPPPPIPPPP' || {
    echo "make-hevc-clip: expected 15 frames, I at 0 and 10, P elsewhere" >&2
    exit 1
}
