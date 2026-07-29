package com.example.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCardBg
import com.example.ui.theme.CyberPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentTimerMinutes: Int,
    onSelectTimer: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to "Вимкнено",
        5 to "5 хвилин",
        15 to "15 хвилин",
        30 to "30 хвилин",
        45 to "45 хвилин",
        60 to "60 хвилин",
        90 to "90 хвилин"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CyberCardBg,
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
                    tint = CyberPrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            options.forEach { (mins, label) ->
                val isSelected = currentTimerMinutes == mins

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) CyberPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
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
                        color = if (isSelected) CyberPrimary else MaterialTheme.colorScheme.onSurface
                    )

                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelectTimer(mins)
                            onDismiss()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = CyberPrimary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
