package com.slukhayka.audiobooks.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Design-system spacing and shape tokens (wayfinder #23).
 *
 * Rhythm per the product vision: 16–20 dp page sides, 24–32 dp between major
 * sections, 8–12 dp inside compact blocks. Cards are 10–14 dp radius with
 * minimal shadows; interactive targets are at least 48 dp.
 */
object AppDimens {
    // Spacing rhythm
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 20.dp
    val SpaceSection = 24.dp
    val SpaceSectionLg = 32.dp

    // Page sides (16–20 dp)
    val PageSides = 16.dp

    // Radii. Every radius the UI uses is a named token here (MD3: no magic
    // corner numbers); components reference these so shapes stay consistent
    // with theming.
    //
    // #885 (wave 1) — the values now follow the «Нічна бібліотека» spec
    // (спека:99): «постер 10–12 dp, головний блок 24 dp, форма/панель 24 dp,
    // основна кнопка повністю заокруглена». The prototype states the same two
    // numbers it can (`border-radius:10px` on a poster, `--sl-radius:24px` for
    // the main block). Before this, panels sat at 16 and the hero at 20 — a
    // value NEITHER source names, which is why the surfaces read as a slightly
    // different dialect rather than as the written design.
    val RadiusProgress = 2.dp   // progress-bar rounded caps
    val RadiusXs = 6.dp         // tiny badges / compact chips
    val RadiusInner = 8.dp      // chips, text fields, inner surfaces
    val RadiusCover = 10.dp     // small covers inside rows
    val RadiusCard = 12.dp      // standard cards, posters (spec band 10–12)
    val RadiusCardLg = 14.dp    // larger cards / list rows
    val RadiusPanel = 24.dp     // panels, sheets, forms (spec: 24 dp)
    val RadiusHero = 24.dp      // hero covers, dialogs (spec: main block 24 dp)

    /**
     * A fully-rounded action («основна кнопка повністю заокруглена», спека:99;
     * the prototype writes `border-radius:999px`). Large enough to exceed any
     * height the app gives a button, so the sides are semicircles at any size —
     * which is why it is a token rather than a per-call-site guess.
     */
    val RadiusPill = 999.dp

    // Touch targets (ADR-0044). The ENFORCED floor is 24 dp — WCAG 2.2 AA
    // «Target Size (Minimum)». 48 dp stays the recommended size for a
    // standalone action, and is kept for everything a driver uses (player
    // transport, bottom navigation, primary buttons): forcing 48 dp on dense
    // chrome — filter chips, section toggles, inline icon rows — is what made
    // the Медіатека look inflated.
    val TouchTarget = 48.dp
    val MinTouchTarget = 24.dp

    // v1.4 C5 (ADR-0033): the ONE clearance above the persistent mini player.
    // Scrollable content pads its bottom by this token instead of a scattered
    // hardcoded 120 dp — a mini-player height change edits one line, not a
    // dozen screens.
    //
    // UI: на екрані «Слухати» міні-плеєр сховано (там уже є картка
    // «Продовжити слухати»), тож той екран НЕ бере цей токен — він ставить
    // маленький відступ. Решта екранів лишають повний, бо панель у
    // `bottomBar` перекриває їм останній елемент.
    val SpaceAboveMiniPlayer = 60.dp
}
