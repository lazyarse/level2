@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.securitycam.level1.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.securitycam.level1.channels.EmailChannelSettings
import io.securitycam.level1.channels.PushoverChannelSettings
import io.securitycam.level1.channels.TelegramChannelSettings
import io.securitycam.level1.channels.WebhookChannelSettings
import io.securitycam.level1.channels.webhookPresets
import io.securitycam.level1.core.AnalysisResolution
import io.securitycam.level1.core.ScheduleWindow
import io.securitycam.level1.core.ScreenOrientation
import io.securitycam.level1.core.VideoQuality
import io.securitycam.level1.core.TriggerType
import io.securitycam.level1.detection.DetectorConfig
import java.time.Duration
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Settings screen (port of `lib/ui/settings_screen.dart`). Draft-commit model:
 * every control edits a local draft; "Save settings" persists the whole thing.
 * The desktop dev-source section is intentionally dropped (mobile always uses
 * the on-device camera/mic).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenRegionEditor: () -> Unit = {},
) {
    val draft by viewModel.draft.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    message?.let { text ->
        LaunchedEffect(text) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    // Per-channel text field state, seeded once from the loaded settings
    // (mirror of the Flutter `_fieldControllers` map).
    val fields = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(draft != null) {
        val current = viewModel.draft.filterNotNull().first()
        for (c in current.channelConfigs) {
            when (c.type) {
                "telegram" -> TelegramChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.token"] = it.botToken
                    fields["${c.id}.chat"] = it.chatId
                }

                "email" -> EmailChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.host"] = it.host
                    fields["${c.id}.port"] = it.port.toString()
                    fields["${c.id}.username"] = it.username
                    fields["${c.id}.password"] = it.password
                    fields["${c.id}.from"] = it.from
                    fields["${c.id}.to"] = it.to
                    fields["${c.id}.tls"] = if (it.useTls) "1" else ""
                }

                "webhook" -> WebhookChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.preset"] = it.preset
                    fields["${c.id}.url"] = it.url
                    fields["${c.id}.token"] = it.bearerToken
                    fields["${c.id}.title"] = it.title
                    fields["${c.id}.bodystyle"] = it.bodyStyle
                }

                "pushover" -> PushoverChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.appToken"] = it.appToken
                    fields["${c.id}.userKey"] = it.userKey
                    fields["${c.id}.sound"] = it.sound
                }
            }
        }
    }

    val current = draft
    var pendingClear by remember { mutableStateOf<ClearRequest?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (current == null) {
            Text("Loading…")
        } else {
            OutlinedTextField(
                value = current.cameraName,
                onValueChange = { name -> viewModel.update { it.copy(cameraName = name) } },
                label = { Text("Camera name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("Detectors")
            for ((type, config) in current.detectorConfigs) {
                DetectorCard(
                    config = config,
                    channelIds = current.channelConfigs.map { it.id },
                    onChanged = { next ->
                        viewModel.update {
                            it.copy(detectorConfigs = it.detectorConfigs + (type to next))
                        }
                    },
                )
            }
            SectionTitle("Detection regions")
            BodyText(
                "Optional inclusion zones: motion/face only triggers inside them. " +
                    "Empty = detect everywhere.",
            )
            Spacer(Modifier.height(8.dp))
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenRegionEditor)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CropFree, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (current.detectionRegions.isEmpty()) {
                            "No regions — detecting everywhere"
                        } else {
                            "${current.detectionRegions.size} region" +
                                if (current.detectionRegions.size == 1) "" else "s"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
            SectionTitle("Channels")
            for (config in current.channelConfigs) {
                if (config.type != "log") {
                    ChannelCard(
                        config = config,
                        fields = fields,
                        onEnabledChange = { enabled ->
                            viewModel.update { settings ->
                                settings.copy(
                                    channelConfigs = settings.channelConfigs.map {
                                        if (it.id == config.id) it.copy(enabled = enabled) else it
                                    },
                                )
                            }
                        },
                    )
                }
            }
            SectionTitle("Schedule")
            BodyText("Monitoring pauses during these times.")
            Spacer(Modifier.height(8.dp))
            for (window in current.scheduleExclusions) {
                ScheduleWindowCard(
                    window = window,
                    onChanged = { next ->
                        viewModel.update { s ->
                            s.copy(
                                scheduleExclusions = s.scheduleExclusions.map {
                                    if (it.id == window.id) next else it
                                },
                            )
                        }
                    },
                    onDelete = {
                        viewModel.update { s ->
                            s.copy(
                                scheduleExclusions =
                                    s.scheduleExclusions.filterNot { it.id == window.id },
                            )
                        }
                    },
                )
            }
            Button(
                onClick = {
                    viewModel.update { s ->
                        s.copy(
                            scheduleExclusions = s.scheduleExclusions + ScheduleWindow(
                                id = java.util.UUID.randomUUID().toString(),
                                days = 0b1111111,
                                startHour = 22,
                                startMinute = 0,
                                endHour = 6,
                                endMinute = 0,
                            ),
                        )
                    }
                },
                modifier = Modifier.testTag("scheduleAddWindow"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add window")
            }
            SectionTitle("Notifications")
            Text("Merge window: ${mergeLabel(current.notificationMergeWindow)}")
            Slider(
                value = current.notificationMergeWindow.toSeconds().toFloat().coerceIn(0f, 30f),
                onValueChange = { v ->
                    viewModel.update {
                        it.copy(notificationMergeWindow = Duration.ofSeconds(v.round().toLong()))
                    }
                },
                valueRange = 0f..30f,
                steps = 29,
            )
            SectionTitle("Video clips")
            BodyText(
                "Android only: each event captures footage before and after the " +
                    "trigger and saves it to your gallery.",
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "Record video locally",
                subtitle = "Save a clip to your gallery for each event. Off saves storage and battery.",
                checked = current.recordVideo,
                onCheckedChange = { v -> viewModel.update { it.copy(recordVideo = v) } },
            )
            DropdownField(
                label = "Resolution",
                selected = VideoQuality.label(current.videoQuality),
                options = VideoQuality.values.map { it to VideoQuality.label(it) },
                enabled = current.recordVideo,
                testTag = "videoQualityDropdown",
                onSelect = { q -> viewModel.update { it.copy(videoQuality = q) } },
            )
            Text("Pre-roll: ${current.preRollSeconds}s")
            Slider(
                value = current.preRollSeconds.toFloat().coerceIn(0f, 30f),
                onValueChange = { v ->
                    viewModel.update { it.copy(preRollSeconds = v.round()) }
                },
                valueRange = 0f..30f,
                steps = 29,
                enabled = current.recordVideo,
                modifier = Modifier.testTag("preRollSlider"),
            )
            Text("Post-roll: ${current.postRollSeconds}s")
            Slider(
                value = current.postRollSeconds.toFloat().coerceIn(0f, 30f),
                onValueChange = { v ->
                    viewModel.update { it.copy(postRollSeconds = v.round()) }
                },
                valueRange = 0f..30f,
                steps = 29,
                enabled = current.recordVideo,
                modifier = Modifier.testTag("postRollSlider"),
            )
            SectionTitle("Events")
            Text(
                "Automatic retention: " +
                    if (current.retentionDays == 0) "off"
                    else "${current.retentionDays} day" + if (current.retentionDays == 1) "" else "s",
            )
            Slider(
                value = current.retentionDays.toFloat().coerceIn(0f, 30f),
                onValueChange = { v -> viewModel.update { it.copy(retentionDays = v.round()) } },
                valueRange = 0f..30f,
                steps = 29,
                modifier = Modifier.testTag("retentionSlider"),
            )
            FilledTonalButton(onClick = { pendingClear = ClearRequest(all = false) }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear events older than 24h")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { pendingClear = ClearRequest(all = true) }) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear all events")
            }
            SectionTitle("Advanced")
            BodyText(
                "Analysis stream resolution: higher = better far-face detection " +
                    "but more battery. Balanced is a good default.",
            )
            Spacer(Modifier.height(8.dp))
            DropdownField(
                label = "Analysis resolution",
                selected = AnalysisResolution.label(current.analysisResolution),
                options = AnalysisResolution.values.map { it to AnalysisResolution.label(it) },
                testTag = "analysisResolutionDropdown",
                onSelect = { r -> viewModel.update { it.copy(analysisResolution = r) } },
            )
            BodyText(
                "Screen orientation: locks the monitor screen to portrait or " +
                    "landscape, or follows the device sensor.",
            )
            DropdownField(
                label = "Screen orientation",
                selected = ScreenOrientation.label(current.screenOrientation),
                options = ScreenOrientation.values.map { it to ScreenOrientation.label(it) },
                testTag = "screenOrientationDropdown",
                onSelect = { o -> viewModel.update { it.copy(screenOrientation = o) } },
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    // Fold raw field state into typed channel configs at commit
                    // time (Dart `_save`), then persist the draft.
                    viewModel.update { draftNow ->
                        draftNow.copy(
                            channelConfigs = buildChannelConfigs(draftNow.channelConfigs, fields),
                        )
                    }
                    viewModel.save()
                },
                modifier = Modifier.testTag("saveSettings"),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save settings")
            }
        }
        }

        pendingClear?.let { request ->
            val all = request.all
            AlertDialog(
                onDismissRequest = { pendingClear = null },
                title = { Text("Clear events") },
                text = {
                    Text(
                        if (all) {
                            "Delete ALL recorded events and their snapshots and videos?"
                        } else {
                            "Delete events older than 24h and their snapshots and videos?"
                        },
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.clearEvents(if (all) null else Duration.ofHours(24))
                        pendingClear = null
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingClear = null }) { Text("Cancel") }
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        )
    }
}

/** Which clear-events confirmation the dialog is showing. */
private data class ClearRequest(val all: Boolean)

/** Builds channel configs from field state at save time (Dart `_save`). */
internal fun buildChannelConfigs(
    configs: List<io.securitycam.level1.core.ChannelConfig>,
    fields: Map<String, String>,
): List<io.securitycam.level1.core.ChannelConfig> = configs.map { c ->
    fun f(key: String): String = fields["${c.id}.$key"] ?: ""
    when (c.type) {
        "telegram" -> c.copy(
            settingsJson = TelegramChannelSettings(
                botToken = f("token"),
                chatId = f("chat"),
            ).toJson(),
        )

        "email" -> c.copy(
            settingsJson = EmailChannelSettings(
                host = f("host").trim(),
                port = f("port").trim().toIntOrNull() ?: 587,
                username = f("username").trim(),
                password = f("password"),
                from = f("from").trim(),
                to = f("to").trim(),
                useTls = f("tls") == "1",
            ).toJson(),
        )

        "webhook" -> c.copy(
            settingsJson = WebhookChannelSettings(
                preset = f("preset").ifEmpty { "custom" },
                url = f("url").trim(),
                bearerToken = f("token"),
                title = f("title"),
                bodyStyle = f("bodystyle").ifEmpty { "json" },
            ).toJson(),
        )

        "pushover" -> c.copy(
            settingsJson = PushoverChannelSettings(
                appToken = f("appToken").trim(),
                userKey = f("userKey").trim(),
                sound = f("sound").trim(),
            ).toJson(),
        )

        else -> c
    }
}

/** One recurring exclusion window: enable switch, day toggles, time steppers. */
@Composable
private fun ScheduleWindowCard(
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
                Switch(
                    checked = window.enabled,
                    onCheckedChange = { onChanged(window.copy(enabled = it)) },
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
private fun TimeStepperRow(
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

private fun fmtTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun scheduleSummary(window: ScheduleWindow): String {
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
private fun DetectorCard(
    config: DetectorConfig,
    channelIds: List<String>,
    onChanged: (DetectorConfig) -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detectorLabel(config.type), modifier = Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = { v -> onChanged(config.copy(enabled = v)) })
            }
            if (config.enabled) {
                SwitchRow(
                    title = "Motion-gated",
                    subtitle = "Only check for this after motion is detected (saves battery).",
                    checked = config.motionGated,
                    onCheckedChange = { v -> onChanged(config.copy(motionGated = v)) },
                )
                Text("Threshold: %.2f".format(config.threshold))
                Slider(
                    value = config.threshold.toFloat().coerceIn(0f, 1f),
                    onValueChange = { v -> onChanged(config.copy(threshold = v.toDouble())) },
                    valueRange = 0f..1f,
                    modifier = Modifier.testTag("threshold_${config.type}"),
                )
                StepperRow(
                    label = "Persistence: ${config.persistenceFrames}",
                    canDecrement = config.persistenceFrames > 1,
                    canIncrement = true,
                    onDecrement = { onChanged(config.copy(persistenceFrames = config.persistenceFrames - 1)) },
                    onIncrement = { onChanged(config.copy(persistenceFrames = config.persistenceFrames + 1)) },
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
                )
                Text("Route to channels", style = MaterialTheme.typography.bodySmall)
                for (id in channelIds) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val routes = config.routeToChannelIds.toMutableList()
                                if (id in routes) routes.remove(id) else routes.add(id)
                                onChanged(config.copy(routeToChannelIds = routes))
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = id in config.routeToChannelIds,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(id)
                    }
                }
            }
        }
    }
}

