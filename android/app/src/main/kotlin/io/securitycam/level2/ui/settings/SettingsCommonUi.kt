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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
