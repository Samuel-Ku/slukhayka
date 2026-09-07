package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/** One home for the existing settings destinations; preferences stay in their modules. */
@Composable
internal fun SettingsScreen(
    returnDestination: SettingsDestination? = null,
    onOpen: (SettingsDestination) -> Unit
) {
    val title = stringResource(R.string.nav_settings)
    val destinations = remember {
        listOf(
            SettingsDestination.Profile, SettingsDestination.Storage,
            SettingsDestination.NetworkPrivacy, SettingsDestination.Recommendations,
            SettingsDestination.ContentLanguages, SettingsDestination.AppLocale
        )
    }
    val headingFocus = remember { FocusRequester() }
    val rowFocus = remember { destinations.associateWith { FocusRequester() } }
    LaunchedEffect(returnDestination) {
        withFrameNanos { }
        (rowFocus[returnDestination] ?: headingFocus).requestFocus()
    }
    Column(
        Modifier.fillMaxSize().testTag("settings_screen").accessibilityPane(title)
            .verticalScroll(rememberScrollState())
    ) {
        // v1.4 C5 (ADR-0033): the same tab header as Огляд/Медіатека —
        // one headline model across the tabs; the return-focus contract
        // rides the header's title.
        AppTabHeader(
            title = title,
            returnFocusRequester = headingFocus
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            destinations.forEach { destination ->
            ListItem(
                headlineContent = { Text(stringResource(destination.titleRes)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("settings_${destination.name}")
                    .focusRequester(rowFocus.getValue(destination))
                    .focusProperties { canFocus = true }
                    .clickable(role = Role.Button) { onOpen(destination) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
