package io.securitycam.level2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.securitycam.level2.channels.LogChannel
import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.Channel
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.event.ChannelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the per-channel "Send test" buttons (design:
 * `2026-08-19-channel-sendtest-design.md`). The ViewModel's channel factories
 * are injected so no test ever touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendTestUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** LogChannel validates trivially and never touches the network. */
    private fun viewModel(
        factories: Map<String, ChannelFactory>,
        channels: List<ChannelConfig>? = null,
    ): SettingsViewModel =
        SettingsViewModel(
            settingsLoader = {
                val base = AppSettings.defaults()
                if (channels == null) base else base.copyWith(channelConfigs = channels)
            },
            settingsSaver = {},
            eventsClearer = {},
            channelFactories = factories,
        )

    private fun telegramChannels(): List<ChannelConfig> = listOf(
        ChannelConfig(id = "log", type = "log", enabled = true),
        ChannelConfig(id = "telegram", type = "telegram", enabled = false),
    )

    private fun setContent(vm: SettingsViewModel) {
        compose.setContent { SettingsScreen(viewModel = vm) }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()
    }

    private fun expandSection(title: String) {
        compose.onNodeWithTag(sectionTag(title)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun expandChannel(id: String) {
        compose.onNodeWithTag("channelHeader_$id").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun logChannelHasNoSendTestButton() {
        val vm = viewModel(factories = mapOf("log" to { c: ChannelConfig -> LogChannel(id = c.id) }))
        setContent(vm)

        compose.onNodeWithTag("sendTest_log").assertDoesNotExist()
    }

    @Test
    fun invalidDraftDisablesTheButton() {
        // No telegram factory → merged draft cannot validate → disabled.
        val vm = viewModel(factories = emptyMap(), channels = telegramChannels())
        setContent(vm)

        expandSection("Notification Channels")
        expandChannel("telegram")
        compose.onNodeWithTag("sendTest_telegram").assertExists()
        compose.onNodeWithTag("sendTest_telegram").assertIsNotEnabled()
        assertNull(vm.message.value)
    }

    @Test
    fun validDraftSendsAndShowsDeliveredSnackbar() {
        val vm = viewModel(
            factories = mapOf("telegram" to { c: ChannelConfig -> LogChannel(id = c.id) }),
            channels = telegramChannels(),
        )
        setContent(vm)
        compose.runOnIdle {
            vm.update { settings ->
                settings.copy(
                    channelConfigs = settings.channelConfigs.map { c ->
                        if (c.type == "telegram") {
                            c.copy(
                                enabled = true,
                                settingsJson = mapOf<String, Any?>(
                                    "botToken" to "t",
                                    "chatId" to "1",
                                ),
                            )
                        } else {
                            c
                        }
                    },
                )
            }
        }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        expandSection("Notification Channels")
        expandChannel("telegram")
        compose.onNodeWithTag("sendTest_telegram").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("sendTest_telegram").performClick()
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        // The screen consumes vm.message once the snackbar dismisses, so assert
        // on the visible snackbar text instead.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Send test: delivered")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send test: delivered").assertIsDisplayed()
    }

    @Test
    fun invalidDraftShowsTheReasonUnderTheButton() {
        // No telegram factory → merged draft cannot validate → reason shown.
        val vm = viewModel(factories = emptyMap(), channels = telegramChannels())
        setContent(vm)

        expandSection("Notification Channels")
        expandChannel("telegram")
        compose.onNodeWithTag("sendTestError_telegram").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unknown channel type telegram").assertIsDisplayed()
    }

    /** Always-valid channel whose test send blocks until [gate] completes. */
    private class BlockingChannel(
        override val id: String,
        private val gate: CompletableDeferred<Unit>,
    ) : Channel {
        override val type: String = "telegram"
        override val enabled: Boolean = true
        override val settings: ChannelSettings = object : ChannelSettings() {
            override val type: String = "telegram"
            override fun toJson(): Map<String, Any?> = emptyMap()
            override val secretFields: List<String> = emptyList()
        }

        override fun validate(): String? = null

        override suspend fun send(message: AlertMessage) {}

        override suspend fun sendTest() {
            gate.await()
        }
    }

    @Test
    fun allSendButtonsDisabledWhileOneIsInFlight() {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(
            factories = mapOf("telegram" to { c: ChannelConfig -> BlockingChannel(c.id, gate) }),
            channels = listOf(
                ChannelConfig(id = "log", type = "log", enabled = true),
                ChannelConfig(id = "telegram", type = "telegram", enabled = false),
                ChannelConfig(id = "telegram-2", type = "telegram", enabled = false),
            ),
        )
        setContent(vm)

        expandSection("Notification Channels")
        expandChannel("telegram")
        expandChannel("telegram-2")
        compose.onNodeWithTag("sendTest_telegram").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("sendTest_telegram-2").performScrollTo().assertIsEnabled()

        compose.onNodeWithTag("sendTest_telegram").performScrollTo().performClick()
        dispatcher.scheduler.runCurrent()
        compose.waitForIdle()

        // A keeps its per-card in-flight label; B is disabled too — taps on
        // B are visibly blocked instead of silently dropped.
        compose.onNodeWithTag("sendTest_telegram").performScrollTo()
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithTag("sendTest_telegram").assertIsNotEnabled()
        compose.onNodeWithTag("sendTest_telegram-2").assertIsNotEnabled()

        compose.runOnIdle { gate.complete(Unit) }
        dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Send test: delivered")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send test: delivered").assertIsDisplayed()
        compose.onNodeWithTag("sendTest_telegram-2").performScrollTo().assertIsEnabled()
    }
}
