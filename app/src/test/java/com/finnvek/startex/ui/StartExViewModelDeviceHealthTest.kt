package com.finnvek.startex.ui

import androidx.lifecycle.viewModelScope
import com.finnvek.startex.StartExApplication
import com.finnvek.startex.device.DeviceHealthSnapshot
import com.finnvek.startex.device.DeviceThermalStatus
import com.finnvek.startex.device.NetworkTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StartExViewModelDeviceHealthTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun freshRefreshInvalidatesTheOldSnapshotAndIgnoresAStaleCompletion() =
        runTest {
            val application = RuntimeEnvironment.getApplication() as StartExApplication
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
            val refreshDispatcher = StandardTestDispatcher(testScheduler)
            val viewModel = StartExViewModel(application, refreshDispatcher)
            val staleRefreshRelease = CompletableDeferred<Unit>()
            var refreshCount = 0
            viewModel.deviceHealthSnapshot = { _, _ ->
                when (++refreshCount) {
                    1 -> {
                        HEALTHY_SNAPSHOT
                    }

                    2 -> {
                        withContext(NonCancellable) { staleRefreshRelease.await() }
                        HEALTHY_SNAPSHOT
                    }

                    else -> {
                        BLOCKED_SNAPSHOT
                    }
                }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect()
            }
            viewModel.state.first { it.loaded }

            viewModel.refreshDeviceHealth()
            runCurrent()
            assertEquals(HEALTHY_SNAPSHOT, viewModel.state.value.deviceHealth)

            viewModel.refreshDeviceHealth()
            runCurrent()
            assertNull(viewModel.state.value.deviceHealth)

            viewModel.refreshDeviceHealth()
            runCurrent()
            assertEquals(BLOCKED_SNAPSHOT, viewModel.state.value.deviceHealth)

            staleRefreshRelease.complete(Unit)
            runCurrent()
            assertEquals(BLOCKED_SNAPSHOT, viewModel.state.value.deviceHealth)

            viewModel.viewModelScope.cancel()
            withContext(Dispatchers.IO) { application.database.clearAllTables() }
        }

    private companion object {
        val HEALTHY_SNAPSHOT =
            DeviceHealthSnapshot(
                networkConnected = true,
                networkTransport = NetworkTransport.WIFI,
                batteryLevelPercent = 80,
                batteryCritical = false,
                charging = false,
                thermalStatus = DeviceThermalStatus.NONE,
                foregroundServiceRunning = false,
                providerRttMillis = 20,
                lastEventAgeMillis = 100,
            )

        val BLOCKED_SNAPSHOT = HEALTHY_SNAPSHOT.copy(networkConnected = false)
    }
}
