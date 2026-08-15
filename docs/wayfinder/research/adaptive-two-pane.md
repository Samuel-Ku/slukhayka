# Adaptive two-pane layout — wayfinder ticket «Adaptive two-pane layout» (#36)

Status: resolved 2026-08-07. Research verdict for tablets/foldables (stage 4).

## The decision

**Adopt Material 3 Adaptive (`androidx.compose.material3.adaptive`) with `ListDetailPaneScaffold`** for the Медіатека (book list ↔ book detail/player) and later the Огляд screens. The adaptive APIs are **stable** in current Material 3 releases — no experimental opt-in needed for the core surface.

## Facts

- `ListDetailPaneScaffold`, `SupportingPaneScaffold`, `currentWindowAdaptiveInfo()`, `WindowSizeClass` are **stable** in `material3-adaptive` (1.x); the older `ExperimentalMaterial3AdaptiveApi` opt-in applies only to preview/navigation extras, not the scaffold itself.
- **Dependencies** (matching the project's Compose BoM): `androidx.compose.material3:material3-adaptive` (adaptive + adaptive-layout + adaptive-navigation artifacts), plus the `androidx.window` size-class bridge already pulled by `currentWindowAdaptiveInfo()`.
- The scaffold handles the hard parts automatically: single-pane (phone) shows list → detail with **back handling**, dual-pane (tablet/foldable expanded) shows both, and panes refit on resize/fold-state change.

## Minimal pattern (from the docs)

```kotlin
val navigator = rememberListDetailPaneScaffoldNavigator<Long>() // bookId as content key
ListDetailPaneScaffold(
    directive = navigator.scaffoldDirective,
    value = navigator.scaffoldValue,
    listPane = { BookList(onBookClick = { id -> navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }) },
    detailPane = { navigator.currentDestination?.contentKey?.let { BookDetail(it) } ?: EmptyDetail() }
)
```

## Implications for the app

- Медіатека today is a single-pane LazyColumn screen; wrapping it in the scaffold is a contained refactor and can land after the Library rework ticket (which changes the card layout anyway).
- The current `selectedBookId` in MainViewModel maps directly onto the scaffold's content key — no state model change.
- Player-full-screen stays a modal overlay on all window sizes; only the catalog/library panes adapt.

## Verdict

**GO for stage 4, gated on the Library rework ticket** (adapt the layout once, not twice). No experimental API risk.

Sources: developer.android.com — Build adaptive layouts (Material 3 Adaptive); androidx.compose.material3.adaptive reference.
