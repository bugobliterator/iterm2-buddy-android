#!/usr/bin/env bash
# Runs RelayEndToEndTest against the real companion relay.
#
# Clones gnachman/iterm2-companion-relay at the pinned commit into a temp dir,
# writes the pnpm-workspace.yaml that allows better-sqlite3's native build
# (pnpm >= 10.26 refuses unlisted dependency builds), installs production
# dependencies with pnpm, checks better-sqlite3 loads, then runs the test with
# RELAY_DIR pointing at the checkout. The temp dir is removed on exit.
#
# Needs git, Node 20+ and pnpm on PATH, and network access to github.com and
# the npm registry. Set GRADLE to run Gradle another way (default ./gradlew).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELAY_REPO="${RELAY_REPO:-https://github.com/gnachman/iterm2-companion-relay.git}"
RELAY_COMMIT="b8aa2f90d2bc8b617d1b9c97043aa0b171885310"
GRADLE="${GRADLE:-./gradlew}"

for tool in git node pnpm; do
    command -v "$tool" >/dev/null || { echo "relay-it: $tool is not on PATH" >&2; exit 1; }
done

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/relay-it.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
RELAY_DIR="$WORK_DIR/relay"

echo "relay-it: cloning the relay at ${RELAY_COMMIT:0:7} into $RELAY_DIR"
git init --quiet "$RELAY_DIR"
git -C "$RELAY_DIR" remote add origin "$RELAY_REPO"
git -C "$RELAY_DIR" fetch --quiet --depth 1 origin "$RELAY_COMMIT"
git -C "$RELAY_DIR" checkout --quiet FETCH_HEAD
actual="$(git -C "$RELAY_DIR" rev-parse HEAD)"
if [[ "$actual" != "$RELAY_COMMIT" ]]; then
    echo "relay-it: checked out $actual, expected $RELAY_COMMIT" >&2
    exit 1
fi

cat > "$RELAY_DIR/pnpm-workspace.yaml" <<'YAML'
allowBuilds:
  better-sqlite3: true
YAML

echo "relay-it: pnpm install --prod (pnpm $(pnpm --version), node $(node --version))"
(cd "$RELAY_DIR" && pnpm install --prod)

(cd "$RELAY_DIR" && node -e "require('better-sqlite3')") || {
    echo "relay-it: better-sqlite3 does not load; its native build failed" >&2
    exit 1
}
echo "relay-it: better-sqlite3 loads"

cd "$REPO_ROOT"
RELAY_DIR="$RELAY_DIR" $GRADLE testDebugUnitTest --tests '*RelayEndToEndTest*' --rerun
