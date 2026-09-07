package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.slukhayka.audiobooks.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentTimerMinutes: Int,
    isEndOfChapter: Boolean = false,
    remainingSeconds: Int = 0,
    onSelectTimer: (Int) -> Unit,
    onExtendTimer: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // v1.4 (ADR-0033): the option labels ride resources, not code.
    val options = listOf(
        0 to stringResource(R.string.timer_off),
        -1 to stringResource(R.string.timer_until_chapter_end),
        5 to stringResource(R.string.timer_minutes, 5),
        15 to stringResource(R.string.timer_minutes, 15),
        30 to stringResource(R.string.timer_minutes, 30),
        45 to stringResource(R.string.timer_minutes, 45),
        60 to stringResource(R.string.timer_minutes, 60),
        90 to stringResource(R.string.timer_minutes, 90)
    )
    val headingFocusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val paneTitle = stringResource(R.string.a11y_timer_pane)
    val closeDescription = stringResource(R.string.a11y_timer_close)
    val extendDescription = stringResource(R.string.a11y_timer_extend)
    val activeLabel = stringResource(R.string.timer_active)
    val chapterEndCountdown = stringResource(R.string.timer_until_chapter_end_countdown, remainingSeconds / 60, remainingSeconds % 60)
    val remainingCountdown = stringResource(R.string.timer_remaining_countdown, remainingSeconds / 60, remainingSeconds % 60)
    val currentMode = options.firstOrNull { it.first == currentTimerMinutes }?.second
        ?: if (remainingSeconds > 0) activeLabel else options.first().second

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // MD3: modal bottom sheet = surfaceContainerLow (tonal elevation).
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("sleep_timer_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = paneTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .focusRequester(headingFocusRequester)
                            .focusable()
                            .semantics {
                                heading()
                                stateDescription = currentMode
                            }
                            .testTag("sleep_timer_heading")
                    )
                    if (remainingSeconds > 0) {
                        Text(
                            text = if (isEndOfChapter) chapterEndCountdown else remainingCountdown,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .testTag("sleep_timer_countdown")
                                .clearAndSetSemantics { hideFromAccessibility() }
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = closeDescription)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .testTag("sleep_timer_options")
            ) {
                options.forEach { (mins, label) ->
                    val isSelected = currentTimerMinutes == mins

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusCard))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f))
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelectTimer(mins)
                                    onDismiss()
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("sleep_timer_option_$mins"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )

                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            modifier = Modifier
                                .testTag("sleep_timer_radio_$mins")
                                .clearAndSetSemantics { hideFromAccessibility() },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            if (remainingSeconds > 0) {
                Button(
                    onClick = onExtendTimer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = extendDescription }
                        .testTag("extend_sleep_timer_button")
                ) {
                    Text(extendDescription, modifier = Modifier.clearAndSetSemantics { })
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
