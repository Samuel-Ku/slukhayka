package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * ADR-0018 — the canonical NAVIGATION chip: a filled pill with NO outline
 * that moves the user to another screen, as opposed to the outlined
 * `FilterChip` which filters the current list. The form difference IS the
 * signal (design guide §5.2): filled = «перейти», outlined = «фільтрувати»,
 * even when the labels match — «Роман» can legitimately be both a
 * NavigationChip (open the genre) and a FilterChip (narrow the feed).
 *
 * spec-28 (#198): the Огляд nav row (ТОП 100 / Виконавці / Автори / Серії /
 * Колекції) and the «Жанри» row navigate → they use this chip, never a
 * filter-shaped chip.
 */
@Composable
fun NavigationChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusCardLg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.testTag("navigation_chip_$title")
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
