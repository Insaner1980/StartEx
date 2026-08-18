package com.finnvek.startex.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JupiterSwapClientTest {
    private val credentials = ApiKeySource { "fixture-key" }

    @Test
    fun `order parser rejects a response missing a critical mint`() {
        val result =
            JupiterJson.parseOrder(
                fixture("jupiter/order-missing-output.json"),
                transactionRequired = true,
            )

        assertTrue(result is ProviderResult.Failure)
        assertEquals(
            "outputMint",
            (result as ProviderResult.Failure).error.invalidField,
        )
    }

    @Test
    fun `order request uses swap v2 and api key header`() =
        runTest {
            val transport =
                RecordingTransport(
                    HttpResponse(200, fixture("jupiter/order-success.json"), emptyMap()),
                )
            val client =
                OkHttpJupiterSwapProvider(
                    transport = transport,
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result =
                client.order(
                    SwapOrderRequest(
                        inputMint = "So11111111111111111111111111111111111111112",
                        outputMint = "TokenMint11111111111111111111111111111111111",
                        amountAtomic = 10_000_000,
                        taker = "Wallet11111111111111111111111111111111111111",
                        maximumSlippageBps = 300,
                        maximumPriorityFeeLamports = 20_000,
                        maximumTransactionCostLamports = 20_000,
                    ),
                )

            assertTrue(result is ProviderResult.Success)
            assertEquals("2500000", (result as ProviderResult.Success).value.outAmountAtomic)
            assertEquals(
                "https://api.jup.ag/swap/v2/order",
                transport.request.url
                    .newBuilder()
                    .query(null)
                    .build()
                    .toString(),
            )
            assertEquals("fixture-key", transport.request.header("x-api-key"))
            assertEquals("10000000", transport.request.url.queryParameter("amount"))
            assertEquals("Wallet11111111111111111111111111111111111111", transport.request.url.queryParameter("taker"))
            assertEquals("300", transport.request.url.queryParameter("slippageBps"))
            assertEquals("20000", transport.request.url.queryParameter("priorityFeeLamports"))
            assertEquals("maxCap", transport.request.url.queryParameter("broadcastFeeType"))
        }

    @Test
    fun `quote-only order accepts a missing transaction`() =
        runTest {
            val quoteOnly = fixture("jupiter/order-success.json").replace("\"AQIDBA==\"", "null")
            val client =
                OkHttpJupiterSwapProvider(
                    transport = RecordingTransport(HttpResponse(200, quoteOnly, emptyMap())),
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result =
                client.order(
                    SwapOrderRequest(
                        inputMint = "So11111111111111111111111111111111111111112",
                        outputMint = "TokenMint11111111111111111111111111111111111",
                        amountAtomic = 10_000_000,
                    ),
                )

            assertTrue(result is ProviderResult.Success)
            assertNull((result as ProviderResult.Success).value.unsignedTransactionBase64)
        }

    // CPD-OFF
    @Test
    fun `response above the requested slippage cap fails closed`() =
        runTest {
            val response = fixture("jupiter/order-success.json").replace("\"slippageBps\": 40", "\"slippageBps\": 301")
            val client =
                OkHttpJupiterSwapProvider(
                    transport = RecordingTransport(HttpResponse(200, response, emptyMap())),
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result =
                client.order(
                    SwapOrderRequest(
                        inputMint = "So11111111111111111111111111111111111111112",
                        outputMint = "TokenMint11111111111111111111111111111111111",
                        amountAtomic = 10_000_000,
                    ),
                )

            assertTrue(result is ProviderResult.Failure)
            assertEquals("slippageBps", (result as ProviderResult.Failure).error.invalidField)
        }

    @Test
    fun `response above the requested total fee cap fails closed`() =
        runTest {
            val response = fixture("jupiter/order-success.json").replace("\"feeBps\": 50", "\"feeBps\": 501")
            val client =
                OkHttpJupiterSwapProvider(
                    transport = RecordingTransport(HttpResponse(200, response, emptyMap())),
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result =
                client.order(
                    SwapOrderRequest(
                        inputMint = "So11111111111111111111111111111111111111112",
                        outputMint = "TokenMint11111111111111111111111111111111111",
                        amountAtomic = 10_000_000,
                        maximumTotalFeeBps = 500,
                    ),
                )

            assertTrue(result is ProviderResult.Failure)
            assertEquals("feeBps", (result as ProviderResult.Failure).error.invalidField)
        }

    // CPD-ON
    @Test
    fun `execute serializes the signed transaction and request id`() =
        runTest {
            val transport =
                RecordingTransport(
                    HttpResponse(200, fixture("jupiter/execute-success.json"), emptyMap()),
                )
            val client =
                OkHttpJupiterSwapProvider(
                    transport = transport,
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result =
                client.execute(
                    SwapExecuteRequest(
                        signedTransactionBase64 = "AQIDBA==",
                        requestId = "order-request-1",
                        lastValidBlockHeight = 420_000_100,
                    ),
                )

            assertTrue(result is ProviderResult.Success)
            assertEquals("POST", transport.request.method)
            assertTrue(checkNotNull(transport.requestBody).contains("\"signedTransaction\":\"AQIDBA==\""))
            assertTrue(checkNotNull(transport.requestBody).contains("\"requestId\":\"order-request-1\""))
            assertTrue(checkNotNull(transport.requestBody).contains("\"lastValidBlockHeight\":420000100"))
        }

    @Test
    fun `failed execute response fails closed`() =
        runTest {
            val client =
                OkHttpJupiterSwapProvider(
                    transport =
                        RecordingTransport(
                            HttpResponse(200, fixture("jupiter/execute-failed.json"), emptyMap()),
                        ),
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result = client.execute(SwapExecuteRequest("AQIDBA==", "order-request-1"))

            assertTrue(result is ProviderResult.Failure)
            assertEquals(-1000, (result as ProviderResult.Failure).error.providerCode)
        }

    @Test
    fun `execute network failure is an uncertain submission`() =
        runTest {
            val client =
                OkHttpJupiterSwapProvider(
                    transport = HttpTransport { throw IOException("fixture disconnect") },
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result = client.execute(SwapExecuteRequest("AQIDBA==", "order-request-1"))

            assertTrue(result is ProviderResult.Failure)
            assertTrue((result as ProviderResult.Failure).error is ProviderError.SubmissionUncertain)
        }

    @Test
    fun `rate limit is typed and honors retry-after`() =
        runTest {
            val client =
                OkHttpJupiterSwapProvider(
                    transport = RecordingTransport(HttpResponse(429, "{}", mapOf("retry-after" to "3"))),
                    apiKeySource = credentials,
                    requestGate = RequestGate.None,
                )

            val result = client.order(SwapOrderRequest("in", "out", 1))

            assertTrue(result is ProviderResult.Failure)
            assertEquals(3_000L, (result as ProviderResult.Failure).error.retryAfterMillis)
        }
}

private class RecordingTransport(
    private val response: HttpResponse,
) : HttpTransport {
    lateinit var request: Request
    var requestBody: String? = null

    override suspend fun execute(request: Request): HttpResponse {
        this.request = request
        requestBody =
            request.body?.let { body ->
                okio.Buffer().use { buffer ->
                    body.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        return response
    }
}
