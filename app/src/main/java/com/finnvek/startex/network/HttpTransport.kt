package com.finnvek.startex.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException

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
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
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
        }

    private companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 2L * 1_024 * 1_024
    }
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
