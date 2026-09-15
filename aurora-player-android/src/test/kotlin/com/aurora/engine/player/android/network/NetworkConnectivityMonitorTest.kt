package com.aurora.engine.player.android.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkConnectivityMonitorTest {

    private lateinit var context: Context
    private lateinit var monitor: NetworkConnectivityMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        monitor = NetworkConnectivityMonitor(context)
    }

    @Test
    fun `manual update broadcasts to listeners`() {
        var notifiedStatus: NetworkStatus? = null
        monitor.addListener { status ->
            notifiedStatus = status
        }

        val testStatus = NetworkStatus(
            isConnected = true,
            networkType = NetworkType.WIFI,
            isMetered = false
        )

        monitor.updateManually(testStatus)

        assertThat(monitor.networkStatus.value).isEqualTo(testStatus)
        assertThat(notifiedStatus).isEqualTo(testStatus)
        assertThat(monitor.isOnline()).isTrue()
    }

    @Test
    fun `handover from wifi to cellular does not indicate offline`() {
        monitor.updateManually(NetworkStatus(true, NetworkType.WIFI, false))
        assertThat(monitor.isOnline()).isTrue()

        monitor.updateManually(NetworkStatus(true, NetworkType.CELLULAR, true))
        assertThat(monitor.isOnline()).isTrue()
        assertThat(monitor.networkStatus.value.isMetered).isTrue()
    }

    @Test
    fun `offline status updates isOnline to false`() {
        monitor.updateManually(NetworkStatus(false, NetworkType.OFFLINE, false))
        assertThat(monitor.isOnline()).isFalse()
    }
}
