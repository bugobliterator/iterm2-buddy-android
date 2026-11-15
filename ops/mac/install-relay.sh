#!/usr/bin/env bash
# Installs the iTerm2 companion relay on this Mac as a launchd agent and
# publishes it on the tailnet with `tailscale serve` (never funnel).
#
# Safe to re-run: it restores the checkout to the pinned commit, reinstalls,
# rewrites the env file and plist, and reloads the agent. Everything that can
# fail is checked before the running agent is stopped. Relay state lives
# outside the checkout, so registrations survive. See README.md in this
# directory for undo steps.
#
# Needs git, Node 20+, pnpm and tailscale on PATH. The port is kept from the
# installed relay.env (default 8787); set RELAY_PORT to change it. iTerm2 and
# the phone only see the tailnet origin.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY_REPO="https://github.com/gnachman/iterm2-companion-relay.git"
RELAY_COMMIT="b8aa2f90d2bc8b617d1b9c97043aa0b171885310"
RELAY_ORIGIN="https://relay.example.ts.net"
RELAY_HOSTNAME="${RELAY_ORIGIN#https://}"
LABEL="dev.bugobliterator.itermbuddy.relay"

CHECKOUT="$HOME/Library/Application Support/iterm2-companion-relay"
STATE_DIR="$HOME/Library/Application Support/iterm2-companion-relay-state"
ENV_DIR="$HOME/.config/iterm2-companion-relay"
ENV_FILE="$ENV_DIR/relay.env"
LOG="$HOME/Library/Logs/iterm2-companion-relay.log"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

say() { echo "install-relay: $*"; }
die() { echo "install-relay: $*" >&2; exit 1; }

for tool in git node pnpm tailscale plutil lsof; do
    command -v "$tool" >/dev/null || die "$tool is not on PATH"
done
NODE="$(command -v node)"
node_major="$("$NODE" -p 'process.versions.node.split(".")[0]')"
(( node_major >= 20 )) || die "node $("$NODE" -v) is older than 20"

# --- Preflight: nothing below changes the running service ------------------

# Without a tailnet HTTPS certificate `tailscale serve` would stop and wait for
# the owner to enable HTTPS in the admin console.
tailscale status --json \
    | "$NODE" -e 'const s = JSON.parse(require("fs").readFileSync(0, "utf8"));
                  process.exit((s.CertDomains ?? []).includes(process.argv[1]) ? 0 : 1)' "$RELAY_HOSTNAME" \
    || die "tailscale CertDomains does not list $RELAY_HOSTNAME; enable HTTPS certificates for the tailnet"

HELPERS="$SCRIPT_DIR/install-relay-helpers.mjs"

# Prints "on" or "off" from the machine-readable serve config (top-level and
# foreground Funnel). Any failure to read or parse it returns non-zero.
funnel_state() {
    local status
    status="$(tailscale serve status --json)" || { echo "install-relay: tailscale serve status --json failed" >&2; return 1; }
    printf '%s' "$status" | "$NODE" "$HELPERS" funnel-state \
        || { echo "install-relay: cannot parse tailscale serve status --json" >&2; return 1; }
}
funnel="$(funnel_state)" || die "cannot read the Funnel state"
[[ "$funnel" == "off" ]] || die "tailscale funnel is on; the relay must stay tailnet only (tailscale funnel reset)"

# Keep the installed port unless RELAY_PORT overrides it.
if [[ -z "${RELAY_PORT:-}" && -f "$ENV_FILE" ]]; then
    RELAY_PORT="$(sed -n 's/^RELAY_PORT=//p' "$ENV_FILE" | head -n 1)"
fi
RELAY_PORT="${RELAY_PORT:-8787}"
[[ "$RELAY_PORT" =~ ^[0-9]+$ ]] || die "RELAY_PORT '$RELAY_PORT' is not a port number"

# The running agent's own listener is not a conflict.
agent_pid() { launchctl list | awk -v label="$LABEL" '$3 == label && $1 != "-" { print $1 }'; }
foreign_listeners() {
    local own_pid
    own_pid="$(agent_pid)"
    lsof -nP -t -iTCP:"$RELAY_PORT" -sTCP:LISTEN 2>/dev/null | grep -vx "${own_pid:-none}" || true
}
[[ -z "$(foreign_listeners)" ]] \
    || die "port $RELAY_PORT is in use by PID $(foreign_listeners | tr '\n' ' '); rerun with RELAY_PORT=<free port>"

# --- Checkout pinned to RELAY_COMMIT, tracked files restored ----------------

say "pinning checkout at ${RELAY_COMMIT:0:7} in $CHECKOUT"
[[ -d "$CHECKOUT/.git" ]] || git init --quiet "$CHECKOUT"
if current_remote="$(git -C "$CHECKOUT" remote get-url origin 2>/dev/null)"; then
    [[ "$current_remote" == "$RELAY_REPO" ]] || git -C "$CHECKOUT" remote set-url origin "$RELAY_REPO"
else
    git -C "$CHECKOUT" remote add origin "$RELAY_REPO"
fi
if ! git -C "$CHECKOUT" cat-file -e "$RELAY_COMMIT^{commit}" 2>/dev/null; then
    git -C "$CHECKOUT" fetch --quiet --depth 1 origin "$RELAY_COMMIT"