private fun detectorLabel(type: String): String = when (type) {
    TriggerType.motion -> "Motion"
    TriggerType.babyCry -> "Baby cry"
    TriggerType.glassBreak -> "Glass break"
    TriggerType.loudNoise -> "Loud noise"
    TriggerType.face -> "Face"
    TriggerType.person -> "Person"
    TriggerType.tamper -> "Tamper"
    else -> type
}

@Composable
private fun ChannelCard(
    config: io.securitycam.level1.core.ChannelConfig,
    fields: MutableMap<String, String>,
    onEnabledChange: (Boolean) -> Unit,
) {
    // Keys are already fully qualified as "<channelId>.<field>".
    val setField: SetField = { key, value -> fields[key] = value }

    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(config.type, modifier = Modifier.weight(1f))
                Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
            }
            when (config.type) {
                "telegram" -> {
                    SecretField("Bot token", fields, "${config.id}.token", setField)
                    Field("Chat ID", fields, "${config.id}.chat", setField)
                }

                "email" -> {
                    Field("SMTP host", fields, "${config.id}.host", setField)
                    NumberField("Port (587 or 465)", fields, "${config.id}.port", setField)
                    Field("Username", fields, "${config.id}.username", setField)
                    SecretField("Password / app password", fields, "${config.id}.password", setField)
                    Field("From address", fields, "${config.id}.from", setField)
                    Field("To address", fields, "${config.id}.to", setField)
                    SwitchRow(
                        title = "Implicit TLS (SSL, port 465)",
                        subtitle = "",
                        checked = fields["${config.id}.tls"] == "1",
                        onCheckedChange = { v -> setField("${config.id}.tls", if (v) "1" else "") },
                    )
                }

                "webhook" -> {
                    val preset = fields["${config.id}.preset"]?.ifEmpty { "custom" } ?: "custom"
                    DropdownField(
                        label = "Preset",
                        selected = preset,
                        options = webhookPresets.map { it to it },
                        testTag = "webhookPreset_${config.id}",
                        onSelect = { p -> setField("${config.id}.preset", p) },
                    )
                    SecretField("Webhook URL", fields, "${config.id}.url", setField)
                    SecretField("Bearer token", fields, "${config.id}.token", setField)
                    if (preset == "ntfy") {
                        Field("Title", fields, "${config.id}.title", setField)
                    }
                    if (preset == "custom") {
                        SwitchRow(
                            title = "JSON body",
                            subtitle = "",
                            checked = (fields["${config.id}.bodystyle"] ?: "json") == "json",
                            onCheckedChange = { v -> setField("${config.id}.bodystyle", if (v) "json" else "text") },
                        )
                    }
                }

                "pushover" -> {
                    SecretField("App token", fields, "${config.id}.appToken", setField)
                    SecretField("User key", fields, "${config.id}.userKey", setField)
                    Field("Sound", fields, "${config.id}.sound", setField)
                }
            }
        }
    }
}

