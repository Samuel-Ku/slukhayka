package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit = {},
    onSaveForBook: (() -> Unit)? = null,
    onSetDefault: (() -> Unit)? = null,
    onDismiss: () -> Unit = {}
) {
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceLg)
        ) {
            Text(
                text = "Швидкість відтворення",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = AppDimens.SpaceMd)
            )

            speeds.forEach { speed ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.TouchTarget)
                        .clickable {
                            onSpeedChange(speed)
                            onDismiss()
                        }
                        .testTag("speed_option_${speed}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (speed == currentSpeed),
                        onClick = {
                            onSpeedChange(speed)
                            onDismiss()
                        }
                    )
                    Spacer(Modifier.width(AppDimens.SpaceMd))
                    Text(
                        text = "${speed}x",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (onSaveForBook != null || onSetDefault != null) {
                Spacer(Modifier.height(AppDimens.SpaceLg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (onSaveForBook != null) {
                        TextButton(onClick = { onSaveForBook(); onDismiss() }) {
                            Text("Зберегти для книги")
                        }
                    }
                    if (onSetDefault != null) {
                        TextButton(onClick = { onSetDefault(); onDismiss() }) {
                            Text("За замовчуванням")
                        }
                    }
                }
            }
        }
    }
}
