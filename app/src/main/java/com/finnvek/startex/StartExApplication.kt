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
import com.finnvek.startex.service.TradingMonitorService
import okhttp3.OkHttpClient
import java.time.Duration

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
