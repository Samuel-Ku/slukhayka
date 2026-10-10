package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * #1205 — the full player as the RIGHT pane of a window that has room for it.
 *
 * The slot is deliberately not a new geometry: it is the page's slot. The
 * owner's decision (issue #1205, 2026-10-10) is «правою панеллю замість
 * сторінки книги», so the pane is [WideDetailPane] — the same 0.4 list / 0.6
 * detail split with the same hairline the opened book's page already uses —
 * and the content the listener came from keeps the leading share. At 840 dp
 * that share gives the player ≈456 dp beside the 80 dp rail, which is where
 * the audit's arithmetic put it (`docs/audits/2026-10-10-900-large-screens-options.md`
 * §2) and inside the widths the landscape player already proved (≈520 dp).
 *
 * [content] is the SAME content the phone route draws alone: this composable
 * decides how much width each half gets and nothing else — it owns no
 * navigation state and no screen of its own, exactly like [WideDetailPane].
 *
 * [playerPaneTitle] names the player half for TalkBack. The modal overlay the
 * player used to be carries the same title, so the surface announces itself
 * the same way in both placements (ADR-0044's contract for modal surfaces).
 */
@Composable
fun PlayerPane(
    playerPaneTitle: String,
    content: @Composable () -> Unit,
    player: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("player_pane")
    ) {
        WideDetailPane(
            list = content,
            detail = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .accessibilityPane(playerPaneTitle)
                ) {
                    player()
                }
            }
        )
    }
}
