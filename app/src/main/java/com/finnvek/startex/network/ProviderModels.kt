package com.finnvek.startex.network

enum class ProviderId {
    HELIUS,
    PUMP_PORTAL,
    JUPITER,
    KRAKEN,
}

sealed class ProviderError(
    open val provider: ProviderId? = null,
    open val invalidField: String? = null,
    open val providerCode: Int? = null,
    open val retryAfterMillis: Long? = null,
) {
    data class MissingApiKey(
        override val provider: ProviderId,
    ) : ProviderError(provider = provider)

    data class InvalidRequest(
        override val provider: ProviderId,
        override val invalidField: String,
    ) : ProviderError(provider = provider, invalidField = invalidField)

    data class InvalidResponse(
        override val provider: ProviderId,
        override val invalidField: String,
    ) : ProviderError(provider = provider, invalidField = invalidField)

    data class RateLimited(
        override val provider: ProviderId,
        override val retryAfterMillis: Long?,
    ) : ProviderError(provider = provider, retryAfterMillis = retryAfterMillis)

    data class Unauthorized(
        override val provider: ProviderId,
    ) : ProviderError(provider = provider)

    data class HttpFailure(
        override val provider: ProviderId,
        val statusCode: Int,
    ) : ProviderError(provider = provider)

    data class RemoteFailure(
        override val provider: ProviderId,
        override val providerCode: Int,
    ) : ProviderError(provider = provider, providerCode = providerCode)

    data class ExecutionRejected(
        override val provider: ProviderId,
        override val providerCode: Int,
    ) : ProviderError(provider = provider, providerCode = providerCode)

    data class ConnectionClosed(
        override val provider: ProviderId,
        val closeCode: Int,
    ) : ProviderError(provider = provider)

    data class StaleData(
        override val provider: ProviderId,
        val ageMillis: Long,
    ) : ProviderError(provider = provider)

    data class NetworkUnavailable(
        override val provider: ProviderId,
    ) : ProviderError(provider = provider)

    data class SubmissionUncertain(
        override val provider: ProviderId,
    ) : ProviderError(provider = provider)
}

sealed interface ProviderResult<out T> {
    data class Success<T>(
        val value: T,
        val receivedAtMillis: Long = System.currentTimeMillis(),
    ) : ProviderResult<T>

    data class Failure(
        val error: ProviderError,
    ) : ProviderResult<Nothing>
}

fun interface ApiKeySource {
    suspend fun apiKeyFor(provider: ProviderId): String?
}
