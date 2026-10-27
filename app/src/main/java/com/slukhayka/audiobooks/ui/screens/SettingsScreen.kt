package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkTabHeader
import com.slukhayka.audiobooks.ui.theme.AppThemeMode
import com.slukhayka.audiobooks.ui.theme.ContentDensity
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * One home for the existing settings destinations; preferences stay in their
 * modules.
 *
 * Plan 2026-2027: the root now leads with the appearance group the direction
 * asks for — theme «Авто / Папір / Чорнило» and the player's ink exception —
 * then the same seven destinations, unchanged and still never more than two
 * taps from anywhere. Groups are sentence case now (the direction's rule), and
 * the row is a paper list row: icon, title, one-line description, chevron.
 */
@Composable
internal fun SettingsScreen(
    returnDestination: SettingsDestination? = null,
    themeMode: AppThemeMode = AppThemeMode.AUTO,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    playerAlwaysInk: Boolean = true,
    onPlayerAlwaysInkChange: (Boolean) -> Unit = {},
    density: ContentDensity = ContentDensity.COMFORTABLE,
    onDensityChange: (ContentDensity) -> Unit = {},
    // The same destination the system Back returns to; a visible door for the
    // listener who does not know/use gestures.
    onBackClick: () -> Unit = {},
    // LAST on purpose: the trailing lambda is the navigation callback.
    onOpen: (SettingsDestination) -> Unit,
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
    val rowFocus = remember(destinations) { destinations.associateWith { FocusRequester() } }
    LaunchedEffect(returnDestination) {
        withFrameNanos { }
        (rowFocus[returnDestination] ?: headingFocus).requestFocus()
    }

    PaperInkThemedContent {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("settings_screen")
                .accessibilityPane(title)
                .verticalScroll(rememberScrollState())
        ) {
            PaperInkTabHeader(
                title = title,
                // Settings is not a root: no gear, the system Back returns.
                headingTestTag = "settings_heading",
                returnFocusRequester = headingFocus,
                onBackClick = onBackClick,
            )
            Column(Modifier.padding(horizontal = PaperInkDimens.SpacePage)) {
                GroupLabel(R.string.settings_group_appearance)
                ThemeChoiceRow(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
                PaperDivider()
                DensityChoiceRow(density = density, onDensityChange = onDensityChange)
                PaperDivider()
                PlayerInkRow(
                    playerAlwaysInk = playerAlwaysInk,
                    onPlayerAlwaysInkChange = onPlayerAlwaysInkChange,
                )
                PaperDivider()

                groups.forEach { group ->
                    GroupLabel(group.titleRes)
                    group.destinations.forEach { destination ->
                        SettingsRow(
                            destination = destination,
                            focusRequester = rowFocus.getValue(destination),
                            onClick = { onOpen(destination) },
                        )
                        PaperDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = PaperInkType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 24.dp, bottom = 6.dp)
            .testTag("settings_group_$titleRes")
            .semantics { heading() }
    )
}

@Composable
private fun PaperDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The theme choice: three pills, one selected; each pill is a radio option. */
@Composable
private fun ThemeChoiceRow(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = PaperInkType.RowTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppThemeMode.entries.forEach { mode ->
                val selected = mode == themeMode
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onThemeModeChange(mode) },
                        )
                        .testTag("settings_theme_${mode.name.lowercase()}")
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            when (mode) {
                                AppThemeMode.AUTO -> R.string.settings_theme_auto
                                AppThemeMode.PAPER -> R.string.settings_theme_paper
                                AppThemeMode.INK -> R.string.settings_theme_ink
                            }
                        ),
                        style = PaperInkType.Label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** «Зручно / Компактно»: a layout preference, never a smaller touch target. */
@Composable
private fun DensityChoiceRow(
    density: ContentDensity,
    onDensityChange: (ContentDensity) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)) {
        Text(
            text = stringResource(R.string.settings_density),
            style = PaperInkType.RowTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 10.dp).testTag("settings_density"),
        )
        Text(
            text = stringResource(R.string.settings_density_desc),
            style = PaperInkType.Micro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentDensity.entries.forEach { value ->
                val selected = value == density
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onDensityChange(value) },
                        )
                        .testTag("settings_density_${value.name.lowercase()}")
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            when (value) {
                                ContentDensity.COMFORTABLE -> R.string.settings_density_comfortable
                                ContentDensity.COMPACT -> R.string.settings_density_compact
                            }
                        ),
                        style = PaperInkType.Label,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** Decision A made visible: the player is ink unless the listener says otherwise. */
@Composable
private fun PlayerInkRow(
    playerAlwaysInk: Boolean,
    onPlayerAlwaysInkChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = playerAlwaysInk,
                role = Role.Switch,
                onValueChange = onPlayerAlwaysInkChange,
            )
            .testTag("settings_player_always_ink_row")
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_player_always_ink),
                style = PaperInkType.RowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_player_always_ink_desc),
                style = PaperInkType.Micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = playerAlwaysInk,
            onCheckedChange = null,
            modifier = Modifier.testTag("settings_player_always_ink"),
        )
    }
}

@Composable
private fun SettingsRow(
    destination: SettingsDestination,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRequester(focusRequester)
            .focusProperties { canFocus = true }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("settings_${destination.name}")
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(destination.titleRes),
                style = PaperInkType.RowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(destination.descriptionRes),
                style = PaperInkType.Micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** #866 — one labelled group of settings rows: the name answers "where to look". */
private data class SettingsGroup(
    val titleRes: Int,
    val destinations: List<SettingsDestination>
)
