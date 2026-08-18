package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

data class JupiterTokenAudit(
    val isSuspicious: Boolean,
    val mintAuthorityDisabled: Boolean,
    val freezeAuthorityDisabled: Boolean,
    val topHoldersPercentage: BigDecimal,
    val developerBalancePercentage: BigDecimal?,
    val developerMintCount: Int?,
)

data class JupiterTokenStats(
    val organicBuyVolumeUsd: BigDecimal,
    val organicSellVolumeUsd: BigDecimal,
    val buyCount: Int,
    val sellCount: Int,
    val traderCount: Int,
    val organicBuyerCount: Int?,
    val netBuyerCount: Int?,
)

data class JupiterTokenSnapshot(
    val mint: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val tokenProgram: String,
    val holderCount: Int,
    val liquidityUsd: BigDecimal,
    val marketCapUsd: BigDecimal?,
    val usdPrice: BigDecimal?,
    val organicScore: BigDecimal,
    val organicScoreLabel: String,
    val isVerified: Boolean?,
    val audit: JupiterTokenAudit,
    val stats5m: JupiterTokenStats,
    val updatedAtMillis: Long,
)

interface JupiterTokensProvider {
    suspend fun tokenSnapshot(mint: String): ProviderResult<JupiterTokenSnapshot>
}

class OkHttpJupiterTokensProvider(
    private val transport: HttpTransport,
    private val apiKeySource: ApiKeySource,
    private val requestGate: RequestGate = JupiterFreeRequestGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumAgeMillis: Long = DEFAULT_MAXIMUM_AGE_MILLIS,
) : JupiterTokensProvider {
    init {
        require(maximumAgeMillis > 0)
    }

    override suspend fun tokenSnapshot(mint: String): ProviderResult<JupiterTokenSnapshot> {
        if (mint.isBlank()) {
            return ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.JUPITER, "mint"))
        }
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.JUPITER)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.JUPITER))
        val url = ENDPOINT.newBuilder().addQueryParameter("query", mint).build()
        val request =
            Request
                .Builder()
                .url(url)
                .header(API_KEY_HEADER, apiKey)
                .get()
                .build()
        // CPD-OFF
        requestGate.awaitTurn()
        val response =
            try {
                transport.execute(request)
            } catch (_: ResponseTooLargeException) {
                return invalidResponse("bodySize")
            } catch (_: IOException) {
                return ProviderResult.Failure(ProviderError.NetworkUnavailable(ProviderId.JUPITER))
            }
        response.httpError(ProviderId.JUPITER)?.let { return ProviderResult.Failure(it) }
        // CPD-ON
        return when (val parsed = JupiterTokensJson.parse(response.body, mint)) {
            is ProviderResult.Failure -> parsed
            is ProviderResult.Success -> enforceFreshness(parsed.value)
        }
    }

    private fun enforceFreshness(snapshot: JupiterTokenSnapshot): ProviderResult<JupiterTokenSnapshot> {
        val age = clock() - snapshot.updatedAtMillis
        if (age < 0) return invalidResponse("updatedAt")
        return if (age > maximumAgeMillis) {
            ProviderResult.Failure(ProviderError.StaleData(ProviderId.JUPITER, age))
        } else {
            ProviderResult.Success(snapshot)
        }
    }

    private fun invalidResponse(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, field))

    private companion object {
        val ENDPOINT = "https://api.jup.ag/tokens/v2/search".toHttpUrl()
        const val API_KEY_HEADER = "x-api-key"
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 120_000L
    }
}

