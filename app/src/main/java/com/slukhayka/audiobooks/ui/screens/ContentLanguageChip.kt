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

/** Flags describe the Edition language preference, never the interface locale. */
@Composable
internal fun ContentLanguageChip(languages: Set<String>, onClick: () -> Unit) {
    val (symbol, nameRes) = when (languages) {
        setOf("uk") -> "🇺🇦" to R.string.content_language_uk
        setOf("en") -> "🇬🇧" to R.string.content_language_en
        else -> "🌐" to R.string.content_language_all
    }
    val description = stringResource(R.string.content_language_chip_label, stringResource(nameRes))
    // The symbol is an icon: keep its physical size while the accessible name scales normally.
    val iconSize = with(LocalDensity.current) { 24.dp.toSp() }
    FilterChip(
        selected = nameRes != R.string.content_language_all,
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
