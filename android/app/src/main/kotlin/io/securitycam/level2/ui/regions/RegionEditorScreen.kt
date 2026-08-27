package io.securitycam.level2.ui.regions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.securitycam.level2.detection.DetectionRegion
import io.securitycam.level2.detection.DetectionRegionShape
import io.securitycam.level2.ui.monitor.PreviewSurface

private val RegionPalette = listOf(
    Color(0xFF8AB4F8),
    Color(0xFF81C995),
    Color(0xFFFDD663),
    Color(0xFFF28B82),
    Color(0xFFD7AEFB),
)

/** Distinct color for exclusion (privacy) zones. */
private val ExclusionBase = Color(0xFFEA4335)

/** Distinct color for tripwire zones. */
private val TripwireBase = Color(0xFFFF9800)

/** Palette entry for the active editor mode: red in exclusion mode, orange in tripwire mode. */
private fun regionColor(mode: RegionEditorMode, index: Int): Color =
    when (mode) {
        RegionEditorMode.exclusion -> ExclusionBase
        RegionEditorMode.tripwire -> TripwireBase
        else -> RegionPalette[index % RegionPalette.size]
    }

/**
 * Full-screen region editor over the live preview. Port of
 * `lib/ui/region_editor_screen.dart`, extended per the privacy-zones design
 * with an Inclusion/Exclusion mode toggle: both lists are edited in place
 * (exclusions rendered in red) and reported together via [onSave].
 *
 * Geometry: the preview letterboxes (FIT_CENTER) and all screen↔normalized
 * mapping goes through [fitCenterBox], so drawn regions land exactly where
 * they appear on screen AND match detector coordinates (which are normalized
 * to the analysis frame of [frameWidth]×[frameHeight]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegionEditorScreen(
    initialRegions: List<DetectionRegion>,
    onSave: (inclusions: List<DetectionRegion>, exclusions: List<DetectionRegion>, tripwires: List<DetectionRegion>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
    initialExclusions: List<DetectionRegion> = emptyList(),
    initialTripwireRegions: List<DetectionRegion> = emptyList(),
    frameWidth: Int = 320,
    frameHeight: Int = 240,
) {
    val vm = remember { RegionEditorViewModel(initialRegions, initialExclusions, initialTripwireRegions) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Detection regions") },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(vm.inclusionRegions, vm.exclusionRegions, vm.tripwireRegions)
                            onClose()
                        },
                        modifier = Modifier.testTag("regionDone"),
                    ) { Text("Done") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = vm.mode == RegionEditorMode.inclusion,
                    onClick = { vm.chooseMode(RegionEditorMode.inclusion) },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                    modifier = Modifier.testTag("regionMode_inclusion"),
                ) { Text("Inclusion") }
                SegmentedButton(
                    selected = vm.mode == RegionEditorMode.exclusion,
                    onClick = { vm.chooseMode(RegionEditorMode.exclusion) },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                    modifier = Modifier.testTag("regionMode_exclusion"),
                ) { Text("Exclusion") }
                SegmentedButton(
                    selected = vm.mode == RegionEditorMode.tripwire,
                    onClick = { vm.chooseMode(RegionEditorMode.tripwire) },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                    modifier = Modifier.testTag("regionMode_tripwire"),
                ) { Text("Tripwire") }
            }
            if (vm.mode == RegionEditorMode.tripwire) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Direction:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    listOf("in" to "Entry", "out" to "Exit", "either" to "Either").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { vm.chooseTripwireDirection(value) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (vm.tripwireDirection == value) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Unspecified
                                },
                            ),
                        ) { Text(label) }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
            EditorCanvas(vm, showPreview, frameWidth, frameHeight, Modifier.weight(1f).padding(8.dp))
            Column(Modifier.padding(12.dp)) {
                FlowRow {
                    ToolButton(
                        label = "Rectangle",
                        active = vm.shape == DetectionRegionShape.rect,
                        tag = "regionTool_rect",
                        onClick = { vm.chooseShape(DetectionRegionShape.rect) },
                    )
                    Spacer(Modifier.width(8.dp))
                    ToolButton(
                        label = "Polygon",
                        active = vm.shape == DetectionRegionShape.poly,
                        tag = "regionTool_poly",
                        onClick = { vm.chooseShape(DetectionRegionShape.poly) },
                    )
                    if (vm.pendingPoly != null) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { vm.commitPoly() },
                            modifier = Modifier.testTag("regionClosePoly"),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Close poly")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.addRegion() },
                        modifier = Modifier.testTag("regionAdd"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.testTag("regionClear"),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
                if (vm.regions.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 140.dp)) {
                        itemsIndexed(vm.regions) { i, region ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.select(i) }
                                    .testTag("regionRow_$i")
                                    .padding(vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CropSquare,
                                    contentDescription = null,
                                    tint = regionColor(vm.mode, i),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(region.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        region.shape,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = { vm.deleteAt(i) },
                                    modifier = Modifier.testTag("regionDelete_$i"),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete region")
                                }
                            }
                            if (i < vm.regions.lastIndex) {
                                androidx.compose.material3.HorizontalDivider()
                            }
                        }
                    }
                }
                val sel = vm.selected
                if (sel in vm.regions.indices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = vm.regions[sel].label,
                            onValueChange = { vm.renameSelected(it) },
                            label = { Text("Region name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("regionLabelField"),
                        )
                        IconButton(
                            onClick = { vm.deleteSelected() },
                            modifier = Modifier.testTag("regionDeleteSelected"),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete region")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all regions?") },
            text = {
                Text(
                    when (vm.mode) {
                        RegionEditorMode.exclusion ->
                            "This removes every exclusion zone. Detection will no " +
                                "longer ignore those areas."
                        RegionEditorMode.tripwire ->
                            "This removes every tripwire region. Person-crossing " +
                                "detection will no longer be active."
                        else ->
                            "This removes every inclusion region. Detection will apply " +
                                "to the whole frame."
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        vm.clearAll()
                    },
                    modifier = Modifier.testTag("regionClearConfirm"),
                ) { Text("Clear") }
            },
        )
    }
}

@Composable
private fun ToolButton(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Unspecified
            },
        ),
        modifier = Modifier.testTag(tag),
    ) { Text(label) }
}

@Composable
private fun EditorCanvas(
    vm: RegionEditorViewModel,
    showPreview: Boolean,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier,
) {
    Box(modifier) {
        if (showPreview) {
            PreviewSurface(Modifier.fillMaxSize(), fillCrop = false)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF202124)),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("regionCanvas")
                .pointerInput(Unit, frameWidth, frameHeight) {
                    val box = fitCenterBox(
                        size.width.toFloat(), size.height.toFloat(), frameWidth, frameHeight,
                    )
                    detectTapGestures { off ->
                        val (nx, ny) = screenToNorm(off.x, off.y, box)
                        vm.onTap(nx, ny)
                    }
                }
                .pointerInput(Unit, frameWidth, frameHeight) {
                    val box = fitCenterBox(
                        size.width.toFloat(), size.height.toFloat(), frameWidth, frameHeight,
                    )
                    detectDragGestures(
                        onDragStart = { off ->
                            val (nx, ny) = screenToNorm(off.x, off.y, box)
                            vm.onPanStart(nx, ny)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val (nx, ny) = screenToNorm(change.position.x, change.position.y, box)
                            vm.onPanUpdate(nx, ny)
                        },
                        onDragEnd = { vm.onPanEnd() },
                        onDragCancel = { vm.onPanEnd() },
                    )
                },
        ) {
            val b = fitCenterBox(size.width, size.height, frameWidth, frameHeight)
            vm.regions.forEachIndexed { i, r ->
                val base = regionColor(vm.mode, i)
                val fill = base.copy(alpha = 0.18f)
                val isSelected = i == vm.selected
                if (r.shape == DetectionRegionShape.rect && r.points.size >= 4) {
                    val rect = Rect(
                        left = normToScreen(r.points[0], b.offsetX, b.width),
                        top = normToScreen(r.points[1], b.offsetY, b.height),
                        right = normToScreen(r.points[2], b.offsetX, b.width),
                        bottom = normToScreen(r.points[3], b.offsetY, b.height),
                    )
                    drawRect(fill, topLeft = rect.topLeft, size = rect.size)
                    drawRect(base, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 2f))
                    if (isSelected) {
                        drawHandle(rect.topLeft)
                        drawHandle(Offset(rect.right, rect.top))
                        drawHandle(Offset(rect.left, rect.bottom))
                        drawHandle(Offset(rect.right, rect.bottom))
                    }
                } else {
                    val path = Path()
                    var k = 0
                    while (k + 1 < r.points.size) {
                        val p = Offset(
                            normToScreen(r.points[k], b.offsetX, b.width),
                            normToScreen(r.points[k + 1], b.offsetY, b.height),
                        )
                        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        k += 2
                    }
                    path.close()
                    drawPath(path, fill)
                    drawPath(path, base, style = Stroke(width = 2f))
                    if (isSelected) {
                        k = 0
                        while (k + 1 < r.points.size) {
                            drawHandle(
                                Offset(
                                    normToScreen(r.points[k], b.offsetX, b.width),
                                    normToScreen(r.points[k + 1], b.offsetY, b.height),
                                ),
                            )
                            k += 2
                        }
                    }
                }
            }
            vm.pendingPoly?.let { p ->
                if (p.size >= 2) {
                    val path = Path()
                    var k = 0
                    while (k + 1 < p.size) {
                        val pt = Offset(
                            normToScreen(p[k], b.offsetX, b.width),
                            normToScreen(p[k + 1], b.offsetY, b.height),
                        )
                        if (k == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        k += 2
                    }
                    drawPath(path, Color.White, style = Stroke(width = 1.5f))
                }
            }
            vm.dragRect?.let { dr ->
                val drRect = Rect(
                    left = normToScreen(dr[0], b.offsetX, b.width),
                    top = normToScreen(dr[1], b.offsetY, b.height),
                    right = normToScreen(dr[2], b.offsetX, b.width),
                    bottom = normToScreen(dr[3], b.offsetY, b.height),
                )
                drawRect(
                    Color.White,
                    topLeft = drRect.topLeft,
                    size = drRect.size,
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}

/** Letterboxed display area of a FIT_CENTER preview inside its view. */
internal data class DisplayBox(val offsetX: Float, val offsetY: Float, val width: Float, val height: Float)

