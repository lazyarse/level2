package io.securitycam.level2.ui.zones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
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
import android.view.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.securitycam.level2.camera_service.CameraRotations
import io.securitycam.level2.core.ScreenOrientation
import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.DetectionZoneShape
import io.securitycam.level2.ui.monitor.PreviewSurface
import io.securitycam.level2.ui.monitor.LANDSCAPE_PREVIEW_ASPECT
import io.securitycam.level2.ui.monitor.ZoneDisplayMapper
import io.securitycam.level2.ui.theme.AppButtonShape

private val ZonePalette = listOf(
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
private fun zoneColor(mode: ZoneEditorMode, index: Int): Color =
    when (mode) {
        ZoneEditorMode.exclusion -> ExclusionBase
        ZoneEditorMode.tripwire -> TripwireBase
        else -> ZonePalette[index % ZonePalette.size]
    }

/**
 * Full-screen zone editor over the live preview. Port of
 * `lib/ui/zone_editor_screen.dart`, extended per the privacy-zones design
 * with an Inclusion/Exclusion mode toggle: both lists are edited in place
 * (exclusions rendered in red) and reported together via [onSave].
 *
 * Geometry: stored zones are normalized to the analysis frame; both
 * directions convert through [ZoneDisplayMapper] with the capture rotation
 * ([mapPoint] for draw, [unmapPoint] for touches), so drawn zones land
 * exactly where they appear on screen AND match detector coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ZoneEditorScreen(
    initialZones: List<DetectionZone>,
    onSave: (inclusions: List<DetectionZone>, exclusions: List<DetectionZone>, tripwires: List<DetectionZone>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
    initialExclusions: List<DetectionZone> = emptyList(),
    initialTripwireZones: List<DetectionZone> = emptyList(),
    frameWidth: Int = 320,
    frameHeight: Int = 240,
    captureOrientation: String = ScreenOrientation.sensor,
) {
    val vm = remember { ZoneEditorViewModel(initialZones, initialExclusions, initialTripwireZones) }
    var confirmClear by remember { mutableStateOf(false) }
    // Capture rotation keys both directions: touches unmap into
    // analysis-normalized storage, stored zones map back for draw. R=0
    // reproduces the legacy fitCenterBox/screenToNorm math exactly.
    val displayRotationDegrees = runCatching { LocalContext.current.display?.rotation }
        .getOrNull().let {
            when (it) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
    val captureRotation =
        CameraRotations.resolveCapture(captureOrientation, displayRotationDegrees) * 90

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Detection zones") },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(vm.inclusionZones, vm.exclusionZones, vm.tripwireZones)
                            onClose()
                        },
                        modifier = Modifier.testTag("zoneDone"),
                        shape = AppButtonShape,
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
                    selected = vm.mode == ZoneEditorMode.inclusion,
                    onClick = { vm.chooseMode(ZoneEditorMode.inclusion) },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                    modifier = Modifier.testTag("zoneMode_inclusion"),
                ) { Text("Inclusion") }
                SegmentedButton(
                    selected = vm.mode == ZoneEditorMode.exclusion,
                    onClick = { vm.chooseMode(ZoneEditorMode.exclusion) },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                    modifier = Modifier.testTag("zoneMode_exclusion"),
                ) { Text("Exclusion") }
                SegmentedButton(
                    selected = vm.mode == ZoneEditorMode.tripwire,
                    onClick = { vm.chooseMode(ZoneEditorMode.tripwire) },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                    modifier = Modifier.testTag("zoneMode_tripwire"),
                ) { Text("Tripwire") }
            }
            if (vm.mode == ZoneEditorMode.tripwire) {
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
                            shape = AppButtonShape,
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
            if (captureRotation == 90) {
                // Landscape stream in a portrait UI: letterbox a landscape
                // box so PreviewView geometry matches the zone mapping.
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EditorCanvas(
                        vm,
                        showPreview,
                        frameWidth,
                        frameHeight,
                        captureRotation,
                        Modifier.fillMaxWidth()
                            .aspectRatio(LANDSCAPE_PREVIEW_ASPECT)
                            .padding(8.dp),
                    )
                }
            } else {
                EditorCanvas(
                    vm,
                    showPreview,
                    frameWidth,
                    frameHeight,
                    captureRotation,
                    Modifier.weight(1f).padding(8.dp),
                )
            }
            Column(Modifier.padding(12.dp)) {
                FlowRow {
                    ToolButton(
                        label = "Rectangle",
                        active = vm.shape == DetectionZoneShape.rect,
                        tag = "zoneTool_rect",
                        onClick = { vm.chooseShape(DetectionZoneShape.rect) },
                    )
                    Spacer(Modifier.width(8.dp))
                    ToolButton(
                        label = "Polygon",
                        active = vm.shape == DetectionZoneShape.poly,
                        tag = "zoneTool_poly",
                        onClick = { vm.chooseShape(DetectionZoneShape.poly) },
                    )
                    if (vm.pendingPoly != null) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { vm.commitPoly() },
                            modifier = Modifier.testTag("zoneClosePoly"),
                            shape = AppButtonShape,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Close poly")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.addZone() },
                        modifier = Modifier.testTag("zoneAdd"),
                        shape = AppButtonShape,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.testTag("zoneClear"),
                        shape = AppButtonShape,
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
                if (vm.zones.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 140.dp)) {
                        itemsIndexed(vm.zones) { i, zone ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.select(i) }
                                    .testTag("zoneRow_$i")
                                    .padding(vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CropSquare,
                                    contentDescription = null,
                                    tint = zoneColor(vm.mode, i),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(zone.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        zone.shape,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = { vm.deleteAt(i) },
                                    modifier = Modifier.testTag("zoneDelete_$i"),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete zone")
                                }
                            }
                            if (i < vm.zones.lastIndex) {
                                androidx.compose.material3.HorizontalDivider()
                            }
                        }
                    }
                }
                val sel = vm.selected
                if (sel in vm.zones.indices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = vm.zones[sel].label,
                            onValueChange = { vm.renameSelected(it) },
                            label = { Text("Zone name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("zoneLabelField"),
                        )
                        IconButton(
                            onClick = { vm.deleteSelected() },
                            modifier = Modifier.testTag("zoneDeleteSelected"),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete zone")
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all zones?") },
            text = {
                Text(
                    when (vm.mode) {
                        ZoneEditorMode.exclusion ->
                            "This removes every exclusion zone. Detection will no " +
                                "longer ignore those areas."
                        ZoneEditorMode.tripwire ->
                            "This removes every tripwire zone. Person-crossing " +
                                "detection will no longer be active."
                        else ->
                            "This removes every inclusion zone. Detection will apply " +
                                "to the whole frame."
                    },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    shape = AppButtonShape,
                ) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        vm.clearAll()
                    },
                    modifier = Modifier.testTag("zoneClearConfirm"),
                    shape = AppButtonShape,
                ) { Text("Clear") }
            },
        )
    }
}

@Composable
private fun ToolButton(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = AppButtonShape,
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
    vm: ZoneEditorViewModel,
    showPreview: Boolean,
    frameWidth: Int,
    frameHeight: Int,
    captureRotation: Int,
    modifier: Modifier,
) {
    val frameAspect = frameWidth.toFloat() / frameHeight.coerceAtLeast(1).toFloat()
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
                .testTag("zoneCanvas")
                .pointerInput(Unit, frameWidth, frameHeight, captureRotation) {
                    detectTapGestures { off ->
                        val p = ZoneDisplayMapper.unmapPoint(
                            off.x, off.y, captureRotation,
                            size.width.toFloat(), size.height.toFloat(), frameAspect,
                        )
                        vm.onTap(p.x.toDouble(), p.y.toDouble())
                    }
                }
                .pointerInput(Unit, frameWidth, frameHeight, captureRotation) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val p = ZoneDisplayMapper.unmapPoint(
                                off.x, off.y, captureRotation,
                                size.width.toFloat(), size.height.toFloat(), frameAspect,
                            )
                            vm.onPanStart(p.x.toDouble(), p.y.toDouble())
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = ZoneDisplayMapper.unmapPoint(
                                change.position.x, change.position.y, captureRotation,
                                size.width.toFloat(), size.height.toFloat(), frameAspect,
                            )
                            vm.onPanUpdate(p.x.toDouble(), p.y.toDouble())
                        },
                        onDragEnd = { vm.onPanEnd() },
                        onDragCancel = { vm.onPanEnd() },
                    )
                },
        ) {
            // Stored zones are analysis-normalized; map them into the canvas
            // with the same capture rotation the touches were stored with.
            fun mapZone(nx: Double, ny: Double): Offset =
                ZoneDisplayMapper.mapPoint(
                    nx.toFloat(), ny.toFloat(), captureRotation,
                    size.width, size.height, frameAspect,
                )
            vm.zones.forEachIndexed { i, r ->
                val base = zoneColor(vm.mode, i)
                val fill = base.copy(alpha = 0.18f)
                val isSelected = i == vm.selected
                if (r.shape == DetectionZoneShape.rect && r.points.size >= 4) {
                    val p0 = mapZone(r.points[0], r.points[1])
                    val p1 = mapZone(r.points[2], r.points[3])
                    val rect = Rect(
                        left = minOf(p0.x, p1.x),
                        top = minOf(p0.y, p1.y),
                        right = kotlin.math.max(p0.x, p1.x),
                        bottom = kotlin.math.max(p0.y, p1.y),
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
                        val p = mapZone(r.points[k], r.points[k + 1])
                        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        k += 2
                    }
                    path.close()
                    drawPath(path, fill)
                    drawPath(path, base, style = Stroke(width = 2f))
                    if (isSelected) {
                        k = 0
                        while (k + 1 < r.points.size) {
                            drawHandle(mapZone(r.points[k], r.points[k + 1]))
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
                        val pt = mapZone(p[k], p[k + 1])
                        if (k == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        k += 2
                    }
                    drawPath(path, Color.White, style = Stroke(width = 1.5f))
                }
            }
            vm.dragRect?.let { dr ->
                val d0 = mapZone(dr[0], dr[1])
                val d1 = mapZone(dr[2], dr[3])
                val drRect = Rect(
                    left = minOf(d0.x, d1.x),
                    top = minOf(d0.y, d1.y),
                    right = kotlin.math.max(d0.x, d1.x),
                    bottom = kotlin.math.max(d0.y, d1.y),
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(c: Offset) {
    drawCircle(Color.White, radius = 5f, center = c)
}
