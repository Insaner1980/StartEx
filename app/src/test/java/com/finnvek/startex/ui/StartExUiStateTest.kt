package com.finnvek.startex.ui

import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.BotSessionEntity
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.ui.components.abbreviateAddress
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
}
