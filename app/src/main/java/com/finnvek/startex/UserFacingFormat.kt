package com.finnvek.startex

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatUserNumber(
    value: BigDecimal,
    maximumFractionDigits: Int,
    locale: Locale,
): String =
    NumberFormat
        .getNumberInstance(locale)
        .apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            this.maximumFractionDigits = maximumFractionDigits
        }.format(value)

internal fun formatUserTimestamp(
    timestampMillis: Long,
    locale: Locale,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(timestampMillis))

internal fun parseSolAmount(value: String): BigDecimal? =
    runCatching {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && !(normalized.contains('.') && normalized.contains(',')))
        BigDecimal(normalized.replace(',', '.')).takeIf {
            it > BigDecimal.ZERO && it.stripTrailingZeros().scale() <= SOL_DECIMAL_PLACES
        }
    }.getOrNull()

private const val SOL_DECIMAL_PLACES = 9
