package com.example.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.App
import com.example.MainActivity
import com.example.R
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
        val state = withContext(Dispatchers.IO) {
            try {
                val playerManager = App.instance.playerManager
                val playerState = playerManager.playerState.value
                if (playerState.currentBook != null) {
                    WidgetStateMapper.mapFromPlayerState(playerState)
                } else {
                    // Nothing loaded: resume the most recently listened book so
                    // the widget is never a dead control.
                    val progressList = App.instance.repository.recentProgress.first()
                    val latest = progressList.maxByOrNull { it.lastListenedAt }
                    if (latest != null) {
                        val book = App.instance.repository.getBookSync(latest.bookId)
                        val chapters = App.instance.repository.getChaptersList(latest.bookId)
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

        provideContent {
            GlanceTheme {
                WidgetContent(state)
            }
        }
    }

    @Composable
    private fun WidgetContent(state: GlanceWidgetState) {
        val size = LocalSize.current

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            if (size.width < 220.dp) {
                CompactWidgetLayout(state)
            } else {
                ExpandedWidgetLayout(state)
            }
        }
    }

    @Composable
    private fun CompactWidgetLayout(state: GlanceWidgetState) {
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
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(18.dp)
                        .clickable(actionRunCallback<Rewind15ActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_replay_10),
                        contentDescription = "Назад на 15 секунд",
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
                Spacer(modifier = GlanceModifier.width(12.dp))
                Box(
                    modifier = GlanceModifier
                        .size(40.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(20.dp)
                        .clickable(actionRunCallback<TogglePlayActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        ),
                        contentDescription = if (state.isPlaying) "Пауза" else "Грати",
                        modifier = GlanceModifier.size(24.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ExpandedWidgetLayout(state: GlanceWidgetState) {
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
                    provider = ImageProvider(R.drawable.ic_launcher_foreground),
                    contentDescription = state.title,
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

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Transport Control Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(18.dp)
                        .clickable(actionRunCallback<Rewind15ActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_replay_10),
                        contentDescription = "Назад на 15 секунд",
                        modifier = GlanceModifier.size(20.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(22.dp)
                        .clickable(actionRunCallback<TogglePlayActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        ),
                        contentDescription = if (state.isPlaying) "Пауза" else "Грати",
                        modifier = GlanceModifier.size(24.dp)
                    )
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(18.dp)
                        .clickable(actionRunCallback<FastForward15ActionCallback>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_forward_10),
                        contentDescription = "Вперед на 15 секунд",
                        modifier = GlanceModifier.size(20.dp)
                    )
                }
            }
        }
    }
}
