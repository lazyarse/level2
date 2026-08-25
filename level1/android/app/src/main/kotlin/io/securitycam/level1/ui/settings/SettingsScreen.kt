@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.securitycam.level1.ui.settings

import android.Manifest
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.securitycam.level1.channels.EmailChannelSettings
import io.securitycam.level1.channels.PushoverChannelSettings
import io.securitycam.level1.channels.TelegramChannelSettings
import io.securitycam.level1.channels.WebhookChannelSettings
import io.securitycam.level1.channels.webhookPresets
import io.securitycam.level1.camera_service.CameraInfo
import io.securitycam.level1.camera_service.availableCameras
import io.securitycam.level1.core.AnalysisResolution
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.core.AppSettings.Companion.withFaceRecognition
import io.securitycam.level1.core.ClipStampPosition
import io.securitycam.level1.core.KnownFace
import io.securitycam.level1.core.LiveViewSettings
import io.securitycam.level1.core.ScheduleWindow
import io.securitycam.level1.core.ScreenOrientation
import io.securitycam.level1.core.VideoQuality
import io.securitycam.level1.core.DetectorType
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
    var showAddFaceDialog by remember { mutableStateOf(false) }
    var faceEnrollName by remember { mutableStateOf("") }
    val enrolling by viewModel.enrollingLabel.collectAsState()
    val isEnrolling = enrolling != null

    // Enrollment needs only CAMERA (no audio). If missing, stash the entered
    // name and resume enrollment once the grant returns.
    var pendingFaceName by remember { mutableStateOf<String?>(null) }
    val enrollPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val name = pendingFaceName
        pendingFaceName = null
        if (grants[Manifest.permission.CAMERA] == true && name != null) {
            viewModel.startEnrollment(name)
        } else if (name != null) {
            viewModel.notifyEnrollmentPermissionDenied()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Pending delete awaiting confirmation.
    var pendingDeleteFace by remember { mutableStateOf<KnownFace?>(null) }

    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (current == null) {
                Text("Loading…", modifier = Modifier.padding(16.dp))
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp),
                    ) {
                        OutlinedTextField(
                            value = current.cameraName,
                            onValueChange = { name -> viewModel.update { it.copy(cameraName = name) } },
                            label = { Text("Camera name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // CameraManager enumeration is IPC: keep it off the
                        // composition's main thread.
                        val cameras by produceState(emptyList<CameraInfo>(), ctx) {
                            value = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) { availableCameras(ctx) }
                        }
                        if (cameras.size > 1) {
                            DropdownField(
                                label = "Camera",
                                selected = cameras.firstOrNull { it.id == current.cameraId }?.label
                                    ?: current.cameraId,
                                options = cameras.map { it.id to it.label },
                                testTag = "cameraDropdown",
                                onSelect = { id -> viewModel.update { it.copy(cameraId = id) } },
                            )
                        }
                        CollapsibleSection("Detectors", summary = detectorSummary(current)) {
                            detectorGroup("Camera", current, cameraDetectorOrder) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                            detectorGroup("Audio", current, audioGeneralOrder) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                            detectorGroup("Combined", current, combinedPetOrder) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                            detectorGroup("System", current, listOf(TriggerType.health)) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                        }
                        CollapsibleSection("Regions", summary = regionsSummary(current)) {
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
                        }
                        CollapsibleSection(
                            "Face Recognition",
                            summary = faceRecognitionSummary(current),
                        ) {
                            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Face, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Recognise known faces")
                                        BodyText(
                                            if (current.knownFaces.isEmpty()) {
                                                "No faces enrolled yet"
                                            } else {
                                                "${current.knownFaces.size} enrolled"
                                            },
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    Switch(
                                        checked = AppSettings.faceRecognitionEnabled(current),
                                        onCheckedChange = { on ->
                                            viewModel.update { it.withFaceRecognition(on) }
                                        },
                                        modifier = Modifier.testTag("faceRecognitionSwitch"),
                                    )
                                }
                            }
                            if (AppSettings.faceRecognitionEnabled(current)) {
                                for (face in current.knownFaces) {
                                    Card(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            FaceThumbnail(
                                                file = viewModel.thumbFile(face.id),
                                                label = face.label,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(face.label)
                                                val samples = produceState(0, face.id) {
                                                    value = viewModel.sampleCount(face.id)
                                                }
                                                if (samples.value > 0) {
                                                    Text(
                                                        "${samples.value} photo" +
                                                            if (samples.value == 1) "" else "s",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = { viewModel.startSampleCapture(face) },
                                                enabled = !isEnrolling,
                                                modifier =
                                                    Modifier.testTag("addSample_${face.id}"),
                                            ) {
                                                Icon(
                                                    Icons.Filled.AddPhotoAlternate,
                                                    contentDescription =
                                                        "Add photos of ${face.label}",
                                                )
                                            }
                                            IconButton(
                                                onClick = { pendingDeleteFace = face },
                                                modifier =
                                                    Modifier.testTag("deleteFace_${face.id}"),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = "Delete ${face.label}",
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { showAddFaceDialog = true },
                                    enabled = !isEnrolling,
                                    modifier = Modifier.testTag("addFaceButton"),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add face")
                                }
                            }
                        }
                        CollapsibleSection(
                            "Channels",
                            summary = "${current.channelConfigs.count { it.type != "log" }} channels",
                        ) {
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
                                        onSendTest = { merged ->
                                            viewModel.sendTestFromUi(merged)
                                        },
                                        inFlight = viewModel.sendingTestId.collectAsState().value == config.id,
                                        factories = viewModel.testFactories,
                                    )
                                }
                            }
                        }
                        CollapsibleSection("Schedule", summary = "${current.scheduleExclusions.size} windows") {
                            BodyText("Define the time slots that video monitoring should happen.")
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
                        }
                        CollapsibleSection("Video clips", summary = if (current.recordVideo) "on" else "off") {
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
                            SwitchRow(
                                title = "Date/time stamp",
                                subtitle = "Burn the date/time into recorded clips",
                                checked = current.clipTimestamp,
                                onCheckedChange = { v ->
                                    viewModel.update { it.copy(clipTimestamp = v) }
                                },
                            )
                            if (current.clipTimestamp) {
                                SwitchRow(
                                    title = "Include camera name",
                                    subtitle = "Prefix the stamp with the camera name",
                                    checked = current.clipTimestampCameraName,
                                    onCheckedChange = { v ->
                                        viewModel.update {
                                            it.copy(clipTimestampCameraName = v)
                                        }
                                    },
                                )
                                DropdownField(
                                    label = "Stamp position",
                                    selected = ClipStampPosition.label(current.clipTimestampPosition),
                                    options = ClipStampPosition.values.map {
                                        it to ClipStampPosition.label(it)
                                    },
                                    testTag = "clipStampPosition",
                                    onSelect = { p ->
                                        viewModel.update { it.copy(clipTimestampPosition = p) }
                                    },
                                )
                            }
                            SwitchRow(
                                title = "Privacy mask",
                                subtitle = "Obscure exclusion zones in recorded clips",
                                checked = current.privacyMasking,
                                onCheckedChange = { v ->
                                    viewModel.update { it.copy(privacyMasking = v) }
                                },
                            )
                            if (current.privacyMasking) {
                                DropdownField(
                                    label = "Mask effect",
                                    selected = io.securitycam.level1.core.PrivacyMaskEffect.label(current.privacyMaskEffect),
                                    options = io.securitycam.level1.core.PrivacyMaskEffect.values.map {
                                        it to io.securitycam.level1.core.PrivacyMaskEffect.label(it)
                                    },
                                    testTag = "privacyMaskEffect",
                                    onSelect = { e ->
                                        viewModel.update { it.copy(privacyMaskEffect = e) }
                                    },
                                )
                            }
                        }
                        CollapsibleSection(
                            "Live View",
                            summary = liveViewSummary(current.liveView),
                        ) {
                            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                                SwitchRow(
                                    title = "Enable live stream",
                                    subtitle = "RTSP stream while monitoring",
                                    checked = current.liveView.enabled,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(enabled = v)) }
                                    },
                                )
                            }
                            if (current.liveView.enabled) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Mode",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    "Server runs a local RTSP server you connect to. " +
                                        "Push streams to a remote RTSP relay.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf("server" to "Server", "push" to "Push").forEach { (value, label) ->
                                        val selected = current.liveView.mode == value
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                viewModel.update { it.copy(liveView = it.liveView.copy(mode = value)) }
                                            },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                if (current.liveView.mode == "server") {
                                    if (current.liveView.username.isBlank() &&
                                        current.liveView.password.isBlank()
                                    ) {
                                        Spacer(Modifier.height(8.dp))
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("liveViewNoAuthWarning"),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                            ),
                                        ) {
                                            Text(
                                                "No password set — anyone on this Wi-Fi " +
                                                    "network can watch the stream. Set a " +
                                                    "username and password below to require login.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(12.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = current.liveView.port.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { port ->
                                                viewModel.update { it.copy(liveView = it.liveView.copy(port = port)) }
                                            }
                                        },
                                        label = { Text("Port") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("liveViewPort"),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    SwitchRow(
                                        title = "Require authentication",
                                        subtitle = "",
                                        checked = current.liveView.username.isNotEmpty(),
                                        onCheckedChange = { v ->
                                            viewModel.update {
                                                it.copy(liveView = it.liveView.copy(
                                                    username = if (v) "admin" else "",
                                                    password = if (v) it.liveView.password else "",
                                                ))
                                            }
                                        },
                                    )
                                    if (current.liveView.username.isNotEmpty()) {
                                        OutlinedTextField(
                                            value = current.liveView.username,
                                            onValueChange = { v ->
                                                viewModel.update { it.copy(liveView = it.liveView.copy(username = v)) }
                                            },
                                            label = { Text("Username") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("liveViewUsername"),
                                        )
                                        OutlinedTextField(
                                            value = current.liveView.password,
                                            onValueChange = { v ->
                                                viewModel.update { it.copy(liveView = it.liveView.copy(password = v)) }
                                            },
                                            label = { Text("Password") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("liveViewPassword"),
                                        )
                                    }
                                }
                                if (current.liveView.mode == "push") {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = current.liveView.relayUrl,
                                        onValueChange = { v ->
                                            viewModel.update { it.copy(liveView = it.liveView.copy(relayUrl = v)) }
                                        },
                                        label = { Text("Relay URL") },
                                        placeholder = { Text("rtsp://host:port/path") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("liveViewRelayUrl"),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                DropdownField(
                                    label = "Resolution",
                                    selected = current.liveView.resolution,
                                    options = listOf("480p", "720p", "1080p").map { it to it },
                                    enabled = true,
                                    testTag = "liveViewResolution",
                                    onSelect = { q ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(resolution = q)) }
                                    },
                                )
                                Text("FPS: ${current.liveView.fps}")
                                Slider(
                                    value = current.liveView.fps.toFloat().coerceIn(5f, 30f),
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(fps = v.round())) }
                                    },
                                    valueRange = 5f..30f,
                                    steps = 24,
                                    modifier = Modifier.testTag("liveViewFps"),
                                )
                                SwitchRow(
                                    title = "Include audio",
                                    subtitle = "Stream microphone audio",
                                    checked = current.liveView.audioEnabled,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(audioEnabled = v)) }
                                    },
                                )
                            }
                        }
                        CollapsibleSection("Cloud backup", summary = cloudBackupSummary(current.cloudBackup)) {
                            Card(modifier = Modifier.padding(vertical = 4.dp)) {
                                SwitchRow(
                                    title = "Back up clips & snapshots",
                                    subtitle = "Uploads to your own server when online",
                                    checked = current.cloudBackup.enabled,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(enabled = v)) }
                                    },
                                )
                            }
                            if (current.cloudBackup.enabled) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf("webdav" to "WebDAV", "s3" to "S3").forEach { (value, label) ->
                                        FilterChip(
                                            selected = current.cloudBackup.backend == value,
                                            onClick = {
                                                viewModel.update {
                                                    it.copy(cloudBackup = it.cloudBackup.copy(backend = value))
                                                }
                                            },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = current.cloudBackup.serverUrl,
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(serverUrl = v)) }
                                    },
                                    label = {
                                        Text(if (current.cloudBackup.backend == "s3") "Endpoint URL" else "Server URL")
                                    },
                                    placeholder = {
                                        Text(
                                            if (current.cloudBackup.backend == "s3") {
                                                "https://s3.eu-central-1.amazonaws.com"
                                            } else {
                                                "https://cloud.example.com/remote.php/dav/files/me"
                                            },
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloudBackupUrl"),
                                )
                                OutlinedTextField(
                                    value = current.cloudBackup.bucketOrPath,
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(bucketOrPath = v)) }
                                    },
                                    label = {
                                        Text(if (current.cloudBackup.backend == "s3") "Bucket" else "Remote folder")
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloudBackupBucket"),
                                )
                                if (current.cloudBackup.backend == "s3") {
                                    OutlinedTextField(
                                        value = current.cloudBackup.region,
                                        onValueChange = { v ->
                                            viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(region = v)) }
                                        },
                                        label = { Text("Region") },
                                        placeholder = { Text("us-east-1") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("cloudBackupRegion"),
                                    )
                                }
                                OutlinedTextField(
                                    value = current.cloudBackup.username,
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(username = v)) }
                                    },
                                    label = {
                                        Text(if (current.cloudBackup.backend == "s3") "Access key ID" else "Username")
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloudBackupUser"),
                                )
                                OutlinedTextField(
                                    value = current.cloudBackup.password,
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(password = v)) }
                                    },
                                    label = {
                                        Text(if (current.cloudBackup.backend == "s3") "Secret access key" else "Password")
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloudBackupPassword"),
                                )
                                SwitchRow(
                                    title = "Include clips",
                                    subtitle = "Upload event videos",
                                    checked = current.cloudBackup.backupClips,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(backupClips = v)) }
                                    },
                                )
                                SwitchRow(
                                    title = "Include snapshots",
                                    subtitle = "Upload alert photos",
                                    checked = current.cloudBackup.backupSnapshots,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(backupSnapshots = v)) }
                                    },
                                )
                                FilledTonalButton(onClick = { viewModel.validateCloudBackup() }) {
                                    Text("Test connection")
                                }
                            }
                        }
                        CollapsibleSection("Events", summary = retentionSummary(current.retentionDays)) {
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
                        }
                        CollapsibleSection("Advanced") {
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
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    ScrollbarThumb(scrollState, Modifier.align(Alignment.CenterEnd))
                }
                HorizontalDivider()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("saveSettings"),
                    shape = RoundedCornerShape(2.dp),
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

        pendingDeleteFace?.let { face ->
            AlertDialog(
                onDismissRequest = { pendingDeleteFace = null },
                title = { Text("Remove ${face.label}?") },
                text = {
                    Text(
                        "Their saved photo samples will be deleted and they " +
                            "will no longer be recognised.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteFace(face)
                            pendingDeleteFace = null
                        },
                        modifier = Modifier.testTag("confirmDeleteFace"),
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteFace = null }) { Text("Cancel") }
                },
            )
        }

        if (showAddFaceDialog) {
            AlertDialog(
                onDismissRequest = { showAddFaceDialog = false; faceEnrollName = "" },
                title = { Text("Enrol face") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = faceEnrollName,
                            onValueChange = { faceEnrollName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.testTag("faceNameField"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The camera turns on briefly — look at it when you confirm.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val name = faceEnrollName.trim()
                            if (name.isEmpty()) return@Button
                            showAddFaceDialog = false
                            faceEnrollName = ""
                            // Block duplicates up-front; the row's photos icon
                            // extends an existing person instead.
                            if (current?.knownFaces
                                    ?.any { it.label.equals(name, ignoreCase = true) } == true
                            ) {
                                viewModel.notifyDuplicateName(name)
                                return@Button
                            }
                            val missing = viewModel.missingEnrollmentPermissions()
                            if (missing.isEmpty()) {
                                viewModel.startEnrollment(name)
                            } else {
                                pendingFaceName = name
                                enrollPermissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        enabled = faceEnrollName.trim().isNotEmpty() && !isEnrolling,
                    ) { Text("Enrol") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddFaceDialog = false; faceEnrollName = "" }) {
                        Text("Cancel")
                    }
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
                Spacer(Modifier.width(8.dp))
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
    var expanded by rememberSaveable("detector_${config.type}") { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron_detector_${config.type}")
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag("detectorHeader_${config.type}"),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "collapse_${config.type}" else "expand_${config.type}",
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
                Spacer(Modifier.width(8.dp))
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
                Switch(checked = config.enabled, onCheckedChange = { v -> onChanged(config.copy(enabled = v)) })
            }
            if (expanded) {
                detectorHint(config.type)?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (config.type != TriggerType.motion) {
                    SwitchRow(
                        title = "Motion-gated",
                        subtitle = "Only check for this after motion is detected (saves battery).",
                        checked = config.motionGated,
                        onCheckedChange = { v -> onChanged(config.copy(motionGated = v)) },
                    )
                }
                val hybrid = config.type in combinedPetOrder
                if (hybrid) {
                    Text("Sight threshold: %.2f".format(config.threshold))
                    Slider(
                        value = config.threshold.toFloat().coerceIn(0f, 1f),
                        onValueChange = { v -> onChanged(config.copy(threshold = v.toDouble())) },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("threshold_${config.type}"),
                    )
                    val audioThreshold = config.audioThreshold ?: config.threshold
                    Text("Sound threshold: %.2f".format(audioThreshold))
                    Slider(
                        value = audioThreshold.toFloat().coerceIn(0f, 1f),
                        onValueChange = { v -> onChanged(config.copy(audioThreshold = v.toDouble())) },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("audioThreshold_${config.type}"),
                    )
                } else {
                    Text("Threshold: %.2f".format(config.threshold))
                    Slider(
                        value = config.threshold.toFloat().coerceIn(0f, 1f),
                        onValueChange = { v -> onChanged(config.copy(threshold = v.toDouble())) },
                        valueRange = 0f..1f,
                        modifier = Modifier.testTag("threshold_${config.type}"),
                    )
                }
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

private fun detectorLabel(type: String): String =
    DetectorType.fromKey(type)?.label ?: type

@Composable
private fun ChannelCard(
    config: io.securitycam.level1.core.ChannelConfig,
    fields: MutableMap<String, String>,
    onEnabledChange: (Boolean) -> Unit,
    onSendTest: (io.securitycam.level1.core.ChannelConfig) -> Unit,
    inFlight: Boolean,
    factories: Map<String, io.securitycam.level1.event.ChannelFactory>,
) {
    var expanded by rememberSaveable("channel_${config.id}") { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron_${config.id}")
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .testTag("channelCard_${config.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag("channelHeader_${config.id}"),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "collapse_${config.id}" else "expand_${config.id}",
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
                Text(channelTitle(config.type), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
            }
            if (expanded) {
                ChannelBody(
                    config = config,
                    fields = fields,
                    onSendTest = onSendTest,
                    inFlight = inFlight,
                    factories = factories,
                )
            }
        }
    }
}

/** Expanded channel card contents: type-specific fields plus the test sender. */
@Composable
private fun ChannelBody(
    config: io.securitycam.level1.core.ChannelConfig,
    fields: MutableMap<String, String>,
    onSendTest: (io.securitycam.level1.core.ChannelConfig) -> Unit,
    inFlight: Boolean,
    factories: Map<String, io.securitycam.level1.event.ChannelFactory>,
) {
    // Keys are already fully qualified as "<channelId>.<field>".
    val setField: SetField = { key, value -> fields[key] = value }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            val draftValid = remember(config.id, config.type, fields.toMap()) {
                val merged = buildChannelConfigs(listOf(config), fields).first()
                val channel = factories[merged.type]?.invoke(merged)
                channel != null && channel.validate() == null
            }
            OutlinedButton(
                onClick = {
                    onSendTest(buildChannelConfigs(listOf(config), fields).first())
                },
                enabled = draftValid && !inFlight,
                modifier = Modifier.testTag("sendTest_${config.id}"),
            ) {
                Text(if (inFlight) "Sending…" else "Send test")
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(switchTag(title)))
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
private fun StepperRow(
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

/**
 * Collapsible settings group: tapping the header toggles a body that is only
 * composed while expanded (keeps the semantics tree and scroll height small).
 */
@Composable
private fun CollapsibleSection(
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
private fun ScrollbarThumb(scrollState: ScrollState, modifier: Modifier = Modifier) {
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

/** Explainer shown at the top of a detector's fold-down, when non-null. */
private fun detectorHint(type: String): String? = when (type) {
    TriggerType.bird -> "Detects birds."
    TriggerType.livestock -> "Detects cows, sheep and horses."
    TriggerType.dog -> "Triggers on sight or sound (barking, growling)."
    TriggerType.cat -> "Triggers on sight or sound (meowing, purring, hissing)."
    else -> null
}

/** Channel header display name: raw type ids rendered Title Case. */
private fun channelTitle(type: String): String = type.split('_', ' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Regions section summary: inclusion/exclusion counts. */
private fun regionsSummary(settings: AppSettings): String {
    val inc = settings.detectionRegions.size
    val ex = settings.exclusionRegions.size
    return if (inc == 0 && ex == 0) "none" else "$inc inclusion · $ex exclusion"
}

/** Camera-section display order: pet/animal detectors grouped, then the rest. */
private val cameraDetectorOrder = listOf(
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
private val audioGeneralOrder = listOf(
    TriggerType.loudNoise,
    TriggerType.glassBreak,
    TriggerType.babyCry,
)

/** Combined sight+sound pet detectors. */
private val combinedPetOrder = listOf(
    TriggerType.dog,
    TriggerType.cat,
)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.detectorGroup(
    label: String,
    settings: AppSettings,
    types: List<String>,
    onChanged: (String, DetectorConfig) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    for (type in types) {
        val config = settings.detectorConfigs[type] ?: continue
        DetectorCard(
            config = config,
            channelIds = settings.channelConfigs.map { it.id },
            onChanged = { next -> onChanged(type, next) },
        )
    }
}

private fun retentionSummary(days: Int): String =
    if (days == 0) "retention off" else "$days day" + if (days == 1) "" else "s"

private fun detectorSummary(settings: AppSettings): String {
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

private fun faceRecognitionSummary(settings: AppSettings): String {
    val enabled = AppSettings.faceRecognitionEnabled(settings)
    val count = settings.knownFaces.size
    return if (!enabled) "off" else "On: $count enrolled"
}

private fun cloudBackupSummary(cb: io.securitycam.level1.core.CloudBackupSettings): String {
    if (!cb.enabled) return "off"
    val backend = if (cb.backend == "s3") "s3" else "webdav"
    val kinds = buildList {
        if (cb.backupClips) add("clips")
        if (cb.backupSnapshots) add("snaps")
    }
    return "$backend" + if (kinds.isEmpty()) "" else " (${kinds.joinToString("+")})"
}

private fun liveViewSummary(lv: LiveViewSettings): String {
    if (!lv.enabled) return "off"
    return if (lv.mode == "server") {
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

/** Enrolled-face thumbnail decoded from disk; falls back to a face icon. */
@Composable
private fun FaceThumbnail(file: java.io.File?, label: String) {
    if (file == null) {
        Icon(Icons.Filled.Face, contentDescription = label)
        return
    }
    // Cached decode keyed by absolute path; synchronous peek renders
    // previously-seen faces in first frame while scrolling.
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = io.securitycam.level1.ui.events.ThumbCache.peek("face:${file.absolutePath}"),
        key1 = file.absolutePath,
    ) {
        val f = file
        if (value == null) {
            value = io.securitycam.level1.ui.events.ThumbCache.getOrLoad(
                "face:${f.absolutePath}",
            ) {
                runCatching { f.readBytes() }.getOrNull()
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(
            Icons.Filled.Face,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
