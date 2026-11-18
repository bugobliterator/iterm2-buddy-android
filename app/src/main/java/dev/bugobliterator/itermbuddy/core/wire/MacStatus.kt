package dev.bugobliterator.itermbuddy.core.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Upstream `CompanionMacStatus` (revision 14), reduced to what this app shows: whether the Mac
 * cannot serve requests right now ([mainBlocked]) and how many alerts it reports the phone may
 * answer ([alertCount], the size of upstream `modalAlerts`). Always the Mac's complete current
 * state, never a delta. The alerts' contents (headings, bodies, buttons, session GUIDs) are not
 * kept: this app does not answer alerts.
 *
 * The Mac sends it in its `hello` reply ([MacMessage.HelloReply.macStatus]) and then as
 * [MacMessage.MacStatusChanged] on each change, only to a phone at revision 14 or later
 * (`MC/CompanionLink.swift:518-536, 559-572`).
 */
data class MacStatus(val mainBlocked: Boolean, val alertCount: Int) {
    /** Whether the home and the live view show the Mac alert banner. */
    val showsAlert: Boolean get() = mainBlocked || alertCount > 0
}

/**
 * The `macStatus` of a `hello` reply: null when the Mac sent none (a phone below revision 14, or
 * a Mac older than 14), absent and `null` alike. [MacMessage.HelloReply]s from [decodeEnvelope]
 * have had it checked already; for one built by hand a malformed status throws
 * [WireDecodeException].
 */
fun MacMessage.HelloReply.macStatus(): MacStatus? = raw.ifPresent("macStatus") { key -> decodeMacStatus(get(key)) }

/**
 * Upstream's lenient `CompanionMacStatus.init(from:)` (`CompanionMessages.swift:583-587`): an
 * absent or `null` `modalAlerts` is no alerts, an absent or `null` `mainBlocked` is false; present
 * with the wrong type throws. Each alert must be an object with a string `id`, the one field
 * `CompanionModalAlert` requires (its other fields are optional upstream and not read here).
 */
internal fun decodeMacStatus(element: JsonElement?): MacStatus {
    val case = "macStatus"
    val status = element as? JsonObject ?: throw WireDecodeException("$case is missing or not an object")
    val mainBlocked = status.ifPresent("mainBlocked") { boolean(case, it) } ?: false
    val alerts = status.ifPresent("modalAlerts") { array(case, it) }.orEmpty()
    for (alert in alerts) {
        alert.asObject("$case.modalAlerts").string("$case.modalAlerts", "id")
    }
    return MacStatus(mainBlocked, alerts.size)
}

/** `macStatusChanged(status:)`, unsolicited. */
internal fun decodeMacStatusChanged(body: JsonElement): MacMessage {
    val case = "macStatusChanged"
    return MacMessage.MacStatusChanged(decodeMacStatus(body.asObject(case)["status"]))
}
