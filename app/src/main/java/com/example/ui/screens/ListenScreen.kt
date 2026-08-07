package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AudiobookEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.CyberTextPrimary
import com.example.ui.theme.CyberTextSecondary

/**
 * Слухати tab (spec-9): the listening panel — the app's first screen. The full
 * block set (hero resume card, recent, downloaded, new-on-4read, empty state,
 * continue-the-series) lands with ticket T3/T4; this file starts as the tab
 * shell so the navigation ticket (T2) stays green and demoable on its own.
 */
@Composable
fun ListenScreen(
    viewModel: MainViewModel,
    onBookClick: (String) -> Unit,
    onPlayClick: (AudiobookEntity) -> Unit,
    onBrowseClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Слухати",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = CyberTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Панель прослуховування",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = CyberTextSecondary
        )
    }
}
