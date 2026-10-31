package com.slukhayka.audiobooks.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Material 3 Jetpack Glance Home Screen Widget (spec-21 Track B, restored as
 * spec-22 T4). Responsive layout supporting 4x2 (expanded) and 2x2 (compact):
 * cover/title/chapter, progress, play/pause and ±15 s transport.
 */
class AudiobookGlanceWidget : GlanceAppWidget() {

    companion object {
        private val SMALL_SIZE = DpSize(120.dp, 90.dp)
        private val MEDIUM_SIZE = DpSize(240.dp, 90.dp)
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Cold start / system refresh: compute the frame from live data once.
        // A live session reads the sampler's snapshot below instead — Glance
        // 1.1.1 does not re-run provideGlance for `updateAll` while active.
        val coldStartState = computeColdStartState(context)

        provideContent {
            val storedState = WidgetSnapshotCodec.read(currentState<Preferences>())
            GlanceTheme {
                WidgetContent(storedState ?: coldStartState)
            }
        }
    }

    private suspend fun computeColdStartState(context: Context): GlanceWidgetState =
        withContext(Dispatchers.IO) {
            try {
                val playerManager = App.instance.playerManager
                val playerState = playerManager.playerState.value
                if (playerState.currentBook != null) {
                    WidgetStateMapper.mapFromPlayerState(playerState)
                } else {
                    // Nothing loaded: resume the most recently listened book so
                    // the widget is never a dead control.
                    val progressList = App.instance.libraryEntries.recentProgress.first()
                    val latest = progressList.maxByOrNull { it.lastListenedAt }
                    if (latest != null) {
                        val book = App.instance.libraryEntries.getBookSync(latest.bookId)
                        val chapters = App.instance.sourceCatalog.getChaptersList(latest.bookId)
                        WidgetStateMapper.mapFromPlayerState(
                            playerState = playerState,
                            fallbackBook = book,
                            fallbackProgress = latest,
                            fallbackChapters = chapters
                        )
                    } else {
                        WidgetStateMapper.mapFromPlayerState(playerState)
                    }
                }
            } catch (e: Exception) {
                GlanceWidgetState()
            }
        }

    @Composable
    private fun WidgetContent(state: GlanceWidgetState) {
        val size = LocalSize.current
        val labels = widgetAccessibilityLabels(LocalContext.current, state)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .semantics { contentDescription = labels.openApp }
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            if (size.width < 220.dp) {
                CompactWidgetLayout(state, labels)
            } else {
                ExpandedWidgetLayout(state, labels)
            }
        }
    }

    @Composable
    private fun CompactWidgetLayout(
        state: GlanceWidgetState,
        labels: WidgetAccessibilityLabels
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = state.title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = if (state.isPlaying) "Відтворюється" else state.chapterTitle,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp
                )
            )
            if (state.showsTransportControls()) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TransportButton(
                        iconRes = R.drawable.ic_replay_10,
                        description = labels.rewind,
                        background = GlanceTheme.colors.surface,
                        iconSize = 20.dp,
                        action = actionRunCallback<Rewind15ActionCallback>()
                    )
                    Spacer(modifier = GlanceModifier.width(12.dp))
                    TransportButton(
                        iconRes = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                        description = labels.playPause,
                        background = GlanceTheme.colors.primary,
                        iconSize = 24.dp,
                        action = actionRunCallback<TogglePlayActionCallback>()
                    )
                }
            }
        }
    }

    @Composable
    private fun ExpandedWidgetLayout(
        state: GlanceWidgetState,
        labels: WidgetAccessibilityLabels
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Book Cover / App Icon
            Box(
                modifier = GlanceModifier
                    .size(56.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    // F23: the launcher foreground is ink-on-transparent; on the dark
                    // scheme's primaryContainer it measures 1.81:1.
                    // This plate carries its own paper ground (8.03:1 on ink,
                    // 14.52:1 ink-on-paper; the light plate blends but the glyph is 13.08:1).
                    provider = ImageProvider(R.drawable.ic_widget_app_icon),
                    contentDescription = null,
                    modifier = GlanceModifier.size(48.dp)
                )
            }

            Spacer(modifier = GlanceModifier.width(12.dp))

            // Info & Controls
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "${state.author} • ${state.chapterTitle}",
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.outline,
                        fontSize = 11.sp
                    )
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = state.progressFraction,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surface
                )

                Spacer(modifier = GlanceModifier.height(4.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.positionFormatted} / ${state.durationFormatted}",
                        style = TextStyle(
                            color = GlanceTheme.colors.outline,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            if (state.showsTransportControls()) {
                Spacer(modifier = GlanceModifier.width(8.dp))

                // Transport Control Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransportButton(
                        iconRes = R.drawable.ic_replay_10,
                        description = labels.rewind,
                        background = GlanceTheme.colors.surface,
                        iconSize = 20.dp,
                        action = actionRunCallback<Rewind15ActionCallback>()
                    )

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    TransportButton(
                        iconRes = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                        description = labels.playPause,
                        background = GlanceTheme.colors.primary,
                        iconSize = 24.dp,
                        action = actionRunCallback<TogglePlayActionCallback>()
                    )

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    TransportButton(
                        iconRes = R.drawable.ic_forward_10,
                        description = labels.forward,
                        background = GlanceTheme.colors.surface,
                        iconSize = 20.dp,
                        action = actionRunCallback<FastForward15ActionCallback>()
                    )
                }
            }
        }
    }

    /**
     * #1749 — the accessible name belongs on the 48 dp click target, not on the
     * inner glyph: the container is what TalkBack focuses and what receives the
     * tap. The image is decorative.
     */
    @Composable
    private fun TransportButton(
        iconRes: Int,
        description: String,
        background: ColorProvider,
        iconSize: Dp,
        action: Action
    ) {
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .background(background)
                .cornerRadius(24.dp)
                .semantics { contentDescription = description }
                .clickable(action),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                modifier = GlanceModifier.size(iconSize)
            )
        }
    }
}

/**
 * An empty widget has nothing to play, so it must not offer transport buttons
 * that do nothing. The cold-start path shows the last listened book instead,
 * so this is the genuinely-empty case.
 */
internal fun GlanceWidgetState.showsTransportControls(): Boolean = hasActiveBook

internal data class WidgetAccessibilityLabels(
    val openApp: String,
    val rewind: String,
    val playPause: String,
    val forward: String
)

internal fun widgetAccessibilityLabels(
    context: Context,
    state: GlanceWidgetState
): WidgetAccessibilityLabels = WidgetAccessibilityLabels(
    openApp = context.getString(R.string.a11y_widget_open_app),
    rewind = context.getString(R.string.a11y_widget_rewind_work, state.title),
    playPause = context.getString(
        if (state.isPlaying) R.string.a11y_widget_pause_work else R.string.a11y_widget_play_work,
        state.title
    ),
    forward = context.getString(R.string.a11y_widget_forward_work, state.title)
)
