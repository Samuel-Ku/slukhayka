package com.slukhayka.audiobooks.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * spec-54 T08 (#875) — a collection (or a curator's profile) is a PAGE, not a
 * temporary panel.
 *
 * It is a full-screen surface with ONE explicit way back, so the content
 * behaves like a page: nothing is half-expanded, nothing can be dragged away,
 * and the system back does the same thing as the button. The screen UNDERNEATH
 * stays composed, so the listener's place in the collections list is kept.
 */
@Composable
fun CollectionPage(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "collection_page",
    content: @Composable () -> Unit
) {
    BackHandler { onClose() }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .heightIn(min = AppDimens.TouchTarget)
                        .testTag("${testTag}_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.a11y_close_page)
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
