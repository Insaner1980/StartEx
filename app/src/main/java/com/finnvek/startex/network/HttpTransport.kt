package com.finnvek.startex.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface HttpTransport {
    suspend fun execute(request: Request): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient,
    private val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) : HttpTransport {
    init {
        require(maximumResponseBytes > 0)
    }

    override suspend fun execute(request: Request): HttpResponse =
        client.newCall(request).awaitResponse().use { response ->
            HttpResponse(
                statusCode = response.code,
                body = readBoundedBody(response.body, maximumResponseBytes),
                headers =
                    response
                        .header("Retry-After")
                        ?.let { retryAfter -> mapOf("Retry-After" to retryAfter) }
                        .orEmpty(),
            )
        }

    private companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 2L * 1_024 * 1_024
    }
}

private suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, rejectedResponse, _ -> rejectedResponse.close() }
                }
            },
        )
    }

class ResponseTooLargeException : IOException("Provider response exceeded the configured limit")

internal fun readBoundedBody(
    body: ResponseBody,
    maximumBytes: Long,
): String {
    require(maximumBytes in 1 until Long.MAX_VALUE)
    if (body.contentLength() > maximumBytes) throw ResponseTooLargeException()
    val source = body.source()
    if (source.request(maximumBytes + 1)) throw ResponseTooLargeException()
    return source.readUtf8()
}

internal fun HttpResponse.httpError(provider: ProviderId): ProviderError? =
    when (statusCode) {
        in 200..299 -> null
        401, 403 -> ProviderError.Unauthorized(provider)
        429 -> ProviderError.RateLimited(provider, retryAfterMillis())
        else -> ProviderError.HttpFailure(provider, statusCode)
    }

private fun HttpResponse.retryAfterMillis(): Long? {
    val value = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
    return value?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000)
}
