package com.finnvek.startex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPolicyTest {
    @Test
    fun `backoff grows exponentially and remains bounded`() {
        val policy =
            RetryPolicy(
                initialDelayMillis = 1_000,
                maximumDelayMillis = 8_000,
                jitter = { it },
            )

        assertEquals(1_000, policy.delayMillis(failureCount = 1))
        assertEquals(4_000, policy.delayMillis(failureCount = 3))
        assertEquals(8_000, policy.delayMillis(failureCount = 8))
    }

    @Test
    fun `server retry-after takes precedence over exponential delay`() {
        val policy =
            RetryPolicy(
                initialDelayMillis = 1_000,
                maximumDelayMillis = 8_000,
                jitter = { delay -> delay + delay / 4 },
            )

        assertEquals(12_000, policy.delayMillis(failureCount = 2, retryAfterMillis = 12_000))
    }

    @Test
    fun `jitter is applied after exponential growth and capped`() {
        val policy =
            RetryPolicy(
                initialDelayMillis = 1_000,
                maximumDelayMillis = 8_000,
                jitter = { delay -> delay + delay / 4 },
            )

        assertEquals(5_000, policy.delayMillis(failureCount = 3))
        assertEquals(8_000, policy.delayMillis(failureCount = 8))
    }

    @Test
    fun `provider sample is rejected after freshness window`() {
        val freshness = ProviderFreshness(maxAgeMillis = 5_000)

        assertTrue(freshness.isFresh(receivedAtMillis = 10_000, nowMillis = 15_000))
        assertFalse(freshness.isFresh(receivedAtMillis = 10_000, nowMillis = 15_001))
    }

    @Test
    fun `health becomes unavailable after repeated failures`() {
        val tracker = ProviderHealthTracker(unavailableAfterFailures = 3)

        tracker.recordFailure(ProviderId.HELIUS, ProviderError.NetworkUnavailable(ProviderId.HELIUS))
        tracker.recordFailure(ProviderId.HELIUS, ProviderError.NetworkUnavailable(ProviderId.HELIUS))
        assertEquals(ProviderHealthState.DEGRADED, tracker.snapshot(ProviderId.HELIUS).state)

        tracker.recordFailure(ProviderId.HELIUS, ProviderError.NetworkUnavailable(ProviderId.HELIUS))
        assertEquals(ProviderHealthState.UNAVAILABLE, tracker.snapshot(ProviderId.HELIUS).state)
    }
}
