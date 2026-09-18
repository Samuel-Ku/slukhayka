package com.slukhayka.audiobooks.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.SelectedTab

/**
 * #900 — the wide-window navigation surface: the same working sections the
 * bottom bar carries (ADR-0049), as a leading rail instead of a bar.
 *
 * Same order, same names, same [SelectedTab] values — the rail is a different
 * SURFACE for the same navigation, never a second navigation (issue #900:
 * «на великих екранах не створюємо паралельних екранів — лише інша розкладка
 * тих самих»). «Налаштування» stays behind the gear on every root (ADR-0049),
 * so the rail does not gain a fourth item the bar does not have.
 *
 * Test tags carry a `rail_` prefix so a test states WHICH surface it asserts;
 * the phone bar keeps its historical `tab_*` tags untouched.
 */
@Composable
fun AppNavigationRail(
    selectedTab: SelectedTab,
    bookDetailOpen: Boolean = false,
    onSelect: (SelectedTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        // MD3: the rail is a tonal container (surfaceContainer), the same step
        // above the screen surface the bottom bar uses.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier.testTag("navigation_rail")
    ) {
        NavigationRailItem(
            selected = selectedTab == SelectedTab.LISTEN && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LISTEN) },
            icon = { Icon(imageVector = Icons.Default.Headphones, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_listen)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_listen")
        )

        NavigationRailItem(
            selected = selectedTab == SelectedTab.EXPLORE && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.EXPLORE) },
            icon = { Icon(imageVector = Icons.Default.Explore, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_explore)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_explore")
        )

        NavigationRailItem(
            selected = selectedTab == SelectedTab.LIBRARY && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LIBRARY) },
            icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_library)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_library")
        )
    }
}

@Composable
private fun railItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.outlineVariant
)
