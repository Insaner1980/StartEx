package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

data class RpcBalance(
    val lamports: Long,
    val slot: Long,
)

data class RpcLatestBlockhash(
    val blockhash: String,
    val lastValidBlockHeight: Long,
    val slot: Long,
)

data class RpcFeeForMessage(
    val lamports: Long,
    val slot: Long,
)

data class RpcAccountInfo(
    val owner: String,
    val executable: Boolean,
    val lamports: Long,
    val slot: Long,
)

sealed interface RpcSimulation {
    val slot: Long

    data class Succeeded(
        override val slot: Long,
        val unitsConsumed: Long?,
    ) : RpcSimulation

    data class Rejected(
        override val slot: Long,
    ) : RpcSimulation
}

data class RpcSignatureStatus(
    val slot: Long,
    val confirmationStatus: String?,
    val confirmations: Long?,
    val hasError: Boolean,
)

data class RpcTokenHolding(
    val tokenAccount: String,
    val mint: String,
    val tokenProgram: String,
    val amountAtomic: BigInteger,
    val decimals: Int,
)

data class RpcTokenHoldings(
    val slot: Long,
    val holdings: List<RpcTokenHolding>,
)

data class RpcAddressSignature(
    val signature: String,
    val slot: Long,
    val blockTimeMillis: Long?,
    val memo: String?,
    val failed: Boolean,
)

interface HeliusRpcProvider {
    suspend fun getBalance(address: String): ProviderResult<RpcBalance>

    suspend fun getLatestBlockhash(): ProviderResult<RpcLatestBlockhash>

    suspend fun getBlockHeight(): ProviderResult<Long> =
        ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "blockHeight"))

    suspend fun getFeeForMessage(messageBase64: String): ProviderResult<RpcFeeForMessage?>

    suspend fun getAccountInfo(address: String): ProviderResult<RpcAccountInfo?>

    suspend fun simulateTransaction(unsignedTransactionBase64: String): ProviderResult<RpcSimulation>

    suspend fun getSignatureStatus(signature: String): ProviderResult<RpcSignatureStatus?>

    suspend fun getTokenHoldings(ownerAddress: String): ProviderResult<RpcTokenHoldings> =
        ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "tokenHoldings"))

    suspend fun getSignaturesForAddress(
        address: String,
        limit: Int = DEFAULT_SIGNATURE_LIMIT,
    ): ProviderResult<List<RpcAddressSignature>> = ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "signatures"))

    suspend fun sendTransaction(signedTransactionBase64: String): ProviderResult<String>

    companion object {
        const val DEFAULT_SIGNATURE_LIMIT = 25
    }
}

