package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.library.ListenComposer
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.RemainingTimeUnits
import com.slukhayka.audiobooks.ui.library.deduplicateListenShelves
import com.slukhayka.audiobooks.ui.library.formatRemainingTime
import com.slukhayka.audiobooks.ui.library.nextSeriesPartCaption
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Слухати tab (spec-9): the listening panel — the app's first screen. Built
 * only from data that already exists or comes straight from 4read: the hero
 * resume card, recently-listened, downloaded books and one or two "new on
 * 4read" rows. On a fresh install the tab shows a placeholder hero and two
 * CTAs instead of raw emptiness.
 *
 * The blocks are pure composables ([ListenHeroCard], [ListenBlockShelf],
 * [ListenEmptyState]) so snapshot tests can render them without a
 * `MainViewModel`.
 */
@Composable
fun ListenScreen(
    viewModel: MainViewModel,
    // ADR-0008 batch 3 (#158): the screen receives the modules it reads from
    // as parameters, wired from the composition root — the injection idiom
    // settled by #154. Listen composition (blocks, prefs, hidden) and the
    // next-in-series orchestration stay on the ViewModel. spec-28 (#192):
    // discovery left the tab — only personal content (hero + 8 shelves).
    libraryEntries: LibraryEntries,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit,
    onImportClick: () -> Unit
) {
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel. Cold flows need an initial value; the catalogue StateFlows
    // carry their own.
    val allBooks by libraryEntries.allBooks.collectAsState(initial = emptyList())
    // Wayfinder #62: the rule-based block list — every block carries its
    // eligibility and a reason line; hidden blocks stay computed but are
    // unrendered here.
    val listenBlocks by viewModel.listenBlocks.collectAsState()
    val hiddenBlocks by viewModel.hiddenListenBlocks.collectAsState()
    // v1.4 E1: the single shelf-management door — the eight per-block ⋮
    // menus are gone; one sheet owns reorder/hide/restore (ADR-0033).
    var manageShelvesOpen by rememberSaveable { mutableStateOf(false) }

    // Refresh the "continue the series" suggestion whenever the hero book
    // changes (keyed on its id so position updates don't refetch).
    val heroBook = listenBlocks.firstOrNull { it.id == ListenComposer.BlockId.HERO }?.books?.firstOrNull()?.book
    LaunchedEffect(heroBook?.id) {
        viewModel.loadNextInSeries(heroBook)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("listen_screen"),
        contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer)
    ) {
        // Fresh install: placeholder hero + clear next actions.
        if (allBooks.isEmpty()) {
            item {
                ListenEmptyState(
                    onBrowseClick = onBrowseClick,
                    onImportClick = onImportClick
                )
            }
            return@LazyColumn
        }

        // Wayfinder #62 — the rule-based block list. Every eligible block
        // renders with its reason; hidden blocks are skipped (still computed).
        val visibleBlocks = listenBlocks.filter { it.id !in hiddenBlocks }

        // spec-28 (#191 + #200): full cross-shelf dedup — a book renders in
        // at most ONE shelf, the highest on screen. deduplicateListenShelves
        // owns the entire claimed set (hero book included): it claims the
        // hero's book first (US-3), then each visible block claims in display
        // order, so the user's reorder re-prioritises which shelf claims a
        // book. A block emptied by dedup renders nothing (no empty header).
        val dedupedBlocks = deduplicateListenShelves(visibleBlocks)

        for (block in dedupedBlocks) {
            when (block.id) {
                ListenComposer.BlockId.HERO -> {
                    val hero = block.books.first()
                    item(key = "block-hero") {
                        ListenHeroCard(
                            book = hero.book,
                            progress = hero.progress!!,
                            // The hero shows the BOOK-level percent/remaining:
                            // the in-chapter position alone would read as 0 %
                            // for anyone early in a long chapter. The
                            // cumulative wall-clock position comes from the
                            // LibraryBook (the same effectiveChapterDurations
                            // rule the library cards use).
                            cumulativePositionSeconds = hero.cumulativePositionSeconds,
                            onResumeClick = { onPlayClick(hero.book) },
                            onBookClick = { onBookClick(hero.book.id) }
                        )
                    }
                    // v1.4 E1: the ONE shelf-management door, right after the
                    // hero (the spec's «шапка таба або після hero»).
                    item(key = "block-manage") {
                        ListenShelvesManageEntry(onClick = { manageShelvesOpen = true })
                    }
                }
                // The seven remaining blocks — one horizontal shelf of
                // compact posters each (spec-28 #191). v1.4 E1: the header IS
                // the canonical section header (no ⋮ menu — reorder and hide
                // live in the shelf sheet); a block with no books left after
                // dedup renders nothing.
                else -> {
                    if (block.books.isEmpty()) continue
                    item(key = "block-${block.id.name}") {
                        AppSectionHeader(
                            title = block.title,
                            subtitle = block.reason,
                            modifier = Modifier.testTag("listen_block_heading_${block.id.name}")
                        )
                    }
                    item(key = "block-${block.id.name}-shelf") {
                        ListenBlockShelf(
                            books = block.books,
                            onBookClick = onBookClick,
                            onNotInterested = { viewModel.dismissListenBook(it) },
                            // spec-28 (#201): the «Далі у серії» shelf restores
                            // the series context — each card names which part
                            // is next. No one-tap play triangle (ADR-0018).
                            captionFor = if (block.id == ListenComposer.BlockId.NEXT_IN_SERIES) {
                                { entry -> nextSeriesPartCaption(entry) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }

        // No hero on screen (hidden via the sheet, or no resume book) — the
        // management door moves to the top so it can never be unreachable
        // (it replaces the old all-hidden restore row).
        if (dedupedBlocks.none { it.id == ListenComposer.BlockId.HERO }) {
            item(key = "block-manage") {
                ListenShelvesManageEntry(onClick = { manageShelvesOpen = true })
            }
        }

        // spec-28 (#192): discovery left the tab — the cross-source
        // «Новинки» rail and the «Більше книг на Sluhay» CTA now live on
        // Огляд, and the 4read sections render there only.
    }

    if (manageShelvesOpen) {
        ListenShelvesSheet(
            blocks = listenBlocks,
            hiddenIds = hiddenBlocks,
            onMoveUp = viewModel::moveListenBlockUp,
            onMoveDown = viewModel::moveListenBlockDown,
            onHide = viewModel::hideListenBlock,
            onUnhide = viewModel::unhideListenBlock,
            onRestoreAll = viewModel::restoreHiddenListenBlocks,
            onDismiss = { manageShelvesOpen = false }
        )
    }
}

/**
 * v1.4 E1 — the single «Керувати полицями» door (tab header or after-hero
 * per the spec): opens the shelf-management sheet. The eight per-block ⋮
 * menus are gone; the blocks themselves render the canonical
 * [AppSectionHeader] (no action slot).
 */
@Composable
fun ListenShelvesManageEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("listen_manage_shelves")
    ) {
        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.listen_manage_shelves))
    }
}

/**
 * v1.4 E1 — the shelf-management sheet: every block listed in display order
 * with ↑↓ reorder, hide and per-row restore, plus a restore-all footer. All
 * mutations reuse the existing [ListenPrefsStore] mechanics through the
 * ViewModel — the same SharedPreferences file the ⋮ menus wrote to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenShelvesSheet(
    blocks: List<ListenComposer.Block>,
    hiddenIds: Set<ListenComposer.BlockId>,
    onMoveUp: (ListenComposer.BlockId) -> Unit,
    onMoveDown: (ListenComposer.BlockId) -> Unit,
    onHide: (ListenComposer.BlockId) -> Unit,
    onUnhide: (ListenComposer.BlockId) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // fix(accessibility): #371 — the decorative drag handle must not
        // create an extra TalkBack focus stop (same ruling as the library
        // filter sheet).
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.clearAndSetSemantics {}) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.accessibilityPane(stringResource(R.string.a11y_listen_shelves_pane))
    ) {
        ListenShelvesSheetContent(
            blocks = blocks,
            hiddenIds = hiddenIds,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onHide = onHide,
            onUnhide = onUnhide,
            onRestoreAll = onRestoreAll,
            onClose = onDismiss,
            headingFocusRequester = headingFocusRequester,
            includePaneSemantics = false
        )
    }
}

/**
 * The sheet body, extracted so the snapshot harness pins it without hosting
 * a `ModalBottomSheet` window (the library filter sheet's pattern).
 */
@Composable
fun ListenShelvesSheetContent(
    blocks: List<ListenComposer.Block>,
    hiddenIds: Set<ListenComposer.BlockId>,
    onMoveUp: (ListenComposer.BlockId) -> Unit,
    onMoveDown: (ListenComposer.BlockId) -> Unit,
    onHide: (ListenComposer.BlockId) -> Unit,
    onUnhide: (ListenComposer.BlockId) -> Unit,
    onRestoreAll: () -> Unit,
    onClose: (() -> Unit)? = null,
    headingFocusRequester: FocusRequester? = null,
    includePaneSemantics: Boolean = true
) {
    val localHeadingFocusRequester = remember { FocusRequester() }
    val effectiveHeadingFocusRequester = headingFocusRequester ?: localHeadingFocusRequester
    LaunchedEffect(effectiveHeadingFocusRequester) {
        withFrameNanos { }
        effectiveHeadingFocusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (includePaneSemantics) {
                    Modifier.accessibilityPane(stringResource(R.string.a11y_listen_shelves_pane))
                } else {
                    Modifier
                }
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("listen_shelves_sheet_content")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.listen_manage_shelves),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(effectiveHeadingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("listen_shelves_sheet_heading")
            )
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.a11y_listen_shelves_close)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        blocks.forEachIndexed { index, block ->
            ListenShelfRow(
                block = block,
                hidden = block.id in hiddenIds,
                canMoveUp = index > 0,
                canMoveDown = index < blocks.lastIndex,
                onMoveUp = { onMoveUp(block.id) },
                onMoveDown = { onMoveDown(block.id) },
                onToggleVisibility = { if (block.id in hiddenIds) onUnhide(block.id) else onHide(block.id) }
            )
        }
        if (hiddenIds.isNotEmpty()) {
            TextButton(
                onClick = onRestoreAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("listen_shelves_restore_all")
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsBackupRestore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.listen_shelves_restore_all))
            }
        }
    }
}

