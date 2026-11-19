#!/usr/bin/env bash
# Installs iTerm2's companion plugin as iTerm2 3.7.7 installs it itself (plugins.sh): the zip
# checked against COMPANION_PLUGIN_ZIP_SHA256, the plugin's JavaScript against its Ed25519
# signature with the key iTerm2 embeds (CompanionPlugin.swift:24), then registered and looked up
# with NSWorkspace. A plugin iTerm2 cannot find makes it drop the pairing at launch
# (unpairIfPluginMissing), so a lookup that fails here fails the job.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
source "$REAL_ITERM2_SCRIPTS/plugins.sh"
require_macos
[[ -n "$COMPANION_PLUGIN_ZIP_SHA256" ]] || die "COMPANION_PLUGIN_ZIP_SHA256 is not set in ci/real-iterm2/common.sh"

install_signed_plugin "companion plugin" "$COMPANION_PLUGIN_ZIP_URL" "$COMPANION_PLUGIN_ZIP_SHA256" \
    "$COMPANION_PLUGIN_SIGNING_KEY" "$COMPANION_PLUGIN_BUNDLE_ID" iTermCompanionPlugin
