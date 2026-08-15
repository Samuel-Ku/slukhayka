package com.example.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import com.example.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec-17 (#110): the single 4×2 home-screen widget.
 *
 * Layout: cover placeholder (app icon) · title · progress bar · prev/play/next.
 * The book area (cover + title) opens the full player; the buttons send
 * transport commands through the MediaSession in place.
 *
 * The widget's single source of truth is the MediaSession hosted by
 * [com.example.player.PlaybackService]: state changes push instantly through
 * the session's [Player.Listener] events, and the progress bar ticks on the
 * pure [WidgetUpdatePolicy] cadence — 15 s while playing, nothing while
 * paused/stopped (battery guarantee).
 */
class HomeScreenWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val snapshot by produceState<SessionSnapshot?>(initialValue = null) {
                liveSessionSnapshot(context)
            }
            HomeScreenWidgetContent(snapshot = snapshot)
        }
    }

    /**
     * Subscribes to the session for the composition's lifetime:
     *  - emits the current state immediately;
     *  - re-emits on every session event, gated by the pure refresh policy;
     *  - re-reads the position on the policy's cadence (15 s) while playing;
     *  - sleeps between events while paused/stopped — zero scheduled work.
     */
    private suspend fun ProduceStateScope<SessionSnapshot?>.liveSessionSnapshot(
        context: Context,
    ) {
        val controller = connect(context) ?: return
        value = SessionSnapshotReader.from(controller)
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                wakeups.trySend(Unit)
            }
        }
        controller.addListener(listener)
        try {
            var lastRefreshMs = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                val candidate = SessionSnapshotReader.from(controller)
                val candidateModel = WidgetModelMapper.map(candidate)
                val now = System.currentTimeMillis()
                if (WidgetUpdatePolicy.shouldRefresh(
                        value?.let { WidgetModelMapper.map(it) }, candidateModel, lastRefreshMs, now
                    )
                ) {
                    value = candidate
                    lastRefreshMs = now
                }
                val delayMs = WidgetUpdatePolicy.nextRefreshDelayMs(candidateModel, lastRefreshMs, now)
                if (delayMs == null) {
                    // Paused/stopped: no timer at all; wait for the next event.
                    wakeups.receive()
                } else {
                    withTimeoutOrNull(delayMs) { wakeups.receive() }
                }
            }
        } finally {
            controller.removeListener(listener)
            controller.release()
        }
    }
}

/** The widget's render tree; public so Glance's unit test can render it with a fixed snapshot. */
@Composable
fun HomeScreenWidgetContent(snapshot: SessionSnapshot?) {
    val context = LocalContext.current
    val model = snapshot?.let { WidgetModelMapper.map(it) }
    if (model?.hasBook == true) {
        BookRow(model, context)
    } else {
        PlaceholderRow(context)
    }
}

@Composable
private fun BookRow(model: WidgetModel, context: Context) {
    val openPlayer = actionStartActivity(WidgetIntents.openPlayerIntent(context))
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover placeholder (app icon) — tap opens the player.
        Box(
            modifier = GlanceModifier
                .size(56.dp)
                .background(WidgetColors.cover)
                .cornerRadius(12.dp)
                .clickable(openPlayer),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size(34.dp),
            )
        }
        Spacer(GlanceModifier.size(12.dp))
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = model.title ?: "",
                modifier = GlanceModifier.clickable(openPlayer),
                style = TextStyle(
                    color = WidgetColors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = model.progress,
                modifier = GlanceModifier.fillMaxWidth(),
                color = WidgetColors.primary,
                backgroundColor = WidgetColors.track,
            )
            Spacer(GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    action = actionRunCallback<PreviousChapterAction>(),
                    iconRes = R.drawable.ic_skip_previous,
                    contentDescription = context.getString(R.string.widget_previous),
                    modifier = GlanceModifier.defaultWeight().height(40.dp),
                )
                Spacer(GlanceModifier.size(6.dp))
                TransportButton(
                    action = actionRunCallback<PlayPauseAction>(),
                    iconRes = if (model.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    contentDescription = context.getString(
                        if (model.isPlaying) R.string.widget_pause else R.string.widget_play
                    ),
                    modifier = GlanceModifier.defaultWeight().height(40.dp),
                )
                Spacer(GlanceModifier.size(6.dp))
                TransportButton(
                    action = actionRunCallback<NextChapterAction>(),
                    iconRes = R.drawable.ic_skip_next,
                    contentDescription = context.getString(R.string.widget_next),
                    modifier = GlanceModifier.defaultWeight().height(40.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceholderRow(context: Context) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(44.dp)
                .background(WidgetColors.cover)
                .cornerRadius(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size(28.dp),
            )
        }
        Spacer(GlanceModifier.size(12.dp))
        Text(
            text = context.getString(R.string.widget_nothing_playing),
            style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = 14.sp),
        )
    }
}

@Composable
private fun TransportButton(
    action: Action,
    iconRes: Int,
    contentDescription: String,
    modifier: GlanceModifier,
) {
    Box(
        modifier = modifier
            .background(WidgetColors.control)
            .cornerRadius(20.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
        )
    }
}

/** Fixed palette — the widget must read well on any wallpaper (media-widget convention). */
private object WidgetColors {
    val surface = ColorProvider(Color(0xFF1C1B1F))
    val cover = ColorProvider(Color(0xFF2A2830))
    val control = ColorProvider(Color(0xFF34323B))
    val onSurface = ColorProvider(Color(0xFFF5F2F7))
    val onSurfaceVariant = ColorProvider(Color(0xFFC9C5D0))
    val primary = ColorProvider(Color(0xFFD0BCFF))
    val track = ColorProvider(Color(0xFF4A4458))
}