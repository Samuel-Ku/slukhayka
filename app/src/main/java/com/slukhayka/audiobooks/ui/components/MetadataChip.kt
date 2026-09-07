package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * v1.4 C4 (ADR-0033) — the ONE non-interactive metadata chip; the
 * language/source/plain slots replace `LanguageBadge`, `SourceBadgePill`,
 * `TagPill` and the recommendation-reason surfaces. A chip states a fact
 * about the card it rides on; it is never clickable (actions live on the
 * card or in ⋮, «one tool, one place»).
 *
 * Slots (first non-null wins):
 * - [language] — the rendition's BCP-47 tag rendered as the `EN`/`UA` code
 *   (spec-45 #495); unknown/unnormalizable renders NOTHING — the honest
 *   absence (US3). TalkBack announces the full language name.
 * - [source] — provenance: which source carries this card (spec-10 T4).
 * - [text] — a plain fact (genre, chapter count, duration, the
 *   recommendation reason). Optional [border] for the outlined variant.
 *
 * Pure `@Composable` so every surface — feed row, search card, book page —
 * shares one look and one a11y contract.
 */
@Composable
fun MetadataChip(
    modifier: Modifier = Modifier,
    language: String? = null,
    source: String? = null,
    text: String? = null,
    border: BorderStroke? = null
) {
    if (language != null) {
        val code = LanguageCode.normalize(language) ?: return
        val name = when (code) {
            LanguageCode.UKRAINIAN -> stringResource(R.string.content_language_uk)
            LanguageCode.ENGLISH -> stringResource(R.string.content_language_en)
            else -> code
        }
        // The chip label is the two-letter code per the ticket (EN/UA) — NOT
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
        return
    }
    if (source != null) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(AppDimens.RadiusXs),
            modifier = modifier
        ) {
            Text(
                text = source,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        return
    }
    if (text != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(AppDimens.RadiusXs),
            modifier = modifier.then(
                if (border != null) {
                    Modifier.border(border, RoundedCornerShape(AppDimens.RadiusXs))
                } else {
                    Modifier
                }
            )
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