class OkHttpHeliusRpcProvider(
    private val transport: HttpTransport,
    private val apiKeySource: ApiKeySource,
    private val requestGate: RequestGate = FixedIntervalRequestGate(100),
    private val sendRequestGate: RequestGate = FixedIntervalRequestGate(1_000),
) : HeliusRpcProvider {
    override suspend fun getBalance(address: String): ProviderResult<RpcBalance> {
        if (address.isBlank()) return invalidRequest("address")
        return mapRpc(
            rpc(
                "getBalance",
                buildJsonArray {
                    add(JsonPrimitive(address))
                    add(buildJsonObject { put("commitment", "confirmed") })
                },
            ),
        ) { result ->
            val root = result.jsonObject
            RpcBalance(
                lamports = root.requiredLong("value"),
                slot = root.requiredContextSlot(),
            )
        }
    }

    override suspend fun getLatestBlockhash(): ProviderResult<RpcLatestBlockhash> =
        mapRpc(
            rpc(
                "getLatestBlockhash",
                buildJsonArray {
                    add(buildJsonObject { put("commitment", "confirmed") })
                },
            ),
        ) { result ->
            val root = result.jsonObject
            val value = root.requiredObject("value")
            RpcLatestBlockhash(
                blockhash = value.requiredRpcString("blockhash"),
                lastValidBlockHeight = value.requiredLong("lastValidBlockHeight"),
                slot = root.requiredContextSlot(),
            )
        }

    override suspend fun getBlockHeight(): ProviderResult<Long> =
        mapRpc(
            rpc(
                "getBlockHeight",
                buildJsonArray {
                    add(buildJsonObject { put("commitment", "confirmed") })
                },
            ),
        ) { result ->
            (result.jsonPrimitive.longOrNull ?: result.jsonPrimitive.contentOrNull?.toLongOrNull())
                ?.takeIf { it >= 0 }
                ?: invalidRpc("result")
        }

    override suspend fun getFeeForMessage(messageBase64: String): ProviderResult<RpcFeeForMessage?> {
        if (messageBase64.isBlank()) return invalidRequest("message")
        return mapRpc(
            rpc(
                "getFeeForMessage",
                buildJsonArray {
                    add(JsonPrimitive(messageBase64))
                    add(buildJsonObject { put("commitment", "confirmed") })
                },
            ),
        ) { result ->
            val root = result.jsonObject
            val value = root["value"] ?: invalidRpc("value")
            if (value is JsonNull) {
                null
            } else {
                RpcFeeForMessage(
                    lamports =
                        value.jsonPrimitive.longOrNull
                            ?: value.jsonPrimitive.contentOrNull?.toLongOrNull()
                            ?: invalidRpc("value"),
                    slot = root.requiredContextSlot(),
                )
            }
        }
    }

    override suspend fun getAccountInfo(address: String): ProviderResult<RpcAccountInfo?> {
        if (address.isBlank()) return invalidRequest("address")
        return mapRpc(
            rpc(
                "getAccountInfo",
                buildJsonArray {
                    add(JsonPrimitive(address))
                    add(
                        buildJsonObject {
                            put("commitment", "confirmed")
                            put("encoding", "base64")
                        },
                    )
                },
            ),
        ) { result ->
            val root = result.jsonObject
            val value = root["value"]
            if (value == null || value is JsonNull) {
                null
            } else {
                val account = value.jsonObject
                RpcAccountInfo(
                    owner = account.requiredRpcString("owner"),
                    executable = account.requiredBoolean("executable"),
                    lamports = account.requiredLong("lamports"),
                    slot = root.requiredContextSlot(),
                )
            }
        }
    }

    override suspend fun simulateTransaction(unsignedTransactionBase64: String): ProviderResult<RpcSimulation> {
        if (unsignedTransactionBase64.isBlank()) return invalidRequest("unsignedTransaction")
        return mapRpc(
            rpc(
                "simulateTransaction",
                buildJsonArray {
                    add(JsonPrimitive(unsignedTransactionBase64))
                    add(
                        buildJsonObject {
                            put("encoding", "base64")
                            put("sigVerify", false)
                            put("commitment", "confirmed")
                            put("replaceRecentBlockhash", false)
                        },
                    )
                },
            ),
        ) { result ->
            val root = result.jsonObject
            val value = root.requiredObject("value")
            val error = value["err"] ?: invalidRpc("err")
            val slot = root.requiredContextSlot().takeIf { it >= 0 } ?: invalidRpc("context.slot")
            if (error is JsonNull) {
                RpcSimulation.Succeeded(
                    slot = slot,
                    unitsConsumed = value.optionalNonNegativeLong("unitsConsumed"),
                )
            } else {
                RpcSimulation.Rejected(slot)
            }
        }
    }

    override suspend fun getSignatureStatus(signature: String): ProviderResult<RpcSignatureStatus?> {
        if (signature.isBlank()) return invalidRequest("signature")
        return mapRpc(
            rpc(
                "getSignatureStatuses",
                buildJsonArray {
                    add(buildJsonArray { add(JsonPrimitive(signature)) })
                    add(buildJsonObject { put("searchTransactionHistory", true) })
                },
            ),
        ) { result ->
            val value = result.jsonObject["value"]?.jsonArray?.firstOrNull()
            if (value == null || value is JsonNull) {
                null
            } else {
                val status = value.jsonObject
                RpcSignatureStatus(
                    slot = status.requiredLong("slot"),
                    confirmationStatus = status.optionalRpcString("confirmationStatus"),
                    confirmations = status.optionalLong("confirmations"),
                    hasError = status["err"] != null && status["err"] !is JsonNull,
                )
            }
        }
    }

    override suspend fun getTokenHoldings(ownerAddress: String): ProviderResult<RpcTokenHoldings> {
        if (ownerAddress.isBlank()) return invalidRequest("ownerAddress")
        val legacy =
            when (val result = tokenAccountsByProgram(ownerAddress, TOKEN_PROGRAM_ID)) {
                is ProviderResult.Failure -> return result
                is ProviderResult.Success -> result
            }
        val token2022 =
            when (val result = tokenAccountsByProgram(ownerAddress, TOKEN_2022_PROGRAM_ID)) {
                is ProviderResult.Failure -> return result
                is ProviderResult.Success -> result
            }
        return ProviderResult.Success(
            RpcTokenHoldings(
                slot = maxOf(legacy.value.slot, token2022.value.slot),
                holdings =
                    (legacy.value.holdings + token2022.value.holdings)
                        .sortedWith(compareBy(RpcTokenHolding::mint, RpcTokenHolding::tokenAccount)),
            ),
            receivedAtMillis = maxOf(legacy.receivedAtMillis, token2022.receivedAtMillis),
        )
    }

    override suspend fun getSignaturesForAddress(
        address: String,
        limit: Int,
    ): ProviderResult<List<RpcAddressSignature>> {
        if (address.isBlank()) return invalidRequest("address")
        if (limit !in 1..MAX_SIGNATURE_LIMIT) return invalidRequest("limit")
        return mapRpc(
            rpc(
                "getSignaturesForAddress",
                buildJsonArray {
                    add(JsonPrimitive(address))
                    add(
                        buildJsonObject {
                            put("commitment", "confirmed")
                            put("limit", limit)
                        },
                    )
                },
            ),
        ) { result ->
            result.jsonArray.map { element ->
                val entry = element.jsonObject
                val blockTimeSeconds = entry.optionalLong("blockTime")
                RpcAddressSignature(
                    signature = entry.requiredRpcString("signature"),
                    slot = entry.requiredLong("slot").also { if (it < 0) invalidRpc("slot") },
                    blockTimeMillis =
                        blockTimeSeconds?.let {
                            if (it < 0) invalidRpc("blockTime")
                            try {
                                Math.multiplyExact(it, 1_000L)
                            } catch (_: ArithmeticException) {
                                invalidRpc("blockTime")
                            }
                        },
                    memo = entry.optionalRpcString("memo"),
                    failed = entry["err"]?.let { it !is JsonNull } ?: invalidRpc("err"),
                )
            }
        }
    }

    private suspend fun tokenAccountsByProgram(
        ownerAddress: String,
        tokenProgram: String,
    ): ProviderResult<RpcTokenHoldings> =
        mapRpc(
            rpc(
                "getTokenAccountsByOwner",
                buildJsonArray {
                    add(JsonPrimitive(ownerAddress))
                    add(buildJsonObject { put("programId", tokenProgram) })
                    add(
                        buildJsonObject {
                            put("commitment", "confirmed")
                            put("encoding", "jsonParsed")
                        },
                    )
                },
            ),
        ) { result ->
            val root = result.jsonObject
            RpcTokenHoldings(
                slot = root.requiredContextSlot().also { if (it < 0) invalidRpc("context.slot") },
                holdings =
                    root.requiredArray("value").map { element ->
                        val accountRoot = element.jsonObject
                        val account = accountRoot.requiredObject("account")
                        val parsedInfo =
                            account
                                .requiredObject("data")
                                .requiredObject("parsed")
                                .requiredObject("info")
                        val tokenAmount = parsedInfo.requiredObject("tokenAmount")
                        val amount =
                            tokenAmount
                                .requiredRpcString("amount")
                                .toBigIntegerOrNull()
                                ?.takeIf { it.signum() >= 0 }
                                ?: invalidRpc("tokenAmount.amount")
                        val decimals =
                            tokenAmount
                                .requiredLong("decimals")
                                .takeIf { it in 0..MAX_TOKEN_DECIMALS }
                                ?.toInt()
                                ?: invalidRpc("tokenAmount.decimals")
                        val actualProgram = account.requiredRpcString("owner")
                        if (actualProgram != tokenProgram) invalidRpc("account.owner")
                        RpcTokenHolding(
                            tokenAccount = accountRoot.requiredRpcString("pubkey"),
                            mint = parsedInfo.requiredRpcString("mint"),
                            tokenProgram = actualProgram,
                            amountAtomic = amount,
                            decimals = decimals,
                        )
                    },
            )
        }

    override suspend fun sendTransaction(signedTransactionBase64: String): ProviderResult<String> {
        if (signedTransactionBase64.isBlank()) return invalidRequest("signedTransaction")
        return mapRpc(
            rpc(
                "sendTransaction",
                buildJsonArray {
                    add(JsonPrimitive(signedTransactionBase64))
                    add(
                        buildJsonObject {
                            put("encoding", "base64")
                            put("skipPreflight", false)
                            put("preflightCommitment", "confirmed")
                            put("maxRetries", 0)
                        },
                    )
                },
                sendRequestGate,
                ProviderError.SubmissionUncertain(ProviderId.HELIUS),
            ),
        ) { result ->
            result.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) ?: invalidRpc("result")
        }
    }

    private suspend fun rpc(
        method: String,
        params: JsonArray,
        specialGate: RequestGate? = null,
        transportFailure: ProviderError = ProviderError.NetworkUnavailable(ProviderId.HELIUS),
    ): ProviderResult<JsonElement> {
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.HELIUS)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.HELIUS))
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestIds.incrementAndGet())
                put("method", method)
                put("params", params)
            }
        val url = MAINNET_URL.newBuilder().addQueryParameter("api-key", apiKey).build()
        val request =
            Request
                .Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        specialGate?.awaitTurn()
        requestGate.awaitTurn()
        val response =
            try {
                transport.execute(request)
            } catch (_: ResponseTooLargeException) {
                return ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.HELIUS, "bodySize"))
            } catch (_: IOException) {
                return ProviderResult.Failure(transportFailure)
            }
        response.httpError(ProviderId.HELIUS)?.let { return ProviderResult.Failure(it) }
        return HeliusJson.parseRpc(response.body)
    }

    private fun invalidRequest(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, field))

    private companion object {
        val MAINNET_URL = "https://mainnet.helius-rpc.com/".toHttpUrl()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val requestIds = AtomicLong()
        const val MAX_SIGNATURE_LIMIT = 1_000
        const val MAX_TOKEN_DECIMALS = 255L
        const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}

