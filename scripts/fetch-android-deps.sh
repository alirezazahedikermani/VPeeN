#!/usr/bin/env bash
# Fetch the native sources the Android build needs (pinned).
#
#   hev-socks5-tunnel  - tun2socks for Android (JNI), MIT licensed
#                        https://github.com/heiher/hev-socks5-tunnel
#
# Usage: scripts/fetch-android-deps.sh            (from the repo root)
#        HEV_REF=2.19.3 scripts/fetch-android-deps.sh
set -euo pipefail

HEV_REF="${HEV_REF:-2.19.3}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/android/app/src/main/jni/hev-socks5-tunnel"

if [ -f "$DEST/.vpeen-ref" ] && [ "$(cat "$DEST/.vpeen-ref")" = "$HEV_REF" ]; then
    echo "hev-socks5-tunnel $HEV_REF already present"
    exit 0
fi

rm -rf "$DEST"
git clone --depth 1 --branch "$HEV_REF" --recurse-submodules --shallow-submodules \
    https://github.com/heiher/hev-socks5-tunnel "$DEST"
echo "$HEV_REF" > "$DEST/.vpeen-ref"
echo "hev-socks5-tunnel $HEV_REF -> $DEST"
