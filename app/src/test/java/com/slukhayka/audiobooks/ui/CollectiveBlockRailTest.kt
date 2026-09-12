package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.collective.CollectiveAttempt
import com.slukhayka.audiobooks.data.collective.CollectiveAttemptStatus
import com.slukhayka.audiobooks.data.collective.CollectiveBlockCard
import com.slukhayka.audiobooks.data.collective.CollectiveBlockKind
import com.slukhayka.audiobooks.data.collective.CollectiveFeedBlock
import com.slukhayka.audiobooks.data.collective.collectiveBlockKey
import com.slukhayka.audiobooks.ui.screens.CollectiveBlockRail
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #523 — the collective block rail renders the block's name, its provenance,
 * the cards in the source's order and the honest attempt line, and a tap
 * reports the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h2000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class CollectiveBlockRailTest {

    @get:Rule
    val compose = createComposeRule()

    private val key = collectiveBlockKey("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)

    private fun block(status: CollectiveAttemptStatus = CollectiveAttemptStatus.SUCCESS) = CollectiveFeedBlock(
        blockKey = key,
        sourceId = "soundbooks",
        kind = CollectiveBlockKind.NEW_ARRIVALS,
        name = "Новинки Sound-Books",
        provenanceUrl = "https://sound-books.net",
        cards = listOf(
            CollectiveBlockCard("soundbooks", "https://sound-books.net/kobzar", "Кобзар", "Тарас Шевченко"),
            CollectiveBlockCard("soundbooks", "https://sound-books.net/misto", "Місто", "Валер'ян Підмогильний")
        ),
        fetchedAt = 1_700_000_000_000L,
        staleAfter = 1_700_021_600_000L,
        version = 2L,
        lastAttempt = CollectiveAttempt(1_700_000_000_000L, status)
    )

    @Test
    fun `the rail shows provenance, both cards and the honest attempt line`() {
        var opened: CollectiveBlockCard? = null
        compose.setContent {
            AudiobookTheme {
                CollectiveBlockRail(block = block(), onCardClick = { opened = it })
            }
        }

        compose.onNodeWithTag("collective_block_$key").assertExists()
        compose.onNodeWithText("Новинки Sound-Books", ignoreCase = true).assertExists()
        compose.onNodeWithText("Джерело: https://sound-books.net").assertExists()
        compose.onNodeWithTag("collective_card_soundbooks_${"https://sound-books.net/kobzar".hashCode()}")
            .assertExists()
        compose.onNodeWithTag("collective_card_soundbooks_${"https://sound-books.net/misto".hashCode()}")
            .assertExists()
            .performClick()
        assertEquals("Місто", opened?.title)
    }

    @Test
    fun `a failed attempt says the block is the last good snapshot`() {
        compose.setContent {
            AudiobookTheme {
                CollectiveBlockRail(block = block(CollectiveAttemptStatus.CHALLENGE), onCardClick = {})
            }
        }

        compose.onNodeWithText("Остання спроба: показано останній добрий знімок").assertExists()
    }
}
