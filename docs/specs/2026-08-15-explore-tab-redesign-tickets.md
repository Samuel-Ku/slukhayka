# Tickets: Explore Tab UI/UX 2026 Modernization

Derived from: `docs/specs/2026-08-15-explore-tab-redesign.md`
Triage Label: `ready-for-agent`

---

## Ticket #1: Collapsible Search Header with Auto-Focus and Animated Filter Chips

**Labels:** `ready-for-agent`, `ui/ux`, `screen-home`

### Description
Transform the search bar and filter chips in `HomeScreen.kt` into a collapsible, animation-driven header.

### Acceptance Criteria
- [ ] Replace persistent large search field with a compact TopBar containing app branding, `[🔍 Search]` icon button, and `[🔄 Refresh]` icon button.
- [ ] Tapping `[🔍 Search]` smoothly expands (`AnimatedVisibility`) the search input field and displays contextual mood/genre chips (`Усі`, `⚡ Короткі (< 2 год)`, `🔥 Топ тижня`, `Завантажені`, `Фантастика`, `Cyberpunk`, `Детективи`, `Класика`, `Антиутопія`).
- [ ] Automatically request keyboard focus on the search input via `FocusRequester` when expanded.
- [ ] Provide a dedicated `[✕]` dismiss button in the search bar to collapse search, clear query, and reset filters.
- [ ] Unit tests in `NavigationTabsTest` verify that the search bar toggle functions without regressions.

---

## Ticket #2: Search Dismissal & System Back Navigation Handler

**Labels:** `ready-for-agent`, `navigation`, `screen-home`

### Description
Add Compose `BackHandler` support on the Explore tab so that users can naturally exit search mode using the Android system Back button/gesture.

### Acceptance Criteria
- [ ] When search is expanded (`isSearchExpanded == true` or query is not blank or non-default genre selected), `BackHandler(enabled = true)` intercepts the back press.
- [ ] Intercepted back press collapses search mode, clears `searchQuery`, resets `selectedGenre` to `"Усі"`, and restores the normal feed view.
- [ ] When search is not active, `BackHandler` is disabled, allowing standard system navigation behavior.

---

## Ticket #3: Reorder Feed Hierarchy (Curated Rows First, Library Archive at Bottom)

**Labels:** `ready-for-agent`, `ui/ux`, `screen-home`

### Description
Reorder the layout elements in `HomeScreen.kt` to prioritize discovery and active sessions over archive management.

### Acceptance Criteria
- [ ] Top section: "Продовжити слухання" (Smart Resume Session Card) with remaining duration (`formatDurationUk`) and progress bar.
- [ ] Middle section: 4Read online curated catalogue rows (Новинки book cards, Цикли/Серії wide cards) and Shimmer skeleton loading state.
- [ ] Bottom section: "Вся бібліотека" section header, total book counter badge, and fallback CTAs ("Оновити каталог", "Імпортувати файл").
- [ ] Search mode cleanly replaces the whole feed with the filtered search results list.

---

## Ticket #4: Smart Genre-Aware Fallback Covers

**Labels:** `ready-for-agent`, `ui/ux`, `components`

### Description
Enhance `CatalogCoverImage` and `BookCoverImage` to display vibrant, genre-themed M3 Expressive gradient artwork when remote images fail or are blocked by Cloudflare hotlink protection.

### Acceptance Criteria
- [ ] Map genres to dynamic gradient brushes (e.g. Cyberpunk -> Neon Purple/Teal, Sci-Fi -> Deep Cosmic Indigo/Cyan, Detectives -> Slate/Amber, Classics -> Warm Burgundy/Gold, Default -> Dark Cyber Primary).
- [ ] Render elegant typographic initials, genre badge, and headphone icon instead of a generic blank/grey placeholder.
- [ ] Image error handler gracefully activates the fallback without UI jitter or crash.
