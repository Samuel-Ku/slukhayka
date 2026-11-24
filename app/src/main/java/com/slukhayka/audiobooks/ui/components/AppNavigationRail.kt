package com.slukhayka.audiobooks.ui.components

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
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons

/**
 * #900 — the wide-window navigation surface: the same working sections the
 * bottom bar carries (ADR-0049), as a leading rail instead of a bar.
 *
 * Same order, same names, same [SelectedTab] values — the rail is a different
 * SURFACE for the same navigation, never a second navigation (issue #900:
 * «на великих екранах не створюємо паралельних екранів — лише інша розкладка
 * тих самих»). The map is the four working sections ADR-0049 / #898 fixed —
 * Слухати · Огляд · Мої книги · Друзі. «Налаштування» stays behind the gear on
 * every root, so the rail has no fifth item the bar does not have.
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
            icon = { PaperInkIcon(PaperInkIcons.Headphones, contentDescription = null, size = PaperInkIconSizes.Navigation) },
            label = { Text(stringResource(R.string.nav_listen)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_listen")
        )

        NavigationRailItem(
            selected = selectedTab == SelectedTab.EXPLORE && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.EXPLORE) },
            icon = { PaperInkIcon(PaperInkIcons.Compass, contentDescription = null, size = PaperInkIconSizes.Navigation) },
            label = { Text(stringResource(R.string.nav_explore)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_explore")
        )

        NavigationRailItem(
            selected = selectedTab == SelectedTab.LIBRARY && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LIBRARY) },
            icon = { PaperInkIcon(PaperInkIcons.Library, contentDescription = null, size = PaperInkIconSizes.Navigation) },
            label = { Text(stringResource(R.string.nav_library)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_library")
        )

        // #898 / ADR-0049 — the fourth working section, on the rail exactly as
        // on the bar: the same destination, the same name, the same callback.
        NavigationRailItem(
            selected = selectedTab == SelectedTab.FRIENDS && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.FRIENDS) },
            icon = { PaperInkIcon(PaperInkIcons.Friends, contentDescription = null, size = PaperInkIconSizes.Navigation) },
            label = { Text(stringResource(R.string.nav_friends)) },
            colors = railItemColors(),
            modifier = Modifier.testTag("rail_tab_friends")
        )
    }
}

@Composable
private fun railItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.outlineVariant
)
