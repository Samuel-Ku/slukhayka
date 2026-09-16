package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * ADR-0049 / spec-54 T (#860) — the ONE gear every ROOT screen carries in its
 * header: «Налаштування» open with a single tap from anywhere in the roots,
 * and the bottom bar keeps only the working sections.
 *
 * It is a real 48 dp target with a spoken label — the same a11y contract as
 * the header's other actions, so TalkBack reaches it from every root.
 */
@Composable
fun AppSettingsGear(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "settings_gear"
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(AppDimens.TouchTarget).testTag(testTag)
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.a11y_open_settings)
        )
    }
}
