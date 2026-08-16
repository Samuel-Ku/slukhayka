---
status: accepted
---

# UX rules from the four-review synthesis

Four independent UX reviews of 2026-08-16 (behavioral review, redesign plan,
Nielsen-heuristics review, visual spec) agreed on one systemic diagnosis: every
screen tried to be navigation, filter, showcase and statistics at once, and one
destructive action («Очистити» in the library) deleted all offline files with
no confirmation. This ADR records the rules extracted from that synthesis so
future screens are built against them instead of re-deriving them (spec-27,
#184).

## Decision

- **Деструктивна дія ніколи не виглядає нейтральною.** Any action that
  destroys listener data requires a confirmation dialog stating the concrete
  consequences («Видалити 12 книг, 2,3 ГБ?»), a destructive-colored confirm
  button, and is never placed next to neutral status text.
- **Чесні дані (honest data).** A number is shown only when it is true:
  unknown duration renders as nothing (never «00:00»), percent is cumulative
  book-level, counters update only after a background sync completes
  («довантажуємо…» beats a wrong number).
- **Один інструмент — одне місце.** One visible path per function: genre
  filtering has one chip row; one CTA for import; one action block on the book
  page (primary play button with three states + secondary icons + destructive
  in the ⋮ menu).
- **Landing відповідає на одне питання.** The «Слухати» tab answers «що
  слухати зараз»; anything else is visually secondary.
- **Control-to-content ratio.** If a screen has more control elements than
  visible content units, simplify; rarely used actions live in secondary
  menus, not next to daily ones.

## Rejected alternatives (considered during the synthesis)

- **Undo-toast instead of the confirmation dialog** — deferred (P3): a real
  undo needs deferred physical deletion, which confuses offline scenarios
  («я ж видалив, чому місце не звільнилось»).
- **Soft delete with a 24h grace period** — rejected: delayed deletion breaks
  the «files are gone, space is free» mental model in offline scenarios.
- **Collapsible storage row in the library** — rejected: the row is already
  one slim line; a collapse toggle would be chrome, not clarity.
- **Text captions under the Огляд navigation types** — superseded by
  icon-cards for «ТОП 100 / Виконавці / Автори» (the form itself signals
  «this navigates, it does not filter»).
