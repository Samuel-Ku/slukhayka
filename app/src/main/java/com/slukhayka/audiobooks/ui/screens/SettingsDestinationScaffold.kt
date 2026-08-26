package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane

internal enum class SettingsDestination(
    val titleRes: Int,
    val paneTag: String,
    val headingTag: String
) {
    Profile(
        titleRes = R.string.profile_title,
        paneTag = "profile_screen_pane",
        headingTag = "profile_screen_heading"
    ),
    NetworkPrivacy(
        titleRes = R.string.privacy_title,
        paneTag = "network_privacy_screen_pane",
        headingTag = "network_privacy_screen_heading"
    ),
    Recommendations(
        titleRes = R.string.recommendations_title,
        paneTag = "recommendations_screen_pane",
        headingTag = "recommendations_screen_heading"
    ),
    Storage(
        titleRes = R.string.storage_title,
        paneTag = "storage_destination_screen_pane",
        headingTag = "storage_destination_screen_heading"
    )
}

/** Shared chrome and entry-focus contract for pushed settings destinations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDestinationScaffold(
    destination: SettingsDestination,
    onBackClick: () -> Unit,
    modalVisible: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val title = stringResource(destination.titleRes)
    val headingFocusRequester = remember { FocusRequester() }
    val largeText = LocalDensity.current.fontScale >= 2f

    LaunchedEffect(title) {
        withFrameNanos { }
        headingFocusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier
            .testTag(destination.paneTag)
            .accessibilityPane(title)
            .accessibilityModalBackground(modalVisible),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                expandedHeight = if (largeText) 112.dp else 64.dp,
                title = {
                    Text(
                        text = title,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .focusRequester(headingFocusRequester)
                            .focusable()
                            .testTag(destination.headingTag)
                            .semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.defaultMinSize(
                            minWidth = 48.dp,
                            minHeight = 48.dp
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content
    )
}
