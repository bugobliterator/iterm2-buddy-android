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
    /**
     * The Mac named it and gave something to open: show [displayName], tappable, opening
     * [MentionResolution.workgroupID]'s member list when set, else [MentionResolution.sessionGuid].
     */
    data class Named(val resolution: MentionResolution) : MentionDisplay {
        val displayName: String get() = checkNotNull(resolution.displayName)
    }

    /**
     * The Mac answered that it does not resolve, or named it with nothing to open: show
     * [DEFUNCT_SESSION_TEXT], as iOS does (`MessageBubbleView.swift:147-165`).
     */
    data object Defunct : MentionDisplay

    /** Not answered (yet): show the mention as written. */
    data object Raw : MentionDisplay
}

/** How the mention [identifier] renders with [resolutions] ([MentionResolver.resolutions]). */
fun mentionDisplay(resolutions: Map<String, MentionResolution>, identifier: String): MentionDisplay =
    mentionDisplay(resolutions[identifier])

/** How a mention renders given what the Mac said about it: [resolution], null when it has not answered. */
fun mentionDisplay(resolution: MentionResolution?): MentionDisplay {
    if (resolution == null) return MentionDisplay.Raw
    val opens = resolution.workgroupID != null || resolution.sessionGuid != null
    return if (resolution.displayName != null && opens) MentionDisplay.Named(resolution) else MentionDisplay.Defunct
}

/**
 * The texts of a message body that can hold @-mentions, as iOS scans them (`AppModel.swift:4141-4166`):
 * plain text and markdown, their multipart subparts (not attachments or context), an explanation
 * response, and a command request's markdown description; also an executing-command notice's
 * description, which the conversation renders as markdown.
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
    // Android addition: the notice renders its command's description with mentions named.
    is MessageBody.ClientNotice -> (body.action as? ClientAction.ExecutingCommand)?.let { listOf(it.content.markdownDescription()) }
        ?: emptyList()
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
 * rpc bound when null). [scope] must outlive the resolver: a request whose scope is cancelled
 * still releases its identifiers, but its answer is lost. Thread-safe.
 * Logs counts and failure kinds only, never text or names.
 *
 * Scoped to the pairing, like the link it asks through: [clear] forgets everything, and the
 * owner registers it with the unpair cleanups.
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

    /** Bumped by [clear], so a request sent before it neither lands nor releases anything after. */
    private var generation = 0L

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

    /**
     * Forgets every resolution, the retry gate and the requests in flight (whose answers are then
     * dropped): the pairing ended. Register it with the unpair cleanups.
     */
    fun clear() {
        synchronized(lock) {
            generation++
            inFlight.clear()
            retryGate.clear()
            resolutionsFlow.value = emptyMap()
        }
    }

    private fun request(identifiers: Set<String>) {
        var sentIn = 0L
        val batch = synchronized(lock) {
            sentIn = generation
            val known = resolutionsFlow.value
            identifiers.filter { known[it]?.displayName == null && it !in inFlight && it !in retryGate }
                .also { inFlight += it }
        }
        if (batch.isEmpty()) return
        log("Resolving ${batch.size} mention(s)")
        val job = scope.launch {
            val reply = try {
                link.request(PhoneMessage.ResolveMentions(batch), timeout = requestTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Mention resolution failed: ${e::class.simpleName}")
                null
            }
            settle(batch, reply, sentIn)
        }
        // Runs however the job ends, even cancelled before it started, so nothing stays in flight.
        job.invokeOnCompletion {
            synchronized(lock) { if (generation == sentIn) inFlight -= batch.toSet() }
        }
    }

    private fun settle(batch: List<String>, reply: MacMessage?, sentIn: Long) {
        if (reply !is MacMessage.MentionsResolved) {
            if (reply != null) log("Mention resolution failed: ${reply::class.simpleName}")
            synchronized(lock) { if (generation == sentIn) retryGate += batch }
            return
        }
        val byIdentifier = reply.resolutions.associateBy { it.identifier }
        synchronized(lock) {
            if (generation != sentIn) return
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
