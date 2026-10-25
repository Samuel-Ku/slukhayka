package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** How much of a wide window the LIST keeps; the page beside it takes the rest. */
const val WideDetailPaneListWeight = 0.4f

/**
 * #900 — a root's list with the opened item's page beside it.
 *
 * [list] and [detail] are the SAME two screens the phone shows one at a time
 * (issue #900: «лише інша розкладка тих самих»); this composable decides only
 * how much width each of them gets, and adds the one hairline that separates
 * them. It owns no navigation state and no screen content of its own.
 *
 * The list keeps the smaller share: it is scanned, the page is read.
 *
 * #1235 — the list pane measures ITSELF and tells the canonical [BookRow]
 * whether it is narrow ([LocalBookRowCompact]). A wide window can still hand
 * the list 192 dp (840 dp with the mini-player column) or 208 dp (600 dp with
 * the rail); deciding that from the window would miss both. The measurement
 * lives here, once per pane, not once per row.
 */
@Composable
fun WideDetailPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWeight: Float = WideDetailPaneListWeight
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .testTag("wide_detail_pane")
    ) {
        Box(
            modifier = Modifier
                .weight(listWeight)
                .fillMaxHeight()
                .testTag("wide_detail_list")
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalBookRowCompact provides (maxWidth < NarrowBookRowMaxWidth)
                ) {
                    list()
                }
            }
        }
        VerticalDivider()
        Box(
            modifier = Modifier
                .weight(1f - listWeight)
                .fillMaxHeight()
                .testTag("wide_detail_detail")
        ) {
            detail()
        }
    }
}
