# Empty states audit — wayfinder ticket «Empty states audit» (#24)

Status: resolved 2026-08-07. Every screen's empty state inspected in code; gaps and the house standard below.

## House standard (proposed, feeds the Design-system ticket)

Every empty state = **icon + title + short explanation + one or two next actions**. No screen ever looks broken or unexplained. Two sizes: a full-state (icon 56 dp, title, body, CTA row) and a compact row for list sub-tabs.

## Per-screen inventory

| Screen | Current empty state | Icon | CTA | Gap |
|---|---|---|---|---|
| Слухати (ListenScreen) | `ListenEmptyState`: placeholder hero + «Переглянути каталог» / «Імпортувати з пристрою» | ✅ | ✅ | None — already matches the vision |
| Огляд (HomeScreen) | `EmptyCatalogState` (no catalogue) + `EmptyStateMessage` (search: «Нічого не знайдено») | ✅ / ❌ | ✅ / ❌ | Search result empty is text-only, no icon, no «скинути пошук» action |
| Медіатека (LibraryScreen) | `EmptyStateMessage` text-only on each sub-tab (Завантажені / Обрані / Закладки) | ❌ | ❌ | **Biggest gap**: vision requires «Знайти книгу» + «Додати власні файли»; current rows are bare text with no action |
| Серія (SeriesScreen) | Icon (MenuBook) + «Не вдалося завантажити книги циклу» | ✅ | ❌ | No retry CTA on load failure |
| Сторінка книги (BookDetailScreen) | Bookmarks tab: icon + text (Box) | ✅ | ~ | No action (add first bookmark hint exists in text) |
| Плеєр (PlayerScreen) | Error state with retry (`onRetryPlayback`) | ~ | ✅ | Acceptable |

## Verdict

The Слухати empty state is the reference implementation. Медіатека sub-tabs need the standard treatment with two CTAs («Знайти книгу» navigates to Огляд, «Додати власні файли» opens the import sheet). Search-empty in Огляд should get an icon + a «Очистити пошук» action. SeriesScreen needs a retry button. This becomes one consistent set of composables under the Design-system ticket.
