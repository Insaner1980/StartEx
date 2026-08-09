package com.finnvek.startex.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class MnemonicBackupChallengeTest {
    private val phrase = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu".toCharArray()

    @Test
    fun `verifies selected words by their one based positions`() {
        MnemonicBackupChallenge.forPositions(phrase, setOf(2, 7, 12)).use { challenge ->
            assertTrue(
                challenge.verify(
                    mapOf(
                        2 to "beta".toCharArray(),
                        7 to "eta".toCharArray(),
                        12 to "mu".toCharArray(),
                    ),
                ),
            )
            assertFalse(
                challenge.verify(
                    mapOf(
                        2 to "beta".toCharArray(),
                        7 to "theta".toCharArray(),
                        12 to "mu".toCharArray(),
                    ),
                ),
            )
        }
    }

    @Test
    fun `random challenge selects three distinct valid positions`() {
        MnemonicBackupChallenge.random(phrase, SecureRandom()).use { challenge ->
            assertEquals(3, challenge.wordNumbers.size)
            assertEquals(3, challenge.wordNumbers.distinct().size)
            assertTrue(challenge.wordNumbers.all { it in 1..12 })
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `cleared challenge cannot verify recovery words`() {
        val challenge = MnemonicBackupChallenge.forPositions(phrase, setOf(1, 2, 3))
        challenge.close()

        challenge.verify(emptyMap())
    }
}
