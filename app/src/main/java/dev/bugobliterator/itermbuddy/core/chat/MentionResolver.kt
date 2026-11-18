package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** The text the Mac shows for a mention that no longer resolves (`CompanionMessages.swift:80-82`). */
const val DEFUNCT_SESSION_TEXT = "[defunct session]"

/** How a mention renders, given what the Mac has said about it. */
sealed interface MentionDisplay {
    /** The Mac named it: show [MentionResolution.displayName], tappable. */
    data class Named(val resolution: MentionResolution) : MentionDisplay {
        val displayName: String get() = checkNotNull(resolution.displayName)
    }

    /** The Mac answered that it does not resolve: show [DEFUNCT_SESSION_TEXT]. */
    data object Defunct : MentionDisplay

    /** Not answered (yet): show the mention as written. */
    data object Raw : MentionDisplay
}

/** How the mention [identifier] renders with [resolutions] ([MentionResolver.resolutions]). */
fun mentionDisplay(resolutions: Map<String, MentionResolution>, identifier: String): MentionDisplay {
    val resolution = resolutions[identifier] ?: return MentionDisplay.Raw
    return if (resolution.displayName != null) MentionDisplay.Named(resolution) else MentionDisplay.Defunct
}

/**
 * The texts of a message body that can hold @-mentions, as iOS scans them (`AppModel.swift:4141-4166`):
 * plain text and markdown, their multipart subparts (not attachments or context), an explanation
 * response, and a command request's markdown description.
 */
fun mentionableTexts(body: MessageBody): List<String> = when (body) {
    is MessageBody.PlainText -> listOf(body.text)
    is MessageBody.Markdown -> listOf(body.text)
    is MessageBody.Multipart -> body.parts.mapNotNull {
        when (it) {
            is Subpart.PlainText -> it.text
            is Subpart.Markdown -> it.text
            is Subpart.AttachmentPart, is Subpart.Context -> null
        }
    }
    is MessageBody.ExplanationResponse -> listOf(body.markdown)
    is MessageBody.ClassicCommand -> listOf(body.content.markdownDescription())
    is MessageBody.ExternalCommand -> listOf(body.markdownDescription)
    else -> emptyList()
}

/**
 * Asks the Mac what @-mentions name, the iOS rules (`AppModel.swift:4168-4223`). [note] scans
 * texts and sends one `resolveMentions` request for the identifiers not yet resolved, not in
 * flight and not behind the retry gate. A hit (a non-null display name) is kept for good; a miss
 * the Mac answered is kept too, so it renders [DEFUNCT_SESSION_TEXT], but goes behind the retry
 * gate like a failed request, which leaves its mentions raw. [reopenRetryGate] lets them be asked
 * again: the owner calls it when a conversation opens, on each link up and when AI comes back
 * on, then notes the texts again (`AppModel.swift:2351, 2950`).
 *
 * Each request runs in its own coroutine in [scope], bounded by [requestTimeout] (the client's
 * rpc bound when null). Thread-safe. Logs counts and failure kinds only, never text or names.
 */
class MentionResolver(
    private val link: StreamLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val requestTimeout: Duration? = null,
) {
    private val lock = Any()
    private val resolutionsFlow = MutableStateFlow<Map<String, MentionResolution>>(emptyMap())
    private val inFlight = HashSet<String>()
    private val retryGate = HashSet<String>()

    /**
     * What the Mac said about each identifier it answered: hits, and misses (null display name)
     * until a later answer names them. An identifier missing here was never answered.
     */
    val resolutions: StateFlow<Map<String, MentionResolution>> = resolutionsFlow.asStateFlow()

    /**
     * Scans [texts] for mentions and requests those that need it. Use [atSignOptional] for agent
     * text, where a bare stable ID counts (`MentionParser.swift:64-72`).
     */
    fun note(texts: List<String>, atSignOptional: Boolean = false) {
        val identifiers = LinkedHashSet<String>()
        for (text in texts) MentionParser.mentions(text, atSignOptional).mapTo(identifiers) { it.identifier }
        request(identifiers)
    }

    /** [note]s the mentionable texts of [messages], with the "@" optional in the agent's. */
    fun noteMessages(messages: List<ChatMessage>) {
        val identifiers = LinkedHashSet<String>()
        for (message in messages) {
            if (message.hidden) continue
            val atSignOptional = message.author == Participant.AGENT
            for (text in mentionableTexts(message.body)) {
                MentionParser.mentions(text, atSignOptional).mapTo(identifiers) { it.identifier }
            }
        }
        request(identifiers)
    }

    /** Lets identifiers that missed or failed be requested again by the next [note]. */
    fun reopenRetryGate() {
        synchronized(lock) { retryGate.clear() }
    }

    private fun request(identifiers: Set<String>) {
        val batch = synchronized(lock) {
            val known = resolutionsFlow.value
            identifiers.filter { known[it]?.displayName == null && it !in inFlight && it !in retryGate }
                .also { inFlight += it }
        }
        if (batch.isEmpty()) return
        log("Resolving ${batch.size} mention(s)")
        scope.launch {
            try {
                val reply = try {
                    link.request(PhoneMessage.ResolveMentions(batch), timeout = requestTimeout)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Mention resolution failed: ${e::class.simpleName}")
                    null
                }
                settle(batch, reply)
            } finally {
                synchronized(lock) { inFlight -= batch.toSet() }
            }
        }
    }

    private fun settle(batch: List<String>, reply: MacMessage?) {
        if (reply !is MacMessage.MentionsResolved) {
            if (reply != null) log("Mention resolution failed: ${reply::class.simpleName}")
            synchronized(lock) { retryGate += batch }
            return
        }
        val byIdentifier = reply.resolutions.associateBy { it.identifier }
        synchronized(lock) {
            resolutionsFlow.update { current ->
                val next = current.toMutableMap()
                for (identifier in batch) {
                    val resolution = byIdentifier[identifier]
                    if (resolution?.displayName != null) {
                        next[identifier] = resolution
                    } else {
                        retryGate += identifier
                        // A hit is never asked for again, so this only records a miss.
                        if (next[identifier]?.displayName == null) next[identifier] = MentionResolution(identifier, null, null, null)
                    }
                }
                next
            }
        }
    }

    override fun toString(): String = synchronized(lock) {
        "MentionResolver(resolved=${resolutionsFlow.value.size}, inFlight=${inFlight.size}, gated=${retryGate.size})"
    }
}
