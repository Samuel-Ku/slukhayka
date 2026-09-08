package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

/**
 * spec-28 (#202) — the shared pushed-index-screen chrome: one Scaffold with
 * a top bar (title, back arrow, zeroed window insets, background colors)
 * that every catalogue index screen («Серії», «Колекції») reuses. Only the
 * chrome is shared — the CONTENT stays per-screen and is handed in as the
 * [content] slot with the scaffold's padding already applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexScreenScaffold(
    title: String,
    onBackClick: () -> Unit,
    actions: @Composable () -> Unit = {},
    // v1.4 E6 (ADR-0033): the screen's honest count lives under the title
    // (R10 — the counter rides the canonical header, never a free-standing
    // list row).
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(title) {
        withFrameNanos { }
        headingFocusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier
            .testTag("secondary_screen_pane")
            .accessibilityPane(title),
        topBar = {
            // Host Scaffold in MainActivity already consumed the status bar
            // (innerPadding.top); don't let this inner TopAppBar add it again.
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier
                                .focusRequester(headingFocusRequester)
                                .focusable()
                                .testTag("secondary_screen_heading")
                                .semantics { heading() }
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("secondary_screen_subtitle")
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.secondary_action_back)
                        )
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        content(padding)
    }
}

/**
 * spec-28 (#202) — the shared index empty-state: the centred icon + message
 * placeholder every catalogue index renders when its data hasn't synced yet
 * (never a crash). v1.4 C4 (ADR-0033): a thin facade over the canonical
 * [EmptyState] — one empty-state shape app-wide (icon 56, bold title,
 * polite live-region announcement). The message is per-screen.
 */
@Composable
fun IndexEmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        title = message,
        body = "",
        modifier = modifier
    )
}

/**
 * A named progress state shared by pushed content lists. v1.4 C4
 * (ADR-0033): a thin facade over the canonical [EmptyState] — the spinner
 * rides the icon slot (a live indicator, not a static glyph), the label is
 * the canonical title, and the real progress-bar node keeps its
 * contentDescription so TalkBack announces loading exactly once.
 */
@Composable
fun SecondaryLoadingState(
    modifier: Modifier = Modifier
) {
    val loadingDescription = stringResource(R.string.secondary_loading)
    EmptyState(
        icon = Icons.Filled.Info,
        title = loadingDescription,
        body = "",
        iconContent = {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("secondary_loading")
                    .semantics {
                        contentDescription = loadingDescription
                    }
            )
        },
        modifier = modifier
    )
}

/**
 * A one-shot polite empty/error message, rendered only while that state
 * exists. v1.4 C4 (ADR-0033): a thin facade over the canonical [EmptyState]
 * — the error flavour announces «Помилка» on the same polite title node.
 */
@Composable
fun SecondaryMessageState(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val errorState = stringResource(R.string.secondary_state_error)
    EmptyState(
        icon = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
        title = message,
        body = "",
        stateDescription = if (isError) errorState else null,
        modifier = modifier.testTag("secondary_message")
    )
}
