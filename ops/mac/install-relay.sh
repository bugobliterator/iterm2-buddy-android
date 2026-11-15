#!/usr/bin/env bash
# Installs the iTerm2 companion relay on this Mac as a launchd agent and
# publishes it on the tailnet with `tailscale serve` (never funnel).
#
# Safe to re-run: it re-pins the checkout, reinstalls, rewrites the env file
# and plist, and reloads the agent. Relay state lives outside the checkout, so
# registrations survive. See README.md in this directory for undo steps.
#
# Needs git, Node 20+, pnpm and tailscale on PATH. Set RELAY_PORT to use a port
# other than 8787 (iTerm2 and the phone only see the tailnet origin).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY_REPO="https://github.com/gnachman/iterm2-companion-relay.git"
RELAY_COMMIT="b8aa2f90d2bc8b617d1b9c97043aa0b171885310"
RELAY_ORIGIN="https://siddharths-macbook-pro.tailnet-99bb.ts.net"
RELAY_PORT="${RELAY_PORT:-8787}"
LABEL="dev.bugobliterator.itermbuddy.relay"

CHECKOUT="$HOME/Library/Application Support/iterm2-companion-relay"
STATE_DIR="$HOME/Library/Application Support/iterm2-companion-relay-state"
ENV_DIR="$HOME/.config/iterm2-companion-relay"
ENV_FILE="$ENV_DIR/relay.env"
LOG="$HOME/Library/Logs/iterm2-companion-relay.log"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

say() { echo "install-relay: $*"; }
die() { echo "install-relay: $*" >&2; exit 1; }

for tool in git node pnpm tailscale jq; do
    command -v "$tool" >/dev/null || die "$tool is not on PATH"
done
RELAY_HOSTNAME="${RELAY_ORIGIN#https://}"
# Without a tailnet HTTPS certificate `tailscale serve` would stop and wait for
# the owner to enable HTTPS in the admin console.
tailscale status --json | jq -e --arg host "$RELAY_HOSTNAME" '.CertDomains // [] | index($host)' >/dev/null \
    || die "tailscale CertDomains does not list $RELAY_HOSTNAME; enable HTTPS certificates for the tailnet"
NODE="$(command -v node)"
node_major="$("$NODE" -p 'process.versions.node.split(".")[0]')"
(( node_major >= 20 )) || die "node $("$NODE" -v) is older than 20"

say "pinning checkout at ${RELAY_COMMIT:0:7} in $CHECKOUT"
if [[ ! -d "$CHECKOUT/.git" ]]; then
    git init --quiet "$CHECKOUT"
    git -C "$CHECKOUT" remote add origin "$RELAY_REPO"
fi
if [[ "$(git -C "$CHECKOUT" rev-parse -q --verify HEAD || true)" != "$RELAY_COMMIT" ]]; then
    git -C "$CHECKOUT" fetch --quiet --depth 1 origin "$RELAY_COMMIT"
    git -C "$CHECKOUT" checkout --quiet --force FETCH_HEAD
fi
[[ "$(git -C "$CHECKOUT" rev-parse HEAD)" == "$RELAY_COMMIT" ]] || die "checkout is not at $RELAY_COMMIT"

# pnpm >= 10.26 refuses unlisted dependency builds; better-sqlite3 needs its
# install script to fetch the native binary.
cat > "$CHECKOUT/pnpm-workspace.yaml" <<'YAML'
allowBuilds:
  better-sqlite3: true
YAML
# CI=true keeps pnpm from prompting before it purges an outdated node_modules.
say "pnpm install --prod (pnpm $(pnpm --version), node $("$NODE" -v))"
(cd "$CHECKOUT" && CI=true pnpm install --prod)
(cd "$CHECKOUT" && "$NODE" -e "require('better-sqlite3')") || die "better-sqlite3 does not load; its native build failed"
say "better-sqlite3 loads"

mkdir -p "$STATE_DIR" "$ENV_DIR" "$(dirname "$PLIST")" "$(dirname "$LOG")"
( umask 077 && cat > "$ENV_FILE" <<EOF
RELAY_HOST=127.0.0.1
RELAY_PORT=$RELAY_PORT
RELAY_DB=$STATE_DIR/relay.db
ATTEST_REQUIRED=false
RELAY_ORIGIN=$RELAY_ORIGIN
RELAY_DAILY_BYTE_QUOTA=8589934592
RELAY_TRUST_PROXY=true
EOF
)
chmod 600 "$ENV_FILE"
say "wrote $ENV_FILE"

# launchd cannot source an env file, so the values go into the plist.
# ponytail: no XML escaping; the values are fixed paths and literals without & or <.
environment=""
while IFS='=' read -r key value; do
    [[ -n "$key" ]] || continue
    environment+="        <key>$key</key>"$'\n'"        <string>$value</string>"$'\n'
done < "$ENV_FILE"
environment="${environment%$'\n'}"
plist="$(cat "$SCRIPT_DIR/$LABEL.plist.template")"
plist="${plist//@NODE@/$NODE}"
plist="${plist//@CHECKOUT@/$CHECKOUT}"
plist="${plist//@LOG@/$LOG}"
plist="${plist//@ENVIRONMENT@/$environment}"
printf '%s\n' "$plist" > "$PLIST"
plutil -lint -s "$PLIST" || die "rendered plist is invalid: $PLIST"
say "wrote $PLIST"

domain="gui/$(id -u)"
port_listening() { lsof -nP -iTCP:"$RELAY_PORT" -sTCP:LISTEN 2>/dev/null | grep -q LISTEN; }
launchctl bootout "$domain/$LABEL" 2>/dev/null || true
# A previous instance of this agent may take a moment to release the port.
for _ in $(seq 1 10); do port_listening || break; sleep 1; done
port_listening && die "port $RELAY_PORT is in use; rerun with RELAY_PORT=<free port>"
touch "$LOG"
log_lines_before="$(wc -l < "$LOG")"
launchctl bootstrap "$domain" "$PLIST"
say "loaded $LABEL; waiting for the relay to listen"
for _ in $(seq 1 20); do
    started="$(tail -n +"$((log_lines_before + 1))" "$LOG" | grep -m1 'relay: listening on' || true)"
    [[ -n "$started" ]] && break
    sleep 1
done
[[ -n "$started" ]] || { tail -n 20 "$LOG" >&2; die "relay did not report listening within 20 s; see $LOG"; }
say "$started"

say "publishing on the tailnet: tailscale serve --bg --https=443 http://127.0.0.1:$RELAY_PORT"
tailscale serve --bg --https=443 "http://127.0.0.1:$RELAY_PORT"
tailscale serve status
if tailscale funnel status 2>&1 | grep -qi 'funnel on'; then
    die "tailscale funnel is on; the relay must stay tailnet only (tailscale funnel reset)"
fi
say "done; probe with: node $SCRIPT_DIR/check-relay.mjs $RELAY_ORIGIN"
