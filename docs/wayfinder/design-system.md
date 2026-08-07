# Design system — wayfinder ticket «Design system» (#23)

Status: resolved 2026-08-07. The single Compose design system for the app, decided and implemented.

## The decision

One calm, editorial design system: **dark graphite-navy as the primary theme**, a **warm near-white "paper" light theme**, **exactly one brand accent (warm amber)**, covers only as delicate player decoration, cards 10–14 dp radius with minimal shadows, content separated by spacing and typography (no card-inside-card), and a fixed animation budget. Implemented as tokens + theme + primitives in code; conventions below.

## Tokens (code: `ui/theme/`)

### Colour — `Color.kt`

| Role | Dark (graphite-navy) | Light (warm paper) |
|---|---|---|
| Background | `#111318` | `#FAF6EE` |
| Surface | `#191C23` | `#FFFFFF` |
| Card (surfaceVariant) | `#1F232B` | `#F2ECDF` |
| Border (outlineVariant) | `#2E3440` | `#DDD3C0` |
| Text primary | `#E9E6DF` | `#211D17` |
| Text muted (onSurfaceVariant) | `#A9A39A` | `#6E6557` |
| **Accent (primary — the one brand colour)** | `#E9A13B` | `#9A6A00` |
| On-accent | `#241A00` | `#FFFFFF` |

The dark background is deep graphite with a navy cast — never pure black. Every colour is a named token; components use `MaterialTheme.colorScheme` roles, never raw hex. The pre-design-system `Cyber*` constants survive as **legacy aliases** so old screens compile and pick up the new palette; they are to be migrated away from, not added to.

### Typography — `Type.kt`

System font family throughout (Ukrainian glyph coverage, calm reading). Scale keeps a 4-sp rhythm: `displaySmall` 36/44 Bold (hero) → `headlineMedium` 28/36 → `headlineSmall` 24/32 → `titleLarge` 20/28 → `titleMedium` 16/24 → `titleSmall` 14/20 → `bodyLarge` 16/24 → `bodyMedium` 14/20 → `bodySmall` 12/16 → labels 14/12/11. Titles are SemiBold/Bold, body Normal, labels Medium.

### Spacing, shape, touch — `Dimens.kt`

- Spacing: 4 / 8 / 12 / 16 / 20; **sections 24–32 dp**, page sides **16–20 dp**, inside compact blocks **8–12 dp**.
- Radii: inner 8, **card 12** (band 10–14), hero 20, pill 100.
- **Touch targets ≥ 48 dp** everywhere (Android accessibility).

## Component conventions

- **Section headers** = `AppSectionHeader` (uppercase bold label in the accent, 24 dp section rhythm, optional trailing action). One header component for all tabs.
- **Empty states** = the house standard from the empty-states audit: icon + title + short explanation + one or two next actions. Two sizes: `EmptyState` (full: 56 dp icon, CTA column) and `EmptyStateRow` (compact: 40 dp icon, optional trailing action). No empty screen ever looks broken.
- **Cards** = `MaterialTheme.shapes.medium` (12 dp), `surfaceVariant` container, `outlineVariant` hairline at most, minimal shadows. **Never a card inside a card** — separate nested content with spacing and type instead.
- Reference implementation: the Слухати tab (hero card, rows, empty state, headers) now uses the scheme roles + primitives. Other screens still use the legacy aliases and migrate under the stage-1 tickets.

## Animation budget (decided)

Included: miniplayer expand/collapse; cover-to-player transition (shared element later); progress movement; bottom-sheet entrance (Material 3 default); short haptics on bookmark, play and timer actions. **Excluded**: parallax, decorative loops, always-floating elements. No per-screen cover recolouring — cover art only decorates the player.

## What shipped in code

`ui/theme/` (Color.kt, Type.kt, Dimens.kt, Theme.kt — dark+light schemes, shapes), `ui/components/DesignSystem.kt` (AppSectionHeader, EmptyState, EmptyStateRow), ListenScreen migrated as the reference (hero card, rows, empty state, headers all on scheme roles; play targets bumped to 48 dp), `DesignSystemSnapshotTest` covering the primitives in both schemes. The light scheme is defined and snapshot-tested but not yet user-selectable — the themes ticket (#37) enables it after migrating the legacy colours.

## Notes for the themes ticket (#37)

- `secondary` is deliberately a muted text grey, not a second accent — do not map `secondaryContainer` onto it for filled-tonal buttons/chips; those should use `primaryContainer`.
- Light-accent contrast is borderline for small text (`#9A6A00` on the paper background ≈ 4.3:1) — prefer the accent for large/bold text and icons, and keep body text on the neutral ramp.
- Legacy `Cyber*` aliases hide raw-colour usage; migrate screen by screen to scheme roles before shipping light mode.
- `EmptyState` renders its CTA column only when an actions block is passed — full empty states should always carry at least one CTA per the audit.