fi
git -C "$CHECKOUT" checkout --quiet --force --detach "$RELAY_COMMIT"
git -C "$CHECKOUT" reset --quiet --hard "$RELAY_COMMIT"
# Drop anything not in the commit, keeping only what this script generates.
git -C "$CHECKOUT" clean -q -fdx -e node_modules -e pnpm-workspace.yaml -e pnpm-lock.yaml
[[ "$(git -C "$CHECKOUT" rev-parse HEAD)" == "$RELAY_COMMIT" ]] || die "checkout is not at $RELAY_COMMIT"
[[ -z "$(git -C "$CHECKOUT" status --porcelain --untracked-files=no)" ]] || die "checkout has modified tracked files"

# pnpm >= 10.26 refuses unlisted dependency builds; better-sqlite3 needs its
# install script to fetch the native binary.
cat > "$CHECKOUT/pnpm-workspace.yaml" <<'YAML'
allowBuilds:
  better-sqlite3: true
YAML
# CI=true keeps pnpm from prompting before it purges an outdated node_modules.
say "pnpm install --prod (pnpm $(pnpm --version), node $("$NODE" -v))"
(cd "$CHECKOUT" && CI=true pnpm install --prod)
# Constructing a database is what loads the native addon; require() alone does not.
(cd "$CHECKOUT" && "$NODE" -e "new (require('better-sqlite3'))(':memory:').close()") \
    || die "better-sqlite3 cannot open a database; its native build failed"
say "better-sqlite3 opens a database"

# --- Render env file and plist to temp files and validate them --------------

mkdir -p "$STATE_DIR" "$ENV_DIR" "$(dirname "$PLIST")" "$(dirname "$LOG")"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/install-relay.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

environment_lines="RELAY_HOST=127.0.0.1
RELAY_PORT=$RELAY_PORT
RELAY_DB=$STATE_DIR/relay.db
ATTEST_REQUIRED=false
RELAY_ORIGIN=$RELAY_ORIGIN
RELAY_DAILY_BYTE_QUOTA=8589934592
RELAY_TRUST_PROXY=true"
( umask 077 && printf '%s\n' "$environment_lines" > "$WORK_DIR/relay.env" )

# launchd cannot source an env file, so the values go into the plist. The
# helper fills the template in node: Bash 5.3's patsub_replacement would treat
# `&` in ${var//pattern/replacement} specially.
RELAY_NODE="$NODE" RELAY_CHECKOUT="$CHECKOUT" RELAY_LOG="$LOG" \
    "$NODE" "$HELPERS" render-plist "$SCRIPT_DIR/$LABEL.plist.template" "$WORK_DIR/relay.env" "$WORK_DIR/relay.plist" \
    || die "cannot render the plist"
plutil -lint -s "$WORK_DIR/relay.plist" || die "rendered plist is invalid: $WORK_DIR/relay.plist"
rendered_node="$(plutil -extract ProgramArguments.0 raw -o - "$WORK_DIR/relay.plist")"
[[ "$rendered_node" == "$NODE" ]] || die "rendered plist names node '$rendered_node', expected '$NODE'"

# --- Replace the running agent ----------------------------------------------

mv -f "$WORK_DIR/relay.env" "$ENV_FILE"
chmod 600 "$ENV_FILE"
say "wrote $ENV_FILE"
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
mv -f "$WORK_DIR/relay.plist" "$PLIST"
say "wrote $PLIST"

# The old instance may take a moment to release the port.
port_listening() { lsof -nP -iTCP:"$RELAY_PORT" -sTCP:LISTEN 2>/dev/null | grep -q LISTEN; }
for _ in $(seq 1 10); do port_listening || break; sleep 1; done
port_listening && die "port $RELAY_PORT is still in use after stopping the agent"

touch "$LOG"
log_lines_before="$(wc -l < "$LOG")"
launchctl bootstrap "$DOMAIN" "$PLIST"
say "loaded $LABEL; waiting for the relay to listen"
started=""
for _ in $(seq 1 20); do
    started="$(tail -n +"$((log_lines_before + 1))" "$LOG" | grep -m1 'relay: listening on' || true)"
    [[ -n "$started" ]] && break
    sleep 1
done
[[ -n "$started" ]] || { tail -n 20 "$LOG" >&2; die "relay did not report listening within 20 s; see $LOG"; }
say "$started"

# --- Publish on the tailnet only ---------------------------------------------

say "publishing on the tailnet: tailscale serve --bg --https=443 http://127.0.0.1:$RELAY_PORT"
tailscale serve --bg --https=443 "http://127.0.0.1:$RELAY_PORT"
# Fail closed: if Funnel is on, or its state cannot be read, unpublish and stop.
unpublish_and_stop() {
    tailscale funnel --https=443 off || true
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    die "$1; ran tailscale funnel --https=443 off and stopped $LABEL"
}
funnel="$(funnel_state)" || unpublish_and_stop "cannot read the Funnel state after publishing"
[[ "$funnel" == "off" ]] || unpublish_and_stop "tailscale funnel is on after publishing"
tailscale serve status
say "done; probe with: node $SCRIPT_DIR/check-relay.mjs $RELAY_ORIGIN"
