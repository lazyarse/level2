package io.securitycam.level2.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.securitycam.level2.BuildConfig
import io.securitycam.level2.channels.EmailChannelSettings
import io.securitycam.level2.channels.PushoverChannel
import io.securitycam.level2.channels.PushoverChannelSettings
import io.securitycam.level2.channels.TelegramChannelSettings
import io.securitycam.level2.channels.WebhookChannelSettings
import io.securitycam.level2.channels.webhookPresets
import io.securitycam.level2.core.AnalysisResolution
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.AppSettings.Companion.withDetectorConfig
import io.securitycam.level2.core.AppSettings.Companion.withFaceRecognition
import io.securitycam.level2.core.ClipStampPosition
import io.securitycam.level2.core.DetectionSpeed
import io.securitycam.level2.core.PreviewMode
import io.securitycam.level2.core.VideoPreview
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.core.LiveViewSettings
import io.securitycam.level2.core.ScheduleWindow
import io.securitycam.level2.core.VideoQuality
import io.securitycam.level2.core.DetectorType
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.core.supportsVideoPreview
import io.securitycam.level2.ui.theme.AppButtonShape
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.detection.SensitivityScale
import io.securitycam.level2.ui.events.ZoomableSnapshotDialog
import io.securitycam.level2.ui.events.decodeUpright
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext


/** One recurring exclusion window: enable switch, day toggles, time steppers. */
@Composable
internal fun ScheduleWindowCard(
    window: ScheduleWindow,
    onChanged: (ScheduleWindow) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .testTag("scheduleWindow_${window.id}"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scheduleSummary(window),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "delete_${window.id}")
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = window.enabled,
                    onCheckedChange = { onChanged(window.copy(enabled = it)) },
                    modifier = Modifier.testTag("scheduleWindowEnabled_${window.id}"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val labels = listOf("M", "T", "W", "T", "F", "S", "S")
                for (bit in 0 until 7) {
                    val active = window.days and (1 shl bit) != 0
                    FilterChip(
                        selected = active,
                        onClick = {
                            val next = if (active) {
                                window.days and (1 shl bit).inv()
                            } else {
                                window.days or (1 shl bit)
                            }
                            onChanged(window.copy(days = next))
                        },
                        label = { Text(labels[bit]) },
                    )
                }
            }
            TimeStepperRow("Start", window.startHour, window.startMinute) { h, m ->
                onChanged(window.copy(startHour = h, startMinute = m))
            }
            TimeStepperRow("End", window.endHour, window.endMinute) { h, m ->
                onChanged(window.copy(endHour = h, endMinute = m))
            }
        }
    }
}

@Composable
internal fun TimeStepperRow(
    label: String,
    hour: Int,
    minute: Int,
    onStep: (Int, Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${fmtTime(hour, minute)}", modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
                var t = hour * 60 + minute - 15
                t = (t + 1440) % 1440
                onStep(t / 60, t % 60)
            },
        ) { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "${label}_minus") }
        IconButton(
            onClick = {
                val t = (hour * 60 + minute + 15) % 1440
                onStep(t / 60, t % 60)
            },
        ) { Icon(Icons.Outlined.AddCircleOutline, contentDescription = "${label}_plus") }
    }
}

internal fun fmtTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

internal fun scheduleSummary(window: ScheduleWindow): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val days = (0 until 7).filter { window.days and (1 shl it) != 0 }.map { names[it] }
    val dayText = when {
        days.isEmpty() -> "Never"
        days.size == 7 -> "Every day"
        else -> days.joinToString(",")
    }
    return "$dayText ${fmtTime(window.startHour, window.startMinute)}–" +
        fmtTime(window.endHour, window.endMinute) +
        if (!window.enabled) " (off)" else ""
}

