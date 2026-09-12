package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.ui.screens.CanonicalAuthorContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #736 / ADR-0041 — the person page leads with the listener's own Медіатека
 * Works and marks the Дзеркало neighbours it can still add by tap; when
 * ownership is not known nothing is marked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h2000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class CanonicalAuthorOwnershipTest {

    @get:Rule
    val compose = createComposeRule()

    private val author = AuthorSummary(
        id = "author-shevchenko",
        displayName = "Тарас Шевченко",
        normalizedName = "тарас шевченко",
        workCount = 2
    )

    private fun work(id: String, title: String) =
        WorkEntity(id = id, mergeKey = id, title = title, author = "Тарас Шевченко", addedAt = 1L)

    @Test
    fun `owned works lead and mirror neighbours are marked`() {
        val owned = work("w1", "Кобзар")
        val mirror = work("w2", "Гайдамаки")
        compose.setContent {
            AudiobookTheme {
                CanonicalAuthorContent(
                    author = author,
                    works = listOf(mirror, owned),
                    onWorkClick = {},
                    ownedWorkIds = setOf("w1")
                )
            }
        }
        compose.waitForIdle()

        val ownedTop = compose.onNodeWithText("Кобзар").getBoundsInRoot().top
        val mirrorTop = compose.onNodeWithText("Гайдамаки").getBoundsInRoot().top
        assertTrue("an owned Work leads its author's page", ownedTop < mirrorTop)
        assertEquals(1, compose.onAllNodesWithText("З Дзеркала").fetchSemanticsNodes().size)
    }

    @Test
    fun `unknown ownership marks nothing and keeps the given order`() {
        val first = work("w1", "Кобзар")
        val second = work("w2", "Гайдамаки")
        compose.setContent {
            AudiobookTheme {
                CanonicalAuthorContent(
                    author = author,
                    works = listOf(first, second),
                    onWorkClick = {},
                    ownedWorkIds = null
                )
            }
        }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("З Дзеркала").fetchSemanticsNodes().size)
        assertTrue(
            compose.onNodeWithText("Кобзар").getBoundsInRoot().top <
                compose.onNodeWithText("Гайдамаки").getBoundsInRoot().top
        )
    }
}
