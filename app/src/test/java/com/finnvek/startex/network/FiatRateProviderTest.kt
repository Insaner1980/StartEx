package com.finnvek.startex.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class FiatRateProviderTest {
    private val now = Instant.parse("2026-08-09T12:00:30Z").toEpochMilli()

    @Test
    fun `kraken public candle produces a fresh approximate SOL EUR rate`() =
        runTest {
            val transport =
                FiatRecordingTransport(
                    HttpResponse(200, fixture("kraken/sol-eur.json"), emptyMap()),
                )
            val provider =
                KrakenFiatRateProvider(
                    transport = transport,
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = provider.solEurRate()

            assertTrue(result is ProviderResult.Success)
            val rate = (result as ProviderResult.Success).value
            assertEquals(0, BigDecimal("142.50000").compareTo(rate.eurPerSol))
            assertEquals(Instant.parse("2026-08-09T12:00:00Z").toEpochMilli(), rate.observedAtMillis)
            assertEquals(
                "https://api.kraken.com/0/public/OHLC",
                transport.request.url
                    .newBuilder()
                    .query(null)
                    .build()
                    .toString(),
            )
            assertEquals("SOLEUR", transport.request.url.queryParameter("pair"))
            assertEquals("1", transport.request.url.queryParameter("interval"))
        }

    @Test
    fun `stale fiat candle fails closed for EUR sizing`() =
        runTest {
            val stale =
                fixture("kraken/sol-eur.json")
                    .replace("1786276740", "1786276140")
                    .replace("1786276800", "1786276200")
            val provider =
                KrakenFiatRateProvider(
                    transport = FiatRecordingTransport(HttpResponse(200, stale, emptyMap())),
                    requestGate = RequestGate.None,
                    clock = { now },
                    maximumAgeMillis = 120_000,
                )

            val result = provider.solEurRate()

            assertTrue(result is ProviderResult.Failure)
            assertTrue((result as ProviderResult.Failure).error is ProviderError.StaleData)
        }

    @Test
    fun `malformed fiat candle is unavailable instead of guessed`() =
        runTest {
            val provider =
                KrakenFiatRateProvider(
                    transport =
                        FiatRecordingTransport(
                            HttpResponse(200, fixture("kraken/sol-eur-missing-close.json"), emptyMap()),
                        ),
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = provider.solEurRate()

            assertTrue(result is ProviderResult.Failure)
            assertEquals("candle.close", (result as ProviderResult.Failure).error.invalidField)
        }
}

private class FiatRecordingTransport(
    private val response: HttpResponse,
) : HttpTransport {
    lateinit var request: Request

    override suspend fun execute(request: Request): HttpResponse {
        this.request = request
        return response
    }
}