@Composable
internal fun DetectorCard(
    config: DetectorConfig,
    onChanged: (DetectorConfig) -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    var expanded by rememberSaveable("detector_${config.type}") { mutableStateOf(false) }
    ExpandableCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        headerTestTag = "detectorHeader_${config.type}",
        expandContentDescription = "expand_${config.type}",
        collapseContentDescription = "collapse_${config.type}",
        chevronLabel = "chevron_detector_${config.type}",
        cardModifier = Modifier.padding(vertical = 4.dp),
        headerContent = {
                DetectorType.fromKey(config.type)?.let { dt ->
                    Icon(
                        dt.icon,
                        contentDescription = dt.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(detectorLabel(config.type))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (config.type == TriggerType.motion) {
                    // Motion is the gate source for every vision detector and
                    // cannot be disabled.
                    Text(
                        "Always on",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { v -> onChanged(config.copy(enabled = v)) },
                        modifier = Modifier.testTag("detectorEnabled_${config.type}"),
                    )
                }
            },
        bodyContent = {
                DetectorType.fromKey(config.type)?.hint?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                motionGateNote(config.type)?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                val hybrid = config.type in combinedPetOrder
                if (config.type != TriggerType.health) {
                    if (hybrid) {
                        val sightSensitivity =
                            SensitivityScale.thresholdToSensitivity(config.type, config.threshold)
                        SettingSlider(
                            label = "Sight sensitivity: $sightSensitivity/20 " +
                                "(${SensitivityScale.label(sightSensitivity)})",
                            value = sightSensitivity.toFloat(),
                            range = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            testTag = "threshold_${config.type}",
                            onChange = { v -> onChanged(config.copy(
                                threshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.roundToInt(),
                                ),
                                audioThreshold = config.audioThreshold ?: config.threshold,
                            )) },
                        )
                        val audioThreshold = config.audioThreshold ?: config.threshold
                        val soundSensitivity =
                            SensitivityScale.thresholdToSensitivity(config.type, audioThreshold)
                        SettingSlider(
                            label = "Sound sensitivity: $soundSensitivity/20 " +
                                "(${SensitivityScale.label(soundSensitivity)})",
                            value = soundSensitivity.toFloat(),
                            range = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            testTag = "audioThreshold_${config.type}",
                            onChange = { v -> onChanged(config.copy(
                                audioThreshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.roundToInt(),
                                ),
                            )) },
                        )
                    } else {
                        val sensitivity =
                            SensitivityScale.thresholdToSensitivity(config.type, config.threshold)
                        SettingSlider(
                            label = "Sensitivity: $sensitivity/20 " +
                                "(${SensitivityScale.label(sensitivity)})",
                            value = sensitivity.toFloat(),
                            range = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            testTag = "threshold_${config.type}",
                            onChange = { v -> onChanged(config.copy(
                                threshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.roundToInt(),
                                ),
                            )) },
                        )
                    }
                }
                if (config.type != TriggerType.health) {
                    StepperRow(
                        label = "Persistence: ${config.persistenceFrames} frames",
                        canDecrement = config.persistenceFrames > 1,
                        canIncrement = true,
                        onDecrement = { onChanged(config.copy(persistenceFrames = config.persistenceFrames - 1)) },
                        onIncrement = { onChanged(config.copy(persistenceFrames = config.persistenceFrames + 1)) },
                        modifier = Modifier.testTag("persistence_${config.type}"),
                    )
                    StepperRow(
                        label = "Cooldown: ${config.cooldown.toSeconds()}s",
                        canDecrement = config.cooldown.toSeconds() > 0,
                        canIncrement = config.cooldown.toSeconds() < 600,
                        onDecrement = {
                            onChanged(config.copy(cooldown = config.cooldown.minusSeconds(15)))
                        },
                        onIncrement = {
                            onChanged(config.copy(cooldown = config.cooldown.plusSeconds(15)))
                        },
                        modifier = Modifier.testTag("cooldown_${config.type}"),
                    )
                }
                if (config.type == TriggerType.loitering) {
                    StepperRow(
                        label = "Dwell time: ${config.dwellSeconds}s",
                        canDecrement = config.dwellSeconds > 3,
                        canIncrement = config.dwellSeconds < 120,
                        onDecrement = { onChanged(config.copy(dwellSeconds = config.dwellSeconds - 1)) },
                        onIncrement = { onChanged(config.copy(dwellSeconds = config.dwellSeconds + 1)) },
                        modifier = Modifier.testTag("dwell_${config.type}"),
                    )
                }
                if (config.type == TriggerType.tripwire) {
                    Text("Targets", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val targets = config.tripwireTargets
                        val options = listOf(
                            "person" to "Person",
                            "vehicle" to "Vehicle",
                            "bird" to "Bird",
                            "cat" to "Cat",
                            "dog" to "Dog",
                            "livestock" to "Livestock",
                        )
                        for ((key, label) in options) {
                            FilterChip(
                                selected = key in targets,
                                onClick = {
                                    val newTargets = if (key in targets) {
                                        targets.toMutableList().apply { remove(key) }
                                    } else {
                                        targets + key
                                    }
                                    onChanged(config.copy(tripwireTargets = newTargets))
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                extraContent()
            },
    )
}

internal fun detectorLabel(type: String): String =
    DetectorType.fromKey(type)?.label ?: type

/**
 * Fixed motion-gating behavior per detector (the pipeline rule, not a user
 * option): vision detectors sleep until motion fires, tamper must see every
 * frame, and sound is never gated on vision.
 */
internal fun motionGateNote(type: String): String? = when (type) {
    TriggerType.motion, TriggerType.health,
    TriggerType.babyCry, TriggerType.glassBreak, TriggerType.loudNoise -> null
    TriggerType.tamper -> "Runs on every frame — tamper needs to see still frames too."
    TriggerType.dog, TriggerType.cat ->
        "Sight runs after motion is detected (saves battery); sound is always listening."
    else -> "Runs after motion is detected (saves battery)."
}

/** Camera-section display order: pet/animal detectors grouped, then the rest. */
internal val cameraDetectorOrder = listOf(
    TriggerType.motion,
    TriggerType.person,
    TriggerType.face,
    TriggerType.tamper,
    TriggerType.bird,
    TriggerType.livestock,
    TriggerType.vehicle,
    TriggerType.loitering,
)

/** General-purpose sound detectors (pet sounds live under Combined). */
internal val audioGeneralOrder = listOf(
    TriggerType.loudNoise,
    TriggerType.glassBreak,
    TriggerType.babyCry,
)

/** Combined sight+sound pet detectors. */
internal val combinedPetOrder = listOf(
    TriggerType.dog,
    TriggerType.cat,
)

/** One-line explainer under each detector group heading (health has none). */
internal fun detectorGroupHint(label: String): String? = when (label) {
    "Camera" -> "Spot things the camera sees — motion, people, faces, vehicles, animals and tampering."
    "Audio" -> "Listen for sounds — loud noises, glass breaking and baby cries."
    "Combined" -> "Use the camera and microphone together — pets seen or heard."
    else -> null
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.detectorGroup(
    label: String?,
    settings: AppSettings,
    types: List<String>,
    faceExtraContent: (@Composable ColumnScope.() -> Unit)? = null,
    onChanged: (String, DetectorConfig) -> Unit,
) {
    if (label != null) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).testTag("detectorGroup_$label"),
        )
        detectorGroupHint(label)?.let { BodyText(it) }
    }
    for (type in types) {
        val config = settings.detectorConfigs[type] ?: continue
        DetectorCard(
            config = config,
            onChanged = { next -> onChanged(type, next) },
            extraContent = if (type == TriggerType.face) {
                faceExtraContent ?: {}
            } else {
                {}
            },
        )
    }
}

/**
 * Enabled detectors sharing the single YOLO inference pass (face runs on
 * MediaPipe instead; loitering/tripwire reuse other detectors' boxes).
 * Drives the multi-detector performance hint.
 */
internal val yoloDetectorTypes = setOf(
    TriggerType.person,
    TriggerType.vehicle,
    TriggerType.dog,
    TriggerType.cat,
    TriggerType.bird,
    TriggerType.livestock,
)

internal fun yoloDetectorCount(settings: AppSettings): Int =
    settings.detectorConfigs.count { (type, config) ->
        type in yoloDetectorTypes && config.enabled
    }

internal fun detectorSummary(settings: AppSettings): String {
    val shownTypes = cameraDetectorOrder.toSet() +
        audioGeneralOrder.toSet() +
        combinedPetOrder.toSet() +
        setOf(TriggerType.health)
    val total = settings.detectorConfigs.count { it.key in shownTypes }
    val active = settings.detectorConfigs.count { (type, config) ->
        type in shownTypes && config.enabled
    }
    return "$active/$total active"
}
