package com.finnvek.startex.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

sealed interface HeliusSubscription {
    data class Account(
        val address: String,
    ) : HeliusSubscription

    data class Signature(
        val signature: String,
    ) : HeliusSubscription
}

sealed interface HeliusRealtimeEvent {
    data class AccountChanged(
        val address: String,
        val lamports: Long,
        val slot: Long,
    ) : HeliusRealtimeEvent

    data class SignatureChanged(
        val signature: String,
        val slot: Long,
        val failed: Boolean,
    ) : HeliusRealtimeEvent
}

interface HeliusRealtimeListener {
    fun onEvent(event: HeliusRealtimeEvent)

    fun onError(error: ProviderError)
}

fun interface HeliusRealtimeConnection {
    fun close()
}

fun interface HeliusWebSocketProvider {
    suspend fun connect(
        subscriptions: Set<HeliusSubscription>,
        listener: HeliusRealtimeListener,
    ): ProviderResult<HeliusRealtimeConnection>
}

class OkHttpHeliusWebSocketProvider(
    private val client: OkHttpClient,
    private val apiKeySource: ApiKeySource,
) : HeliusWebSocketProvider {
    private val lock = Any()
    private var activeSocket: WebSocket? = null
    private val expectedClosures = mutableSetOf<WebSocket>()

    override suspend fun connect(
        subscriptions: Set<HeliusSubscription>,
        listener: HeliusRealtimeListener,
    ): ProviderResult<HeliusRealtimeConnection> {
        if (subscriptions.isEmpty() || subscriptions.size > MAXIMUM_SUBSCRIPTIONS) {
            return ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "subscriptions"))
        }
        if (subscriptions.any { !it.isValid() }) {
            return ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "subscription"))
        }
        val apiKey =
            apiKeySource.apiKeyFor(ProviderId.HELIUS)?.takeIf(String::isNotBlank)
                ?: return ProviderResult.Failure(ProviderError.MissingApiKey(ProviderId.HELIUS))
        synchronized(lock) {
            if (activeSocket != null) {
                return ProviderResult.Failure(ProviderError.InvalidRequest(ProviderId.HELIUS, "connection"))
            }
            val pending = mutableMapOf<Long, HeliusSubscription>()
            val active = mutableMapOf<Long, HeliusSubscription>()
            val listenerAdapter = listenerAdapter(listener, subscriptions, pending, active)
            val url = ENDPOINT.newBuilder().addQueryParameter("api-key", apiKey).build()
            val socket = client.newWebSocket(Request.Builder().url(url).build(), listenerAdapter)
            activeSocket = socket
            return ProviderResult.Success(
                HeliusRealtimeConnection {
                    markExpectedClosure(socket)
                    if (!socket.close(NORMAL_CLOSE, "")) releaseSocket(socket)
                },
            )
        }
    }

    private fun listenerAdapter(
        listener: HeliusRealtimeListener,
        subscriptions: Set<HeliusSubscription>,
        pending: MutableMap<Long, HeliusSubscription>,
        active: MutableMap<Long, HeliusSubscription>,
    ) = object : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            sendSubscriptions(webSocket, subscriptions, pending, listener)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            handleMessage(webSocket, text, listener, pending, active)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (!releaseSocket(webSocket)) {
                listener.onError(ProviderError.ConnectionClosed(ProviderId.HELIUS, code))
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (!releaseSocket(webSocket)) {
                listener.onError(ProviderError.NetworkUnavailable(ProviderId.HELIUS))
            }
        }
    }

    private fun sendSubscriptions(
        webSocket: WebSocket,
        subscriptions: Set<HeliusSubscription>,
        pending: MutableMap<Long, HeliusSubscription>,
        listener: HeliusRealtimeListener,
    ) {
        for (subscription in subscriptions) {
            val requestId = requestIds.incrementAndGet()
            synchronized(lock) { pending[requestId] = subscription }
            if (!webSocket.send(HeliusWebSocketJson.subscriptionRequest(requestId, subscription))) {
                listener.onError(ProviderError.NetworkUnavailable(ProviderId.HELIUS))
                closeForFailure(webSocket)
                return
            }
        }
    }

    private fun handleMessage(
        webSocket: WebSocket,
        text: String,
        listener: HeliusRealtimeListener,
        pending: MutableMap<Long, HeliusSubscription>,
        active: MutableMap<Long, HeliusSubscription>,
    ) {
        when (val message = HeliusWebSocketJson.parse(text)) {
            is ProviderResult.Failure -> {
                listener.onError(message.error)
                closeForFailure(webSocket)
            }

            is ProviderResult.Success -> {
                when (val value = message.value) {
                    is HeliusWireMessage.Acknowledgement -> {
                        handleAcknowledgement(webSocket, value, listener, pending, active)
                    }

                    is HeliusWireMessage.AccountNotification -> {
                        handleAccountNotification(webSocket, value, listener, active)
                    }

                    is HeliusWireMessage.SignatureNotification -> {
                        handleSignatureNotification(webSocket, value, listener, active)
                    }
                }
            }
        }
    }

    private fun handleAcknowledgement(
        webSocket: WebSocket,
        message: HeliusWireMessage.Acknowledgement,
        listener: HeliusRealtimeListener,
        pending: MutableMap<Long, HeliusSubscription>,
        active: MutableMap<Long, HeliusSubscription>,
    ) {
        synchronized(lock) {
            val subscription = pending.remove(message.requestId)
            if (subscription == null) {
                listener.onError(ProviderError.InvalidResponse(ProviderId.HELIUS, "id"))
                closeForFailure(webSocket)
            } else {
                active[message.subscriptionId] = subscription
            }
        }
    }

    private fun handleAccountNotification(
        webSocket: WebSocket,
        message: HeliusWireMessage.AccountNotification,
        listener: HeliusRealtimeListener,
        active: Map<Long, HeliusSubscription>,
    ) {
        val subscription = synchronized(lock) { active[message.subscriptionId] }
        if (subscription !is HeliusSubscription.Account) {
            listener.onError(ProviderError.InvalidResponse(ProviderId.HELIUS, "subscription"))
            closeForFailure(webSocket)
            return
        }
        listener.onEvent(
            HeliusRealtimeEvent.AccountChanged(subscription.address, message.lamports, message.slot),
        )
    }

    private fun handleSignatureNotification(
        webSocket: WebSocket,
        message: HeliusWireMessage.SignatureNotification,
        listener: HeliusRealtimeListener,
        active: Map<Long, HeliusSubscription>,
    ) {
        val subscription = synchronized(lock) { active[message.subscriptionId] }
        if (subscription !is HeliusSubscription.Signature) {
            listener.onError(ProviderError.InvalidResponse(ProviderId.HELIUS, "subscription"))
            closeForFailure(webSocket)
            return
        }
        listener.onEvent(
            HeliusRealtimeEvent.SignatureChanged(subscription.signature, message.slot, message.failed),
        )
    }

    private fun closeForFailure(webSocket: WebSocket) {
        markExpectedClosure(webSocket)
        if (!webSocket.close(POLICY_VIOLATION_CLOSE, "")) releaseSocket(webSocket)
    }

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

    private companion object {
        val ENDPOINT = "https://mainnet.helius-rpc.com/".toHttpUrl()
        val requestIds = AtomicLong()
        const val MAXIMUM_SUBSCRIPTIONS = 64
        const val NORMAL_CLOSE = 1000
        const val POLICY_VIOLATION_CLOSE = 1008
    }
}

