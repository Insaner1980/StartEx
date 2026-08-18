package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import java.math.BigDecimal

data class SwapOrderRequest(
    val inputMint: String,
    val outputMint: String,
    val amountAtomic: Long,
    val taker: String? = null,
    val maximumSlippageBps: Int = 300,
    val maximumPriorityFeeLamports: Long? = null,
    val maximumTotalFeeBps: Int = 2_000,
    val maximumTransactionCostLamports: Long? = null,
)

data class SwapOrder(
    val inputMint: String,
    val outputMint: String,
    val inAmountAtomic: String,
    val outAmountAtomic: String,
    val minimumOutAmountAtomic: String?,
    val slippageBps: Int?,
    val router: String,
    val mode: String,
    val unsignedTransactionBase64: String?,
    val requestId: String,
    val lastValidBlockHeight: Long?,
    val expireAt: String?,
    val feeBps: Int,
    val feeMint: String,
    val priceImpactPercent: BigDecimal,
    val signatureFeeLamports: Long,
    val prioritizationFeeLamports: Long,
    val rentFeeLamports: Long,
)

data class SwapExecuteRequest(
    val signedTransactionBase64: String,
    val requestId: String,
    val lastValidBlockHeight: Long? = null,
)

data class SwapEvent(
    val inputMint: String,
    val inputAmountAtomic: String,
    val outputMint: String,
    val outputAmountAtomic: String,
)

data class SwapExecution(
    val signature: String,
    val slot: Long?,
    val inputAmountAtomic: String,
    val outputAmountAtomic: String,
    val events: List<SwapEvent>,
)

interface JupiterSwapProvider {
    suspend fun order(request: SwapOrderRequest): ProviderResult<SwapOrder>

    suspend fun execute(request: SwapExecuteRequest): ProviderResult<SwapExecution>
}

