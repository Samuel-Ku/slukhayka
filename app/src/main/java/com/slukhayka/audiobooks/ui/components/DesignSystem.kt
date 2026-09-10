package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * Design-system primitives (wayfinder #23): one set of section headers and
 * empty states for the whole app. Implements the empty-states house standard
 * from the audit — icon + title + short explanation + one or two next actions.
 * Content is separated by spacing and typography, never by nesting cards.
 */

// The canonical section header moved to SectionHeaders.kt (v1.4 C1,
// ADR-0033): two levels (group/section) with optional counter and action
// slots. This file keeps the canonical empty states.

/**
 * Full-size empty state: 56 dp icon, title, explanation and (per the house
 * standard) one or two next actions. Pass an [actions] block to render the
 * CTA column; without one the column is omitted entirely.
 *
 * v1.4 C4 (ADR-0033): [stateDescription] lets a transient state announce its
 * nature («Помилка») on the same title node that carries the polite
 * live-region; [iconContent] replaces the static icon with a live indicator
 * (a spinner) for the loading facade — one empty-state shape app-wide.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    // [iconContent] precedes [actions] deliberately: `actions` must stay the
    // LAST parameter so trailing-lambda call sites keep binding to it.
    iconContent: (@Composable () -> Unit)? = null,
    stateDescription: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.SpaceXl, vertical = AppDimens.SpaceSection),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (iconContent != null) {
                    iconContent()
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AppDimens.SpaceMd))
        // v1.4 C4 (ADR-0033): an empty state is transient — it announces
        // itself politely (screen readers) the moment it appears.
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                if (stateDescription != null) this.stateDescription = stateDescription
            }
        )
        if (body.isNotBlank()) {
            Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actions != null) {
            Spacer(modifier = Modifier.height(AppDimens.SpaceXl))
            actions()
        }
    }
}

/** Compact empty state for list sub-tabs: 40 dp icon, title, one-line body, optional trailing action. */
@Composable
fun EmptyStateRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (action != null) action()
    }
}
