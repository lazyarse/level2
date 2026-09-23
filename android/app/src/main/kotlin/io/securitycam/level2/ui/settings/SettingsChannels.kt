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
import androidx.compose.material3.ExperimentalMaterial3Api
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


@Composable
internal fun ChannelCard(
    config: io.securitycam.level2.core.ChannelConfig,
    fields: MutableMap<String, String>,
    siblings: List<io.securitycam.level2.core.ChannelConfig>,
    onEnabledChange: (Boolean) -> Unit,
    onLabelChange: (String) -> Unit,
    onSendTest: (io.securitycam.level2.core.ChannelConfig) -> Unit,
    onDelete: () -> Unit,
    inFlight: Boolean,
    sendingDisabled: Boolean,
    factories: Map<String, io.securitycam.level2.event.ChannelFactory>,
    onPreviewChange: (Boolean) -> Unit = {},
    onFrequencyChange: (String, Int) -> Unit = { _, _ -> },
    testPreviewUrl: String? = null,
) {
    var expanded by rememberSaveable("channel_${config.id}") { mutableStateOf(false) }
    var confirmDelete by rememberSaveable("delete_${config.id}") { mutableStateOf(false) }
    val name = channelDisplayName(config, siblings)
    ExpandableCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        headerTestTag = "channelHeader_${config.id}",
        expandContentDescription = "expand_${config.id}",
        collapseContentDescription = "collapse_${config.id}",
        chevronLabel = "chevron_${config.id}",
        cardModifier = Modifier
            .padding(vertical = 4.dp)
            .testTag("channelCard_${config.id}"),
        contentSpacing = 8.dp,
        headerContent = {
                Icon(
                    channelIcon(config.type),
                    contentDescription = name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(name, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (config.type in multiAccountTypes) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete $name",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("channelEnabled_${config.id}"),
                )
            },
        bodyContent = {
                ChannelBody(
                    config = config,
                    fields = fields,
                    onLabelChange = onLabelChange,
                )
                SwitchRow(
                    title = "Push video preview",
                    subtitle = "Send a short preview of each clip along with the alert " +
                        "(channels that cannot attach files silently skip it in the runtime).",
                    checked = config.pushVideoPreview,
                    onCheckedChange = onPreviewChange,
                    testTag = "channelPreview_${config.id}",
                )
                DropdownField(
                    label = "Alert frequency",
                    selected = alertFrequencyLabel(config),
                    options = alertFrequencyOptions.map { "${it.mode}:${it.seconds}" to it.label },
                    testTag = "channelFrequency_${config.id}",
                    onSelect = { value ->
                        val mode = value.substringBefore(":")
                        val seconds = value.substringAfter(":").toIntOrNull() ?: 60
                        onFrequencyChange(mode, seconds)
                    },
                )
                // Snapshot-aware validation: derivedStateOf subscribes to the
                // fields map reads inside the calculation, so this recomputes
                // only when field contents change — not on every keystroke-driven
                // recomposition of the card (the old
                // remember(..., fields.toMap()) key rebuilt the map — a new
                // instance — on every recomposition, re-running validation).
                val draftError by remember(config.id, config.type) {
                    derivedStateOf {
                        val snapshot = fields.toMap()
                        val merged = buildChannelConfigs(listOf(config), snapshot).first()
                        val channel = factories[merged.type]?.invoke(merged)
                        if (channel == null) "Unknown channel type ${merged.type}"
                        else channel.validate() ?: emailPortError(config.id, snapshot)
                            ?: pushoverNumericError(config.id, snapshot)
                    }
                }
                // Local copy: delegated properties don't smart-cast.
                val validationError = draftError
                val draftValid = validationError == null
                OutlinedButton(
                    onClick = {
                        onSendTest(buildChannelConfigs(listOf(config), fields).first())
                    },
                    // Disabled while ANY channel's test is in flight (the
                    // ViewModel drops concurrent taps, so every card must show
                    // it); the label stays per-card.
                    enabled = draftValid && !sendingDisabled,
                    modifier = Modifier.testTag("sendTest_${config.id}"),
                    shape = AppButtonShape,
                ) {
                    Text(if (inFlight) "Sending…" else "Send test")
                }
                if (validationError != null) {
                    Text(
                        text = validationError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("sendTestError_${config.id}"),
                    )
                }
                if (config.type == ChannelTypes.EMAIL && testPreviewUrl != null) {
                    TestPreviewRow(url = testPreviewUrl, channelId = config.id)
                }
                Spacer(Modifier.height(8.dp))
            },
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete $name?",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                onDelete()
                confirmDelete = false
            },
            confirmLabel = "Delete",
            confirmTestTag = "confirmDeleteChannel_${config.id}",
            body = "Remove this ${channelTitle(config.type)} account?",
        )
    }
}

