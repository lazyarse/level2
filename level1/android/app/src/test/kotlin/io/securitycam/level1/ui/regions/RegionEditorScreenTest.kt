package io.securitycam.level1.ui.regions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.securitycam.level1.detection.DetectionRegion
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
class RegionEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: List<DetectionRegion>? = null

    @Before
    fun setUp() {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
    }

    private fun setContent() {
        compose.setContent {
            RegionEditorScreen(
                initialRegions = listOf(
                    DetectionRegion(
                        id = "r1",
                        shape = "rect",
                        label = "doorway",
                        points = listOf(0.1, 0.2, 0.5, 0.8),
                    ),
                ),
                onSave = { saved = it },
                onClose = {},
                showPreview = false,
            )
        }
    }

    @Test
    fun rendersToolBarAndRegionList() {
        setContent()
        compose.onNodeWithText("Detection regions").assertIsDisplayed()
        compose.onNodeWithText("Rectangle").assertIsDisplayed()
        compose.onNodeWithText("Polygon").assertIsDisplayed()
        compose.onNodeWithText("doorway").assertIsDisplayed()
    }

    @Test
    fun doneSavesTheRegionList() {
        setContent()
        compose.onNodeWithTag("regionDone").performClick()
        compose.waitForIdle()
        val out = saved
        assertNotNull(out)
        assertEquals(1, out!!.size)
        assertEquals("doorway", out!!.single().label)
    }

    @Test
    fun clearAllRemovesRegionsWithConfirm() {
        setContent()
        compose.onNodeWithTag("regionClear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clear all regions?").assertExists()
        compose.onNodeWithTag("regionClearConfirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("doorway").assertDoesNotExist()
        assertNull(saved)
        compose.onNodeWithTag("regionDone").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<DetectionRegion>(), saved)
    }

}
