#!/usr/bin/env bash
# Starts the local companion relay behind TLS, at the origin iTerm2 is seeded with:
#
# 1. Fetches gnachman/iterm2-companion-relay at the commit scripts/relay-it.sh pins, the same
#    way (shallow fetch of the commit, pnpm-workspace.yaml allowing better-sqlite3's native
#    build, pnpm install --prod) and checks better-sqlite3 loads.
# 2. Makes a throwaway CA and a localhost certificate (30 days) and trusts the CA system-wide,
#    because iTerm2 has no TLS override for the relay (CompanionPluginClient.swift:305-327).
# 3. Starts the relay on 127.0.0.1:$RELAY_PORT in open mode (ATTEST_REQUIRED=false) with
#    RELAY_ORIGIN equal to the seeded origin and RELAY_LOG on, then tls-proxy.mjs on
#    localhost:$TLS_PROXY_PORT in front of it. Both run in the background for the rest of the
#    job; their logs go to the diagnostics directory.
#
# Needs git, node (20+), pnpm, openssl and sudo.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools git node pnpm openssl curl security sudo osascript

# --- 1. The relay -----------------------------------------------------------------------------

log "fetching the relay at ${RELAY_COMMIT:0:7} into $RELAY_DIR"
rm -rf "$RELAY_DIR"
git init --quiet "$RELAY_DIR"
git -C "$RELAY_DIR" remote add origin "$RELAY_REPO"
git -C "$RELAY_DIR" fetch --quiet --depth 1 origin "$RELAY_COMMIT" || die "could not fetch $RELAY_COMMIT from $RELAY_REPO"
git -C "$RELAY_DIR" checkout --quiet FETCH_HEAD
actual="$(git -C "$RELAY_DIR" rev-parse HEAD)"
[[ "$actual" == "$RELAY_COMMIT" ]] || die "checked out $actual, expected $RELAY_COMMIT"

cat > "$RELAY_DIR/pnpm-workspace.yaml" <<'YAML'
allowBuilds:
  better-sqlite3: true
YAML
log "pnpm install --prod (pnpm $(pnpm --version), node $(node --version))"
(cd "$RELAY_DIR" && pnpm install --prod) || die "pnpm install failed in the relay checkout"
(cd "$RELAY_DIR" && node -e "require('better-sqlite3')") ||
    die "better-sqlite3 does not load; its native build failed"
log "better-sqlite3 loads"

# --- 2. TLS -----------------------------------------------------------------------------------

log "openssl: $(openssl version)"
rm -rf "$TLS_DIR"
mkdir -p "$TLS_DIR"
cat > "$TLS_DIR/openssl.cnf" <<'CNF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = placeholder
[ca_extensions]
basicConstraints = critical, CA:true
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF
cat > "$TLS_DIR/leaf.ext" <<'EXT'
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, IP:127.0.0.1, IP:::1
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid, issuer
EXT
openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days 30 \
    -config "$TLS_DIR/openssl.cnf" -extensions ca_extensions \
    -subj "/CN=iTerm2 Buddy CI throwaway relay CA $(date -u +%Y%m%d%H%M%S)" \
    -keyout "$TLS_DIR/ca.key" -out "$TLS_DIR/ca.pem" 2>/dev/null || die "could not make the CA"
openssl req -new -newkey rsa:2048 -nodes -sha256 -config "$TLS_DIR/openssl.cnf" -subj "/CN=localhost" \
    -keyout "$TLS_DIR/localhost.key" -out "$TLS_DIR/localhost.csr" 2>/dev/null || die "could not make the certificate request"
openssl x509 -req -sha256 -days 30 -in "$TLS_DIR/localhost.csr" -CA "$TLS_DIR/ca.pem" -CAkey "$TLS_DIR/ca.key" \
    -CAcreateserial -extfile "$TLS_DIR/leaf.ext" -out "$TLS_DIR/localhost.pem" 2>/dev/null || die "could not sign the certificate"
