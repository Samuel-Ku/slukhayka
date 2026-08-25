package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.AudioPlayerManager

/**
 * Playback-speed bottom sheet (wayfinder #26): preset chips plus a precise
 * 0.5x–3.0x slider, and the two memory actions — save the speed for the
 * current book, or set it as the global default. The slider applies the speed
 * live; the memory actions persist it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onSaveForBook: () -> Unit,
    onSetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var sliderSpeed by remember { mutableStateOf(currentSpeed) }
    val headingFocusRequester = remember { FocusRequester() }
    val paneTitle = stringResource(R.string.a11y_speed_pane)
    val closeDescription = stringResource(R.string.a11y_speed_close)
    val exactSpeedDescription = stringResource(R.string.a11y_speed_exact)
    val exactSpeedState = stringResource(
        R.string.a11y_speed_preset,
        formatSpeedForSpeech(sliderSpeed)
    )

    // Explicit tonal role (matching the other sheets) instead of relying on
    // the component default.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("speed_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = paneTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .semantics { heading() }
                        .testTag("speed_sheet_heading")
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = closeDescription)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioPlayerManager.SPEED_PRESETS.forEach { preset ->
                    val presetSpeech = formatSpeedForSpeech(preset)
                    val presetDescription = stringResource(R.string.a11y_speed_preset, presetSpeech)
                    FilterChip(
                        selected = kotlin.math.abs(sliderSpeed - preset) < 0.01f,
                        onClick = {
                            sliderSpeed = preset
                            onSpeedChange(preset)
                        },
                        label = {
                            Text(
                                "${formatSpeed(preset)}x",
                                modifier = Modifier.clearAndSetSemantics { }
                            )
                        },
                        modifier = Modifier
                            .semantics { contentDescription = presetDescription }
                            .testTag("speed_preset_${formatSpeed(preset)}"),
                        // Unselected chips one tonal step above the sheet so
                        // they read as affordances (same as the timer rows).
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${formatSpeed(sliderSpeed)}x",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = sliderSpeed,
                onValueChange = {
                    sliderSpeed = it
                    onSpeedChange(it)
                },
                valueRange = AudioPlayerManager.SPEED_MIN..AudioPlayerManager.SPEED_MAX,
                steps = 9, // 0.25 increments between 0.5 and 3.0
                modifier = Modifier
                    .semantics {
                        contentDescription = exactSpeedDescription
                        stateDescription = exactSpeedState
                    }
                    .testTag("exact_speed_slider")
            )

            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onSetDefault,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Стандарт для всіх")
                }
                Button(
                    onClick = onSaveForBook,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Запам'ятати для цієї книги")
                }
            }
        }
    }
}

/** Formats a speed value with one or two decimals, e.g. 1.0 → "1.0", 1.25 → "1.25". */
private fun formatSpeed(speed: Float): String {
    val tenth = (speed * 10f).toInt()
    return if ((speed * 100f).toInt() % 10 == 0) {
        "${tenth / 10}.${tenth % 10}"
    } else {
        String.format(java.util.Locale.US, "%.2f", speed)
    }
}

private fun formatSpeedForSpeech(speed: Float): String =
    formatSpeed(speed).replace('.', ',')