/** Expanded channel card contents: type-specific fields plus the test sender. */
@Composable
internal fun ChannelBody(
    config: io.securitycam.level2.core.ChannelConfig,
    fields: MutableMap<String, String>,
    onLabelChange: (String) -> Unit,
) {
    // Keys are already fully qualified as "<channelId>.<field>".
    val setField: SetField = { key, value -> fields[key] = value }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (config.type in multiAccountTypes) {
            OutlinedTextField(
                value = config.label,
                onValueChange = onLabelChange,
                label = { Text("Account name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("channelLabel_${config.id}"),
            )
        }
        when (config.type) {
                ChannelTypes.TELEGRAM -> {
                    ChannelTextField("Bot token", config.id, fields, "${config.id}.token", setField, isSecret = true)
                    ChannelTextField("Chat ID", config.id, fields, "${config.id}.chat", setField)
                }

                ChannelTypes.EMAIL -> {
                    ChannelTextField("SMTP host", config.id, fields, "${config.id}.host", setField, KeyboardType.Email)
                    ChannelTextField("Port (587 or 465)", config.id, fields, "${config.id}.port", setField, KeyboardType.Number)
                    ChannelTextField("Username", config.id, fields, "${config.id}.username", setField, KeyboardType.Email)
                    ChannelTextField("Password / app password", config.id, fields, "${config.id}.password", setField, isSecret = true)
                    ChannelTextField("From address", config.id, fields, "${config.id}.from", setField, KeyboardType.Email)
                    ChannelTextField("To address", config.id, fields, "${config.id}.to", setField, KeyboardType.Email)
                    SwitchRow(
                        title = "Implicit TLS (SSL, port 465)",
                        subtitle = "Off for port 587 (STARTTLS — Ethereal, Gmail) · on for 465",
                        checked = fields["${config.id}.tls"] == "1",
                        onCheckedChange = { v -> setField("${config.id}.tls", if (v) "1" else "") },
                    )
                }

                ChannelTypes.WEBHOOK -> {
                    val preset = fields["${config.id}.preset"]?.ifEmpty { WebhookValues.CUSTOM } ?: WebhookValues.CUSTOM
                    DropdownField(
                        label = "Preset",
                        selected = preset,
                        options = webhookPresets.map { it to it },
                        testTag = "webhookPreset_${config.id}",
                        onSelect = { p -> setField("${config.id}.preset", p) },
                    )
                    ChannelTextField("Webhook URL", config.id, fields, "${config.id}.url", setField, isSecret = true)
                    ChannelTextField("Bearer token", config.id, fields, "${config.id}.token", setField, isSecret = true)
                    if (preset == WebhookValues.NTFY) {
                        ChannelTextField("Title", config.id, fields, "${config.id}.title", setField)
                    }
                    if (preset == WebhookValues.CUSTOM) {
                        SwitchRow(
                            title = "JSON body",
                            subtitle = "",
                            checked = (fields["${config.id}.bodystyle"] ?: WebhookValues.JSON) == WebhookValues.JSON,
                            onCheckedChange = { v -> setField("${config.id}.bodystyle", if (v) WebhookValues.JSON else WebhookValues.TEXT) },
                        )
                        SwitchRow(
                            title = "Attach photos",
                            subtitle = "Upload the snapshot with each alert (multipart). Off sends JSON/text only.",
                            checked = fields["${config.id}.attachphotos"] == "1",
                            onCheckedChange = { v -> setField("${config.id}.attachphotos", if (v) "1" else "") },
                        )
                    }
                }

                ChannelTypes.PUSHOVER -> {
                    ChannelTextField("App token", config.id, fields, "${config.id}.appToken", setField, isSecret = true)
                    ChannelTextField("User key", config.id, fields, "${config.id}.userKey", setField, isSecret = true)
                    val sound = fields["${config.id}.sound"] ?: ""
                    DropdownField(
                        label = "Sound",
                        selected = sound.ifEmpty { "Default" },
                        options = listOf("" to "Default") + PushoverChannel.VALID_SOUNDS.sorted().map { it to it },
                        testTag = fieldTag(config.id, "Sound"),
                        onSelect = { s -> setField("${config.id}.sound", s) },
                    )
                    val priorityRaw = fields["${config.id}.priority"]?.trim() ?: "0"
                    val priorityValue = priorityRaw.toIntOrNull()?.toString() ?: "0"
                    DropdownField(
                        label = "Priority",
                        selected = pushoverPriorityVerboseLabel(priorityValue),
                        options = pushoverPriorityOptions,
                        testTag = fieldTag(config.id, "Priority"),
                        onSelect = { v -> setField("${config.id}.priority", v) },
                    )
                    ChannelTextField("Emergency retry seconds", config.id, fields, "${config.id}.retrySeconds", setField, keyboardType = KeyboardType.Number)
                    ChannelTextField("Emergency expiry seconds", config.id, fields, "${config.id}.expireSeconds", setField, keyboardType = KeyboardType.Number)
                }
            }
    }
}

internal val pushoverPriorityOptions: List<Pair<String, String>> = listOf(
    "-2" to "Lowest (-2) — no sound/vibrate",
    "-1" to "Low (-1) — quiet",
    "0" to "Normal (0)",
    "1" to "High (1) — bypass quiet hours",
    "2" to "Emergency (2) — require acknowledgement",
)

internal fun pushoverPriorityVerboseLabel(value: String): String =
    pushoverPriorityOptions.firstOrNull { it.first == value }?.second
        ?: "Normal (0)"

private typealias SetField = (String, String) -> Unit

/**
 * Sandbox preview link from the last email test send (Ethereal.email caught
 * message). Selectable + copyable so the tester can open it in a browser and
 * verify the message manually; sandbox links expire after a few hours.
 */
@Composable
internal fun TestPreviewRow(url: String, channelId: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sandbox preview (expires in a few hours)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("testPreviewUrl_$channelId"),
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy preview link")
            }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open preview link")
            }
        }
    }
}

