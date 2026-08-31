package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.library.siblingNarrations
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.ReviewSaveResult
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.reviewWorkIdFor
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.library.BookPlayState
import com.slukhayka.audiobooks.ui.library.bookPlayLabel
import com.slukhayka.audiobooks.ui.library.bookPlayState
import com.slukhayka.audiobooks.ui.library.bookPositionAndTotal
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    viewModel: MainViewModel,
    // ADR-0008 batch 4 (#159): the screen receives the modules it acts on as
    // parameters, wired from the composition root — the injection idiom
    // settled by #154. Bookmark creation, download and the selection flow
    // stay orchestrated by the ViewModel.
    listeningState: ListeningStateStore,
    offlineDownloads: OfflineDownloads,
    // ADR-0011: the screen reads the other rendition cards of the same Work
    // (the «Інші начитки» block) straight from the module.
    libraryEntries: LibraryEntries,
    personBookmarks: PersonBookmarks,
    onBackClick: () -> Unit,
    playerModalVisible: Boolean = false,
    returnFocusOrigin: BookDetailLinkOrigin? = null,
    fullPlayerModalActive: Boolean = false,
    onChildRouteOpened: (BookDetailLinkOrigin) -> Unit = {},
    onReturnFocusRestored: (BookDetailLinkOrigin) -> Unit = {}
) {
    val book by viewModel.selectedBook.collectAsState()
    val chapters by viewModel.selectedBookChapters.collectAsState()
    val bookmarks by viewModel.selectedBookBookmarks.collectAsState()
    val relatedBooks by viewModel.relatedBooks.collectAsState()
    // Spec-25 (#171): the book's series universe — the «Всесвіт» line under
    // the series pill. Null until the lazy resolution cached it (silent).
    val bookUniverse by viewModel.selectedBookUniverse.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val downloadingBookId by viewModel.downloadingBookId.collectAsState()
    val downloadMessage by viewModel.downloadMessage.collectAsState()
    val downloadRecoveryBookId by viewModel.downloadRecoveryBookId.collectAsState()
    // Spec-15 T5: what every source carrying the Work says about it.
    val sourceProfiles by viewModel.sourceProfiles.collectAsState()
    // Spec-23 T5: every Edition carrying the Work — the «Джерела» section.
    val bookSources by viewModel.bookSources.collectAsState()

    // #40 decision 1: the favourite toggle lives on the book page itself.
    val favoriteBooks by viewModel.libraryEntries.getFavoriteAudiobooks()
        .collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var showDeleteSheet by remember { mutableStateOf(false) }
    // #382 (spec-27): видалення переїхало в ⋮ меню шапки — тригер сам по собі
    // не запускає видалення, лише відкриває список рідкісних дій.
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var bookmarkToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }
    var bookmarkDeleteOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    var playerReturnFocusChapterId by remember { mutableStateOf<String?>(null) }
    var playerReturnFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    val deleteTriggerFocusRequester = remember { FocusRequester() }

    RestoreFocusAfterModal(
        modalVisible = playerModalVisible,
        returnFocusRequester = playerReturnFocusRequester,
        onFocusRestored = {
            playerReturnFocusChapterId = null
            playerReturnFocusRequester = null
        }
    )

    // Spec-40 #277/#278/#280 — «Відгуки»: form open/edit state, delete
    // confirmation target. The store itself rides MainViewModel (null
    // without Firebase keys → no block rendered).
    var showReviewForm by remember { mutableStateOf(false) }
    var editingReview by remember { mutableStateOf<com.slukhayka.audiobooks.data.reviews.ListenerReview?>(null) }
    var reviewToDelete by remember { mutableStateOf<com.slukhayka.audiobooks.data.reviews.ListenerReview?>(null) }
    var reviewSaveInProgress by remember { mutableStateOf(false) }
    var reviewSaveError by remember { mutableStateOf<String?>(null) }
    val reviewDeleteFocusRequester = remember { FocusRequester() }
    val narrationRatingDeleteFocusRequester = remember { FocusRequester() }

    val currentBook = book ?: return
    var initialTitleFocusPending by remember(currentBook.id) { mutableStateOf(true) }
    val isDownloadingThis = downloadingBookId == currentBook.id
    // #392 — estimated size and live download progress
    var estimatedSize by remember(currentBook.id) {
        mutableStateOf<OfflineDownloads.EstimatedSize?>(null)
    }
    LaunchedEffect(currentBook.id) {
        if (!currentBook.isDownloaded && !isDownloadingThis) {
            estimatedSize = try { offlineDownloads.estimateOfflineSize(currentBook.id) } catch (_: Exception) { null }
        }
    }
    val downloadBytesMap by offlineDownloads.downloadBytesProgress.collectAsState()
    val bytesProgress = downloadBytesMap[currentBook.id]
    // ADR-0011: the other rendition cards of this Work — the «Інші начитки»
    // block. Cold flow collected once per composition; the pure filter is
    // JVM-tested (siblingNarrations).
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val siblingCards = remember(currentBook.id, currentBook.mergeKey, allBooks) {
        siblingNarrations(allBooks, currentBook.id, currentBook.mergeKey ?: "")
    }
    // ADR-0023 (#357): each sibling card's Edition id → its own narration
    // ratings (loaded once per Work by the reviews trigger above).
    var siblingEditionIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(siblingCards) {
        siblingEditionIds = siblingCards.mapNotNull { sib ->
            viewModel.editionIdForBook(sib.id)?.let { sib.id to it }
        }.toMap()
    }
    // Spec-10 T6: stream-only sources (lihtar — its ToS forbids reproduction)
    // hide the download action; the repository refuses anyway, in depth.
    // ADR-0008: the pure stream-only decision is read from the source helpers
    // directly — no forwarding function on the ViewModel.
    val streamOnly = streamOnlyFor(sourceIdForUrl(currentBook.sourceUrl))
    // ADR-0008: suspend module calls from user actions run on the composition
    // scope (same pattern as playerManager's call-through).
    val scope = rememberCoroutineScope()

    val authorIdentity = remember(currentBook.author) {
        currentBook.author.takeIf { it.isNotBlank() }
            ?.let { personBookmarks.identity(PersonRole.AUTHOR, it) }
    }
    val narratorIdentity = remember(currentBook.narrator) {
        currentBook.narrator.takeIf { it.isNotBlank() }
            ?.let { personBookmarks.identity(PersonRole.NARRATOR, it) }
    }
    val authorBookmarkFlow = remember(authorIdentity) {
        authorIdentity?.let {
            personBookmarks.observePersonBookmark(it.role.storageValue, it.id)
        } ?: flowOf(null)
    }
    val narratorBookmarkFlow = remember(narratorIdentity) {
        narratorIdentity?.let {
            personBookmarks.observePersonBookmark(it.role.storageValue, it.id)
        } ?: flowOf(null)
    }
    val authorBookmark by authorBookmarkFlow.collectAsState(initial = null)
    val narratorBookmark by narratorBookmarkFlow.collectAsState(initial = null)

    // Spec-40 #277 — reviews anchor to the WORK (shared across narrations);
    // a row without a Works identity anchors to itself.
    val reviewsWorkId = reviewWorkIdFor(currentBook.id, currentBook.workId)

    // Spec-40 #277/#278/#280 — «Відгуки» state. Collected at the composable
    // root (LazyListScope blocks are not composable contexts); the store
    // itself rides MainViewModel (null without Firebase keys → no block).
    val listenerProfile by viewModel.listenerIdentity.collectAsState()
    val bookReviews by viewModel.bookReviews.collectAsState()
    val pendingReviewKeys by viewModel.pendingReviewKeys.collectAsState()

    // ADR-0023 (#348): narration ratings of this Work + THIS card's Edition id.
    val narrationRatings by viewModel.narrationRatings.collectAsState()
    var currentEditionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentBook.id) {
        currentEditionId = viewModel.editionIdForBook(currentBook.id)
    }
    val editionNarrationRatings = remember(narrationRatings, currentEditionId) {
        currentEditionId?.let { id -> narrationRatings.filter { it.editionId == id } }.orEmpty()
    }
    val narrationAverage = remember(editionNarrationRatings) {
        editionNarrationRatings.map { it.rating }.takeIf(List<Int>::isNotEmpty)
            ?.let { votes -> votes.average() }
    }
    val ownNarrationRating = remember(editionNarrationRatings, listenerProfile) {
        listenerProfile?.uid?.let { uid -> editionNarrationRatings.firstOrNull { it.uid == uid } }
    }
    // #358 — the delete confirmation lives here (destructive action never
    // looks neutral, spec-27); the row only raises the request.
    var showNarrationRatingDeleteConfirm by remember { mutableStateOf(false) }
    val detailPresentation = remember(currentBook, sourceProfiles, bookSources, bookReviews) {
        bookDetailPresentation(
            book = currentBook,
            sourceProfiles = sourceProfiles,
            playableSources = bookSources,
            listenerRatings = bookReviews.map { it.rating }
        )
    }
    LaunchedEffect(reviewsWorkId) {
        viewModel.loadReviews(reviewsWorkId)
        // Spec-40 #281 — the local mute list rides every branch read.
        viewModel.loadHiddenAuthors()
        // ADR-0023 (#348) — the narration ratings ride the same trigger.
        viewModel.loadNarrationRatings(reviewsWorkId)
    }

    // #358 — delete confirmation quoting the exact scope (spec-27). The new
    // destructive surface joins the same modal focus contract as reviews and
    // bookmarks: focus enters its heading and returns to the exact launcher.
    RestoreFocusAfterModal(
        modalVisible = showNarrationRatingDeleteConfirm,
        returnFocusRequester = narrationRatingDeleteFocusRequester,
        fallbackFocusRequester = deleteTriggerFocusRequester
    )
    if (showNarrationRatingDeleteConfirm && currentEditionId != null) {
        val editionId = currentEditionId.orEmpty()
        val titleFocusRequester = remember { FocusRequester() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNarrationRatingDeleteConfirm = false },
            modifier = Modifier
                .accessibilityPane("Видалення оцінки начитки")
                .testTag("narration_rating_delete_dialog"),
            title = {
                Text(
                    "Видалити оцінку начитки?",
                    modifier = Modifier
                        .focusRequester(titleFocusRequester)
                        .focusable()
                        .semantics { heading() }
                )
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    titleFocusRequester.requestFocus()
                }
            },
            text = {
                Text(
                    "Ваші зірки біля «${detailPresentation.narrator}» буде прибрано з цієї сторінки."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOwnNarrationRating(reviewsWorkId, editionId)
                    showNarrationRatingDeleteConfirm = false
                }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text("Видалити", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNarrationRatingDeleteConfirm = false },
                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                ) { Text("Скасувати") }
            }
        )
    }

    // Spec-40 #278 — THIS Work's editions from the local DB, labeled by
    // narrator: the edition-tag dropdown's options.
    val workEditionNarrators = remember(currentBook.id, currentBook.mergeKey, allBooks) {
        if (currentBook.mergeKey.isBlank()) emptyList()
        else allBooks
            .filter { it.mergeKey == currentBook.mergeKey }
            .map { it.displayNarrator }
            .filter { it.isNotBlank() }
            .distinct()
    }

    // Offline-download outcome feedback: the repository may find no audio for
    // a catalogue book whose page could not be fetched — surface that instead
    // of the button silently doing nothing.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(downloadMessage, downloadRecoveryBookId) {
        downloadMessage?.let { message ->
            val recoveryBookId = downloadRecoveryBookId
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = recoveryBookId?.let { "Оновити через браузер" }
            )
            if (result == SnackbarResult.ActionPerformed && recoveryBookId != null) {
                viewModel.open4ReadRecovery(recoveryBookId, chapterIndex = 0, positionMs = 0L)
            }
            viewModel.consumeDownloadMessage()
            viewModel.consumeDownloadRecovery()
        }
    }
    val reviewQueuedMessage = stringResource(R.string.book_detail_review_save_queued)
    val reviewFailureMessage = stringResource(R.string.book_detail_review_save_error)
    LaunchedEffect(viewModel, snackbarHostState, reviewsWorkId) {
        viewModel.reviewSaveResults.collect { event ->
            if (event.workId != reviewsWorkId) return@collect
            reviewSaveInProgress = false
            when (event.result) {
                ReviewSaveResult.FAILED -> {
                    reviewSaveError = reviewFailureMessage
                    if (reviewFailureNeedsSnackbar(showReviewForm)) {
                        snackbarHostState.showSnackbar(reviewFailureMessage)
                    }
                }
                ReviewSaveResult.PUBLISHED -> {
                    // The queue event already closed the form and announced
                    // acceptance. Publication retires state without a second,
                    // misleading success announcement.
                    reviewSaveError = null
                }
                ReviewSaveResult.QUEUED -> {
                    reviewSaveError = null
                    showReviewForm = false
                    editingReview = null
                    snackbarHostState.showSnackbar(reviewQueuedMessage)
                }
            }
        }
    }

    // Related books ("Можливо, Тебе зацікавить:") — load once per opened book;
    // navigating to a related book re-triggers this via the new book id.
    LaunchedEffect(currentBook.id) {
        viewModel.loadRelatedBooks(currentBook.id)
    }

    // #40 decision 1: the other volumes of the book's series («У серії»),
    // fetched once per opened book through the catalog seam. The series
    // fetch is session-cached, so this row costs nothing once the series
    // page loaded.
    var inSeriesBooks by remember { mutableStateOf<List<AudiobookEntity>>(emptyList()) }
    LaunchedEffect(currentBook.id) {
        val url = currentBook.seriesUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        inSeriesBooks = try {
            viewModel.sourceCatalog.fetchSeriesBooks(url)
                .filterNot { it.id == currentBook.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // #40 decision 1: the main button reflects the book's real state — plain
    // start, resume-with-position, re-listen of a finished book, or the live
    // Playing label while this book is on the player. Position and total run
    // on the same rule as the library card (spec-16 T4).
    val progress = viewModel.libraryEntries.recentProgress
        .collectAsState(initial = emptyList()).value
        .firstOrNull { it.bookId == currentBook.id }
    val (cumulativePosition, totalDuration) = bookPositionAndTotal(
        chapters = chapters,
        progress = progress,
        bookTotalDurationSeconds = currentBook.totalDurationSeconds
    )
    val playState = bookPlayState(
        // "Playing" means the audio is ACTUALLY playing, not merely that the
        // player has this book loaded: a restored-but-paused session must read
        // «Продовжити з …», never a play/pause label (2026-08-17 bug report —
        // the old check was loaded-only, so the button always showed «Грає»
        // whether paused or playing).
        isPlayingThisBook = playerState.currentBook?.id == currentBook.id && playerState.isPlaying,
        progress = progress,
        cumulativePositionSeconds = cumulativePosition,
        totalDurationSeconds = totalDuration
    )
    val paneTitle = stringResource(R.string.book_detail_pane_title, detailPresentation.title)
    val downloadActionDescription = when {
        isDownloadingThis -> stringResource(
            R.string.book_detail_download_in_progress,
            currentBook.title,
            (currentBook.downloadProgress.coerceIn(0f, 1f) * 100).toInt()
        )
        currentBook.isDownloaded -> stringResource(
            R.string.book_detail_download_remove,
            currentBook.title
        )
        else -> stringResource(R.string.book_detail_download_add, currentBook.title)
    }
    val downloadStateDescription = stringResource(
        when {
            isDownloadingThis -> R.string.book_detail_downloading
            currentBook.isDownloaded -> R.string.book_detail_downloaded
            else -> R.string.book_detail_streaming
        }
    )

    Scaffold(
        modifier = Modifier.accessibilityModalBackground(
            modalVisible = showAddBookmarkDialog || showDeleteSheet || showDeleteDialog ||
                showReviewForm || bookmarkToDelete != null || reviewToDelete != null ||
                showNarrationRatingDeleteConfirm
        ),
        topBar = {
            // The host Scaffold in MainActivity already consumed the status
            // bar (innerPadding.top), so this inner TopAppBar must NOT add
            // statusBarsPadding again or the header sits a full status-bar
            // height too low.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        detailPresentation.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The pane announcement plus the heading in the body
                        // already identify the Work. Keep the pinned toolbar
                        // title visual without making TalkBack read it twice.
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("book_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.book_detail_back)
                        )
                    }
                },
                actions = {
                    // #40 decision 1: the favourite toggle lives on the book
                    // page itself — the place where the user decides a book is
                    // worth keeping at hand.
                    val isFavoriteThis = favoriteBooks.any { it.id == currentBook.id }
                    FavoriteButton(
                        isFavorite = isFavoriteThis,
                        bookTitle = currentBook.title,
                        onToggle = {
                            scope.launch {
                                viewModel.libraryEntries.toggleFavorite(
                                    currentBook.id,
                                    !isFavoriteThis
                                )
                            }
                        }
                    )
                    // 4read's browser session is the supported way to refresh
                    // Cloudflare-gated playlists, so keep this action in-app.
                    if (currentBook.sourceUrl.contains("4read.org")) {
                        IconButton(onClick = {
                            viewModel.openWebSource("4read", currentBook.sourceUrl, "4read")
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(
                                    R.string.a11y_book_detail_open_site,
                                    currentBook.title
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Spec-13 T3: a WebView-source book opens the source's own
                    // browser surface (the site needs the session past CF).
                    if (currentBook.sourceUrl.contains("sluhay.com") &&
                        !currentBook.sourceUrl.contains("sluhay.com.ua")
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.openWebSource(
                                    sourceId = "sluhay",
                                    homeUrl = "https://sluhay.com/",
                                    displayName = "Sluhay"
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(
                                    R.string.a11y_book_detail_open_sluhay,
                                    currentBook.title
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (!streamOnly) {
                        IconButton(
                            onClick = {
                                if (currentBook.isDownloaded) {
                                    scope.launch { offlineDownloads.removeOfflineDownload(currentBook.id) }
                                } else {
                                    viewModel.downloadBookOffline(currentBook.id)
                                }
                            },
                            enabled = !isDownloadingThis,
                            modifier = Modifier.semantics {
                                contentDescription = downloadActionDescription
                                stateDescription = downloadStateDescription
                            }
                        ) {
                            if (isDownloadingThis) {
                                CircularProgressIndicator(
                                    progress = { currentBook.downloadProgress.coerceIn(0.05f, 0.95f) },
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = if (currentBook.isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = if (currentBook.isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    // #392 — size display below download button
                    if (!streamOnly && !currentBook.isDownloaded && !isDownloadingThis) {
                        val sizeText = when {
                            bytesProgress != null -> {
                                val dl = bytesProgress!!.downloadedBytes / (1024 * 1024)
                                val tot = bytesProgress!!.totalBytes?.let { it / (1024 * 1024) } ?: 0L
                                val pct = if (tot > 0) (dl * 100 / tot).toInt() else 0
                                stringResource(
                                    R.string.book_detail_download_progress,
                                    bytesProgress!!.completedChapters,
                                    bytesProgress!!.totalChapters,
                                    dl,
                                    tot,
                                    pct
                                )
                            }
                            estimatedSize != null -> {
                                val es = estimatedSize!!
                                val mb = es.totalBytes?.let { it / (1024 * 1024) }
                                if (mb != null && mb > 0) {
                                    if (es.isApproximate) stringResource(R.string.book_detail_size_approximate, mb)
                                    else stringResource(R.string.book_detail_size_format, mb)
                                } else stringResource(R.string.book_detail_size_unknown)
                            }
                            else -> stringResource(R.string.book_detail_size_unknown)
                        }
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    } else if (!streamOnly && isDownloadingThis && bytesProgress != null) {
                        val bp = bytesProgress!!
                        val dl = bp.downloadedBytes / (1024 * 1024)
                        val tot = bp.totalBytes?.let { it / (1024 * 1024) } ?: 0L
                        val pct = if (tot > 0) (dl * 100 / tot).toInt() else 0
                        Text(
                            text = stringResource(
                                R.string.book_detail_download_progress,
                                bp.completedChapters,
                                bp.totalChapters,
                                dl,
                                tot,
                                pct
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    // #382 (spec-27): deletion is a rare action — it lives in
                    // the ⋮ overflow, not next to Favorite/Download. The
                    // confirmation flow (Wayfinder #28 sheet) is untouched:
                    // only the entry point moved.
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier
                                .size(AppDimens.TouchTarget)
                                // Фокус повертається сюди ж: ⋮ — нова точка входу
                                // видалення, контракт модалок не змінювався.
                                .focusRequester(deleteTriggerFocusRequester)
                                .testTag("book_detail_delete_trigger")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Інші дії",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.a11y_book_detail_delete_work, currentBook.title)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showDeleteSheet = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .accessibilityPane(paneTitle)
                .testTag("book_detail_screen"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Book Header Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BookDetailIdentityHeader(
                        book = currentBook,
                        presentation = detailPresentation,
                        universeName = bookUniverse?.universeName,
                        requestInitialFocus = initialTitleFocusPending,
                        onInitialFocusHandled = { initialTitleFocusPending = false },
                        returnFocusOrigin = returnFocusOrigin,
                        onChildRouteOpened = onChildRouteOpened,
                        onReturnFocusRestored = onReturnFocusRestored,
                        narrationAverage = narrationAverage,
                        narrationVoteCount = editionNarrationRatings.size,
                        ownNarrationRating = ownNarrationRating?.rating,
                        canRateNarration = viewModel.narrationRatingsStore != null &&
                            listenerProfile != null && currentEditionId != null,
                        onRateNarration = { stars ->
                            val editionId = currentEditionId
                            if (editionId != null && ownNarrationRating?.rating != stars) {
                                // Re-tapping the already-set star is a no-op —
                                // never a rewrite with a fresh editedAt (#358).
                                viewModel.saveNarrationRating(
                                    workId = reviewsWorkId,
                                    editionId = editionId,
                                    rating = stars,
                                    editingCreatedAt = ownNarrationRating?.createdAt
                                )
                            }
                        },
                        onDeleteNarrationRating = ownNarrationRating?.let {
                            { showNarrationRatingDeleteConfirm = true }
                        },
                        narrationRatingDeleteFocusRequester = narrationRatingDeleteFocusRequester,
                        onAuthorClick = { author ->
                            viewModel.openPersonBooks(
                                CatalogPerson(
                                    author,
                                    bookPersonPath("avtor", author),
                                    0,
                                    PersonRole.AUTHOR
                                )
                            )
                        },
                        onNarratorClick = { narrator ->
                            viewModel.openPersonBooks(
                                CatalogPerson(
                                    narrator,
                                    bookPersonPath("chitaet", narrator),
                                    0,
                                    PersonRole.NARRATOR
                                )
                            )
                        },
                        onSeriesClick = { title, url -> viewModel.openSeries(title, url) },
                        onWrongUniverse = { viewModel.reportWrongUniverse(currentBook.id) },
                        authorBookmark = PersonBookmarkControl(
                            isBookmarked = authorBookmark != null,
                            notifyEnabled = authorBookmark?.notifyEnabled ?: true,
                            onToggle = {
                                authorIdentity?.let { identity ->
                                    scope.launch { personBookmarks.toggle(identity) }
                                }
                            },
                            onToggleNotify = { enabled ->
                                authorIdentity?.let { identity ->
                                    scope.launch {
                                        personBookmarks.setNotifyEnabled(
                                            PersonBookmarkKey(identity.role, identity.id),
                                            enabled
                                        )
                                    }
                                }
                            }
                        ),
                        narratorBookmark = PersonBookmarkControl(
                            isBookmarked = narratorBookmark != null,
                            notifyEnabled = narratorBookmark?.notifyEnabled ?: true,
                            onToggle = {
                                narratorIdentity?.let { identity ->
                                    scope.launch { personBookmarks.toggle(identity) }
                                }
                            },
                            onToggleNotify = { enabled ->
                                narratorIdentity?.let { identity ->
                                    scope.launch {
                                        personBookmarks.setNotifyEnabled(
                                            PersonBookmarkKey(identity.role, identity.id),
                                            enabled
                                        )
                                    }
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    BookDetailDescription(detailPresentation)

                    // ADR-0011: «Інші начитки» — the other rendition cards of
                    // the same Work. Tapping one opens that card (its own
                    // narrator, chapters, progress) — the narration selection.
                    if (siblingCards.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Інші начитки",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .semantics { heading() }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        siblingCards.forEach { sibling ->
                            // ADR-0023 (#348/#357): each rendition carries ITS
                            // OWN narration average — the comparison point.
                            val siblingStats = run {
                                val eid = siblingEditionIds[sibling.id]
                                val votes = eid?.let { id -> narrationRatings.filter { it.editionId == id } }
                                    .orEmpty()
                                votes.takeIf { it.isNotEmpty() }?.let { Pair(it.map { r -> r.rating }.average(), it.size) }
                            }
                            NarrationRowCard(
                                sibling = sibling,
                                average = siblingStats?.first,
                                voteCount = siblingStats?.second ?: 0,
                                onClick = { viewModel.selectBook(sibling.id) }
                            )
                        }
                    }

                    // Spec-23 T5/#426: «Джерела» is informational — it shows
                    // which sources carry the Work and whether a browser is
                    // required. Playback chooses the shared source order.
                    BookDetailSourceSection(detailPresentation)

                    Spacer(modifier = Modifier.height(20.dp))

                    BookDetailPrimaryActions(
                        workTitle = currentBook.title,
                        playLabel = bookPlayLabel(playState) { MainViewModel.formatTime(it) },
                        streamOnly = streamOnly,
                        isDownloaded = currentBook.isDownloaded,
                        isDownloading = isDownloadingThis,
                        downloadProgress = currentBook.downloadProgress,
                        onPlay = {
                            when (playState) {
                                BookPlayState.Playing -> viewModel.playerManager.pause()
                                BookPlayState.Finished -> viewModel.relistenBook(currentBook)
                                else -> viewModel.playAudiobook(currentBook)
                            }
                            if (playState !is BookPlayState.Playing) {
                                viewModel.setShowFullPlayer(true)
                            }
                        },
                        onDownload = {
                            if (currentBook.isDownloaded) {
                                scope.launch { offlineDownloads.removeOfflineDownload(currentBook.id) }
                            } else {
                                viewModel.downloadBookOffline(currentBook.id)
                            }
                        },
                        onAddBookmark = { showAddBookmarkDialog = true }
                    )
                }
            }

            // Tab row (Chapters vs Bookmarks)
            item {
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = {
                            Text(
                                text = "Розділи (${chapters.size})",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = {
                            Text(
                                text = "Закладки (${bookmarks.size})",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            // Chapter list
            if (activeTab == 0) {
                itemsIndexed(chapters) { index, chapter ->
                    val chapterFocusRequester = remember(chapter.id) { FocusRequester() }
                    LaunchedEffect(
                        playerReturnFocusChapterId,
                        chapterFocusRequester
                    ) {
                        if (playerReturnFocusChapterId == chapter.id) {
                            // Playback inserts the mini-player and can recreate
                            // this lazy row. Reconnect the stable chapter id to
                            // the requester attached to the current live Card.
                            playerReturnFocusRequester = chapterFocusRequester
                        }
                    }
                    val isCurrentChapter = playerState.currentBook?.id == currentBook.id &&
                        playerState.currentChapterIndex == index
                    val isPlayingThis = isCurrentChapter && playerState.isPlaying

                    ChapterRowItem(
                        chapter = chapter,
                        index = index,
                        isCurrent = isCurrentChapter,
                        isPlaying = isPlayingThis,
                        focusRequester = chapterFocusRequester,
                        onPlayClick = {
                            // Make the activated row the modal's real focus
                            // origin before the background focusRestorer takes
                            // its snapshot. Compose test clicks, unlike keyboard
                            // activation, do not focus the node automatically.
                            chapterFocusRequester.requestFocus()
                            playerReturnFocusChapterId = chapter.id
                            playerReturnFocusRequester = chapterFocusRequester
                            if (isCurrentChapter) {
                                viewModel.playerManager.play()
                            } else {
                                viewModel.playAudiobook(currentBook, chapterIndex = index)
                            }
                            viewModel.setShowFullPlayer(true)
                        },
                        onPauseClick = { viewModel.playerManager.pause() }
                    )
                }
            } else {
                // Bookmarks list
                if (bookmarks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.book_detail_no_bookmarks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        val bookmarkDeleteFocusRequester = remember(bookmark.id) { FocusRequester() }
                        BookmarkRowItem(
                            bookmark = bookmark,
                            workTitle = currentBook.title,
                            onJumpClick = { viewModel.jumpToBookmark(bookmark) },
                            onDeleteClick = {
                                bookmarkDeleteOrigin = bookmarkDeleteFocusRequester
                                bookmarkToDelete = bookmark
                            },
                            deleteFocusRequester = bookmarkDeleteFocusRequester
                        )
                    }
                }
            }

            // Related books from the book page ("Можливо, Тебе зацікавить:").
            if (relatedBooks.isNotEmpty()) {
                item {
                    Text(
                        text = "Можливо, Тебе зацікавить",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(relatedBooks, key = { it.id }) { related ->
                            CatalogBookCard(
                                book = CatalogBook(
                                    id = related.id,
                                    title = related.title,
                                    author = related.author,
                                    url = related.sourceUrl,
                                    coverImageUrl = related.coverImageUrl
                                ),
                                onClick = { viewModel.selectBook(related.id) }
                            )
                        }
                    }
                }
            }

            // #40 decision 1: the other volumes of the book's series ("У
            // серії"), the book itself excluded. The series fetch is session-
            // cached, so this row costs nothing once the series page loaded.
            if (inSeriesBooks.isNotEmpty()) {
                item {
                    Text(
                        text = "У серії",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(inSeriesBooks, key = { it.id }) { seriesBook ->
                            CatalogBookCard(
                                book = CatalogBook(
                                    id = seriesBook.id,
                                    title = seriesBook.title,
                                    author = seriesBook.author,
                                    url = seriesBook.sourceUrl,
                                    coverImageUrl = seriesBook.coverImageUrl
                                ),
                                onClick = { viewModel.selectBook(seriesBook.id) }
                            )
                        }
                    }
                }
            }

            // Spec-40 #277/#278/#280 — «Відгуки», the bottom block of the
            // book page. Reviews anchor to the WORK (shared across
            // narrations); without the store (no Firebase keys) the block
            // degrades to absent — never a fake state.
            if (viewModel.listenerReviews != null) {
                item(key = "reviews_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Відгуки (${bookReviews.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }
                        )
                        // Writing needs a listener identity — until lane-a's
                        // seam answers, the block stays honestly read-only.
                        if (listenerProfile != null) {
                            Button(
                                onClick = {
                                    editingReview = null
                                    reviewSaveError = null
                                    showReviewForm = true
                                },
                                shape = RoundedCornerShape(AppDimens.RadiusCard)
                            ) {
                                Text("Написати відгук")
                            }
                        }
                    }
                }

                // Spec-40 #279 — the honest headline average: flat mean over
                // every source WITH a rating and every listener review. No
                // addends → no row at all (ADR-0014: zeros are never drawn);
                // the source's own ★ stays a separate row as before.
                item(key = "reviews_average") {
                    val average = detailPresentation.combinedAverage
                    if (average != null) {
                        val combinedSummary = stringResource(
                            R.string.book_detail_review_combined_summary,
                            average.value,
                            average.count
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clearAndSetSemantics {
                                    contentDescription = combinedSummary
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "%.1f".format(java.util.Locale.US, average.value),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "джерела і слухачі · ${average.count} оцінок",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (bookReviews.isEmpty()) {
                    item(key = "reviews_empty") {
                        Text(
                            text = "Ще немає відгуків — станьте першим",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(
                        bookReviews,
                        key = { com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec.documentId(it.workId, it.uid) }
                    ) { review ->
                        ReviewCard(
                            review = review,
                            workTitle = currentBook.title,
                            isOwn = listenerProfile?.uid == review.uid,
                            isPending = com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec
                                .documentId(review.workId, review.uid) in pendingReviewKeys,
                            onEdit = {
                                editingReview = review
                                reviewSaveError = null
                                showReviewForm = true
                            },
                            onDelete = { reviewToDelete = review },
                            onHideAuthor = { viewModel.hideAuthor(review.authorName) },
                            deleteFocusRequester = if (listenerProfile?.uid == review.uid) {
                                reviewDeleteFocusRequester
                            } else {
                                null
                            }
                        )
                    }

                    // Spec-40 #282 — the source's own visitors' comments: a
                    // plainly-labelled simple list UNDER our cards, never mixed
                    // into them (parsed texts have no author/date/rating and do
                    // not survive card form). Absent sources render nothing.
                    sourceProfiles
                        .filter { it.visitorComments.isNotEmpty() }
                        .forEach { profile ->
                            item(key = "visitor_comments_${profile.sourceId}") {
                                VisitorCommentsSubblock(profile)
                            }
                        }
                }            }
        }
    }

    if (showAddBookmarkDialog) {
        val currentChapterIndex = playerState.currentChapterIndex
        val currentChapterTitle = if (chapters.isNotEmpty() && currentChapterIndex in chapters.indices) {
            chapters[currentChapterIndex].title
        } else {
            stringResource(R.string.book_detail_chapter_fallback, currentChapterIndex + 1)
        }
        val timestampSeconds = playerState.currentPositionMs / 1000L
        val timestampLabel = MainViewModel.formatTime(timestampSeconds)
        val bookmarkSavedMessage = stringResource(
            R.string.book_detail_bookmark_saved,
            currentChapterTitle,
            timestampLabel
        )
        val bookmarkSaveError = stringResource(R.string.book_detail_bookmark_save_error)
        val defaultBookmarkNote = stringResource(
            R.string.book_detail_bookmark_default_note,
            timestampLabel
        )

        BookmarkDialog(
            timestampSeconds = timestampSeconds,
            chapterTitle = currentChapterTitle,
            onDismiss = { showAddBookmarkDialog = false },
            onSave = { note ->
                scope.launch {
                    runCatching {
                        listeningState.addBookmark(
                            BookmarkEntity(
                                bookId = currentBook.id,
                                chapterIndex = currentChapterIndex,
                                chapterTitle = currentChapterTitle,
                                timestampSeconds = timestampSeconds,
                                note = note.trim().ifBlank { defaultBookmarkNote }
                            )
                        )
                    }.onSuccess {
                        snackbarHostState.showSnackbar(bookmarkSavedMessage)
                    }.onFailure {
                        snackbarHostState.showSnackbar(bookmarkSaveError)
                    }
                }
            }
        )
    }

    RestoreFocusAfterModal(
        modalVisible = bookmarkToDelete != null,
        returnFocusRequester = bookmarkDeleteOrigin,
        fallbackFocusRequester = deleteTriggerFocusRequester,
        onFocusRestored = { bookmarkDeleteOrigin = null }
    )
    bookmarkToDelete?.let { doomed ->
        val deletedMessage = stringResource(R.string.book_detail_bookmark_deleted)
        val deleteError = stringResource(R.string.book_detail_bookmark_delete_error)
        BookmarkDeleteConfirmation(
            workTitle = currentBook.title,
            bookmark = doomed,
            onConfirm = {
                bookmarkToDelete = null
                scope.launch {
                    runCatching { listeningState.deleteBookmark(doomed.id) }
                        .onSuccess { snackbarHostState.showSnackbar(deletedMessage) }
                        .onFailure { snackbarHostState.showSnackbar(deleteError) }
                }
            },
            onDismiss = { bookmarkToDelete = null }
        )
    }

    // Three-level deletion (wayfinder #28): removing from the library must
    // never silently destroy the user's audio files. This owner also keeps
    // focus on the exact launcher across sheet -> confirmation transitions.
    BookDeleteModalLifecycle(
        workTitle = currentBook.title,
        isDownloaded = currentBook.isDownloaded || currentBook.downloadState == DownloadState.PAUSED,
        showOptions = showDeleteSheet,
        showConfirmation = showDeleteDialog,
        returnFocusRequester = deleteTriggerFocusRequester,
        onRemoveFromLibrary = { viewModel.removeFromLibrary(currentBook.id) },
        onDeleteDownloadedCopy = {
            scope.launch { offlineDownloads.removeOfflineDownload(currentBook.id) }
        },
        onConfirmDelete = { viewModel.deleteBook(currentBook.id) },
        onOptionsDismiss = { showDeleteSheet = false },
        onRequestConfirmation = { showDeleteDialog = true },
        onConfirmationDismiss = { showDeleteDialog = false }
    )

    // Spec-40 #277/#278 — the review write/edit form (one form for both
    // modes; edit prefills stars/body/tag and re-sets under the same key).
    if (showReviewForm && viewModel.listenerReviews != null) {
        ListenerReviewFormSheet(
            bookTitle = currentBook.title,
            editing = editingReview,
            editionOptions = workEditionNarrators,
            defaultEditionTag = currentBook.displayNarrator,
            isSaving = reviewSaveInProgress,
            errorMessage = reviewSaveError,
            onSave = { rating, body, tag ->
                reviewSaveError = null
                reviewSaveInProgress = true
                viewModel.saveReview(reviewsWorkId, rating, body, tag, editingReview)
            },
            onDismiss = {
                if (!reviewSaveInProgress) {
                    showReviewForm = false
                    editingReview = null
                    reviewSaveError = null
                }
            }
        )
    }

    // Spec-40 #277 — deleting a review is destructive: a confirmation that
    // quotes exactly what is removed (spec-27 «destructive never neutral»).
    ReviewDeleteConfirmationOwner(
        workTitle = currentBook.title,
        review = reviewToDelete,
        returnFocusRequester = reviewDeleteFocusRequester,
        fallbackFocusRequester = deleteTriggerFocusRequester,
        onConfirm = { doomed ->
            viewModel.deleteOwnReview(doomed.workId, doomed.uid)
            reviewToDelete = null
        },
        onDismiss = { reviewToDelete = null }
    )
}

internal fun reviewFailureNeedsSnackbar(formVisible: Boolean): Boolean = !formVisible

// #382: найдовший реальний лейбл кнопки — «Продовжити з HH:MM:SS»; такі лейбли
// не влазять в один рядок дій без розриву посередині слова («Продовж/ити»).
private const val PLAY_LABEL_ROW_LIMIT = 12

/**
 * Primary book actions reflow into a vertical stack at accessibility font
 * scale or when the play label alone is too long for one row (#382). The
 * visible and semantic controls are the same in both layouts;
 * nothing is hidden behind a TalkBack-only branch.
 */
@Composable
fun BookDetailPrimaryActions(
    workTitle: String,
    playLabel: String,
    streamOnly: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onAddBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val progressPercent = (downloadProgress.coerceIn(0f, 1f) * 100).toInt()
    val downloadAction = when {
        isDownloading -> stringResource(
            R.string.book_detail_download_in_progress,
            workTitle,
            progressPercent
        )
        isDownloaded -> stringResource(R.string.book_detail_download_remove, workTitle)
        else -> stringResource(R.string.book_detail_download_add, workTitle)
    }
    val downloadState = stringResource(
        when {
            isDownloading -> R.string.book_detail_downloading
            isDownloaded -> R.string.book_detail_downloaded
            else -> R.string.book_detail_streaming
        }
    )
    val playAction = stringResource(R.string.book_detail_play_action, playLabel, workTitle)
    val bookmarkAction = stringResource(R.string.book_detail_add_bookmark, workTitle)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // #382: довгий лейбл сам по собі привід складати дії вертикально —
        // у рядку «Продовжити з HH:MM:SS» слову бракує місця навіть із
        // обтягнутим contentPadding. Короткі («Слухати», «Пауза») далі живуть
        // в одному рядку.
        val playLabelIsLong = playLabel.length > PLAY_LABEL_ROW_LIMIT
        val stackActions = fontScale >= 1.5f || playLabelIsLong || maxWidth < 340.dp
        if (stackActions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BookDetailPlayButton(
                    label = playLabel,
                    actionDescription = playAction,
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!streamOnly) {
                    BookDetailDownloadButton(
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        actionDescription = downloadAction,
                        state = downloadState,
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                BookDetailBookmarkButton(
                    actionDescription = bookmarkAction,
                    onClick = onAddBookmark,
                    showLabel = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookDetailPlayButton(
                    label = playLabel,
                    actionDescription = playAction,
                    onClick = onPlay,
                    modifier = Modifier.weight(if (streamOnly) 1f else 1.2f)
                )
                if (!streamOnly) {
                    BookDetailDownloadButton(
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        actionDescription = downloadAction,
                        state = downloadState,
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    )
                }
                BookDetailBookmarkButton(
                    actionDescription = bookmarkAction,
                    onClick = onAddBookmark,
                    showLabel = false
                )
            }
        }
    }
}

@Composable
private fun BookDetailPlayButton(
    label: String,
    actionDescription: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        // #382: дефолтні 24dp по горизонталі плюс іконка з'їдали ширину слова
        // «Продовжити»; паддінг однаковий з download-кнопкою, підлога ширини
        // тримає найдовший лейбл на вузьких панелях.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = modifier
            .widthIn(min = 150.dp)
            .heightIn(min = 50.dp)
            .testTag("play_book_button")
            .semantics { contentDescription = actionDescription }
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookDetailDownloadButton(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    actionDescription: String,
    state: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !isDownloading,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDownloaded) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (isDownloaded) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurface
        ),
        // #382: спільний паддінг із play-кнопкою — жодних per-button налаштувань.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag("download_offline_button")
            .semantics {
                contentDescription = actionDescription
                stateDescription = state
            }
    ) {
        if (isDownloading) {
            CircularProgressIndicator(
                progress = { downloadProgress.coerceIn(0.05f, 0.95f) },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%")
        } else {
            Text(
                text = stringResource(
                    if (isDownloaded) R.string.book_detail_offline_short
                    else R.string.book_detail_download_short
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BookDetailBookmarkButton(
    actionDescription: String,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag("bookmark_button")
            .semantics { contentDescription = actionDescription }
    ) {
        Icon(
            imageVector = Icons.Default.BookmarkAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.book_detail_add_bookmark_short), maxLines = 2)
        }
    }
}

@Composable
private fun TagPill(
    text: String,
    color: Color,
    container: Color,
    border: androidx.compose.foundation.BorderStroke?
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        border = border
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * #40 decision 1 — the book page's favourite toggle: a filled heart when the
 * book is in «Улюблені», an outlined one otherwise. Public so the snapshot
 * seam can pin both states and the toggle from fixture data.
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    bookTitle: String = ""
) {
    val contextualTitle = bookTitle.takeIf(String::isNotBlank) ?: "книгу"
    val actionDescription = stringResource(
        if (isFavorite) R.string.book_detail_favorite_remove else R.string.book_detail_favorite_add,
        contextualTitle
    )
    val currentState = stringResource(
        if (isFavorite) R.string.book_detail_favorite_on else R.string.book_detail_favorite_off
    )
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .testTag("favorite_toggle_button")
            .semantics {
                contentDescription = actionDescription
                stateDescription = currentState
            }
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * #40 decision 1 — the book's series as a tappable pill on the book page:
 * "«Чаклун» • Кн. 2". Opens the series catalogue page (spec-9 T1
 * SeriesScreen), which holds the volume order and the next-unread-volume
 * CTA. Public (not private) so the snapshot seam can pin the pill's
 * presence and tap through it with fixture data.
 */
@Composable
fun SeriesPill(
    seriesTitle: String,
    seriesIndex: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .testTag("book_detail_series_pill")
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Text(
            text = if (seriesIndex > 0) "«$seriesTitle» • Кн. $seriesIndex" else "«$seriesTitle»",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Spec-25 (#171) — the book page's universe line under the series pill:
 * «Всесвіт: «Перший закон»». Renders only for a resolved universe — a
 * missing one never degrades the book page. Public (not private) so the
 * snapshot seam can pin the line with fixture data.
 */
@Composable
fun BookUniverseLine(universeName: String) {
    Text(
        text = "Всесвіт: «$universeName»",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("book_detail_universe_line")
    )
}

@Composable
fun ChapterRowItem(
    chapter: ChapterEntity,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    focusRequester: FocusRequester? = null,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit
) {
    val duration = chapter.durationSeconds.takeIf { it > 0L }?.let(MainViewModel::formatTime)
    val chapterSummary = if (duration != null) {
        stringResource(R.string.book_detail_chapter_summary, chapter.title, duration)
    } else {
        stringResource(R.string.book_detail_chapter_summary_unknown, chapter.title)
    }
    val chapterState = stringResource(
        when {
            isPlaying -> R.string.book_detail_chapter_playing
            isCurrent -> R.string.book_detail_chapter_paused
            else -> R.string.book_detail_chapter_not_current
        }
    )
    val actionLabel = stringResource(
        when {
            isPlaying -> R.string.book_detail_chapter_pause
            isCurrent -> R.string.book_detail_chapter_resume
            else -> R.string.book_detail_chapter_play
        },
        chapter.title
    )
    val onAction = if (isPlaying) onPauseClick else onPlayClick
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .border(
                1.dp,
                if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(AppDimens.RadiusCard)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // One stable node owns touch, semantics and focus. Splitting
                // these responsibilities between Card and Row exposes two
                // clickable accessibility nodes with identical bounds.
                .testTag("book_detail_chapter_${chapter.id}")
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .clickable(onClickLabel = actionLabel, onClick = onAction)
                .semantics(mergeDescendants = true) {
                    contentDescription = chapterSummary
                    stateDescription = chapterState
                    selected = isCurrent
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isCurrent) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapter.durationSeconds > 0L) {
                    // Spec-27 (#204): the duration renders without the
                    // «Duration:» label (US-15, P1 #8).
                    Text(
                        text = MainViewModel.formatTime(chapter.durationSeconds),
                        // Spec-22 T2: tabular figures for duration counters.
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun BookmarkRowItem(
    bookmark: BookmarkEntity,
    workTitle: String,
    onJumpClick: () -> Unit,
    onDeleteClick: () -> Unit,
    deleteFocusRequester: FocusRequester? = null
) {
    val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
    val jumpLabel = stringResource(
        R.string.book_detail_bookmark_jump,
        workTitle,
        bookmark.chapterTitle,
        timestamp
    )
    val deleteLabel = stringResource(
        R.string.book_detail_bookmark_delete,
        workTitle,
        bookmark.chapterTitle,
        timestamp
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCard)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "На ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onJumpClick,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = jumpLabel,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .then(
                        if (deleteFocusRequester != null) {
                            Modifier.focusRequester(deleteFocusRequester)
                        } else Modifier
                    )
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = deleteLabel,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun BookDeleteModalLifecycle(
    workTitle: String,
    isDownloaded: Boolean,
    showOptions: Boolean,
    showConfirmation: Boolean,
    returnFocusRequester: FocusRequester,
    onRemoveFromLibrary: () -> Unit,
    onDeleteDownloadedCopy: () -> Unit,
    onConfirmDelete: () -> Unit,
    onOptionsDismiss: () -> Unit,
    onRequestConfirmation: () -> Unit,
    onConfirmationDismiss: () -> Unit
) {
    RestoreFocusAfterModal(
        modalVisible = showOptions || showConfirmation,
        returnFocusRequester = returnFocusRequester
    )

    if (showOptions) {
        BookDeleteOptionsSheet(
            workTitle = workTitle,
            isDownloaded = isDownloaded,
            onRemoveFromLibrary = {
                onRemoveFromLibrary()
                onOptionsDismiss()
            },
            onDeleteDownloadedCopy = {
                onDeleteDownloadedCopy()
                onOptionsDismiss()
            },
            onDeleteEverything = {
                onOptionsDismiss()
                onRequestConfirmation()
            },
            onDismiss = onOptionsDismiss
        )
    }

    if (showConfirmation) {
        BookDeleteConfirmationDialog(
            workTitle = workTitle,
            onConfirm = {
                onConfirmDelete()
                onConfirmationDismiss()
            },
            onDismiss = onConfirmationDismiss
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BookDeleteOptionsSheet(
    workTitle: String,
    isDownloaded: Boolean,
    onRemoveFromLibrary: () -> Unit,
    onDeleteDownloadedCopy: () -> Unit,
    onDeleteEverything: () -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(
                stringResource(R.string.a11y_book_detail_delete_options_pane, workTitle)
            )
            .testTag("book_detail_delete_options_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_options_title, workTitle),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_delete_options_heading")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_options_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            BookDeleteOption(
                title = stringResource(R.string.a11y_book_detail_remove_library, workTitle),
                consequence = stringResource(R.string.a11y_book_detail_remove_library_consequence),
                icon = Icons.Default.RemoveCircleOutline,
                color = MaterialTheme.colorScheme.primary,
                testTag = "delete_remove_from_library",
                onClick = onRemoveFromLibrary
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (isDownloaded) {
                BookDeleteOption(
                    title = stringResource(R.string.a11y_book_detail_delete_download, workTitle),
                    consequence = stringResource(R.string.a11y_book_detail_delete_download_consequence),
                    icon = Icons.Default.CloudOff,
                    color = MaterialTheme.colorScheme.onSurface,
                    testTag = "delete_downloaded_copy",
                    onClick = onDeleteDownloadedCopy
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            BookDeleteOption(
                title = stringResource(R.string.a11y_book_detail_delete_everything, workTitle),
                consequence = stringResource(R.string.a11y_book_detail_delete_everything_consequence),
                icon = Icons.Default.Delete,
                color = MaterialTheme.colorScheme.error,
                testTag = "delete_book_and_files",
                onClick = onDeleteEverything
            )
        }
    }
}

@Composable
private fun BookDeleteOption(
    title: String,
    consequence: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = consequence,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
fun BookDeleteConfirmationDialog(
    workTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        // MD3: dialog = surfaceContainerHigh (highest tonal step of a
        // raised container, below text fields).
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .accessibilityPane(
                stringResource(R.string.a11y_book_detail_delete_confirm_pane, workTitle)
            )
            .testTag("book_detail_delete_confirm_dialog"),
        title = {
            LaunchedEffect(headingFocusRequester) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_confirm_title, workTitle),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_delete_confirm_heading")
            )
        },
        text = {
            Text(
                text = stringResource(R.string.a11y_book_detail_delete_confirm_consequence, workTitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("book_detail_delete_confirm")
            ) {
                Text(
                    stringResource(R.string.book_detail_bookmark_delete_confirm),
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.book_detail_cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

@Composable
fun BookmarkDeleteConfirmation(
    workTitle: String,
    bookmark: BookmarkEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
    val headingFocusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(stringResource(R.string.book_detail_bookmark_delete_pane))
            .testTag("book_detail_bookmark_delete_dialog"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            LaunchedEffect(headingFocusRequester) {
                withFrameNanos { }
                headingFocusRequester.requestFocus()
            }
            Text(
                text = stringResource(R.string.book_detail_bookmark_delete_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("book_detail_bookmark_delete_heading")
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.book_detail_bookmark_delete_question,
                        workTitle,
                        bookmark.chapterTitle,
                        timestamp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (bookmark.note.isBlank()) {
                        stringResource(R.string.book_detail_bookmark_delete_consequence_no_note)
                    } else {
                        stringResource(
                            R.string.book_detail_bookmark_delete_consequence,
                            bookmark.note
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("book_detail_bookmark_delete_confirm")
            ) {
                Text(
                    stringResource(R.string.book_detail_bookmark_delete_confirm),
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.book_detail_cancel),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

/**
 * The page's one Work/Edition identity block. The visible fallback cover
 * repeats the same title by design, so it is explicitly decorative here and
 * the heading below remains the single accessible owner of the Work name.
 */
@Composable
fun BookDetailIdentityHeader(
    book: AudiobookEntity,
    presentation: BookDetailPresentation,
    universeName: String? = null,
    narrationAverage: Double? = null,
    narrationVoteCount: Int = 0,
    ownNarrationRating: Int? = null,
    canRateNarration: Boolean = false,
    onRateNarration: (Int) -> Unit = {},
    onDeleteNarrationRating: (() -> Unit)? = null,
    narrationRatingDeleteFocusRequester: FocusRequester? = null,
    onAuthorClick: (String) -> Unit = {},
    onNarratorClick: (String) -> Unit = {},
    onSeriesClick: (String, String) -> Unit = { _, _ -> },
    onWrongUniverse: () -> Unit = {},
    requestInitialFocus: Boolean = true,
    onInitialFocusHandled: () -> Unit = {},
    returnFocusOrigin: BookDetailLinkOrigin? = null,
    onChildRouteOpened: (BookDetailLinkOrigin) -> Unit = {},
    onReturnFocusRestored: (BookDetailLinkOrigin) -> Unit = {},
    authorBookmark: PersonBookmarkControl = PersonBookmarkControl(),
    narratorBookmark: PersonBookmarkControl = PersonBookmarkControl()
) {
    Card(
        modifier = Modifier
            .width(180.dp)
            .height(240.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusHero))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                RoundedCornerShape(AppDimens.RadiusHero)
            ),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        BookCoverImage(
            book = book,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    BookDetailCanonicalSummary(
        presentation = presentation,
        entryFocusKey = book.id,
        universeName = universeName,
        narrationAverage = narrationAverage,
        narrationVoteCount = narrationVoteCount,
        ownNarrationRating = ownNarrationRating,
        canRateNarration = canRateNarration,
        onRateNarration = onRateNarration,
        onDeleteNarrationRating = onDeleteNarrationRating,
        narrationRatingDeleteFocusRequester = narrationRatingDeleteFocusRequester,
        onAuthorClick = onAuthorClick,
        onNarratorClick = onNarratorClick,
        onSeriesClick = onSeriesClick,
        onWrongUniverse = onWrongUniverse,
        requestInitialFocus = requestInitialFocus,
        onInitialFocusHandled = onInitialFocusHandled,
        returnFocusOrigin = returnFocusOrigin,
        onChildRouteOpened = onChildRouteOpened,
        onReturnFocusRestored = onReturnFocusRestored,
        authorBookmark = authorBookmark,
        narratorBookmark = narratorBookmark
    )
}

/** The exact Book Detail control that launched a pushed child destination. */
enum class BookDetailLinkOrigin {
    AUTHOR,
    NARRATOR,
    SERIES
}

data class PersonBookmarkControl(
    val isBookmarked: Boolean = false,
    val notifyEnabled: Boolean = true,
    val onToggle: () -> Unit = {},
    val onToggleNotify: (Boolean) -> Unit = {}
)

/** The production Work/Edition summary consumed by both the page and snapshots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailCanonicalSummary(
    presentation: BookDetailPresentation,
    entryFocusKey: Any = presentation.title,
    universeName: String? = null,
    narrationAverage: Double? = null,
    narrationVoteCount: Int = 0,
    ownNarrationRating: Int? = null,
    canRateNarration: Boolean = false,
    onRateNarration: (Int) -> Unit = {},
    onDeleteNarrationRating: (() -> Unit)? = null,
    narrationRatingDeleteFocusRequester: FocusRequester? = null,
    onAuthorClick: (String) -> Unit = {},
    onNarratorClick: (String) -> Unit = {},
    onSeriesClick: (String, String) -> Unit = { _, _ -> },
    onWrongUniverse: () -> Unit = {},
    requestInitialFocus: Boolean = true,
    onInitialFocusHandled: () -> Unit = {},
    returnFocusOrigin: BookDetailLinkOrigin? = null,
    onChildRouteOpened: (BookDetailLinkOrigin) -> Unit = {},
    onReturnFocusRestored: (BookDetailLinkOrigin) -> Unit = {},
    authorBookmark: PersonBookmarkControl = PersonBookmarkControl(),
    narratorBookmark: PersonBookmarkControl = PersonBookmarkControl()
) {
    val currentEditionState = stringResource(R.string.book_detail_current_edition)
    val titleFocusRequester = remember(entryFocusKey) { FocusRequester() }
    val authorFocusRequester = remember { FocusRequester() }
    val narratorFocusRequester = remember { FocusRequester() }
    val seriesFocusRequester = remember { FocusRequester() }
    LaunchedEffect(entryFocusKey, requestInitialFocus, returnFocusOrigin) {
        if (requestInitialFocus && returnFocusOrigin == null) {
            withFrameNanos { }
            val focused = titleFocusRequester.requestFocus()
            if (focused) onInitialFocusHandled()
        }
    }
    LaunchedEffect(returnFocusOrigin) {
        val origin = returnFocusOrigin ?: return@LaunchedEffect
        withFrameNanos { }
        val restored = when (origin) {
            BookDetailLinkOrigin.AUTHOR -> authorFocusRequester.requestFocus()
            BookDetailLinkOrigin.NARRATOR -> narratorFocusRequester.requestFocus()
            BookDetailLinkOrigin.SERIES -> seriesFocusRequester.requestFocus()
        }
        if (restored) onReturnFocusRestored(origin)
    }
    Text(
        text = presentation.title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag("book_detail_title")
            .focusRequester(titleFocusRequester)
            .focusable()
            .semantics { heading() }
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (presentation.author.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Автор: ${presentation.author}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("book_detail_author_link")
                    .defaultMinSize(minHeight = 48.dp)
                    .focusRequester(authorFocusRequester)
                    .clickable {
                        onChildRouteOpened(BookDetailLinkOrigin.AUTHOR)
                        onAuthorClick(presentation.author)
                    }
            )
            PersonBookmarkButton(
                isBookmarked = authorBookmark.isBookmarked,
                notifyEnabled = authorBookmark.notifyEnabled,
                personName = presentation.author,
                onToggle = authorBookmark.onToggle,
                onToggleNotify = authorBookmark.onToggleNotify,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
    if (presentation.narrator.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Озвучує: ${presentation.narrator}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .focusRequester(narratorFocusRequester)
                    .clickable {
                        onChildRouteOpened(BookDetailLinkOrigin.NARRATOR)
                        onNarratorClick(presentation.narrator)
                    }
                    .semantics { stateDescription = currentEditionState }
                    .testTag("book_detail_narrator_link")
            )
            PersonBookmarkButton(
                isBookmarked = narratorBookmark.isBookmarked,
                notifyEnabled = narratorBookmark.notifyEnabled,
                personName = presentation.narrator,
                onToggle = narratorBookmark.onToggle,
                onToggleNotify = narratorBookmark.onToggleNotify,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
    // ADR-0023 (#348): the narration rating lives beside the narrator's name —
    // crowd average + this listener's stars, never in the book headline.
    NarrationRatingRow(
        average = narrationAverage,
        voteCount = narrationVoteCount,
        ownRating = ownNarrationRating,
        canRate = canRateNarration,
        onRate = onRateNarration,
        onDeleteOwn = onDeleteNarrationRating,
        deleteFocusRequester = narrationRatingDeleteFocusRequester,
        modifier = Modifier.padding(top = 2.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (presentation.genre.isNotBlank() && !presentation.genre.contains("4read", ignoreCase = true)) {
            TagPill(
                text = presentation.genre,
                color = MaterialTheme.colorScheme.onSurface,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
        val chaptersKnown = presentation.totalChapters > 0
        val durationKnown = presentation.totalDurationSeconds > 0L
        if (chaptersKnown || durationKnown) {
            val chaptersLabel = ukPlural(
                presentation.totalChapters,
                "розділ", "розділи", "розділів"
            )
            TagPill(
                text = when {
                    chaptersKnown && durationKnown ->
                        "${presentation.totalChapters} $chaptersLabel • ${MainViewModel.formatTime(presentation.totalDurationSeconds)}"
                    chaptersKnown -> "${presentation.totalChapters} $chaptersLabel"
                    else -> MainViewModel.formatTime(presentation.totalDurationSeconds)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
        val seriesTitle = presentation.seriesTitle.orEmpty()
        if (seriesTitle.isNotBlank()) {
            SeriesPill(
                seriesTitle = seriesTitle,
                seriesIndex = presentation.seriesIndex ?: 0,
                modifier = Modifier.focusRequester(seriesFocusRequester),
                onClick = {
                    presentation.seriesUrl?.takeIf(String::isNotBlank)?.let { url ->
                        onChildRouteOpened(BookDetailLinkOrigin.SERIES)
                        onSeriesClick(seriesTitle, url)
                    }
                }
            )
        }
        universeName?.let { name ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookUniverseLine(name)
                TextButton(
                    onClick = onWrongUniverse,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("book_detail_report_wrong_universe")
                ) {
                    Text("Всесвіт неправильний?", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun BookDetailDescription(presentation: BookDetailPresentation) {
    if (presentation.description.isNotBlank()) {
        Text(
            text = presentation.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun BookDetailSourceSection(
    presentation: BookDetailPresentation
) {
    if (presentation.sources.isEmpty()) return
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = presentation.sourceHeading,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .semantics { heading() }
    )
    Spacer(modifier = Modifier.height(8.dp))
    presentation.sources.forEach { source ->
        WorkSourceRowCard(
            source = source,
            workTitle = presentation.title
        )
    }
}

/**
 * Spec-23 T5/#426 — one informational row of the book page's «Джерела»
 * section: a source that carries the Work, with its stream-only marker
 * («Тільки стрімінг») and browser requirement.
 * [isCurrent] marks the source the library row itself came from. Pure
 * `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkSourceRowCard(
    source: BookDetailSourcePresentation,
    workTitle: String = ""
) {
    val contextualTitle = workTitle.takeIf(String::isNotBlank) ?: "книгу"
    val actionDescription = if (source.selectable) {
        stringResource(R.string.book_detail_play_source, contextualTitle, source.name)
    } else {
        stringResource(R.string.book_detail_source_summary, source.name, contextualTitle)
    }
    val sourceState = stringResource(
        if (source.isCurrent) R.string.book_detail_current_source
        else R.string.book_detail_other_source
    ).let { base ->
        buildString {
            append(if (source.streamOnly) stringResource(R.string.book_detail_source_stream_only, base) else base)
            if (SourceAccessPolicy.modeFor(source.sourceId) == SourceAccessMode.BROWSER) {
                append(" · ${stringResource(R.string.book_detail_browser_needed)}")
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = if (source.isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (source.isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("work_source_${source.sourceId}")
            .semantics(mergeDescendants = true) {
                contentDescription = actionDescription
                stateDescription = sourceState
                selected = source.isCurrent
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = if (source.selectable) Icons.Default.PlayArrow else Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (source.isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceBadgePill(label = "Поточна")
                    }
                }
                source.rating?.let { rating ->
                    Text(
                        text = "Оцінка джерела: ★ ${"%.1f".format(java.util.Locale.US, rating)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                source.differingNarrator?.let { narrator ->
                    Text(
                        text = "Озвучує за даними джерела: $narrator",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (source.differingGenres.isNotEmpty()) {
                    Text(
                        text = "Жанри за даними джерела: ${source.differingGenres.joinToString(" · ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                source.differingDescription?.let { description ->
                    Text(
                        text = "Інший опис від джерела: $description",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (source.streamOnly) {
                    Text(
                        text = "Тільки стрімінг",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * ADR-0011 — one row of the book page's «Інші начитки» section: another
 * rendition card of the same Work. The narrator is the rendition identity
 * (ADR-0010); the row shows it plus the source the card came from, and
 * tapping it opens that card — the narration selection. Pure `@Composable`.
 */
@Composable
fun NarrationRowCard(
    sibling: com.slukhayka.audiobooks.data.db.AudiobookEntity,
    average: Double? = null,
    voteCount: Int = 0,
    onClick: () -> Unit
) {
    val narrator = sibling.narrator.ifBlank { "Невідомий читач" }
    val sourceName = sourceDisplayName(sourceIdForUrl(sibling.sourceUrl))
    val actionDescription = stringResource(
        R.string.book_detail_open_edition,
        narrator,
        sibling.title,
        sourceName
    )
    val otherEditionState = stringResource(R.string.book_detail_other_edition)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("narration_${sibling.id}")
            .semantics(mergeDescendants = true) {
                contentDescription = actionDescription
                stateDescription = otherEditionState
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = narrator,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ADR-0023 (#357): this rendition's own average — shown only when
            // votes exist (honest absence, ADR-0014).
            if (average != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    ReviewStarsRow(rating = kotlin.math.round(average).toInt(), starSize = 12)
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", average) + " · $voteCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("narration_rating_average_${sibling.id}")
                    )
                }
            }
        }
    }
}