private fun HeliusSubscription.isValid(): Boolean =
    when (this) {
        is HeliusSubscription.Account -> address.isNotBlank()
        is HeliusSubscription.Signature -> signature.isNotBlank()
    }

internal sealed interface HeliusWireMessage {
    data class Acknowledgement(
        val requestId: Long,
        val subscriptionId: Long,
    ) : HeliusWireMessage

    data class AccountNotification(
        val subscriptionId: Long,
        val slot: Long,
        val lamports: Long,
    ) : HeliusWireMessage

    data class SignatureNotification(
        val subscriptionId: Long,
        val slot: Long,
        val failed: Boolean,
    ) : HeliusWireMessage
}

internal object HeliusWebSocketJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun subscriptionRequest(
        requestId: Long,
        subscription: HeliusSubscription,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            when (subscription) {
                is HeliusSubscription.Account -> {
                    put("method", "accountSubscribe")
                    put(
                        "params",
                        buildJsonArray {
                            add(JsonPrimitive(subscription.address))
                            add(
                                buildJsonObject {
                                    put("commitment", "confirmed")
                                    put("encoding", "base64")
                                },
                            )
                        },
                    )
                }

                is HeliusSubscription.Signature -> {
                    put("method", "signatureSubscribe")
                    put(
                        "params",
                        buildJsonArray {
                            add(JsonPrimitive(subscription.signature))
                            add(
                                buildJsonObject {
                                    put("commitment", "confirmed")
                                    put("enableReceivedNotification", false)
                                },
                            )
                        },
                    )
                }
            }
        }.toString()

    fun parse(body: String): ProviderResult<HeliusWireMessage> {
        if (body.length > MAXIMUM_MESSAGE_CHARACTERS) return invalid("bodySize")
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.takeUnless { it is JsonNull }?.let { return invalid("error") }
            val method = root["method"]?.jsonPrimitive?.contentOrNull
            if (method == null) {
                return ProviderResult.Success(
                    HeliusWireMessage.Acknowledgement(
                        requestId = root.requiredWireLong("id"),
                        subscriptionId = root.requiredWireLong("result"),
                    ),
                )
            }
            val params = root.requiredWireObject("params")
            val subscriptionId = params.requiredWireLong("subscription")
            val result = params.requiredWireObject("result")
            val slot =
                result
                    .requiredWireObject("context")
                    .requiredWireLong("slot")
                    .also { if (it < 0) invalidWire("slot") }
            when (method) {
                "accountNotification" -> {
                    val lamports = result.requiredWireObject("value").requiredWireLong("lamports")
                    if (lamports < 0) invalidWire("lamports")
                    ProviderResult.Success(
                        HeliusWireMessage.AccountNotification(subscriptionId, slot, lamports),
                    )
                }

                "signatureNotification" -> {
                    val value = result.requiredWireObject("value")
                    ProviderResult.Success(
                        HeliusWireMessage.SignatureNotification(
                            subscriptionId = subscriptionId,
                            slot = slot,
                            failed = value["err"]?.let { it !is JsonNull } ?: invalidWire("err"),
                        ),
                    )
                }

                else -> {
                    invalid("method")
                }
            }
        } catch (error: InvalidWireField) {
            invalid(error.field)
        } catch (_: RuntimeException) {
            invalid("json")
        }
    }

    private fun invalid(field: String): ProviderResult.Failure =
        ProviderResult.Failure(ProviderError.InvalidResponse(ProviderId.HELIUS, field))

    private const val MAXIMUM_MESSAGE_CHARACTERS = 64 * 1_024
}

private class InvalidWireField(
    val field: String,
) : RuntimeException()

private fun invalidWire(field: String): Nothing = throw InvalidWireField(field)

private fun JsonObject.requiredWireObject(name: String): JsonObject =
    get(name)?.takeUnless { it is JsonNull }?.jsonObject ?: invalidWire(name)

private fun JsonObject.requiredWireLong(name: String): Long {
    val value = get(name)?.jsonPrimitive ?: invalidWire(name)
    return value.longOrNull ?: value.contentOrNull?.toLongOrNull() ?: invalidWire(name)
}
