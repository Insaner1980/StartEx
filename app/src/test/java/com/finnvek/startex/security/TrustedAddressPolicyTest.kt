package com.finnvek.startex.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedAddressPolicyTest {
    private val validator =
        WalletAddressValidator { candidate ->
            candidate.takeIf { it == "valid-address" }?.let { "canonical-address" }
        }
    private val policy = TrustedAddressPolicy(validator)

    @Test
    fun `requires current authentication before validating an address change`() {
        val result =
            policy.validateMutation(
                label = "Treasury",
                address = "valid-address",
                hasCurrentAuthentication = false,
            )

        assertEquals(
            TrustedAddressMutation.Rejected(TrustedAddressRejection.AUTHENTICATION_REQUIRED),
            result,
        )
    }

    @Test
    fun `rejects an address the Solana validator cannot normalize`() {
        val result =
            policy.validateMutation(
                label = "Treasury",
                address = "not-an-address",
                hasCurrentAuthentication = true,
            )

        assertEquals(
            TrustedAddressMutation.Rejected(TrustedAddressRejection.INVALID_ADDRESS),
            result,
        )
    }

    @Test
    fun `returns a normalized trusted address after authorization`() {
        val result =
            policy.validateMutation(
                label = "  Treasury  ",
                address = " valid-address ",
                hasCurrentAuthentication = true,
            )

        assertTrue(result is TrustedAddressMutation.Allowed)
        assertEquals(
            TrustedAddress("Treasury", "canonical-address"),
            (result as TrustedAddressMutation.Allowed).address,
        )
    }

    @Test
    fun `locked address change requires a separate deliberate confirmation`() {
        val existing = TrustedAddress("Treasury", "canonical-address", isLocked = true)

        val result =
            policy.validateMutation(
                label = "New treasury",
                address = "valid-address",
                hasCurrentAuthentication = true,
                existing = existing,
                confirmedLockedChange = false,
            )

        assertEquals(
            TrustedAddressMutation.Rejected(TrustedAddressRejection.LOCKED_CHANGE_CONFIRMATION_REQUIRED),
            result,
        )
    }

    @Test
    fun `first withdrawal confirms final address characters after authentication`() {
        assertEquals(
            TrustedWithdrawalAuthorization.AUTHENTICATION_REQUIRED,
            policy.authorizeFirstWithdrawal(
                address = "1111111111111111111111111111AbCd",
                confirmedFinalCharacters = "AbCd".toCharArray(),
                hasCurrentAuthentication = false,
            ),
        )
        assertEquals(
            TrustedWithdrawalAuthorization.FINAL_CHARACTERS_MISMATCH,
            policy.authorizeFirstWithdrawal(
                address = "1111111111111111111111111111AbCd",
                confirmedFinalCharacters = "abcd".toCharArray(),
                hasCurrentAuthentication = true,
            ),
        )
        assertEquals(
            TrustedWithdrawalAuthorization.ALLOWED,
            policy.authorizeFirstWithdrawal(
                address = "1111111111111111111111111111AbCd",
                confirmedFinalCharacters = "AbCd".toCharArray(),
                hasCurrentAuthentication = true,
            ),
        )
    }
}
