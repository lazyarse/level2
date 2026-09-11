package io.securitycam.level2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.securitycam.level2.channels.AlertLog
import io.securitycam.level2.channels.AlertLogEntry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric tests for the alert-log viewer (AlertLog is process-global). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertLogScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun clearBefore() = runBlocking { AlertLog.clear() }

    @After
    fun clearAfter() = runBlocking { AlertLog.clear() }

    @Test
    fun emptyStateShowsWhenClean() {
        compose.setContent { AlertLogScreen(onClose = {}) }
        compose.waitForIdle()

        compose.onNodeWithTag("alertLogEmpty").assertIsDisplayed()
    }

    @Test
    fun rowsRenderAndClearEmpties() {
        runBlocking {
            AlertLog.add(
                AlertLogEntry(Instant.EPOCH, "log", "motion", "Motion detected in Hallway"),
            )
        }
        compose.setContent { AlertLogScreen(onClose = {}) }
        compose.waitForIdle()

        compose.onNodeWithText("Motion detected in Hallway").assertIsDisplayed()
        compose.onNodeWithTag("clearAlertLog").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("alertLogEmpty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("alertLogEmpty").assertIsDisplayed()
    }
}
