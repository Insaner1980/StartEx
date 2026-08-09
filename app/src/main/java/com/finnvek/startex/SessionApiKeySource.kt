package com.finnvek.startex

import com.finnvek.startex.network.ApiKeySource
import com.finnvek.startex.network.ProviderId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionApiKeySource : ApiKeySource {
    private val mutex = Mutex()
    private val keys = mutableMapOf<ProviderId, CharArray>()

    override suspend fun apiKeyFor(provider: ProviderId): String? =
        mutex.withLock {
            keys[provider]?.concatToString()
        }

    suspend fun put(
        provider: ProviderId,
        key: CharArray,
    ) {
        require(key.isNotEmpty()) { "API key is required" }
        mutex.withLock {
            keys.remove(provider)?.fill('\u0000')
            keys[provider] = key.copyOf()
        }
    }

    suspend fun remove(provider: ProviderId) {
        mutex.withLock {
            keys.remove(provider)?.fill('\u0000')
        }
    }

    suspend fun clear() {
        mutex.withLock {
            keys.values.forEach { it.fill('\u0000') }
            keys.clear()
        }
    }
}