class OkHttpJupiterSwapProvider(
    private val transport: HttpTransport,
    private val apiKeySource: ApiKeySource,
    private val requestGate: RequestGate = JupiterFreeRequestGate,
) : JupiterSwapProvider {
    override suspend fun order(request: SwapOrderRequest): ProviderResult<SwapOrder> {
        validateOrder(request)?.let { return ProviderResult.Failure(it) }
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.JUPITER)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.JUPITER))
        val url =
            BASE_URL
                .newBuilder()
                .addPathSegment("order")
                .addQueryParameter("inputMint", request.inputMint)
                .addQueryParameter("outputMint", request.outputMint)
                .addQueryParameter("amount", request.amountAtomic.toString())
                .addQueryParameter("slippageBps", request.maximumSlippageBps.toString())
                .apply { request.taker?.let { addQueryParameter("taker", it) } }
                .apply {
                    request.maximumPriorityFeeLamports?.let {
                        addQueryParameter("priorityFeeLamports", it.toString())
                        addQueryParameter("broadcastFeeType", "maxCap")
                    }
                }.build()
        val httpRequest =
            Request
                .Builder()
                .url(url)
                .header(API_KEY_HEADER, apiKey)
                .get()
                .build()

        return when (val response = perform(httpRequest, submission = false)) {
            is ProviderResult.Failure -> {
                response
            }

            is ProviderResult.Success -> {
                when (val parsed = JupiterJson.parseOrder(response.value.body, request.taker != null)) {
                    is ProviderResult.Failure -> parsed
                    is ProviderResult.Success -> validateOrderResponse(request, parsed.value)
                }
            }
        }
    }

    override suspend fun execute(request: SwapExecuteRequest): ProviderResult<SwapExecution> {
        // CPD-OFF
        validateExecute(request)?.let { return ProviderResult.Failure(it) }
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.JUPITER)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.JUPITER))
        val payload =
            buildJsonObject {
                put("signedTransaction", request.signedTransactionBase64)
                put("requestId", request.requestId)
                request.lastValidBlockHeight?.let { put("lastValidBlockHeight", it) }
            }
        // CPD-ON
        val httpRequest =
            Request
                .Builder()
                .url(BASE_URL.newBuilder().addPathSegment("execute").build())
                .header(API_KEY_HEADER, apiKey)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        return when (val response = perform(httpRequest, submission = true)) {
            is ProviderResult.Failure -> response
            is ProviderResult.Success -> JupiterJson.parseExecution(response.value.body)
        }
    }

    private suspend fun perform(
        request: Request,
        submission: Boolean,
    ): ProviderResult<HttpResponse> {
        requestGate.awaitTurn()
        return try {
            val response = transport.execute(request)
            response.httpError(ProviderId.JUPITER)?.let { ProviderResult.Failure(it) }
                ?: ProviderResult.Success(response)
        } catch (_: ResponseTooLargeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, "bodySize"))
        } catch (_: IOException) {
            val error =
                if (submission) {
                    ProviderError.SubmissionUncertain(ProviderId.JUPITER)
                } else {
                    ProviderError.NetworkUnavailable(ProviderId.JUPITER)
                }
            ProviderResult.Failure(error)
        }
    }

    private fun validateOrder(request: SwapOrderRequest): ProviderError? =
        when {
            request.inputMint.isBlank() -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "inputMint")
            }

            request.outputMint.isBlank() -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "outputMint")
            }

            request.amountAtomic <= 0 -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "amount")
            }

            request.taker?.isBlank() == true -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "taker")
            }

            request.maximumSlippageBps !in 0..10_000 -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "slippageBps")
            }

            request.maximumPriorityFeeLamports?.let { it < 0 } == true -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "priorityFeeLamports")
            }

            request.maximumTotalFeeBps !in 0..10_000 -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "feeBps")
            }

            request.maximumTransactionCostLamports?.let { it < 0 } == true -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "maximumTransactionCostLamports")
            }

            else -> {
                null
            }
        }

    private fun validateExecute(request: SwapExecuteRequest): ProviderError? =
        when {
            request.signedTransactionBase64.isBlank() -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "signedTransaction")
            }

            request.requestId.isBlank() -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "requestId")
            }

            request.lastValidBlockHeight?.let { it <= 0 } == true -> {
                ProviderError.InvalidRequest(ProviderId.JUPITER, "lastValidBlockHeight")
            }

            else -> {
                null
            }
        }

    private fun validateOrderResponse(
        request: SwapOrderRequest,
        order: SwapOrder,
    ): ProviderResult<SwapOrder> =
        when {
            order.inputMint != request.inputMint -> {
                invalidOrder("inputMint")
            }

            order.outputMint != request.outputMint -> {
                invalidOrder("outputMint")
            }

            order.inAmountAtomic != request.amountAtomic.toString() -> {
                invalidOrder("inAmount")
            }

            order.outAmountAtomic.toULong() == 0UL -> {
                invalidOrder("outAmount")
            }

            order.minimumOutAmountAtomic?.toULongOrNull() == null -> {
                invalidOrder("otherAmountThreshold")
            }

            order.slippageBps == null || order.slippageBps !in 0..request.maximumSlippageBps -> {
                invalidOrder("slippageBps")
            }

            order.feeBps !in 0..request.maximumTotalFeeBps -> {
                invalidOrder("feeBps")
            }

            order.feeMint != request.inputMint && order.feeMint != request.outputMint -> {
                invalidOrder("feeMint")
            }

            order.signatureFeeLamports < 0 -> {
                invalidOrder("signatureFeeLamports")
            }

            order.prioritizationFeeLamports < 0 -> {
                invalidOrder("prioritizationFeeLamports")
            }

            order.rentFeeLamports < 0 -> {
                invalidOrder("rentFeeLamports")
            }

            order.router !in ALLOWED_ROUTERS -> {
                invalidOrder("router")
            }

            order.mode !in ALLOWED_MODES -> {
                invalidOrder("mode")
            }

            request.maximumPriorityFeeLamports != null &&
                order.prioritizationFeeLamports > request.maximumPriorityFeeLamports -> {
                invalidOrder("prioritizationFeeLamports")
            }

            request.maximumTransactionCostLamports != null &&
                exceedsTransactionCostCap(order, request.maximumTransactionCostLamports) -> {
                invalidOrder("transactionCostLamports")
            }

            request.taker != null && order.router != "jupiterz" && order.lastValidBlockHeight == null -> {
                invalidOrder("lastValidBlockHeight")
            }

            request.taker != null && order.router == "jupiterz" && order.expireAt.isNullOrBlank() -> {
                invalidOrder("expireAt")
            }

            else -> {
                ProviderResult.Success(order)
            }
        }

    private fun invalidOrder(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, field))

    private fun exceedsTransactionCostCap(
        order: SwapOrder,
        cap: Long,
    ): Boolean {
        val costs = listOf(order.signatureFeeLamports, order.prioritizationFeeLamports, order.rentFeeLamports)
        var remaining = cap
        for (cost in costs) {
            if (cost < 0 || cost > remaining) return true
            remaining -= cost
        }
        return false
    }

    private companion object {
        val BASE_URL = "https://api.jup.ag/swap/v2".toHttpUrl()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val API_KEY_HEADER = "x-api-key"
        val ALLOWED_ROUTERS = setOf("metis", "jupiterz", "dflow", "okx")
        val ALLOWED_MODES = setOf("ultra", "manual")
    }
}

object JupiterJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseOrder(
        body: String,
        transactionRequired: Boolean,
    ): ProviderResult<SwapOrder> =
        parse {
            val root = json.parseToJsonElement(body).jsonObject
            val transaction = root.optionalString("transaction")
            if (transactionRequired && transaction.isNullOrBlank()) invalid("transaction")
            SwapOrder(
                inputMint = root.requiredString("inputMint"),
                outputMint = root.requiredString("outputMint"),
                inAmountAtomic = root.requiredAtomic("inAmount"),
                outAmountAtomic = root.requiredAtomic("outAmount"),
                minimumOutAmountAtomic = root.optionalString("otherAmountThreshold"),
                slippageBps = root.optionalInt("slippageBps"),
                router = root.requiredString("router"),
                mode = root.requiredString("mode"),
                unsignedTransactionBase64 = transaction,
                requestId = root.requiredString("requestId"),
                lastValidBlockHeight = root.optionalLong("lastValidBlockHeight"),
                expireAt = root.optionalString("expireAt"),
                feeBps = root.requiredInt("feeBps"),
                feeMint = root.requiredString("feeMint"),
                priceImpactPercent = root.requiredDecimal("priceImpact"),
                signatureFeeLamports = root.requiredLong("signatureFeeLamports"),
                prioritizationFeeLamports = root.requiredLong("prioritizationFeeLamports"),
                rentFeeLamports = root.requiredLong("rentFeeLamports"),
            )
        }

    fun parseExecution(body: String): ProviderResult<SwapExecution> =
        parse {
            val root = json.parseToJsonElement(body).jsonObject
            val status = root.requiredString("status")
            val code = root.requiredInt("code")
            if (status != "Success" || code != 0) {
                throw RejectedExecution(code)
            }
            SwapExecution(
                signature = root.requiredString("signature"),
                slot = root.optionalLong("slot"),
                inputAmountAtomic = root.requiredAtomic("inputAmountResult"),
                outputAmountAtomic = root.requiredAtomic("outputAmountResult"),
                events =
                    root.optionalArray("swapEvents").map { element ->
                        val event = element.jsonObject
                        SwapEvent(
                            inputMint = event.requiredString("inputMint"),
                            inputAmountAtomic = event.requiredAtomic("inputAmount"),
                            outputMint = event.requiredString("outputMint"),
                            outputAmountAtomic = event.requiredAtomic("outputAmount"),
                        )
                    },
            )
        }

    private fun <T> parse(block: () -> T): ProviderResult<T> =
        try {
            ProviderResult.Success(block())
        } catch (error: InvalidField) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, error.field))
        } catch (error: RejectedExecution) {
            ProviderResult.Failure(ProviderError.ExecutionRejected(ProviderId.JUPITER, error.code))
        } catch (_: RuntimeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.JUPITER, "json"))
        }
}

private class InvalidField(
    val field: String,
) : RuntimeException()

private class RejectedExecution(
    val code: Int,
) : RuntimeException()

private fun invalid(field: String): Nothing = throw InvalidField(field)

private fun JsonObject.requiredString(name: String): String = optionalString(name)?.takeIf(String::isNotBlank) ?: invalid(name)

private fun JsonObject.requiredAtomic(name: String): String =
    requiredString(name).also {
        if (it.toULongOrNull() == null) invalid(name)
    }

private fun JsonObject.requiredInt(name: String): Int = optionalInt(name) ?: invalid(name)

private fun JsonObject.requiredLong(name: String): Long = optionalLong(name) ?: invalid(name)

private fun JsonObject.requiredDecimal(name: String): BigDecimal = optionalString(name)?.toBigDecimalOrNull() ?: invalid(name)

private fun JsonObject.optionalString(name: String): String? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.contentOrNull
}

private fun JsonObject.optionalInt(name: String): Int? =
    get(name)?.let { element ->
        if (element is JsonNull) null else element.jsonPrimitive.intOrNull
    }

private fun JsonObject.optionalLong(name: String): Long? =
    get(name)?.let { element ->
        if (element is JsonNull) {
            null
        } else {
            element.jsonPrimitive.longOrNull ?: element.jsonPrimitive.contentOrNull?.toLongOrNull()
        }
    }

private fun JsonObject.optionalArray(name: String): JsonArray = get(name)?.let { element -> element.jsonArray } ?: JsonArray(emptyList())
