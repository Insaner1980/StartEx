package com.finnvek.startex.network

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

class HeliusRpcClientTest {
    @Test
    fun `block height uses confirmed commitment`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/block-height-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getBlockHeight()

            assertEquals(499_999L, (result as ProviderResult.Success).value)
            val requestBody = checkNotNull(transport.body)
            assertTrue(requestBody.contains("\"method\":\"getBlockHeight\""))
            assertTrue(requestBody.contains("\"commitment\":\"confirmed\""))
        }

    @Test
    fun `token holdings query both supported token programs with exact atomic balances`() =
        runTest {
            val transport =
                QueueHeliusTransport(
                    fixture("helius/token-holdings-legacy.json"),
                    fixture("helius/token-holdings-2022.json"),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getTokenHoldings("Wallet11111111111111111111111111111111111111")

            assertTrue(result is ProviderResult.Success)
            val holdings = (result as ProviderResult.Success).value
            assertEquals(420_000_011L, holdings.slot)
            assertEquals(2, holdings.holdings.size)
            assertEquals(BigInteger("1234500"), holdings.holdings.first().amountAtomic)
            assertEquals(6, holdings.holdings.first().decimals)
            assertTrue(transport.bodies[0].contains("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
            assertTrue(transport.bodies[1].contains("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"))
            assertTrue(transport.bodies.all { it.contains("\"encoding\":\"jsonParsed\"") })
            assertTrue(transport.bodies.none { it.contains("fixture-key") })
        }

    @Test
    fun `signature history preserves confirmed chain failure facts`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/signatures-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result =
                client.getSignaturesForAddress(
                    address = "Wallet11111111111111111111111111111111111111",
                    limit = 10,
                )

            assertTrue(result is ProviderResult.Success)
            val signatures = (result as ProviderResult.Success).value
            assertEquals(2, signatures.size)
            assertEquals(1_786_233_600_000L, signatures.first().blockTimeMillis)
            assertFalse(signatures.first().failed)
            assertTrue(signatures.last().failed)
            assertEquals("sanitized fixture", signatures.last().memo)
            val requestBody = checkNotNull(transport.body)
            assertTrue(requestBody.contains("\"method\":\"getSignaturesForAddress\""))
            assertTrue(requestBody.contains("\"limit\":10"))
        }

    @Test
    fun `balance request follows Solana JSON RPC contract`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/balance-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getBalance("Wallet11111111111111111111111111111111111111")

            assertTrue(result is ProviderResult.Success)
            assertEquals(123_456_789, (result as ProviderResult.Success).value.lamports)
            assertEquals(420_000_001, result.value.slot)
            assertEquals(
                "https://mainnet.helius-rpc.com/?api-key=fixture-key",
                transport.request.url.toString(),
            )
            assertEquals("fixture-key", transport.request.url.queryParameter("api-key"))
            assertTrue(checkNotNull(transport.body).contains("\"method\":\"getBalance\""))
            assertFalse(checkNotNull(transport.body).contains("fixture-key"))
        }

    @Test
    fun `rpc error is returned as typed failure without remote message`() =
        runTest {
            val client =
                OkHttpHeliusRpcProvider(
                    transport =
                        CapturingHeliusTransport(
                            HttpResponse(200, fixture("helius/rpc-error.json"), emptyMap()),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getBalance("Wallet11111111111111111111111111111111111111")

            assertTrue(result is ProviderResult.Failure)
            assertEquals(-32602, (result as ProviderResult.Failure).error.providerCode)
        }

    @Test
    fun `missing api key fails before any request`() =
        runTest {
            var called = false
            val client =
                OkHttpHeliusRpcProvider(
                    transport =
                        HttpTransport {
                            called = true
                            HttpResponse(500, "", emptyMap())
                        },
                    apiKeySource = ApiKeySource { null },
                    requestGate = RequestGate.None,
                )

            val result = client.getBalance("Wallet11111111111111111111111111111111111111")

            assertTrue(result is ProviderResult.Failure)
            assertEquals(ProviderId.HELIUS, (result as ProviderResult.Failure).error.provider)
            assertFalse(called)
        }

    @Test
    fun `simulation sends the exact transaction with signature verification disabled`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/simulation-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.simulateTransaction("AAECAwQ=")

            assertTrue(result is ProviderResult.Success)
            val simulation = (result as ProviderResult.Success).value as RpcSimulation.Succeeded
            assertEquals(420_000_002, simulation.slot)
            assertEquals(150L, simulation.unitsConsumed)
            val requestBody = checkNotNull(transport.body)
            assertTrue(requestBody.contains("\"method\":\"simulateTransaction\""))
            assertTrue(requestBody.contains("\"AAECAwQ=\""))
            assertTrue(requestBody.contains("\"encoding\":\"base64\""))
            assertTrue(requestBody.contains("\"sigVerify\":false"))
            assertTrue(requestBody.contains("\"replaceRecentBlockhash\":false"))
        }

    @Test
    fun `simulation rejection is a typed closed result`() =
        runTest {
            val client =
                OkHttpHeliusRpcProvider(
                    transport =
                        CapturingHeliusTransport(
                            HttpResponse(200, fixture("helius/simulation-rejected.json"), emptyMap()),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.simulateTransaction("AAECAwQ=")

            assertTrue(result is ProviderResult.Success)
            assertTrue((result as ProviderResult.Success).value is RpcSimulation.Rejected)
        }

    @Test
    fun `malformed simulation success fails closed`() =
        runTest {
            val client =
                OkHttpHeliusRpcProvider(
                    transport =
                        CapturingHeliusTransport(
                            HttpResponse(200, fixture("helius/simulation-malformed.json"), emptyMap()),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.simulateTransaction("AAECAwQ=")

            assertTrue(result is ProviderResult.Failure)
            assertEquals("unitsConsumed", (result as ProviderResult.Failure).error.invalidField)
        }

    @Test
    fun `fee request sends the exact base64 message at confirmed commitment`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/fee-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getFeeForMessage("AQIDBA==")

            assertTrue(result is ProviderResult.Success)
            val fee = (result as ProviderResult.Success).value
            assertEquals(5_000L, checkNotNull(fee).lamports)
            assertEquals(420_000_005L, fee.slot)
            val requestBody = checkNotNull(transport.body)
            assertTrue(requestBody.contains("\"method\":\"getFeeForMessage\""))
            assertTrue(requestBody.contains("\"AQIDBA==\""))
            assertTrue(requestBody.contains("\"commitment\":\"confirmed\""))
        }

    @Test
    fun `unavailable fee estimate remains typed for fail-closed callers`() =
        runTest {
            val client =
                OkHttpHeliusRpcProvider(
                    transport =
                        CapturingHeliusTransport(
                            HttpResponse(200, fixture("helius/fee-null.json"), emptyMap()),
                        ),
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                )

            val result = client.getFeeForMessage("AQIDBA==")

            assertTrue(result is ProviderResult.Success)
            assertEquals(null, (result as ProviderResult.Success).value)
        }

    @Test
    fun `transaction submission disables RPC retries`() =
        runTest {
            val transport =
                CapturingHeliusTransport(
                    HttpResponse(200, fixture("helius/send-success.json"), emptyMap()),
                )
            val client =
                OkHttpHeliusRpcProvider(
                    transport = transport,
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    sendRequestGate = RequestGate.None,
                )

            val result = client.sendTransaction("AQIDBA==")

            assertTrue(result is ProviderResult.Success)
            assertEquals("local-signature", (result as ProviderResult.Success).value)
            val requestBody = checkNotNull(transport.body)
            assertTrue(requestBody.contains("\"method\":\"sendTransaction\""))
            assertTrue(requestBody.contains("\"maxRetries\":0"))
        }

    @Test
    fun `submission transport failure is explicitly uncertain`() =
        runTest {
            val client =
                OkHttpHeliusRpcProvider(
                    transport = HttpTransport { throw IOException("fixture disconnect") },
                    apiKeySource = ApiKeySource { "fixture-key" },
                    requestGate = RequestGate.None,
                    sendRequestGate = RequestGate.None,
                )

            val result = client.sendTransaction("AQIDBA==")

            assertTrue(result is ProviderResult.Failure)
            assertTrue((result as ProviderResult.Failure).error is ProviderError.SubmissionUncertain)
        }
}

private class QueueHeliusTransport(
    vararg bodies: String,
) : HttpTransport {
    private val responses = ArrayDeque(bodies.toList())
    val bodies = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
        bodies +=
            request.body
                ?.let { requestBody ->
                    okio.Buffer().use { buffer ->
                        requestBody.writeTo(buffer)
                        buffer.readUtf8()
                    }
                }.orEmpty()
        return HttpResponse(200, responses.removeFirst(), emptyMap())
    }
}

private class CapturingHeliusTransport(
    private val response: HttpResponse,
) : HttpTransport {
    lateinit var request: Request
    var body: String? = null

    override suspend fun execute(request: Request): HttpResponse {
        this.request = request
        body =
            request.body?.let { requestBody ->
                okio.Buffer().use { buffer ->
                    requestBody.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        return response
    }
}
