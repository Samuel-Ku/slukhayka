package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.*

/**
 * Full-screen list of every book in a 4read.org series (cycle) — spec #8
 * ticket T8. Opened from the "Цикли" row of the Explore screen; the book list
 * is fetched (and upserted) from the series page, then rendered as the
 * standard [AudiobookListItem] rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit
) {
    val series by viewModel.selectedSeries.collectAsState()
    val books by viewModel.seriesBooks.collectAsState()
    val isLoading by viewModel.isSeriesLoading.collectAsState()

    val currentSeries = series ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentSeries.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberBg)
            )
        },
        containerColor = CyberBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("series_screen"),
            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
        ) {
            when {
                isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = CyberPrimary)
                        }
                    }
                }

                books.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = CyberTextSecondary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Не вдалося завантажити книги циклу. Перевірте з'єднання.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberTextSecondary
                                )
                            }
                        }
                    }
                }

                else -> {
                    item {
                        Text(
                            text = "${books.size} книг у циклі",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            color = CyberSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(books, key = { it.id }) { book ->
                        AudiobookListItem(
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onPlayClick = {
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            }
                        )
                    }
                }
            }
        }
    }
}
