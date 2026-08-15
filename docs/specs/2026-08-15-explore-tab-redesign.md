# [Spec] Explore Tab UI/UX 2026 Modernization — 2026-08-15

> **Status:** Draft / Ready for execution. Single iteration, single branch `main`.
> **Source:** Synthesized from `/grill-me` design review & user requirements on 2026-08-15.
> **Target:** `HomeScreen.kt`, `BookCoverImage.kt`, `MainActivity.kt`.

---

## 1. Problem Statement

The "Огляд" (Explore) tab serves as the primary storefront and landing surface for the audiobook player. In its current form, it suffers from several legacy layout and UX flaws:

1. **Header Clutter & Visual Noise:**
   - The search bar and a long horizontal list of filter chips permanently occupy ~140dp of prime vertical screen space at the top of the feed.
   - Users are confronted with search controls before seeing their active listening session or fresh catalogue recommendations.
2. **Displaced Information Hierarchy:**
   - The local library list and management actions were placed near the top, competing with curated online rows (Новинки, Цикли).
3. **Cover Fallback Aesthetics:**
   - When remote covers fail to load (due to Cloudflare/anti-hotlinking on 4read.org or offline usage), items fall back to generic grey placeholder boxes instead of stylized, genre-aware typographic art.
4. **Search Interaction Friction:**
   - Searching does not auto-focus the keyboard.
   - Exiting search requires manually clearing text rather than a native one-tap dismiss or system Back button handler.

---

## 2. Target Solution & UI/UX 2026 Principles

Transform the Explore tab into a clean, media-first streaming discovery feed:

1. **Collapsible Top App Bar with Magnifier Action:**
   - In default mode, the header is minimal: App brand + `[🔍 Search]` + `[🔄 Refresh]` icon buttons.
   - Tapping `[🔍]` animates the modern `OutlinedTextField` into view and reveals the contextual mood/genre chips (`⚡ Короткі`, `🔥 Топ тижня`, `Фантастика`, etc.).
   - Automatic keyboard focus (`FocusRequester.requestFocus()`).
2. **Seamless Search Dismissal & Back Handling:**
   - A dedicated `[✕]` or `[←]` button collapses search mode, clears query, resets filters, and returns smoothly to the default feed.
   - System `BackHandler` captures back gestures while search is open to close search without exiting the tab/app.
3. **Reordered Feed Hierarchy (Discovery First, Archive Last):**
   - **Top:** Smart Continue Listening card (with remaining time `formatDurationUk`, progress indicator, and haptic play).
   - **Middle:** 4Read.org curated rows (Новинки book cards, Цикли/Серії wide cards).
   - **Bottom:** "Вся бібліотека" section with total book count badge, empty state guidance, and local file import CTAs.
4. **Smart Genre-Aware Cover Art Fallbacks:**
   - If `coverImageUrl` is unavailable or blocked, dynamically render vibrant M3 Expressive gradient backgrounds mapped to genre (Cyberpunk: neon violet/blue, Sci-Fi: cosmic indigo, Classics: warm amber, Detectives: deep slate).
5. **Tactile Haptic Feedback:**
   - Micro-haptics on playback start, filter selection, and search toggles.

---

## 3. User Stories & Acceptance Criteria

### US-1: Clean Header & Collapsible Search
* **Given** the user is on the Explore tab,
* **When** viewing the initial screen,
* **Then** the search input and filter chips are hidden, showing only the header with `[🔍 Search]` and `[🔄 Refresh]` icons.
* **When** the user taps `[🔍 Search]`,
* **Then** the search bar and filter chips expand smoothly with animation, and the keyboard focus is requested automatically.

### US-2: Search Mode Dismissal & Back Navigation
* **Given** search mode is active (expanded),
* **When** the user taps `[✕]` or the system Back button,
* **Then** search mode collapses, the search query is cleared, filters reset to "Усі", and the full curated feed is restored.

### US-3: Reordered Feed Hierarchy
* **Given** the user scrolls through the Explore tab,
* **Then** they see:
  1. Continue Listening session (if active playback exists).
  2. Curated 4Read rows (Новинки, Цикли).
  3. "Вся бібліотека" (all local books) at the bottom with import CTAs.

### US-4: Stylized Genre-Aware Cover Fallbacks
* **Given** an audiobook with no network cover or blocked image,
* **When** displayed in catalog rows or lists,
* **Then** it renders a polished gradient tile matching its genre with clean title typography and icon rather than a broken or blank element.

---

## 4. Implementation Tasks (Work Breakdown)

- [ ] **T1:** Update `HomeScreen.kt` state to track `isSearchExpanded: Boolean` and attach `FocusRequester`.
- [ ] **T2:** Implement animated collapsible search header with `[🔍]` trigger and `[✕]` close button.
- [ ] **T3:** Integrate Compose `BackHandler(enabled = isSearchExpanded || inSearchMode)` to collapse search.
- [ ] **T4:** Move "Вся бібліотека" and catalog management CTAs to the bottom of `HomeScreen.kt`.
- [ ] **T5:** Enhance `CatalogCoverImage` and `BookCoverImage` with dynamic genre gradient palette fallbacks.
- [ ] **T6:** Verify with unit tests (`gradle :app:testDebugUnitTest`) and ensure green compilation.

---

## 5. Non-Goals / Out of Scope
- Modifying Room database schemas or repository network parsers.
- Changing BottomNavigation structure (remains two tabs: Explore · Library).
