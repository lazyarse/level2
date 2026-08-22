package io.securitycam.level1.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level1.SecurityCamApp
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.storage.RecordedEventRow
import io.securitycam.level1.ui.events.EventsViewModel
import io.securitycam.level1.ui.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
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

    /** Enough same-day rows to satisfy the pager's initial-fill threshold. */
    private fun freshRows(): List<RecordedEventRow> {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return (1..25).map { i ->
            RecordedEventRow(
                id = i.toLong(),
                timestamp = today.atTime(10, i).atZone(zone).toInstant(),
                cameraName = "Hallway",
                triggerType = "motion",
                score = 0.8,
                snapshotName = null,
                videoName = null,
                channelStatuses = emptyMap(),
                triggerTypes = emptyList(),
            )
        }
    }

    @Test
    fun eventsTabDoesNotRecreateItsStateOnNavigation() {
        var loads = 0
        val instances = mutableListOf<EventsViewModel>()
        val eventsFactory = viewModelFactory {
            initializer {
                val log = freshRows()
                EventsViewModel(
                    pageLoader = { start, end ->
                        loads++
                        log.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
                    },
                    floorLoader = { log.minOf { it.timestamp } },
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
        compose.onAllNodesWithText("Motion · score 0.80", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }

        compose.onNodeWithText("Monitor").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()

        assertEquals("view-model must survive tab switches", 1, instances.size)
        assertEquals("initial load must not re-run on return", 1, loads)
        compose.onAllNodesWithText("Motion · score 0.80", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}
