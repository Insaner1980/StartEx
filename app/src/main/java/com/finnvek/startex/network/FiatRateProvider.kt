package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal

private const val CANDLE_TIME_FIELD = "candle.time"

data class FiatRate(
    val eurPerSol: BigDecimal,
    val observedAtMillis: Long,
    val source: String,
)

fun interface FiatRateProvider {
    suspend fun solEurRate(): ProviderResult<FiatRate>
}

class KrakenFiatRateProvider(
    private val transport: HttpTransport,
    private val requestGate: RequestGate = KrakenPublicRequestGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumAgeMillis: Long = DEFAULT_MAXIMUM_AGE_MILLIS,
) : FiatRateProvider {
    init {
        require(maximumAgeMillis > 0)
    }

    override suspend fun solEurRate(): ProviderResult<FiatRate> {
        val request =
            Request
                .Builder()
                .url(
                    ENDPOINT
                        .newBuilder()
                        .addQueryParameter("pair", "SOLEUR")
                        .addQueryParameter("interval", "1")
                        .build(),
                ).get()
                .build()
        requestGate.awaitTurn()
        val response =
            try {
                transport.execute(request)
            } catch (_: ResponseTooLargeException) {
                return invalidResponse("bodySize")
            } catch (_: IOException) {
                return ProviderResult.Failure(ProviderError.NetworkUnavailable(ProviderId.KRAKEN))
            }
        response.httpError(ProviderId.KRAKEN)?.let { return ProviderResult.Failure(it) }
        return when (val parsed = KrakenFiatJson.parse(response.body)) {
            is ProviderResult.Failure -> parsed
            is ProviderResult.Success -> enforceFreshness(parsed.value)
        }
    }

    private fun enforceFreshness(rate: FiatRate): ProviderResult<FiatRate> {
        val age = clock() - rate.observedAtMillis
        if (age < 0) return invalidResponse(CANDLE_TIME_FIELD)
        return if (age > maximumAgeMillis) {
            ProviderResult.Failure(ProviderError.StaleData(ProviderId.KRAKEN, age))
        } else {
            ProviderResult.Success(rate)
        }
    }

    private fun invalidResponse(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.KRAKEN, field))

    private companion object {
        val ENDPOINT = "https://api.kraken.com/0/public/OHLC".toHttpUrl()
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 120_000L
    }
}

private object KrakenFiatJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ProviderResult<FiatRate> =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val errors = root["error"]?.jsonArray ?: invalidFiat("error")
            if (errors.isNotEmpty()) invalidFiat("error")
            val result = root["result"]?.jsonObject ?: invalidFiat("result")
            val candleSeries =
                result.entries
                    .filter { (name, value) -> name != "last" && value !is JsonNull }
                    .map { it.value.jsonArray }
                    .singleOrNull()
                    ?: invalidFiat("result.pair")
            val candle = candleSeries.lastOrNull()?.jsonArray ?: invalidFiat("candle")
            val observedAtSeconds = candle.longAt(0, CANDLE_TIME_FIELD)
            val close = candle.decimalAt(4, "candle.close")
            if (observedAtSeconds <= 0) invalidFiat(CANDLE_TIME_FIELD)
            if (close.signum() <= 0) invalidFiat("candle.close")
            ProviderResult.Success(
                FiatRate(
                    eurPerSol = close,
                    observedAtMillis = Math.multiplyExact(observedAtSeconds, 1_000),
                    source = "Kraken SOL/EUR 1m OHLC",
                ),
            )
        } catch (error: InvalidFiatField) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.KRAKEN, error.field))
        } catch (_: RuntimeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.KRAKEN, "json"))
        }
}

private class InvalidFiatField(
    val field: String,
) : RuntimeException()

private fun invalidFiat(field: String): Nothing = throw InvalidFiatField(field)

private fun JsonArray.longAt(
    index: Int,
    field: String,
): Long =
    getOrNull(index)
        ?.jsonPrimitive
        ?.let { primitive -> primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() }
        ?: invalidFiat(field)

private fun JsonArray.decimalAt(
    index: Int,
    field: String,
): BigDecimal =
    getOrNull(index)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBigDecimalOrNull()
        ?: invalidFiat(field)
