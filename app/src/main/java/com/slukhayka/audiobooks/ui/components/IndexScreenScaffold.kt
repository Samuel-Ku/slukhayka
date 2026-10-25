package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.theme.AppDimens

/** #1235 — below this header width the title gets its own full-width line. */
val NarrowIndexHeaderMaxWidth = 280.dp

/**
 * spec-28 (#202) — the shared pushed-index-screen chrome: one Scaffold with
 * a top bar (title, back arrow, zeroed window insets, background colors)
 * that every catalogue index screen («Серії», «Колекції») reuses. Only the
 * chrome is shared — the CONTENT stays per-screen and is handed in as the
 * [content] slot with the scaffold's padding already applied.
 *
 * #1235 — a wide window's list pane is 192–208 dp, and the small TopAppBar
 * spent 96 dp of that on the back arrow and one action before the title began.
 * The person page's name is the reason #1235 names the header explicitly, but
 * the rule is the component's: under [NarrowIndexHeaderMaxWidth] the back arrow
 * and actions take their own row and the title (two lines, then ellipsis) keeps
 * the pane's full width. The tags, heading semantics and focus requester are
 * identical in both layouts; only the geometry changes.
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
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < NarrowIndexHeaderMaxWidth) {
                    NarrowIndexTopBar(
                        title = title,
                        subtitle = subtitle,
                        actions = actions,
                        onBackClick = onBackClick,
                        headingFocusRequester = headingFocusRequester
                    )
                } else {
                    // Host Scaffold in MainActivity already consumed the status bar
                    // (innerPadding.top); don't let this inner TopAppBar add it again.
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            IndexHeading(
                                title = title,
                                subtitle = subtitle,
                                headingFocusRequester = headingFocusRequester,
                                maxLines = 1
                            )
                        },
                        navigationIcon = {
                            BackIconButton(onBackClick)
                        },
                        actions = { actions() },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        content(padding)
    }
}

/** The narrow header: navigation/actions on one line, title under them. */
@Composable
private fun NarrowIndexTopBar(
    title: String,
    subtitle: String?,
    actions: @Composable () -> Unit,
    onBackClick: () -> Unit,
    headingFocusRequester: FocusRequester
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppDimens.TouchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackIconButton(onBackClick)
                Spacer(modifier = Modifier.weight(1f))
                actions()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppDimens.PageSides,
                        end = AppDimens.PageSides,
                        bottom = AppDimens.SpaceSm
                    )
            ) {
                IndexHeading(
                    title = title,
                    subtitle = subtitle,
                    headingFocusRequester = headingFocusRequester,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun IndexHeading(
    title: String,
    subtitle: String?,
    headingFocusRequester: FocusRequester,
    maxLines: Int
) {
    Column {
        Text(
            text = title,
            maxLines = maxLines,
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
}

@Composable
private fun BackIconButton(onBackClick: () -> Unit) {
    IconButton(
        onClick = onBackClick,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.secondary_action_back)
        )
    }
}
