package com.slukhayka.audiobooks.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * spec-54 T03 (#871) — the ONE reason a tab's state survives switching tabs.
 *
 * The roots render through `when (selectedTab)`, so the tab that is not
 * selected LEAVES the composition and its `rememberSaveable` state (scroll,
 * filters, an expanded search) would die with it. Wrapping the selected tab's
 * content in this host keeps that state in a holder keyed by [tabKey], and the
 * listener gets it back on return.
 *
 * It is a component, not an inline holder, so the behaviour itself is testable
 * ([com.slukhayka.audiobooks.ui.TabSaveableHostTest]) instead of living inside
 * the 1200-line composition root.
 */
@Composable
fun TabSaveableHost(
    tabKey: String,
    content: @Composable () -> Unit
) {
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(tabKey) { content() }
}
