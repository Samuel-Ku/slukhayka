package com.slukhayka.audiobooks.ui.screens.bookdetail

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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
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
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.screens.BookDetailLinkOrigin
import com.slukhayka.audiobooks.ui.screens.PersonBookmarkControl
import com.slukhayka.audiobooks.ui.screens.BookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.BookDetailSourcePresentation
import com.slukhayka.audiobooks.ui.screens.NarrationRatingRow
import com.slukhayka.audiobooks.ui.screens.ReviewStarsRow
import com.slukhayka.audiobooks.ui.screens.PersonBookmarkButton
import com.slukhayka.audiobooks.ui.library.siblingNarrations
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.data.reviews.ReviewSaveResult
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.reviewWorkIdFor
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.library.BookPlayState
import com.slukhayka.audiobooks.ui.library.bookPlayLabel
import com.slukhayka.audiobooks.ui.library.bookPlayState
import com.slukhayka.audiobooks.ui.library.bookPositionAndTotal
import com.slukhayka.audiobooks.ui.theme.*

/**
 * ПРОТОТИП: hero-обкладинка (у стилі сторінки серіалу в HBO Max).
 *
 * Обкладинка займає верхню частину екрана на всю ширину, а назва, автор,
 * начитка й рейтинг лягають на неї знизу — поверх градієнта, що зливає
 * арт із фоном сторінки. Оригінальна версія була карткою 180×240 dp
 * по центру; тут її замінено на full-bleed.
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
    requestInitialFocus: Boolean = true,
    onInitialFocusHandled: () -> Unit = {},
    returnFocusOrigin: BookDetailLinkOrigin? = null,
    onChildRouteOpened: (BookDetailLinkOrigin) -> Unit = {},
    onReturnFocusRestored: (BookDetailLinkOrigin) -> Unit = {},
    authorBookmark: PersonBookmarkControl = PersonBookmarkControl(),
    narratorBookmark: PersonBookmarkControl = PersonBookmarkControl()
) {
    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    // Висота обкладинки в hero: малюємо її на всю ширину в природній
    // пропорції й притискаємо до ГОРИ, тож ріжеться лише низ — верх
    // обкладинки (назва, арт) лишається цілим.
    var coverAspect by remember(book.coverImageUrl) { mutableStateOf<Float?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // #991: 72 % екрана — це МІНІМУМ hero, а не його стеля. Фіксована
            // висота на fontScale = 2f лишала ОСТАННЬОМУ рядку summary
            // (пілюля серії) ~24 px, бо Column міряє дітей із залишком.
            // `heightIn(min = …)` не змінює нічого для тих, чий summary
            // вміщається в 72 % (1f), і дає боксу вирости, коли не вміщається.
            .heightIn(min = heroHeight)
            .clipToBounds()
            .testTag("book_detail_cover")
    ) {
        // Шар обкладинки. `matchParentSize` тримає обкладинку ПОЗА
        // розрахунком висоти боксу: інакше її природна висота (більша за
        // heroHeight) сама розтягла б бокс навіть на 1f. Усередині ж
        // constraints — це вже ВИМІРЯНИЙ бокс, тож обкладинку прив'язано до
        // реальної висоти hero, а не до константи 72 %: коли summary робить
        // hero вищим, під обкладинкою не лишається дірки.
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val heroMeasuredHeight = maxHeight
            val naturalHeight = coverAspect?.let { maxWidth / it } ?: heroMeasuredHeight
            BookCoverImage(
                book = book,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Міряємо на природну висоту (вона може перевищувати
                    // hero), але звітуємо лише висоту hero: обкладинка
                    // лишається притиснутою до гори, а зайве знизу обрізає
                    // clipToBounds — так само, як було до #991.
                    .wrapContentHeight(unbounded = true, align = Alignment.Top)
                    .height(maxOf(naturalHeight, heroMeasuredHeight)),
                contentScale = ContentScale.Crop,
                onImageLoaded = { drawable ->
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    if (width > 0 && height > 0) {
                        coverAspect = width.toFloat() / height.toFloat()
                    }
                }
            )
        }
        // Верхній скрим: іконки прозорого тулбара мають читатися на арті.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        // Нижній скрим: текст плавно переходить у фон сторінки. Опущений на
        // 40 dp нижче — так видно більше обкладинки. Нижній край градієнта
        // все одно впирається у фон сторінки, тож обрізається непомітно.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 40.dp)
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.30f to MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                        0.55f to MaterialTheme.colorScheme.background,
                        1.0f to MaterialTheme.colorScheme.background
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                requestInitialFocus = requestInitialFocus,
                onInitialFocusHandled = onInitialFocusHandled,
                returnFocusOrigin = returnFocusOrigin,
                onChildRouteOpened = onChildRouteOpened,
                onReturnFocusRestored = onReturnFocusRestored,
                authorBookmark = authorBookmark,
                narratorBookmark = narratorBookmark
            )
        }
    }
}

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
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
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
    // The links already provide 48 dp touch targets; do not add empty rows between people.
    if (presentation.author.isNotBlank()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val textMaxWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.book_detail_author_label, presentation.author),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = textMaxWidth)
                        .focusRequester(authorFocusRequester)
                        .focusProperties { canFocus = true }
                        // #766 B — ORDER MATTERS: with heightIn applied BEFORE
                        // clickable, the touch target measured the text's natural
                        // height (~40dp) even though the layout box was 48dp, and
                        // ATF (TouchTargetSizeCheck) flagged it. The clickable must
                        // wrap the constrained layout.
                        .testTag("book_detail_author_link")
                        .clickable {
                            onChildRouteOpened(BookDetailLinkOrigin.AUTHOR)
                            onAuthorClick(presentation.author)
                        }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                )
                PersonBookmarkButton(
                    isBookmarked = authorBookmark.isBookmarked,
                    notifyEnabled = authorBookmark.notifyEnabled,
                    personName = presentation.author,
                    onToggle = authorBookmark.onToggle,
                    onToggleNotify = authorBookmark.onToggleNotify,
                    testTag = "book_detail_author_bookmark"
                )
            }
        }
    }
    if (presentation.narrator.isNotBlank()) {
        BoxWithConstraints(
            modifier = Modifier
                // #766 B — the -8dp overlap (#561 follow-up) does NOT keep
                // every target its full 48dp: the next row draws over this one,
                // so ATF's TouchTargetSizeCheck measures the AUTHOR row as
                // 140px/40dp instead of the 168px/48dp the layout claims (the
                // unmerged tree shows the row at 48dp, ATF at 40dp —
                // the 8dp overlap is exactly the difference). No offset: the
                // rows sit flush and each keeps its real 48dp target.
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val textMaxWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.book_detail_narrator_label, presentation.narrator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = textMaxWidth)
                        .focusRequester(narratorFocusRequester)
                        .focusProperties { canFocus = true }
                        // #766 B — ORDER MATTERS: with heightIn applied BEFORE
                        // clickable, the touch target measured the text's natural
                        // height (~40dp) even though the layout box was 48dp, and
                        // ATF (TouchTargetSizeCheck) flagged it. The clickable must
                        // wrap the constrained layout.
                        .testTag("book_detail_narrator_link")
                        .clickable {
                            onChildRouteOpened(BookDetailLinkOrigin.NARRATOR)
                            onNarratorClick(presentation.narrator)
                        }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .semantics { stateDescription = currentEditionState }
                )
                PersonBookmarkButton(
                    isBookmarked = narratorBookmark.isBookmarked,
                    notifyEnabled = narratorBookmark.notifyEnabled,
                    personName = presentation.narrator,
                    onToggle = narratorBookmark.onToggle,
                    onToggleNotify = narratorBookmark.onToggleNotify,
                    testTag = "book_detail_narrator_bookmark"
                )
            }
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
    Spacer(modifier = Modifier.height(AppDimens.SpaceSm))
    FlowRow(
        modifier = Modifier.testTag("book_detail_metadata_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (presentation.genre.isNotBlank() && !presentation.genre.contains("4read", ignoreCase = true)) {
            MetadataChip(
                text = presentation.genre,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
        val chaptersKnown = presentation.totalChapters > 0
        val durationKnown = presentation.totalDurationSeconds > 0L
        if (chaptersKnown || durationKnown) {
            val chaptersLabel = pluralStringResource(
                R.plurals.chapter_count,
                presentation.totalChapters,
                presentation.totalChapters
            )
            MetadataChip(
                text = when {
                    chaptersKnown && durationKnown ->
                        "${presentation.totalChapters} $chaptersLabel • ${MainViewModel.formatTime(presentation.totalDurationSeconds)}"
                    chaptersKnown -> "${presentation.totalChapters} $chaptersLabel"
                    else -> MainViewModel.formatTime(presentation.totalDurationSeconds)
                },
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
    universeName?.takeIf(String::isNotBlank)?.let { name ->
        Spacer(modifier = Modifier.height(AppDimens.SpaceSm))
        BookUniverseLine(name)
    }
    val seriesTitle = presentation.seriesTitle.orEmpty()
    if (seriesTitle.isNotBlank()) {
        Spacer(modifier = Modifier.height(AppDimens.SpaceSm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("book_detail_series_row"),
            horizontalArrangement = Arrangement.Center
        ) {
            SeriesPill(
                seriesTitle = seriesTitle,
                seriesIndex = presentation.seriesIndex ?: 0,
                modifier = Modifier.focusRequester(seriesFocusRequester)
                    .focusProperties { canFocus = true },
                onClick = {
                    presentation.seriesUrl?.takeIf(String::isNotBlank)?.let { url ->
                        onChildRouteOpened(BookDetailLinkOrigin.SERIES)
                        onSeriesClick(seriesTitle, url)
                    }
                }
            )
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
            modifier = Modifier.padding(horizontal = 8.dp).testTag("book_detail_description")
        )
    }
}

@Composable
fun BookDetailSourceSection(
    presentation: BookDetailPresentation
) {
    if (presentation.sources.isEmpty()) return
    // v1.4 E4 / spec-46 T10 (#571, ADR-0033): the «Джерело/Джерела» heading is
    // the canonical AppSectionHeader — the last free-standing `Text(titleMedium
    // Bold)` + heading() on the book page. The singular/plural split (#572)
    // stays: one source is not a «Джерела» section.
    AppSectionHeader(
        title = stringResource(
            if (presentation.sources.size == 1) R.string.book_detail_source_heading_one
            else R.string.book_detail_source_heading_many
        )
    )
    presentation.sources.forEach { source ->
        WorkSourceRowCard(
            source = source,
            workTitle = presentation.title
        )
    }
}

/**
 * Spec-23 T5/#426 — one informational row of the book page's «Джерела»
 * section: a source that carries the Work.
 *
 * v1.4 E4 / spec-46 T10 (#571, ADR-0033): the row is the canonical [BookRow]
 * now — «divider not border» (ADR-0018) — instead of its own bordered
 * `Surface`. Slots: the source icon is [BookRow]'s `leading`, the two state
 * markers («Поточна» / «Тільки стрімінг») ride the `badges` chip slot, the
 * source's rating is the `stats` fact line, and the source's DIFFERING
 * metadata (narrator/genres/description — the work's own values are already in
 * the summary above) rides the free-form `footnote` block inside the text
 * column. No `trailing` and no [BookRow] click: the block is informational —
 * playback picks the source through the shared coordinator, so a detail-page
 * tap must never become an implicit source switch.
 * [isCurrent] marks the source the library row itself came from. Pure
 * `@Composable` — pinned by the snapshot seam from fixture rows.
 */
