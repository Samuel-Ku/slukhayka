package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * [rowHeightPx] is the step: dragging one row-height past a neighbour swaps
 * positions live, so the list re-renders while the finger moves. [autoScroll]
 * lets the caller scroll its bounded, scrolled container when the pointer
 * reaches an edge — a long drag over dozens of chapters must not stall.
 */
@Composable
fun ChapterDragHandle(
    index: Int,
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    autoScroll: ChapterDragAutoScroll? = null,
    testTag: String? = null,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentAutoScroll by rememberUpdatedState(autoScroll)
    var accumulated by remember { mutableFloatStateOf(0f) }
    var handleBounds by remember { mutableStateOf(Rect.Zero) }

    val handleLabel = stringResource(R.string.a11y_chapter_drag_handle)
    val moveUpLabel = stringResource(R.string.a11y_chapter_move_up)
    val moveDownLabel = stringResource(R.string.a11y_chapter_move_down)

    Box(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { handleBounds = it.boundsInWindow() }
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
            .pointerInput(currentCount) {
                detectDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f }
                ) { change, dragAmount ->
                    if (!currentEnabled) return@detectDragGestures
                    change.consume()
                    accumulated += dragAmount.y
                    val steps = (accumulated / rowHeightPx).toInt()
                    if (steps != 0) {
                        val from = currentIndex
                        val to = (from + steps).coerceIn(0, currentCount - 1)
                        if (to != from) {
                            currentOnMove(from, to)
                            accumulated -= steps * rowHeightPx
                        }
                    }
                    val scroll = currentAutoScroll
                    if (scroll != null && abs(accumulated) < rowHeightPx) {
                        val pointerY = handleBounds.top + change.position.y
                        val direction = when {
                            pointerY < scroll.viewportTop + scroll.edgePx -> -1
                            pointerY > scroll.viewportBottom - scroll.edgePx -> 1
                            else -> 0
                        }
                        if (direction != 0) {
                            scroll.scrollState.dispatchRawDelta(direction * scroll.stepPx)
                        }
                    }
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
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
