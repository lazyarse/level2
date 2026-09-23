@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.material3.AlertDialog
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
 * Settings screen. Draft-commit model:
 * every control edits a local draft; "Save settings" persists the whole thing.
 * The desktop dev-source section is intentionally dropped (mobile always uses
 * the on-device camera/mic).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenZoneEditor: () -> Unit = {},
    onOpenAlertLog: () -> Unit = {},
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
                ChannelTypes.TELEGRAM -> TelegramChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.token"] = it.botToken
                    fields["${c.id}.chat"] = it.chatId
                }

                ChannelTypes.EMAIL -> EmailChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.host"] = it.host
                    fields["${c.id}.port"] = it.port.toString()
                    fields["${c.id}.username"] = it.username
                    fields["${c.id}.password"] = it.password
                    fields["${c.id}.from"] = it.from
                    fields["${c.id}.to"] = it.to
                    fields["${c.id}.tls"] = if (it.useTls) "1" else ""
                }

                ChannelTypes.WEBHOOK -> WebhookChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.preset"] = it.preset
                    fields["${c.id}.url"] = it.url
                    fields["${c.id}.token"] = it.bearerToken
                    fields["${c.id}.title"] = it.title
                    fields["${c.id}.bodystyle"] = it.bodyStyle
                    fields["${c.id}.attachphotos"] = if (it.attachPhotos) "1" else ""
                }

                ChannelTypes.PUSHOVER -> PushoverChannelSettings.fromJson(c.settingsJson).let {
                    fields["${c.id}.appToken"] = it.appToken
                    fields["${c.id}.userKey"] = it.userKey
                    fields["${c.id}.sound"] = it.sound
                    fields["${c.id}.priority"] = it.priority.toString()
                    fields["${c.id}.retrySeconds"] = it.retrySeconds.toString()
                    fields["${c.id}.expireSeconds"] = it.expireSeconds.toString()
                }
            }
        }
    }

    val current = draft
    // Hoisted above the channel loop: one collector each, stable across
    // expand/collapse recompositions (per-card collectors restarted and
    // multiplied with the card count).
    val sendingTestId by viewModel.sendingTestId.collectAsState()
    val testPreview by viewModel.lastTestPreview.collectAsState()
    var pendingClear by rememberSaveable(stateSaver = ClearRequestSaver) {
        mutableStateOf<ClearRequest?>(null)
    }
    var clearDurationHours by rememberSaveable { mutableStateOf(24) }
    var showAddFaceDialog by rememberSaveable { mutableStateOf(false) }
    var faceEnrollName by rememberSaveable { mutableStateOf("") }
    val enrolling by viewModel.enrollingLabel.collectAsState()
    val isEnrolling = enrolling != null

    // Enrollment needs only CAMERA (no audio). If missing, stash the entered
    // name and resume enrollment once the grant returns.
    var pendingFaceName by rememberSaveable { mutableStateOf<String?>(null) }
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
    var pendingDeleteFace by rememberSaveable(stateSaver = KnownFaceSaver) {
        mutableStateOf<KnownFace?>(null)
    }

    // Face whose photo gallery is open (tap the row thumbnail).
    var galleryFace by rememberSaveable(stateSaver = KnownFaceSaver) {
        mutableStateOf<KnownFace?>(null)
    }
    // Absolute path of the gallery photo shown zoomed, if any.
    var zoomPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Gallery photo awaiting delete confirmation (index into the face's photos).
    var pendingDeletePhotoFace by rememberSaveable(stateSaver = KnownFaceSaver) {
        mutableStateOf<KnownFace?>(null)
    }
    var pendingDeletePhotoIndex by rememberSaveable { mutableStateOf(-1) }

    // Raw LiveView port text: the typed Int in the draft can't represent a
    // cleared/in-progress field, so keep the raw string here and parse on
    // save (invalid input falls back to the last valid port).
    var livePortText by rememberSaveable { mutableStateOf<String?>(null) }
    // Last non-blank LiveView username, restored when re-enabling auth so a
    // custom name survives an off/on toggle instead of becoming "admin".
    var lastLvUsername by rememberSaveable { mutableStateOf("admin") }

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(
                                "Settings",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("settingsTitle"),
                            )
                        }
                        OutlinedTextField(
                            value = current.cameraName,
                            onValueChange = { name -> viewModel.update { it.copy(cameraName = name.take(20)) } },
                            label = { Text("Camera name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Text(
                                    "${current.cameraName.length}/20",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("cameraNameCounter"),
                                )
                            },
                        )
                        // Camera list comes from the ViewModel (loaded once off
                        // the main thread); empty until enumeration finishes.
                        val cameras by viewModel.availableCameras.collectAsState()
                        DropdownField(
                            label = "Camera",
                            selected = cameras.firstOrNull { it.id == current.cameraId }?.label
                                ?: current.cameraId,
                            options = cameras.map { it.id to it.label },
                            testTag = "cameraDropdown",
                            onSelect = { id -> viewModel.update { it.copy(cameraId = id) } },
                        )
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .testTag("cameraDetectorsDivider"),
                        )
                        CollapsibleSection("Detectors", summary = detectorSummary(current)) {
                            Column {
                                BodyText(
                                    "• Sensitivity: higher catches more (1 = strict, " +
                                        "20 = catches everything)",
                                )
                                BodyText(
                                    "• Persistence: consecutive frames before triggering",
                                )
                                BodyText(
                                    "• Cooldown: minimum gap between triggers",
                                )
                            }
                            if (yoloDetectorCount(current) >= 2) {
                                BodyText(
                                    "Tip: on older phones multiple vision detectors can " +
                                        "stutter the preview during motion — lower " +
                                        "Detection speed in Advanced settings.",
                                )
                            }
                            detectorGroup(
                                "Camera",
                                current,
                                cameraDetectorOrder,
                                faceExtraContent = {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Face Recognition",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(
                                            top = 4.dp,
                                            bottom = 4.dp,
                                        ),
                                    )
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
                                                        .padding(
                                                            horizontal = 12.dp,
                                                            vertical = 8.dp,
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clickable {
                                                                galleryFace = face
                                                            }
                                                            .testTag(
                                                                "faceThumbnail_${face.id}",
                                                            ),
                                                    ) {
                                                        FaceThumbnail(
                                                            file = viewModel.thumbFile(face.id),
                                                            label = face.label,
                                                        )
                                                    }
                                                    Spacer(Modifier.width(12.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(face.label)
                                                        val photoCount = produceState(
                                                            0,
                                                            face.id,
                                                            message,
                                                        ) {
                                                            value = viewModel.facePhotoCount(face.id)
                                                        }
                                                        if (photoCount.value > 0) {
                                                            Text(
                                                                "${photoCount.value} photo" +
                                                                    if (photoCount.value == 1) "" else "s",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme
                                                                    .colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.startSampleCapture(face)
                                                        },
                                                        enabled = !isEnrolling,
                                                        modifier = Modifier.testTag(
                                                            "addSample_${face.id}",
                                                        ),
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.AddPhotoAlternate,
                                                            contentDescription =
                                                                "Add photos of ${face.label}",
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { pendingDeleteFace = face },
                                                        modifier = Modifier.testTag(
                                                            "deleteFace_${face.id}",
                                                        ),
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.Delete,
                                                            contentDescription =
                                                                "Delete ${face.label}",
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
                                            shape = AppButtonShape,
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Add face")
                                        }
                                    }
                                },
                            ) { _, next ->
                                viewModel.update { it.withDetectorConfig(next) }
                            }
                            detectorGroup("Audio", current, audioGeneralOrder) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                            detectorGroup("Combined", current, combinedPetOrder) { type, next ->
                                viewModel.update { it.copy(detectorConfigs = it.detectorConfigs + (type to next)) }
                            }
                        }
                        CollapsibleSection(
                            "Notification Channels",
                            summary = run {
                                val nonLog = current.channelConfigs.filter { it.type != ChannelTypes.LOG }
                                val active = nonLog.count { it.enabled }
                                "$active/${nonLog.size} active"
                            },
                        ) {
                            if (current.channelConfigs.none { it.type != ChannelTypes.LOG }) {
                                BodyText("No notification channels yet — add one below.")
                            }
                            // Email first: it's the primary alert channel. Stable sort
                            // keeps the stored relative order of everything else,
                            // regardless of the persisted blob order.
                            val orderedChannels = current.channelConfigs.sortedBy { config ->
                                if (config.type == ChannelTypes.EMAIL) 0 else 1
                            }
                            for (config in orderedChannels) {
                                if (config.type != ChannelTypes.LOG) {
                                    ChannelCard(
                                        config = config,
                                        fields = fields,
                                        siblings = current.channelConfigs,
                                        onEnabledChange = { enabled ->
                                            viewModel.update { settings ->
                                                settings.copy(
                                                    channelConfigs = settings.channelConfigs.map {
                                                        if (it.id == config.id) it.copy(enabled = enabled) else it
                                                    },
                                                )
                                            }
                                        },
                                        onLabelChange = { label ->
                                            viewModel.update { settings ->
                                                settings.copy(
                                                    channelConfigs = settings.channelConfigs.map {
                                                        if (it.id == config.id) it.copy(label = label) else it
                                                    },
                                                )
                                            }
                                        },
                                        onSendTest = { merged ->
                                            viewModel.sendTestFromUi(merged)
                                        },
                                        onPreviewChange = { v ->
                                            viewModel.update { settings ->
                                                settings.copy(
                                                    channelConfigs = settings.channelConfigs.map {
                                                        if (it.id == config.id) it.copy(pushVideoPreview = v) else it
                                                    },
                                                )
                                            }
                                        },
                                        onFrequencyChange = { mode, seconds ->
                                            viewModel.update { settings ->
                                                settings.copy(
                                                    channelConfigs = settings.channelConfigs.map {
                                                        if (it.id == config.id) {
                                                            it.copy(alertMode = mode, alertEverySeconds = seconds)
                                                        } else {
                                                            it
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                        onDelete = {
                                            viewModel.update { settings ->
                                                settings.copy(
                                                    channelConfigs = settings.channelConfigs.filterNot {
                                                        it.id == config.id
                                                    },
                                                )
                                            }
                                        },
                                        inFlight = sendingTestId == config.id,
                                        sendingDisabled = sendingTestId != null,
                                        factories = viewModel.testFactories,
                                        testPreviewUrl = testPreview?.takeIf { it.channelId == config.id }?.url,
                                    )
                                }
                            }
                            DropdownField(
                                label = "Add Notification Channel",
                                selected = "",
                                options = multiAccountTypes.sorted()
                                    .map { it to "${channelTitle(it)} account" },
                                testTag = "addChannel",
                                onSelect = { type ->
                                    viewModel.update { settings ->
                                        val id = nextFreeChannelId(
                                            type,
                                            settings.channelConfigs.map { it.id }.toSet(),
                                        )
                                        settings.copy(
                                            channelConfigs = settings.channelConfigs +
                                                io.securitycam.level2.core.ChannelConfig(
                                                    id = id,
                                                    type = type,
                                                    enabled = false,
                                                ),
                                        )
                                    }
                                },
                            )
                        }
                        CollapsibleSection("Video clips", summary = if (current.recordVideo) "on" else "off") {
                            SwitchRow(
                                title = "Record video locally",
                                subtitle = "Save a clip to your gallery for each event. Off saves storage and battery.",
                                checked = current.recordVideo,
                                onCheckedChange = { v -> viewModel.update { it.copy(recordVideo = v) } },
                            )
                            Spacer(Modifier.height(8.dp))
                            DropdownField(
                                label = "Video resolution",
                                selected = VideoQuality.label(current.videoQuality),
                                options = VideoQuality.values.map { it to VideoQuality.label(it) },
                                enabled = current.recordVideo,
                                testTag = "videoQualityDropdown",
                                onSelect = { q -> viewModel.update { it.copy(videoQuality = q) } },
                            )
                            Spacer(Modifier.height(8.dp))
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
                            Spacer(Modifier.height(8.dp))
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
                            Spacer(Modifier.height(8.dp))
                            SwitchRow(
                                title = "Privacy mask",
                                subtitle = "Obscure exclusion zones in recorded clips",
                                checked = current.privacyMasking,
                                onCheckedChange = { v ->
                                    viewModel.update { it.copy(privacyMasking = v) }
                                },
                            )
                            if (current.privacyMasking) {
                                Spacer(Modifier.height(8.dp))
                                DropdownField(
                                    label = "Mask effect",
                                    selected = io.securitycam.level2.core.PrivacyMaskEffect.label(current.privacyMaskEffect),
                                    options = io.securitycam.level2.core.PrivacyMaskEffect.values.map {
                                        it to io.securitycam.level2.core.PrivacyMaskEffect.label(it)
                                    },
                                    testTag = "privacyMaskEffect",
                                    onSelect = { e ->
                                        viewModel.update { it.copy(privacyMaskEffect = e) }
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Watermark",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
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
                                Spacer(Modifier.height(8.dp))
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
                                Spacer(Modifier.height(8.dp))
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
                        }
                        CollapsibleSection("Zones", summary = zonesSummary(current)) {
                            BodyText(
                                "Optional inclusion zones: motion/face only triggers inside them. " +
                                    "Empty = detect everywhere.",
                            )
                            Spacer(Modifier.height(8.dp))
                            Card {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onOpenZoneEditor)
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.CropFree, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        if (current.detectionZones.isEmpty()) {
                                            "No zones — detecting everywhere"
                                        } else {
                                            "${current.detectionZones.size} zone" +
                                                if (current.detectionZones.size == 1) "" else "s"
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                                }
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
                                ChipRow(
                                    options = listOf(LiveViewModes.SERVER to "Server", LiveViewModes.PUSH to "Push"),
                                    selected = current.liveView.mode,
                                    onSelect = { value ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(mode = value)) }
                                    },
                                )
                                if (current.liveView.mode == LiveViewModes.SERVER) {
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
                                        value = livePortText ?: current.liveView.port.toString(),
                                        onValueChange = { v ->
                                            // Keep the raw string so the field
                                            // stays clearable; only valid
                                            // ports reach the draft (the save
                                            // button re-parses with fallback).
                                            livePortText = v
                                            v.trim().toIntOrNull()
                                                ?.takeIf { it in 1..65535 }
                                                ?.let { port ->
                                                    viewModel.update {
                                                        it.copy(
                                                            liveView = it.liveView.copy(
                                                                port = port,
                                                            ),
                                                        )
                                                    }
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
                                            if (!v && current.liveView.username.isNotBlank()) {
                                                lastLvUsername = current.liveView.username
                                            }
                                            viewModel.update {
                                                it.copy(liveView = it.liveView.copy(
                                                    username = if (v) {
                                                        it.liveView.username.ifBlank {
                                                            lastLvUsername.ifBlank { "admin" }
                                                        }
                                                    } else {
                                                        ""
                                                    },
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
                                if (current.liveView.mode == LiveViewModes.PUSH) {
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
                                SwitchRow(
                                    title = "Talk-back",
                                    subtitle = "Let RTSP client speak through phone speaker",
                                    checked = current.liveView.talkBackEnabled,
                                    onCheckedChange = { v ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(talkBackEnabled = v)) }
                                    },
                                )
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
                                shape = AppButtonShape,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Add window")
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
                                ChipRow(
                                    options = listOf(CloudBackends.WEBDAV to "WebDAV", CloudBackends.S3 to "S3"),
                                    selected = current.cloudBackup.backend,
                                    onSelect = { value ->
                                        viewModel.update {
                                            it.copy(cloudBackup = it.cloudBackup.copy(backend = value))
                                        }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = current.cloudBackup.serverUrl,
                                    onValueChange = { v ->
                                        viewModel.update { it.copy(cloudBackup = it.cloudBackup.copy(serverUrl = v)) }
                                    },
                                    label = {
                                        Text(if (current.cloudBackup.backend == CloudBackends.S3) "Endpoint URL" else "Server URL")
                                    },
                                    placeholder = {
                                        Text(
                                            if (current.cloudBackup.backend == CloudBackends.S3) {
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
                                        Text(if (current.cloudBackup.backend == CloudBackends.S3) "Bucket" else "Remote folder")
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cloudBackupBucket"),
                                )
                                if (current.cloudBackup.backend == CloudBackends.S3) {
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
                                        Text(if (current.cloudBackup.backend == CloudBackends.S3) "Access key ID" else "Username")
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
                                        Text(if (current.cloudBackup.backend == CloudBackends.S3) "Secret access key" else "Password")
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
                                FilledTonalButton(
                                    onClick = { viewModel.validateCloudBackup() },
                                    shape = AppButtonShape,
                                ) {
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
                            val clearOptions = buildList {
                                add(24 to "24 hours")
                                add(48 to "48 hours")
                                if (current.retentionDays > 0) {
                                    val days = current.retentionDays
                                    val dayLabel = if (days == 1) "1 day" else "$days days"
                                    add(days * 24 to "$dayLabel (retention)")
                                }
                            }
                            // A retention change can invalidate the picked
                            // duration; reset rather than crash or clear the
                            // wrong window.
                            LaunchedEffect(clearOptions) {
                                if (clearOptions.none { it.first == clearDurationHours }) {
                                    clearDurationHours = 24
                                }
                            }
                            DropdownField(
                                label = "Clear events older than",
                                // The retention slider can invalidate a
                                // previously picked duration; fall back to
                                // the first option instead of crashing.
                                selected = clearOptions.firstOrNull { it.first == clearDurationHours }?.second
                                    ?: clearOptions.first().second,
                                options = clearOptions.map { it.second to it.second },
                                testTag = "clearEventsOlderThan",
                                onSelect = { label ->
                                    clearDurationHours = clearOptions.first { it.second == label }.first
                                },
                            )
                            FilledTonalButton(
                                onClick = { pendingClear = ClearRequest(all = false, hours = clearDurationHours) },
                                shape = AppButtonShape,
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Clear events")
                            }
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = { pendingClear = ClearRequest(all = true) },
                                shape = AppButtonShape,
                            ) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Clear all events")
                            }
                        }
                        CollapsibleSection("Advanced") {
                            current.detectorConfigs[TriggerType.health]?.let { health ->
                                SwitchRow(
                                    title = "Heartbeat",
                                    subtitle = DetectorType.fromKey(TriggerType.health)?.hint.orEmpty(),
                                    checked = health.enabled,
                                    onCheckedChange = { v ->
                                        viewModel.update {
                                            it.copy(
                                                detectorConfigs = it.detectorConfigs +
                                                    (TriggerType.health to health.copy(enabled = v)),
                                            )
                                        }
                                    },
                                    testTag = "heartbeatSwitch",
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onOpenAlertLog)
                                    .padding(vertical = 12.dp)
                                    .testTag("openAlertLog"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Terminal, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "View Alert Log",
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Filled.ChevronRight, contentDescription = null)
                            }
                            Spacer(Modifier.height(16.dp))
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
                                modifier = Modifier.testTag("mergeWindowSlider"),
                            )
                            BodyText(
                                "When multiple triggers fire within this window, they are " +
                                    "grouped into a single notification to reduce noise.",
                            )
                            Spacer(Modifier.height(16.dp))
                            BodyText(
                                "Analysis stream resolution: higher = better far-face detection " +
                                    "but more battery. Balanced is a good default.",
                            )
                            Spacer(Modifier.height(16.dp))
                            DropdownField(
                                label = "Analysis resolution",
                                selected = AnalysisResolution.label(current.analysisResolution),
                                options = AnalysisResolution.values.map { it to AnalysisResolution.label(it) },
                                testTag = "analysisResolutionDropdown",
                                onSelect = { r -> viewModel.update { it.copy(analysisResolution = r) } },
                            )
                            Spacer(Modifier.height(16.dp))
                            BodyText(
                                "Detection speed: how often detectors re-check during " +
                                    "continuous motion. Lower is smoother on older phones " +
                                    "but adds detection delay. Takes effect when monitoring " +
                                    "restarts.",
                            )
                            DropdownField(
                                label = "Detection speed",
                                selected = DetectionSpeed.label(current.detectionSpeed),
                                options = DetectionSpeed.values.map { it to DetectionSpeed.label(it) },
                                testTag = "detectionSpeedDropdown",
                                onSelect = { v -> viewModel.update { it.copy(detectionSpeed = v) } },
                            )
                            HorizontalDivider()
                            BodyText(
                                "Video preview: a short preview of the recorded " +
                                    "clip pushed with each alert when a channel's preview toggle is on.",
                            )
                            DropdownField(
                                label = "Preview mode",
                                selected = if (current.previewMode == PreviewMode.VIDEO) "Video (MP4)" else "Contact sheet (6×10)",
                                options = listOf(PreviewMode.VIDEO.name to "Video (MP4)", PreviewMode.SHEET.name to "Contact sheet (6×10)"),
                                testTag = "previewModeDropdown",
                                onSelect = { v -> viewModel.update { it.copy(previewMode = PreviewMode.valueOf(v)) } },
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Preview frame rate: ${current.previewFps} fps")
                            Slider(
                                value = current.previewFps.toFloat(),
                                onValueChange = { v ->
                                    viewModel.update {
                                        it.copy(previewFps = VideoPreview.clampFps(v.round()))
                                    }
                                },
                                valueRange = VideoPreview.MIN_FPS.toFloat()..VideoPreview.MAX_FPS.toFloat(),
                                steps = VideoPreview.MAX_FPS - VideoPreview.MIN_FPS - 1,
                                modifier = Modifier.testTag("previewFpsSlider"),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Preview width: ${current.previewMaxWidthPx} px")
                            Slider(
                                value = current.previewMaxWidthPx.toFloat(),
                                onValueChange = { v ->
                                    viewModel.update {
                                        it.copy(previewMaxWidthPx = VideoPreview.clampWidth(v.round()))
                                    }
                                },
                                valueRange = VideoPreview.MIN_WIDTH.toFloat()..VideoPreview.MAX_WIDTH.toFloat(),
                                steps = (VideoPreview.MAX_WIDTH - VideoPreview.MIN_WIDTH) / 16 - 1,
                                modifier = Modifier.testTag("previewWidthSlider"),
                            )
                        }
                        HorizontalDivider()
                        Text(
                            "About Level 2",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 8.dp)
                                .testTag(sectionTag("About Level 2")),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        BodyText(
                            "Everyone has the right to feel safe and secure. Level 2, " +
                                "a free, privacy-first security camera application was created " +
                                "to provide advanced security camera use. It has advanced " +
                                "features beyond motion detection such as person detection, " +
                                "face recognition, dog/cat detection (including their noises), " +
                                "schedules, private uploading of your videos and much more. If " +
                                "anything is broken or you'd like to suggest a feature, feel " +
                                "free to create an issue on github.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/lazyarse/level2"),
                                    )
                                    ctx.startActivity(intent)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "github.com/lazyarse/level2",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    ScrollbarThumb(scrollState, Modifier.align(Alignment.CenterEnd))
                }
                HorizontalDivider()
                Button(
                    onClick = {
                        // Fold raw field state into typed channel configs at commit
                        // time (Dart `_save`), then persist the draft. The raw
                        // LiveView port text is parsed here too; garbage falls
                        // back to the last valid port (same as email in
                        // buildChannelConfigs).
                        viewModel.update { draftNow ->
                            val parsedPort = livePortText?.trim()?.toIntOrNull()
                                ?.takeIf { it in 1..65535 }
                                ?: draftNow.liveView.port
                            draftNow.copy(
                                liveView = draftNow.liveView.copy(port = parsedPort),
                                channelConfigs = buildChannelConfigs(draftNow.channelConfigs, fields),
                            )
                        }
                        livePortText = null
                        viewModel.save()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("saveSettings"),
                    shape = AppButtonShape,
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
                            val label = if (request.hours >= 24 && request.hours % 24 == 0) {
                                "${request.hours / 24}d"
                            } else {
                                "${request.hours}h"
                            }
                            "Delete events older than $label and their snapshots and videos?"
                        },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearEvents(if (all) null else Duration.ofHours(request.hours.toLong()))
                            pendingClear = null
                        },
                        shape = AppButtonShape,
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingClear = null },
                        shape = AppButtonShape,
                    ) { Text("Cancel") }
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
                        shape = AppButtonShape,
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDeleteFace = null },
                        shape = AppButtonShape,
                    ) { Text("Cancel") }
                },
            )
        }

        galleryFace?.let { face ->
            FaceGalleryDialog(
                face = face,
                messageKey = message,
                photoFiles = { viewModel.listFacePhotos(face.id) },
                photoIndex = { file -> viewModel.photoIndexOf(face.id, file) },
                onClose = { galleryFace = null },
                onZoom = { file -> zoomPhotoPath = file.absolutePath },
                onDelete = { index ->
                    pendingDeletePhotoFace = face
                    pendingDeletePhotoIndex = index
                },
            )
        }

        zoomPhotoPath?.let { path ->
            val zoomBitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null,
                key1 = path,
            ) {
                value = withContext(Dispatchers.IO) {
                    runCatching { java.io.File(path).readBytes() }
                        .getOrNull()?.let { decodeUpright(it) }
                }
            }
            ZoomableSnapshotDialog(
                bitmap = zoomBitmap,
                loading = zoomBitmap == null,
                title = galleryFace?.label ?: "Photo",
                onClose = { zoomPhotoPath = null },
                closeTag = "galleryPhotoClose",
            )
        }

        val deletePhotoFace = pendingDeletePhotoFace
        if (deletePhotoFace != null && pendingDeletePhotoIndex >= 0) {
            AlertDialog(
                onDismissRequest = {
                    pendingDeletePhotoFace = null
                    pendingDeletePhotoIndex = -1
                },
                title = { Text("Remove this photo?") },
                text = {
                    Text(
                        "It will be deleted and no longer used for recognition.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteFacePhoto(
                                deletePhotoFace,
                                pendingDeletePhotoIndex,
                            )
                            pendingDeletePhotoFace = null
                            pendingDeletePhotoIndex = -1
                        },
                        modifier = Modifier.testTag("confirmDeletePhoto"),
                        shape = AppButtonShape,
                    ) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingDeletePhotoFace = null
                            pendingDeletePhotoIndex = -1
                        },
                        shape = AppButtonShape,
                    ) { Text("Cancel") }
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
                        shape = AppButtonShape,
                    ) { Text("Enrol") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddFaceDialog = false; faceEnrollName = "" },
                        shape = AppButtonShape,
                    ) {
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
private data class ClearRequest(val all: Boolean, val hours: Int = 24)

/** Bundle-safe saver so the clear-events dialog survives rotation. */
private val ClearRequestSaver: Saver<ClearRequest?, Any> = listSaver<ClearRequest?, Any>(
    save = { request ->
        if (request == null) emptyList() else listOf(request.all, request.hours)
    },
    restore = { saved ->
        if (saved.isEmpty()) null
        else ClearRequest(all = saved[0] as Boolean, hours = (saved[1] as Int))
    },
)

/** Bundle-safe saver so the pending face-delete dialog survives rotation. */
private val KnownFaceSaver: Saver<KnownFace?, Any> = listSaver<KnownFace?, Any>(
    save = { face ->
        if (face == null) emptyList() else listOf(face.id, face.label)
    },
    restore = { saved ->
        if (saved.isEmpty()) null
        else KnownFace(id = saved[0] as String, label = saved[1] as String)
    },
)

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
                        Text(
                            "Sight sensitivity: $sightSensitivity/20 " +
                                "(${SensitivityScale.label(sightSensitivity)})",
                        )
                        Slider(
                            value = sightSensitivity.toFloat(),
                            onValueChange = { v -> onChanged(config.copy(
                                threshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.round(),
                                ),
                                audioThreshold = config.audioThreshold ?: config.threshold,
                            )) },
                            valueRange = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            modifier = Modifier.testTag("threshold_${config.type}"),
                        )
                        val audioThreshold = config.audioThreshold ?: config.threshold
                        val soundSensitivity =
                            SensitivityScale.thresholdToSensitivity(config.type, audioThreshold)
                        Text(
                            "Sound sensitivity: $soundSensitivity/20 " +
                                "(${SensitivityScale.label(soundSensitivity)})",
                        )
                        Slider(
                            value = soundSensitivity.toFloat(),
                            onValueChange = { v -> onChanged(config.copy(
                                audioThreshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.round(),
                                ),
                            )) },
                            valueRange = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            modifier = Modifier.testTag("audioThreshold_${config.type}"),
                        )
                    } else {
                        val sensitivity =
                            SensitivityScale.thresholdToSensitivity(config.type, config.threshold)
                        Text(
                            "Sensitivity: $sensitivity/20 " +
                                "(${SensitivityScale.label(sensitivity)})",
                        )
                        Slider(
                            value = sensitivity.toFloat(),
                            onValueChange = { v -> onChanged(config.copy(
                                threshold = SensitivityScale.sensitivityToThreshold(
                                    config.type,
                                    v.round(),
                                ),
                            )) },
                            valueRange = SensitivityScale.MIN.toFloat()..SensitivityScale.MAX.toFloat(),
                            steps = 18,
                            modifier = Modifier.testTag("threshold_${config.type}"),
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

private fun detectorLabel(type: String): String =
    DetectorType.fromKey(type)?.label ?: type

/**
 * Fixed motion-gating behavior per detector (the pipeline rule, not a user
 * option): vision detectors sleep until motion fires, tamper must see every
 * frame, and sound is never gated on vision.
 */
private fun motionGateNote(type: String): String? = when (type) {
    TriggerType.motion, TriggerType.health,
    TriggerType.babyCry, TriggerType.glassBreak, TriggerType.loudNoise -> null
    TriggerType.tamper -> "Runs on every frame — tamper needs to see still frames too."
    TriggerType.dog, TriggerType.cat ->
        "Sight runs after motion is detected (saves battery); sound is always listening."
    else -> "Runs after motion is detected (saves battery)."
}

@Composable
private fun ChannelCard(
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $name?") },
            text = { Text("Remove this ${channelTitle(config.type)} account?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        confirmDelete = false
                    },
                    shape = AppButtonShape,
                    modifier = Modifier.testTag("confirmDeleteChannel_${config.id}"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    shape = AppButtonShape,
                ) { Text("Cancel") }
            },
        )
    }
}

/** Expanded channel card contents: type-specific fields plus the test sender. */
@Composable
private fun ChannelBody(
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

private val pushoverPriorityOptions: List<Pair<String, String>> = listOf(
    "-2" to "Lowest (-2) — no sound/vibrate",
    "-1" to "Low (-1) — quiet",
    "0" to "Normal (0)",
    "1" to "High (1) — bypass quiet hours",
    "2" to "Emergency (2) — require acknowledgement",
)

private fun pushoverPriorityVerboseLabel(value: String): String =
    pushoverPriorityOptions.firstOrNull { it.first == value }?.second
        ?: "Normal (0)"

private typealias SetField = (String, String) -> Unit

/**
 * Sandbox preview link from the last email test send (Ethereal.email caught
 * message). Selectable + copyable so the tester can open it in a browser and
 * verify the message manually; sandbox links expire after a few hours.
 */
@Composable
private fun TestPreviewRow(url: String, channelId: String) {
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
private fun ChannelTextField(
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

/**
 * Stable tag qualified by channel id (mirrors the Dart `_fieldOf(label)`
 * finder strategy, plus the account): two expanded same-type cards must
 * never share a tag.
 */
internal fun fieldTag(channelId: String, label: String): String =
    "field_${channelId}_" + label.lowercase().replace(Regex("[^a-z0-9]+"), "_")

@Composable
private fun DropdownField(
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

@Composable
private fun SwitchRow(
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
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

private fun mergeLabel(window: Duration): String =
    if (window.isZero) "Off" else "${window.toSeconds()}s"

/** Alert-frequency presets for the per-channel dropdown (value + label). */
private data class FrequencyOption(val mode: String, val seconds: Int, val label: String)

private val alertFrequencyOptions: List<FrequencyOption>
    get() = listOf(
        FrequencyOption(io.securitycam.level2.core.AlertMode.EVERY_TRIGGER, 0, "Every trigger"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.PER_WAVE, 0, "Once per wave"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 15, "Every 15 seconds"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 30, "Every 30 seconds"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 60, "Every minute"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 120, "Every 2 minutes"),
        FrequencyOption(io.securitycam.level2.core.AlertMode.THROTTLED, 300, "Every 5 minutes"),
    )

private fun alertFrequencyLabel(config: io.securitycam.level2.core.ChannelConfig): String =
    alertFrequencyOptions.firstOrNull { it.mode == config.alertMode && it.seconds == config.alertEverySeconds }
        ?.label
        ?: alertFrequencyOptions.firstOrNull { it.mode == config.alertMode }?.label
        ?: "Every trigger"

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

/** Zones section summary: inclusion/exclusion counts. */
private fun zonesSummary(settings: AppSettings): String {
    val inc = settings.detectionZones.size
    val ex = settings.exclusionZones.size
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

/** One-line explainer under each detector group heading (health has none). */
private fun detectorGroupHint(label: String): String? = when (label) {
    "Camera" -> "Spot things the camera sees — motion, people, faces, vehicles, animals and tampering."
    "Audio" -> "Listen for sounds — loud noises, glass breaking and baby cries."
    "Combined" -> "Use the camera and microphone together — pets seen or heard."
    else -> null
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.detectorGroup(
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

private fun retentionSummary(days: Int): String =
    if (days == 0) "retention off" else "$days day" + if (days == 1) "" else "s"

/**
 * Enabled detectors sharing the single YOLO inference pass (face runs on
 * MediaPipe instead; loitering/tripwire reuse other detectors' boxes).
 * Drives the multi-detector performance hint.
 */
private val yoloDetectorTypes = setOf(
    TriggerType.person,
    TriggerType.vehicle,
    TriggerType.dog,
    TriggerType.cat,
    TriggerType.bird,
    TriggerType.livestock,
)

private fun yoloDetectorCount(settings: AppSettings): Int =
    settings.detectorConfigs.count { (type, config) ->
        type in yoloDetectorTypes && config.enabled
    }

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

private fun cloudBackupSummary(cb: io.securitycam.level2.core.CloudBackupSettings): String {
    if (!cb.enabled) return "off"
    val backend = if (cb.backend == CloudBackends.S3) CloudBackends.S3 else CloudBackends.WEBDAV
    val kinds = buildList {
        if (cb.backupClips) add("clips")
        if (cb.backupSnapshots) add("snaps")
    }
    return "$backend" + if (kinds.isEmpty()) "" else " (${kinds.joinToString("+")})"
}

private fun liveViewSummary(lv: LiveViewSettings): String {
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

/** Enrolled-face thumbnail decoded from disk; falls back to a face icon. */
@Composable
private fun FaceThumbnail(file: java.io.File?, label: String, size: Dp = 48.dp) {
    if (file == null) {
        Icon(Icons.Filled.Face, contentDescription = label)
        return
    }
    // Cached decode keyed by absolute path; synchronous peek renders
    // previously-seen faces in first frame while scrolling.
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = io.securitycam.level2.ui.events.ThumbCache.peek("face:${file.absolutePath}"),
        key1 = file.absolutePath,
    ) {
        val f = file
        if (value == null) {
            value = io.securitycam.level2.ui.events.ThumbCache.getOrLoad(
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
                .size(size)
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

/**
 * Scrollable per-face photo list. Tap a photo to zoom; the delete action is
 * hidden for a lone photo (the row trash removes whole faces instead). The
 * photo list re-reads on [messageKey] so a delete confirm refreshes the rows.
 */
@Composable
private fun FaceGalleryDialog(
    face: KnownFace,
    messageKey: String?,
    photoFiles: () -> List<java.io.File>,
    photoIndex: (java.io.File) -> Int?,
    onClose: () -> Unit,
    onZoom: (java.io.File) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val photos by produceState(
        initialValue = emptyList<java.io.File>(),
        key1 = face.id,
        key2 = messageKey,
    ) {
        value = runCatching { photoFiles() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("faceGalleryDialog"),
        title = { Text("Photos of ${face.label}") },
        text = {
            if (photos.isEmpty()) {
                Text(
                    "No photos yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(photos, key = { it.absolutePath }) { photo ->
                        val index = photoIndex(photo)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clickable { onZoom(photo) }
                                    .testTag("galleryPhoto_${face.id}_$index"),
                            ) {
                                FaceThumbnail(
                                    file = photo,
                                    label = face.label,
                                    size = 64.dp,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (photos.size > 1 && index != null) {
                                IconButton(
                                    onClick = { onDelete(index) },
                                    modifier = Modifier.testTag(
                                        "deletePhoto_${face.id}_$index",
                                    ),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete this photo",
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("closeGallery"),
                shape = AppButtonShape,
            ) { Text("Close") }
        },
    )
}
