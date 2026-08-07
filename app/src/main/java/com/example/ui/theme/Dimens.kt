package com.example.ui.theme

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

    // Radii — cards sit in the 10–14 dp band
    val RadiusInner = 8.dp
    val RadiusCard = 12.dp
    val RadiusHero = 20.dp

    // Touch targets (Android accessibility: ≥ 48 dp)
    val TouchTarget = 48.dp
}
