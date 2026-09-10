package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * v1.4 C5 (ADR-0033) — the ONE bottom-tab header model: title (+ optional
 * brand mark) and trailing [actions]. The Огляд brand lockup
 * ([showBrandMark]), the Медіатека title+subtitle trio and every bare
 * headline collapse into this composable. The title is the TalkBack heading
 * and can carry the tab's focus-return target ([returnFocusRequester]).
 */
@Composable
fun AppTabHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBrandMark: Boolean = false,
    headingTestTag: String? = null,
    returnFocusRequester: FocusRequester? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppDimens.PageSides),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (showBrandMark) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier
                    .then(if (headingTestTag != null) Modifier.testTag(headingTestTag) else Modifier)
                    .then(
                        if (returnFocusRequester != null) {
                            Modifier.focusRequester(returnFocusRequester).focusable()
                        } else {
                            Modifier
                        }
                    )
                    .semantics { heading() }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = if (showBrandMark) FontWeight.ExtraBold else FontWeight.Bold,
                        letterSpacing = if (showBrandMark) 1.sp else 0.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (actions != null) actions()
    }
}