private object HeliusJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseRpc(body: String): ProviderResult<JsonElement> =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.let { errorElement ->
                if (errorElement !is JsonNull) {
                    val code =
                        errorElement.jsonObject["code"]?.jsonPrimitive?.intOrNull
                            ?: return invalidResponse("error.code")
                    return ProviderResult.Failure(ProviderError.RemoteFailure(ProviderId.HELIUS, code))
                }
            }
            val result = root["result"] ?: return invalidResponse("result")
            ProviderResult.Success(result)
        } catch (_: RuntimeException) {
            invalidResponse("json")
        }

    private fun invalidResponse(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.HELIUS, field))
}

private fun <T> mapRpc(
    result: ProviderResult<JsonElement>,
    transform: (JsonElement) -> T,
): ProviderResult<T> =
    when (result) {
        is ProviderResult.Failure -> {
            result
        }

        is ProviderResult.Success -> {
            try {
                ProviderResult.Success(transform(result.value), result.receivedAtMillis)
            } catch (error: InvalidRpcField) {
                ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.HELIUS, error.field))
            } catch (_: RuntimeException) {
                ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.HELIUS, "result"))
            }
        }
    }

private class InvalidRpcField(
    val field: String,
) : RuntimeException()

private fun invalidRpc(field: String): Nothing = throw InvalidRpcField(field)

