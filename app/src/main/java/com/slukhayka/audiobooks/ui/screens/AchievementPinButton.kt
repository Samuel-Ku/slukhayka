package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/**
 * #704 (T6) — the showcase pin toggle on an earned award.
 *
 * Deliberately the SAME shape as [PersonBookmarkButton]: ★/☆, a 48×48 dp
 * target, [contentDescription] naming the action and [stateDescription]
 * announcing the state. The spec says «a11y-контракти канонічні; нових швів не
 * вводимо», and a second way of expressing "toggle a thing" is exactly the new
 * seam it forbids.
 *
 * Only EARNED awards carry this button — you cannot showcase what you have not
 * got, and the schema enforces that independently of the screen.
 */
@Composable
fun AchievementPinButton(
    isPinned: Boolean,
    awardName: String,
    onToggle: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val actionDescription = stringResource(
        if (isPinned) R.string.achievement_pin_remove else R.string.achievement_pin_add,
        awardName
    )
    val currentState = stringResource(
        if (isPinned) R.string.achievement_pin_on else R.string.achievement_pin_off
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .testTag(testTag)
            .semantics {
                role = Role.Button
                contentDescription = actionDescription
                stateDescription = currentState
            }
            .clickable(
                role = Role.Button,
                onClickLabel = actionDescription,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPinned) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = null,
            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}
