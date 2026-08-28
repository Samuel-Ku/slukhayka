package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/**
 * #400 — ★/☆ bookmark button for a person (author or narrator).
 *
 * Tap toggles the bookmark; long-press on a bookmarked person opens a menu
 * to toggle [notifyEnabled] without removing the bookmark.
 *
 * Touch target is 48×48 dp (Material Design minimum).
 * Accessibility semantics follow the [FavoriteButton] pattern.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonBookmarkButton(
    isBookmarked: Boolean,
    notifyEnabled: Boolean,
    personName: String,
    onToggle: () -> Unit,
    onToggleNotify: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val actionDescription = stringResource(
        if (isBookmarked) R.string.person_bookmark_remove else R.string.person_bookmark_add,
        personName
    )
    val currentState = stringResource(
        if (isBookmarked) R.string.person_bookmark_on else R.string.person_bookmark_off
    )

    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(48.dp)
            .testTag("person_bookmark_button")
            .combinedClickable(
                onClick = onToggle,
                onLongClick = {
                    if (isBookmarked) showMenu = true
                },
                role = Role.Button
            )
    ) {
        Icon(
            imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = null,
            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(24.dp)
                .testTag("person_bookmark_icon")
        )
    }

    if (isBookmarked) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (notifyEnabled) {
                            stringResource(R.string.person_bookmark_notify_disable)
                        } else {
                            stringResource(R.string.person_bookmark_notify_enable)
                        }
                    )
                },
                onClick = {
                    showMenu = false
                    onToggleNotify(!notifyEnabled)
                },
                modifier = Modifier.testTag("person_bookmark_notify_toggle")
            )
        }
    }
}
