package com.slukhayka.audiobooks.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Design-system palette (wayfinder #23 — Design system).
//
// Dark is the primary theme: deep graphite with a navy cast — never pure
// black. Light is a warm near-white "paper". Exactly one brand accent (warm
// amber). Cover artwork is used only as delicate player decoration, never for
// per-screen recolouring.
// ─────────────────────────────────────────────────────────────────────────────

// Dark (graphite-navy)
val AppBgDark = Color(0xFF111318)
val AppSurfaceDark = Color(0xFF191C23)
val AppCardDark = Color(0xFF242832)
val AppBorderDark = Color(0xFF2E3440)
val AppTextPrimaryDark = Color(0xFFF1EDE5)
val AppTextMutedDark = Color(0xFFB9B1A5)
val AppAccentDark = Color(0xFFE9A13B)          // warm amber — the one brand accent
val AppOnAccentDark = Color(0xFF241A00)
val AppAccentContainerDark = Color(0xFF3A2E19)
val AppOnAccentContainerDark = Color(0xFFF5E3BD)

// Light (warm near-white "paper")
val AppBgLight = Color(0xFFFAF6EE)
val AppSurfaceLight = Color(0xFFFFFFFF)
val AppCardLight = Color(0xFFF0EADC)
val AppBorderLight = Color(0xFFDDD3C0)
val AppTextPrimaryLight = Color(0xFF211D17)
val AppTextMutedLight = Color(0xFF6E6557)
val AppAccentLight = Color(0xFF835800)
val AppOnAccentLight = Color(0xFFFFFFFF)
val AppAccentContainerLight = Color(0xFFF5E3BD)
val AppOnAccentContainerLight = Color(0xFF4A3200)

// ── Cover-badge scrims (spec-22 T1/T2, ported from the reverted 2026-08-15
// typography spec) ────────────────────────────────────────────────────────────
// Solid high-contrast pill backgrounds for rating badges and genre tags laid
// over cover art, so they stay WCAG AA regardless of the artwork's luminance.
// Border keeps the pill separable from busy covers.
val AppBadgeScrim = Color(0xFF1A1721)      // solid scrim — never translucent
val AppBadgeScrimBorder = Color(0xFF3A3444)

// ── Semantic stat colours (listening stats cards) ──────────────────────────
// Named tokens so screens never hardcode literal Color() values; decorative
// accents for stat tiles, not brand colours.
val AppStatStreak = Color(0xFFFF9800)   // "Серія днів" — warm orange
val AppStatLibrary = Color(0xFF4CAF50)  // "Всього в бібліотеці" — green

// ── Debug-overlay tokens ───────────────────────────────────────────────────
// The player diagnostic overlay is the only screen that intentionally uses a
// denser, terminal-like palette. Kept as named tokens (never literal Color()
// in screens) so the whole palette lives in this one file.
val AppDebugOk = Color(0xFF00E676)      // status: playing / ok
val AppDebugWarn = Color(0xFFFFAB00)    // status: buffering / warning
val AppDebugError = Color(0xFFFF5252)   // status: idle / error
val AppDebugPanel = Color(0xFF10141D)   // overlay card background
val AppDebugPanelInner = Color(0xFF0A0D14) // source-url surface

// ── Legacy aliases ──────────────────────────────────────────────────────────
// The pre-design-system "Cyber*" constants, kept so existing screens compile
// and pick up the new dark palette unchanged. Migrate screens to scheme roles
// (MaterialTheme.colorScheme) as the stage-1 tickets land; do not add new
// usages.
val CyberBg = AppBgDark
val CyberSurface = AppSurfaceDark
val CyberSurfaceVariant = AppCardDark
val CyberPrimary = AppAccentDark
val CyberOnPrimary = AppOnAccentDark
val CyberSecondary = AppTextMutedDark
val CyberOnSecondary = AppOnAccentDark
val CyberAccent = AppAccentDark
val CyberTextPrimary = AppTextPrimaryDark
val CyberTextSecondary = AppTextMutedDark
val CyberCardBg = AppCardDark
val CyberCardBorder = AppBorderDark

/**
 * #885 (wave 3) — the poster's inner edge, replacing the 1 dp ring the
 * prototype never had. `#FFFFFF12` is the prototype's own value: white at
 * ~7 % alpha, which reads as "the cover catches a little light" rather than as
 * a drawn line. It lives HERE, in the palette, because
 * `HardcodedColorGuardTest` refuses colour anywhere else — and it is right to:
 * a colour that only exists at one call site cannot be reviewed, themed or
 * contrasted. It is scheme-independent on purpose (it sits on artwork, not on
 * a surface), which is why it is not part of a light/dark pair.
 */
val AppPosterEdgeHighlight = Color.White.copy(alpha = 0.07f)

// ─────────────────────────────────────────────────────────────────────────────
// #885 (wave 3) — the HERO panel palette.
//
// The prototype's hero is not a card on the app's surface: it is a full-width
// gradient panel (`.sl-hero`, prototype :1078-1090) that carries its OWN text
// colours, because its background is a warm brown-black rather than
// `AppSurface*`. A panel with its own ground needs its own foreground, or the
// theme's `onSurface` would be measured against the wrong backdrop.
//
// Contrast measured against the panel base `#302821` (WCAG 2.1, AA is 4.5:1):
// title 13.26:1 · eyebrow 10.20:1 · author 10.08:1 · accent 6.63:1. Every one
// clears the bar with room to spare, and `ThemeContrastTest` checks them.
//
// The light theme reuses the same panel palette on purpose: the hero is a
// "now playing" statement panel, and the prototype gives it one identity in
// both themes — it is a branded block, not a themed surface (ADR-0033: one
// vocabulary, and this role has one value).
// ─────────────────────────────────────────────────────────────────────────────

/** The hero panel's ground — the prototype's `#302821`. */
val AppHeroPanel = Color(0xFF302821)

/** Hero title and body text on [AppHeroPanel] — the prototype's `#FFF4DE`. */
val AppHeroOnPanel = Color(0xFFFFF4DE)

/** The hero's eyebrow ("ПРОДОВЖИТИ СЛУХАТИ") — the prototype's `#F1D5A5`. */
val AppHeroEyebrow = Color(0xFFF1D5A5)

/** Secondary hero text (author) on [AppHeroPanel] — the prototype's `#E1D6C5`. */
val AppHeroOnPanelMuted = Color(0xFFE1D6C5)

/** Hero progress track: white at 16 % (`#FFFFFF28`), never a theme outline. */
val AppHeroTrack = Color.White.copy(alpha = 0.16f)

/** Hero icon chip background: white at 9 % (`#FFFFFF16`). */
val AppHeroIconScrim = Color.White.copy(alpha = 0.09f)
