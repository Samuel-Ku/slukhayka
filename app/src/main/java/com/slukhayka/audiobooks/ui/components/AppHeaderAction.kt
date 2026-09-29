package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #885 (wave 3) — the ONE action a root screen's header may carry.
 *
 * The prototype gives every header action the same shape: `.sl-icon` is a
 * 48×48 circle, and the ones sitting in a header add `.sl-tonal`
 * (`background:var(--sl-card)`). The app rendered them as bare `IconButton`
 * glyphs with no backing at all, so the header read as loose symbols floating
 * next to the title rather than as a row of controls.
 *
 * This is a vocabulary collapse, not a new style: the gear, the library's ⋮ and
 * «+», and Огляд's refresh were four copies of the same 48 dp icon button.
 * Putting the shape in one place is what makes them stay consistent — a
 * half-migrated header (tonal gear beside a bare ⋮) would look worse than the
 * state it replaced.
 *
 * [contentDescription] is required, not optional: a header action with no
 * spoken label is unreachable by TalkBack, and the app's a11y contract expects
 * every root to expose its actions.
 */
@Composable
fun AppHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    /**
     * Optional icon colour. Unspecified keeps the tonal surface's own content
     * colour; the library's «+» is deliberately primary, and a collapse must
     * preserve what a surface already meant, not flatten it.
     */
    tint: Color = Color.Unspecified
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        // `surfaceContainer` IS the palette's card colour in both schemes
        // (`AppCardDark` / `AppCardLight`), which is what the prototype's
        // `--sl-card` resolves to — so no new colour enters the theme.
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .size(AppDimens.TouchTarget)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(AppHeaderActionIconSize),
            tint = tint
        )
    }
}

/** The prototype's `.sl-icon svg { width:22px }`, in dp. */
private val AppHeaderActionIconSize = AppDimens.TouchTarget * (22f / 48f)
