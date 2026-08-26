package com.slukhayka.audiobooks.ui.components

import android.widget.Toast
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.mediarouter.app.MediaRouteButton
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.player.CastPlaybackController
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * ADR-0024 (#362): the one cast affordance, shared by the player screen and
 * the mini-player («one tool, one place» — the tool itself is shared too).
 *
 * - No Google services on the device → the button never renders (casting is
 *   not a broken promise, it does not exist there).
 * - Book has no stream Source → an honest disabled state; tapping explains
 *   why instead of opening a dialog that could not work anyway.
 * - Otherwise the standard system MediaRouteButton: device discovery and the
 *   picker dialog are exactly what users know from other apps.
 */
@Composable
fun CastButton(
    castReady: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val castAvailable = remember { runCatching { App.instance.castController.isCastAvailable() }.getOrDefault(false) }
    if (!castAvailable) return

    val mediaRouteButton = remember(context) {
        runCatching {
            val themedContext = ContextThemeWrapper(
                context,
                androidx.appcompat.R.style.Theme_AppCompat
            )
            MediaRouteButton(themedContext).also {
                CastButtonFactory.setUpMediaRouteButton(themedContext, it)
            }
        }.getOrNull()
    }

    if (castReady && mediaRouteButton != null) {
        AndroidView(
            modifier = modifier.size(AppDimens.TouchTarget),
            factory = { mediaRouteButton }
        )
    } else {
        IconButton(
            onClick = {
                Toast.makeText(
                    context,
                    CastPlaybackController.NO_STREAM_EXPLANATION,
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = modifier.alpha(0.45f)
        ) {
            Icon(Icons.Default.Cast, contentDescription = "Кастування недоступне")
        }
    }
}
