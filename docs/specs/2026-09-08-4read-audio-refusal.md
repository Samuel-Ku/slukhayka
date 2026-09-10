# Spec-49: Відмова від аудіо 4read і мапування заміщення прямими джерелами

Дата: 2026-09-08. ADR: `docs/adr/0037-4read-audio-refusal-and-direct-replacement-mapping.md`.
Глосарій: розділ «Source refusal and replacement» у `CONTEXT.md`
(Source Audio Refusal, Replacement Mapping, Narration Claim, Source Watch).

## Problem Statement

Слухач принципово не слухає аудіо крізь браузерну сесію, а 4read з серпня
2026 віддає аудіо лише так. При цьому метадані й підбірки 4read хороші, і
майже вся медіатека засіяна книжками, чиї Source рядки вказують на 4read:
картки Огляду ведуть у глухий кут, книжки медіатеки не грають, а ті самі
начитки є в інших прямих джерелах — тільки застосунок про це не знає і не
шукає. Видаляти книжки шкода: метадані цінні. Просити куратора прибрати
4read зі спільного каталогу теж неправильно: метадані потрібні всім, відмова
стосується лише аудіо і лише одного слухача.

## Solution

Особиста відмова від аудіо одного джерела: 4read лишається повноцінним
джерелом метаданих (секції, «Новинки», union, обкладинки, тривалість), але
його Sources більше ніколи не пропонуються — ні probe, ні автоматичний Play,
ні завантаження, ні браузерний вихід всупереч відмові. Коли слухач відкриває
чи грає таку книжку, застосунок мапує її на прямі джерела: спершу нуль
запитів по локальній union і спільному SearchCache, за промахом — один
паралельний пошуковий залп усіма прямими джерелами, зведений за MergeKey і
мемоїзований 6h/15m. Знайдена начитка причіплюється до наявного Edition за
згодою наратора або за явною претензією слухача — прогрес несесться. Книжку,
чия начитка поки ніде не знайшлась, можна додати в медіатеку і поставити на
спостереження: поява джерела повідомить локально, імпорт — одним тапом.
У спільну базу добровільно публікується лише анонімний агрегат «скільки
слухачів відмовились від аудіо цього джерела» — він показується, але ніколи
не переставляє чиїсь джерела.

## User Stories

1. As a listener who refuses 4read audio, I want one settings switch that
   stops 4read from ever being offered for playback, so that my refusal is
   one decision, not a fight on every book.
2. As a listener who refuses 4read audio, I want 4read to keep feeding the
   catalog sections, «Новинки» rail, union, covers and durations, so that I
   keep the metadata and curation I value.
3. As a listener with a 4read-seeded library, I want my existing books to
   stay in place with their progress, so that refusing a source costs me
   nothing.
4. As a listener, I want refused sources excluded before any probe or
   automatic Play, so that the app never silently reaches for audio I have
   refused.
5. As a listener, I want no hidden browser fallback for refused sources, so
   that «відмова» means what it says.
6. As a listener opening a refused book, I want the app to first look for a
   direct counterpart among sources I already have locally and in the shared
   search cache, so that most opens cost zero network requests.
7. As a listener opening a refused book, I want at most one parallel search
   volley across all direct sources per open, so that discovery never turns
   into background crawling.
8. As a listener, I want mapping verdicts memoized (positive 6h, negative
   15m) per Work, so that repeated taps never re-request.
9. As a listener, I want a found narration attached to my existing Edition
   when narrators agree, so that my progress carries across the source
   replacement.
10. As a listener, I want to claim «це та сама начитка» with one tap when
    the found source names a narrator my Edition never had, so that progress
    carries without fabricating identity.
11. As a listener, I want a narration I did NOT claim to stay a separate
    «Інша начитка», so that different narrations never silently overwrite my
    progress.
12. As a listener, I want the found source's narrator shown as a claim from
    that source's page, so that narrator names are honest provenance, never
    guesses.
13. As a listener, I want to add a book whose audio is currently unavailable
    to my library anyway, so that it keeps its metadata and is ready when a
    source appears.
14. As a listener, I want to put such a book on watch, so that I do not have
    to re-open it periodically myself.
15. As a listener on watch, I want a local notification when any source
    starts carrying my watched book, so that I can import it with one tap.
16. As a listener on watch, I want the check to ride refreshes that already
    happen, so that watching costs no network traffic of its own.
17. As a listener, I want appearance to notify me and never auto-import, so
    that nothing enters my library without my action.
18. As a listener who opted into the shared channel, I want my refusal
    published as one anonymous per-source count, so that the collective sees
    demand-side signal without exposing me.
19. As a listener, I want other listeners' refusal counts shown at most as a
    badge, so that collective signal informs me but never reorders my
    sources.
20. As a listener who reconsidered, I want to undo the refusal and find my
    4read sources back without re-importing, so that the preference is
    reversible.
21. As a listener, I want a refused-only book to show an honest
    «аудіо недоступне» state with the watch action, so that the UI never
    fakes availability.
22. As a listener, I want refusals, watches and narration claims to stay on
    my device, so that my verdicts remain personal.

## Implementation Decisions

- **Source Audio Refusal** — a new local, never-synced listener preference
  keyed by source id: the source keeps serving metadata (catalog sections,
  «Новинки», union, duration enrichment, covers) while its Sources are
  excluded from every automatic path — source probing, automatic Play,
  offline downloads, playback import. Existing Source rows stay in place,
  dormant; undoing the refusal restores them with no re-import. Not a
  Tombstone: tombstones anchor at the Work and would kill the metadata this
  whole feature preserves.
