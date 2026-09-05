package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.slukhayka.audiobooks.R

@Composable
fun CrashReportingConsentDialog(
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        // A tap outside must not turn an undecided choice into an accidental
        // refusal. Both outcomes are explicit, named actions.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.crash_reporting_prompt_title)) },
        text = { Text(stringResource(R.string.crash_reporting_prompt_body)) },
        confirmButton = {
            TextButton(
                onClick = onAllow,
                modifier = Modifier.testTag("crash_reporting_allow")
            ) {
                Text(stringResource(R.string.crash_reporting_allow))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDeny,
                modifier = Modifier.testTag("crash_reporting_deny")
            ) {
                Text(stringResource(R.string.crash_reporting_deny))
            }
        },
        modifier = Modifier.testTag("crash_reporting_consent_dialog")
    )
}

@Composable
fun CrashReportingSettingsSection(
    allowed: Boolean,
    onAllowedChange: (Boolean) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.crash_reporting_section_title),
            modifier = Modifier.semantics { heading() }
        )
        SettingsSwitchRow(
            title = stringResource(R.string.crash_reporting_switch_title),
            description = stringResource(R.string.crash_reporting_switch_description),
            checked = allowed,
            onCheckedChange = onAllowedChange,
            testTag = "crash_reporting_switch"
        )
    }
}
