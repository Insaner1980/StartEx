package com.finnvek.startex.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAccessPolicyTest {
    @Test
    fun `secure session signs only while authenticated and foreground`() {
        val policy = WalletAccessPolicy()

        assertFalse(policy.canSign(isForeground = true))
        policy.onAuthenticationSucceeded()
        assertTrue(policy.canSign(isForeground = true))
        assertFalse(policy.canSign(isForeground = false))

        policy.onAppBackgrounded()
        assertFalse(policy.canSign(isForeground = true))
    }

    @Test
    fun `cancelled authentication leaves the secure session locked`() {
        val policy = WalletAccessPolicy()
        policy.onAuthenticationSucceeded()

        policy.onAuthenticationRejected()

        assertFalse(policy.canSign(isForeground = true))
        assertFalse(policy.hasCurrentAuthentication)
    }

    @Test
    fun `unattended mode requires authentication before it can be enabled`() {
        val policy = WalletAccessPolicy()

        assertEquals(ModeChangeResult.AUTHENTICATION_REQUIRED, policy.setUnattendedEnabled(true))
        assertEquals(WalletAccessMode.SECURE_SESSION, policy.mode)

        policy.onAuthenticationSucceeded()
        assertEquals(
            ModeChangeResult.CHANGED,
            policy.setUnattendedEnabled(true, completeUnattendedPrerequisites()),
        )
        assertEquals(WalletAccessMode.UNATTENDED, policy.mode)
    }

    @Test
    fun `unattended mode requires explicit wallet and risk prerequisites`() {
        val policy = WalletAccessPolicy()
        policy.onAuthenticationSucceeded()

        assertEquals(
            ModeChangeResult.PREREQUISITES_REQUIRED,
            policy.setUnattendedEnabled(
                true,
                completeUnattendedPrerequisites().copy(dailyLossCapLamports = 0),
            ),
        )
        assertEquals(WalletAccessMode.SECURE_SESSION, policy.mode)
    }

    @Test
    fun `unattended mode permits background signing after process restart`() {
        val policy = WalletAccessPolicy()
        policy.onAuthenticationSucceeded()
        policy.setUnattendedEnabled(true, completeUnattendedPrerequisites())

        policy.onProcessStarted()

        assertTrue(policy.canSign(isForeground = false))
        assertFalse(policy.hasCurrentAuthentication)
    }

    @Test
    fun `changing back to secure session also requires current authentication`() {
        val policy = WalletAccessPolicy(initialMode = WalletAccessMode.UNATTENDED)

        assertEquals(ModeChangeResult.AUTHENTICATION_REQUIRED, policy.setUnattendedEnabled(false))

        policy.onAuthenticationSucceeded()
        assertEquals(ModeChangeResult.CHANGED, policy.setUnattendedEnabled(false))
        assertEquals(WalletAccessMode.SECURE_SESSION, policy.mode)
        assertFalse(policy.canSign(isForeground = true))
    }

    private fun completeUnattendedPrerequisites() =
        UnattendedPrerequisites(
            dedicatedLowBalanceWalletConfirmed = true,
            riskAcknowledged = true,
            totalExposureCapLamports = 100_000_000,
            dailyLossCapLamports = 10_000_000,
        )
}