- **Refusal is per source, absolute, no escape hatch.** The motive is the
  4read site specifically, not the browser class — sluhay and other browser
  sources remain offered. There is deliberately no «listen through browser
  anyway» action for a refused source (the listener's own words:
  «принципово не буду слухати у браузері»). A refused-only Work surfaces an
  honest unavailable state plus the watch action.
- **Filtering point: a precondition on source selection.** Refused sources
  are dropped from the candidate list before the coordinator's capability
  phases (LOCAL → DIRECT → UNKNOWN → BROWSER) — inside the selection
  coordinator's contract, so every caller (card actions, player prepare,
  downloads) inherits the rule from one place. Unavailability caused by a
  refusal must read as intentional, not as a network failure.
- **Replacement Mapping** generalizes the existing tap-time cross-resolve
  (#469) from sluhay-only to all direct sources: on opening/playing a Work
  whose audio sources are all refused or absent, first consult the local
  union and the shared SearchCache (zero requests in the common case); on
  a miss, issue ONE parallel search volley (title+author) across all direct
  sources, merge by MergeKey, and memoize the per-Work verdict with the
  Edition Availability Assertion discipline (positive 6h / negative 15m).
  The sluhayua resolver becomes the general resolver behind the same gateway
  seam; the browser door no longer exists for a refused source.
- **Narration identity.** The mapped card carries its own narrator claim
  from its source page (absent stays absent, never guessed). Same narrator
  (both named, equal — or both unknown) → the mapped Source attaches to the
  SAME Edition (one narration, several Sources; progress carries). Narrator
  only on the found side → the mapped Source arrives as its own Edition (a
  sibling in «Інші начитки») and the book page offers a one-tap Narration
  Claim: accepting re-parents the Source onto the existing Edition, fills
  the narrator from the found page's claim, and takes listener precedence
  like a Metadata Override; declining keeps the sibling. Chapter-count
  agreement is never evidence — auto-merge by topology is fabrication.
- **Source Watch** — a local, never-synced per-Work relationship for audio
  that currently plays nowhere. It is checked for free on already-scheduled
  union/feed refreshes and on mapping verdicts — no polling loop of its own.
  An appearance (any source matching the Work's MergeKey) fires a local
  notification through the existing person-new-arrivals notification
  machinery; tapping imports through the ordinary import doors. Silent
  auto-import is a forbidden pattern.
- **Shared refusal aggregate.** The only thing that reaches the shared base
  is an anonymous per-source refusal count, one counter per source id,
  behind explicit opt-in consent and App Check, read best-effort. It renders
  as a badge/verdict at most and NEVER reorders, filters or hides anything
  automatically. No per-book refusals exist: a refusal is a property of the
  source, and the Work-level reason does not exist.
- **Preference plumbing follows the Content Language Preference pattern**
  (local store, no sync, no Listening State impact). Settings get one
  refusal switch listing sources; the refusal itself is the only state the
  shared aggregate writes.
- **No new parsers, no new adapters, no schema for sources.** The mapping
  rides existing normalized search/card models and existing import/upsert
  doors; watch needs its own tiny local persistence and nothing else.

## Testing Decisions

- Good tests here pin external behavior: what a tap/open/play on a refused
  Work ends with (mapping, honest unavailable, watch offer), what requests
  fire (zero vs one volley), what carries progress — never internal state
  layout.
- **Seam 1 — source selection coordinator** (existing pure JVM seam): refused
  sources are filtered as a precondition. Tests: a refused DIRECT loses to
  any allowed candidate; refused-only candidates yield the honest
  unavailable verdict (never BrowserRequired); a refused source never
  consumes probe budget. Prior art: the coordinator's existing order/TTL
  tests.
- **Seam 2 — the cross-resolve gateway** (existing seam, generalized
  resolver): union/cache-first with zero requests, one parallel volley on
  miss, exact 6h/15m memo per Work, MergeKey agreement, best-effort silence
  on failures. Prior art: the sluhayua cross-resolve tests and the
  availability-policy tests this generalizes.
- **Seam 3 — import/watch door** (one new, minimal seam): the narration
  claim re-parents a mapped Source onto the existing Edition and fills the
  narrator; declining leaves a sibling Edition; the watch policy is pure and
  its appearance event feeds the existing notification machinery exactly
  once per appearance. Prior art: import/upsert JVM tests and the
  person-new-arrivals detection tests.

## Out of Scope

- Batch/background mapping of the whole library (the library maps itself at
  touch; «пакетне мапування» was explicitly rejected).
- Any per-book or per-Work refusal, shared or local.
- Refusal of a whole source class (browser sources as a class) or any allow/
  deny exception machinery on top of the per-source switch.
- A browser escape hatch for refused sources.
- Auto-import on watch appearance; shared watch demands («хтось чекає цю
  книгу»).
- Per-book shared refusal signals; any collective re-ranking driven by
  refusal counts.
- Removing 4read from the catalog union, sections or «Новинки» — the
  metadata source stays fully alive.

## Further Notes

- 4read's own audio remains browser-only; the refusal makes that fact
  irrelevant to the listener rather than papering over it — the honest
  unavailable state tells the truth, the mapping offers the way out.
- Duration enrichment rides 4read's page fetches today; keeping 4read alive
  as a metadata source preserves that path untouched — a deliberate
  consequence of refusing audio only.
- The narration claim's listener-precedence write into the Edition row is
  the one place implementation must choose the exact write door; the spec
  leaves that to the task breakdown.
