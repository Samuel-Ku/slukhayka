@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.R
import com.example.data.db.AudiobookEntity
import com.example.data.repository.AudiobookRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MediaLibrarySession.Callback implementation for Android Auto, head units,
 * lock-screen and external media browsers (spec-21 Track A / T1).
 *
 * Exposes a structured media tree:
 *  - [ROOT_ID_ROOT]: Top-level root folder
 *    - [ROOT_ID_RECENTS]: "Продовжити слухати" (recently listened books)
 *    - [ROOT_ID_FAVORITES]: "Обране" (favorited books)
 *    - [ROOT_ID_CATALOG]: "Каталог" (full library catalogue)
 */
class AudiobookLibrarySessionCallback(
    private val context: Context,
    private val repository: AudiobookRepository,
    private val playerManager: AudioPlayerManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : MediaLibrarySession.Callback {

    companion object {
        const val ROOT_ID_ROOT = "root"
        const val ROOT_ID_RECENTS = "recents"
        const val ROOT_ID_FAVORITES = "favorites"
        const val ROOT_ID_CATALOG = "catalog"

        fun mapBookToMediaItem(book: AudiobookEntity, isPlayable: Boolean = true): MediaItem {
            val authorText = book.author?.ifBlank { "Слухайка" } ?: "Слухайка"
            val metadata = MediaMetadata.Builder()
                .setTitle(book.title)
                .setArtist(authorText)
                .setAlbumTitle(book.genre)
                .setArtworkUri(book.coverImageUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(isPlayable)
                .build()
            return MediaItem.Builder()
                .setMediaId(book.id)
                .setMediaMetadata(metadata)
                .build()
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = MediaItem.Builder()
            .setMediaId(ROOT_ID_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

        scope.launch(ioDispatcher) {
            try {
                when (parentId) {
                    ROOT_ID_ROOT -> {
                        val recentsNode = MediaItem.Builder()
                            .setMediaId(ROOT_ID_RECENTS)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Продовжити слухати")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .build()
                            )
                            .build()
                        val favoritesNode = MediaItem.Builder()
                            .setMediaId(ROOT_ID_FAVORITES)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Обране")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .build()
                            )
                            .build()
                        val catalogNode = MediaItem.Builder()
                            .setMediaId(ROOT_ID_CATALOG)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Каталог")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .build()
                            )
                            .build()
                        future.set(
                            LibraryResult.ofItemList(
                                ImmutableList.of(recentsNode, favoritesNode, catalogNode),
                                params
                            )
                        )
                    }
                    ROOT_ID_RECENTS -> {
                        val progressList = repository.recentProgress.first()
                        val distinctBookIds = progressList.map { it.bookId }.distinct()
                        val items = mutableListOf<MediaItem>()
                        for (bookId in distinctBookIds) {
                            val book = repository.getBookSync(bookId)
                            if (book != null) {
                                items.add(mapBookToMediaItem(book))
                            }
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                    }
                    ROOT_ID_FAVORITES -> {
                        val allBooks = repository.allBooks.first()
                        val favorites = allBooks.filter { it.isFavorite }
                        val items = favorites.map { mapBookToMediaItem(it) }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                    }
                    ROOT_ID_CATALOG -> {
                        val allBooks = repository.allBooks.first()
                        val items = allBooks.map { mapBookToMediaItem(it) }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                    }
                    else -> {
                        val chapters = repository.getChaptersList(parentId)
                        val items = chapters.map { ch ->
                            val meta = MediaMetadata.Builder()
                                .setTitle(ch.title)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                            MediaItem.Builder()
                                .setMediaId("${parentId}_chapter_${ch.chapterIndex}")
                                .setMediaMetadata(meta)
                                .build()
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                    }
                }
            } catch (e: Exception) {
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }

        return future
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (mediaId == ROOT_ID_ROOT) {
            return onGetLibraryRoot(session, browser, null)
        }
        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        scope.launch(ioDispatcher) {
            when (mediaId) {
                ROOT_ID_RECENTS -> {
                    val recentsNode = MediaItem.Builder()
                        .setMediaId(ROOT_ID_RECENTS)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Продовжити слухати")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                    future.set(LibraryResult.ofItem(recentsNode, null))
                }
                ROOT_ID_FAVORITES -> {
                    val favoritesNode = MediaItem.Builder()
                        .setMediaId(ROOT_ID_FAVORITES)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Обране")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                    future.set(LibraryResult.ofItem(favoritesNode, null))
                }
                ROOT_ID_CATALOG -> {
                    val catalogNode = MediaItem.Builder()
                        .setMediaId(ROOT_ID_CATALOG)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Каталог")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                    future.set(LibraryResult.ofItem(catalogNode, null))
                }
                else -> {
                    val rawId = if (mediaId.contains("_chapter_")) mediaId.substringBefore("_chapter_") else mediaId
                    val book = repository.getBookSync(rawId)
                    if (book != null) {
                        future.set(LibraryResult.ofItem(mapBookToMediaItem(book), null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                }
            }
        }
        return future
    }

    override fun onSetMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(mainDispatcher) {
            val target = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            if (target != null) {
                val rawId = target.mediaId
                val bookId = if (rawId.contains("_chapter_")) rawId.substringBefore("_chapter_") else rawId
                val targetChapterIndex = if (rawId.contains("_chapter_")) {
                    rawId.substringAfter("_chapter_").toIntOrNull() ?: 0
                } else null

                val book = withContext(ioDispatcher) { repository.getBookSync(bookId) }
                if (book != null) {
                    val chapters = withContext(ioDispatcher) { repository.getChaptersList(bookId) }
                    val progress = withContext(ioDispatcher) { repository.getProgressSync(bookId) }
                    val chapterIdx = targetChapterIndex ?: (progress?.currentChapterIndex ?: 0)
                    val posSec = if (targetChapterIndex != null) 0L else (progress?.currentPositionSeconds ?: 0L)
                    playerManager.loadAndPlayBook(
                        book = book,
                        chapters = chapters,
                        initialChapterIndex = chapterIdx,
                        initialPositionSeconds = posSec,
                        autoPlay = true
                    )
                }
            }
            future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
        }
        return future
    }

    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(mainDispatcher) {
            val currentBook = playerManager.playerState.value.currentBook
            if (currentBook != null) {
                val mediaItem = mapBookToMediaItem(currentBook)
                val posMs = playerManager.playerState.value.currentPositionMs
                val chapterIdx = playerManager.playerState.value.currentChapterIndex
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(mediaItem), chapterIdx, posMs))
            } else {
                val progressList = withContext(ioDispatcher) { repository.recentProgress.first() }
                val latest = progressList.maxByOrNull { it.lastListenedAt }
                if (latest != null) {
                    val book = withContext(ioDispatcher) { repository.getBookSync(latest.bookId) }
                    if (book != null) {
                        val mediaItem = mapBookToMediaItem(book)
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(mediaItem),
                                latest.currentChapterIndex,
                                latest.currentPositionSeconds * 1000L
                            )
                        )
                    } else {
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    }
                } else {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }
        }
        return future
    }
}
