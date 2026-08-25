package io.securitycam.level2.detection.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/yolo_person_engine_test.dart`. */
class YoloPostprocessTest {

    companion object {
        const val ANCHORS = 8400
        const val CLASSES = 80
    }

    private fun blankOutput(): FloatArray = FloatArray((CLASSES + 4) * ANCHORS)

    /** cx, cy, w, h normalized [0,1]; person score in row 4. */
    private fun personAt(i: Int, score: Double = 0.9): FloatArray {
        val out = blankOutput()
        out[i] = 0.5f
        out[ANCHORS + i] = 0.5f
        out[2 * ANCHORS + i] = 0.4f
        out[3 * ANCHORS + i] = 0.8f
        out[4 * ANCHORS + i] = score.toFloat()
        return out
    }

    /** cx, cy, w, h normalized [0,1]; dog score in row 16. */
    private fun dogAt(i: Int, score: Double = 0.9): FloatArray {
        val out = blankOutput()
        out[i] = 0.3f
        out[ANCHORS + i] = 0.6f
        out[2 * ANCHORS + i] = 0.2f
        out[3 * ANCHORS + i] = 0.3f
        out[(4 + YoloClasses.DOG) * ANCHORS + i] = score.toFloat()
        return out
    }

    @Test
    fun squareFrameMaps1to1WithNoPadding() {
        val info = letterboxInfo(640, 640)
        assertEquals(1.0, info.gain, 0.0)
        assertEquals(0, info.padX)
        assertEquals(0, info.padY)
    }

    @Test
    fun frame320x240ScalesTo640x480WithCenteredVerticalPadding() {
        val info = letterboxInfo(320, 240)
        assertEquals(2.0, info.gain, 0.0)
        assertEquals(0, info.padX)
        assertEquals(80, info.padY)
    }

    @Test
    fun decodesOneAnchoredPersonIntoFrameCoordinates() {
        val boxes = decodeYolo26(
            personAt(0),
            conf = 0.25,
            iou = 0.7,
            maxDetections = 30,
            frameWidth = 640,
            frameHeight = 640,
        )
        assertEquals(1, boxes.size)
        val b = boxes.single()
        assertEquals(192.0, b.x1, 0.01) // (0.5 - 0.2) * 640
        assertEquals(64.0, b.y1, 0.01) // (0.5 - 0.4) * 640
        assertEquals(448.0, b.x2, 0.01) // (0.5 + 0.2) * 640
        assertEquals(576.0, b.y2, 0.01) // (0.5 + 0.4) * 640
        assertEquals(0.9, b.score, 1e-6)
    }

    @Test
    fun undoesLetterboxPaddingAndScaleBackToTheOriginalFrame() {
        val out = blankOutput()
        out[0] = 0.5f // cx
        out[ANCHORS] = 0.5f // cy
        out[2 * ANCHORS] = 0.3f // w
        out[3 * ANCHORS] = 0.3f // h
        out[4 * ANCHORS] = 0.9f
        val boxes = decodeYolo26(
            out,
            conf = 0.25,
            iou = 0.7,
            maxDetections = 30,
            frameWidth = 320,
            frameHeight = 240,
        )
        // Box centered on the frame: (0.5*320, 0.5*240).
        val b = boxes.single()
        assertEquals(112.0, b.x1, 0.01) // (0.35*640 - 0) / 2
        assertEquals(72.0, b.y1, 0.01) // (0.35*640 - 80) / 2
        assertEquals(208.0, b.x2, 0.01) // (0.65*640 - 0) / 2
        assertEquals(168.0, b.y2, 0.01) // (0.65*640 - 80) / 2
    }

    @Test
    fun dropsAnchorsBelowTheConfidenceGate() {
        val boxes = decodeYolo26(
            personAt(0, score = 0.2),
            conf = 0.25,
            iou = 0.7,
            maxDetections = 30,
            frameWidth = 640,
            frameHeight = 640,
        )
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun clampsOutOfFrameBoxes() {
        val out = blankOutput()
        out[0] = 0.9f // cx
        out[ANCHORS] = 0.9f // cy
        out[2 * ANCHORS] = 0.4f // w
        out[3 * ANCHORS] = 0.4f // h
        out[4 * ANCHORS] = 0.9f
        val boxes = decodeYolo26(
            out,
            conf = 0.25,
            iou = 0.7,
            maxDetections = 30,
            frameWidth = 320,
            frameHeight = 240,
        )
        val b = boxes.single()
        assertTrue(b.x1 >= 0)
        assertTrue(b.y1 >= 0)
        assertTrue(b.x2 <= 320)
        assertTrue(b.y2 <= 240)
    }

    private val a = PersonBox(10.0, 10.0, 100.0, 100.0, 0.9)
    private val b = PersonBox(12.0, 12.0, 98.0, 98.0, 0.5)
    private val c = PersonBox(300.0, 300.0, 400.0, 400.0, 0.7)

    @Test
    fun keepsOnlyTheHigherScoringOfTwoOverlappingBoxes() {
        val kept = nms(listOf(a, b), iou = 0.7, maxDetections = 30)
        assertEquals(1, kept.size)
        assertEquals(0.9, kept.single().score, 0.0)
    }

    @Test
    fun keepsFarApartBoxes() {
        val kept = nms(listOf(a, c), iou = 0.7, maxDetections = 30)
        assertEquals(2, kept.size)
    }

    @Test
    fun respectsMaxDetections() {
        val kept = nms(listOf(a, c), iou = 0.1, maxDetections = 1)
        assertEquals(1, kept.size)
    }

    @Test
    fun decodeYoloClassesReadsDogClass16() {
        val boxes = decodeYoloClasses(
            dogAt(0),
            classIndices = listOf(YoloClasses.DOG),
            conf = 0.25,
            iou = 0.7,
            maxDetections = 10,
            frameWidth = 640,
            frameHeight = 640,
        )
        assertEquals(1, boxes.size)
        assertEquals(0.9, boxes.single().score, 1e-6)
    }

    @Test
    fun decodeYoloClassesIgnoresPersonWhenOnlyDogRequested() {
        val out = personAt(0, score = 0.9)
        val boxes = decodeYoloClasses(
            out,
            classIndices = listOf(YoloClasses.DOG),
            conf = 0.25,
            iou = 0.7,
            maxDetections = 10,
            frameWidth = 640,
            frameHeight = 640,
        )
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun decodeYoloClassesCanReadMultipleClasses() {
        val out = blankOutput()
        // Person at anchor 0
        out[0] = 0.5f; out[ANCHORS] = 0.5f; out[2 * ANCHORS] = 0.4f; out[3 * ANCHORS] = 0.8f
        out[4 * ANCHORS] = 0.9f
        // Dog at anchor 1
        out[1] = 0.3f; out[ANCHORS + 1] = 0.6f; out[2 * ANCHORS + 1] = 0.2f; out[3 * ANCHORS + 1] = 0.3f
        out[(4 + YoloClasses.DOG) * ANCHORS + 1] = 0.8f
        val boxes = decodeYoloClasses(
            out,
            classIndices = listOf(YoloClasses.PERSON, YoloClasses.DOG),
            conf = 0.25,
            iou = 0.7,
            maxDetections = 10,
            frameWidth = 640,
            frameHeight = 640,
        )
        assertEquals(2, boxes.size)
    }
}