package com.slukhayka.audiobooks.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import com.slukhayka.audiobooks.data.facets.GenreIdentity
import com.slukhayka.audiobooks.data.metadata.EditionDurationPolicy
import com.slukhayka.audiobooks.data.metadata.EditionDurationSummary
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.formatRowDuration
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.AppDimens
import java.util.Locale

/** Confirmed facts stay correlated to the Edition that owns them. */
data class DiscoveryCollectionEdition(
    val editionId: String,
    val language: String? = null,
    val durationSeconds: Long? = null,
    val narrator: String? = null,
)

/** One existing catalogue card with the confirmed metadata used by its collection. */
data class DiscoveryCollectionBook(
    val result: GlobalSearchResult,
    val genres: Set<String> = emptySet(),
    val languages: Set<String> = (result.sources.map { it.language } + result.language)
        .filter(String::isNotBlank).toSet(),
    val completed: Boolean = false,
    val editions: List<DiscoveryCollectionEdition> = emptyList(),
)

/** Full membership; shortened overview previews never feed this page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoveryCollectionScreen(
    title: String,
    books: List<DiscoveryCollectionBook>,
    onBackClick: () -> Unit,
    onBookClick: (GlobalSearchResult) -> Unit,
    onSearchAll: (String) -> Unit,
    modifier: Modifier = Modifier,
    collectionId: String = title,
    onHideCompletedChange: (Boolean) -> Unit = {},
    onOpenEdition: ((GlobalSearchResult, String) -> Unit)? = null,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onOpenBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("catalog_overview", Context.MODE_PRIVATE) }
    var query by rememberSaveable(collectionId) { mutableStateOf("") }
    var genreSelection by rememberSaveable(collectionId) { mutableStateOf("") }
    var languageSelection by rememberSaveable(collectionId) { mutableStateOf("") }
    var durationSelection by rememberSaveable(collectionId) { mutableStateOf("") }
    var hideCompleted by rememberSaveable(collectionId) {
        mutableStateOf(preferences.getBoolean("hide_finished", false))
    }
    var showFilters by rememberSaveable(collectionId) { mutableStateOf(false) }
    var includeUnknown by rememberSaveable(collectionId, genreSelection, languageSelection, durationSelection) {
        mutableStateOf(false)
    }
    val genres = genreSelection.toSelection()
    val languages = languageSelection.toSelection()
    val durations = durationSelection.toSelection()
    val allBooks = remember(books) { books.distinctBy { it.result.key } }
    val normalizedQuery = remember(query) { FacetIdentity.normalizedText(query).orEmpty() }
    val candidates = remember(allBooks, normalizedQuery, hideCompleted) {
        allBooks.filter { book ->
            (!hideCompleted || !book.completed) &&
                (normalizedQuery.isBlank() || listOf(book.result.title, book.result.author, book.result.narrator)
                    .any { FacetIdentity.normalizedText(it).orEmpty().contains(normalizedQuery) })
        }
    }
    val matches = remember(candidates, genreSelection, languageSelection, durationSelection) {
        candidates.groupBy { collectionMatch(it, genres, languages, durations) }
    }
    val confirmed = matches[CollectionFactMatch.MATCH].orEmpty()
    val unknown = matches[CollectionFactMatch.UNKNOWN].orEmpty()
    val visible = confirmed + if (includeUnknown) unknown else emptyList()
    val genreOptions = remember(allBooks) {
        allBooks.flatMap { it.genres }.flatMap(GenreIdentity::fromSourceText)
            .distinctBy { it.id }.sortedBy { it.label }
    }
    val languageOptions = remember(allBooks) {
        allBooks.flatMap { book -> book.languages + book.editions.mapNotNull { it.language } }
            .mapNotNull(LanguageCode::normalize).distinct().sorted()
    }
    val locale = LocalConfiguration.current.locales[0]
    fun changeHideCompleted(value: Boolean) {
        hideCompleted = value
        preferences.edit().putBoolean("hide_finished", value).apply()
        onHideCompletedChange(value)
    }
    fun resetFilters() {
        genreSelection = ""
        languageSelection = ""
        durationSelection = ""
        includeUnknown = false
        changeHideCompleted(false)
    }
    val active = genres.isNotEmpty() || languages.isNotEmpty() || durations.isNotEmpty() || hideCompleted
    val listState = rememberLazyListState()
    IndexScreenScaffold(
        title = title,
        onBackClick = onBackClick,
        subtitle = stringResource(R.string.collection1826_results, visible.size, allBooks.size),
    ) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.collection1826_search_within)) },
                singleLine = true,
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) {
                        Icon(PaperInkIcons.Close, contentDescription = stringResource(R.string.collection1826_clear_search))
                    } }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppDimens.PageSides)
                    .testTag("discovery_collection_search"),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(
                    onClick = { showFilters = true },
                    modifier = Modifier.testTag("discovery_collection_filters"),
                ) {
                    Text(if (active) stringResource(
                        R.string.collection1826_filters_count,
                        genres.size + languages.size + durations.size + if (hideCompleted) 1 else 0,
                    ) else stringResource(R.string.collection1826_filters))
                }
                if (active) {
                    IconButton(onClick = ::resetFilters, modifier = Modifier.testTag("collection_filter_reset")) {
                        Icon(PaperInkIcons.FilterOff, contentDescription = stringResource(R.string.collection1826_reset))
                    }
                }
                TextButton(
                    onClick = { onSearchAll(query) },
                    modifier = Modifier.weight(1f).testTag("discovery_collection_search_all"),
                ) { Text(stringResource(R.string.collection1826_search_all)) }
            }
            if (active) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = AppDimens.PageSides),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("discovery_collection_active_filters"),
                ) {
                    items(genres.toList(), key = { "genre_$it" }) { genreId ->
                        FilterChip(
                            selected = true, onClick = { genreSelection = genreSelection.toggled(genreId) },
                            label = { Text(genreOptions.firstOrNull { it.id == genreId }?.label
                                ?: stringResource(R.string.collection1826_genres)) },
                        )
                    }
                    items(languages.toList(), key = { "language_$it" }) { language ->
                        FilterChip(selected = true, onClick = { languageSelection = languageSelection.toggled(language) },
                            label = { Text(languageLabel(language, locale)) })
                    }
                    items(FacetDurationBucket.entries.filter { it.wireName in durations }, key = { it.wireName }) { duration ->
                        FilterChip(selected = true, onClick = { durationSelection = durationSelection.toggled(duration.wireName) },
                            label = { Text(durationLabel(duration)) })
                    }
                    if (hideCompleted) {
                        item(key = "hide_completed") {
                            FilterChip(selected = true, onClick = { changeHideCompleted(false) },
                                label = { Text(stringResource(R.string.collection1826_hide_completed)) })
                        }
                    }
                }
            }
            if (unknown.isNotEmpty()) {
                TextButton(
                    onClick = { includeUnknown = !includeUnknown },
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("discovery_collection_include_unknown"),
                ) {
                    Text(stringResource(
                        if (includeUnknown) R.string.collection1826_hide_unknown else R.string.collection1826_show_unknown,
                        unknown.size,
                    ))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag("discovery_collection_list"),
                contentPadding = PaddingValues(bottom = AppDimens.SpaceAboveMiniPlayer),
            ) {
                if (visible.isEmpty()) {
                    item {
                        EmptyState(
                            icon = PaperInkIcons.Search,
                            title = stringResource(R.string.collection1826_empty), body = "",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                items(visible, key = { it.result.key }) { book ->
                    val details = listOfNotNull(
                        book.editions.filter {
                            val match = collectionEditionMatch(it, languages, durations)
                            match == CollectionFactMatch.MATCH ||
                                (includeUnknown && match == CollectionFactMatch.UNKNOWN)
                        }.mapNotNull { it.narrator?.takeIf(String::isNotBlank) }.distinct()
                            .joinToString(" · ").ifBlank {
                                book.result.narrator.takeIf {
                                    languages.isEmpty() && durations.isEmpty()
                                }.orEmpty()
                            }.takeIf(String::isNotBlank),
                        collectionDurationLabel(book, languages, durations, includeUnknown),
                    )
                    BookRow(
                        title = book.result.title,
                        author = book.result.author,
                        coverUrl = book.result.coverImageUrl,
                        genre = book.genres.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        stats = details.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        badges = if (book.completed) {
                            { MetadataChip(text = stringResource(R.string.collection1826_completed)) }
                        } else null,
                        stateDescription = if (book.completed) stringResource(R.string.collection1826_completed) else null,
                        onClick = {
                            val preferredEdition = if (languages.isNotEmpty() || durations.isNotEmpty()) {
                                val knownEditions = book.editions.filter { it.editionId.isNotBlank() }
                                knownEditions.firstOrNull {
                                    collectionEditionMatch(it, languages, durations) == CollectionFactMatch.MATCH
                                } ?: knownEditions.firstOrNull {
                                    includeUnknown && collectionEditionMatch(it, languages, durations) == CollectionFactMatch.UNKNOWN
                                }
                            } else null
                            if (preferredEdition != null && onOpenEdition != null) {
                                onOpenEdition(book.result, preferredEdition.editionId)
                            } else {
                                onBookClick(book.result)
                            }
                        },
                        footnote = { CatalogCardStatus(book.result.key, actionState, onOpenBrowser) },
                        testTag = "discovery_collection_book_${book.result.key}",
                    )
                }
            }
        }
    }
    if (showFilters) {
        AlertDialog(
            onDismissRequest = { showFilters = false },
            title = { Text(stringResource(R.string.collection1826_filters)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.collection1826_genres), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genreOptions.forEach { genre ->
                            FilterChip(
                                selected = genre.id in genres,
                                onClick = { genreSelection = genreSelection.toggled(genre.id) },
                                label = { Text(genre.label) },
                                modifier = Modifier.testTag("collection_filter_genre_${genre.id}"),
                            )
                        }
                    }
                    Text(stringResource(R.string.collection1826_languages), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languageOptions.forEach { language ->
                            FilterChip(
                                selected = language in languages,
                                onClick = { languageSelection = languageSelection.toggled(language) },
                                label = { Text(languageLabel(language, locale)) },
                                modifier = Modifier.testTag("collection_filter_language_$language"),
                            )
                        }
                    }
                    Text(stringResource(R.string.collection1826_duration), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FacetDurationBucket.entries.forEach { duration ->
                            FilterChip(
                                selected = duration.wireName in durations,
                                onClick = { durationSelection = durationSelection.toggled(duration.wireName) },
                                label = { Text(durationLabel(duration)) },
                                modifier = Modifier.testTag("collection_filter_duration_${duration.wireName}"),
                            )
                        }
                    }
                    FilterChip(
                        selected = hideCompleted, onClick = { changeHideCompleted(!hideCompleted) },
                        label = { Text(stringResource(R.string.collection1826_hide_completed)) },
                        modifier = Modifier.testTag("collection_filter_hide_completed"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilters = false }, modifier = Modifier.testTag("collection_filter_done")) {
                    Text(stringResource(R.string.collection1826_done))
                }
            },
            dismissButton = {
                TextButton(onClick = ::resetFilters) { Text(stringResource(R.string.collection1826_reset)) }
            },
        )
    }
}

private fun String.toSelection(): Set<String> = split('|').filter(String::isNotBlank).toSet()
private fun String.toggled(value: String): String = toSelection().let { selected ->
    (if (value in selected) selected - value else selected + value).joinToString("|")
}

private enum class CollectionFactMatch { MATCH, UNKNOWN, MISMATCH }

private fun combineFacts(vararg facts: CollectionFactMatch): CollectionFactMatch = when {
    CollectionFactMatch.MISMATCH in facts -> CollectionFactMatch.MISMATCH
    CollectionFactMatch.UNKNOWN in facts -> CollectionFactMatch.UNKNOWN
    else -> CollectionFactMatch.MATCH
}

private fun collectionMatch(
    book: DiscoveryCollectionBook,
    genres: Set<String>, languages: Set<String>, durations: Set<String>,
): CollectionFactMatch {
    val knownGenres = book.genres.flatMap(GenreIdentity::fromSourceText).map { it.id }.toSet()
    val genreMatch = when {
        genres.isEmpty() || knownGenres.any { it in genres } -> CollectionFactMatch.MATCH
        knownGenres.isEmpty() -> CollectionFactMatch.UNKNOWN
        else -> CollectionFactMatch.MISMATCH
    }
    if (languages.isEmpty() && durations.isEmpty()) return genreMatch
    val editionMatches = collectionEditions(book).map { collectionEditionMatch(it, languages, durations) }
    val editionMatch = when {
        CollectionFactMatch.MATCH in editionMatches -> CollectionFactMatch.MATCH
        CollectionFactMatch.UNKNOWN in editionMatches -> CollectionFactMatch.UNKNOWN
        else -> CollectionFactMatch.MISMATCH
    }
    return combineFacts(genreMatch, editionMatch)
}

private fun collectionEditions(book: DiscoveryCollectionBook): List<DiscoveryCollectionEdition> =
    if (book.editions.isNotEmpty()) book.editions else {
        val language = LanguageCode.normalize(book.result.language)
        if (language != null) {
            listOf(DiscoveryCollectionEdition("", language, book.result.durationSeconds))
        } else {
            // A duration without Edition ownership cannot be paired with a
            // language from a different source. Keep those facts separate.
            listOf(DiscoveryCollectionEdition("", null, book.result.durationSeconds)) +
                book.languages.mapNotNull(LanguageCode::normalize).distinct()
                    .map { DiscoveryCollectionEdition("", it, null) }
        }
    }

private fun collectionEditionMatch(
    edition: DiscoveryCollectionEdition, languages: Set<String>, durations: Set<String>,
): CollectionFactMatch {
        val language = LanguageCode.normalize(edition.language)
        val duration = edition.durationSeconds?.let(EditionDurationPolicy::bucketFor)?.wireName
        return combineFacts(
            when {
                languages.isEmpty() -> CollectionFactMatch.MATCH
                language == null -> CollectionFactMatch.UNKNOWN
                language in languages -> CollectionFactMatch.MATCH
                else -> CollectionFactMatch.MISMATCH
            },
            when {
                durations.isEmpty() -> CollectionFactMatch.MATCH
                duration == null -> CollectionFactMatch.UNKNOWN
                duration in durations -> CollectionFactMatch.MATCH
                else -> CollectionFactMatch.MISMATCH
            },
        )
    }

private fun collectionDurationLabel(
    book: DiscoveryCollectionBook, languages: Set<String>, durations: Set<String>, includeUnknown: Boolean,
): String? {
    val eligible = collectionEditions(book).filter {
        when (collectionEditionMatch(it, languages, durations)) {
            CollectionFactMatch.MATCH -> true
            CollectionFactMatch.UNKNOWN -> includeUnknown
            CollectionFactMatch.MISMATCH -> false
        }
    }
    return when (val summary = EditionDurationPolicy.summarize(eligible.mapNotNull { it.durationSeconds })) {
        is EditionDurationSummary.Single -> formatRowDuration(summary.seconds)
        is EditionDurationSummary.Range ->
            "${formatRowDuration(summary.shortestSeconds)}–${formatRowDuration(summary.longestSeconds)}"
        null -> null
    }
}

private fun languageLabel(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale)

@Composable
private fun durationLabel(bucket: FacetDurationBucket): String = stringResource(when (bucket) {
    FacetDurationBucket.UNDER_FIVE_HOURS -> R.string.discovery_duration_under_five
    FacetDurationBucket.FIVE_TO_TEN_HOURS -> R.string.discovery_duration_five_ten
    FacetDurationBucket.TEN_TO_TWENTY_HOURS -> R.string.discovery_duration_ten_twenty
    FacetDurationBucket.TWENTY_HOURS_OR_MORE -> R.string.discovery_duration_over_twenty
})
