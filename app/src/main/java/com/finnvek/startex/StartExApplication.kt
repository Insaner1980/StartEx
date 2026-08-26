package com.finnvek.startex

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import com.finnvek.startex.data.StartExRepository
import com.finnvek.startex.data.local.StartExDatabase
import com.finnvek.startex.data.settings.AppSettingsStore
import com.finnvek.startex.device.AndroidDeviceHealthProvider
import com.finnvek.startex.network.KrakenFiatRateProvider
import com.finnvek.startex.network.OkHttpHeliusRpcProvider
import com.finnvek.startex.network.OkHttpHeliusWebSocketProvider
import com.finnvek.startex.network.OkHttpJupiterSwapProvider
import com.finnvek.startex.network.OkHttpJupiterTokensProvider
import com.finnvek.startex.network.OkHttpPumpPortalDiscoveryProvider
import com.finnvek.startex.network.OkHttpTransport
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.security.AndroidKeystoreSecretCipher
import com.finnvek.startex.security.CorruptedSecretEnvelopeException
import com.finnvek.startex.security.KeystoreAccessMode
import com.finnvek.startex.security.SecretEnvelope
import com.finnvek.startex.security.SecretEnvelopeDatabaseException
import com.finnvek.startex.security.SecretEnvelopeFailure
import com.finnvek.startex.security.classifySecretEnvelopeFailure
import com.finnvek.startex.security.clearSecret
import com.finnvek.startex.service.TradingMonitorService
import com.finnvek.startex.wallet.WalletSecretCodec
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.time.Duration

internal data class SessionApiKeyRestoreResult(
    val restored: Boolean,
    val failure: SecretEnvelopeFailure? = null,
)

class StartExApplication : Application() {
    val database: StartExDatabase by lazy { StartExDatabase.create(this) }
    val repository: StartExRepository by lazy { StartExRepository(database) }
    val settings: AppSettingsStore by lazy { AppSettingsStore(this) }
    val deviceHealth by lazy { AndroidDeviceHealthProvider(this, TradingMonitorService::class.java) }
    val sessionApiKeys = SessionApiKeySource()
    val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(30))
            .pingInterval(Duration.ofSeconds(30))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val httpTransport by lazy { OkHttpTransport(httpClient) }
    val heliusRpc by lazy { OkHttpHeliusRpcProvider(httpTransport, sessionApiKeys) }
    val heliusWebSocket by lazy { OkHttpHeliusWebSocketProvider(httpClient, sessionApiKeys) }
    val pumpPortal by lazy { OkHttpPumpPortalDiscoveryProvider(httpClient, sessionApiKeys) }
    val jupiterSwap by lazy { OkHttpJupiterSwapProvider(httpTransport, sessionApiKeys) }
    val jupiterTokens by lazy { OkHttpJupiterTokensProvider(httpTransport, sessionApiKeys) }
    val fiatRates by lazy { KrakenFiatRateProvider(httpTransport) }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    internal suspend fun restoreSessionApiKey(provider: ProviderId): SessionApiKeyRestoreResult {
        val result =
            try {
                val stored =
                    try {
                        repository.providerCredential(provider.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw SecretEnvelopeDatabaseException(error)
                    } ?: return SessionApiKeyRestoreResult(restored = false)
                val accessMode =
                    KeystoreAccessMode.entries.firstOrNull { it.name == stored.keystoreAccessMode }
                        ?: throw CorruptedSecretEnvelopeException()
                if (accessMode != KeystoreAccessMode.UNATTENDED) throw CorruptedSecretEnvelopeException()
                val storedIv = stored.apiKeyIv
                val storedCiphertext = stored.encryptedApiKey
                val envelope =
                    try {
                        try {
                            SecretEnvelope(
                                version = stored.secretEnvelopeVersion,
                                publicAddress = "provider:${provider.name}",
                                iv = storedIv,
                                ciphertext = storedCiphertext,
                            )
                        } catch (error: IllegalArgumentException) {
                            throw CorruptedSecretEnvelopeException(error)
                        }
                    } finally {
                        storedIv.clearSecret()
                        storedCiphertext.clearSecret()
                    }
                val cipher = AndroidKeystoreSecretCipher.unattended()
                val encoded = cipher.decrypt(cipher.prepareDecryption(envelope), envelope)
                val decoded =
                    try {
                        WalletSecretCodec.decodeAndClear(encoded)
                    } catch (error: Exception) {
                        throw CorruptedSecretEnvelopeException(error)
                    }
                try {
                    sessionApiKeys.put(provider, decoded)
                } finally {
                    decoded.fill('0')
                }
                SessionApiKeyRestoreResult(restored = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SessionApiKeyRestoreResult(
                    restored = false,
                    failure = classifySecretEnvelopeFailure(error),
                )
            }
        if (!result.restored) sessionApiKeys.remove(provider)
        return result
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_BOT_STATUS,
                    getString(R.string.channel_bot_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_bot_description) },
                NotificationChannel(
                    CHANNEL_TRADES,
                    getString(R.string.channel_trades_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = getString(R.string.channel_trades_description) },
                NotificationChannel(
                    CHANNEL_CRITICAL,
                    getString(R.string.channel_critical_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.channel_critical_description) },
                NotificationChannel(
                    CHANNEL_PROVIDER,
                    getString(R.string.channel_provider_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = getString(R.string.channel_provider_description) },
                NotificationChannel(
                    CHANNEL_CANDIDATES,
                    getString(R.string.channel_candidates_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_candidates_description) },
            ),
        )
    }

    companion object {
        const val CHANNEL_BOT_STATUS = "bot_status"
        const val CHANNEL_TRADES = "trades"
        const val CHANNEL_CRITICAL = "critical_safety"
        const val CHANNEL_PROVIDER = "provider_health"
        const val CHANNEL_CANDIDATES = "candidate_activity"
    }
}
