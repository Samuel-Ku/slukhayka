package com.slukhayka.audiobooks.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ADR-0033 / spec-54 T10–T11 (#861/#862) — the measurable half of "readable":
 * the WCAG contrast ratio between a foreground and its background.
 *
 * It lives in the theme so BOTH the palette and the cover placeholder can be
 * held to the same number instead of someone's impression of a screenshot.
 * 4.5:1 is the floor for body text; 3:1 is the floor for large text and icons.
 */
object ColorContrast {

    const val TEXT_FLOOR = 4.5
    const val LARGE_TEXT_FLOOR = 3.0

    fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** The ratio, always ≥ 1: the brighter colour over the darker one. */
    fun ratio(a: Color, b: Color): Double {
        val first = relativeLuminance(a)
        val second = relativeLuminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun meetsTextFloor(foreground: Color, background: Color): Boolean =
        ratio(foreground, background) >= TEXT_FLOOR
}
