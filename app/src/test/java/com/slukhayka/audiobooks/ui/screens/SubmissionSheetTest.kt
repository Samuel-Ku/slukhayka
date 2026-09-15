package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.SubmissionState
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-601 T3/T5 — the submission sheet's honest states: the remaining budget
 * is visible, submitting forwards the pasted link, and every refusal has its
 * own plainly-worded status (no silent failure, no fake success).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class SubmissionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: SubmissionUiState,
        remaining: Int? = 7,
        onSubmit: (String) -> Unit = {},
        preview: ListenerSubmissionFlow.SubmissionPreview? = null,
        onPreview: ((String) -> Unit)? = null,
        onSubmitWithEdits: ((String, ListenerSubmissionFlow.PreviewEdits) -> Unit)? = null
    ) {
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = state,
                    remainingToday = remaining,
                    onSubmit = onSubmit,
                    preview = preview,
                    onPreview = onPreview,
                    onSubmitWithEdits = onSubmitWithEdits,
                    includePaneSemantics = false
                )
            }
        }
    }

    private fun playlistPreview() = ListenerSubmissionFlow.SubmissionPreview(
        url = "https://www.youtube.com/playlist?list=PLprev",
        kind = ListenerSubmissionFlow.PreviewKind.YOUTUBE_PLAYLIST,
        title = "Проста книга",
        author = "Автор",
        narrator = null,
        coverUrl = null,
        durationSeconds = 8365L,
        chapterCount = 12
    )

    @Test
    fun `shows the honest remaining budget`() {
        show(SubmissionUiState.Idle)
        compose.onNodeWithText("Залишилось надсилань сьогодні: 7").assertExists()
    }

    @Test
    fun `blank field cannot submit and a pasted link is forwarded`() {
        val submitted = mutableListOf<String>()
        show(SubmissionUiState.Idle, onSubmit = { submitted += it })

        compose.onNodeWithTag("submission_submit").assertIsNotEnabled()
        compose.onNodeWithTag("submission_url_field")
            .performTextInput("https://youtu.be/abc12345678")
        compose.onNodeWithTag("submission_submit").assertIsEnabled().performClick()

        assertEquals(listOf("https://youtu.be/abc12345678"), submitted)
    }

    @Test
    fun `every refusal renders its own status`() {
        show(
            SubmissionUiState.Refused(ListenerSubmissionFlow.Reason.DAILY_LIMIT_REACHED),
            remaining = 0
        )
        compose.onNodeWithText("Денний ліміт надсилань вичерпано.").assertExists()
    }

    @Test
    fun `imported without a shared base says so plainly`() {
        show(SubmissionUiState.Imported(publishable = false))
        compose.onNodeWithText(
            "Книгу додано локально. Спільна база недоступна — публікації не буде."
        ).assertExists()
    }

    @Test
    fun `published state is distinct`() {
        show(SubmissionUiState.Published)
        compose.onNodeWithText("Опубліковано в спільному каталозі.").assertExists()
    }

    @Test
    fun `metadata-only state is distinct`() {
        show(SubmissionUiState.MetadataPublished)
        compose.onNodeWithText(
            "Метадані опубліковано. Аудіо з цього посилання недоступне без логіну — інші побачать чесний недоступний стан."
        ).assertExists()
    }

    @Test
    fun `an already-owned link offers opening the book`() {
        var opened: String? = null
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = SubmissionUiState.AlreadyInLibrary("book-7"),
                    remainingToday = 7,
                    onSubmit = {},
                    onOpenBook = { opened = it },
                    includePaneSemantics = false
                )
            }
        }

        compose.onNodeWithText("Уже в медіатеці.").assertExists()
        compose.onNodeWithTag("submission_open_book").performClick()
        assertEquals("book-7", opened)
    }

    @Test
    fun `a shared-base duplicate says so plainly`() {
        show(SubmissionUiState.Refused(ListenerSubmissionFlow.Reason.ALREADY_PUBLISHED))
        compose.onNodeWithText("Уже в спільній базі.").assertExists()
    }

    @Test
    fun `the offline queue is visible with both actions`() {
        var retried: String? = null
        var removed: String? = null
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = SubmissionUiState.Deferred,
                    remainingToday = 7,
                    onSubmit = {},
                    deferredLinks = listOf(
                        SubmissionState(
                            sourceId = "deferred-1",
                            url = "https://youtu.be/abc",
                            bookId = "",
                            metadataJson = "",
                            channelId = "",
                            state = SubmissionState.State.DEFERRED
                        )
                    ),
                    onRetryDeferred = { retried = it },
                    onRemoveDeferred = { removed = it },
                    includePaneSemantics = false
                )
            }
        }

        compose.onNodeWithText("Відкладені посилання").assertExists()
        compose.onNodeWithText(
            "Немає мережі — посилання відкладено. Оброблю, коли зʼявиться звʼязок."
        ).assertExists()
        compose.onNodeWithTag("submission_deferred_retry_deferred-1").performClick()
        assertEquals("deferred-1", retried)
        compose.onNodeWithTag("submission_deferred_remove_deferred-1").performClick()
        assertEquals("deferred-1", removed)
    }

    @Test
    fun `an unsupported link explains what is accepted`() {
        show(SubmissionUiState.Unsupported)
        compose.onNodeWithText(
            "Це посилання не підтримується. Надішліть YouTube-відео, плейлист або пост Telegram."
        ).assertExists()
    }

    @Test
    fun `a shared link prefills the field`() {
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = SubmissionUiState.Idle,
                    remainingToday = 7,
                    onSubmit = {},
                    prefillUrl = "https://youtu.be/abc",
                    includePaneSemantics = false
                )
            }
        }
        compose.onNodeWithText("https://youtu.be/abc").assertExists()
    }

    @Test
    fun `the clipboard chip appears only with a supported candidate and fills the field`() {
        compose.setContent {
            AudiobookTheme {
                SubmissionSheetContent(
                    state = SubmissionUiState.Idle,
                    remainingToday = 7,
                    onSubmit = {},
                    clipboardCandidate = "https://t.me/bookchannel/42",
                    includePaneSemantics = false
                )
            }
        }
        compose.onNodeWithTag("submission_clipboard_chip").performClick()
        compose.onNodeWithText("https://t.me/bookchannel/42").assertExists()
    }

    @Test
    fun `preview card shows real engine data with its honest type`() {
        show(
            SubmissionUiState.Idle,
            preview = playlistPreview(),
            onPreview = {},
            onSubmitWithEdits = { _, _ -> }
        )

        compose.onNodeWithTag("submission_preview_card").assertExists()
        compose.onNodeWithText("Проста книга").assertExists()
        compose.onNodeWithText("плейлист · 12 розділів").assertExists()
        compose.onNodeWithText("2:19:25").assertExists()
    }

    @Test
    fun `preview edit flows into add`() {
        var added: Pair<String, ListenerSubmissionFlow.PreviewEdits>? = null
        show(
            SubmissionUiState.Idle,
            preview = playlistPreview(),
            onPreview = {},
            onSubmitWithEdits = { url, edits -> added = url to edits }
        )

        compose.onNodeWithTag("submission_preview_edit").performClick()
        compose.onNodeWithTag("metadata_edit_title").performTextClearance()
        compose.onNodeWithTag("metadata_edit_title").performTextInput("Виправлена книга")
        compose.onNodeWithTag("metadata_edit_save").performClick()
        compose.onNodeWithTag("submission_preview_add").performClick()

        assertEquals(
            "https://www.youtube.com/playlist?list=PLprev" to
                ListenerSubmissionFlow.PreviewEdits(
                    title = "Виправлена книга",
                    author = "Автор",
                    narrator = ""
                ),
            added
        )
    }

    @Test
    fun `one-tap add stays one tap without a preview`() {
        val submitted = mutableListOf<String>()
        show(SubmissionUiState.Idle, onSubmit = { submitted += it })

        compose.onNodeWithTag("submission_url_field")
            .performTextInput("https://youtu.be/abc12345678")
        compose.onNodeWithTag("submission_submit").performClick()

        assertEquals(listOf("https://youtu.be/abc12345678"), submitted)
        compose.onNodeWithTag("submission_preview_card").assertDoesNotExist()
    }
}
