package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * v1.4 C1 (ADR-0033) — the canonical two-level section header; the only
 * section-heading composable in the app. The a11y heading contract rides on
 * the title itself, so TalkBack navigation by headings survives every
 * migration (ADR-0018).
 *
 * - [AppSectionHeader] `SectionHeaderLevel.GROUP` — top-level feed groups
 *   («Для вас», «Відкрити нове»): headlineSmall bold, sentence case.
 * - [AppSectionHeader] `SectionHeaderLevel.SECTION` — shelves, rails and
 *   blocks: uppercase titleSmall, optionally with a [count] subtitle
 *   (R10: «12 книг у жанрі» — the counter lives in the header, never as a
 *   free-standing row) and/or an [action] slot (a 48 dp control, e.g. the
 *   Listen block menu until ticket #570 replaces it).
 *
 * The [subtitle] slot carries a small secondary line under the title (the
 * Listen block reason, «чому це тут»); [count] and [subtitle] are mutually
 * exclusive in practice — a header shows one secondary line.
 */
enum class SectionHeaderLevel { GROUP, SECTION }

@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    level: SectionHeaderLevel = SectionHeaderLevel.SECTION,
    count: String? = null,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    val titleStyle = when (level) {
        SectionHeaderLevel.GROUP -> MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.ExtraBold
        )
        SectionHeaderLevel.SECTION -> MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppDimens.PageSides,
                end = AppDimens.PageSides,
                top = AppDimens.SpaceLg,
                bottom = AppDimens.SpaceSm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (level == SectionHeaderLevel.SECTION) title.uppercase() else title,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            val secondary = count ?: subtitle
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (action != null) action()
    }
}

/**
 * Convenience overload for the old `AppSectionHeader(title, action)` call
 * shape (the wayfinder-era section header). Rendered at the SECTION level.
 */
@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    AppSectionHeader(
        title = title,
        modifier = modifier,
        level = SectionHeaderLevel.SECTION,
        action = action
    )
}
