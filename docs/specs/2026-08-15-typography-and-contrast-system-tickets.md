# Tickets: Typography, Readability & High-Contrast Visual System

Derived from: `docs/specs/2026-08-15-typography-and-contrast-system.md`
Triage Label: `ready-for-agent`

---

## Ticket #1: Material Design 3 Typography Scale & High-Contrast Theme Palette

**Labels:** `ready-for-agent`, `ui/ux`, `theme`

### Description
Establish a comprehensive, accessible typography hierarchy in `Type.kt` and enhance WCAG contrast in `Color.kt`.

### Acceptance Criteria
- [ ] Define complete M3 `Typography` in `Type.kt` (`displaySmall`, `headlineMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`).
- [ ] Ensure all font styles specify comfortable `lineHeight` and letter spacing suitable for Cyrillic typography.
- [ ] Provide helper style with `FontFeature.tabularFigures()` or monospace configuration for playback timers.
- [ ] Adjust `CyberTextSecondary` in `Color.kt` to `#D1CBD9` for > 7:1 contrast ratio against dark cards.

---

## Ticket #2: Overhaul Typography & Micro-Text in HomeScreen & Cover Badges

**Labels:** `ready-for-agent`, `ui/ux`, `screen-home`

### Description
Upgrade all text sizes in `HomeScreen.kt`, eliminating micro-text (< 11sp), tuning font weights, and adding high-contrast pill styling for badges.

### Acceptance Criteria
- [ ] Catalog card titles set to `14sp` with `lineHeight = 18sp` and `FontWeight.SemiBold`.
- [ ] Rating badge and series badges upgraded from `9sp` to `11sp` with solid dark scrims (`#1A1721`).
- [ ] Book list items use 3-tier hierarchy: Title (`16sp`, `FontWeight.SemiBold`), Author (`13sp`, `FontWeight.Medium`), Metadata (`12sp`, `FontWeight.Normal`).
- [ ] Genre chips and search placeholders set to accessible sizes (`13sp` - `14sp`).

---

## Ticket #3: Enhance Fallback Typography in BookCoverImage & CatalogCoverImage

**Labels:** `ready-for-agent`, `ui/ux`, `components`

### Description
Refactor typography in `BookCoverImage.kt` and `CatalogCoverImage` fallback generators for clean, legibly scaled art.

### Acceptance Criteria
- [ ] Minimum title size in fallback covers is `12sp` with `lineHeight = 15sp` and `FontWeight.Bold`.
- [ ] Author subtitle is `11sp` with clear spacing.
- [ ] Genre icons scaled proportionally (`24-28dp`).

---

## Ticket #4: Apply Typography Scale & Tabular Timers to PlayerScreen & LibraryScreen

**Labels:** `ready-for-agent`, `ui/ux`, `player`, `library`

### Description
Apply the new typography hierarchy and tabular timer figures across `PlayerScreen.kt` and `LibraryScreen.kt`.

### Acceptance Criteria
- [ ] Elapsed and remaining playback timestamps use tabular figures to eliminate second-by-second jitter.
- [ ] Chapter title, author, and speed controls in player adhere to clear M3 scales without cramped text.
- [ ] Library filter chips, import button, and statistics use standardized `labelLarge` / `bodyMedium`.
- [ ] Compilation and test suite pass without regressions.