/**
 * Geometry for mapping between screen space and frame-normalized coordinates:
 * FIT_CENTER scales the frame uniformly to fit and centers it, so the visible
 * content occupies exactly this box inside the canvas.
 */
internal fun fitCenterBox(canvasW: Float, canvasH: Float, frameW: Int, frameH: Int): DisplayBox {
    if (canvasW <= 0f || canvasH <= 0f || frameW <= 0 || frameH <= 0) {
        return DisplayBox(0f, 0f, canvasW.coerceAtLeast(1f), canvasH.coerceAtLeast(1f))
    }
    val gain = minOf(canvasW / frameW, canvasH / frameH)
    val w = frameW * gain
    val h = frameH * gain
    return DisplayBox((canvasW - w) / 2f, (canvasH - h) / 2f, w, h)
}

/** Screen point → normalized [0..1] frame coordinates, clamped to the image. */
internal fun screenToNorm(x: Float, y: Float, box: DisplayBox): Pair<Double, Double> =
    (((x - box.offsetX) / box.width).toDouble().coerceIn(0.0, 1.0)) to
        (((y - box.offsetY) / box.height).toDouble().coerceIn(0.0, 1.0))

/** Normalized [0..1] coordinate → screen position along one axis. */
internal fun normToScreen(norm: Double, offset: Float, extent: Float): Float =
    offset + (norm * extent).toFloat()

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(c: Offset) {
    drawCircle(Color.White, radius = 5f, center = c)
}
