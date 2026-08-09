package com.finnvek.startex.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class JupiterTokensClientTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z").toEpochMilli()

    @Test
    fun `token search returns normalized critical enrichment fields`() =
        runTest {
            val transport =
                TokenRecordingTransport(
                    HttpResponse(200, fixture("jupiter/token-success.json"), emptyMap()),
                )
            val client =
                OkHttpJupiterTokensProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Success)
            val snapshot = (result as ProviderResult.Success).value
            assertEquals(420, snapshot.holderCount)
            assertEquals(BigDecimal("19.5"), snapshot.audit.topHoldersPercentage)
            assertEquals(74, snapshot.stats5m.traderCount)
            assertEquals(
                "https://api.jup.ag/tokens/v2/search",
                transport.request.url
                    .newBuilder()
                    .query(null)
                    .build()
                    .toString(),
            )
            assertEquals("Mint333333333333333333333333333333333333333", transport.request.url.queryParameter("query"))
            assertEquals("fixture-key", transport.request.header("x-api-key"))
        }

    @Test
    fun `missing audit data fails closed`() =
        runTest {
            val client =
                OkHttpJupiterTokensProvider(
                    transport =
                        TokenRecordingTransport(
                            HttpResponse(200, fixture("jupiter/token-missing-audit.json"), emptyMap()),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertEquals("audit", (result as ProviderResult.Failure).error.invalidField)
        }

    @Test
    fun `missing suspicious-token flag fails closed`() =
        runTest {
            val missingFlag =
                fixture("jupiter/token-success.json")
                    .replace("      \"isSus\": false,\n", "")
            val client =
                OkHttpJupiterTokensProvider(
                    transport = TokenRecordingTransport(HttpResponse(200, missingFlag, emptyMap())),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertEquals("isSus", (result as ProviderResult.Failure).error.invalidField)
        }

    @Test
    fun `non-positive USD price fails closed`() =
        runTest {
            val invalidPrice =
                fixture("jupiter/token-success.json")
                    .replace("\"usdPrice\": 0.0042", "\"usdPrice\": 0")
            val client =
                OkHttpJupiterTokensProvider(
                    transport = TokenRecordingTransport(HttpResponse(200, invalidPrice, emptyMap())),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertEquals("usdPrice", (result as ProviderResult.Failure).error.invalidField)
        }

    @Test
    fun `developer balance outside percentage range fails closed`() =
        runTest {
            val invalidAudit =
                fixture("jupiter/token-success.json")
                    .replace("\"devBalancePercentage\": 2.1", "\"devBalancePercentage\": -1")
            val client =
                OkHttpJupiterTokensProvider(
                    transport = TokenRecordingTransport(HttpResponse(200, invalidAudit, emptyMap())),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertEquals(
                "audit.devBalancePercentage",
                (result as ProviderResult.Failure).error.invalidField,
            )
        }

    @Test
    fun `stale token data fails closed`() =
        runTest {
            val stale =
                fixture("jupiter/token-success.json")
                    .replace("2026-08-09T11:59:30Z", "2026-08-09T11:00:00Z")
            val client =
                OkHttpJupiterTokensProvider(
                    transport = TokenRecordingTransport(HttpResponse(200, stale, emptyMap())),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                    maximumAgeMillis = 120_000,
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertTrue((result as ProviderResult.Failure).error is ProviderError.StaleData)
        }

    @Test
    fun `token rate limit remains typed`() =
        runTest {
            val client =
                OkHttpJupiterTokensProvider(
                    transport =
                        TokenRecordingTransport(
                            HttpResponse(429, "{}", mapOf("Retry-After" to "4")),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    clock = { now },
                )

            val result = client.tokenSnapshot("Mint333333333333333333333333333333333333333")

            assertTrue(result is ProviderResult.Failure)
            assertEquals(4_000L, (result as ProviderResult.Failure).error.retryAfterMillis)
        }
}

private class TokenRecordingTransport(
    private val response: HttpResponse,
) : HttpTransport {
    lateinit var request: Request

    override suspend fun execute(request: Request): HttpResponse {
        this.request = request
        return response
    }
}
