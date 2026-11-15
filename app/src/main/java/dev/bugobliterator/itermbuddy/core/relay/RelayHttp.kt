package dev.bugobliterator.itermbuddy.core.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The relay's room header (relay `src/index.js:13`). */
internal const val ROOM_HEADER = "x-relay-room"
internal const val USER_AGENT = "iTermBuddyAndroid"

/** The relay's room-name rule (relay `src/index.js:12`), checked before any request is made. */
internal val ROOM_NAME_PATTERN = Regex("[0-9a-f]{64}")

/**
 * Removes any `Origin` header. The relay answers 403 to every request that carries one (relay
 * `src/index.js:21-23`). Appended after the caller's interceptors, so none of them can add one.
 */
internal object StripOriginHeader : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Origin") == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader("Origin").build())
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
