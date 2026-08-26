package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.math.BigDecimal

enum class PumpPortalEventKind {
    NEW_TOKEN,
    MIGRATION,
}

data class PumpPortalEvent(
    val kind: PumpPortalEventKind,
    val signature: String,
    val mint: String,
    val creator: String?,
    val name: String?,
    val symbol: String?,
    val metadataUri: String?,
    val marketCapSol: BigDecimal?,
    val pool: String?,
)

object PumpPortalProtocol {
    val discoverySubscriptions: List<String> =
        listOf(
            "{\"method\":\"subscribeNewToken\"}",
            "{\"method\":\"subscribeMigration\"}",
        )
}

interface PumpPortalEventListener {
    fun onEvent(event: PumpPortalEvent)

    fun onError(error: ProviderError)
}

fun interface PumpPortalConnection {
    fun close()
}

fun interface PumpPortalDiscoveryProvider {
    suspend fun connect(listener: PumpPortalEventListener): ProviderResult<PumpPortalConnection>
}

class OkHttpPumpPortalDiscoveryProvider(
    private val client: OkHttpClient,
    private val apiKeySource: ApiKeySource,
) : PumpPortalDiscoveryProvider {
    private val lock = Any()
    private var activeSocket: WebSocket? = null
    private val expectedClosures = mutableSetOf<WebSocket>()

    override suspend fun connect(listener: PumpPortalEventListener): ProviderResult<PumpPortalConnection> {
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.PUMP_PORTAL)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.PUMP_PORTAL))
        synchronized(lock) {
            if (activeSocket != null) {
                return ProviderResult.Failure(
                    ProviderError.InvalidRequest(ProviderId.PUMP_PORTAL, "connection"),
                )
            }
            val url = ENDPOINT.newBuilder().addQueryParameter("api-key", apiKey).build()
            val webSocketListener = listenerAdapter(listener)
            val socket = client.newWebSocket(Request.Builder().url(url).build(), webSocketListener)
            activeSocket = socket
            return ProviderResult.Success(
                PumpPortalConnection {
                    markExpectedClosure(socket)
                    if (!socket.close(NORMAL_CLOSE, "")) releaseSocket(socket)
                },
            )
        }
    }

    private fun listenerAdapter(listener: PumpPortalEventListener): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                sendDiscoverySubscriptions(webSocket, listener)
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                handleDiscoveryMessage(webSocket, text, listener)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                notifyPumpPortalClosed(releaseSocket(webSocket), code, listener)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                if (!releaseSocket(webSocket)) {
                    listener.onError(ProviderError.NetworkUnavailable(ProviderId.PUMP_PORTAL))
                }
            }
        }

    private fun sendDiscoverySubscriptions(
        webSocket: WebSocket,
        listener: PumpPortalEventListener,
    ) {
        if (PumpPortalProtocol.discoverySubscriptions.all(webSocket::send)) return
        listener.onError(ProviderError.NetworkUnavailable(ProviderId.PUMP_PORTAL))
        closeSocket(webSocket, NORMAL_CLOSE)
    }

    private fun handleDiscoveryMessage(
        webSocket: WebSocket,
        text: String,
        listener: PumpPortalEventListener,
    ) {
        if (text.length > MAXIMUM_EVENT_CHARACTERS) {
            listener.onError(ProviderError.InvalidResponse(ProviderId.PUMP_PORTAL, "bodySize"))
            closeSocket(webSocket, POLICY_VIOLATION_CLOSE)
            return
        }
        if (PumpPortalJson.isSubscriptionAcknowledgement(text)) return
        when (val parsed = PumpPortalJson.parseEvent(text)) {
            is ProviderResult.Success -> listener.onEvent(parsed.value)
            is ProviderResult.Failure -> listener.onError(parsed.error)
        }
    }

    private fun closeSocket(
        webSocket: WebSocket,
        closeCode: Int,
    ) {
        markExpectedClosure(webSocket)
        if (!webSocket.close(closeCode, "")) releaseSocket(webSocket)
    }

    // CPD-OFF
    private fun markExpectedClosure(webSocket: WebSocket) =
        synchronized(lock) {
            if (activeSocket === webSocket) activeSocket = null
            expectedClosures += webSocket
        }

    private fun releaseSocket(webSocket: WebSocket): Boolean =
        synchronized(lock) {
            if (activeSocket === webSocket) activeSocket = null
            expectedClosures.remove(webSocket)
        }
    // CPD-ON

    private companion object {
        // OkHttp represents a secure WebSocket handshake as an HTTPS request.
        val ENDPOINT = "https://pumpportal.fun/api/data".toHttpUrl()
        const val NORMAL_CLOSE = 1000
        const val POLICY_VIOLATION_CLOSE = 1008
        const val MAXIMUM_EVENT_CHARACTERS = 64 * 1_024
    }
}

