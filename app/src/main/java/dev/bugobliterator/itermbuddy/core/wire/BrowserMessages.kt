package dev.bugobliterator.itermbuddy.core.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Types and codecs for browser input and browser navigation (revision 15, upstream
 * `sources/Companion/Shared/CompanionMessages.swift:915-945, 1157-1166`). Encoding writes what
 * Swift's synthesized `Codable` writes, a `Double` as Swift's `JSONEncoder` writes it
 * ([swiftDouble]: `300`, not `300.0`). Decoding follows `Envelope.kt`'s rules: unknown keys are
 * ignored, optional fields read as `decodeIfPresent`, and a known case with a bad body throws
 * WireDecodeException.
 *
 * Every browser message is sent with no request ID (iOS `CompanionClient.swift:325-364`), and
 * only to a Mac whose hello revision is at least 15 (`browserInputRevision`); an older Mac
 * decodes them as `.unsupported` and ignores them.
 */

/**
 * Upstream `CompanionBrowserNavigationState`: what the browser controls show for a browser
 * stream's page. The Mac writes every field: [url] and [title] are `""` when the page has none
 * (`CompanionBrowserFrameSource.swift:129-141`). Decoding is lenient, as upstream's
 * (`CompanionMessages.swift:608-616`): an absent boolean is false; an absent string is null here
 * (upstream reads it as `""`), so callers treat null and `""` alike.
 *
 * [url] and [title] are page content: [toString] shows only their lengths.
 */
data class BrowserNavigationState(
    val url: String?,
    val title: String?,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val isLoading: Boolean,
) {
    override fun toString(): String =
        "BrowserNavigationState(${redacted("url", url)}, ${redacted("title", title)}, " +
            "canGoBack=$canGoBack, canGoForward=$canGoForward, isLoading=$isLoading)"
}

/** `name(len=n)`, or `name=null`: a string's presence and length, never its text. */
internal fun redacted(name: String, value: String?): String = if (value == null) "$name=null" else "$name(len=${value.length})"

private fun requireU32(name: String, value: Long) {
    require(value in 0..U32_MAX) { "$name must be a UInt32" }
}

private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))

/** `browserTap(streamID:x:y:)`; [swiftDouble] refuses NaN and infinities, as Swift's encoder does. */
internal fun encodeBrowserTap(message: PhoneMessage.BrowserTap): JsonObject {
    requireU32("streamID", message.streamID)
    return case(
        "browserTap",
        obj("streamID" to JsonPrimitive(message.streamID), "x" to swiftDouble(message.x), "y" to swiftDouble(message.y)),
    )
}

internal fun encodeBrowserScroll(message: PhoneMessage.BrowserScroll): JsonObject {
    requireU32("streamID", message.streamID)
    return case(
        "browserScroll",
        obj(
            "streamID" to JsonPrimitive(message.streamID),
            "x" to swiftDouble(message.x),
            "y" to swiftDouble(message.y),
            "deltaX" to swiftDouble(message.deltaX),
            "deltaY" to swiftDouble(message.deltaY),
        ),
    )
}

internal fun encodeBrowserLoad(message: PhoneMessage.BrowserLoad): JsonObject {
    requireU32("streamID", message.streamID)
    return case("browserLoad", obj("streamID" to JsonPrimitive(message.streamID), "text" to JsonPrimitive(message.text)))
}

/** `browserEditingChanged(streamID:editing:)`, unsolicited. */
internal fun decodeBrowserEditingChanged(body: JsonElement): MacMessage {
    val case = "browserEditingChanged"
    val message = body.asObject(case)
    return MacMessage.BrowserEditingChanged(message.u32(case, "streamID"), message.boolean(case, "editing"))
}

/** `browserNavigationChanged(streamID:state:)`, unsolicited; `state` is required, its fields are not. */
internal fun decodeBrowserNavigationChanged(body: JsonElement): MacMessage {
    val case = "browserNavigationChanged"
    val message = body.asObject(case)
    val state = message.obj(case, "state")
    val stateCase = "$case.state"
    return MacMessage.BrowserNavigationChanged(
        message.u32(case, "streamID"),
        BrowserNavigationState(
            url = state.ifPresent("url") { string(stateCase, it) },
            title = state.ifPresent("title") { string(stateCase, it) },
            canGoBack = state.ifPresent("canGoBack") { boolean(stateCase, it) } ?: false,
            canGoForward = state.ifPresent("canGoForward") { boolean(stateCase, it) } ?: false,
            isLoading = state.ifPresent("isLoading") { boolean(stateCase, it) } ?: false,
        ),
    )
}
