package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * The root-tab header of the direction: wordmark eyebrow, 34/36 screen title,
 * and the settings gear on the same line. 104 dp in portrait (the measured
 * height from the prototype), one compact line in a landscape phone.
 *
 * The gear keeps the SAME tag and accessible name as the legacy header
 * (`settings_gear`, «Налаштування»), so every root keeps working identically
 * while only this screen is migrated.
 */
@Composable
fun PaperInkTabHeader(
    title: String,
    // Null on screens that are not a root (Settings): the gear is a root
    // affordance, not a decorative mark.
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    headingTestTag: String? = null,
    compact: Boolean = false,
    // A root may name what the screen holds («12 книг · 3 год 20 хв») instead
    // of leaving the space under the title empty; sections hide the wordmark.
    subtitle: String? = null,
    showBrandMark: Boolean = true,
    // Optional root actions (refresh, search…) rendered before the gear. The
    // gear stays the LAST control on every root, so its place never moves.
    actions: (@Composable RowScope.() -> Unit)? = null,
    // Focus contract: a screen that left for a sub-screen returns focus to its
    // own title, exactly as the legacy header did.
    returnFocusRequester: FocusRequester? = null,
    // A pushed screen (Settings) needs a visible way back, not only system Back.
    onBackClick: (() -> Unit)? = null,
    // F10 review (200 % text, then 320 dp, then 180 %): a compact pushed
    // header may wrap to three lines — short titles still take one, longer
    // ones grow the header instead of ellipsizing (min 56 dp stays the floor;
    // the legacy contract was 112 dp at 200 %). The pushed screen passes 3.
    maxTitleLines: Int? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 56.dp else 104.dp)
            .padding(
                horizontal = PaperInkDimens.SpacePage,
                vertical = if (compact) 4.dp else 20.dp,
            )
            .testTag("tab_header_root"),
        // F25 review: a no-brand header (e.g. the Library section view) has
        // less content than the 104 dp floor, and a plain Column top-aligns
        // it — the title then sat 8 dp above the header's centre. Centring the
        // children keeps that case on the same centre line as the brand case.
        verticalArrangement = Arrangement.Center,
    ) {
        // F25 (аудит 2026-10-28): the brand line is its OWN row. Before, the
        // wordmark and the title shared one Column, so the back arrow and the
        // gear centred on the brand+title BLOCK — 8 dp above the title's
        // centre. They now centre on the title line, where the prototype puts
        // them (`.sl-header` brand above, `.sl-header h1` + gear on one line).
        if (!compact && showBrandMark) {
            Row(
                modifier = Modifier.testTag("tab_header_brand"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PaperInkIcon(
                    imageVector = PaperInkIcons.Wave,
                    contentDescription = null,
                    size = 15.dp,
                    tint = colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = PaperInkType.TrackingLabel,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBackClick != null) {
                val backDescription = stringResource(R.string.action_back)
                Box(
                    modifier = Modifier
                        .size(PaperInkDimens.TouchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onBackClick)
                        .testTag("settings_back")
                        .semantics { contentDescription = backDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    PaperInkIcon(
                        imageVector = PaperInkIcons.ChevronLeft,
                        contentDescription = null,
                        size = PaperInkIconSizes.Chrome,
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                // The heading IS this node: its tag, its `heading()` semantics
                // and its entry focus live on the title Text, so a screen reader
                // and a test both name/land on the same element at 200 % text.
                Text(
                    text = title,
                    style = if (compact) PaperInkType.SectionTitle else PaperInkType.ScreenTitle,
                    color = colorScheme.onSurface,
                    maxLines = maxTitleLines ?: if (compact) 1 else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(if (headingTestTag != null) Modifier.testTag(headingTestTag) else Modifier)
                        .then(
                            if (returnFocusRequester != null) {
                                Modifier.focusRequester(returnFocusRequester).focusable()
                            } else {
                                Modifier
                            },
                        )
                        .semantics { heading() },
                )
            }
            if (actions != null) actions()
            if (onSettingsClick != null) {
                val settingsDescription = stringResource(R.string.a11y_open_settings)
                Box(
                    modifier = Modifier
                        .size(PaperInkDimens.TouchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onSettingsClick)
                        .testTag("settings_gear")
                        .semantics { contentDescription = settingsDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    PaperInkIcon(
                        imageVector = PaperInkIcons.Settings,
                        contentDescription = null,
                        size = PaperInkIconSizes.Chrome,
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // The subtitle sits UNDER the title line, not inside the title's
        // Column: the trailing actions centre on the title line (the Library
        // root has brand + title + subtitle), so the subtitle must not pull
        // them down.
        if (!compact && subtitle != null) {
            Text(
                text = subtitle,
                style = PaperInkType.Meta,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tab_header_subtitle"),
            )
        }
    }
}
