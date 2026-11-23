package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.library.ListenComposer.Block
import com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class ListenShelvesState(
    val content: ListenContent = ListenContent.Loading,
    val managementBlocks: List<Block> = emptyList(),
    val displayBlocks: List<Block> = emptyList(),
    val hiddenBlockIds: Set<BlockId> = emptySet(),
) {
    val heroBook: AudiobookEntity?
        get() = managementBlocks.firstOrNull { it.id == BlockId.HERO }?.books?.firstOrNull()?.book
}

internal class ListenShelvesModule(
    scope: CoroutineScope,
    composedBooks: Flow<List<LibraryBook>>,
    nextInSeries: Flow<AudiobookEntity?>,
    finishedWorkIds: Flow<Set<String>>,
    private val prefs: ListenPrefsStore,
    private val copyForApp: () -> ListenComposer.ListenCopy,
    now: () -> Long = System::currentTimeMillis,
) {
    private val localizedCopy = MutableStateFlow(copyForApp())

    val state: StateFlow<ListenShelvesState> = combine(
        composedBooks, nextInSeries, finishedWorkIds, prefs.snapshots, localizedCopy,
    ) { books, next, finished, snapshot, copy ->
        val managed = ListenComposer.compose(books, next, snapshot, finished, now(), copy)
        val visible = managed.filter { it.id !in snapshot.hiddenBlockIds }
        val displayed = deduplicateListenShelves(visible).filter { it.books.isNotEmpty() }
        ListenShelvesState(
            content = listenContentFor(
                libraryLoaded = true,
                blocksReady = true,
                allBooksEmpty = books.isEmpty(),
                composedBlocks = managed.size,
                dedupedBlocks = displayed.size,
            ),
            managementBlocks = managed,
            displayBlocks = displayed,
            hiddenBlockIds = snapshot.hiddenBlockIds,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ListenShelvesState())

    fun onUiLocaleApplied() {
        localizedCopy.value = copyForApp()
    }

    fun moveBlockUp(id: BlockId) = prefs.moveBlockUp(id)
    fun moveBlockDown(id: BlockId) = prefs.moveBlockDown(id)
    fun hideBlock(id: BlockId) = prefs.hideBlock(id)
    fun unhideBlock(id: BlockId) = prefs.unhideBlock(id)
    fun restoreHiddenBlocks() = prefs.restoreHiddenBlocks()
    fun dismissBook(bookId: String) = prefs.dismissBook(bookId)
    fun undismissBook(bookId: String) = prefs.undismissBook(bookId)
}