private typealias SetField = (String, String) -> Unit

@Composable
private fun Field(
    label: String,
    fields: Map<String, String>,
    key: String,
    setField: SetField,
) {
    OutlinedTextField(
        value = fields[key] ?: "",
        onValueChange = { setField(key, it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(fieldTag(label)),
    )
}

@Composable
private fun NumberField(
    label: String,
    fields: Map<String, String>,
    key: String,
    setField: SetField,
) {
    OutlinedTextField(
        value = fields[key] ?: "",
        onValueChange = { setField(key, it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(fieldTag(label)),
    )
}

@Composable
private fun SecretField(
    label: String,
    fields: Map<String, String>,
    key: String,
    setField: SetField,
) {
    OutlinedTextField(
        value = fields[key] ?: "",
        onValueChange = { setField(key, it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(fieldTag(label)),
    )
}

/** Stable tag mirroring the Dart `_fieldOf(label)` finder strategy. */
internal fun fieldTag(label: String): String =
    "field_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_")

@Composable
private fun DropdownField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    testTag: String? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
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

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(switchTag(title)))
    }
}

internal fun switchTag(title: String): String =
    "switch_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_")

@Composable
private fun StepperRow(
    label: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

private fun mergeLabel(window: Duration): String =
    if (window.isZero) "Off" else "${window.toSeconds()}s"

private fun Float.round(): Int = Math.round(this)
