package com.example.ui.theme

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
val AppCardDark = Color(0xFF1F232B)
val AppBorderDark = Color(0xFF2E3440)
val AppTextPrimaryDark = Color(0xFFE9E6DF)
val AppTextMutedDark = Color(0xFFA9A39A)
val AppAccentDark = Color(0xFFE9A13B)          // warm amber — the one brand accent
val AppOnAccentDark = Color(0xFF241A00)
val AppAccentContainerDark = Color(0xFF3A2E14)
val AppOnAccentContainerDark = Color(0xFFF3D9A8)

// Light (warm near-white "paper")
val AppBgLight = Color(0xFFFAF6EE)
val AppSurfaceLight = Color(0xFFFFFFFF)
val AppCardLight = Color(0xFFF2ECDF)
val AppBorderLight = Color(0xFFDDD3C0)
val AppTextPrimaryLight = Color(0xFF211D17)
val AppTextMutedLight = Color(0xFF6E6557)
val AppAccentLight = Color(0xFF9A6A00)
val AppOnAccentLight = Color(0xFFFFFFFF)
val AppAccentContainerLight = Color(0xFFF7E6C3)
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
