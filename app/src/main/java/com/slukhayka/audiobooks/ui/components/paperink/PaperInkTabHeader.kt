package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
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
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    headingTestTag: String? = null,
    compact: Boolean = false,
    // Optional root actions (refresh, search…) rendered before the gear. The
    // gear stays the LAST control on every root, so its place never moves.
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 56.dp else 104.dp)
            .padding(
                horizontal = PaperInkDimens.SpacePage,
                vertical = if (compact) 4.dp else 20.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (headingTestTag != null) Modifier.testTag(headingTestTag) else Modifier)
                .semantics { heading() },
        ) {
            if (!compact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = colorScheme.tertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = PaperInkType.TrackingLabel,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = title,
                style = if (compact) PaperInkType.SectionTitle else PaperInkType.ScreenTitle,
                color = colorScheme.onSurface,
            )
        }
        if (actions != null) actions()
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
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
