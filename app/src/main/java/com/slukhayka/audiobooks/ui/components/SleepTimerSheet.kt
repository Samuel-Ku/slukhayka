package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.slukhayka.audiobooks.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentTimerMinutes: Int,
    isEndOfChapter: Boolean = false,
    remainingSeconds: Int = 0,
    onSelectTimer: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to "Вимкнено",
        -1 to "До кінця розділу",
        5 to "5 хвилин",
        15 to "15 хвилин",
        30 to "30 хвилин",
        45 to "45 хвилин",
        60 to "60 хвилин",
        90 to "90 хвилин"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // MD3: modal bottom sheet = surfaceContainerLow (tonal elevation).
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("sleep_timer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                Column {
                    Text(
                        text = "Таймер сну",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (remainingSeconds > 0) {
                        val min = remainingSeconds / 60
                        val sec = remainingSeconds % 60
                        Text(
                            text = if (isEndOfChapter) "До кінця розділу: %d:%02d".format(min, sec)
                            else "Залишилось: %d:%02d".format(min, sec),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            options.forEach { (mins, label) ->
                val isSelected = currentTimerMinutes == mins

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusCard))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f))
                        .clickable {
                            onSelectTimer(mins)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                        onClick = {
                            onSelectTimer(mins)
                            onDismiss()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
