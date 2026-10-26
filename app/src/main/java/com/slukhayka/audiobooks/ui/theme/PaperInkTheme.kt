package com.slukhayka.audiobooks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The «Папір і голос» theme, applied subtree-first while screens migrate.
 *
 * The direction is rolled out screen by screen (the way the previous design
 * system migrated `ListenScreen` first): a screen that has been rebuilt on the
 * new tokens is wrapped in this theme and the rest of the app keeps the legacy
 * one until its own migration lands. This avoids a "new fonts on old layout"
 * global switch that would make every un-migrated screen look broken instead of
 * unfinished.
 *
 * Two schemes, one hierarchy: [paperInkLightScheme] is the warm «Папір» day
 * theme, [paperInkDarkScheme] is the green-cast «Чорнило» night theme. The
 * player is intended to always use the dark one (product decision A) — screens
 * that need it can pass `darkTheme = true` regardless of the system setting.
 */
@Composable
fun PaperInkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme) {
        if (darkTheme) paperInkDarkScheme() else paperInkLightScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PaperInkType.typography,
        shapes = PaperInkShapes,
        content = content,
    )
}
