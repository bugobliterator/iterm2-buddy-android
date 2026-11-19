package dev.bugobliterator.itermbuddy.fakemac

import dev.bugobliterator.itermbuddy.Fixtures
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The chat side of a v3.7.7 Mac on top of a [FakeMac], answering from the `agent-chat` samples as
 * `CompanionHostBridge.swift` does:
 * - `listChatsAndSessions` → `chatsAndSessions`; `createChat` → `chatCreated`; `resolveMentions` →
 *   `mentionsResolved`; `fetchWorkgroupInfo` → `workgroupInfo`.
 * - `subscribe` → the `history` reply (the sample's messages, retargeted to the requested chat),
 *   then the unsolicited snapshot, `typingStatus(true)` and `turnLifecycle(started)` only when they
 *   hold ([agentTyping], [turnInProgress]), then whatever [afterSubscribe] sends, all before any
 *   later message on the link (`HB:1411-1443`).
 * - [delayReply] holds back the next reply to a case (the serve loop goes on); [streamReply] sends
 *   a scripted turn; [pushChatList], [flipAi] and [refuseDelete] send the unsolicited messages.
 * - AI off ([setAiAvailable]) as the Mac's `updateAIAvailability` and `requireAI` (`HB:2250-2300`):
 *   every subscription is dropped, a `subscribe` is refused with the typed `aiUnavailable` error,
 *   a `publish` (no request ID) is dropped silently, the list is answered with no chats
 *   (`HB:448-455`), and nothing is streamed. The flip is announced separately ([announceAi]),
 *   as the Mac's `link.poke(.aiAvailability)` is a task of its own, so a reply computed after the
 *   flip can reach the phone before the announcement. [flipAi] does both.
 *
 * What the phone sent is in [FakeMac.received] ([sent] filters it by case).
 */
class FakeChatMac(val mac: FakeMac, private val scope: CoroutineScope) {
    /** Whether the subscribe snapshot says the agent is typing (`typingStatus(true)`). */
    @Volatile var agentTyping = false

    /** Whether the subscribe snapshot says a turn is in progress (`turnLifecycle(started)`). */
    @Volatile var turnInProgress = false

    /** Runs after each subscribe's history and snapshot, inside the same serve step: (chatID, requestID). */
    @Volatile var afterSubscribe: suspend (String, Long?) -> Unit = { _, _ -> }

    /** The request IDs of the subscribes answered so far, by chat. */
    val subscribes = CopyOnWriteArrayList<Pair<String, Long?>>()

    private val delays = ConcurrentHashMap<String, Duration>()

    /** Whether AI is on on this Mac now (`CompanionPairingController.aiAvailable()`). */
    @Volatile var aiAvailable = true
        private set

    /** The chats subscribed on the Mac now: dropped when AI turns off, as `updateAIAvailability` does. */
    val subscribed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        reply("listChatsAndSessions") { _, id ->
            send(samplePayload(if (aiAvailable) "chats-and-sessions.json" else "chats-and-sessions-ai-off.json"), id)
        }
        reply("createChat") { _, id -> if (refusedWithoutAi(id)) Unit else send(samplePayload("chat-created.json"), id) }
        reply("resolveMentions") { _, id -> send(samplePayload("mentions-resolved.json"), id) }
        reply("fetchWorkgroupInfo") { _, id -> send(samplePayload("workgroup-info.json"), id) }
        reply("unsubscribe") { body, _ -> subscribed -= body.getValue("chatID").jsonPrimitive.content }
        reply("subscribe") { body, id ->
            val chatID = body.getValue("chatID").jsonPrimitive.content
            if (refusedWithoutAi(id)) return@reply
            subscribed += chatID
            subscribes += chatID to id
            send(history(chatID), id)
            if (agentTyping) send(retarget(samplePayload("typing-status-on.json"), chatID), null)
            if (turnInProgress) send(retarget(samplePayload("turn-lifecycle-started.json"), chatID), null)
            afterSubscribe(chatID, id)
        }
    }

    /** The next reply to a request whose case is [case] goes [by] later, from its own coroutine. */
    fun delayReply(case: String, by: Duration) {
        delays[case] = by
    }

    /** The bodies of what the phone sent with payload case [case], in order. */
    fun sent(case: String): List<JsonObject> = mac.receivedBodies(case)

    /** The `history` payload for [chatID]: the sample's messages with their chat ID set to it. */
    fun history(chatID: String = SAMPLE_CHAT): JsonObject = Companion.history(chatID)

    /**
     * A scripted agent turn in [chatID]: typing on, `turnLifecycle(started)`, the opening delivery,
     * [appends] `append` deltas naming it (each with its own message ID, in order), typing off, and
     * `turnLifecycle(ended)`.
     */
    suspend fun streamReply(chatID: String = SAMPLE_CHAT, appends: Int) {
        // No subscription survives AI off: the Mac pushes nothing.
        if (!aiAvailable) return
        send(retarget(samplePayload("typing-status-on.json"), chatID), null)
        send(retarget(samplePayload("turn-lifecycle-started.json"), chatID), null)
        send(retarget(samplePayload("delivery-agent-opening.json"), chatID), null)
        repeat(appends) { send(append(chatID, it), null) }
        send(retarget(samplePayload("typing-status-off.json"), chatID), null)
        send(retarget(samplePayload("turn-lifecycle-ended.json"), chatID), null)
    }

    /** The `index`th append delta of the sample reply, with message ID [appendID]. */
    fun append(chatID: String, index: Int): JsonObject {
        val payload = retarget(samplePayload("delivery-append.json"), chatID)
        val delivery = payload.getValue("delivery").jsonObject
        val message = delivery.getValue("message").jsonObject
        val renamed = JsonObject(message + ("uniqueID" to JsonPrimitive(appendID(index))))
        return JsonObject(mapOf("delivery" to JsonObject(delivery + ("message" to renamed))))
    }

    /** Pushes `chatListChanged` with the sample list. */
    suspend fun pushChatList() = send(samplePayload("chat-list-changed.json"), null)

    /** Turns AI on or off ([setAiAvailable]) and announces it ([announceAi]), as the Mac does. */
    suspend fun flipAi(available: Boolean) {
        setAiAvailable(available)
        announceAi()
    }

    /**
     * Turns AI on or off on the Mac without telling the phone yet: off drops every subscription
     * (`HB:2250-2263`), and from now on requests are gated and the list answered as
     * [aiAvailable] says. The next hello carries it too.
     */
    fun setAiAvailable(available: Boolean) {
        aiAvailable = available
        mac.helloAiAvailable = available
        if (!available) subscribed.clear()
    }

    /** Sends `aiAvailabilityChanged` with [aiAvailable]: the link's poke, separate from any reply. */
    suspend fun announceAi() =
        send(JsonObject(mapOf("aiAvailabilityChanged" to JsonObject(mapOf("available" to JsonPrimitive(aiAvailable))))), null)

    /**
     * `requireAI` (`HB:2276-2300`): with AI off, a request with an ID gets the typed `aiUnavailable`
     * error and one without is dropped; true when it was refused.
     */
    suspend fun refusedWithoutAi(requestID: Long?): Boolean {
        if (aiAvailable) return false
        if (requestID != null) send(samplePayload("error-ai-unavailable.json"), requestID)
        return true
    }

    /** The unsolicited `error` a refused `deleteChat` gets (`HB:458-470`). */
    suspend fun refuseDelete() = send(samplePayload("error-delete-refused.json"), null)

    private fun reply(case: String, answer: suspend (JsonObject, Long?) -> Unit) {
        mac.onRequestScripted(case) { envelope ->
            val id = (envelope["requestID"] as? JsonPrimitive)?.long
            val body = envelope.getValue("payload").jsonObject.getValue(case).jsonObject
            val wait = delays.remove(case)
            if (wait == null) {
                answer(body, id)
            } else {
                scope.launch {
                    delay(wait)
                    try {
                        answer(body, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The link went away while the reply was held back.
                    }
                }
            }
        }
    }

    private suspend fun send(payload: JsonObject, requestID: Long?) = mac.sendMessage(payload, requestID)

    companion object {
        /** The chat every `agent-chat` sample is about. */
        const val SAMPLE_CHAT = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"

        /** The message ID [append] gives the `index`th delta. */
        fun appendID(index: Int): String = "11111111-2222-4333-8444-%012d".format(900_000 + index)

        /** The payload (`{"<case>": ...}`) of the `agent-chat` sample [name]. */
        fun samplePayload(name: String): JsonObject =
            Json.parseToJsonElement(Fixtures.read("messages/agent-chat/$name")).jsonObject
                .getValue("json").jsonObject.getValue("payload").jsonObject

        /** The `history` payload for [chatID]: the sample's messages with their chat ID set to it. */
        fun history(chatID: String = SAMPLE_CHAT): JsonObject = retarget(samplePayload("history.json"), chatID)

        /** [payload] with every `chatID` in it, at any depth, set to [chatID]. */
        fun retarget(payload: JsonObject, chatID: String): JsonObject = rewrite(payload, chatID) as JsonObject

        private fun rewrite(element: JsonElement, chatID: String): JsonElement = when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) -> if (key == "chatID") JsonPrimitive(chatID) else rewrite(value, chatID) },
            )
            is JsonArray -> JsonArray(element.map { rewrite(it, chatID) })
            else -> element
        }
    }
}
