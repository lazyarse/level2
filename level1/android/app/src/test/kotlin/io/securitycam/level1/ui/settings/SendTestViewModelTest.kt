package io.securitycam.level1.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level1.channels.LogChannel
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.Channel
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.core.ChannelSettings
import io.securitycam.level1.event.ChannelFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for `SettingsViewModel.sendTest` (design:
 * `2026-08-19-channel-sendtest-design.md`). No real network — fake factories.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendTestViewModelTest {

    private class FakeChannel(
        override val id: String,
        override val type: String,
        private val invalid: String? = null,
        private val throwOnSend: Boolean = false,
    ) : Channel {
        var sentTests: Int = 0

        override val enabled: Boolean = true
        override val settings: ChannelSettings = object : ChannelSettings() {
            override val type: String get() = this@FakeChannel.type
            override fun toJson(): Map<String, Any?> = emptyMap()
            override val secretFields: List<String> get() = emptyList()
        }

        override fun validate(): String? = invalid

        override suspend fun send(alert: io.securitycam.level1.core.AlertMessage) {}

        override suspend fun sendTest() {
            sentTests++
            if (throwOnSend) throw IllegalStateException("boom")
        }
    }

    private fun viewModel(
        factories: Map<String, ChannelFactory>,
    ): SettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return SettingsViewModel(
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            channelFactories = factories,
        )
    }

    @Test
    fun validChannelReturnsDeliveredAndCallsSendTest() = runBlocking {
        val fake = FakeChannel("c", "log")
        val vm = viewModel(factories = mapOf("log" to { _: ChannelConfig -> fake }))

        assertEquals("delivered", vm.sendTest(ChannelConfig(id = "c", type = "log")))
        assertEquals(1, fake.sentTests)
    }

    @Test
    fun invalidDraftShortCircuitsWithoutNetwork() = runBlocking {
        val fake = FakeChannel("c", "webhook", invalid = "Webhook URL is required")
        val vm = viewModel(factories = mapOf("webhook" to { _: ChannelConfig -> fake }))

        val result = vm.sendTest(
            ChannelConfig(id = "c", type = "webhook"),
        )
        assertTrue(result.startsWith("invalid:"))
        assertTrue(result.contains("Webhook URL is required"))
        assertEquals(0, fake.sentTests)
    }

    @Test
    fun throwingSendReturnsFailed() = runBlocking {
        val fake = FakeChannel("c", "log", throwOnSend = true)
        val vm = viewModel(factories = mapOf("log" to { _: ChannelConfig -> fake }))

        val result = vm.sendTest(ChannelConfig(id = "c", type = "log"))
        assertTrue(result.startsWith("failed:"))
        assertTrue(result.contains("boom"))
        assertEquals(1, fake.sentTests)
    }

    @Test
    fun unknownTypeFailsGracefully() = runBlocking {
        val vm = viewModel(factories = emptyMap())
        assertEquals("failed: unknown channel type log", vm.sendTest(ChannelConfig(id = "c", type = "log")))
    }
}
