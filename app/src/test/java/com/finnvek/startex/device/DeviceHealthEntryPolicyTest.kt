package com.finnvek.startex.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHealthEntryPolicyTest {
    @Test
    fun `known healthy device facts allow new entries`() {
        val snapshot = healthySnapshot()

        assertEquals(DeviceEntryAction.ALLOW_NEW_ENTRIES, DeviceHealthEntryPolicy.action(snapshot))
    }

    @Test
    fun `disconnected network blocks only new entries`() {
        val snapshot = healthySnapshot().copy(networkConnected = false)

        assertEquals(DeviceEntryAction.BLOCK_NEW_ENTRIES, DeviceHealthEntryPolicy.action(snapshot))
    }

    @Test
    fun `critical battery blocks new entries only while not charging`() {
        assertEquals(
            DeviceEntryAction.BLOCK_NEW_ENTRIES,
            DeviceHealthEntryPolicy.action(
                healthySnapshot().copy(batteryCritical = true, charging = false),
            ),
        )
        assertEquals(
            DeviceEntryAction.ALLOW_NEW_ENTRIES,
            DeviceHealthEntryPolicy.action(
                healthySnapshot().copy(batteryCritical = true, charging = true),
            ),
        )
    }

    @Test
    fun `severe thermal status blocks new entries`() {
        val snapshot = healthySnapshot().copy(thermalStatus = DeviceThermalStatus.SEVERE)

        assertEquals(DeviceEntryAction.BLOCK_NEW_ENTRIES, DeviceHealthEntryPolicy.action(snapshot))
    }

    @Test
    fun `missing critical device facts fail closed`() {
        val snapshots =
            listOf(
                healthySnapshot().copy(networkConnected = null),
                healthySnapshot().copy(networkTransport = null),
                healthySnapshot().copy(batteryLevelPercent = null),
                healthySnapshot().copy(batteryCritical = null),
                healthySnapshot().copy(charging = null),
                healthySnapshot().copy(thermalStatus = null),
            )

        snapshots.forEach { snapshot ->
            assertEquals(DeviceEntryAction.BLOCK_NEW_ENTRIES, DeviceHealthEntryPolicy.action(snapshot))
        }
    }

    private fun healthySnapshot() =
        DeviceHealthSnapshot(
            networkConnected = true,
            networkTransport = NetworkTransport.WIFI,
            batteryLevelPercent = 75,
            batteryCritical = false,
            charging = false,
            thermalStatus = DeviceThermalStatus.NONE,
            foregroundServiceRunning = true,
            providerRttMillis = 42,
            lastEventAgeMillis = 1_000,
        )
}