private object JupiterTokensJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        body: String,
        expectedMint: String,
    ): ProviderResult<JupiterTokenSnapshot> =
        try {
            val matches =
                json
                    .parseToJsonElement(body)
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it.optionalTokenString("id") == expectedMint }
            val root = matches.singleOrNull() ?: invalidToken("id")
            val audit = root.requiredTokenObject("audit")
            val stats = root.requiredTokenObject("stats5m")
            val holderCount =
                root.requiredTokenInt("holderCount").also {
                    if (it <= 0) invalidToken("holderCount")
                }
            val liquidity =
                root.requiredTokenDecimal("liquidity").also {
                    if (it.signum() <= 0) invalidToken("liquidity")
                }
            val organicScore =
                root.requiredTokenDecimal("organicScore").also {
                    if (it < BigDecimal.ZERO || it > MAX_PERCENT) invalidToken("organicScore")
                }
            ProviderResult.Success(
                JupiterTokenSnapshot(
                    mint = root.requiredTokenString("id"),
                    name = root.requiredTokenString("name"),
                    symbol = root.requiredTokenString("symbol"),
                    decimals =
                        root.requiredTokenInt("decimals").also {
                            if (it !in 0..18) invalidToken("decimals")
                        },
                    tokenProgram = root.requiredTokenString("tokenProgram"),
                    holderCount = holderCount,
                    liquidityUsd = liquidity,
                    marketCapUsd = root.optionalTokenDecimal("mcap"),
                    usdPrice =
                        root.optionalTokenDecimal("usdPrice")?.also {
                            if (it.signum() <= 0) invalidToken("usdPrice")
                        },
                    organicScore = organicScore,
                    organicScoreLabel = root.requiredTokenString("organicScoreLabel"),
                    isVerified = root.optionalTokenBoolean("isVerified"),
                    audit =
                        JupiterTokenAudit(
                            isSuspicious = audit.requiredTokenBoolean("isSus"),
                            mintAuthorityDisabled = audit.requiredTokenBoolean("mintAuthorityDisabled"),
                            freezeAuthorityDisabled = audit.requiredTokenBoolean("freezeAuthorityDisabled"),
                            topHoldersPercentage =
                                audit.requiredTokenDecimal("topHoldersPercentage").also {
                                    if (it < BigDecimal.ZERO || it > MAX_PERCENT) {
                                        invalidToken("audit.topHoldersPercentage")
                                    }
                                },
                            developerBalancePercentage =
                                audit.optionalTokenDecimal("devBalancePercentage")?.also {
                                    if (it < BigDecimal.ZERO || it > MAX_PERCENT) {
                                        invalidToken("audit.devBalancePercentage")
                                    }
                                },
                            developerMintCount = audit.optionalTokenInt("devMints"),
                        ),
                    stats5m =
                        JupiterTokenStats(
                            organicBuyVolumeUsd = stats.requiredNonNegativeDecimal("buyOrganicVolume"),
                            organicSellVolumeUsd = stats.requiredNonNegativeDecimal("sellOrganicVolume"),
                            buyCount = stats.requiredNonNegativeInt("numBuys"),
                            sellCount = stats.requiredNonNegativeInt("numSells"),
                            traderCount = stats.requiredNonNegativeInt("numTraders"),
                            organicBuyerCount = stats.optionalTokenInt("numOrganicBuyers"),
                            netBuyerCount = stats.optionalTokenInt("numNetBuyers"),
                        ),
                    updatedAtMillis = Instant.parse(root.requiredTokenString("updatedAt")).toEpochMilli(),
                ),
            )
        } catch (error: InvalidTokenField) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, error.field))
        } catch (_: RuntimeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, "json"))
        }
}

private class InvalidTokenField(
    val field: String,
) : RuntimeException()

private fun invalidToken(field: String): Nothing = throw InvalidTokenField(field)

private fun JsonObject.requiredTokenString(name: String): String =
    optionalTokenString(name)?.takeIf(String::isNotBlank) ?: invalidToken(name)

private fun JsonObject.optionalTokenString(name: String): String? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.contentOrNull
}

private fun JsonObject.requiredTokenObject(name: String): JsonObject =
    get(name)?.takeUnless { it is JsonNull }?.jsonObject ?: invalidToken(name)

private fun JsonObject.requiredTokenInt(name: String): Int = optionalTokenInt(name) ?: invalidToken(name)

private fun JsonObject.optionalTokenInt(name: String): Int? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.intOrNull
}

private fun JsonObject.requiredNonNegativeInt(name: String): Int =
    requiredTokenInt(name).also {
        if (it < 0) invalidToken(name)
    }

private val MAX_PERCENT = BigDecimal("100")

private fun JsonObject.requiredTokenDecimal(name: String): BigDecimal = optionalTokenDecimal(name) ?: invalidToken(name)

private fun JsonObject.optionalTokenDecimal(name: String): BigDecimal? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.contentOrNull?.toBigDecimalOrNull()
}

private fun JsonObject.requiredNonNegativeDecimal(name: String): BigDecimal =
    requiredTokenDecimal(name).also {
        if (it.signum() < 0) invalidToken(name)
    }

private fun JsonObject.requiredTokenBoolean(name: String): Boolean = optionalTokenBoolean(name) ?: invalidToken(name)

private fun JsonObject.optionalTokenBoolean(name: String): Boolean? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.booleanOrNull
}
