package io.securitycam.level1.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level1.SecurityCamApp
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.storage.RecordedEventRow
import io.securitycam.level1.ui.events.EventsViewModel
import io.securitycam.level1.ui.settings.SettingsViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Port of the Flutter `shell_navigation_test.dart`: switching bottom tabs must
 * not recreate the Events screen state — the view-model (and its single
 * initial load) survives navigation away and back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row() = RecordedEventRow(
        id = 1,
        timestamp = Instant.parse("2026-01-01T12:00:00Z"),
        cameraName = "Hallway",
        triggerType = "motion",
        score = 0.8,
        snapshotName = null,
        videoName = null,
        channelStatuses = emptyMap(),
        triggerTypes = emptyList(),
    )

    @Test
    fun eventsTabDoesNotRecreateItsStateOnNavigation() {
        var loads = 0
        val instances = mutableListOf<EventsViewModel>()
        val eventsFactory = viewModelFactory {
            initializer {
                EventsViewModel(
                    loader = {
                        loads++
                        listOf(row())
                    },
                    snapshotLoader = { null },
                    videoOpener = null,
                ).also { instances.add(it) }
            }
        }
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                )
            }
        }

        compose.setContent {
            SecurityCamApp(
                eventsFactory = eventsFactory,
                settingsFactory = settingsFactory,
            )
        }
        compose.waitForIdle()
        assertEquals(0, instances.size)

        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()
        assertEquals(1, instances.size)
        assertEquals(1, loads)
        compose.onNodeWithText("Motion · score 0.80").assertExists()

        compose.onNodeWithText("Monitor").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()

        assertEquals("view-model must survive tab switches", 1, instances.size)
        assertEquals("initial load must not re-run on return", 1, loads)
        compose.onNodeWithText("Motion · score 0.80").assertExists()
    }
}
