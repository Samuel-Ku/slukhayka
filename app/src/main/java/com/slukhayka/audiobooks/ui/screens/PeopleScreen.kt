package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.IndexScreenScaffold
import com.slukhayka.audiobooks.ui.components.SecondaryLoadingState
import com.slukhayka.audiobooks.ui.components.SecondaryMessageState
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Full-screen Виконавці (`/readers.html`) or Автори (`/avtors.html`) index:
 * an alphabetical list of people with their book counts. Tapping a person
 * opens their books list ([PersonBooksScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onPersonClick: (CatalogPerson) -> Unit,
    restoreFocusPersonPath: String? = null,
    onPersonFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val kind by viewModel.selectedPeopleKind.collectAsState()
    val people by viewModel.peopleEntries.collectAsState()
    val isLoading by viewModel.isPeopleLoading.collectAsState()
    val loadFailed by viewModel.peopleLoadFailed.collectAsState()

    val currentKind = kind ?: return

    IndexScreenScaffold(title = currentKind.title, onBackClick = onBackClick) { padding ->
        PeopleContent(
            people = people,
            isLoading = isLoading,
            loadFailed = loadFailed,
            peopleCountLabel = "${people.size} ${if (currentKind.title == "Виконавці") "виконавців" else "авторів"}",
            onPersonClick = onPersonClick,
            restoreFocusPersonPath = restoreFocusPersonPath,
            onPersonFocusRestored = onPersonFocusRestored,
            listState = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun PeopleContent(
    people: List<CatalogPerson>,
    isLoading: Boolean,
    loadFailed: Boolean,
    peopleCountLabel: String,
    onPersonClick: (CatalogPerson) -> Unit,
    modifier: Modifier = Modifier,
    restoreFocusPersonPath: String? = null,
    onPersonFocusRestored: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val returnFocusRequester = remember { FocusRequester() }

    LaunchedEffect(restoreFocusPersonPath, people, isLoading, loadFailed) {
        val path = restoreFocusPersonPath ?: return@LaunchedEffect
        if (isLoading || loadFailed) return@LaunchedEffect
        val personIndex = people.indexOfFirst { it.path == path }
        if (personIndex < 0) return@LaunchedEffect
        // The count row is item zero; people start at item one.
        listState.scrollToItem(personIndex + 1)
        withFrameNanos { }
        if (runCatching { returnFocusRequester.requestFocus() }.getOrDefault(false)) {
            onPersonFocusRestored(path)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.testTag("people_screen"),
        contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
    ) {
        when {
            isLoading -> {
                item {
                    SecondaryLoadingState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp)
                    )
                }
            }

            loadFailed -> {
                item {
                    SecondaryMessageState(
                        message = stringResource(R.string.secondary_people_error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        isError = true
                    )
                }
            }

            people.isEmpty() -> {
                item {
                    SecondaryMessageState(
                        message = stringResource(R.string.secondary_people_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp)
                    )
                }
            }

            else -> {
                item {
                    Text(
                        text = peopleCountLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                items(people, key = { it.path }) { person ->
                    PersonRow(
                        person = person,
                        onClick = { onPersonClick(person) },
                        modifier = if (person.path == restoreFocusPersonPath) {
                            Modifier.focusRequester(returnFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

/** One person: avatar-initial, name and book count. */
@Composable
fun PersonRow(
    person: CatalogPerson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
            .clickable { onClick() }
            .semantics(mergeDescendants = true) { }
            .testTag("person_${person.path.hashCode()}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "${person.bookCount} ${ukPlural(person.bookCount, "книга", "книги", "книг")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
