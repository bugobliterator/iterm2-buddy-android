package dev.bugobliterator.itermbuddy.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

sealed interface RegisterResult {
    /** The relay stored the verifier, or already holds one (403 "already registered"). */
    data object Registered : RegisterResult

    /** Any other HTTP answer; [body] is the relay's reply, cut to a few KiB. */
    data class Refused(val status: Int, val body: String) : RegisterResult

    /** No HTTP answer: DNS, TCP, TLS, timeout or a dropped connection. */
    data class TransportError(val cause: String) : RegisterResult
}

/**
 * Registers a room's join verifier with the relay (room.js:648-724), which locks the room so
 * later joins must be signed. Called after admission with the token the relay handed out
 * (valid 5 min, single use), once the Mac has stored the room secret.
 *
 * @param callTimeoutMillis bound on the whole call; tests pass a few hundred milliseconds.
 */
class RelayRegistrar(
    client: OkHttpClient,
    private val endpoints: RelayEndpoints,
    callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
) {
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor(StripOriginHeader)
        .build()

    /**
     * `POST <httpBaseUrl>/register` with `{"registrationToken": ..., "verifier": <std base64>}`.
     * 2xx and 403 "already registered" both mean the room is locked by a verifier.
     */
    suspend fun register(roomName: String, registrationToken: String, verifier: ByteArray): RegisterResult {
        require(ROOM_NAME_PATTERN.matches(roomName)) { "room name must be 64 lowercase hex characters" }
        val body = buildJsonObject {
            put("registrationToken", registrationToken)
            put("verifier", Base64.getEncoder().encodeToString(verifier))
        }.toString()
        val request = Request.Builder()
            .url(endpoints.httpBaseUrl + REGISTER_PATH)
            .header(ROOM_HEADER, roomName)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).await().use { response ->
                val text = response.peekBody(MAX_BODY_BYTES).string()
                when {
                    response.isSuccessful -> RegisterResult.Registered
                    response.code == HTTP_FORBIDDEN && relayError(text) == ALREADY_REGISTERED ->
                        RegisterResult.Registered
                    else -> RegisterResult.Refused(response.code, text)
                }
            }
        } catch (e: IOException) {
            RegisterResult.TransportError(e.toString())
        }
    }

    private fun relayError(text: String): String? = try {
        ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 30_000L
        const val REGISTER_PATH = "/register"
        const val HTTP_FORBIDDEN = 403
        const val ALREADY_REGISTERED = "already registered"
        const val MAX_BODY_BYTES = 4_096L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
