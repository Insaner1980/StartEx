package com.finnvek.startex.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

internal fun BigInteger.toLongExactCompat(): Long {
    if (this < LONG_MIN_BIG_INTEGER || this > LONG_MAX_BIG_INTEGER) {
        throw ArithmeticException("BigInteger does not fit Long")
    }
    return toLong()
}

internal fun BigInteger.toIntExactCompat(): Int {
    if (this < INT_MIN_BIG_INTEGER || this > INT_MAX_BIG_INTEGER) {
        throw ArithmeticException("BigInteger does not fit Int")
    }
    return toInt()
}

@JvmInline
value class Lamports private constructor(
    val value: Long,
) : Comparable<Lamports> {
    fun toSol(): BigDecimal = BigDecimal.valueOf(value, SOL_DECIMALS)

    operator fun plus(other: Lamports): Lamports = of(Math.addExact(value, other.value))

    fun subtract(other: Lamports): Lamports = of(Math.subtractExact(value, other.value))

    override fun compareTo(other: Lamports): Int = value.compareTo(other.value)

    companion object {
        const val SOL_DECIMALS = 9
        val ZERO: Lamports = Lamports(0)

        fun of(value: Long): Lamports {
            require(value >= 0) { "Lamports cannot be negative" }
            return Lamports(value)
        }

        fun fromSol(value: BigDecimal): Lamports = of(value.movePointRight(SOL_DECIMALS).toBigIntegerExact().toLongExactCompat())
    }
}

private val LONG_MIN_BIG_INTEGER = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_BIG_INTEGER = BigInteger.valueOf(Long.MAX_VALUE)
private val INT_MIN_BIG_INTEGER = BigInteger.valueOf(Int.MIN_VALUE.toLong())
private val INT_MAX_BIG_INTEGER = BigInteger.valueOf(Int.MAX_VALUE.toLong())

data class TokenAmount(
    val atomicUnits: BigInteger,
    val decimals: Int,
) {
    init {
        require(atomicUnits.signum() >= 0) { "Token amount cannot be negative" }
        require(decimals in 0..255) { "Token decimals must fit an unsigned byte" }
    }

    fun toDisplay(): BigDecimal = BigDecimal(atomicUnits, decimals)

    companion object {
        fun ofAtomic(
            atomicUnits: BigInteger,
            decimals: Int,
        ): TokenAmount = TokenAmount(atomicUnits, decimals)

        fun fromDisplay(
            value: BigDecimal,
            decimals: Int,
        ): TokenAmount = ofAtomic(value.movePointRight(decimals).toBigIntegerExact(), decimals)
    }
}

data class EurAmount(
    val value: BigDecimal,
) {
    companion object {
        fun of(value: BigDecimal): EurAmount = EurAmount(value)
    }
}

sealed interface FiatConversion {
    data class Available(
        val amount: EurAmount,
    ) : FiatConversion

    data object Stale : FiatConversion
}

data class SolEurRate(
    val eurPerSol: BigDecimal,
    val observedAt: Instant,
) {
    init {
        require(eurPerSol.signum() > 0) { "EUR rate must be positive" }
    }

    fun convert(
        amount: Lamports,
        now: Instant,
        maximumAge: Duration,
    ): FiatConversion {
        require(!maximumAge.isNegative) { "Maximum age cannot be negative" }
        if (now.isAfter(observedAt.plus(maximumAge))) return FiatConversion.Stale
        return FiatConversion.Available(EurAmount.of(amount.toSol().multiply(eurPerSol)))
    }
}
