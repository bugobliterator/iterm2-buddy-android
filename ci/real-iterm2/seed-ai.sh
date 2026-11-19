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
# 3. The conversation's token budget: AiMaxTokens=128000 and AiResponseMaxTokens=4096. A chat
#    turn sizes its request from these two global preferences, not from the manual model's
#    contextWindowTokens: maxTokens = AiMaxTokens - min(AiMaxTokens / 2, AiResponseMaxTokens)
#    (AIConversation.swift:142-151). Left unset, the first AITermController (AITerm.swift:318)
#    runs migrateAISettings, which writes both from a model guessed from AiModel's name
#    (iTermMigrationHelper.m:568-624): an unknown name such as ours guesses 8192 and 8192
#    (AIMetadata.swift:99-116; iTerm2's own defaults of 128000 and 8192, iTermPreferences.m:797-798,
#    are not registered with NSUserDefaults, so the migration sees no value). That makes
#    maxTokens 4096, below the orchestrator's system prompt alone, and truncate(messages:maxTokens:)
#    (AITerm.swift:1695-1736) then never ends: it never shortens system messages, and its inner
#    loop keeps halving the last message, which stops shrinking at "…[truncated]…". The main
#    thread spins inside it (seen on the runner: every sample in truncate, from
#    AIConversation.truncatedMessages). The manual entry's contextWindowTokens and
#    maxResponseTokens below are set to the same values for consistency.
# 4. The answers to iTerm2's one-time AI prompts (iTermWarning modals, which block the main
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
# 5. Hardening, not the cause seen on the runner: the user keychain search list holds only the CI
#    keychain. Every chat turn builds an
#    AIConversation (ChatAgent.swift:185), whose init reads the default model's registration on
#    the main thread (AIConversation.swift:165, AITermControllerRegistrationHelper.swift:15-31).
#    resolvedRegistration reads the vendor's stored API key before it looks at the credential
#    (AITerm.swift:267-292), so even credential "none" makes a synchronous keychain read
#    (AITermControllerObjC.swift:97-101, 194-230, 253-280): the data-protection keychain, then,
#    for an absent item, SecItemCopyMatching over the user's search list
#    (iTermUpgradeSafeKeychain.swift:66-120, 389-396). With the runner's login keychain in that
#    list, a locked login keychain asks SecurityAgent to unlock it: a window that is not iTerm2's
#    and no iTermWarning, with iTerm2's main thread blocked until someone types a password. The
#    API key item ("iTerm2 API Keys") is absent everywhere, so the search ends in the CI keychain.
#
# Reads back what it wrote, typed, and fails when a value did not stick.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_macos
require_tools defaults plutil shasum od sudo stat install pgrep security

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

defaults write "$domain" AiMaxTokens -int 128000
defaults write "$domain" AiResponseMaxTokens -int 4096
defaults write "$domain" AIModelUpdatesEnabled -bool false
defaults write "$domain" NoSyncAIModelCatalogUpdateConsent -int 2
defaults write "$domain" NoSyncAutoProvideConsent -int 2
defaults write "$domain" "AI Safety Check" -bool false
defaults write "$domain" NoSyncAISafetyCheckProviderMigrationDone -bool true
defaults write "$domain" NoSyncAISafetyCheckNagComplete -bool true

# --- 5. The keychain search list ---------------------------------------------------------------

[[ -f "$CI_KEYCHAIN" ]] || die "no CI keychain at $CI_KEYCHAIN; run seed-pairing.sh first"
log "user keychain search list before: $(security list-keychains -d user | tr -s ' \n' ' ')"
security list-keychains -d user -s "$CI_KEYCHAIN"
security default-keychain -d user -s "$CI_KEYCHAIN"
log "user keychain search list now: $(security list-keychains -d user | tr -s ' \n' ' ')"
if security find-generic-password -s "iTerm2 API Keys" >/dev/null 2>&1; then
    warn "an 'iTerm2 API Keys' item is reachable in the search list; it is not used (credential none)"
else
    log "no 'iTerm2 API Keys' item in the search list: the lookup ends in the CI keychain"
fi

exported="$WORK_DIR/iterm2-defaults.plist"
defaults export "$domain" "$exported"
model_value() {
    plutil -extract "AIManualModelConfigurations.0.$1" raw -o - "$exported" 2>/dev/null
}
log "AI defaults for $domain:"
for key in GenerativeAIAllowed UseRecommendedAIModel AiModel AiMaxTokens AiResponseMaxTokens AIModelUpdatesEnabled NoSyncAIModelCatalogUpdateConsent \
    NoSyncAutoProvideConsent "AI Safety Check" NoSyncAISafetyCheckProviderMigrationDone NoSyncAISafetyCheckNagComplete; do
    printf '    %s = %s\n' "$key" "$(defaults read "$domain" "$key")"
done
for key in id name url api credential functionCalling streaming contextWindowTokens maxResponseTokens; do
    printf '    AIManualModelConfigurations[0].%s = %s\n' "$key" "$(model_value "$key")"
done
[[ "$(defaults read "$domain" UseRecommendedAIModel)" == 0 ]] || die "UseRecommendedAIModel did not stick"
[[ "$(defaults read "$domain" AiMaxTokens)" == 128000 ]] || die "AiMaxTokens did not stick"
[[ "$(defaults read "$domain" AiResponseMaxTokens)" == 4096 ]] || die "AiResponseMaxTokens did not stick"
[[ "$(model_value url)" == "$FAKE_MODEL_URL" ]] || die "the manual model's url did not stick"
[[ "$(model_value api)" == 1 ]] || die "the manual model's api is not the integer 1 (Chat Completions)"
[[ "$(model_value credential)" == none ]] || die "the manual model's credential is not none"
[[ "$(model_value functionCalling)" == true ]] || die "the manual model's functionCalling is not the boolean true"
[[ "$(model_value streaming)" == true ]] || die "the manual model's streaming is not the boolean true"
rm -f "$exported"
log "iTerm2's AI is on and points at $FAKE_MODEL_URL as $FAKE_MODEL_NAME, credential none"
