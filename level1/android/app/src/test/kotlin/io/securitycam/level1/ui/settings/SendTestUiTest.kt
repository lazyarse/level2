package io.securitycam.level1.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.securitycam.level1.channels.LogChannel
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.ChannelConfig
import io.securitycam.level1.event.ChannelFactory
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
    private fun viewModel(factories: Map<String, ChannelFactory>): SettingsViewModel =
        SettingsViewModel(
            settingsLoader = { AppSettings.defaults() },
            settingsSaver = {},
            eventsClearer = {},
            channelFactories = factories,
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
        val vm = viewModel(factories = emptyMap())
        setContent(vm)

        expandSection("Channels")
        expandChannel("telegram")
        compose.onNodeWithTag("sendTest_telegram").assertExists()
        compose.onNodeWithTag("sendTest_telegram").assertIsNotEnabled()
        assertNull(vm.message.value)
    }

    @Test
    fun validDraftSendsAndShowsDeliveredSnackbar() {
        val vm = viewModel(
            factories = mapOf("telegram" to { c: ChannelConfig -> LogChannel(id = c.id) }),
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

        expandSection("Channels")
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
}
