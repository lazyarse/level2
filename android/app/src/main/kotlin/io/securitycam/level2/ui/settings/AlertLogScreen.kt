package io.securitycam.level2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.securitycam.level2.channels.AlertLog
import io.securitycam.level2.event.EventPipeline
import io.securitycam.level2.ui.theme.AppButtonShape
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * In-app viewer for the alert log (what the log channel delivered).
 * Opened from Settings → Advanced; mirrors the zone-editor navigation
 * pattern (full-screen overlay with an onClose callback).
 */
@Composable
fun AlertLogScreen(onClose: () -> Unit) {
    val entries by AlertLog.flow.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Alert log",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(entries.asReversed().joinToString("\n") { it.text }),
                    )
                },
                enabled = entries.isNotEmpty(),
                shape = AppButtonShape,
                modifier = Modifier.testTag("copyAlertLog"),
            ) { Text("Copy") }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { scope.launch { AlertLog.clear() } },
                enabled = entries.isNotEmpty(),
                shape = AppButtonShape,
                modifier = Modifier.testTag("clearAlertLog"),
            ) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text(
                text = "No alerts logged yet — trigger a detector or send a test alert.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("alertLogEmpty"),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("alertLogList"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries.asReversed(), key = { it.timestamp.toEpochMilli() to it.text }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = EventPipeline.ALERT_TIME_FORMAT.format(
                                    entry.timestamp.atZone(ZoneId.systemDefault()),
                                ) + " · ${entry.channelId} · ${entry.triggerType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
