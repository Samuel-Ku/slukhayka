package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.ui.components.IndexEmptyState
import com.slukhayka.audiobooks.ui.library.ukPlural
import kotlinx.coroutines.launch

private const val INLINE_AUTHOR_LIMIT = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorsIndexScreen(
    authors: List<AuthorSummary>,
    onBackClick: () -> Unit,
    onAuthorClick: (AuthorSummary) -> Unit
) {
    AuthorDiscoveryScaffold(title = "Автори", onBackClick = onBackClick) { modifier ->
        AuthorsIndexContent(authors = authors, onAuthorClick = onAuthorClick, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanonicalAuthorScreen(
    author: AuthorSummary,
    works: List<WorkEntity>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onBackClick: () -> Unit,
    onWorkClick: (WorkEntity) -> Unit,
    personBookmarks: PersonBookmarks
) {
    val identity = remember(author.displayName) {
        personBookmarks.identity(PersonRole.AUTHOR, author.displayName)
    }
    val bookmarkFlow = remember(identity) {
        personBookmarks.observePersonBookmark(identity.role.storageValue, identity.id)
    }
    val bookmark by bookmarkFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    AuthorDiscoveryScaffold(
        title = author.displayName,
        onBackClick = onBackClick,
        actions = {
            PersonBookmarkButton(
                isBookmarked = bookmark != null,
                notifyEnabled = bookmark?.notifyEnabled ?: true,
                personName = author.displayName,
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
    ) { modifier ->
        CanonicalAuthorContent(
            author = author,
            works = works,
            onWorkClick = onWorkClick,
            modifier = modifier,
            isLoading = isLoading,
            loadFailed = loadFailed
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthorDiscoveryScaffold(
    title: String,
    onBackClick: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/** Author-first block inserted before both book-result sections on Огляд. */
@Composable
fun AuthorSearchResults(
    authors: List<AuthorSummary>,
    onAuthorClick: (AuthorSummary) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (authors.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().testTag("author_search_results")) {
        Text(
            text = "Автори",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        authors.take(INLINE_AUTHOR_LIMIT).forEach { author ->
            AuthorRow(author = author, onClick = { onAuthorClick(author) })
        }
        if (authors.size > INLINE_AUTHOR_LIMIT) {
            TextButton(onClick = onShowAll, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Усі знайдені автори")
            }
        }
    }
}

/** Full canonical alphabetical author list; never a provider people mirror. */
@Composable
fun AuthorsIndexContent(
    authors: List<AuthorSummary>,
    onAuthorClick: (AuthorSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    if (authors.isEmpty()) {
        IndexEmptyState(
            message = "Автори з'являться після завантаження каталогу.",
            modifier = modifier.testTag("authors_index")
        )
        return
    }
    LazyColumn(
        modifier = modifier.testTag("authors_index"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
    ) {
        item {
            Text(
                text = "${authors.size} ${ukPlural(authors.size, "автор", "автори", "авторів")}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        items(authors, key = AuthorSummary::id) { author ->
            AuthorRow(author, onClick = { onAuthorClick(author) })
        }
    }
}

/** Canonical author's complete locally known Work list, independent of Source. */
@Composable
fun CanonicalAuthorContent(
    author: AuthorSummary,
    works: List<WorkEntity>,
    onWorkClick: (WorkEntity) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    loadFailed: Boolean = false
) {
    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (loadFailed) {
        IndexEmptyState(
            message = "Не вдалося відкрити книги автора. Спробуйте ще раз.",
            modifier = modifier.testTag("canonical_author_page")
        )
        return
    }
    if (works.isEmpty()) {
        IndexEmptyState(
            message = "Книг цього автора поки немає в каталозі.",
            modifier = modifier.testTag("canonical_author_page")
        )
        return
    }
    LazyColumn(
        modifier = modifier.testTag("canonical_author_page"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)
    ) {
        item {
            Text(
                text = "${works.size} ${ukPlural(works.size, "книга", "книги", "книг")}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .testTag("canonical_author_work_count")
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        items(works, key = WorkEntity::id) { work ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onWorkClick(work) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(work.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun AuthorRow(author: AuthorSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            author.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${author.workCount} ${ukPlural(author.workCount, "книга", "книги", "книг")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
