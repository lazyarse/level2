package io.securitycam.level2.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.securitycam.level2.ui.theme.AppButtonShape

/**
 * Shared expand-chevron card container for DetectorCard/ChannelCard.
 * The caller owns [expanded] state (with its own rememberSaveable key) so
 * expand/collapse persistence is unchanged; this only unifies the
 * Card/Column/header-row visuals. Pass [contentSpacing] as 8.dp for the
 * channel card's spaced body, 0.dp for the detector card's top-arranged body.
 */
@Composable
internal fun ExpandableCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    headerTestTag: String,
    expandContentDescription: String,
    collapseContentDescription: String,
    chevronLabel: String,
    cardModifier: Modifier = Modifier,
    contentSpacing: Dp = 0.dp,
    headerContent: @Composable RowScope.() -> Unit,
    bodyContent: @Composable ColumnScope.() -> Unit,
) {
    val chevron by animateFloatAsState(
        if (expanded) 180f else 0f,
        label = chevronLabel,
    )
    Card(modifier = cardModifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = if (contentSpacing > 0.dp) {
                Arrangement.spacedBy(contentSpacing)
            } else {
                Arrangement.Top
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .testTag(headerTestTag),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) {
                        collapseContentDescription
                    } else {
                        expandContentDescription
                    },
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
                Spacer(Modifier.width(8.dp))
                headerContent()
            }
            if (expanded) {
                bodyContent()
            }
        }
    }
}

/** Generic single-select chip row (LiveView mode, cloud backend). */
@Composable
internal fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * Shared title + body + confirm/dismiss dialog. Covers every confirm in
 * Settings and the zone editor: pass [body] for plain text or [bodyContent]
 * for custom content (enrol field, gallery list); [dismissLabel] = null
 * drops the dismiss button; [confirmTextButton] renders the confirm as a
 * TextButton (zone/gallery style) instead of a Button.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    bodyContent: (@Composable () -> Unit)? = null,
    confirmTestTag: String? = null,
    confirmEnabled: Boolean = true,
    confirmTextButton: Boolean = false,
    dismissLabel: String? = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            when {
                bodyContent != null -> bodyContent()
                body != null -> Text(body)
            }
        },
        confirmButton = {
            val tagModifier = if (confirmTestTag != null) {
                Modifier.testTag(confirmTestTag)
            } else {
                Modifier
            }
            if (confirmTextButton) {
                TextButton(
                    onClick = onConfirm,
                    modifier = tagModifier,
                    shape = AppButtonShape,
                ) { Text(confirmLabel) }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = tagModifier,
                    shape = AppButtonShape,
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = if (dismissLabel != null) {
            {
                TextButton(
                    onClick = onDismiss,
                    shape = AppButtonShape,
                ) { Text(dismissLabel) }
            }
        } else {
            null
        },
    )
}

/**
 * One-shot message snackbar: shows [message] once, then reports consumption.
 * Returns the host for the caller's `SnackbarHost` placement.
 */
@Composable
internal fun MessageSnackbar(
    message: String?,
    onConsumed: () -> Unit,
): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    message?.let { text ->
        LaunchedEffect(text) {
            host.showSnackbar(text)
            onConsumed()
        }
    }
    return host
}
