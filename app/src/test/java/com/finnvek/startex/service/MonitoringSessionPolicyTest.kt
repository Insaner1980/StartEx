package com.finnvek.startex.service

import com.finnvek.startex.data.settings.OperatingMode
import com.finnvek.startex.network.RetryPolicy
import com.finnvek.startex.security.WalletAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringSessionPolicyTest {
    @Test
    fun `needs-attention session remains eligible for recovery`() {
        assertTrue(MonitoringSessionPolicy.isRecoverableSessionStatus("NEEDS_ATTENTION"))
    }

    @Test
    fun `paper monitoring starts before provider health is known`() {
        val action =
            MonitoringSessionPolicy.startAction(
                operatingMode = OperatingMode.PAPER,
                persistedSessionMode = null,
                providersConfigured = true,
                riskLimitsValid = true,
                freshStartPrerequisitesReady = true,
            )

        assertEquals(SessionStartAction.START_MONITORING, action)
    }

    @Test
    fun `live session remains locked without an execution coordinator`() {
        val action =
            MonitoringSessionPolicy.startAction(
                operatingMode = OperatingMode.LIVE,
                persistedSessionMode = null,
                providersConfigured = true,
                riskLimitsValid = true,
                freshStartPrerequisitesReady = true,
            )

        assertEquals(SessionStartAction.LIVE_EXECUTION_LOCKED, action)
    }

    @Test
    fun `recovery rejects live and malformed persisted session modes`() {
        listOf("LIVE", "live", "", "UNKNOWN").forEach { persistedMode ->
            val action =
                MonitoringSessionPolicy.startAction(
                    operatingMode = OperatingMode.PAPER,
                    persistedSessionMode = persistedMode,
                    providersConfigured = true,
                    riskLimitsValid = true,
                    freshStartPrerequisitesReady = true,
                )

            assertEquals(SessionStartAction.LIVE_EXECUTION_LOCKED, action)
        }
    }

    @Test
    fun `running paper session becomes incompatible with demo or live settings`() {
        assertTrue(MonitoringSessionPolicy.isModeCompatible(false, OperatingMode.PAPER, "PAPER"))
        assertEquals(false, MonitoringSessionPolicy.isModeCompatible(true, OperatingMode.PAPER, "PAPER"))
        assertEquals(false, MonitoringSessionPolicy.isModeCompatible(false, OperatingMode.LIVE, "PAPER"))
        assertEquals(false, MonitoringSessionPolicy.isModeCompatible(false, OperatingMode.PAPER, "LIVE"))
    }

    @Test
    fun `paper monitoring stops when a required key is missing`() {
        val action =
            MonitoringSessionPolicy.startAction(
                operatingMode = OperatingMode.PAPER,
                persistedSessionMode = null,
                providersConfigured = false,
                riskLimitsValid = true,
                freshStartPrerequisitesReady = true,
            )

        assertEquals(SessionStartAction.NOTIFY_AND_STOP, action)
    }

    @Test
    fun `fresh paper monitoring stops when an authoritative preflight fact is missing`() {
        val action =
            MonitoringSessionPolicy.startAction(
                operatingMode = OperatingMode.PAPER,
                persistedSessionMode = null,
                providersConfigured = true,
                riskLimitsValid = true,
                freshStartPrerequisitesReady = false,
            )

        assertEquals(SessionStartAction.NOTIFY_AND_STOP, action)
    }

    @Test
    fun `secure recovery always requires authentication`() {
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = WalletAccessMode.SECURE_SESSION,
                recoveryAuthenticated = false,
                providersConfigured = true,
                providersHealthy = true,
                riskLimitsValid = true,
            )

        assertEquals(RecoveryAction.REQUIRE_AUTHENTICATION, action)
    }

    @Test
    fun `authenticated secure recovery may reconcile when every safety gate passes`() {
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = WalletAccessMode.SECURE_SESSION,
                recoveryAuthenticated = true,
                providersConfigured = true,
                providersHealthy = true,
                riskLimitsValid = true,
            )

        assertEquals(RecoveryAction.RECONCILE_AND_MONITOR, action)
    }

    @Test
    fun `unattended recovery restores monitoring with entries blocked while providers recover`() {
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = WalletAccessMode.UNATTENDED,
                recoveryAuthenticated = false,
                providersConfigured = true,
                providersHealthy = false,
                riskLimitsValid = true,
            )

        assertEquals(RecoveryAction.MONITOR_WITH_ENTRIES_BLOCKED, action)
    }

    @Test
    fun `unattended recovery stops when a required provider key is missing`() {
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = WalletAccessMode.UNATTENDED,
                recoveryAuthenticated = false,
                providersConfigured = false,
                providersHealthy = true,
                riskLimitsValid = true,
            )

        assertEquals(RecoveryAction.NOTIFY_AND_STOP, action)
    }

    @Test
    fun `recovery stops when risk limits are invalid`() {
        val action =
            MonitoringSessionPolicy.recoveryAction(
                accessMode = WalletAccessMode.UNATTENDED,
                recoveryAuthenticated = false,
                providersConfigured = true,
                providersHealthy = true,
                riskLimitsValid = false,
            )

        assertEquals(RecoveryAction.NOTIFY_AND_STOP, action)
    }

    @Test
    fun `reconnect uses capped exponential delay and provider retry after`() {
        val policy =
            DiscoveryReconnectPolicy(
                retryPolicy =
                    RetryPolicy(
                        initialDelayMillis = 1_000,
                        maximumDelayMillis = 8_000,
                        jitter = { it },
                    ),
                maximumConsecutiveFailures = 5,
            )

        assertEquals(1_000L, policy.nextDelayMillis(1, null))
        assertEquals(5_000L, policy.nextDelayMillis(2, 5_000))
        assertEquals(4_000L, policy.nextDelayMillis(3, null))
        assertEquals(8_000L, policy.nextDelayMillis(4, null))
    }

    @Test
    fun `reconnect stops at the consecutive failure limit`() {
        val policy =
            DiscoveryReconnectPolicy(
                retryPolicy =
                    RetryPolicy(
                        initialDelayMillis = 1_000,
                        maximumDelayMillis = 8_000,
                        jitter = { it },
                    ),
                maximumConsecutiveFailures = 5,
            )

        assertNull(policy.nextDelayMillis(5, null))
    }

    @Test
    fun `connection becomes stale only after the allowed idle interval`() {
        val policy = DiscoveryFreshnessPolicy(maximumIdleMillis = 90_000)

        assertEquals(false, policy.isStale(lastEventAtMillis = 10_000, nowMillis = 100_000))
        assertEquals(true, policy.isStale(lastEventAtMillis = 10_000, nowMillis = 100_001))
    }

    @Test
    fun `session heartbeat cutoff keeps the exact age boundary fresh`() {
        val policy = SessionHeartbeatFreshnessPolicy(maximumAgeMillis = 90_000)

        assertEquals(10_000L, policy.staleCutoffMillis(nowMillis = 100_000))
        assertEquals(false, policy.isStale(lastHeartbeatAtMillis = 10_000, nowMillis = 100_000))
        assertEquals(true, policy.isStale(lastHeartbeatAtMillis = 9_999, nowMillis = 100_000))
        assertEquals(true, policy.isStale(lastHeartbeatAtMillis = 100_001, nowMillis = 100_000))
    }

    @Test
    fun `wallet account event reveals only whether incoming SOL was observed`() {
        assertEquals("ACCOUNT_CHANGED", walletAccountEventCode(null, 10))
        assertEquals("ACCOUNT_CHANGED", walletAccountEventCode(10, 9))
        assertEquals("INCOMING_SOL_DETECTED", walletAccountEventCode(10, 11))
    }
}