/** One sheet row: the block's title (+ reason or «Приховано»), ↑↓ and the hide/show toggle. */
@Composable
private fun ListenShelfRow(
    block: ListenComposer.Block,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("listen_shelf_row_${block.id.name}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = block.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (hidden) stringResource(R.string.listen_shelves_hidden) else (block.reason ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier = Modifier
                .size(AppDimens.TouchTarget)
                .testTag("listen_shelf_up_${block.id.name}")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.a11y_listen_shelf_move_up, block.title)
            )
        }
        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier
                .size(AppDimens.TouchTarget)
                .testTag("listen_shelf_down_${block.id.name}")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = stringResource(R.string.a11y_listen_shelf_move_down, block.title)
            )
        }
        IconButton(
            onClick = onToggleVisibility,
            modifier = Modifier
                .size(AppDimens.TouchTarget)
                .testTag("listen_shelf_toggle_${block.id.name}")
        ) {
            Icon(
                imageVector = if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = stringResource(
                    if (hidden) R.string.a11y_listen_shelf_show else R.string.a11y_listen_shelf_hide,
                    block.title
                )
            )
        }
    }
}

/**
 * spec-28 (#191) — one Listen block as a horizontal shelf of compact posters
 * ([CompactBookCard] in a LazyRow), replacing the old full-width vertical
 * rows. No default play triangle: tapping a poster opens the book page (the
 * hero card is the resume CTA); the Listen-only «Не цікаво» dismiss stays on
 * each poster.
 */
