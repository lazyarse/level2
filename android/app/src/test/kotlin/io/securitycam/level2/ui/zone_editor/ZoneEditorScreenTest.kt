package io.securitycam.level2.ui.zones

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level2.detection.DetectionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoneEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var savedInclusions: List<DetectionZone>? = null
    private var savedExclusions: List<DetectionZone>? = null
    private var savedTripwires: List<DetectionZone>? = null

    @Before
    fun setUp() {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private fun setContent() {
        compose.setContent {
            ZoneEditorScreen(
                initialZones = listOf(
                    DetectionZone(
                        id = "r1",
                        shape = "rect",
                        label = "doorway",
                        points = listOf(0.1, 0.2, 0.5, 0.8),
                    ),
                ),
                onSave = { inclusions, exclusions, tripwires ->
                    savedInclusions = inclusions
                    savedExclusions = exclusions
                    savedTripwires = tripwires
                },
                onClose = {},
                showPreview = false,
            )
        }
    }

    @Test
    fun rendersToolBarAndZoneList() {
        setContent()
        compose.onNodeWithText("Detection zones").assertIsDisplayed()
        compose.onNodeWithText("Rectangle").assertIsDisplayed()
        compose.onNodeWithText("Polygon").assertIsDisplayed()
        compose.onNodeWithText("doorway").assertIsDisplayed()
    }

    @Test
    fun doneSavesTheZoneList() {
        setContent()
        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        val out = savedInclusions
        assertNotNull(out)
        assertEquals(1, out!!.size)
        assertEquals("doorway", out!!.single().label)
        assertNotNull(savedExclusions)
    }

    @Test
    fun clearAllRemovesZonesWithConfirm() {
        setContent()
        compose.onNodeWithTag("zoneClear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clear all zones?").assertExists()
        compose.onNodeWithTag("zoneClearConfirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").assertDoesNotExist()
        assertNull(savedInclusions)
        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<DetectionZone>(), savedInclusions)
    }

    @Test
    fun modeToggleSwitchesListedContentAndSavesBothLists() {
        setContent()
        // Inclusion list shows the doorway zone.
        compose.onNodeWithText("doorway").assertIsDisplayed()

        // Switch to exclusion mode: the inclusion row disappears, exclusion
        // tools are active on an empty list.
        compose.onNodeWithTag("zoneMode_exclusion").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").assertDoesNotExist()

        // Add an exclusion zone via the Add button (default full-frame rect).
        compose.onNodeWithTag("zoneAdd").performClick()
        compose.waitForIdle()

        // Back to inclusion: doorway is listed again, exclusion zone is not.
        compose.onNodeWithTag("zoneMode_inclusion").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").assertIsDisplayed()

        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        assertEquals(1, savedInclusions!!.size)
        assertEquals(1, savedExclusions!!.size)
    }
}
