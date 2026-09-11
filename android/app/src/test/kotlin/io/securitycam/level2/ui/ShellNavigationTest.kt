package io.securitycam.level2.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.SecurityCamApp
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.storage.RecordedEventRow
import io.securitycam.level2.ui.events.EventsViewModel
import io.securitycam.level2.ui.settings.SettingsViewModel
import io.securitycam.level2.ui.settings.sectionTag
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
        compose.onAllNodesWithText("Motion", substring = false)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("Confidence: High", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }

        compose.onNodeWithText("Monitor").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()

        assertEquals("view-model must survive tab switches", 1, instances.size)
        assertEquals("initial load must not re-run on return", 1, loads)
        compose.onAllNodesWithText("Motion", substring = false)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("Confidence: High", substring = true)
            .fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun settingsAppWithBack() {
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
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner()) {
                SecurityCamApp(settingsFactory = settingsFactory)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
    }

    @Test
    fun systemBackDismissesAlertLogViewer() {
        settingsAppWithBack()

        compose.onNodeWithTag(sectionTag("Advanced")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("openAlertLog").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithTag(sectionTag("Advanced")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackDismissesZoneEditor() {
        settingsAppWithBack()

        compose.onNodeWithTag(sectionTag("Zones")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No zones — detecting everywhere").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Detection zones").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Detection zones").assertDoesNotExist()
    }

    private class HangingCoordinator(app: android.app.Application) :
        io.securitycam.level2.identity.FaceEnrollmentCoordinator(
            store = io.securitycam.level2.identity.KnownFaceStore(
                java.io.File(app.filesDir, "kf-hang-${System.nanoTime()}"),
            ),
            embedder = null,
            faceFinder = io.securitycam.level2.identity.FaceFinder { null },
            settingsLoader = { AppSettings() },
            settingsSaver = { },
        ) {
        override suspend fun enroll(label: String): Result<io.securitycam.level2.core.KnownFace> {
            kotlinx.coroutines.awaitCancellation()
        }
    }

    @Test
    fun systemBackCancelsEnrollment() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val instances = mutableListOf<SettingsViewModel>()
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsLoader = { AppSettings() },
                    settingsSaver = { },
                    eventsClearer = { _ -> },
                    enrollmentFactory = { _ -> HangingCoordinator(app) },
                    cameraActive = { true },
                ).also { instances.add(it) }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner()) {
                SecurityCamApp(settingsFactory = settingsFactory)
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { instances.single().startEnrollment("Bob") }
        compose.waitForIdle()
        compose.onNodeWithText("Enrol face").assertIsDisplayed()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Enrol face").assertDoesNotExist()
        // Same path as the Cancel button: the job is cancelled and the
        // ViewModel reports it (the snackbar host lives on the Settings
        // screen, covered by the send-test snackbar test).
        org.junit.Assert.assertEquals(
            "Enrollment cancelled",
            instances.single().message.value,
        )
    }

    private fun backOwner(): OnBackPressedDispatcherOwner {
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        return object : OnBackPressedDispatcherOwner, LifecycleOwner by lifecycleOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
                .also { backDispatcher = it }
        }
    }
}
