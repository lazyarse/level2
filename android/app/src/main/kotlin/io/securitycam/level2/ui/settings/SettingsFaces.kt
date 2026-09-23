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


/** Bundle-safe saver so the pending face-delete dialog survives rotation. */
internal val KnownFaceSaver: Saver<KnownFace?, Any> = listSaver<KnownFace?, Any>(
    save = { face ->
        if (face == null) emptyList() else listOf(face.id, face.label)
    },
    restore = { saved ->
        if (saved.isEmpty()) null
        else KnownFace(id = saved[0] as String, label = saved[1] as String)
    },
)

/** Enrolled-face thumbnail decoded from disk; falls back to a face icon. */
@Composable
internal fun FaceThumbnail(file: java.io.File?, label: String, size: Dp = 48.dp) {
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
internal fun FaceGalleryDialog(
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
    ConfirmDialog(
        title = "Photos of ${face.label}",
        onDismiss = onClose,
        onConfirm = onClose,
        confirmLabel = "Close",
        modifier = Modifier.testTag("faceGalleryDialog"),
        confirmTestTag = "closeGallery",
        confirmTextButton = true,
        dismissLabel = null,
        bodyContent = {
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
    )
}