chmod 600 "$TLS_DIR"/*.key
openssl x509 -in "$TLS_DIR/localhost.pem" -noout -subject -issuer -dates -ext subjectAltName 2>/dev/null | indent ||
    openssl x509 -in "$TLS_DIR/localhost.pem" -noout -subject -issuer -dates | indent

log "trusting the CA in the System keychain"
# Lets add-trusted-cert change admin trust settings without a GUI authorization prompt.
sudo security authorizationdb write com.apple.trust-settings.admin allow >/dev/null ||
    warn "could not relax com.apple.trust-settings.admin; add-trusted-cert may ask for interaction"
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "$TLS_DIR/ca.pem" ||
    die "could not trust the CA system-wide; iTerm2 would reject the relay's certificate"
verdict="$(security verify-cert -c "$TLS_DIR/localhost.pem" -c "$TLS_DIR/ca.pem" -p ssl -s localhost 2>&1)" ||
    die "macOS does not trust the localhost certificate for SSL: $verdict"
log "security verify-cert (policy ssl, host localhost): $verdict"

# --- 3. Relay and proxy -----------------------------------------------------------------------

: > "$RELAY_LOG"
log "starting the relay on 127.0.0.1:$RELAY_PORT (open mode, RELAY_ORIGIN=$RELAY_ORIGIN)"
(
    cd "$RELAY_DIR"
    RELAY_HOST=127.0.0.1 RELAY_PORT="$RELAY_PORT" RELAY_DB="$WORK_DIR/relay.db" \
        ATTEST_REQUIRED=false RELAY_ORIGIN="$RELAY_ORIGIN" RELAY_LOG=true \
        nohup node bin/relay.js >> "$RELAY_LOG" 2>&1 &
    echo $! > "$WORK_DIR/relay.pid"
)
relay_listening() { grep -q "listening on 127.0.0.1:$RELAY_PORT" "$RELAY_LOG"; }
wait_for 30 relay_listening || { sed 's/^/    relay: /' "$RELAY_LOG"; die "the relay did not start listening on 127.0.0.1:$RELAY_PORT"; }
log "relay: $(grep -m1 'listening on' "$RELAY_LOG")"

: > "$TLS_PROXY_LOG"
log "starting the TLS proxy on localhost:$TLS_PROXY_PORT"
TLS_PROXY_PORT="$TLS_PROXY_PORT" RELAY_UPSTREAM_PORT="$RELAY_PORT" \
    TLS_CERT_FILE="$TLS_DIR/localhost.pem" TLS_KEY_FILE="$TLS_DIR/localhost.key" \
    nohup node "$REAL_ITERM2_SCRIPTS/tls-proxy.mjs" >> "$TLS_PROXY_LOG" 2>&1 &
echo $! > "$WORK_DIR/tls-proxy.pid"
proxy_listening() { grep -q "listening on 127.0.0.1:$TLS_PROXY_PORT" "$TLS_PROXY_LOG"; }
wait_for 30 proxy_listening || { sed 's/^/    tls-proxy: /' "$TLS_PROXY_LOG"; die "the TLS proxy did not start"; }
sed 's/^/    /' "$TLS_PROXY_LOG"

# The relay answers a plain GET on / with some HTTP status; any status proves TLS and the hop.
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --cacert "$TLS_DIR/ca.pem" \
    "https://localhost:$TLS_PROXY_PORT/" 2>&1)" || die "curl through the TLS proxy failed: $status"
[[ "$status" =~ ^[1-5][0-9][0-9]$ ]] || die "curl through the TLS proxy got no HTTP status ($status)"
log "https://localhost:$TLS_PROXY_PORT/ answers HTTP $status through the proxy (curl with the CA file)"

# The same request through Foundation, which uses the system trust store as iTerm2 does.
foundation="$(osascript -l JavaScript -e "ObjC.import('Foundation');
    var error = Ref();
    var url = \$.NSURL.URLWithString('https://localhost:$TLS_PROXY_PORT/');
    var data = \$.NSData.dataWithContentsOfURLOptionsError(url, 0, error);
    data.isNil() ? ('error: ' + error[0].localizedDescription.js) : ('ok: ' + data.length + ' bytes')" 2>&1 || true)"
case "$foundation" in
    ok:*) log "Foundation fetch with system trust: $foundation" ;;
    *) warn "Foundation could not fetch https://localhost:$TLS_PROXY_PORT/ with system trust ($foundation). A non-2xx answer also reads as an error here, so check the TLS proxy log before blaming trust." ;;
esac
log "the relay is up at $RELAY_ORIGIN (ws://127.0.0.1:$RELAY_PORT for the JVM client)"
