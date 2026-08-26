package com.finnvek.startex.ui

import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.isFreshHealthy
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.ui.components.abbreviateAddress
import com.finnvek.startex.ui.screens.walletHoldingsForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class StartExUiStateTest {
    @Test
    fun credentialProvidersExcludeKeylessKraken() {
        assertEquals(
            listOf(ProviderId.HELIUS, ProviderId.PUMP_PORTAL, ProviderId.JUPITER),
            CredentialProviders,
        )
        assertFalse(ProviderId.KRAKEN in CredentialProviders)
    }

    @Test
    fun paperPreflightDoesNotRequireWalletReserve() {
        val state =
            PreflightState(
                walletReady = true,
                backupVerified = true,
                providersHealthy = true,
                limitsConfigured = true,
                reserveReady = false,
                reserveRequired = false,
                notificationsAllowed = true,
                deviceHealthReady = true,
            )

        assertTrue(state.isReady)
    }

    @Test
    fun livePreflightFailsClosedWithoutWalletReserve() {
        val state =
            PreflightState(
                walletReady = true,
                backupVerified = true,
                providersHealthy = true,
                limitsConfigured = true,
                reserveReady = false,
                reserveRequired = true,
                notificationsAllowed = true,
                deviceHealthReady = true,
            )

        assertFalse(state.isReady)
    }

    @Test
    fun paperPreflightFailsClosedWithoutDeviceHealth() {
        val state =
            PreflightState(
                walletReady = true,
                backupVerified = true,
                providersHealthy = true,
                limitsConfigured = true,
                reserveReady = true,
                reserveRequired = false,
                notificationsAllowed = true,
                deviceHealthReady = false,
            )

        assertFalse(state.isReady)
    }

    @Test
    fun addressAbbreviationKeepsBothEnds() {
        assertEquals("1234567…4567890", abbreviateAddress("12345678901234567890"))
    }

    @Test
    fun unattendedModeRequiresBothAcknowledgementsAndExistingCaps() {
        val risk = DefaultConfiguration.risk(createdAtMillis = 1)

        assertFalse(
            canEnableUnattendedMode(
                walletConfigured = true,
                risk = risk,
                monitorState = MonitorState.Stopped,
                dedicatedWalletAcknowledged = true,
                reducedSecurityAcknowledged = false,
            ),
        )
        assertTrue(
            canEnableUnattendedMode(
                walletConfigured = true,
                risk = risk,
                monitorState = MonitorState.Stopped,
                dedicatedWalletAcknowledged = true,
                reducedSecurityAcknowledged = true,
            ),
        )
    }

    @Test
    fun tokenHoldingPreservesAtomicAmountAndFlagsToken2022() {
        val holding =
            WalletTokenHolding(
                mint = "mint",
                tokenProgram = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
                amountAtomic = BigInteger("1234567"),
                decimals = 6,
            )

        assertEquals("1.234567", holding.formattedAmount)
        assertTrue(holding.isToken2022)
    }

    @Test
    fun walletHoldingsDisplayIsCappedAtTenAndKeepsToken2022Visible() {
        val standard = (1..12).map { holding("standard-$it", STANDARD_TOKEN_PROGRAM_ID) }
        val token2022 = (1..3).map { holding("token-2022-$it", TOKEN_2022_PROGRAM_ID) }

        val displayed = walletHoldingsForDisplay(standard + token2022)

        assertEquals(10, displayed.size)
        assertEquals(9, displayed.count { !it.isToken2022 })
        assertEquals(1, displayed.count(WalletTokenHolding::isToken2022))
    }

    @Test
    fun staleRunningSessionIsNeverPresentedAsRunning() {
        val session =
            BotSessionEntity(
                id = "session",
                mode = "PAPER",
                status = "RUNNING",
                strategyVersion = 1,
                riskVersion = 1,
                startedAtMillis = 1,
                stoppedAtMillis = null,
                stopReason = null,
                lastHeartbeatAtMillis = 9_999,
            )

        assertEquals(MonitorState.NeedsAttention, monitorStateFor(session, nowMillis = 100_000))
        assertEquals(
            MonitorState.Running,
            monitorStateFor(session.copy(lastHeartbeatAtMillis = 10_000), nowMillis = 100_000),
        )
    }

    @Test
    fun providerHealthRequiresRecentRealSuccess() {
        val health =
            ProviderHealthEntity(
                provider = ProviderId.HELIUS.name,
                state = "HEALTHY",
                consecutiveFailures = 0,
                lastSuccessAtMillis = 1_000,
                lastFailureAtMillis = null,
                latencyMillis = 10,
                retryAfterMillis = null,
                lastFailureCode = null,
                updatedAtMillis = 1_000,
            )

        assertTrue(health.isFreshHealthy(nowMillis = 1_100, maximumAgeMillis = 100))
        assertFalse(health.isFreshHealthy(nowMillis = 1_101, maximumAgeMillis = 100))
        assertFalse(health.copy(lastSuccessAtMillis = 1_101).isFreshHealthy(1_100, 100))
        assertFalse(health.copy(state = "OFFLINE").isFreshHealthy(1_100, 100))
    }

    private fun holding(
        mint: String,
        tokenProgram: String,
    ) = WalletTokenHolding(
        mint = mint,
        tokenProgram = tokenProgram,
        amountAtomic = BigInteger.ONE,
        decimals = 0,
    )

    private companion object {
        const val STANDARD_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}
