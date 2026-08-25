package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import com.slukhayka.audiobooks.ui.library.siblingNarrations
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
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
    onBackClick: () -> Unit
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
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Spec-40 #277/#278/#280 — «Відгуки»: form open/edit state, delete
    // confirmation target. The store itself rides MainViewModel (null
    // without Firebase keys → no block rendered).
    var showReviewForm by remember { mutableStateOf(false) }
    var editingReview by remember { mutableStateOf<com.slukhayka.audiobooks.data.reviews.ListenerReview?>(null) }
    var reviewToDelete by remember { mutableStateOf<com.slukhayka.audiobooks.data.reviews.ListenerReview?>(null) }

    val currentBook = book ?: return
    val isDownloadingThis = downloadingBookId == currentBook.id
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

    // Spec-40 #277 — reviews anchor to the WORK (shared across narrations);
    // a row without a Works identity anchors to itself.
    val reviewsWorkId = currentBook.workId?.takeIf { it.isNotBlank() } ?: currentBook.id

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
    var narrationRatingToDelete by remember { mutableStateOf(false) }
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

    // #358 — delete confirmation quoting the exact scope (spec-27).
    if (narrationRatingToDelete && currentEditionId != null) {
        val editionId = currentEditionId.orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { narrationRatingToDelete = false },
            title = { Text("Видалити оцінку начитки?") },
            text = {
                Text(
                    "Ваші зірки біля «${detailPresentation.narrator}» буде прибрано з цієї сторінки."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOwnNarrationRating(reviewsWorkId, editionId)
                    narrationRatingToDelete = false
                }) {
                    Text("Видалити", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { narrationRatingToDelete = false }) { Text("Скасувати") }
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
    LaunchedEffect(downloadMessage) {
        downloadMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeDownloadMessage()
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

    Scaffold(
        topBar = {
            // The host Scaffold in MainActivity already consumed the status
            // bar (innerPadding.top), so this inner TopAppBar must NOT add
            // statusBarsPadding again or the header sits a full status-bar
            // height too low.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(detailPresentation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // #40 decision 1: the favourite toggle lives on the book
                    // page itself — the place where the user decides a book is
                    // worth keeping at hand.
                    val isFavoriteThis = favoriteBooks.any { it.id == currentBook.id }
                    FavoriteButton(
                        isFavorite = isFavoriteThis,
                        onToggle = {
                            scope.launch {
                                viewModel.libraryEntries.toggleFavorite(
                                    currentBook.id,
                                    !isFavoriteThis
                                )
                            }
                        }
                    )
                    // Spec #8 ticket T4: the WebView is no longer a tab — a
                    // book page keeps an "open on site" escape hatch instead.
                    if (currentBook.sourceUrl.contains("4read.org")) {
                        IconButton(onClick = { viewModel.openWebFallback(currentBook.sourceUrl) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Відкрити на сайті",
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
                                contentDescription = "Відкрити на Sluhay",
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
                            enabled = !isDownloadingThis
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
                                    contentDescription = "Offline Download",
                                    tint = if (currentBook.isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    // Wayfinder #28: deletion is a choice — remove from library,
                    // delete the downloaded copy, or delete everything.
                    IconButton(onClick = { showDeleteSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Видалити книгу",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .height(240.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusHero))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(AppDimens.RadiusHero)),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        com.slukhayka.audiobooks.ui.components.BookCoverImage(
                            book = currentBook,
                            contentDescription = detailPresentation.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    BookDetailCanonicalSummary(
                        presentation = detailPresentation,
                        universeName = bookUniverse?.universeName,
                        narrationAverage = narrationAverage,
                        narrationVoteCount = editionNarrationRatings.size,
                        ownNarrationRating = ownNarrationRating?.rating,
                        canRateNarration = viewModel.narrationRatingsStore != null &&
                            listenerProfile != null && currentEditionId != null,
                        onRateNarration = { stars ->
                            val editionId = currentEditionId ?: return@BookDetailCanonicalSummary
                            viewModel.saveNarrationRating(
                                workId = reviewsWorkId,
                                editionId = editionId,
                                rating = stars,
                                editingCreatedAt = ownNarrationRating?.createdAt
                            )
                        },
                        onDeleteNarrationRating = ownNarrationRating?.let {
                            { narrationRatingToDelete = true }
                        },
                        onAuthorClick = { author ->
                            viewModel.openPersonBooks(
                                CatalogPerson(author, bookPersonPath("avtor", author), 0)
                            )
                        },
                        onNarratorClick = { narrator ->
                            viewModel.openPersonBooks(
                                CatalogPerson(narrator, bookPersonPath("chitaet", narrator), 0)
                            )
                        },
                        onSeriesClick = { title, url -> viewModel.openSeries(title, url) },
                        onWrongUniverse = { viewModel.reportWrongUniverse(currentBook.id) }
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
                            modifier = Modifier.padding(horizontal = 8.dp)
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

                    // Spec-23 T5: «Джерела» — every Edition carrying the Work
                    // (source name + stream-only marker), from the persisted
                    // `editions` rows. Tapping one plays that variant through
                    // the existing per-source policy (incl. Referer/UA). The
                    // current book's own source is marked.
                    BookDetailSourceSection(detailPresentation) { source ->
                        viewModel.playFromSource(source.sourceId, source.url)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                // #40 decision 1: a finished book asks to
                                // restart from the top (RELISTEN); a PLAYING
                                // book toggles to pause — the one control that
                                // stops audio without leaving the page (the
                                // old code re-played the book, so the button
                                // could never pause: 2026-08-17 bug report);
                                // everything else starts or resumes.
                                when (playState) {
                                    BookPlayState.Playing -> viewModel.playerManager.pause()
                                    BookPlayState.Finished -> viewModel.relistenBook(currentBook)
                                    else -> viewModel.playAudiobook(currentBook)
                                }
                                // Pausing stays on the page; starting/resuming
                                // opens the full player as before.
                                if (playState !is BookPlayState.Playing) {
                                    viewModel.setShowFullPlayer(true)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(AppDimens.RadiusPanel),
                            modifier = Modifier
                                // Spec-10 T6: for a stream-only book the Play
                                // button takes the full row (no download twin).
                                .then(if (streamOnly) Modifier.fillMaxWidth() else Modifier.weight(1.2f))
                                .height(50.dp)
                                .testTag("play_book_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = bookPlayLabel(playState) { MainViewModel.formatTime(it) },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!streamOnly) {
                            OutlinedButton(
                            onClick = {
                                if (currentBook.isDownloaded) {
                                    scope.launch { offlineDownloads.removeOfflineDownload(currentBook.id) }
                                } else {
                                    viewModel.downloadBookOffline(currentBook.id)
                                }
                            },
                            enabled = !isDownloadingThis,
                            shape = RoundedCornerShape(AppDimens.RadiusPanel),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (currentBook.isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (currentBook.isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("download_offline_button")
                        ) {
                            if (isDownloadingThis) {
                                // Live progress: the repository writes
                                // downloadProgress to the observed book row,
                                // so this recomposes as chapters complete.
                                CircularProgressIndicator(
                                    progress = { currentBook.downloadProgress.coerceIn(0.05f, 0.95f) },
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${(currentBook.downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    // Spec-27 (#204): Ukrainian labels —
                                    // «Офлайн» / «Завантажити» (US-15).
                                    text = if (currentBook.isDownloaded) "Офлайн" else "Завантажити",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (currentBook.isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        }

                        OutlinedButton(
                            onClick = { showAddBookmarkDialog = true },
                            shape = RoundedCornerShape(AppDimens.RadiusPanel),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("bookmark_button")
                        ) {
                            Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
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
                    val isPlayingThis = playerState.currentBook?.id == currentBook.id &&
                            playerState.currentChapterIndex == index

                    ChapterRowItem(
                        chapter = chapter,
                        index = index,
                        isPlaying = isPlayingThis,
                        onPlayClick = {
                            viewModel.playAudiobook(currentBook, chapterIndex = index)
                            viewModel.setShowFullPlayer(true)
                        }
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
                                text = "No bookmarks added yet for this book.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRowItem(
                            bookmark = bookmark,
                            onJumpClick = { viewModel.jumpToBookmark(bookmark) },
                            onDeleteClick = { scope.launch { listeningState.deleteBookmark(bookmark.id) } }
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
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // Writing needs a listener identity — until lane-a's
                        // seam answers, the block stays honestly read-only.
                        if (listenerProfile != null) {
                            Button(
                                onClick = {
                                    editingReview = null
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
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
                            isOwn = listenerProfile?.uid == review.uid,
                            isPending = com.slukhayka.audiobooks.data.reviews.ListenerReviewCodec
                                .documentId(review.workId, review.uid) in pendingReviewKeys,
                            onEdit = {
                                editingReview = review
                                showReviewForm = true
                            },
                            onDelete = { reviewToDelete = review },
                            onHideAuthor = { viewModel.hideAuthor(review.authorName) }
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
        val currentChapterTitle = if (chapters.isNotEmpty() && playerState.currentChapterIndex in chapters.indices) {
            chapters[playerState.currentChapterIndex].title
        } else "Chapter 1"

        BookmarkDialog(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { showAddBookmarkDialog = false },
            onSave = { note ->
                viewModel.addBookmarkAtCurrentPosition(note)
            }
        )
    }

    if (showDeleteSheet) {
        // Three-level deletion (wayfinder #28): removing from the library must
        // never silently destroy the user's audio files.
        ModalBottomSheet(
            onDismissRequest = { showDeleteSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Видалити книгу",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Оберіть, що саме видалити",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.removeFromLibrary(currentBook.id)
                            showDeleteSheet = false
                        }
                        .padding(vertical = 12.dp)
                        .testTag("delete_remove_from_library"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Прибрати з медіатеки", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Книга зникне зі списку, файли на пристрої лишаться", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (currentBook.isDownloaded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    offlineDownloads.removeOfflineDownload(currentBook.id)
                                }
                                showDeleteSheet = false
                            }
                            .padding(vertical = 12.dp)
                            .testTag("delete_downloaded_copy"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Видалити завантажену копію", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Лишиться в медіатеці, але без офлайн-копії", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDeleteSheet = false
                            showDeleteDialog = true
                        }
                        .padding(vertical = 12.dp)
                        .testTag("delete_book_and_files"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Видалити книгу та файли з пристрою", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text("Повністю видалить книгу й аудіофайли. Дію не можна скасувати.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            // MD3: dialog = surfaceContainerHigh (highest tonal step of a
            // raised container, below text fields).
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Видалити книгу?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Це видалить \"${currentBook.title}\" разом із главами, закладками, прогресом і завантаженими файлами. Дію не можна скасувати.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBook(currentBook.id)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    // MD3 tonal pairing: onError text on the error container.
                    Text("Видалити", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Скасувати", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    // Spec-40 #277/#278 — the review write/edit form (one form for both
    // modes; edit prefills stars/body/tag and re-sets under the same key).
    if (showReviewForm && viewModel.listenerReviews != null) {
        ListenerReviewFormSheet(
            bookTitle = currentBook.title,
            editing = editingReview,
            editionOptions = workEditionNarrators,
            defaultEditionTag = currentBook.displayNarrator,
            onSave = { rating, body, tag ->
                viewModel.saveReview(reviewsWorkId, rating, body, tag, editingReview)
                showReviewForm = false
                editingReview = null
            },
            onDismiss = {
                showReviewForm = false
                editingReview = null
            }
        )
    }

    // Spec-40 #277 — deleting a review is destructive: a confirmation that
    // quotes exactly what is removed (spec-27 «destructive never neutral»).
    reviewToDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { reviewToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Видалити відгук?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Буде видалено ваш відгук про \"${currentBook.title}\" — оцінка ${doomed.rating}★" +
                        (doomed.body?.takeIf { it.isNotBlank() }?.let { " і текст відгуку" } ?: "") +
                        ". Дію не можна скасувати.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteOwnReview(doomed.workId, doomed.uid)
                        reviewToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Видалити", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewToDelete = null }) {
                    Text("Скасувати", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
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
    onToggle: () -> Unit
) {
    IconButton(onClick = onToggle, modifier = Modifier.testTag("favorite_toggle_button")) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) "Прибрати з улюблених" else "Додати в улюблені",
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
        modifier = Modifier.testTag("book_detail_series_pill")
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
    isPlaying: Boolean,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .border(
                1.dp,
                if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(AppDimens.RadiusCard)
            )
            // Test seam (GitHub issue #7 — emulator audio scenario): deterministic
            // compose-test selector for the chapter row in BookDetailScreen.
            // Tags by chapter entity id so the emulator scenario can target a
            // specific chapter regardless of ordering. Pure UI annotation; does
            // not change runtime behaviour.
            .testTag("book_detail_chapter_${chapter.id}")
            .clickable { onPlayClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isPlaying) {
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
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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

            IconButton(onClick = onPlayClick) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = if (isPlaying) "Пауза розділ" else "Відтворити розділ",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun BookmarkRowItem(
    bookmark: BookmarkEntity,
    onJumpClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
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

            IconButton(onClick = onJumpClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Jump to bookmark",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete bookmark",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** The production Work/Edition summary consumed by both the page and snapshots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailCanonicalSummary(
    presentation: BookDetailPresentation,
    universeName: String? = null,
    narrationAverage: Double? = null,
    narrationVoteCount: Int = 0,
    ownNarrationRating: Int? = null,
    canRateNarration: Boolean = false,
    onRateNarration: (Int) -> Unit = {},
    onDeleteNarrationRating: (() -> Unit)? = null,
    onAuthorClick: (String) -> Unit = {},
    onNarratorClick: (String) -> Unit = {},
    onSeriesClick: (String, String) -> Unit = { _, _ -> },
    onWrongUniverse: () -> Unit = {}
) {
    Text(
        text = presentation.title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("book_detail_title")
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (presentation.author.isNotBlank()) {
        Text(
            text = "Автор: ${presentation.author}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .testTag("book_detail_author_link")
                .clickable { onAuthorClick(presentation.author) }
        )
    }
    if (presentation.narrator.isNotBlank()) {
        Text(
            text = "Озвучує: ${presentation.narrator}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNarratorClick(presentation.narrator) }
                .testTag("book_detail_narrator_link"),
            textAlign = TextAlign.Center
        )
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
                onClick = {
                    presentation.seriesUrl?.takeIf(String::isNotBlank)?.let { url ->
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
    presentation: BookDetailPresentation,
    onSourceClick: (BookDetailSourcePresentation) -> Unit = {}
) {
    if (presentation.sources.isEmpty()) return
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = presentation.sourceHeading,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
    Spacer(modifier = Modifier.height(8.dp))
    presentation.sources.forEach { source ->
        WorkSourceRowCard(source = source, onClick = { onSourceClick(source) })
    }
}

/**
 * Spec-23 T5 — one row of the book page's «Джерела» section: a source that
 * carries the Work, with its stream-only marker («Тільки стрімінг»). Tapping
 * plays that source's variant through the existing per-source policy.
 * [isCurrent] marks the source the library row itself came from. Pure
 * `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkSourceRowCard(
    source: BookDetailSourcePresentation,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = if (source.isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (source.isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("work_source_${source.sourceId}")
            .then(if (source.selectable) Modifier.clickable(onClick = onClick) else Modifier)
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("narration_${sibling.id}")
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
                    text = sibling.narrator.ifBlank { "Невідомий читач" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sourceDisplayName(sourceIdForUrl(sibling.sourceUrl)),
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
