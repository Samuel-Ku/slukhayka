package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.AppLocale
import com.slukhayka.audiobooks.AppLocalePrefs
import com.slukhayka.audiobooks.R

/**
 * Spec-45 (#405) R7 (#514) — the «Мова інтерфейсу» destination: a three-value
 * radio (Система / Українська / English). The choice applies IMMEDIATELY
 * through [onApply] (the platform applier: LocaleManager on API 33+, an
 * Activity recreation on older Android) — no manual restart, no reinstall —
 * and only touches the App Locale: content languages, the library and
 * Listening State are independent (US15). A new install defaults to SYSTEM.
 *
 * The screen reads the module's current value and reports the selection
 * through [onApply]; the module keeps the persisted store and the applier
 * does the platform work (ADR-0008 — no ViewModel forwarders; the ViewModel
 * only owns navigation).
 */
@Composable
fun AppLocaleScreen(
    localePrefs: AppLocalePrefs,
    onApply: (AppLocale) -> Unit,
    onBackClick: () -> Unit
) {
    // Local selection state only: applying re-creates the Activity (API 33+
    // via LocaleManager, older via recreate), which re-reads the store.
    var current by remember { mutableStateOf(localePrefs.locale) }

    fun choose(locale: AppLocale) {
        current = locale
        onApply(locale)
    }

    SettingsDestinationScaffold(
        destination = SettingsDestination.AppLocale,
        onBackClick = onBackClick
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("app_locale_screen")
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.app_locale_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.selectableGroup()) {
                SettingsRadioOption(
                    title = stringResource(R.string.app_locale_system),
                    description = "",
                    selected = current == AppLocale.SYSTEM,
                    onSelect = { choose(AppLocale.SYSTEM) },
                    testTag = "app_locale_system_option"
                )
                SettingsRadioOption(
                    title = stringResource(R.string.app_locale_ukrainian),
                    description = "",
                    selected = current == AppLocale.UKRAINIAN,
                    onSelect = { choose(AppLocale.UKRAINIAN) },
                    testTag = "app_locale_ukrainian_option"
                )
                SettingsRadioOption(
                    title = stringResource(R.string.app_locale_english),
                    description = "",
                    selected = current == AppLocale.ENGLISH,
                    onSelect = { choose(AppLocale.ENGLISH) },
                    testTag = "app_locale_english_option"
                )
            }
        }
    }
}
