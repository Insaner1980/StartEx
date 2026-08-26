package com.finnvek.startex.ui

import com.finnvek.startex.data.local.ProviderHealthEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenStateProjectionTest {
    @Test
    fun `service health update does not change unrelated screen states`() {
        val before = PersistedAppState(loaded = true, onboardingComplete = true)
        val after = before.copy(providerHealth = listOf(providerHealth()))

        assertEquals(before.homeScreenState(), after.homeScreenState())
        assertEquals(before.walletScreenState(), after.walletScreenState())
        assertEquals(before.historyScreenState(), after.historyScreenState())
    }

    @Test
    fun `visible screen data changes its screen state`() {
        val before = PersistedAppState(loaded = true, onboardingComplete = true)

        assertNotEquals(before.homeScreenState(), before.copy(walletAddress = "wallet").homeScreenState())
        assertNotEquals(before.walletScreenState(), before.copy(walletUnlocked = true).walletScreenState())
        assertNotEquals(before.historyScreenState(), before.copy(demoMode = true).historyScreenState())
    }

    private fun providerHealth() =
        ProviderHealthEntity(
            provider = "HELIUS",
            state = "HEALTHY",
            consecutiveFailures = 0,
            lastSuccessAtMillis = 2L,
            lastFailureAtMillis = null,
            latencyMillis = 25L,
            retryAfterMillis = null,
            lastFailureCode = null,
            updatedAtMillis = 2L,
        )
}
