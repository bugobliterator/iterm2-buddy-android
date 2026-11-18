package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a session-bound chat's session still exists, so the conversation offers "Go to session"
 * only for one the Mac confirms: a port of iOS's `probeBoundSessionLiveness` (`AM:4256-4276`). It
 * asks with `fetchSessionScreenInfo`, which the Mac resolves through its full lookup (tabs, buried
 * and peer sessions, a stable ID or a raw guid, `HB:2007`), and discards the reply's geometry and
 * name. A `sessionScreenInfo` reply means live; an `unknownSession` error means gone; a timeout,
 * another error or a lost link leaves the prior verdict as it was, so a blip never hides the button.
 *
 * The conversation [watch]es its chat while it is open (iOS probes on appear,
 * `IOS/Screens/ConversationView.swift:96-101`) and [unwatch]es it when it closes; [linkUp] probes
 * every watched chat again. One probe per chat is out at a time: a newer one supersedes it, and the
 * older answer is dropped. [clear] (unpair) forgets everything and drops answers still out.
 *
 * Each request runs in its own coroutine in [scope], bounded by [timeout] (15 s, as iOS's,
 * `AM:4249-4253`). Thread-safe. Logs chat IDs and outcome kinds only.
 */
class BoundSessionProbe(
    private val link: StreamLink,
    private val scope: CoroutineScope,
    private val timeout: Duration = 15.seconds,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val liveFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** The guid each verdict is about, so a chat bound anew is not judged by its old session. */
    private val verdictGuids = HashMap<String, String>()

    /** The open conversations' bound guids (null: unbound, or not listed yet), by chat ID. */
    private val watched = HashMap<String, String?>()
    private val probes = HashMap<String, Job>()
    private var generation = 0L

    /** By chat ID: true when its session is confirmed, false when gone. Absent: not probed, or no verdict yet. */
    val live: StateFlow<Map<String, Boolean>> = liveFlow.asStateFlow()

    /**
     * [chatID]'s conversation is open, bound to [guid] (its `terminalSessionGuid`; null for an
     * orchestrator or unbound chat, which is never probed). Probes when the binding is new to the
     * watch; the same binding again probes nothing (a list refresh re-reports it).
     */
    fun watch(chatID: String, guid: String?) {
        val probeNow = synchronized(lock) {
            val known = watched.containsKey(chatID)
            val before = watched.put(chatID, guid)
            guid != null && (!known || before != guid)
        }
        if (probeNow && guid != null) probe(chatID, guid)
    }

    /**
     * Re-reads every watched chat's binding with [guidOf] (from a new chat list): a chat bound
     * anew, or listed for the first time since it was opened, is probed once.
     */
    fun refreshBindings(guidOf: (String) -> String?) {
        val changed = synchronized(lock) {
            watched.keys.toList().mapNotNull { chatID ->
                val guid = guidOf(chatID)
                if (watched.put(chatID, guid) == guid || guid == null) null else chatID to guid
            }
        }
        for ((chatID, guid) in changed) probe(chatID, guid)
    }

    /** [chatID]'s conversation closed: later link ups do not probe it. Its verdict is kept. */
    fun unwatch(chatID: String) {
        synchronized(lock) { watched -= chatID }
    }

    /** A new link is up: probes every watched chat again. */
    fun linkUp() {
        val all = synchronized(lock) { watched.toMap() }
        for ((chatID, guid) in all) if (guid != null) probe(chatID, guid)
    }

    /** Asks once whether [guid], [chatID]'s bound session, exists, superseding a probe still out for it. */
    fun probe(chatID: String, guid: String) {
        synchronized(lock) {
            val sentIn = generation
            if (verdictGuids[chatID] != guid) {
                verdictGuids -= chatID
                liveFlow.update { it - chatID }
            }
            probes.remove(chatID)?.cancel()
            // Started lazily so it is registered before it can finish and deregister itself.
            val job = scope.launch(start = CoroutineStart.LAZY) { run(chatID, guid, sentIn) }
            probes[chatID] = job
            job.invokeOnCompletion { synchronized(lock) { if (probes[chatID] === job) probes -= chatID } }
            job.start()
        }
    }

    /** Forgets every verdict and watch, and drops the answers still out (unpair). */
    fun clear() {
        synchronized(lock) {
            generation++
            watched.clear()
            verdictGuids.clear()
            probes.values.forEach { it.cancel() }
            probes.clear()
            liveFlow.value = emptyMap()
        }
    }

    private suspend fun run(chatID: String, guid: String, sentIn: Long) {
        val verdict = try {
            when (val reply = link.request(PhoneMessage.FetchSessionScreenInfo(guid), timeout = timeout)) {
                is MacMessage.SessionScreenInfoMessage -> true
                is MacMessage.Error -> if (reply.code == UNKNOWN_SESSION_CODE) false else null.also {
                    log("Bound session probe for $chatID was inconclusive: error ${reply.code}")
                }
                else -> null.also { log("Bound session probe for $chatID got ${reply::class.simpleName}") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Bound session probe for $chatID was inconclusive: ${e::class.simpleName}")
            null
        } ?: return
        val self = currentCoroutineContext().job
        synchronized(lock) {
            // Superseded by a newer probe, or cleared: the answer is dropped.
            if (generation != sentIn || probes[chatID] !== self) return
            verdictGuids[chatID] = guid
            liveFlow.update { it + (chatID to verdict) }
        }
    }

    override fun toString(): String = synchronized(lock) {
        "BoundSessionProbe(verdicts=${liveFlow.value.size}, watched=${watched.size}, probing=${probes.size})"
    }
}

/** The Mac's error code for a session it cannot find (`CompanionError.Code.unknownSession`). */
private const val UNKNOWN_SESSION_CODE = "unknownSession"
