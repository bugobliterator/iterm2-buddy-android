#!/usr/bin/env bash
# Prepares the key-bytes probe (RealIterm2KeyBytesTest): what bytes reach a program in a session
# when the phone types `/`, `*` and friends, and whether real Claude Code shows them.
#
# 1. Writes $WORK_DIR/key-bytes/probe.env, which key-probe.sh (typed into the session, where
#    the runner's PATH is not set up) reads: node's and the repository's absolute paths. This
#    comes first, so the capture modes run even when Claude Code cannot be installed.
# 2. Installs Claude Code CLAUDE_CODE_VERSION globally with pnpm, allowing its postinstall (which
#    puts the native binary in place of bin/claude.exe); if the stub is still there, runs that
#    postinstall itself. Adds the binary and its version to probe.env.
# 3. Seeds ~/.claude.json so Claude Code lands on its prompt with no dialog: onboarding done, the
#    probe's project trusted, and the dummy API key approved (its last 20 characters). The key is
#    not a real key, and ANTHROPIC_BASE_URL (key-probe.sh) points at a closed local port.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools node pnpm

# Pinned: the newest release at least a day old on 2026-11-19 (pnpm may refuse younger ones).
CLAUDE_CODE_VERSION="2.1.327"

KEY_BYTES_DIR="$WORK_DIR/key-bytes"
mkdir -p "$KEY_BYTES_DIR/claude-project"
project_dir="$(cd "$KEY_BYTES_DIR/claude-project" && pwd -P)"
repo_dir="$(cd "$REAL_ITERM2_SCRIPTS/../.." && pwd -P)"
env_file="$KEY_BYTES_DIR/probe.env"
{
    printf 'KEY_BYTES_DIR=%q\n' "$KEY_BYTES_DIR"
    printf 'KEY_BYTES_NODE=%q\n' "$(command -v node)"
    printf 'KEY_BYTES_REPO=%q\n' "$repo_dir"
    printf 'KEY_BYTES_PROJECT=%q\n' "$project_dir"
} > "$env_file"
log "wrote $env_file (node $(node --version))"

export PNPM_HOME="${PNPM_HOME:-$WORK_DIR/pnpm-home}"
mkdir -p "$PNPM_HOME"
export PATH="$PNPM_HOME:$PATH"
log "installing @anthropic-ai/claude-code@$CLAUDE_CODE_VERSION with pnpm $(pnpm --version)"
package="@anthropic-ai/claude-code@$CLAUDE_CODE_VERSION"
# Allow its postinstall; an older or newer pnpm that does not take the flag gets the plain
# install, and the stub check below runs the postinstall instead.
if ! pnpm add -g --allow-build=@anthropic-ai/claude-code "$package" > "$WORK_DIR/pnpm-claude.log" 2>&1 \
    && ! pnpm add -g --config.strict-dep-builds=false "$package" >> "$WORK_DIR/pnpm-claude.log" 2>&1; then
    tail -n 30 "$WORK_DIR/pnpm-claude.log" | indent
    die "pnpm could not install $package"
fi
tail -n 10 "$WORK_DIR/pnpm-claude.log" | indent
package_dir="$(pnpm root -g)/@anthropic-ai/claude-code"
[[ -d "$package_dir" ]] || die "no $package_dir after the install"
claude_bin="$package_dir/bin/claude.exe"
# The stub the package ships is a short shell script; the native binary is tens of megabytes.
if [[ ! -f "$claude_bin" ]] || [[ "$(wc -c < "$claude_bin" | tr -d ' ')" -lt 1000000 ]]; then
    log "bin/claude.exe is still the stub; running the package's postinstall"
    (cd "$package_dir" && node install.cjs) 2>&1 | indent
fi
version="$("$claude_bin" --version 2>&1 | head -n 1)" || die "$claude_bin --version failed: $version"
log "Claude Code: $version ($claude_bin)"
{
    printf 'KEY_BYTES_CLAUDE=%q\n' "$claude_bin"
    printf 'KEY_BYTES_CLAUDE_VERSION=%q\n' "$version"
} >> "$env_file"

dummy_key="sk-ant-api03-buddy-ci-dummy-key-not-a-real-key-000000000000AA"
printf 'KEY_BYTES_API_KEY=%q\n' "$dummy_key" >> "$env_file"
CLAUDE_JSON="$HOME/.claude.json" PROJECT_DIR="$project_dir" KEY_TAIL="${dummy_key: -20}" \
    VERSION="$CLAUDE_CODE_VERSION" node -e '
const fs = require("fs");
const file = process.env.CLAUDE_JSON;
let config = {};
try { config = JSON.parse(fs.readFileSync(file, "utf8")); } catch {}
config.hasCompletedOnboarding = true;
config.lastOnboardingVersion = process.env.VERSION;
config.theme = config.theme || "dark";
config.customApiKeyResponses = { approved: [process.env.KEY_TAIL], rejected: [] };
config.projects = config.projects || {};
const project = config.projects[process.env.PROJECT_DIR] || {};
config.projects[process.env.PROJECT_DIR] = {
    ...project,
    allowedTools: project.allowedTools || [],
    hasTrustDialogAccepted: true,
    hasCompletedProjectOnboarding: true,
    projectOnboardingSeenCount: 1,
};
fs.writeFileSync(file, JSON.stringify(config, null, 2));
'
log "seeded $HOME/.claude.json: onboarding done, $project_dir trusted, the dummy key approved"
