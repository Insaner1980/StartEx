package com.finnvek.startex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

class AmountsTest {
    @Test
    fun `converts SOL to lamports without floating point rounding`() {
        assertEquals(Lamports.of(1_234_567_890), Lamports.fromSol(BigDecimal("1.234567890")))
        assertEquals(BigDecimal("1.234567890"), Lamports.of(1_234_567_890).toSol())
    }

    @Test
    fun `rejects fractional lamports and negative balances`() {
        assertThrows(ArithmeticException::class.java) {
            Lamports.fromSol(BigDecimal("0.0000000001"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Lamports.of(-1)
        }
    }

    @Test
    fun `converts token display amount to exact atomic units`() {
        val amount = TokenAmount.fromDisplay(BigDecimal("12.345678"), decimals = 6)

        assertEquals("12345678", amount.atomicUnits.toString())
        assertEquals(BigDecimal("12.345678"), amount.toDisplay())
    }

    @Test
    fun `rejects token precision that cannot be represented`() {
        assertThrows(ArithmeticException::class.java) {
            TokenAmount.fromDisplay(BigDecimal("0.0000001"), decimals = 6)
        }
    }

    @Test
    fun `exact integer conversions preserve bounds and reject overflow`() {
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).toLongExactCompat())
        assertEquals(Int.MIN_VALUE, BigInteger.valueOf(Int.MIN_VALUE.toLong()).toIntExactCompat())
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toLongExactCompat()
        }
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Int.MIN_VALUE.toLong()).subtract(BigInteger.ONE).toIntExactCompat()
        }
    }

    @Test
    fun `fiat conversion reports a stale rate instead of using it`() {
        val rate =
            SolEurRate(
                eurPerSol = BigDecimal("125.50"),
                observedAt = Instant.parse("2026-08-09T10:00:00Z"),
            )

        val fresh =
            rate.convert(
                amount = Lamports.fromSol(BigDecimal("0.02")),
                now = Instant.parse("2026-08-09T10:04:59Z"),
                maximumAge = Duration.ofMinutes(5),
            )
        val stale =
            rate.convert(
                amount = Lamports.fromSol(BigDecimal("0.02")),
                now = Instant.parse("2026-08-09T10:05:01Z"),
                maximumAge = Duration.ofMinutes(5),
            )

        assertEquals(FiatConversion.Available(EurAmount.of(BigDecimal("2.51000000000"))), fresh)
        assertEquals(FiatConversion.Stale, stale)
    }
}