@Composable
fun ListenBlockShelf(
    books: List<LibraryBook>,
    onBookClick: (String) -> Unit,
    onNotInterested: (String) -> Unit,
    // spec-28 (#201): an optional per-book context caption — only the
    // «Далі у серії» shelf passes one (which part is next); every other
    // shelf keeps the bare canonical card.
    captionFor: ((LibraryBook) -> String?)? = null
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("listen_block_shelf")
    ) {
        items(books, key = { it.book.id }) { entry ->
            PosterCard(
                book = entry.book,
                onClick = { onBookClick(entry.book.id) },
                onNotInterested = { onNotInterested(entry.book.id) },
                // US-3 (spec-28 #199): the card's hairline is fed by the
                // REAL listening percent the library module computed from the
                // playback-progress rows — a started book shows the line, an
                // unstarted one keeps a clean cover.
                progress = entry.percent,
                caption = captionFor?.invoke(entry)
            )
        }
    }
}

/**
 * The big "Продовжити слухати" hero: cover, title, author, current chapter,
 * % progress and remaining time, a large resume button and a small action to
 * open the book page.
 */
@Composable
fun ListenHeroCard(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    cumulativePositionSeconds: Long,
    onResumeClick: () -> Unit,
    onBookClick: () -> Unit
) {
    val totalSec = book.totalDurationSeconds
    val positionSec = cumulativePositionSeconds.coerceAtLeast(0L)
    // Local imports have no duration metadata: percent/remaining would be
    // meaningless (0/1 -> 100%), so fall back to the bare position instead.
    val hasDuration = totalSec > 0
    val progressFraction = if (hasDuration) (positionSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { }
            .clip(RoundedCornerShape(AppDimens.RadiusHero)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.listen_hero_overline),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                com.slukhayka.audiobooks.ui.components.BookCoverImage(
                    book = book,
                    semantics = com.slukhayka.audiobooks.ui.components.BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusCardLg)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.displayAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.listen_chapter_of,
                            progress.currentChapterIndex + 1,
                            book.totalChapters.coerceAtLeast(1)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasDuration) {
                            stringResource(
                                R.string.listen_progress_percent_remaining,
                                (progressFraction * 100).toInt(),
                                formatRemainingLocalized((totalSec - positionSec).coerceAtLeast(0L))
                            )
                        } else {
                            MainViewModel.formatTime(positionSec)
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onResumeClick,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        colors = IconButtonDefaults.iconButtonColors()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.a11y_resume_work, book.title),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    IconButton(
                        onClick = onBookClick,
                        modifier = Modifier.size(AppDimens.TouchTarget)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.a11y_open_work, book.title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** One recently-listened book with its resume progress. */
@Composable
fun RecentlyListenedRow(
    book: AudiobookEntity,
    progress: PlaybackProgressEntity,
    cumulativePositionSeconds: Long,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val totalSec = book.totalDurationSeconds
    val positionSec = cumulativePositionSeconds.coerceAtLeast(0L)
    // Duration-less local imports: keep the bar empty instead of a full bar.
    val hasDuration = totalSec > 0
    val progressFraction = if (hasDuration) (positionSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) { }
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .testTag("recently_listened_${book.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.slukhayka.audiobooks.ui.components.BookCoverImage(
                    book = book,
                    semantics = com.slukhayka.audiobooks.ui.components.BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusInner)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.listen_chapter_position,
                            progress.currentChapterIndex + 1,
                            MainViewModel.formatTime(positionSec)
                        ),
                        // Spec-22 T2: tabular figures — the live position ticks
                        // without shifting the row's digit widths.
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .size(AppDimens.TouchTarget)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.a11y_play_work, book.title),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusProgress)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * Fresh-install state: a placeholder hero card ("your current book will appear
 * here") plus two CTAs — browse the catalogue or import your own files.
 */
@Composable
fun ListenEmptyState(
    onBrowseClick: () -> Unit,
    onImportClick: () -> Unit
) {
    EmptyState(
        icon = Icons.Default.PlayCircle,
        title = "Продовжити слухати",
        body = "Тут з'явиться ваша поточна книга, щойно ви почнете слухати."
    ) {
        Button(
            onClick = onBrowseClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.TouchTarget)
        ) {
            Icon(imageVector = Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.profile_view_catalogue), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onImportClick,
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimens.TouchTarget)
        ) {
            Icon(
                imageVector = Icons.Default.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.profile_import_from_device), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Localized remaining-time label for the hero card (v1.4, ADR-0033). */
@Composable
internal fun formatRemainingLocalized(totalSeconds: Long): String {
    // v1.4: the same bucket logic as the library — the «Залишилося …» phrasing
    // lives in its own listen_remaining_* EN/UK pair (it prefixes, the
    // library's library_remaining wraps). Templates resolve eagerly in this
    // composable body; the units impl does plain formatting.
    val hm = stringResource(R.string.listen_remaining_hm)
    val h = stringResource(R.string.listen_remaining_h)
    val m = stringResource(R.string.listen_remaining_m)
    val single = stringResource(R.string.listen_remaining_less_than_minute)
    return formatRemainingTime(totalSeconds, object : RemainingTimeUnits {
        override fun hoursMinutes(hours: Long, minutes: Long) = String.format(hm, hours, minutes)
        override fun hours(hours: Long) = String.format(h, hours)
        override fun minutes(minutes: Long) = String.format(m, minutes)
        override fun singleMinute() = single
    })
}
