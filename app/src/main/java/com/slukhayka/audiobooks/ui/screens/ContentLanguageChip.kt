package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.facets.orderContentLanguages

/**
 * Flags describe the Edition language preference, never the interface locale.
 *
 * Spec-51 (#742) T3: the chip stopped cycling (two languages cycled; forty do
 * not) and became an honest STATE INDICATOR whose tap opens the one «Мови
 * контенту» destination — one tool, one place. Its symbol is the single
 * language's flag when exactly one is chosen, a globe for «Усі» (the empty
 * selection) and for any multi-language subset; the accessible name always
 * spells the selection out, so a partial choice is never silently "All".
 */
@Composable
internal fun ContentLanguageChip(languages: Set<String>, onClick: () -> Unit) {
    val ordered = orderContentLanguages(languages)
    val symbol = when (ordered.singleOrNull()) {
        "uk" -> "🇺🇦"
        "en" -> "🇬🇧"
        else -> "🌐"
    }
    val names = ordered.map { contentLanguageLabel(it) }
    val name = if (names.isEmpty()) {
        stringResource(R.string.content_language_all)
    } else {
        names.joinToString(", ")
    }
    val description = stringResource(R.string.content_language_chip_label, name)
    // The symbol is an icon: keep its physical size while the accessible name scales normally.
    val iconSize = with(LocalDensity.current) { 24.dp.toSp() }
    FilterChip(
        selected = ordered.isNotEmpty(),
        onClick = onClick,
        label = {
            Text(symbol, fontSize = iconSize, lineHeight = iconSize, maxLines = 1,
                modifier = Modifier.testTag("feed_language_symbol").clearAndSetSemantics {})
        },
        modifier = Modifier.size(width = 56.dp, height = 48.dp)
            .testTag("feed_language")
            .semantics { contentDescription = description }
    )
}