/**
 * Single channel text field replacing Field/NumberField/SecretField.
 * Identical rendering/tags: non-secret uses autoCorrect=false (except Number,
 * whose keyboard never autocorrects); secret adds the show/hide trailing icon.
 */
@Composable
internal fun ChannelTextField(
    label: String,
    channelId: String,
    fields: Map<String, String>,
    key: String,
    setField: SetField,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecret: Boolean = false,
) {
    if (!isSecret) {
        OutlinedTextField(
            value = fields[key] ?: "",
            onValueChange = { setField(key, it) },
            label = { Text(label) },
            singleLine = true,
            // Identifiers, never prose: autocorrect/autocaps would corrupt
            // hostnames, usernames and addresses (e.g. capitalising an SMTP
            // username → 535 auth rejection). Number keyboards never
            // autocorrect, so keep their default options unchanged.
            keyboardOptions = if (keyboardType == KeyboardType.Number) {
                KeyboardOptions(keyboardType = keyboardType)
            } else {
                KeyboardOptions(keyboardType = keyboardType, autoCorrect = false)
            },
            modifier = Modifier.fillMaxWidth().testTag(fieldTag(channelId, label)),
        )
        return
    }
    var visible by rememberSaveable(key) { mutableStateOf(false) }
    OutlinedTextField(
        value = fields[key] ?: "",
        onValueChange = { setField(key, it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(fieldTag(channelId, label)),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DropdownField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    testTag: String? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .let { m -> testTag?.let { m.then(Modifier.testTag(it)) } ?: m },
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

/** Alert-frequency presets for the per-channel dropdown (value + label). */
internal data class FrequencyOption(val mode: String, val seconds: Int, val label: String)

internal val alertFrequencyOptions: List<FrequencyOption>
    get() = listOf(
        FrequencyOption(io.securitycam.level2.core.AlertMode.EVERY_TRIGGER, 0, "Every trigger"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.PER_WAVE, 0, "Once per wave"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 15, "Every 15 seconds"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 30, "Every 30 seconds"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 60, "Every minute"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 120, "Every 2 minutes"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 300, "Every 5 minutes"),
    )

internal fun alertFrequencyLabel(config: io.securitycam.level2.core.ChannelConfig): String =
    alertFrequencyOptions.firstOrNull { it.mode == config.alertMode && it.seconds == config.alertEverySeconds }
        ?.label
        ?: alertFrequencyOptions.firstOrNull { it.mode == config.alertMode }?.label
        ?: "Every trigger"
