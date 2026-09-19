package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.library.PersonWorkRow
import kotlinx.coroutines.launch

/**
 * spec-54 T07 (#874) — the ONE page for a person, whatever role they play here.
 *
 * It shows **Works**, not books: an author and a narrator are the same entity
 * seen in different roles, and the role changes what the page SAYS about them,
 * never the address it lives at nor the shape of its list. A Work keeps its own
 * narrations inside the row, and «у Медіатеці» is marked for both roles.
 */
@Composable
fun PersonBooksScreen(
    viewModel: MainViewModel,
    personBookmarks: PersonBookmarks,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    restoreFocusBookId: String? = null,
    onBookFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val person by viewModel.selectedPerson.collectAsState()
    val works by viewModel.personWorks.collectAsState()
    val isLoading by viewModel.isPersonLoading.collectAsState()
    val loadFailed by viewModel.personLoadFailed.collectAsState()

    val currentPerson = person ?: return
    val identity = remember(currentPerson) {
        personBookmarks.identity(currentPerson.role, currentPerson.name)
    }
    val bookmarkFlow = remember(identity) {
        personBookmarks.observePersonBookmark(identity.role.storageValue, identity.id)
    }
    val bookmark by bookmarkFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    IndexScreenScaffold(
        title = currentPerson.name,
        // Spec-27 (#204) BUG-006: правильна множина — «1 твір», «2 твори».
        // spec-46 T16 (#577): the plural is chrome — EN reads «1 work».
        subtitle = pluralStringResource(R.plurals.person_works_count, works.size, works.size),
        onBackClick = onBackClick,
        actions = {
            PersonBookmarkButton(
                isBookmarked = bookmark != null,
                notifyEnabled = bookmark?.notifyEnabled ?: true,
                personName = currentPerson.name,
                onToggle = {
                    scope.launch { personBookmarks.toggle(identity) }
                },
                onToggleNotify = { enabled ->
                    scope.launch {
                        personBookmarks.setNotifyEnabled(
                            PersonBookmarkKey(identity.role, identity.id),
                            enabled
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("person_books_screen")
        ) {
            when {
                isLoading && works.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("person_books_loading")
                )

                works.isEmpty() -> EmptyState(
                    // spec-46 T16 (#577): the residual person-page state is the
                    // canonical full EmptyState — icon, message and the error
                    // flavour announcing itself, never a bare Text.
                    icon = if (loadFailed) {
                        Icons.Default.Warning
                    } else {
                        Icons.AutoMirrored.Filled.MenuBook
                    },
                    title = stringResource(
                        if (loadFailed) {
                            R.string.secondary_person_books_error
                        } else {
                            R.string.secondary_person_books_empty
                        }
                    ),
                    body = "",
                    stateDescription = if (loadFailed) {
                        stringResource(R.string.secondary_state_error)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("person_books_empty")
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(works, key = { row -> row.workId.ifBlank { row.title } }) { row ->
                        PersonWorkRowItem(
                            row = row,
                            // Tapping the row opens the rendition the row speaks
                            // for; a Work with no narration of ours stays honest
                            // and simply is not tappable.
                            onClick = row.narrations.firstOrNull()?.let { narration ->
                                { onBookClick(narration.id) }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** One Work on the person's page: its own narrations, its own ownership mark. */
@Composable
private fun PersonWorkRowItem(
    row: PersonWorkRow,
    onClick: (() -> Unit)?
) {
    val people = listOfNotNull(row.author, row.narrator)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
    val narrations = if (row.hasSeveralNarrations) {
        " · " + pluralStringResource(
            R.plurals.person_narrations_count,
            row.narrations.size,
            row.narrations.size
        )
    } else {
        ""
    }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                if (people.isNotEmpty() || narrations.isNotEmpty()) {
                    Text(
                        text = people + narrations,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.ownedInLibrary) {
                    Text(
                        text = stringResource(R.string.person_work_in_library),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("person_work_${row.workId}")
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}
