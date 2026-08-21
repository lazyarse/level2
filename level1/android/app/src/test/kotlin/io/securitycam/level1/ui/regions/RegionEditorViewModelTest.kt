package io.securitycam.level1.ui.regions

import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectionRegionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionEditorViewModelTest {

    private fun vmWithDoorway(): RegionEditorViewModel = RegionEditorViewModel(
        listOf(
            DetectionRegion(
                id = "r0",
                shape = DetectionRegionShape.rect,
                label = "doorway",
                points = listOf(0.1, 0.2, 0.5, 0.8),
            ),
        ),
    )

    private fun assertPoints(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertTrue("expected $expected but was $actual", kotlin.math.abs(e - a) < 1e-9)
        }
    }

    @Test
    fun drawingRectCommitsWhenLargeEnough() {
        val vm = vmWithDoorway()
        vm.onPanStart(0.05, 0.05)
        vm.onPanUpdate(0.45, 0.55)
        vm.onPanEnd()
        assertEquals(2, vm.regions.size)
        val drawn = vm.regions.last()
        assertEquals(DetectionRegionShape.rect, drawn.shape)
        assertEquals(listOf(0.05, 0.05, 0.45, 0.55), drawn.points)
        assertEquals(1, vm.selected)
        assertNull(vm.dragRect)
    }

    @Test
    fun tinyDragsAreIgnored() {
        val vm = vmWithDoorway()
        vm.onPanStart(0.5, 0.5)
        vm.onPanUpdate(0.51, 0.51)
        vm.onPanEnd()
        assertEquals(1, vm.regions.size)
    }

    @Test
    fun dragOnEmptySpaceInPolyModeDoesNotDraw() {
        val vm = vmWithDoorway()
        vm.chooseShape(DetectionRegionShape.poly)
        vm.onPanStart(0.05, 0.05)
        vm.onPanUpdate(0.45, 0.55)
        vm.onPanEnd()
        assertEquals(1, vm.regions.size)
    }

    @Test
    fun movingTranslatesPointsAndClamps() {
        val vm = vmWithDoorway()
        vm.onPanStart(0.3, 0.5) // inside doorway, not near a corner
        vm.onPanUpdate(0.4, 0.6) // +0.1/+0.1
        vm.onPanEnd()
        assertPoints(listOf(0.2, 0.3, 0.6, 0.9), vm.regions[0].points)
    }

    @Test
    fun movingClampsToUnitSquare() {
        val vm = vmWithDoorway()
        vm.onPanStart(0.3, 0.5)
        vm.onPanUpdate(0.95, 0.95) // would push x1 to 1.15 / y1 to 1.25
        vm.onPanEnd()
        assertPoints(listOf(0.75, 0.65, 1.0, 1.0), vm.regions[0].points)
    }

    @Test
    fun cornerDragResizes() {
        val vm = vmWithDoorway()
        // (0.1, 0.2) is the rect's first corner; tolerance is 0.06.
        vm.onPanStart(0.12, 0.22)
        vm.onPanUpdate(0.02, 0.05)
        vm.onPanEnd()
        // Dart parity: the dragged position always lands in slots 2/3.
        assertPoints(listOf(0.1, 0.2, 0.02, 0.05), vm.regions[0].points)
    }

    @Test
    fun polyModeTapsCollectVerticesAndCommitRequiresThree() {
        val vm = vmWithDoorway()
        vm.chooseShape(DetectionRegionShape.poly)

        vm.onTap(0.1, 0.1)
        vm.onTap(0.9, 0.1)
        vm.commitPoly()
        assertEquals(1, vm.regions.size) // two vertices are not enough

        // A failed commit discards the pending poly (Dart parity), so the
        // successful shape needs all three vertices tapped afresh.
        vm.onTap(0.1, 0.1)
        vm.onTap(0.9, 0.1)
        vm.onTap(0.5, 0.9)
        vm.commitPoly()
        assertEquals(2, vm.regions.size)
        val poly = vm.regions.last()
        assertEquals(DetectionRegionShape.poly, poly.shape)
        assertEquals(listOf(0.1, 0.1, 0.9, 0.1, 0.5, 0.9), poly.points)
        assertNull(vm.pendingPoly)
    }

    @Test
    fun deleteAdjustsSelectionIndex() {
        val vm = vmWithDoorway()
        vm.addRegion()
        vm.addRegion()
        assertEquals(3, vm.regions.size)
        vm.select(1)
        vm.deleteAt(1)
        assertEquals(-1, vm.selected)
        vm.select(1)
        vm.deleteAt(0) // selected index shifts down
        assertEquals(0, vm.selected)
        assertTrue(vm.regions[vm.selected].label.isNotEmpty())
    }

    @Test
    fun renameKeepsPreviousLabelWhenBlank() {
        val vm = vmWithDoorway()
        vm.select(0)
        vm.renameSelected("  front door ")
        assertEquals("front door", vm.regions[0].label)
        vm.renameSelected("   ")
        assertEquals("front door", vm.regions[0].label)
    }

    @Test
    fun clearAllEmptiesRegions() {
        val vm = vmWithDoorway()
        vm.select(0)
        vm.clearAll()
        assertEquals(0, vm.regions.size)
        assertEquals(-1, vm.selected)
        assertNull(vm.pendingPoly)
    }
}
