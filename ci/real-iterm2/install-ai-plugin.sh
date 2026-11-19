#!/usr/bin/env bash
# Installs iTerm2's AI plugin as iTerm2 3.7.7 installs it itself (plugins.sh,
# CompanionPluginInstaller.installAIPlugin). iTerm2 runs every model request through this
# plugin's JavaScript (AIPluginClient.swift), and reports AI off to the phone without it
# (CompanionPairingController.aiAvailable).
#
# The zip is checked against AI_PLUGIN_ZIP_SHA256; while that pin is empty the run prints the
# zip's sha256 to pin and goes on. The plugin's JavaScript (Contents/Resources/iTermAIPlugin.js)
# is always checked against its Ed25519 signature with the AI plugin key iTerm2 embeds
# (AIPluginClient.swift:115), which is what iTerm2 checks before it loads it.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
source "$REAL_ITERM2_SCRIPTS/plugins.sh"
require_macos

install_signed_plugin "AI plugin" "$AI_PLUGIN_ZIP_URL" "$AI_PLUGIN_ZIP_SHA256" \
    "$AI_PLUGIN_SIGNING_KEY" "$AI_PLUGIN_BUNDLE_ID" iTermAIPlugin
