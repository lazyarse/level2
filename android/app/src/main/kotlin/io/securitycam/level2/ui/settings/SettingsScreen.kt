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
    val snackbarHostState = MessageSnackbar(message) { viewModel.consumeMessage() }

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
                            SettingSlider(
                                label = "Pre-roll: ${current.preRollSeconds}s",
                                value = current.preRollSeconds.toFloat(),
                                range = 0f..30f,
                                steps = 29,
                                testTag = "preRollSlider",
                                enabled = current.recordVideo,
                                onChange = { v ->
                                    viewModel.update { it.copy(preRollSeconds = v.roundToInt()) }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            SettingSlider(
                                label = "Post-roll: ${current.postRollSeconds}s",
                                value = current.postRollSeconds.toFloat(),
                                range = 0f..30f,
                                steps = 29,
                                testTag = "postRollSlider",
                                enabled = current.recordVideo,
                                onChange = { v ->
                                    viewModel.update { it.copy(postRollSeconds = v.roundToInt()) }
                                },
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
                                SettingSlider(
                                    label = "FPS: ${current.liveView.fps}",
                                    value = current.liveView.fps.toFloat(),
                                    range = 5f..30f,
                                    steps = 24,
                                    testTag = "liveViewFps",
                                    onChange = { v ->
                                        viewModel.update { it.copy(liveView = it.liveView.copy(fps = v.roundToInt())) }
                                    },
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
                            SettingSlider(
                                label = "Automatic retention: " +
                                    if (current.retentionDays == 0) "off"
                                    else "${current.retentionDays} day" + if (current.retentionDays == 1) "" else "s",
                                value = current.retentionDays.toFloat(),
                                range = 0f..30f,
                                steps = 29,
                                testTag = "retentionSlider",
                                onChange = { v -> viewModel.update { it.copy(retentionDays = v.roundToInt()) } },
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
                            SettingSlider(
                                label = "Merge window: ${mergeLabel(current.notificationMergeWindow)}",
                                value = current.notificationMergeWindow.toSeconds().toFloat(),
                                range = 0f..30f,
                                steps = 29,
                                testTag = "mergeWindowSlider",
                                onChange = { v ->
                                    viewModel.update {
                                        it.copy(notificationMergeWindow = Duration.ofSeconds(v.roundToInt().toLong()))
                                    }
                                },
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
                            SettingSlider(
                                label = "Preview frame rate: ${current.previewFps} fps",
                                value = current.previewFps.toFloat(),
                                range = VideoPreview.MIN_FPS.toFloat()..VideoPreview.MAX_FPS.toFloat(),
                                steps = VideoPreview.MAX_FPS - VideoPreview.MIN_FPS - 1,
                                testTag = "previewFpsSlider",
                                onChange = { v ->
                                    viewModel.update {
                                        it.copy(previewFps = VideoPreview.clampFps(v.roundToInt()))
                                    }
                                },
                            )
                            Spacer(Modifier.height(16.dp))
                            SettingSlider(
                                label = "Preview width: ${current.previewMaxWidthPx} px",
                                value = current.previewMaxWidthPx.toFloat(),
                                range = VideoPreview.MIN_WIDTH.toFloat()..VideoPreview.MAX_WIDTH.toFloat(),
                                steps = (VideoPreview.MAX_WIDTH - VideoPreview.MIN_WIDTH) / 16 - 1,
                                testTag = "previewWidthSlider",
                                onChange = { v ->
                                    viewModel.update {
                                        it.copy(previewMaxWidthPx = VideoPreview.clampWidth(v.roundToInt()))
                                    }
                                },
                            )
                        }
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
            ConfirmDialog(
                title = "Clear events",
                onDismiss = { pendingClear = null },
                onConfirm = {
                    viewModel.clearEvents(if (all) null else Duration.ofHours(request.hours.toLong()))
                    pendingClear = null
                },
                confirmLabel = "Clear",
                body = if (all) {
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
        }

        pendingDeleteFace?.let { face ->
            ConfirmDialog(
                title = "Remove ${face.label}?",
                onDismiss = { pendingDeleteFace = null },
                onConfirm = {
                    viewModel.deleteFace(face)
                    pendingDeleteFace = null
                },
                confirmLabel = "Remove",
                confirmTestTag = "confirmDeleteFace",
                body = "Their saved photo samples will be deleted and they " +
                    "will no longer be recognised.",
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
            ConfirmDialog(
                title = "Remove this photo?",
                onDismiss = {
                    pendingDeletePhotoFace = null
                    pendingDeletePhotoIndex = -1
                },
                onConfirm = {
                    viewModel.deleteFacePhoto(
                        deletePhotoFace,
                        pendingDeletePhotoIndex,
                    )
                    pendingDeletePhotoFace = null
                    pendingDeletePhotoIndex = -1
                },
                confirmLabel = "Remove",
                confirmTestTag = "confirmDeletePhoto",
                body = "It will be deleted and no longer used for recognition.",
            )
        }

        if (showAddFaceDialog) {
            ConfirmDialog(
                title = "Enrol face",
                onDismiss = { showAddFaceDialog = false; faceEnrollName = "" },
                onConfirm = {
                    val name = faceEnrollName.trim()
                    if (name.isEmpty()) return@ConfirmDialog
                    showAddFaceDialog = false
                    faceEnrollName = ""
                    // Block duplicates up-front; the row's photos icon
                    // extends an existing person instead.
                    if (current?.knownFaces
                            ?.any { it.label.equals(name, ignoreCase = true) } == true
                    ) {
                        viewModel.notifyDuplicateName(name)
                        return@ConfirmDialog
                    }
                    val missing = viewModel.missingEnrollmentPermissions()
                    if (missing.isEmpty()) {
                        viewModel.startEnrollment(name)
                    } else {
                        pendingFaceName = name
                        enrollPermissionLauncher.launch(missing.toTypedArray())
                    }
                },
                confirmLabel = "Enrol",
                confirmEnabled = faceEnrollName.trim().isNotEmpty() && !isEnrolling,
                bodyContent = {
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

