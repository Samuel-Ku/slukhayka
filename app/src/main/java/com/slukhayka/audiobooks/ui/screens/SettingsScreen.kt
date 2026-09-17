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
    // spec-54 T05 (#866) — the SAME seven destinations, grouped so the listener
    // knows WHERE to look: the group name answers "where", and every row stays
    // one tap from here (so never more than two from anywhere).
    val groups = remember {
        listOf(
            SettingsGroup(R.string.settings_group_profile, listOf(SettingsDestination.Profile)),
            SettingsGroup(R.string.settings_group_data, listOf(SettingsDestination.Storage)),
            SettingsGroup(
                R.string.settings_group_network,
                listOf(SettingsDestination.NetworkPrivacy, SettingsDestination.SourceAudioRefusal)
            ),
            SettingsGroup(
                R.string.settings_group_recommendations,
                listOf(SettingsDestination.Recommendations)
            ),
            SettingsGroup(
                R.string.settings_group_language,
                listOf(SettingsDestination.ContentLanguages, SettingsDestination.AppLocale)
            )
        )
    }
    val destinations = remember(groups) { groups.flatMap { it.destinations } }
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
            groups.forEach { group ->
                Text(
                    text = stringResource(group.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 4.dp)
                        .testTag("settings_group_${group.titleRes}")
                        .semantics { heading() }
                )
                group.destinations.forEach { destination ->
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
}

/** #866 — one labelled group of settings rows: the name answers "where to look". */
private data class SettingsGroup(
    val titleRes: Int,
    val destinations: List<SettingsDestination>
)