@Composable
fun WorkSourceRowCard(
    source: BookDetailSourcePresentation,
    workTitle: String = ""
) {
    val contextualTitle = workTitle.takeIf(String::isNotBlank)
        ?: stringResource(R.string.book_detail_accent_book)
    val actionDescription = if (source.selectable) {
        stringResource(R.string.book_detail_play_source, contextualTitle, source.name)
    } else {
        stringResource(R.string.book_detail_source_summary, source.name, contextualTitle)
    }
    val sourceState = stringResource(
        if (source.isCurrent) R.string.book_detail_current_source
        else R.string.book_detail_other_source
    ).let { base ->
        if (source.streamOnly) stringResource(R.string.book_detail_source_stream_only, base) else base
    }
    BookRow(
        title = source.name,
        leading = {
            Icon(
                imageVector = if (source.selectable) Icons.Default.PlayArrow else Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .padding(14.dp)
            )
        },
        badges = {
            // «Поточна» / «Тільки стрімінг» are chip slots (E4 AC4). Both are
            // non-interactive MetadataChip facts, never a second control.
            if (source.isCurrent) {
                MetadataChip(
                    modifier = Modifier.testTag(SourceStateChipTags.CURRENT),
                    source = stringResource(R.string.book_detail_source_current)
                )
            }
            if (source.streamOnly) {
                if (source.isCurrent) Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                MetadataChip(
                    modifier = Modifier.testTag(SourceStateChipTags.STREAM_ONLY),
                    text = stringResource(R.string.book_detail_stream_only)
                )
            }
        },
        stats = source.rating?.let { rating ->
            stringResource(
                R.string.book_detail_source_rating,
                "%.1f".format(java.util.Locale.US, rating)
            )
        },
        footnoteInColumn = true,
        footnote = {
            source.differingNarrator?.let { narrator ->
                Text(
                    text = stringResource(R.string.book_detail_source_narrator, narrator),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (source.differingGenres.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.book_detail_source_genres,
                        source.differingGenres.joinToString(" · ")
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            source.differingDescription?.let { description ->
                Text(
                    text = stringResource(R.string.book_detail_source_description, description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        stateDescription = sourceState,
        contentDescription = actionDescription,
        selected = source.isCurrent,
        testTag = "work_source_${source.sourceId}"
    )
}

/** Test seams for the two «Джерела» state chips (spec-46 T10, E4 AC4). */
object SourceStateChipTags {
    const val CURRENT = "source_state_current_chip"
    const val STREAM_ONLY = "source_state_stream_only_chip"
}

/**
 * ADR-0011 — one row of the book page's «Інші начитки» section: another
 * rendition of the same Work. The narrator is the rendition identity
 * (ADR-0010); the row shows it plus the source the card came from, and
 * tapping it opens that card — the narration selection.
 *
 * v1.4 E4 / spec-46 T10 (#571, ADR-0033): the canonical [BookRow] — the
 * rendition icon is the `leading` slot, the language is the `badges` chip
 * slot (spec-45 #495), the source name is the `stats` fact line, and the
 * rendition's own average (ADR-0023) rides the `trailing` slot so it stays
 * end-aligned outside the merged text block. This is the one book-page row
 * that keeps a click: it opens another narration. Pure `@Composable`.
 */
@Composable
fun NarrationRowCard(
    sibling: com.slukhayka.audiobooks.data.db.AudiobookEntity,
    average: Double? = null,
    voteCount: Int = 0,
    onClick: () -> Unit
) {
    val narrator = sibling.narrator.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.book_detail_unknown_narrator)
    val sourceName = sourceDisplayName(sourceIdForUrl(sibling.sourceUrl))
    val actionDescription = stringResource(
        R.string.book_detail_open_edition,
        narrator,
        sibling.title,
        sourceName
    )
    val otherEditionState = stringResource(R.string.book_detail_other_edition)
    BookRow(
        title = narrator,
        leading = {
            Icon(
                imageVector = Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .padding(14.dp)
            )
        },
        badges = {
            // Spec-45 (#405) T7 (#495): this rendition's language — one
            // EN/UA chip next to the narrator; unknown renders nothing.
            if (sibling.language.isNotBlank()) {
                MetadataChip(language = sibling.language)
            }
        },
        stats = sourceName,
        trailing = {
            // ADR-0023 (#357): this rendition's own average — shown only when
            // votes exist (honest absence, ADR-0014).
            if (average != null) {
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
        },
        stateDescription = otherEditionState,
        contentDescription = actionDescription,
        onClick = onClick,
        testTag = "narration_${sibling.id}"
    )
}
