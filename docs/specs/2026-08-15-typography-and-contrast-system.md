# [Spec] Typography, Readability & High-Contrast Visual System — 2026-08-15

> **Status:** Draft / Ready for review & execution.
> **Source:** Derived from `/grill-me` typography & UI/UX contrast audit on 2026-08-15.
> **Target:** `Type.kt`, `Color.kt`, `HomeScreen.kt`, `BookCoverImage.kt`, `PlayerScreen.kt`, `LibraryScreen.kt`.

---

## 1. Problem Statement

In an audiobook application, typography and contrast dictate usability. Audiobooks are used while commuting, driving, exercising, or relaxing in dim environments. The current visual implementation suffers from several accessibility and readability issues:

1. **Micro-Typography Hazard (< 11sp):**
   - Subtitles, narrator notes, badges, and fallback cover text currently render at `9sp` and `10sp`.
   - On high-DPI displays (400+ ppi) and when accessibility font scaling is enabled, these elements either degrade into unreadable artifacts or cause container clipping.
2. **Font Weight Fatigue:**
   - Excessive reliance on `FontWeight.Bold` and `FontWeight.ExtraBold` across all headings, badges, and body items eliminates visual hierarchy and causes visual fatigue.
3. **Contrast Deficits in Dark Mode (WCAG AA/AAA):**
   - Rating badges and genre tags on book covers use semi-transparent overlays that lose contrast on lighter cover art.
   - Secondary text (`CyberTextSecondary`) at small sizes fails the 4.5:1 / 7:1 contrast ratios needed for ambient lighting.
4. **Jittery Timers (Proportional Figures):**
   - Playback timers and chapter timestamps use proportional numerals, causing digits (e.g., `1` vs `8`) to shift horizontally every second.

---

## 2. Target Design System & Principles (2026 Standards)

### A. Material Design 3 Typography Scale (`Type.kt`)
Establish a comprehensive, accessible typography hierarchy:
* **Display / Headline:** Clean, geometric, assertive headers (`headlineSmall` 24sp/32sp, `titleLarge` 20sp/26sp, `titleMedium` 16sp/22sp).
* **Body / Subtitles:** High-legibility body text with generous line heights (`bodyLarge` 16sp/24sp, `bodyMedium` 14sp/20sp, `bodySmall` 12sp/16sp).
* **Labels & Badges:** Minimum **11sp** (`labelSmall` 11sp/14sp, `labelMedium` 12sp/16sp, `labelLarge` 14sp/20sp) with `FontWeight.SemiBold`.
* **Tabular Numbers:** Tabular figures configured for timestamps and duration counters to prevent layout jitter.

### B. High-Contrast Dark Palette (`Color.kt`)
* **`CyberTextPrimary`:** `#F3F0F5` (Crisp contrast > 12:1 against dark backgrounds).
* **`CyberTextSecondary`:** `#D1CBD9` (Enhanced contrast > 7:1 against card backgrounds for AA/AAA compliance).
* **`CyberPrimary` & `CyberAccent`:** Optimized luminance for badges and interactive states.
* **Pill & Badge Backgrounds:** Solid high-contrast scrims (`#1A1721` with subtle border) replacing low-opacity overlays on covers.

### C. Cover Fallbacks & Metadata Layout
* Genre fallback art uses minimum `12sp` title with `lineHeight = 16sp` and `11sp` author label.
* Rating badges on cards use solid, high-contrast dark pills with gold stars (`11sp`, SemiBold).

---

## 3. User Stories & Acceptance Criteria

### US-1: Accessible Minimum Type Sizes
* **Given** any text element across Explore, Library, and Player screens,
* **Then** no user-facing label or badge has a font size lower than `11sp`.

### US-2: Stable Tabular Timers
* **Given** playback is running on the Player screen, mini-player, or Explore continue listening card,
* **Then** timers count up/down smoothly without horizontal jitter or width shifts.

### US-3: Triple-Tier Font Hierarchy
* **Given** a book card or item in any list,
* **Then** the title is bold and distinct (`15-16sp`), author is medium weight (`13-14sp`), and metadata (narrator, duration) is regular (`12-13sp`).

### US-4: High Contrast Cover Badges
* **Given** a book card in catalog rows or lists,
* **Then** rating badges and category tags remain legibly readable with WCAG AA compliance (> 4.5:1) regardless of underlying image luminance.

---

## 4. Implementation Tasks (Work Breakdown)

- [ ] **T1:** Overhaul `Type.kt` with full M3 `Typography` scale and tabular number support.
- [ ] **T2:** Update `Color.kt` for WCAG AA/AAA compliance on text and surface colors.
- [ ] **T3:** Refactor `HomeScreen.kt` typography: upgrade micro-text (`9-10sp` -> `11-14sp`), standardize line heights, and apply tabular figures to timers.
- [ ] **T4:** Update `BookCoverImage.kt` and `CatalogCoverImage` fallback typography and badge styling.
- [ ] **T5:** Update `PlayerScreen.kt` and `LibraryScreen.kt` for typography and timer consistency.
- [ ] **T6:** Build and test verification (`compile_applet` & unit tests).
