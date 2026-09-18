package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
            list()
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
