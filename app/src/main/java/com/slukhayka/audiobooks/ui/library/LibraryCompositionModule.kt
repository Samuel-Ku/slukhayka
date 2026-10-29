package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.availability.AvailabilityVerdict
import com.slukhayka.audiobooks.data.availability.AvailabilityView
import com.slukhayka.audiobooks.data.availability.LibraryAvailabilityPolicy
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * #1575 (ADR-0008) — the Медіатека's composition, out of `MainViewModel`.
 *
 * The ViewModel used to inline two combines and a "loaded" flag: the unified
 * list (`allBooks` × `recentProgress` × `allChapters` × the readable-copy ids)
 * and the availability projection (the list × persisted verdicts × the
 * Source-Audio-Refusal set). Both are pure projections of the library data,
 * not navigation, so they live in their own module: screens keep receiving
 * the flows as parameters (the ADR-0008 idiom) and the ViewModel only hands
 * them on.
 *
 * The module takes FLOWS, not a store, so its seam is testable with four
 * `MutableStateFlow`s: combine inputs to outputs, no Room and no ViewModel.
 *
 * [isLoaded] preserves the #1066 contract: it flips once, after the first
 * REAL list emission — never on the initial empty StateFlow value — because
 * the screen must be able to tell "empty library" from "not read yet".
 */
internal class LibraryCompositionModule(
    private val scope: CoroutineScope,
    private val allBooks: Flow<List<AudiobookEntity>>,
    recentProgress: Flow<List<PlaybackProgressEntity>>,
    allChapters: Flow<List<ChapterEntity>>,
    onDeviceBookIds: Flow<Set<String>>,
    availabilityVerdicts: Flow<Map<String, AvailabilityVerdict>>,
    refusedSources: Flow<Set<String>>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val composedBooks: Flow<List<LibraryBook>> = combine(
        allBooks,
        recentProgress,
        allChapters,
        onDeviceBookIds
    ) { books, progress, chapters, readableIds ->
        buildLibraryBooks(
            books,
            progress,
            chapters.groupBy { it.bookId },
            readableIds
        )
    }

    /** The unified Медіатека list; filtering/sorting stay pure in the screen. */
    val books: StateFlow<List<LibraryBook>> =
        composedBooks.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoaded = MutableStateFlow(false)

    /** #1066 — true only after the first REAL [books] emission. */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /**
     * ADR-0042 §1 (spec-56 T1) — the problem-Work states by mergeKey. Clean
     * books (available audio) are absent, so a card never gets noise.
     */
    val availability: StateFlow<Map<String, AvailabilityView>> = combine(
        books,
        availabilityVerdicts,
        refusedSources
    ) { list, verdicts, refused ->
        buildMap {
            for (entry in list) {
                val book = entry.book
                // A local copy or an offline download always plays.
                if (entry.isLocal || book.isDownloaded) continue
                val mergeKey = book.mergeKey
                if (mergeKey.isBlank()) continue
                val available = LibraryAvailabilityPolicy.hasAvailableAudio(book.sourceUrl, refused)
                val refusedId = LibraryAvailabilityPolicy.refusedSourceId(book.sourceUrl, refused)
                LibraryAvailabilityPolicy.viewFor(available, refusedId, verdicts[mergeKey])
                    ?.let { view -> put(mergeKey, view) }
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        scope.launch(ioDispatcher) {
            // One read just to learn that the data EXISTS — the flow itself
            // still drives the list. Flips once, and stays true. Waits on the
            // BOOK ROWS only, exactly like the pre-#1575 ViewModel: the flag
            // means "the library was read", not "all four flows spoke".
            allBooks.first()
            _isLoaded.value = true
        }
    }
}
