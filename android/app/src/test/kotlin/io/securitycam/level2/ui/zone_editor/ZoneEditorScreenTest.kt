package io.securitycam.level2.ui.zones

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.geometry.Offset
import io.securitycam.level2.detection.DetectionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        compose.onNodeWithText("doorway").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithTag("zoneClear").performScrollTo().performClick()
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
        compose.onNodeWithText("doorway").performScrollTo().assertIsDisplayed()

        // Switch to exclusion mode: the inclusion row disappears, exclusion
        // tools are active on an empty list.
        compose.onNodeWithTag("zoneMode_exclusion").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").assertDoesNotExist()

        // Add an exclusion zone via the Add button (default full-frame rect).
        compose.onNodeWithTag("zoneAdd").performScrollTo().performClick()
        compose.waitForIdle()

        // Back to inclusion: doorway is listed again, exclusion zone is not.
        compose.onNodeWithTag("zoneMode_inclusion").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        assertEquals(1, savedInclusions!!.size)
        assertEquals(1, savedExclusions!!.size)
    }

    @Test
    fun swipeFromCornerResizesSelectedZone() {
        setContent()
        // Drag the seeded rect's bottom-right corner (0.5, 0.8) outward.
        // Corner pixels derive from the laid-out canvas via fitCenterBox —
        // the same mapping production uses.
        compose.onNodeWithTag("zoneCanvas").performTouchInput {
            val box = fitCenterBox(width.toFloat(), height.toFloat(), 320, 240)
            swipe(
                start = Offset(
                    box.offsetX + 0.5f * box.width,
                    box.offsetY + 0.8f * box.height,
                ),
                end = Offset(
                    box.offsetX + 0.7f * box.width,
                    box.offsetY + 0.9f * box.height,
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        val out = savedInclusions!!.single().points
        // Anchor held, dragged corner followed: loose bounds keep the
        // gesture-timing-tolerant assertion meaningful, not brittle.
        assertEquals(0.1, out[0], 1e-9)
        assertEquals(0.2, out[1], 1e-9)
        assertTrue("x1 grew toward 0.7 but was ${out[2]}", out[2] > 0.55)
        assertTrue("y1 grew toward 0.9 but was ${out[3]}", out[3] > 0.82)
        assertTrue("stays ordered", out[0] < out[2] && out[1] < out[3])
        assertTrue("stays clamped", out.all { it in 0.0..1.0 })
    }

    @Test
    fun canvasIgnoresOuterRelayout() {
        // The canvas is frame-aspect-fixed: showing/hiding the mode, list,
        // and rename rows (or any outer relayout) must not move the preview
        // under the finger — that jump was the reported bug.
        var editorHeight by mutableStateOf(200.dp)
        var saved: List<DetectionZone>? = null
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
                onSave = { inclusions, _, _ -> saved = inclusions },
                onClose = {},
                modifier = Modifier.height(editorHeight),
                showPreview = false,
            )
        }
        compose.waitForIdle()
        // Unclipped layout size (boundsInRoot is viewport-clipped when the
        // editor scrolls — that clipping is expected, not a failure).
        val h1 = compose.onNodeWithTag("zoneCanvas")
            .fetchSemanticsNode().size.height

        compose.runOnIdle { editorHeight = 480.dp }
        compose.waitForIdle()
        val h2 = compose.onNodeWithTag("zoneCanvas")
            .fetchSemanticsNode().size.height
        assertEquals(h1, h2)

        // Drag corner 2 outward; the mapping is unchanged so this resizes.
        compose.onNodeWithTag("zoneCanvas").performTouchInput {
            val box = fitCenterBox(width.toFloat(), height.toFloat(), 320, 240)
            swipe(
                start = Offset(
                    box.offsetX + 0.5f * box.width,
                    box.offsetY + 0.8f * box.height,
                ),
                end = Offset(
                    box.offsetX + 0.75f * box.width,
                    box.offsetY + 0.95f * box.height,
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("zoneDone").performClick()
        compose.waitForIdle()
        val zones = saved!!
        assertEquals(1, zones.size)
        val p = zones.single().points
        // Anchor held: a body move would have shifted x0/y0 by the drag delta.
        assertEquals(0.1, p[0], 1e-9)
        assertEquals(0.2, p[1], 1e-9)
        assertTrue("x1 grew but was ${p[2]}", p[2] > 0.55)
        assertTrue("stays ordered", p[0] < p[2] && p[1] < p[3])
    }
}