private fun JsonObject.requiredRpcString(name: String): String = optionalRpcString(name)?.takeIf(String::isNotBlank) ?: invalidRpc(name)

private fun JsonObject.optionalRpcString(name: String): String? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.contentOrNull
}

private fun JsonObject.requiredLong(name: String): Long = optionalLong(name) ?: invalidRpc(name)

private fun JsonObject.optionalLong(name: String): Long? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.longOrNull ?: element.jsonPrimitive.contentOrNull?.toLongOrNull()
}

private fun JsonObject.optionalNonNegativeLong(name: String): Long? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    val value =
        element.jsonPrimitive.longOrNull
            ?: element.jsonPrimitive.contentOrNull?.toLongOrNull()
            ?: invalidRpc(name)
    return value.takeIf { it >= 0 } ?: invalidRpc(name)
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = get(name)?.jsonPrimitive?.contentOrNull ?: invalidRpc(name)
    return value.toBooleanStrictOrNull() ?: invalidRpc(name)
}

private fun JsonObject.requiredObject(name: String): JsonObject = get(name)?.takeUnless { it is JsonNull }?.jsonObject ?: invalidRpc(name)

private fun JsonObject.requiredArray(name: String): JsonArray = get(name)?.takeUnless { it is JsonNull }?.jsonArray ?: invalidRpc(name)

private fun JsonObject.requiredContextSlot(): Long = requiredObject("context").requiredLong("slot")
