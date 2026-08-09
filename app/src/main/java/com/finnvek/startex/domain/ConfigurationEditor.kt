package com.finnvek.startex.domain

import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import java.math.BigDecimal

enum class ConfigurationField {
    MAXIMUM_TRADE_SOL,
    MAXIMUM_EXPOSURE_SOL,
    MAXIMUM_OPEN_POSITIONS,
    MAXIMUM_DAILY_LOSS_SOL,
    FEE_RESERVE_SOL,
    MAXIMUM_SLIPPAGE_PERCENT,
    MAXIMUM_HOLD_MINUTES,
    MINIMUM_SCORE,
    TAKE_PROFIT_PERCENT,
    HARD_STOP_PERCENT,
    TRAILING_ACTIVATION_PERCENT,
    TRAILING_DISTANCE_PERCENT,
    OBSERVATION_SECONDS,
    VERSION,
    CREATED_AT,
}

enum class ConfigurationErrorCode {
    REQUIRED,
    INVALID_FORMAT,
    TOO_PRECISE,
    OUT_OF_RANGE,
    INCONSISTENT,
    OVERFLOW,
}

data class ConfigurationFieldError(
    val field: ConfigurationField,
    val code: ConfigurationErrorCode,
    val relatedField: ConfigurationField? = null,
)

sealed interface ConfigurationEditResult<out T> {
    data class Updated<T>(
        val entity: T,
    ) : ConfigurationEditResult<T>

    data class Invalid(
        val error: ConfigurationFieldError,
    ) : ConfigurationEditResult<Nothing>
}

data class RiskConfigurationInput(
    val maximumTradeSol: String,
    val maximumExposureSol: String,
    val maximumOpenPositions: String,
    val maximumDailyLossSol: String,
    val feeReserveSol: String,
    val maximumSlippagePercent: String,
    val maximumHoldMinutes: String,
)

data class StrategyConfigurationInput(
    val minimumScore: String,
    val takeProfitPercent: String,
    val hardStopPercent: String,
    val trailingActivationPercent: String,
    val trailingDistancePercent: String,
    val observationSeconds: String,
)

/**
 * Conservative hot-wallet editing ranges. Amounts use SOL, percentages use human percent values,
 * and time inputs use whole minutes or seconds. These ranges intentionally exclude unlimited or
 * unusually large values; callers can display them directly without duplicating validation policy.
 */
object ConfigurationSafeRanges {
    val minimumTradeSol: BigDecimal = BigDecimal("0.001")
    val maximumTradeSol: BigDecimal = BigDecimal("1")
    val minimumExposureSol: BigDecimal = BigDecimal("0.001")
    val maximumExposureSol: BigDecimal = BigDecimal("2")
    val minimumDailyLossSol: BigDecimal = BigDecimal("0.001")
    val maximumDailyLossSol: BigDecimal = BigDecimal("2")
    val minimumFeeReserveSol: BigDecimal = BigDecimal("0.001")
    val maximumFeeReserveSol: BigDecimal = BigDecimal("1")
    val minimumSlippagePercent: BigDecimal = BigDecimal("0.1")
    val maximumSlippagePercent: BigDecimal = BigDecimal("5")
    val minimumTakeProfitPercent: BigDecimal = BigDecimal("1")
    val maximumTakeProfitPercent: BigDecimal = BigDecimal("100")
    val minimumHardStopPercent: BigDecimal = BigDecimal("0.5")
    val maximumHardStopPercent: BigDecimal = BigDecimal("50")
    val minimumTrailingActivationPercent: BigDecimal = BigDecimal("1")
    val maximumTrailingActivationPercent: BigDecimal = BigDecimal("100")
    val minimumTrailingDistancePercent: BigDecimal = BigDecimal("0.5")
    val maximumTrailingDistancePercent: BigDecimal = BigDecimal("50")
    const val MINIMUM_OPEN_POSITIONS = 1
    const val MAXIMUM_OPEN_POSITIONS = 2
    const val MINIMUM_HOLD_MINUTES = 1
    const val MAXIMUM_HOLD_MINUTES = 60
    const val MINIMUM_SCORE = 50
    const val MAXIMUM_SCORE = 100
    const val MINIMUM_OBSERVATION_SECONDS = 15
    const val MAXIMUM_OBSERVATION_SECONDS = 300
}