internal fun notifyPumpPortalClosed(
    expected: Boolean,
    code: Int,
    listener: PumpPortalEventListener,
) {
    if (!expected) {
        listener.onError(ProviderError.ConnectionClosed(ProviderId.PUMP_PORTAL, code))
    }
}

object PumpPortalJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun isSubscriptionAcknowledgement(body: String): Boolean =
        runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject
                .optionalPumpString("message", MAXIMUM_ACKNOWLEDGEMENT_CHARACTERS)
                ?.startsWith("Successfully subscribed") == true
        }.getOrDefault(false)

    fun parseEvent(body: String): ProviderResult<PumpPortalEvent> =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val kind =
                when (root.requiredPumpString("txType", MAXIMUM_EVENT_TYPE_CHARACTERS).lowercase()) {
                    "create" -> PumpPortalEventKind.NEW_TOKEN
                    "migrate", "migration" -> PumpPortalEventKind.MIGRATION
                    else -> invalidPump("txType")
                }
            ProviderResult.Success(
                PumpPortalEvent(
                    kind = kind,
                    signature = root.requiredPumpString("signature", MAXIMUM_SIGNATURE_CHARACTERS),
                    mint = root.requiredPumpString("mint", MAXIMUM_ADDRESS_CHARACTERS),
                    creator = root.optionalPumpString("traderPublicKey", MAXIMUM_ADDRESS_CHARACTERS),
                    name = root.optionalPumpString("name", MAXIMUM_NAME_CHARACTERS),
                    symbol = root.optionalPumpString("symbol", MAXIMUM_SYMBOL_CHARACTERS),
                    metadataUri = root.optionalPumpString("uri", MAXIMUM_METADATA_URI_CHARACTERS),
                    marketCapSol =
                        root
                            .optionalPumpDecimal(
                                "marketCapSol",
                                MAXIMUM_DECIMAL_CHARACTERS,
                            )?.also {
                                if (it.signum() < 0) invalidPump("marketCapSol")
                            },
                    pool = root.optionalPumpString("pool", MAXIMUM_ADDRESS_CHARACTERS),
                ),
            )
        } catch (error: InvalidPumpField) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.PUMP_PORTAL, error.field))
        } catch (_: RuntimeException) {
            ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.PUMP_PORTAL, "json"))
        }

    private const val MAXIMUM_ACKNOWLEDGEMENT_CHARACTERS = 256
    private const val MAXIMUM_EVENT_TYPE_CHARACTERS = 16
    private const val MAXIMUM_SIGNATURE_CHARACTERS = 128
    private const val MAXIMUM_ADDRESS_CHARACTERS = 64
    private const val MAXIMUM_NAME_CHARACTERS = 128
    private const val MAXIMUM_SYMBOL_CHARACTERS = 32
    private const val MAXIMUM_METADATA_URI_CHARACTERS = 2_048
    private const val MAXIMUM_DECIMAL_CHARACTERS = 64
}

private class InvalidPumpField(
    val field: String,
) : RuntimeException()

private fun invalidPump(field: String): Nothing = throw InvalidPumpField(field)

private fun JsonObject.requiredPumpString(
    name: String,
    maximumCharacters: Int,
): String = optionalPumpString(name, maximumCharacters)?.takeIf(String::isNotBlank) ?: invalidPump(name)

private fun JsonObject.optionalPumpString(
    name: String,
    maximumCharacters: Int,
): String? {
    val element = get(name) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.contentOrNull?.also {
        if (it.length > maximumCharacters) invalidPump(name)
    }
}

private fun JsonObject.optionalPumpDecimal(
    name: String,
    maximumCharacters: Int,
): BigDecimal? {
    val value = optionalPumpString(name, maximumCharacters) ?: return null
    return value.toBigDecimalOrNull() ?: invalidPump(name)
}
