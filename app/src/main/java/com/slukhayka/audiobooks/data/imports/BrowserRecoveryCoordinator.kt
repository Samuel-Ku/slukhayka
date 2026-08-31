package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #430 — high-level 4read recovery coordinator, pure JVM seam.
 *
 * One scenario supplies captured page data, ordered observed audio candidates,
 * an existing or absent Edition, a playback verdict, a clean-request verdict
 * and a [SharedBookMetaStore] fake; assertions cover the resulting Library
 * rows, Source Tracks, resume command, publication decision and visible outcome.
 *
 * State transition: capture → parse → identity guard → Track update → Player
 * verdict → UI outcome as one operation. Prefix `http(s)` alone is never
 * success; only a factual Player/media-open verdict is.
 */
class BrowserRecoveryCoordinator(
    private val dao: AudiobookDao,
    private val libraryImport: LibraryImport,
    private val profileStore: SharedBookMetaStore? = null,
    private val playbackVerifier: PlaybackVerifier = PlaybackVerifier { _, _ -> true },
    private val cleanProbe: CleanProbe = CleanProbe { true }
) {

    fun interface PlaybackVerifier {
        /** Returns true only when Player actually opened [trackUrl] (media-open verdict). */
        suspend fun verify(bookId: String, trackUrl: String): Boolean
    }

    fun interface CleanProbe {
        /** Returns true when a cookie-free probe can open [trackUrl] (shareable). */
        suspend fun canOpenClean(trackUrl: String): Boolean
    }

    sealed interface Outcome {
        data class Success(
            val book: AudiobookEntity,
            val shouldCloseBrowser: Boolean = true,
            val resumeChapterIndex: Int,
            val resumePositionMs: Long,
            val publishedProfile: Boolean = false,
            val isNewImport: Boolean = false
        ) : Outcome

        data class Failure(
            val message: String,
            val shouldCloseBrowser: Boolean = false,
            val keepAudioCount: Int = 0
        ) : Outcome

        data class StructureMismatch(
            val storedChapterCount: Int,
            val capturedChapterCount: Int,
            val shouldCloseBrowser: Boolean = false
        ) : Outcome {
            val message: String = "Не оновлено: у збереженій книзі $storedChapterCount розділ(и), на сайті зараз $capturedChapterCount. Прогрес не змінено."
        }
    }

    /**
     * Recovers an existing book's 4read Source or imports a new book from the
     * captured page. [bookId] null or blank means new import.
     *
     * @param bookId the existing book to recover, or null for new import
     * @param sourceId e.g. "4read"
     * @param url the captured page URL (exact Source URL for recovery)
     * @param html captured DOM
     * @param capturedAudioUrls ordered observed audio candidates (range-repeat deduplicated)
     * @param requestedChapterIndex the chapter the listener was on (for resume)
     * @param requestedPositionMs the position the listener was at
     */
    suspend fun recover(
        bookId: String?,
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String> = emptyList(),
        requestedChapterIndex: Int = 0,
        requestedPositionMs: Long = 0L
    ): Outcome = withContext(Dispatchers.IO) {
        // 1. Capture → parse is inside LibraryImport (which also handles capturedAudioUrls).
        // For new import we use importWebSourcePage, for recovery we use recoverWebSourcePage.
        val isNewImport = bookId.isNullOrBlank() || dao.getAudiobookById(bookId) == null
        if (!isNewImport) {
            val captured = libraryImport.inspectWebSourcePage(sourceId, url, html, capturedAudioUrls)
            val storedSource = dao.getSourcesForBookSync(bookId!!)
                .firstOrNull { it.type == sourceId && it.url == url }
            val storedBook = dao.getAudiobookById(bookId)?.toAudiobookEntity()
            val storedEdition = dao.getEditionForWork(bookId)
            val storedCount = dao.getChaptersListForBook(bookId!!).size
            if (
                captured != null &&
                storedSource != null &&
                storedBook != null &&
                RecoveryIdentityGuard.matchesWorkAndEdition(
                    storedTitle = storedBook.title,
                    storedAuthor = storedBook.author,
                    storedNarrator = storedEdition?.narrator.orEmpty(),
                    storedLanguage = storedEdition?.language.orEmpty(),
                    captured = captured
                ) &&
                captured.chapters.size != storedCount
            ) {
                return@withContext Outcome.StructureMismatch(storedCount, captured.chapters.size)
            }
        }
        // Snapshot old tracks for rollback on verifier failure (recovery only).
        val oldTracksSnapshot = if (!isNewImport) {
            val src = dao.getSourcesForBookSync(bookId!!).firstOrNull { it.type == sourceId && it.url == url }
            if (src != null) dao.getTracksForSourceSync(src.id).sortedBy { it.trackIndex } else emptyList()
        } else emptyList()
        val oldBookSnapshot = if (!isNewImport) dao.getAudiobookById(bookId!!) else null

        val book: AudiobookEntity? = if (isNewImport) {
            libraryImport.importWebSourcePage(sourceId, url, html, capturedAudioUrls)
        } else {
            libraryImport.recoverWebSourcePage(bookId!!, sourceId, url, html, capturedAudioUrls)
        }

        if (book == null) {
            return@withContext if (isChallengePage(html)) {
                Outcome.Failure(
                    message = "Сайт ще перевіряє браузер. Завершіть перевірку й спробуйте ще раз.",
                    shouldCloseBrowser = false
                )
            } else if (html.trim().length < 200) {
                Outcome.Failure(
                    message = "Сторінку не вдалося прочитати",
                    shouldCloseBrowser = false
                )
            } else {
                Outcome.Failure(
                    message = "Аудіо ще не знайдено. Відкрийте книгу та запустіть її на сайті, потім спробуйте ще раз.",
                    shouldCloseBrowser = false
                )
            }
        }

        // 2. Player verdict — http prefix alone is never success.
        val effectiveBookId = book.id
        // Determine which track to verify: for new import it's chapter 0, for recovery it's requested chapter.
        val verifyChapterIndex = if (isNewImport) 0 else requestedChapterIndex.coerceIn(0, (book.totalChapters - 1).coerceAtLeast(0))
        val tracks = dao.getTracksForBookSync(effectiveBookId).sortedBy { it.trackIndex }
        val track = tracks.getOrNull(verifyChapterIndex)
        val trackUrl = track?.url.orEmpty()

        if (trackUrl.isBlank() || !trackUrl.startsWith("http", ignoreCase = true)) {
            // Recovery rolls back the failed track update so the existing book keeps its old source;
            // a new import is NEVER deleted — the import already committed a Room row and deletion
            // would lose the book after force-stop (QA #430, hypothesis 3).
            if (!isNewImport && oldTracksSnapshot.isNotEmpty()) {
                dao.insertTracks(oldTracksSnapshot)
            }
            return@withContext Outcome.Failure(
                message = "Аудіо ще не знайдено. Відкрийте книгу та запустіть її на сайті, потім спробуйте ще раз.",
                shouldCloseBrowser = false
            )
        }

        val canPlay = try {
            playbackVerifier.verify(effectiveBookId, trackUrl)
        } catch (_: Exception) {
            false
        }

        if (!canPlay) {
            // Dead candidate — 403, dead URL, etc. Browser stays open.
            // Recovery rolls back to old tracks; a new import is NEVER deleted
            // (QA #430, hypothesis 3 — deletion would lose the book after force-stop).
            if (!isNewImport && oldTracksSnapshot.isNotEmpty()) {
                dao.insertTracks(oldTracksSnapshot)
            }
            return@withContext Outcome.Failure(
                message = "Аудіо недоступне. Перевірте з'єднання або спробуйте іншу книгу.",
                shouldCloseBrowser = false
            )
        }

        // 3. Optional profile publication — only after Player success and clean probe.
        var published = false
        if (profileStore != null) {
            val canOpenClean = try { cleanProbe.canOpenClean(trackUrl) } catch (_: Exception) { false }
            if (canOpenClean) {
                // Best-effort publish; failure is silent.
                runCatching {
                    // Reuse LibraryImport's profile write via import path? Instead, directly put profile.
                    // For now, we rely on LibraryImport's internal profileStore put on import; but for recovery we should publish here.
                    // To keep single place, we attempt to publish via profileStore using the book's current tracks.
                    // This is best-effort and does not affect outcome.
                    val edition = dao.getEditionForWork(effectiveBookId)
                    if (edition != null) {
                        val chapters = dao.getChaptersListForBook(effectiveBookId).sortedBy { it.chapterIndex }
                        val profileChapters = tracks.mapIndexed { idx, t ->
                            com.slukhayka.audiobooks.data.metadata.ProfileChapter(
                                title = chapters.getOrNull(idx)?.title ?: "Глава ${idx + 1}",
                                streamUrl = t.url,
                                durationSeconds = chapters.getOrNull(idx)?.durationSeconds ?: 0L
                            )
                        }
                        val profile = com.slukhayka.audiobooks.data.metadata.BookProfile(
                            coverImageUrl = book.coverImageUrl,
                            chapters = profileChapters,
                            totalDurationSeconds = book.totalDurationSeconds,
                            rating = book.rating.toDouble(),
                            genres = emptyList(),
                            seriesTitle = book.seriesTitle,
                            seriesIndex = book.seriesIndex,
                            description = book.description
                        )
                        profileStore.putProfile(
                            sourceId = sourceId,
                            editionId = edition.id,
                            profile = profile,
                            provenance = com.slukhayka.audiobooks.data.metadata.ProfileProvenance(
                                com.slukhayka.audiobooks.data.metadata.ProfileProvenance.SOURCE_RESOLVED,
                                System.currentTimeMillis()
                            )
                        )
                        published = true
                    }
                }
            }
        }

        val resumeChapter = if (isNewImport) 0 else verifyChapterIndex
        val resumePosition = if (isNewImport) 0L else requestedPositionMs

        Outcome.Success(
            book = book,
            shouldCloseBrowser = true,
            resumeChapterIndex = resumeChapter,
            resumePositionMs = resumePosition,
            publishedProfile = published,
            isNewImport = isNewImport
        )
    }

    companion object {
        private fun isChallengePage(html: String): Boolean {
            val page = html.lowercase()
            return "just a moment" in page ||
                "verify you are human" in page ||
                "cf-chl-" in page ||
                "cloudflare" in page && "challenge" in page
        }

        /** 4read search URL with prefilled Work title. */
        fun searchUrlFor(workTitle: String): String {
            val encoded = java.net.URLEncoder.encode(workTitle.trim(), "UTF-8")
            return "https://4read.org/index.php?do=search&subaction=search&story=$encoded"
        }

        /** Recovery entry URL: exact Source URL when present, otherwise search with Work title. */
        suspend fun recoveryEntryUrl(
            dao: AudiobookDao,
            bookId: String
        ): String {
            val book = dao.getAudiobookById(bookId) ?: return "https://4read.org/"
            val sources = dao.getSourcesForBookSync(bookId)
            val exact = sources.firstOrNull { it.type == "4read" }?.url?.takeIf { it.isNotBlank() }
            if (!exact.isNullOrBlank()) return exact
            val title = book.title.takeIf { it.isNotBlank() } ?: "книга"
            return searchUrlFor(title)
        }
    }
}
