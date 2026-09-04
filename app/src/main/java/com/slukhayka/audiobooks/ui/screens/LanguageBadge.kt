package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * Spec-45 (#405) T7 (#495) — one `EN`/`UA` badge on a card whose rendition's
 * language is known (US3). Unknown languages (`""` / unnormalizable) render
 * NOTHING — the badge is the honest absence. The visible text is the
 * two-letter code; TalkBack announces the full language name via the
 * [contentDescription] (accessibility-announced per the ticket's AC).
 *
 * Pure `@Composable` so every card — feed row, search card, narration row —
 * shares one look and one a11y contract.
 */
@Composable
fun LanguageBadge(
    language: String,
    modifier: Modifier = Modifier
) {
    val code = LanguageCode.normalize(language) ?: return
    val name = when (code) {
        LanguageCode.UKRAINIAN -> stringResource(R.string.content_language_uk)
        LanguageCode.ENGLISH -> stringResource(R.string.content_language_en)
        else -> code
    }
    // The badge label is the two-letter code per the ticket (EN/UA) — NOT
    // the bare uppercase ("uk" would render "UK", never "UA").
    val label = when (code) {
        LanguageCode.UKRAINIAN -> "UA"
        LanguageCode.ENGLISH -> "EN"
        else -> code.uppercase()
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(AppDimens.RadiusXs),
        modifier = modifier.semantics { contentDescription = name }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}