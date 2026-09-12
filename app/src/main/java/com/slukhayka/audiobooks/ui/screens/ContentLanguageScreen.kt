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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.facets.ContentLanguagePrefs
import com.slukhayka.audiobooks.data.facets.orderContentLanguages

/**
 * Spec-45 (#405) T6 (#494), spec-51 (#742) T3 — the «Мови контенту»
 * destination: the «Усі» row plus one checkbox per language that actually has
 * content (plus any still-selected language, so a selection is never stranded
 * invisibly). The offered list is DATA — the catalogue's real languages, in
 * the ONE repo order — never a hardcoded pair. Every write goes straight to
 * the SAME persisted store the feed Pager and the SourceCatalog surfaces read
 * — no draft state, no separate save step; the Огляд «Мова» chip opens this
 * screen (spec-51 replaced its cycling with one obvious destination).
 *
 * «Усі» is the EMPTY selection (web parity): picking a language while «Усі»
 * is on narrows to that language, and unpicking the last one returns to «Усі»
 * — "nothing selected" is not a state, it is every language.
 *
 * Spec-45 (#405) R6 (#513): the screen receives the PREFERENCE MODULE itself
 * (ADR-0008 — screens read module flows and call module actions directly; no
 * ViewModel forwarders). Navigation to the destination stays in the ViewModel
 * ([MainViewModel.contentLanguagesOpen]).
 */
@Composable
fun ContentLanguageScreen(
    prefs: ContentLanguagePrefs,
    availableLanguages: List<String>,
    onBackClick: () -> Unit
) {
    val contentLanguages by prefs.languages.collectAsState()
    val offered = remember(availableLanguages, contentLanguages) {
        orderContentLanguages(availableLanguages + contentLanguages)
    }

    fun toggle(tag: String, checked: Boolean) {
        val current = contentLanguages
        val next = when {
            // From «Усі» (empty) an explicit pick means exactly that language.
            current.isEmpty() -> setOf(tag)
            checked -> current + tag
            // Unpicking the last language lands back on «Усі».
            else -> current - tag
        }
        prefs.setLanguages(next)
    }

    SettingsDestinationScaffold(
        destination = SettingsDestination.ContentLanguages,
        onBackClick = onBackClick
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("content_languages_screen")
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
                        text = stringResource(R.string.content_languages_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ContentLanguageRow(
                tag = "content_language_all_checkbox",
                label = stringResource(R.string.content_language_all),
                checked = contentLanguages.isEmpty(),
                // «Нічого не обрано» is not a state — the store normalizes an
                // empty write back to «Усі», so unpicking this row is a no-op
                // rather than a silent flip of meaning.
                onCheckedChange = { checked -> if (checked) prefs.setLanguages(emptySet()) }
            )
            offered.forEach { tag ->
                ContentLanguageRow(
                    tag = "content_language_${tag}_checkbox",
                    label = contentLanguageLabel(tag),
                    checked = tag in contentLanguages,
                    onCheckedChange = { checked -> toggle(tag, checked) }
                )
            }
        }
    }
}

@Composable
private fun ContentLanguageRow(
    tag: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
    }
}
