package com.finnvek.startex.device

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
}

enum class DeviceThermalStatus(
    val blocksEntries: Boolean,
) {
    NONE(false),
    LIGHT(false),
    MODERATE(false),
    SEVERE(true),
    CRITICAL(true),
    EMERGENCY(true),
    SHUTDOWN(true),
}

data class DeviceHealthSnapshot(
    val networkConnected: Boolean?,
    val networkTransport: NetworkTransport?,
    val batteryLevelPercent: Int?,
    val batteryCritical: Boolean?,
    val charging: Boolean?,
    val thermalStatus: DeviceThermalStatus?,
    val foregroundServiceRunning: Boolean?,
    val providerRttMillis: Long?,
    val lastEventAgeMillis: Long?,
)

enum class DeviceEntryAction {
    ALLOW_NEW_ENTRIES,
    BLOCK_NEW_ENTRIES,
}

enum class DeviceEntryBlockReason {
    DEVICE_FACTS_UNAVAILABLE,
    NETWORK_DISCONNECTED,
    BATTERY_CRITICAL,
    THERMAL_SEVERE,
}

object DeviceHealthEntryPolicy {
    fun action(snapshot: DeviceHealthSnapshot): DeviceEntryAction =
        if (blockReason(snapshot) == null) {
            DeviceEntryAction.ALLOW_NEW_ENTRIES
        } else {
            DeviceEntryAction.BLOCK_NEW_ENTRIES
        }

    fun blockReason(snapshot: DeviceHealthSnapshot): DeviceEntryBlockReason? {
        if (
            snapshot.networkConnected == null ||
            snapshot.networkTransport == null ||
            snapshot.batteryLevelPercent == null ||
            snapshot.batteryCritical == null ||
            snapshot.charging == null ||
            snapshot.thermalStatus == null
        ) {
            return DeviceEntryBlockReason.DEVICE_FACTS_UNAVAILABLE
        }
        if (!snapshot.networkConnected) return DeviceEntryBlockReason.NETWORK_DISCONNECTED
        if (snapshot.batteryCritical && !snapshot.charging) {
            return DeviceEntryBlockReason.BATTERY_CRITICAL
        }
        if (snapshot.thermalStatus.blocksEntries) return DeviceEntryBlockReason.THERMAL_SEVERE
        return null
    }
}

class AndroidDeviceHealthProvider(
    context: Context,
    private val foregroundServiceClass: Class<out Service>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    fun snapshot(
        providerRttMillis: Long?,
        lastEventAtMillis: Long?,
    ): DeviceHealthSnapshot {
        val network = networkFacts()
        val battery = batteryFacts()
        val now = nowMillis()
        return DeviceHealthSnapshot(
            networkConnected = network?.connected,
            networkTransport = network?.transport,
            batteryLevelPercent = battery?.levelPercent,
            batteryCritical = battery?.critical,
            charging = battery?.charging,
            thermalStatus = thermalStatus(),
            foregroundServiceRunning = foregroundServiceRunning(),
            providerRttMillis = providerRttMillis?.takeIf { it >= 0 },
            lastEventAgeMillis =
                lastEventAtMillis
                    ?.takeIf { it in 0..now }
                    ?.let { now - it },
        )
    }

    private fun networkFacts(): NetworkFacts? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = manager.activeNetwork ?: return NetworkFacts(false, NetworkTransport.NONE)
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return null
        val connected =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkFacts(connected, capabilities.transport())
    }

    private fun batteryFacts(): BatteryFacts? {
        val battery =
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
        if (
            !battery.hasExtra(BatteryManager.EXTRA_LEVEL) ||
            !battery.hasExtra(BatteryManager.EXTRA_SCALE) ||
            !battery.hasExtra(BatteryManager.EXTRA_STATUS) ||
            !battery.hasExtra(BatteryManager.EXTRA_PLUGGED) ||
            !battery.hasExtra(BatteryManager.EXTRA_BATTERY_LOW)
        ) {
            return null
        }
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (level < 0 || scale <= 0 || plugged !in KNOWN_PLUGGED_STATES) return null
        val charging =
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
                -> {
                    if (plugged == 0) return null
                    true
                }

                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    if (plugged != 0) return null
                    false
                }

                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> {
                    false
                }

                else -> {
                    return null
                }
            }
        return BatteryFacts(
            levelPercent = ((level.toLong() * 100L) / scale).toInt().coerceIn(0, 100),
            critical = battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false),
            charging = charging,
        )
    }

    private fun thermalStatus(): DeviceThermalStatus? {
        val manager = appContext.getSystemService(PowerManager::class.java) ?: return null
        return when (manager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> DeviceThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> DeviceThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceThermalStatus.SHUTDOWN
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceRunning(): Boolean? {
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getRunningServices(Int.MAX_VALUE).any { service ->
                service.service.className == foregroundServiceClass.name && service.foreground
            }
        }.getOrNull()
    }

    private data class NetworkFacts(
        val connected: Boolean,
        val transport: NetworkTransport,
    )

    private data class BatteryFacts(
        val levelPercent: Int,
        val critical: Boolean,
        val charging: Boolean,
    )

    private companion object {
        val KNOWN_PLUGGED_STATES =
            buildSet {
                add(0)
                add(BatteryManager.BATTERY_PLUGGED_AC)
                add(BatteryManager.BATTERY_PLUGGED_USB)
                add(BatteryManager.BATTERY_PLUGGED_WIRELESS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(BatteryManager.BATTERY_PLUGGED_DOCK)
                }
            }
    }
}

private fun NetworkCapabilities.transport(): NetworkTransport =
    when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
        else -> NetworkTransport.OTHER
    }
