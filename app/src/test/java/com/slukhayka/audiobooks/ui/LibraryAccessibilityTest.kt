package com.slukhayka.audiobooks.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.data.imports.ImportPlan
import com.slukhayka.audiobooks.data.imports.SourceRef
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.GlobalBookmarkItem
import com.slukhayka.audiobooks.ui.screens.LibraryBookCard
import com.slukhayka.audiobooks.ui.screens.LibraryModalUnderlay
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheet
import com.slukhayka.audiobooks.ui.screens.LibraryHeaderActionsInner
import com.slukhayka.audiobooks.ui.screens.LibraryImportSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryImportSheet
import com.slukhayka.audiobooks.ui.screens.ImportPreviewDialog
import com.slukhayka.audiobooks.ui.screens.LibraryEmptyState
import com.slukhayka.audiobooks.ui.screens.LibrarySearchField
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun libraryBookCardAcceptsExactRouteReturnFocus() {
        composeTestRule.setContent {
            val returnFocusRequester = remember { FocusRequester() }
            val touchMode = object : InputModeManager {
                override val inputMode = InputMode.Touch
                override fun requestInputMode(inputMode: InputMode) = false
            }
            CompositionLocalProvider(LocalInputModeManager provides touchMode) {
                AudiobookTheme {
                    LibraryBookCard(
                        book = fixtureBook,
                        grid = false,
                        onClick = {},
                        modifier = Modifier.focusRequester(returnFocusRequester)
                    )
                }
            }
            LaunchedEffect(returnFocusRequester) {
                withFrameNanos { }
                returnFocusRequester.requestFocus()
            }
        }

        composeTestRule.onNodeWithTag("library_book_item_${fixtureBook.book.id}")
            .assertIsFocused()
    }

    private val fixtureBook = run {
        val books = TestDataFactory.dataBooks()
        val chapters = TestDataFactory.dataChapters(books)
        buildLibraryBooks(
            books = books,
            progressList = TestDataFactory.seedPlaybackProgress(
                books,
                chapterIndex = 1,
                positionSeconds = 300L
            ),
            chaptersByBook = chapters.groupBy { it.bookId }
        ).first()
    }

    @Test
    fun libraryEntryIsOneContextualActionWithHonestState() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryBookCard(book = fixtureBook, grid = false, onClick = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Нейромант, Вільям Гібсон")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Нейромант, Вільям Гібсон")
                )
            )
        composeTestRule.onNodeWithTag("library_book_item_${fixtureBook.book.id}")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    // #741 — a removed/scam source is not branded: no source
                    // label is announced for a 4read row.
                    "Прослухано 45 відсотків, залишилося 18 хв. " +
                        "Завантажено для прослуховування без інтернету"
                )
            )
    }

    @Test
    fun libraryEntryHasNoSeparatePlayShortcut() {
        // v1.5 review, on-device: a play disc on every row put the screen's
        // accent on eight identical circles and stole it from the «Продовжити»
        // CTA. The row is the way in — and it stays exactly one action.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryBookCard(
                    book = fixtureBook,
                    grid = false,
                    onClick = {},
                    onListenNow = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("library_book_item_${fixtureBook.book.id}")
            .assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Слухати книгу Нейромант")
            .assertDoesNotExist()
    }

    @Test
    fun statusControlsExposeSelection() {
        var selectedFilter: LibraryFilter? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryStatusRow(
                        selected = LibraryFilter.LISTENING,
                        onSelect = { selectedFilter = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("library_status_listening")
            .assertIsSelected()
            .performClick()
        assertEquals(LibraryFilter.LISTENING, selectedFilter)
    }

    @Test
    fun sortControlsExposeSelectionWithoutDuplicateRadioNodes() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryFilterSheetContent(
                    filter = LibraryFilter.LOCAL,
                    sort = LibrarySort.TITLE,
                    gridMode = false,
                    onFilterChange = {},
                    onSortChange = {},
                    onGridModeChange = {}
                )
            }
        }

        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            useUnmergedTree = true
        ).assertCountEquals(LibrarySort.entries.size)
        composeTestRule.onNodeWithTag("library_sort_title").assertIsSelected()
    }

    @Test
    fun sheetsExposePaneTitlesAndHeadings() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryImportSheetContent(onImportFile = {}, onImportFolder = {})
            }
        }

        composeTestRule.onNodeWithTag("library_import_sheet_content")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        composeTestRule.onNodeWithText("Додати аудіо")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun libraryModalOwnerHidesUnderlayAndRestoresFilterImportAndPreviewOrigins() {
        val preview = MainViewModel.ImportPreviewState(
            plan = ImportPlan(SourceRef.Files(emptyList()), emptyList()),
            treeUri = "content://library-preview"
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                var activeModal by remember { mutableStateOf<String?>(null) }
                val filterOrigin = remember { FocusRequester() }
                val importOrigin = remember { FocusRequester() }
                val previewOrigin = remember { FocusRequester() }
                RestoreFocusAfterModal(activeModal == "filter", filterOrigin)
                RestoreFocusAfterModal(activeModal == "import", importOrigin)
                RestoreFocusAfterModal(activeModal == "preview", previewOrigin)
                Box(Modifier.width(320.dp).height(480.dp)) {
                    Column(
                        Modifier
                            .testTag("library_modal_underlay")
                            .accessibilityModalBackground(activeModal != null)
                    ) {
                        Button(
                            onClick = { activeModal = "filter" },
                            modifier = Modifier.focusRequester(filterOrigin).testTag("filter_origin")
                        ) { androidx.compose.material3.Text("Фільтр") }
                        Button(
                            onClick = { activeModal = "import" },
                            modifier = Modifier.focusRequester(importOrigin).testTag("import_origin")
                        ) { androidx.compose.material3.Text("Додати") }
                        Button(
                            onClick = { activeModal = "preview" },
                            modifier = Modifier.focusRequester(previewOrigin).testTag("preview_origin")
                        ) { androidx.compose.material3.Text("Перегляд") }
                    }
                    when (activeModal) {
                        "filter" -> LibraryFilterSheet(
                            filter = LibraryFilter.ALL,
                            sort = LibrarySort.RECENTLY_LISTENED,
                            gridMode = false,
                            onFilterChange = {},
                            onSortChange = {},
                            onGridModeChange = {},
                            onDismiss = { activeModal = null }
                        )
                        "import" -> LibraryImportSheet(
                            onImportFile = { activeModal = null },
                            onImportFolder = { activeModal = null },
                            onDismiss = { activeModal = null }
                        )
                        "preview" -> ImportPreviewDialog(
                            preview = preview,
                            onAcceptMerge = {},
                            onRejectMerge = {},
                            onConfirm = { activeModal = null },
                            onDismiss = { activeModal = null }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("filter_origin").performClick()
        assertLibraryModal("library_filter_sheet_heading")
        composeTestRule.onNodeWithContentDescription("Закрити фільтр і сортування").performClick()
        composeTestRule.onNodeWithTag("filter_origin").assertIsFocused()

        composeTestRule.onNodeWithTag("import_origin").performClick()
        assertLibraryModal("library_import_sheet_heading")
        composeTestRule.onNodeWithTag("import_option_file").performClick()
        composeTestRule.onNodeWithTag("import_origin").assertIsFocused()

        composeTestRule.onNodeWithTag("preview_origin").performClick()
        assertLibraryModal("library_import_preview_heading")
        composeTestRule.onNodeWithTag("library_import_preview_confirm").performClick()
        composeTestRule.onNodeWithTag("preview_origin").assertIsFocused()
    }

    @Test
    fun libraryModalOwnerGroupsScreenAndSnackbarIntoOneHiddenUnderlay() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryModalUnderlay(modalVisible = true) {
                    Box(Modifier.semantics { contentDescription = "Library body" })
                    Box(Modifier.semantics { contentDescription = "Library snackbar" })
                }
            }
        }

        composeTestRule.onNodeWithTag("library_modal_underlay", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
        listOf("Library body", "Library snackbar").forEach { label ->
            composeTestRule.onNode(
                hasContentDescription(label) and
                    hasAnyAncestor(hasTestTag("library_modal_underlay")),
                useUnmergedTree = true
            ).assert(hasContentDescription(label))
        }
    }

    private fun assertLibraryModal(headingTag: String) {
        composeTestRule.onNodeWithTag("library_modal_underlay", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        composeTestRule.onNodeWithTag(headingTag, useUnmergedTree = true).assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
            useUnmergedTree = true
        ).assertCountEquals(1)
    }

    @Test
    fun emptyLibraryExplainsTheStateAndOffersTwoNextActions() {
        var imported = false
        var browsed = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryEmptyState(
                    onImportClick = { imported = true },
                    onBrowseClick = { browsed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Мої книги порожні").assertIsDisplayed()
        composeTestRule.onNodeWithText("Додайте власні аудіокниги з пристрою або знайдіть нові в каталозі.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_empty_import")
            .assertHeightIsAtLeast(24.dp)
            .performClick()
        composeTestRule.onNodeWithTag("library_empty_browse")
            .assertHeightIsAtLeast(24.dp)
            .performClick()
        assertEquals(true, imported)
        assertEquals(true, browsed)
    }

    @Test
    fun bookmarkActionsNameBookChapterAndPosition() {
        val bookmark = TestDataFactory.seedBookmarks().first()
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalBookmarkItem(
                    bookmark = bookmark,
                    bookTitle = "Нейромант",
                    onJumpClick = {},
                    onDeleteClick = {}
                )
            }
        }

        val time = MainViewModel.formatTime(bookmark.timestampSeconds)
        composeTestRule.onNodeWithTag("bookmark_jump_${bookmark.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Перейти до закладки у книзі Нейромант, ${bookmark.chapterTitle}, $time")
                )
            )
            .assertHeightIsAtLeast(24.dp)
        composeTestRule.onNodeWithTag("bookmark_delete_${bookmark.id}")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Видалити закладку з книги Нейромант, ${bookmark.chapterTitle}, $time")
                )
            )
            .assertHeightIsAtLeast(24.dp)
    }

    @Test
    fun twoHundredPercentFontKeepsLibraryCardReachable() {
        val density = composeTestRule.density.density
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(411.dp, 720.dp)) {
                        LibraryBookCard(book = fixtureBook, grid = false, onClick = {})
                    }
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Нейромант, Вільям Гібсон")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(24.dp)
    }
    @Test
    fun theModerationBadgeIsSpokenThroughTheCardState() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibraryBookCard(
                    book = fixtureBook,
                    grid = false,
                    onClick = {},
                    submissionBadge = SubmissionBadge.PENDING_MODERATION
                )
            }
        }

        val label = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(com.slukhayka.audiobooks.R.string.submission_badge_pending_moderation)
        composeTestRule.onNodeWithTag("library_book_item_${fixtureBook.book.id}")
            .assert(
                SemanticsMatcher("state description carries the moderation badge") { node ->
                    node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(label) == true
                }
            )
    }

    /**
     * ADR-0033 amended 2026-09-18 (IA §4 «Бібліотека»): the local search is a
     * permanently visible field on the root, exactly like Огляд — no 🔍 action
     * reveals or hides it, so the listener never pays an extra tap.
     */
    @Test
    fun librarySearchFieldIsPermanentlyVisibleWithoutAnyRevealingAction() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                val focus = remember { FocusRequester() }
                Column {
                    LibraryHeaderActionsInner(
                        bookmarksCount = 0,
                        peopleCount = 0,
                        menuOpen = false,
                        onMenuOpenChange = {},
                        onOpenSection = {},
                        onAdd = {},
                        importFocusRequester = focus
                    )
                    LibrarySearchField(query = "", onQueryChange = {})
                }
            }
        }

        composeTestRule.onNodeWithTag("library_search")
            .assertTextContains("Пошук у медіатеці")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_search_toggle").assertDoesNotExist()
    }

    /** ✕ only empties the query; the field itself never leaves the screen. */
    @Test
    fun typedLibraryQueryKeepsTheFieldAndClearOnlyEmptiesIt() {
        var query by mutableStateOf("Гібсон")
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                LibrarySearchField(query = query, onQueryChange = { query = it })
            }
        }

        composeTestRule.onNodeWithTag("library_search").assertTextContains("Гібсон")
        composeTestRule.onNodeWithTag("library_search_clear")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(24.dp)
            .performClick()
        composeTestRule.onNodeWithTag("library_search")
            .assertIsDisplayed()
            .assertTextContains("Пошук у медіатеці")
        composeTestRule.onNodeWithTag("library_search_clear").assertDoesNotExist()
    }

    /**
     * The system Back clears a typed query first — navigation keeps it only when
     * the field is empty (ADR-0033 amended 2026-09-18).
     */
    @Test
    fun systemBackClearsATypedLibraryQueryWithoutLeavingTheField() {
        var query by mutableStateOf("Гібсон")
        val dispatcher = OnBackPressedDispatcher()
        composeTestRule.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides remember(dispatcher) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher = dispatcher
                        override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    LibrarySearchField(query = query, onQueryChange = { query = it })
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher.onBackPressed() }

        composeTestRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_search_clear").assertDoesNotExist()
    }

    @Test
    fun emptyLibraryQueryLeavesBackToTheNavigationContract() {
        var query by mutableStateOf("")
        var clears = 0
        val dispatcher = OnBackPressedDispatcher()
        composeTestRule.setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides remember(dispatcher) {
                    object : OnBackPressedDispatcherOwner {
                        override val onBackPressedDispatcher: OnBackPressedDispatcher = dispatcher
                        override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
                    }
                }
            ) {
                AudiobookTheme(darkTheme = true) {
                    LibrarySearchField(
                        query = query,
                        onQueryChange = {
                            clears++
                            query = it
                        }
                    )
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher.onBackPressed() }

        assertEquals("an empty field never consumes Back", 0, clears)
    }
}
