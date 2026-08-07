package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.player.AudioPlayerManager

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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Швидкість відтворення",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioPlayerManager.SPEED_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = kotlin.math.abs(sliderSpeed - preset) < 0.01f,
                        onClick = {
                            sliderSpeed = preset
                            onSpeedChange(preset)
                        },
                        label = { Text("${formatSpeed(preset)}x") }
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
                steps = 9 // 0.25 increments between 0.5 and 3.0
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onSetDefault,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Стандарт для всіх")
                }
                Button(
                    onClick = onSaveForBook,
                    modifier = Modifier.weight(1f)
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
