package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks

/**
 * #1706 — the person-bookmark Flows are cold DAO queries: every call builds a
 * NEW Flow instance, and `collectAsState` keys its subscription on that
 * instance. Built in the composition body, they were cancelled and
 * re-subscribed on every recomposition — while playing, the progress write
 * ticks every ~5 s, and on «Огляд» the search query changes on every keystroke.
 *
 * Remember the Flow once per module instance, then collect: the same idiom as
 * `PlayerScreen`/`BookDetailScreen`. The helpers live here so the rule can be
 * tested without a `MainViewModel` (the reason `rememberSeriesBooks` exists,
 * #1506); the initial values stay the ones the screens already passed.
 */
@Composable
internal fun rememberBookmarkedAuthors(
    personBookmarks: PersonBookmarks
): List<PersonBookmarkEntity> =
    remember(personBookmarks) { personBookmarks.bookmarkedAuthors() }
        .collectAsState(initial = emptyList())
        .value

@Composable
internal fun rememberBookmarkedNarrators(
    personBookmarks: PersonBookmarks
): List<PersonBookmarkEntity> =
    remember(personBookmarks) { personBookmarks.bookmarkedNarrators() }
        .collectAsState(initial = emptyList())
        .value

@Composable
internal fun rememberAllPersonBookmarks(
    personBookmarks: PersonBookmarks
): List<PersonBookmarkEntity> =
    remember(personBookmarks) { personBookmarks.allBookmarks() }
        .collectAsState(initial = emptyList())
        .value
