package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/**
 * Canonical settings switch row. The row owns the only toggleable semantics;
 * the visual [Switch] is deliberately silent so TalkBack never lands on a
 * duplicate control without the explanation beside it.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
    checkedStateDescription: String = stringResource(R.string.settings_state_on),
    uncheckedStateDescription: String = stringResource(R.string.settings_state_off)
) {
    val currentState = if (checked) checkedStateDescription else uncheckedStateDescription
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag(testTag)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics(mergeDescendants = true) {
                stateDescription = currentState
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

/** One whole-row radio target; place siblings inside `Modifier.selectableGroup()`. */
@Composable
fun SettingsRadioOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val selectedDescription = stringResource(
        if (selected) R.string.settings_state_selected else R.string.settings_state_not_selected
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag(testTag)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics(mergeDescendants = true) {
                stateDescription = selectedDescription
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Destructive recommendation reset with a named modal and deterministic focus return. */
@Composable
fun RecommendationResetControl(
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onDialogVisibilityChange: (Boolean) -> Unit = {}
) {
    var confirmReset by remember { mutableStateOf(false) }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    val triggerFocusRequester = remember { FocusRequester() }
    val dialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(confirmReset) {
        if (!confirmReset && shouldRestoreFocus) {
            triggerFocusRequester.requestFocus()
            shouldRestoreFocus = false
        }
    }

    Column(
        modifier = modifier.accessibilityModalBackground(confirmReset)
    ) {
        TextButton(
            onClick = {
                shouldRestoreFocus = true
                confirmReset = true
                onDialogVisibilityChange(true)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .focusRequester(triggerFocusRequester)
                .testTag("recommendation_reset_button"),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(stringResource(R.string.recommendations_reset_action))
        }
        Text(
            text = stringResource(R.string.recommendations_reset_consequence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (confirmReset) {
        val title = stringResource(R.string.recommendations_reset_dialog_title)
        AlertDialog(
            onDismissRequest = {
                confirmReset = false
                onDialogVisibilityChange(false)
            },
            modifier = Modifier
                .testTag("recommendation_reset_dialog")
                .accessibilityPane(title),
            title = {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    dialogFocusRequester.requestFocus()
                }
                Text(
                    title,
                    modifier = Modifier
                        .focusRequester(dialogFocusRequester)
                        .focusable()
                        .testTag("recommendation_reset_dialog_heading")
                        .semantics { heading() }
                )
            },
            text = { Text(stringResource(R.string.recommendations_reset_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        confirmReset = false
                        onDialogVisibilityChange(false)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("recommendation_reset_confirm")
                ) {
                    Text(stringResource(R.string.recommendations_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onDialogVisibilityChange(false)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
