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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext


/**
 * Stable tag qualified by channel id (mirrors the Dart `_fieldOf(label)`
 * finder strategy, plus the account): two expanded same-type cards must
 * never share a tag.
 */
internal fun fieldTag(channelId: String, label: String): String =
    "field_${channelId}_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_")

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(testTag ?: switchTag(title)))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun switchTag(title: String): String =
    "switch_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_")

@Composable
internal fun StepperRow(
    label: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrement, enabled = canDecrement) {
            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "decrease_$label")
        }
        IconButton(onClick = onIncrement, enabled = canIncrement) {
            Icon(Icons.Outlined.AddCircleOutline, contentDescription = "increase_$label")
        }
    }
}

@Composable
internal fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

internal fun mergeLabel(window: Duration): String =
    if (window.isZero) "Off" else "${window.toSeconds()}s"

internal fun Float.round(): Int = Math.round(this)

/**
 * Collapsible settings group: tapping the header toggles a body that is only
 * composed while expanded (keeps the semantics tree and scroll height small).
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron_$title")
    Column(modifier = Modifier.animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(top = 20.dp, bottom = 8.dp)
                .testTag(sectionTag(title)),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            summary?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "collapse_$title" else "expand_$title",
                modifier = Modifier.graphicsLayer { rotationZ = chevron },
            )
        }
        if (expanded) content()
    }
}

internal fun sectionTag(title: String): String =
    "section_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_")

/** Thin overlay thumb for a vertically scrolling column; hidden when it fits. */
@Composable
internal fun ScrollbarThumb(scrollState: ScrollState, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        if (scrollState.maxValue > 0) {
            val viewport = size.height
            val thumbHeight = maxOf(
                viewport * viewport / (viewport + scrollState.maxValue),
                48.dp.toPx(),
            )
            val fraction = scrollState.value.toFloat() / scrollState.maxValue
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.50f),
                topLeft = Offset(size.width - 12.dp.toPx(), fraction * (viewport - thumbHeight)),
                size = Size(8.dp.toPx(), thumbHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

/** Zones section summary: inclusion/exclusion counts. */
internal fun zonesSummary(settings: AppSettings): String {
    val inc = settings.detectionZones.size
    val ex = settings.exclusionZones.size
    return if (inc == 0 && ex == 0) "none" else "$inc inclusion · $ex exclusion"
}

internal fun retentionSummary(days: Int): String =
    if (days == 0) "retention off" else "$days day" + if (days == 1) "" else "s"

internal fun cloudBackupSummary(cb: io.securitycam.level2.core.CloudBackupSettings): String {
    if (!cb.enabled) return "off"
    val backend = if (cb.backend == CloudBackends.S3) CloudBackends.S3 else CloudBackends.WEBDAV
    val kinds = buildList {
        if (cb.backupClips) add("clips")
        if (cb.backupSnapshots) add("snaps")
    }
    return "$backend" + if (kinds.isEmpty()) "" else " (${kinds.joinToString("+")})"
}

internal fun liveViewSummary(lv: LiveViewSettings): String {
    if (!lv.enabled) return "off"
    return if (lv.mode == LiveViewModes.SERVER) {
        val auth = if (lv.username.isNotEmpty()) " auth" else " · no password"
        "server :${lv.port}$auth"
    } else {
        val host = try {
            java.net.URI(lv.relayUrl).host ?: lv.relayUrl
        } catch (_: Exception) {
            lv.relayUrl.ifEmpty { "no relay" }
        }
        "push -> $host"
    }
}