object ConfigurationEditor {
    fun editRisk(
        previous: RiskConfigEntity,
        input: RiskConfigurationInput,
        nowMillis: Long,
    ): ConfigurationEditResult<RiskConfigEntity> =
        validatedEdit {
            val trade =
                parseSol(
                    input.maximumTradeSol,
                    ConfigurationField.MAXIMUM_TRADE_SOL,
                    ConfigurationSafeRanges.minimumTradeSol,
                    ConfigurationSafeRanges.maximumTradeSol,
                )
            val exposure =
                parseSol(
                    input.maximumExposureSol,
                    ConfigurationField.MAXIMUM_EXPOSURE_SOL,
                    ConfigurationSafeRanges.minimumExposureSol,
                    ConfigurationSafeRanges.maximumExposureSol,
                )
            val openPositions =
                parseWholeNumber(
                    input.maximumOpenPositions,
                    ConfigurationField.MAXIMUM_OPEN_POSITIONS,
                    ConfigurationSafeRanges.MINIMUM_OPEN_POSITIONS,
                    ConfigurationSafeRanges.MAXIMUM_OPEN_POSITIONS,
                )
            val dailyLoss =
                parseSol(
                    input.maximumDailyLossSol,
                    ConfigurationField.MAXIMUM_DAILY_LOSS_SOL,
                    ConfigurationSafeRanges.minimumDailyLossSol,
                    ConfigurationSafeRanges.maximumDailyLossSol,
                )
            val feeReserve =
                parseSol(
                    input.feeReserveSol,
                    ConfigurationField.FEE_RESERVE_SOL,
                    ConfigurationSafeRanges.minimumFeeReserveSol,
                    ConfigurationSafeRanges.maximumFeeReserveSol,
                )
            val slippage =
                parsePercentBps(
                    input.maximumSlippagePercent,
                    ConfigurationField.MAXIMUM_SLIPPAGE_PERCENT,
                    ConfigurationSafeRanges.minimumSlippagePercent,
                    ConfigurationSafeRanges.maximumSlippagePercent,
                )
            val holdMinutes =
                parseWholeNumber(
                    input.maximumHoldMinutes,
                    ConfigurationField.MAXIMUM_HOLD_MINUTES,
                    ConfigurationSafeRanges.MINIMUM_HOLD_MINUTES,
                    ConfigurationSafeRanges.MAXIMUM_HOLD_MINUTES,
                )

            requireAtMost(
                trade,
                exposure,
                ConfigurationField.MAXIMUM_TRADE_SOL,
                ConfigurationField.MAXIMUM_EXPOSURE_SOL,
            )
            previous.copy(
                version = nextVersion(previous.version),
                maximumTradeLamports = toLamports(trade, ConfigurationField.MAXIMUM_TRADE_SOL),
                maximumExposureLamports = toLamports(exposure, ConfigurationField.MAXIMUM_EXPOSURE_SOL),
                maximumOpenPositions = openPositions,
                maximumDailyLossLamports = toLamports(dailyLoss, ConfigurationField.MAXIMUM_DAILY_LOSS_SOL),
                minimumWalletReserveLamports = toLamports(feeReserve, ConfigurationField.FEE_RESERVE_SOL),
                maximumSlippageBps = slippage,
                maximumHoldingMillis = minutesToMillis(holdMinutes, ConfigurationField.MAXIMUM_HOLD_MINUTES),
                createdAtMillis = validTimestamp(nowMillis),
            )
        }

