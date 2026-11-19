#!/usr/bin/env bash
# Turns on iTerm2's AI features before its first launch and points them at the local fake model
# (fake-model.mjs), so Agent Chat runs with no vendor, no key and no click:
#
# 1. The secure setting EnableAI=true (SecureUserDefaults.swift:96), root-owned like
#    EnableCompanionPairing; iTerm2 would otherwise write it behind an administrator prompt.
#    With the AI plugin installed and GenerativeAIAllowed on (its default), iTerm2 then reports
#    AI on to the phone (CompanionPairingController.aiAvailable).
# 2. User defaults: UseRecommendedAIModel=NO and one AIManualModelConfigurations entry
#    (LLMMetadata.manualModel): Chat Completions (api 1) at FAKE_MODEL_URL, function calling and
#    streaming on, credential "none" (AIModelCredential.none: iTerm2 sends its self-hosted
#    placeholder, never a vendor key). AiModel names it, so LLMMetadata.model() picks it.
# 3. The answers to iTerm2's one-time AI prompts (iTermWarning modals, which block the main
#    thread until someone clicks), so none can come up on the runner:
#    - AIModelUpdatesEnabled=NO (iTermPreferences.m:277, default NO at :820) and
#      NoSyncAIModelCatalogUpdateConsent=2, denied (iTermUserDefaults.m:60, .h:152-156): the
#      "Check for AI Model Updates?" consent (AIModelCatalogUpdater.swift:115-121, 163-210);
#    - NoSyncAutoProvideConsent=2, denied (iTermUserDefaults.m:61, .h:168-172): "Share Terminal
#      Contents Automatically?" (ChatAgent.swift:1129-1148), asked by a session-bound turn;
#    - "AI Safety Check"=NO and NoSyncAISafetyCheckProviderMigrationDone=YES
#      (iTermMigrationHelper.m:436-460, run by every AITermController, AITerm.swift:318), and
#      NoSyncAISafetyCheckNagComplete=YES (RemoteCommand+Mac.swift:38): the safety-check
#      provider migration and its nag.
#
# Reads back what it wrote, typed, and fails when a value did not stick.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools defaults plutil shasum od sudo stat install pgrep

if pgrep -x iTerm2 >/dev/null; then
    die "iTerm2 is already running; it must read the AI settings at its first launch"
fi

# --- 1. The secure setting --------------------------------------------------------------------

install_secure_setting EnableAI true

# --- 2. User defaults -------------------------------------------------------------------------

domain="$ITERM2_BUNDLE_ID"
defaults write "$domain" GenerativeAIAllowed -bool true
defaults write "$domain" UseRecommendedAIModel -bool false
defaults write "$domain" AiModel -string "$FAKE_MODEL_NAME"
defaults write "$domain" AIEconomyModelName -string ""
# A property-list value, so the numbers and flags are typed (an old-style "{...}" would make
# every value a string, which LLMMetadata reads as absent).
defaults write "$domain" AIManualModelConfigurations "<array><dict>
    <key>id</key><string>$FAKE_MODEL_NAME</string>
    <key>name</key><string>$FAKE_MODEL_NAME</string>
    <key>url</key><string>$FAKE_MODEL_URL</string>
    <key>api</key><integer>1</integer>
    <key>credential</key><string>none</string>
    <key>functionCalling</key><true/>
    <key>streaming</key><true/>
    <key>supportsTemperature</key><true/>
    <key>vision</key><false/>
    <key>hostedWebSearch</key><false/>
    <key>hostedFileSearch</key><false/>
    <key>hostedCodeInterpreter</key><false/>
    <key>contextWindowTokens</key><integer>128000</integer>
    <key>maxResponseTokens</key><integer>4096</integer>
</dict></array>"

defaults write "$domain" AIModelUpdatesEnabled -bool false
defaults write "$domain" NoSyncAIModelCatalogUpdateConsent -int 2
defaults write "$domain" NoSyncAutoProvideConsent -int 2
defaults write "$domain" "AI Safety Check" -bool false
defaults write "$domain" NoSyncAISafetyCheckProviderMigrationDone -bool true
defaults write "$domain" NoSyncAISafetyCheckNagComplete -bool true

exported="$WORK_DIR/iterm2-defaults.plist"
defaults export "$domain" "$exported"
model_value() {
    plutil -extract "AIManualModelConfigurations.0.$1" raw -o - "$exported" 2>/dev/null
}
log "AI defaults for $domain:"
for key in GenerativeAIAllowed UseRecommendedAIModel AiModel AIModelUpdatesEnabled NoSyncAIModelCatalogUpdateConsent \
    NoSyncAutoProvideConsent "AI Safety Check" NoSyncAISafetyCheckProviderMigrationDone NoSyncAISafetyCheckNagComplete; do
    printf '    %s = %s\n' "$key" "$(defaults read "$domain" "$key")"
done
for key in id name url api credential functionCalling streaming contextWindowTokens maxResponseTokens; do
    printf '    AIManualModelConfigurations[0].%s = %s\n' "$key" "$(model_value "$key")"
done
[[ "$(defaults read "$domain" UseRecommendedAIModel)" == 0 ]] || die "UseRecommendedAIModel did not stick"
[[ "$(model_value url)" == "$FAKE_MODEL_URL" ]] || die "the manual model's url did not stick"
[[ "$(model_value api)" == 1 ]] || die "the manual model's api is not the integer 1 (Chat Completions)"
[[ "$(model_value credential)" == none ]] || die "the manual model's credential is not none"
[[ "$(model_value functionCalling)" == true ]] || die "the manual model's functionCalling is not the boolean true"
[[ "$(model_value streaming)" == true ]] || die "the manual model's streaming is not the boolean true"
rm -f "$exported"
log "iTerm2's AI is on and points at $FAKE_MODEL_URL as $FAKE_MODEL_NAME, credential none"
