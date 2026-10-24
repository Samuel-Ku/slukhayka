package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * #1188 — the explicit drag handle that sits BESIDE the «Вгору»/«Вниз» buttons.
 *
 * The decision of 2026-10-11 keeps both paths: only this handle starts a drag
 * (the rest of the row keeps scrolling as before), the arrows stay, and
 * TalkBack/keyboard get named "move up"/"move down" actions — a gesture alone
 * would not be accessible. The same composable is used by the import preview
 * and by [ChapterOrderDialog] so the two surfaces speak one language.
 *
 * The caller MUST key its rows by the chapter identity (or pass [rowHeightPx]
 * matching the real row) — a positional loop would let a continuing multi-step
 * gesture move the wrong chapter after the first live swap.
 */
@Composable
fun ChapterDragHandle(
    index: Int,
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    rowHeightPx: Float? = null,
    autoScroll: ChapterDragAutoScroll? = null,
) {
    val density = LocalDensity.current
    val stepPx = rowHeightPx ?: with(density) { 48.dp.toPx() }
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentAutoScroll by rememberUpdatedState(autoScroll)
    val scope = rememberCoroutineScope()
    var accumulated by remember { mutableFloatStateOf(0f) }
    var handleBounds by remember { mutableStateOf(Rect.Zero) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var focused by remember { mutableStateOf(false) }

    val handleLabel = stringResource(R.string.a11y_chapter_drag_handle)
    val moveUpLabel = stringResource(R.string.a11y_chapter_move_up)
    val moveDownLabel = stringResource(R.string.a11y_chapter_move_down)

    // Auto-scroll must NOT depend on pointer movement: holding the finger in
    // the edge zone keeps the frame loop scrolling until the drag ends.
    fun startAutoScroll() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive) {
                val scroll = currentAutoScroll
                if (scroll == null) break
                val direction = when {
                    pointerY < scroll.viewportTop + scroll.edgePx -> -1
                    pointerY > scroll.viewportBottom - scroll.edgePx -> 1
                    else -> 0
                }
                if (direction == 0) break
                scroll.scrollState.dispatchRawDelta(direction * scroll.stepPx)
                withFrameNanos { }
            }
        }
    }

    Surface(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { handleBounds = it.boundsInWindow() }
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .semantics {
                contentDescription = handleLabel
                customActions = listOf(
                    CustomAccessibilityAction(moveUpLabel) {
                        if (currentEnabled && currentIndex > 0) currentOnMove(currentIndex, currentIndex - 1)
                        true
                    },
                    CustomAccessibilityAction(moveDownLabel) {
                        if (currentEnabled && currentIndex < currentCount - 1) currentOnMove(currentIndex, currentIndex + 1)
                        true
                    }
                )
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !currentEnabled) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (currentIndex > 0) currentOnMove(currentIndex, currentIndex - 1); true
                    }
                    Key.DirectionDown -> {
                        if (currentIndex < currentCount - 1) currentOnMove(currentIndex, currentIndex + 1); true
                    }
                    else -> false
                }
            }
            .pointerInput(currentCount, stepPx) {
                detectDragGestures(
                    onDragStart = { position ->
                        accumulated = 0f
                        pointerY = handleBounds.top + position.y
                        startAutoScroll()
                    },
                    onDragEnd = {
                        accumulated = 0f
                        scrollJob?.cancel()
                        scrollJob = null
                    },
                    onDragCancel = {
                        accumulated = 0f
                        scrollJob?.cancel()
                        scrollJob = null
                    }
                ) { change, dragAmount ->
                    if (!currentEnabled) return@detectDragGestures
                    change.consume()
                    pointerY = handleBounds.top + change.position.y
                    accumulated += dragAmount.y
                    val steps = (accumulated / stepPx).toInt()
                    if (steps != 0) {
                        val from = currentIndex
                        val to = (from + steps).coerceIn(0, currentCount - 1)
                        if (to != from) {
                            currentOnMove(from, to)
                            accumulated -= steps * stepPx
                        } else {
                            accumulated = 0f
                        }
                    }
                    // Re-evaluate the edge zone as the finger moves; the loop
                    // itself keeps scrolling while the finger is still.
                    if (abs(accumulated) < stepPx && currentAutoScroll != null) startAutoScroll()
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (focused) 0.9f else 0.35f),
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Where [ChapterDragHandle] may auto-scroll while a drag touches the edge. */
class ChapterDragAutoScroll(
    val scrollState: ScrollState,
    viewportTop: Float,
    viewportBottom: Float,
    val edgePx: Float,
    val stepPx: Float
) {
    var viewportTop: Float = viewportTop
        private set
    var viewportBottom: Float = viewportBottom
        private set

    fun update(top: Float, bottom: Float) {
        viewportTop = top
        viewportBottom = bottom
    }
}
