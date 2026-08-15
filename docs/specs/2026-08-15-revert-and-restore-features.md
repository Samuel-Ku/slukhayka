# [Spec-22] Відкат регресу паралельної сесії + повернення UI-фіч (typography, Explore redesign, widget, sleep timer)

> **Status:** Draft — synthesized from the 2026-08-15 grilling (user delegated the interview; recommendations locked). Pending tickets on GitHub.
> **Tracker:** filed as issue (labels `spec-22`, `ready-for-agent`).

## Problem Statement

A parallel session pushed 4 commits (`696066b`..`5c5d436`) to `main` that rewrote the app on top of a state where the multi-source catalog, global search, smart import, identity merge, diagnostics, and the spec-19 ONNX recommendation row had been deleted (~35k lines, incl. all `docs/wayfinder`). CI is red on all 4 commits — the first step fails at Gradle wrapper validation, so none of that state ever compiled or ran a test. The pre-change state `addfc01` is green (CI run `31804056375`).

However, the same session also produced three well-scoped UI specs that are worth keeping — a typography/high-contrast visual system, an Explore tab redesign (collapsible search, genre-aware cover fallbacks, haptics), and Spec-21 system integrations (Glance widget, sleep-timer polish). The user wants the regression reverted and those features restored on top of the current healthy codebase — everything except Android Auto (built on the deleted architecture) and the resurrected in-app browser surface.

## Solution

1. **Revert** the four commits so the tree returns exactly to `addfc01` (green, verified CI), pushed to `main` as a normal revert commit (history preserved, no force-push).
2. **Port the clean, conflict-free pieces** from the reverted range: root `metadata.json` («Слухайка» branding), `rootProject.name = "slukhayka"`, and the `Type.kt` / `Color.kt` design tokens (M3 typography scale + WCAG AA/AAA contrast palette).
3. **Re-implement the typography pass on the current codebase** (≥11sp minimum labels, tabular figures for timers, high-contrast cover badges) — the other session's HomeScreen/LibraryScreen were rewrites of the deleted state, so their UI code is not portable as-is; the spec is the deliverable.
4. **Re-implement the Explore redesign in the current IA** (no tab reshuffle): collapsible search (icon → field + chips, ✕/Back dismisses), mood chips, genre-aware gradient cover fallbacks when Cloudflare blocks covers, micro-haptics. «Продовжити слухати» and the «Нове з джерел» feeds stay on the Слухати tab.
5. **Add the Glance home-screen widget** (Spec-21 Track B): 4x2 / 2x2 responsive, cover + title + chapter + progress, play/pause, ±15 s.
6. **Add sleep-timer polish** (Spec-21 Track C): «До кінця розділу» mode, 30 s volume fade before expiry, shake-to-extend (+15 min) with haptic tick.

Each step is its own commit with tests and a CI gate (assembleDebug + testDebugUnitTest + Kover).

## User Stories

1. As a user, I want the app back to its healthy, green state, so that nothing I rely on (multi-source catalog, search, recommendations) regresses.
2. As a user, I want every label and badge readable at small sizes, so that audiobook UI works in dim light and with font scaling.
3. As a user, I want playback timers not to jitter horizontally, so that counting seconds does not shift the layout.
4. As a user, I want the Explore header clean with a collapsible search, so that catalogue rows are visible before search controls.
5. As a user, I want search to dismiss with ✕ or the system Back, so that leaving search never strands me.
6. As a user, I want genre-aware fallback cover art when a cover is blocked, so that the catalogue never shows grey placeholders.
7. As a user, I want subtle haptics on key actions, so that the app feels tactile without being noisy.
8. As a user, I want a home-screen widget with progress, play/pause and ±15 s, so that I control playback without opening the app.
9. As a user, I want a «До кінця розділу» sleep mode and a shake-to-extend gesture, so that I can fall asleep without losing my place.
10. As a maintainer, I want each restored feature to land as its own commit with green CI, so that regressions are attributable.

## Implementation Decisions

- **Revert, not force-push.** `git revert 696066b..5c5d436` produces one revert commit whose tree equals `addfc01`; the reverted range stays in history for reference. No history rewrite on the shared remote.
- **Port scope from the reverted range:** root `metadata.json`, `settings.gradle.kts` (`rootProject.name = "slukhayka"`), and the typography/contrast design tokens only. Everything else from the range is discarded (its data-layer rewrite, its screen rewrites, its downgrade of onnxruntime to 1.18.0, the Gradle wrapper jar swap that broke CI, the deleted docs).
- **Typography is a re-implementation on the current code**, guided by the other session's spec (2026-08-15-typography-and-contrast-system): minimum 11sp for labels/badges, M3 type scale, tabular figures for timers, high-contrast solid pills on covers (WCAG AA/AAA on `CyberTextPrimary`/`CyberTextSecondary`).
- **Explore redesign is a re-implementation on the current HomeScreen** with the existing multi-source rows intact: collapsible search field with `FocusRequester`, BackHandler + ✕ dismissal, mood/genre chips revealed with search, genre-mapped gradient fallbacks in the cover component, micro-haptics. No moving «Продовжити слухати» or the library between tabs.
- **Widget (Spec-21 Track B):** `androidx.glance:glance-appwidget` + `glance-material3`; a `GlanceAppWidgetReceiver` observing player state; actions play/pause, rewind 15 s, forward 15 s, tap-to-open.
- **Sleep timer (Spec-21 Track C):** add «До кінця розділу» mode to the existing sleep-timer sheet; 30 s linear volume fade before expiry; `SensorManager`-based shake detection (threshold ~13–15 m/s²) during the fade window resets volume, extends by 15 min, haptic tick.
- **Out of scope by decision:** Android Auto / MediaLibraryService (depends on the deleted architecture), the in-app browser surface (FourReadWebScreen — the project deliberately hides browsers from the UI), the deleted multi-source data layer (it was our code and is untouched by the revert), onnxruntime downgrade, docs deletions.

## Testing Decisions

- **What makes a good test:** external behaviour — the revert restores the `addfc01` tree; a timer string does not change width when seconds tick; a badge stays legible on a light cover; search opens/collapses and Back dismisses; a cover fallback renders per genre when the URL is blocked; the widget state maps from player state; the sleep timer stops exactly at the chapter end and shake extends the timer.
- **Modules tested:** existing typography/component snapshot tests (`ui/snapshots/`), cover-fallback unit tests, widget state-mapping unit test (`AudiobookGlanceWidgetStateTest` pattern from the reverted range), sleep-timer boundary/fade/shake-math unit tests, plus the existing full suite as the CI gate.
- **Prior art:** snapshot tests under `ui/snapshots/` (roborazzi, Pixel8, sdk 36), pure-function unit tests for timer/format logic, the existing `SleepTimerSheet` tests, and the project's CI gate (assembleDebug + testDebugUnitTest + Kover 15/9).

## Out of Scope

- Android Auto / MediaLibraryService (Spec-21 Track A) — depends on the deleted architecture; revisit as a fresh effort on the current codebase.
- The in-app browser surface (FourReadWebScreen) — browsers stay out of the UI per spec-15.
- Re-adding anything from the reverted range's data-layer rewrite (it is a regression, not a feature).
- Spec-20 rebrand tickets (#121–#126) — separate effort, already tracked.

## Further Notes

- The root `metadata.json` ported here is the same file the user asked about — it carries the «Слухайка» branding and a current description, and it is new (the archive copy under `archive/migration-2026-07-30/` is a different, historical artifact).
- The revert commit references the 4 reverted SHAs and the broken CI runs (`31827226720`, `31872342620`, `31872962845`, `31873650355`) for auditability.
