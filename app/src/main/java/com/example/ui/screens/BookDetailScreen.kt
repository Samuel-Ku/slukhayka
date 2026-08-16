package com.example.ui.screens

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
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogPerson
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ChapterEntity
import com.example.data.downloads.OfflineDownloads
import com.example.data.entries.LibraryEntries
import com.example.data.listening.ListeningStateStore
import com.example.data.source.sourceDisplayName
import com.example.data.source.sourceIdForUrl
import com.example.data.source.streamOnlyFor
import com.example.ui.library.siblingNarrations
import com.example.ui.MainViewModel
import com.example.ui.bookPersonPath
import com.example.ui.components.BookmarkDialog
import com.example.ui.displayAuthor
import com.example.ui.displayNarrator
import com.example.ui.library.BookPlayState
import com.example.ui.library.bookPlayLabel
import com.example.ui.library.bookPlayState
import com.example.ui.library.bookPositionAndTotal
import com.example.ui.theme.*

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
    val isSourceProfilesLoading by viewModel.isSourceProfilesLoading.collectAsState()
    // Spec-23 T5: every Edition carrying the Work — the «Джерела» section.
    val bookSources by viewModel.bookSources.collectAsState()

    // #40 decision 1: the favourite toggle lives on the book page itself.
    val favoriteBooks by viewModel.libraryEntries.getFavoriteAudiobooks()
        .collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var showDeleteSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val currentBook = book ?: return
    val isDownloadingThis = downloadingBookId == currentBook.id
    // ADR-0011: the other rendition cards of this Work — the «Інші начитки»
    // block. Cold flow collected once per composition; the pure filter is
    // JVM-tested (siblingNarrations).
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    val siblingCards = remember(currentBook.id, currentBook.mergeKey, allBooks) {
        siblingNarrations(allBooks, currentBook.id, currentBook.mergeKey ?: "")
    }
    // Spec-10 T6: stream-only sources (lihtar — its ToS forbids reproduction)
    // hide the download action; the repository refuses anyway, in depth.
    // ADR-0008: the pure stream-only decision is read from the source helpers
    // directly — no forwarding function on the ViewModel.
    val streamOnly = streamOnlyFor(sourceIdForUrl(currentBook.sourceUrl))
    // ADR-0008: suspend module calls from user actions run on the composition
    // scope (same pattern as playerManager's call-through).
    val scope = rememberCoroutineScope()

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
        isPlayingThisBook = playerState.currentBook?.id == currentBook.id,
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
                title = { Text(currentBook.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        com.example.ui.components.BookCoverImage(
                            book = currentBook,
                            contentDescription = currentBook.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = currentBook.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("book_detail_title")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // "4read.org" is a placeholder author, not a real one — the
                    // Source pill below already names the catalog. #40: a real
                    // author's name opens their catalogue page
                    // (`/xfsearch/avtor/…`), a blank renders nothing.
                    if (currentBook.displayAuthor.isNotBlank()) {
                        Text(
                            text = "By ${currentBook.displayAuthor}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .testTag("book_detail_author_link")
                                .clickable {
                                    viewModel.openPersonBooks(
                                        CatalogPerson(
                                            name = currentBook.displayAuthor,
                                            path = bookPersonPath("avtor", currentBook.displayAuthor),
                                            bookCount = 0
                                        )
                                    )
                                }
                        )
                    }

                    // #40: a real narrator is a tappable person link
                    // (`/xfsearch/chitaet/…`); the fabricated
                    // "4read Voice Narrator" placeholder is scrubbed away
                    // entirely (ADR-0004 write path), never rendered as text.
                    val narratorName = currentBook.displayNarrator
                    if (narratorName.isNotBlank()) {
                        Text(
                            text = "Narrated by $narratorName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.openPersonBooks(
                                        CatalogPerson(
                                            name = narratorName,
                                            path = bookPersonPath("chitaet", narratorName),
                                            bookCount = 0
                                        )
                                    )
                                }
                                .testTag("book_detail_narrator_link"),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tags row — FlowRow so long labels wrap instead of being
                    // clipped off-screen or split mid-word.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "4read Каталог" is the placeholder genre for catalog
                        // books — the Source pill below already says it.
                        if (currentBook.genre.isNotBlank() &&
                            !currentBook.genre.contains("4read", ignoreCase = true)
                        ) {
                            TagPill(
                                text = currentBook.genre,
                                color = MaterialTheme.colorScheme.onSurface,
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        // Only render the stats pill when at least one value is
                        // actually known — never a fabricated "5 Ch. • 4:00:00"
                        // for a catalogue book whose chapters haven't loaded yet.
                        // Each part renders only when known, so a source with a
                        // real duration but no chapter count shows just the
                        // duration, never "0 Ch.".
                        val chaptersKnown = currentBook.totalChapters > 0
                        val durationKnown = currentBook.totalDurationSeconds > 0L
                        if (chaptersKnown || durationKnown) {
                            TagPill(
                                text = when {
                                    chaptersKnown && durationKnown ->
                                        "${currentBook.totalChapters} Ch. • ${MainViewModel.formatTime(currentBook.totalDurationSeconds)}"
                                    chaptersKnown -> "${currentBook.totalChapters} Ch."
                                    else -> MainViewModel.formatTime(currentBook.totalDurationSeconds)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        // Real site rating — repositories seed 0f for unknown,
                        // so a positive value means the page actually carried one.
                        if (currentBook.rating > 0f) {
                            TagPill(
                                text = "★ ${currentBook.rating}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        // The one canonical source label — only for books that
                        // actually came from the catalog, never for local imports.
                        // Spec-22 T2: solid scrim instead of a translucent wash,
                        // so the pill stays readable on any background.
                        if (currentBook.sourceUrl.contains("4read.org")) {
                            TagPill(
                                text = "4read.org Source",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                container = AppBadgeScrim,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppBadgeScrimBorder)
                            )
                        }

                        // #40 decision 1: the book's series names its
                        // neighbours — the pill opens the series page
                        // (spec-9 T1), where the volume order and the
                        // next-unread CTA live.
                        val seriesTitle = currentBook.seriesTitle.orEmpty()
                        if (seriesTitle.isNotBlank()) {
                            SeriesPill(
                                seriesTitle = seriesTitle,
                                seriesIndex = currentBook.seriesIndex ?: 0,
                                onClick = {
                                    currentBook.seriesUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                        viewModel.openSeries(seriesTitle, url)
                                    }
                                }
                            )
                        }
                        // Spec-25 (#171): the universe line under the series
                        // pill — renders only when the series' universe
                        // resolved (a missing universe stays silent). The
                        // adjacent affordance (spec-26 T9) reports a wrong
                        // universe: the line hides, the work re-resolves, and
                        // the verdict either corrects the resolution (cache +
                        // shared base) or restores it.
                        bookUniverse?.let { universe ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BookUniverseLine(universe.universeName)
                                TextButton(
                                    onClick = { viewModel.reportWrongUniverse(currentBook.id) },
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier.testTag("book_detail_report_wrong_universe")
                                ) {
                                    Text(
                                        text = "Всесвіт неправильний?",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Description — the Source pill above already names the
                    // catalog, so strip the repeated domain/prefix from the
                    // stored template and keep only the meaningful part.
                    val displayDescription = remember(currentBook.description) {
                        currentBook.description
                            .replace("Аудіокнига з каталогу 4read.org. ", "")
                            .replace("Аудиокнига с портала 4read.org. ", "")
                            .replace("Книга знайдена на порталі 4read.org за запитом \"", "")
                            .replace("\". Джерело: ", ". Джерело: ")
                            .replace(Regex("""https?://4read\.org/"""), "")
                            .trim()
                    }
                    if (displayDescription.isNotBlank()) {
                        Text(
                            text = displayDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }

                    // Spec-15 T5: what every source carrying this Work says
                    // about it — one labelled block per source (description,
                    // rating, narrator, genres), loaded through the source's
                    // own adapter. A source whose page fails degrades to the
                    // remaining blocks, never a blank page.
                    if (sourceProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Що кажуть джерела",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        sourceProfiles.forEach { profile ->
                            SourceProfileBlock(profile = profile)
                        }
                    } else if (isSourceProfilesLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

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
                            NarrationRowCard(
                                sibling = sibling,
                                onClick = { viewModel.selectBook(sibling.id) }
                            )
                        }
                    }

                    // Spec-23 T5: «Джерела» — every Edition carrying the Work
                    // (source name + stream-only marker), from the persisted
                    // `editions` rows. Tapping one plays that variant through
                    // the existing per-source policy (incl. Referer/UA). The
                    // current book's own source is marked.
                    if (bookSources.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Джерела",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        bookSources.forEach { source ->
                            WorkSourceRowCard(
                                source = source,
                                isCurrent = source.url == currentBook.sourceUrl,
                                onClick = { viewModel.playFromSource(source.sourceId, source.url) }
                            )
                        }
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
                                // restart from the top (RELISTEN); everything
                                // else starts or resumes.
                                when (playState) {
                                    BookPlayState.Finished -> viewModel.relistenBook(currentBook)
                                    else -> viewModel.playAudiobook(currentBook)
                                }
                                viewModel.setShowFullPlayer(true)
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
                                    text = if (currentBook.isDownloaded) "Offline" else "Download",
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
                                text = "Chapters (${chapters.size})",
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
                                text = "Bookmarks (${bookmarks.size})",
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

/**
 * Spec-15 T5 — one labelled per-source block of the book detail page: what a
 * single source carrying the Work says about it (description, rating,
 * narrator, genres), loaded through that source's own adapter. Only the
 * fields the source's page actually carried render — a source with no
 * description contributes its rating/narrator/genres, never filler. Pure
 * `@Composable` (no ViewModel) so the snapshot seam can pin it from fixture
 * data.
 */
@Composable
fun SourceProfileBlock(
    profile: com.example.data.entries.LibraryEntries.SourceProfile,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("source_profile_${profile.sourceId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadgePill(label = profile.sourceName)
                Spacer(modifier = Modifier.width(8.dp))
                if (profile.rating != null) {
                    Text(
                        text = "★ ${profile.rating}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (profile.narrator.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Читає: ${profile.narrator}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (profile.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (profile.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.genres.take(3).forEach { genre ->
                        TagPill(
                            text = genre,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            container = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
        }
    }
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
                    Text(
                        text = "Duration: ${MainViewModel.formatTime(chapter.durationSeconds)}",
                        // Spec-22 T2: tabular figures for duration counters.
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onPlayClick) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = "Play Chapter",
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
                    text = "At ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
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

/**
 * Spec-23 T5 — one row of the book page's «Джерела» section: a source that
 * carries the Work, with its stream-only marker («Тільки стрімінг»). Tapping
 * plays that source's variant through the existing per-source policy.
 * [isCurrent] marks the source the library row itself came from. Pure
 * `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkSourceRowCard(
    source: com.example.data.catalog.SourceCatalog.WorkSourceRow,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("work_source_${source.sourceId}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.sourceName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceBadgePill(label = "Поточна")
                    }
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
    sibling: com.example.data.db.AudiobookEntity,
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
        }
    }
}