    fun editStrategy(
        previous: StrategyConfigEntity,
        input: StrategyConfigurationInput,
        nowMillis: Long,
    ): ConfigurationEditResult<StrategyConfigEntity> =
        validatedEdit {
            val minimumScore =
                parseWholeNumber(
                    input.minimumScore,
                    ConfigurationField.MINIMUM_SCORE,
                    ConfigurationSafeRanges.MINIMUM_SCORE,
                    ConfigurationSafeRanges.MAXIMUM_SCORE,
                )
            val takeProfit =
                parsePercentBps(
                    input.takeProfitPercent,
                    ConfigurationField.TAKE_PROFIT_PERCENT,
                    ConfigurationSafeRanges.minimumTakeProfitPercent,
                    ConfigurationSafeRanges.maximumTakeProfitPercent,
                )
            val hardStop =
                parsePercentBps(
                    input.hardStopPercent,
                    ConfigurationField.HARD_STOP_PERCENT,
                    ConfigurationSafeRanges.minimumHardStopPercent,
                    ConfigurationSafeRanges.maximumHardStopPercent,
                )
            val trailingActivation =
                parsePercentBps(
                    input.trailingActivationPercent,
                    ConfigurationField.TRAILING_ACTIVATION_PERCENT,
                    ConfigurationSafeRanges.minimumTrailingActivationPercent,
                    ConfigurationSafeRanges.maximumTrailingActivationPercent,
                )
            val trailingDistance =
                parsePercentBps(
                    input.trailingDistancePercent,
                    ConfigurationField.TRAILING_DISTANCE_PERCENT,
                    ConfigurationSafeRanges.minimumTrailingDistancePercent,
                    ConfigurationSafeRanges.maximumTrailingDistancePercent,
                )
            val observationSeconds =
                parseWholeNumber(
                    input.observationSeconds,
                    ConfigurationField.OBSERVATION_SECONDS,
                    ConfigurationSafeRanges.MINIMUM_OBSERVATION_SECONDS,
                    ConfigurationSafeRanges.MAXIMUM_OBSERVATION_SECONDS,
                )

            requireAtMost(
                hardStop,
                takeProfit,
                ConfigurationField.HARD_STOP_PERCENT,
                ConfigurationField.TAKE_PROFIT_PERCENT,
            )
            requireAtMost(
                trailingActivation,
                takeProfit,
                ConfigurationField.TRAILING_ACTIVATION_PERCENT,
                ConfigurationField.TAKE_PROFIT_PERCENT,
            )
            requireStrictlyLess(
                trailingDistance,
                trailingActivation,
                ConfigurationField.TRAILING_DISTANCE_PERCENT,
                ConfigurationField.TRAILING_ACTIVATION_PERCENT,
            )
            val observationMillis = secondsToMillis(observationSeconds, ConfigurationField.OBSERVATION_SECONDS)
            if (observationMillis > previous.maximumCandidateAgeMillis) {
                invalid(ConfigurationField.OBSERVATION_SECONDS, ConfigurationErrorCode.INCONSISTENT)
            }
            previous.copy(
                version = nextVersion(previous.version),
                minimumEntryScore = minimumScore,
                takeProfitBps = takeProfit,
                hardStopLossBps = hardStop,
                trailingActivationBps = trailingActivation,
                trailingDistanceBps = trailingDistance,
                minimumObservationMillis = observationMillis,
                createdAtMillis = validTimestamp(nowMillis),
            )
        }
}

private inline fun <T> validatedEdit(block: () -> T): ConfigurationEditResult<T> =
    try {
        ConfigurationEditResult.Updated(block())
    } catch (error: InvalidConfigurationInput) {
        ConfigurationEditResult.Invalid(error.error)
    }

private fun parseSol(
    raw: String,
    field: ConfigurationField,
    minimum: BigDecimal,
    maximum: BigDecimal,
): BigDecimal =
    parseDecimal(raw, field, SOL_DECIMALS).also { value ->
        requireRange(value, minimum, maximum, field)
    }

private fun parsePercentBps(
    raw: String,
    field: ConfigurationField,
    minimum: BigDecimal,
    maximum: BigDecimal,
): Int {
    val value = parseDecimal(raw, field, PERCENT_DECIMALS)
    requireRange(value, minimum, maximum, field)
    return try {
        value.movePointRight(PERCENT_DECIMALS).toBigIntegerExact().toIntExactCompat()
    } catch (_: ArithmeticException) {
        invalid(field, ConfigurationErrorCode.OVERFLOW)
    }
}

