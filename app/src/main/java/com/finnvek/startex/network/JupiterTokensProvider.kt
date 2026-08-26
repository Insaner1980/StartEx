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

fun interface JupiterTokensProvider {
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
            ProviderResult.Success(root.toTokenSnapshot())
        } catch (error: InvalidTokenField) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, error.field))
        } catch (_: RuntimeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, "json"))
        }
}

private fun JsonObject.toTokenSnapshot(): JupiterTokenSnapshot =
    JupiterTokenSnapshot(
        mint = requiredTokenString("id"),
        name = requiredTokenString("name"),
        symbol = requiredTokenString("symbol"),
        decimals = requiredTokenIntInRange("decimals", 0..18),
        tokenProgram = requiredTokenString("tokenProgram"),
        holderCount = requiredPositiveTokenInt("holderCount"),
        liquidityUsd = requiredPositiveTokenDecimal("liquidity"),
        marketCapUsd = optionalTokenDecimal("mcap"),
        usdPrice = optionalPositiveTokenDecimal("usdPrice"),
        organicScore = requiredTokenPercentage("organicScore"),
        organicScoreLabel = requiredTokenString("organicScoreLabel"),
        isVerified = optionalTokenBoolean("isVerified"),
        audit = requiredTokenObject("audit").toTokenAudit(),
        stats5m = requiredTokenObject("stats5m").toTokenStats(),
        updatedAtMillis = Instant.parse(requiredTokenString("updatedAt")).toEpochMilli(),
    )

private fun JsonObject.toTokenAudit(): JupiterTokenAudit =
    JupiterTokenAudit(
        isSuspicious = requiredTokenBoolean("isSus"),
        mintAuthorityDisabled = requiredTokenBoolean("mintAuthorityDisabled"),
        freezeAuthorityDisabled = requiredTokenBoolean("freezeAuthorityDisabled"),
        topHoldersPercentage = requiredTokenPercentage("topHoldersPercentage", "audit.topHoldersPercentage"),
        developerBalancePercentage =
            optionalTokenPercentage("devBalancePercentage", "audit.devBalancePercentage"),
        developerMintCount = optionalTokenInt("devMints"),
    )

private fun JsonObject.toTokenStats(): JupiterTokenStats =
    JupiterTokenStats(
        organicBuyVolumeUsd = requiredNonNegativeDecimal("buyOrganicVolume"),
        organicSellVolumeUsd = requiredNonNegativeDecimal("sellOrganicVolume"),
        buyCount = requiredNonNegativeInt("numBuys"),
        sellCount = requiredNonNegativeInt("numSells"),
        traderCount = requiredNonNegativeInt("numTraders"),
        organicBuyerCount = optionalTokenInt("numOrganicBuyers"),
        netBuyerCount = optionalTokenInt("numNetBuyers"),
    )

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

private fun JsonObject.requiredTokenIntInRange(
    name: String,
    range: IntRange,
): Int = requiredTokenInt(name).takeIf { it in range } ?: invalidToken(name)

private fun JsonObject.requiredPositiveTokenInt(name: String): Int = requiredTokenInt(name).takeIf { it > 0 } ?: invalidToken(name)

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

private fun JsonObject.requiredPositiveTokenDecimal(name: String): BigDecimal =
    requiredTokenDecimal(name).takeIf { it.signum() > 0 } ?: invalidToken(name)

private fun JsonObject.optionalPositiveTokenDecimal(name: String): BigDecimal? {
    val value = optionalTokenDecimal(name) ?: return null
    return value.takeIf { it.signum() > 0 } ?: invalidToken(name)
}

private fun JsonObject.requiredTokenPercentage(
    name: String,
    errorField: String = name,
): BigDecimal =
    requiredTokenDecimal(name).takeIf { it >= BigDecimal.ZERO && it <= MAX_PERCENT }
        ?: invalidToken(errorField)

private fun JsonObject.optionalTokenPercentage(
    name: String,
    errorField: String,
): BigDecimal? {
    val value = optionalTokenDecimal(name) ?: return null
    return value.takeIf { it >= BigDecimal.ZERO && it <= MAX_PERCENT } ?: invalidToken(errorField)
}

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
