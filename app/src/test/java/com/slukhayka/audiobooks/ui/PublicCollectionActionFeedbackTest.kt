package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.PublishedCollection
import com.slukhayka.audiobooks.ui.screens.collections.CollectionPage
import com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionActionFeedback
import com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionContent
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1822 — «Слухати» answers what a vote or a report actually did.
 *
 * The ViewModel publishes both outcomes on replay-less event flows, so an
 * offline / no-profile tap used to vanish without a word. The harness mirrors
 * the screen's wiring: the tap starts the action, the outcome comes back on
 * the flow, [PublicCollectionActionFeedback] speaks it, and a stored report
 * closes the page before offering to undo the LOCAL hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class PublicCollectionActionFeedbackTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val collection = PublishedCollection(
        authorId = "a".repeat(64),
        collectionId = "c1",
        pseudonym = "Слухач",
        title = "Магія",
        description = "",
        bookIds = listOf("book-1"),
        publishedAt = 1L
    )

    private lateinit var voteResults: MutableSharedFlow<Boolean>
    private lateinit var reportResults: MutableSharedFlow<Pair<String, Boolean>>
    private var undone: String? = null
    private var reportTaps = 0
    private var pageCloses = 0

    /**
     * @param voteOutcome emitted by a star tap; null means the tap sends nothing.
     * @param reportOutcome emitted by the report tap; null means nothing is sent.
     */
    private fun setContent(
        voteOutcome: Boolean? = null,
        reportOutcome: Boolean? = null
    ) {
        voteResults = MutableSharedFlow(extraBufferCapacity = 8)
        reportResults = MutableSharedFlow(extraBufferCapacity = 8)
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            val snackbarHost = remember { SnackbarHostState() }
            PaperInkTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    PublicCollectionActionFeedback(
                        voteResults = voteResults,
                        reportResults = reportResults,
                        snackbarHostState = snackbarHost,
                        onReported = {
                            open = false
                            pageCloses++
                        },
                        onUndoLocalHide = { documentId -> undone = documentId }
                    )
                    if (open) {
                        CollectionPage(
                            title = collection.title,
                            onClose = { open = false },
                            testTag = "public_collection_page"
                        ) {
                            PublicCollectionContent(
                                collection = collection,
                                originalAvailableLocally = true,
                                onSaveForYou = {},
                                onVote = { stars -> voteOutcome?.let { voteResults.tryEmit(it) } },
                                onReport = {
                                    reportTaps++
                                    reportOutcome?.let { reportResults.tryEmit("doc-1" to it) }
                                }
                            )
                        }
                    }
                    SnackbarHost(
                        hostState = snackbarHost,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }

    @Test
    fun `a refused vote says so instead of staying silent`() {
        setContent(voteOutcome = false)

        compose.onNodeWithTag("rating_star_4").performScrollTo().performClick()

        compose.onNodeWithText(context.getString(R.string.collection_vote_error))
            .assertIsDisplayed()
    }

    @Test
    fun `a stored vote is announced by the stars and adds no refusal line`() {
        setContent(voteOutcome = true)

        compose.onNodeWithTag("rating_star_4").performScrollTo().performClick()

        compose.onNodeWithText(context.getString(R.string.collection_vote_error))
            .assertDoesNotExist()
    }

    @Test
    fun `a refused report says why and keeps the page open`() {
        setContent(reportOutcome = false)

        compose.onNodeWithTag("public_collection_report").performScrollTo().performClick()

        compose.onNodeWithText(context.getString(R.string.collection_report_error))
            .assertIsDisplayed()
        compose.onNodeWithTag("public_collection_page").assertIsDisplayed()
        assertEquals("a refusal closes nothing", 0, pageCloses)
    }

    @Test
    fun `a stored report closes the page and offers undo of the local hide`() {
        setContent(reportOutcome = true)

        compose.onNodeWithTag("public_collection_report").performScrollTo().performClick()

        compose.onNodeWithTag("public_collection_page").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.collection_reported))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.collection_report_undo))
            .assertIsDisplayed()
        assertEquals("the reported page closes exactly once", 1, pageCloses)
        assertNull("the undo must not fire before its action", undone)

        compose.onNodeWithText(context.getString(R.string.collection_report_undo)).performClick()

        assertEquals("the action reverses the hide of the same document", "doc-1", undone)
    }

    /**
     * The complaint itself is de-duplicated by the shared store's transaction
     * (one report document per reporter and collection), and the page leaves
     * the screen on success — so a second tap has nothing left to send.
     */
    @Test
    fun `a stored report leaves no report control for a second tap`() {
        setContent(reportOutcome = true)

        compose.onNodeWithTag("public_collection_report").performScrollTo().performClick()

        compose.onNodeWithTag("public_collection_report").assertDoesNotExist()
        compose.onNodeWithTag("public_collection_page").assertDoesNotExist()
        assertEquals("the first tap sent exactly one report", 1, reportTaps)
        assertFalse(undone != null)
    }
}