private fun parseDecimal(
    raw: String,
    field: ConfigurationField,
    maximumDecimals: Int,
): BigDecimal {
    if (raw.isBlank()) invalid(field, ConfigurationErrorCode.REQUIRED)
    if (raw.length > MAXIMUM_INPUT_LENGTH) invalid(field, ConfigurationErrorCode.OVERFLOW)
    if (raw != raw.trim() || !DECIMAL_PATTERN.matches(raw)) {
        invalid(field, ConfigurationErrorCode.INVALID_FORMAT)
    }
    val value = raw.toBigDecimalOrNull() ?: invalid(field, ConfigurationErrorCode.OVERFLOW)
    val effectiveScale = value.stripTrailingZeros().scale().coerceAtLeast(0)
    if (effectiveScale > maximumDecimals) invalid(field, ConfigurationErrorCode.TOO_PRECISE)
    return value
}

private fun parseWholeNumber(
    raw: String,
    field: ConfigurationField,
    minimum: Int,
    maximum: Int,
): Int {
    if (raw.isBlank()) invalid(field, ConfigurationErrorCode.REQUIRED)
    if (raw.length > MAXIMUM_INPUT_LENGTH) invalid(field, ConfigurationErrorCode.OVERFLOW)
    if (raw != raw.trim() || !WHOLE_NUMBER_PATTERN.matches(raw)) {
        invalid(field, ConfigurationErrorCode.INVALID_FORMAT)
    }
    val value =
        try {
            raw.toBigInteger().toIntExactCompat()
        } catch (_: ArithmeticException) {
            invalid(field, ConfigurationErrorCode.OVERFLOW)
        }
    if (value !in minimum..maximum) invalid(field, ConfigurationErrorCode.OUT_OF_RANGE)
    return value
}

private fun requireRange(
    value: BigDecimal,
    minimum: BigDecimal,
    maximum: BigDecimal,
    field: ConfigurationField,
) {
    if (value < minimum || value > maximum) invalid(field, ConfigurationErrorCode.OUT_OF_RANGE)
}

private fun <T : Comparable<T>> requireAtMost(
    value: T,
    maximum: T,
    field: ConfigurationField,
    relatedField: ConfigurationField,
) {
    if (value > maximum) invalid(field, ConfigurationErrorCode.INCONSISTENT, relatedField)
}

private fun <T : Comparable<T>> requireStrictlyLess(
    value: T,
    maximum: T,
    field: ConfigurationField,
    relatedField: ConfigurationField,
) {
    if (value >= maximum) invalid(field, ConfigurationErrorCode.INCONSISTENT, relatedField)
}

private fun toLamports(
    value: BigDecimal,
    field: ConfigurationField,
): Long =
    try {
        value.movePointRight(SOL_DECIMALS).toBigIntegerExact().toLongExactCompat()
    } catch (_: ArithmeticException) {
        invalid(field, ConfigurationErrorCode.OVERFLOW)
    }

private fun minutesToMillis(
    value: Int,
    field: ConfigurationField,
): Long =
    try {
        Math.multiplyExact(value.toLong(), MILLIS_PER_MINUTE)
    } catch (_: ArithmeticException) {
        invalid(field, ConfigurationErrorCode.OVERFLOW)
    }

private fun secondsToMillis(
    value: Int,
    field: ConfigurationField,
): Long =
    try {
        Math.multiplyExact(value.toLong(), MILLIS_PER_SECOND)
    } catch (_: ArithmeticException) {
        invalid(field, ConfigurationErrorCode.OVERFLOW)
    }

private fun nextVersion(previous: Int): Int =
    try {
        Math.addExact(previous, 1)
    } catch (_: ArithmeticException) {
        invalid(ConfigurationField.VERSION, ConfigurationErrorCode.OVERFLOW)
    }

private fun validTimestamp(nowMillis: Long): Long {
    if (nowMillis < 0) invalid(ConfigurationField.CREATED_AT, ConfigurationErrorCode.OUT_OF_RANGE)
    return nowMillis
}

private fun invalid(
    field: ConfigurationField,
    code: ConfigurationErrorCode,
    relatedField: ConfigurationField? = null,
): Nothing = throw InvalidConfigurationInput(ConfigurationFieldError(field, code, relatedField))

private class InvalidConfigurationInput(
    val error: ConfigurationFieldError,
) : RuntimeException()

private val DECIMAL_PATTERN = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
private val WHOLE_NUMBER_PATTERN = Regex("(?:0|[1-9][0-9]*)")
private const val SOL_DECIMALS = 9
private const val PERCENT_DECIMALS = 2
private const val MAXIMUM_INPUT_LENGTH = 64
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
