#!/usr/bin/env bash
# Prepares the key-bytes probe (RealIterm2KeyBytesTest): what bytes reach a program in a session
# when the phone types `/`, `*` and friends, and whether real Claude Code shows them.
#
# 1. Writes $WORK_DIR/key-bytes/probe.env, which key-probe.sh (typed into the session, where
#    the runner's PATH is not set up) reads: node's and the repository's absolute paths. This
#    comes first, so the capture modes run even when Claude Code cannot be installed.
# 2. Installs Claude Code CLAUDE_CODE_VERSION globally with pnpm, allowing its postinstall (which
#    puts the native binary in place of bin/claude.exe). It finds the package through `claude`
#    on PATH or a search of pnpm's directories (pnpm 11 keeps global packages apart), runs the
#    postinstall itself if the stub is still there, and falls back to the package's
#    cli-wrapper.cjs under node. Adds the command and its version to probe.env.
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
# Where the package landed: pnpm 11 keeps each global package in its own directory under the
# global dir, so `pnpm root -g` is not it. Try the `claude` on PATH (through symlinks, or the
# path a shell shim names), then search the pnpm directories for the package's package.json.
realpath_of() { node -e 'try { console.log(require("fs").realpathSync(process.argv[1])) } catch {}' "$1"; }
tried=()
package_dir=""
claude_on_path="$(command -v claude || true)"
tried+=("command -v claude: ${claude_on_path:-none}")
if [[ -n "$claude_on_path" ]]; then
    resolved="$(realpath_of "$claude_on_path")"
    tried+=("its real path: ${resolved:-none}")
    candidate="${resolved%/bin/*}"
    if [[ -n "$resolved" && -f "$candidate/package.json" && "$candidate" == */@anthropic-ai/claude-code ]]; then
        package_dir="$candidate"
    elif [[ -f "$resolved" && "$(head -c 2 "$resolved")" == '#!' ]]; then
        # A shim: the package path is in it.
        shimmed="$(grep -oE '[^" ]*/@anthropic-ai/claude-code/' "$resolved" | head -n 1)"
        # pnpm's shims name it relative to their own directory, as $basedir/...
        shimmed="${shimmed/\$basedir/$(dirname "$resolved")}"
        tried+=("the shim names: ${shimmed:-nothing}")
        [[ -n "$shimmed" && -f "${shimmed}package.json" ]] && package_dir="$(realpath_of "${shimmed%/}")"
    fi
fi
if [[ -z "$package_dir" ]]; then
    roots=("$PNPM_HOME" "$(pnpm root -g 2>/dev/null)/.." "$(pnpm config get global-dir 2>/dev/null)")
    for root in "${roots[@]}"; do
        [[ -n "$root" && -d "$root" ]] || continue
        found="$(find "$root" -maxdepth 10 -path '*/@anthropic-ai/claude-code/package.json' 2>/dev/null | head -n 1)"
        tried+=("find in $root: ${found:-nothing}")
        if [[ -n "$found" ]]; then
            package_dir="$(realpath_of "$(dirname "$found")")"
            break
        fi
    done
fi
if [[ -z "$package_dir" ]]; then
    printf '%s\n' "${tried[@]}" | indent
    die "could not find @anthropic-ai/claude-code after the install"
fi
log "the package is at $package_dir"
claude_bin="$package_dir/bin/claude.exe"
# The stub the package ships is a short shell script; the native binary is tens of megabytes.
native() { [[ -f "$claude_bin" ]] && [[ "$(wc -c < "$claude_bin" | tr -d ' ')" -ge 1000000 ]]; }
if ! native; then
    log "bin/claude.exe is still the stub; running the package's postinstall"
    (cd "$package_dir" && node install.cjs) 2>&1 | indent || true
fi
if ! native; then
    # The package's own fallback launcher, run with node, from a script `script -r` can exec.
    claude_bin="$KEY_BYTES_DIR/claude-launch.sh"
    printf '#!/bin/sh\nexec %q %q "$@"\n' "$(command -v node)" "$package_dir/cli-wrapper.cjs" > "$claude_bin"
    chmod +x "$claude_bin"
    log "no native binary; using node cli-wrapper.cjs through $claude_bin"
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
