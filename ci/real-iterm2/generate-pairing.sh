#!/usr/bin/env bash
# Mints this run's throwaway pairing with GenerateCiPairing.java (JDK 17 on PATH): a Mac static
# key pair, a phone static key pair, a room secret and a pairing ID, written to $PAIRING_FILE
# (mode 600). seed-pairing.sh gives iTerm2 the Mac side; the JVM test reads the phone side.
# Only the pairing ID and the public keys' first bytes are printed.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_tools java

java -version 2>&1 | indent
java "$REAL_ITERM2_SCRIPTS/GenerateCiPairing.java" "$PAIRING_FILE" "$RELAY_ORIGIN"
for key in PAIRING_ID RELAY_ORIGIN MAC_STATIC_PRIVATE_KEY_HEX MAC_STATIC_PUBLIC_KEY_HEX \
    PHONE_STATIC_PRIVATE_KEY_HEX PHONE_STATIC_PUBLIC_KEY_HEX ROOM_SECRET_HEX; do
    pairing_value "$key" >/dev/null
done
for key in MAC_STATIC_PRIVATE_KEY_HEX MAC_STATIC_PUBLIC_KEY_HEX PHONE_STATIC_PRIVATE_KEY_HEX \
    PHONE_STATIC_PUBLIC_KEY_HEX ROOM_SECRET_HEX; do
    [[ "$(pairing_value "$key")" =~ ^[0-9a-f]{64}$ ]] || die "$key is not 32 bytes of hex"
done
log "pairing ID $(pairing_value PAIRING_ID), relay origin $(pairing_value RELAY_ORIGIN)"
log "Mac public key $(pairing_value MAC_STATIC_PUBLIC_KEY_HEX | cut -c1-8)..., phone public key $(pairing_value PHONE_STATIC_PUBLIC_KEY_HEX | cut -c1-8)..."
