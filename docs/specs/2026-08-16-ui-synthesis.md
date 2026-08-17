# UI synthesis from six design documents (autonomous grilling)

> Source: the six UI documents under `docs/attachments/` (one is duplicated).
> This is the output of an **autonomous** `/grilling` session — the user asked
> to «choose the recommended» without a back-and-forth, so the recommended
> answers below were chosen by the interviewer and recorded as decisions.
> Where a decision is hard-to-reverse and surprising it is promoted to an ADR
> (ADR-0018); the rest is captured here.

---

## 1. Meta-finding: the design system already exists in code

Five of the six documents propose building a design system from scratch — one
brief lists «створити `AppTheme` з токенами» as its Stage 1, and three of them
carry full color/typography/spacing token tables copied from 1080×2400
screenshots.

**The design system is already shipped.** Wayfinder #23 delivered
[Color.kt](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Color.kt),
[Theme.kt](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Theme.kt),
[Dimens.kt](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Dimens.kt)
and [Type.kt](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Type.kt).
The dark palette (graphite-navy `AppBgDark #111318` → `AppTextPrimaryDark
#E9E6DF`, amber `AppAccentDark #E9A13B`), the 4-sp spacing rhythm, the 10–14 dp
card-radius band, and the editorial type scale are all named tokens in code.

**Consequence for every numeric value in the docs:** the palette hexes
(`#F5A623`, `#0B0B0F`, `#101116`…), the type sizes (`24sp display`, `18sp
title`…), the spacing steps, the bottom-nav heights (64–72 vs 88–96 dp) and the
mini-player heights (64 vs 68–76 dp) are **stale screenshot approximations and
must be ignored**. The code is the source of truth. What the docs correctly
diagnose is *not* missing tokens — it is **inconsistent use of the tokens that
already exist** (chips, cards, progress bars, badges all diverged).

The one genuinely missing piece is **component-level discipline**, not a new
theme. That is the subject of ADR-0018.

---

## 2. Consensus — no conflict, already decided

These items appear in effectively every document with the same verdict. They
are not new decisions; they map onto existing records:

| # | Finding (all six docs agree) | Already captured in |
|---|---|---|
| C1 | Three-tab roles: Слухати=resume, Огляд=discover, Медіатека=manage; each tab answers one question | ADR-0014 «Landing відповідає на одне питання» |
| C2 | Destructive actions need a concrete confirmation, never next to neutral text | ADR-0014 (rule 1), spec-27 BUG-001 |
| C3 | Огляд shows genre chips twice — one row must go | ADR-0014 «Один інструмент — одне місце» |
| C4 | The same book appears in 2–3 different card styles on one screen | ADR-0015 «No duplicates on screen» |
| C5 | Single source of truth for progress (hero / player / library disagree) | spec-27 (#184) BUG-003, ADR-0014 «honest data» |
| C6 | Full Ukrainian localization; no EN/UA/RU mixing in system labels | spec-27 BUG (localization), product-surfaces |
| C7 | One accent per screen; amber = action/progress/active state only | ADR-0014 «control-to-content», design tokens |
| C8 | Mini-player is one global component, same weight everywhere | product-surfaces spec |
| C9 | Chapters list must use one visual model whether in a sheet or a tab | product-surfaces (ChaptersSheet → fold into tab) |
| C10 | «Очистити» ≠ cache clear — it deletes offline books | ADR-0014 (rule 1), spec-27 BUG-001 |

C1–C10 need no new decision. They are the *backlog* (spec-27 #184 and the
product-surfaces items), not open questions.

---

## 3. Resolved variants — recommended answer chosen

The documents genuinely disagree on these. Each is settled here with the
recommended answer.

### 3.1 Chip taxonomy: three types, not two

- The design-guide proposes **two** chip types (`NavChip` / `FilterChip`).
- The redesign-brief and the UI-architecture doc propose **three**
  (`Navigation` / `Filter` / `Metadata`).

**Recommended: three.** The metadata chip (genre tag, «65 розділів», source
badge `4read`) is a real third shape — it is non-actionable and must *not*
look like a button. Collapsing it into either of the other two makes it read as
a tappable filter. See ADR-0018.

### 3.2 Card taxonomy: three canonical types by context

The docs name the same three shapes differently — `PosterCard`/`ListRow`/`HeroCard`
(design-guide), `Hero`/`Book row`/`Collection card` (ui_ia), `Book row`/`Compact
book card` (redesign-brief). **Recommended: three canonical components, one per
context**, with the old names treated as aliases:

| Canonical | Context | Aliases from docs |
|---|---|---|
| **HeroCard** | the single resume card on «Слухати» | Hero card |
| **CompactBookCard** | horizontal shelves («Нове», «Для вас») | PosterCard, Collection card, Compact book card |
| **BookRow** | vertical lists (медіатека, каталог, розділи, пошук) | ListRow, Book row |

See ADR-0018.

### 3.3 «Глава» → «Розділ» everywhere

The domain term is **Chapter** (English, unchanged in the glossary). The UI
label is currently inconsistent — the app mixes «Глава» and «Розділ». All six
docs agree the canonical label is **«Розділ»**. This is a UI-layer
localization decision only; it does not add a domain term, so `CONTEXT.md`
stays untouched.

### 3.4 Numeric token values: the code wins

Every doc proposes a different palette/type/spacing table (see §1). The
already-shipped tokens in `Color.kt`/`Dimens.kt`/`Type.kt` are authoritative;
no doc value is adopted over a code token.

---

## 4. Conflict with an existing decision — ADR-0015 wins

Several documents propose cutting «Слухати» to 3–5 sections («Нещодавно
слухали» + «Наступне для вас» + «Завантажено») or merging the shelves.

**This contradicts ADR-0015, which stands.** The eight blocks are user-
reorderable and hideable — the block system *is* the feature. The real defect
the docs observed is not the count but the duplicate hero book and the
flatly-equal card styles, both of which ADR-0015 already covers (deduplication
+ muted non-hero rows). No change.

---

## 5. Where the doc-specific fixes already live

| Document | Notable contributions | Disposition |
|---|---|---|
| `sluchayka-ui-spec.md` | 10-point audit (P0–P3), token tables | Audit → spec-27 BUG-001..012; token tables ignored (§1) |
| `sluhayka-ui-design-guide.md` | component library, chip split, progress `ProgressTrack` | Component rules → ADR-0018; audit → spec-27 |
| `slukhaika-ui-redesign-brief.md` | 3 chip types, IA, «Керувати завантаженнями», DoD | Chip/card rules → ADR-0018; destructive-downloads → spec-27 BUG-001 |
| `ui_ia_audiobook_app.md` | tab roles, card taxonomy, empty/error states | Roles → ADR-0014; taxonomy → ADR-0018 |
| `Слухайка_UI_Аналіз.md` | 6 recommendations, hierarchy critique | All → ADR-0014 + spec-27 |
| `Слухайка_UI_архітектура_та_редизайн.md` | 3 chip types, push/sheet/dialog depth, Definition of Done | Depth + chips → ADR-0018 |

---

## 6. What is genuinely new → ADR-0018

Three decisions in this synthesis are *not* already recorded anywhere and meet
the ADR bar (hard to reverse, surprising, a real trade-off):

1. **The component taxonomy** — three chip types and three card types (the
   canonical vocabulary every future screen builds against).
2. **The navigation-depth rule** — push vs sheet vs dialog, so the same task
   never gets two containers (the defect the docs saw in Chapters sheet-vs-tab).
3. **«Розділ»** as the canonical UI label for Chapter.

These are recorded in [ADR-0018](../adr/0018-ui-component-taxonomy.md).

---

## 7. What this means for delivery

Per ADR-0017 (visible deltas over decision docs), this synthesis is the
*decision* layer only. The visible delta it feeds is spec-27 (#184): the
component discipline in ADR-0018 is applied to the P0 fixes (destructive-delete
confirmation, duplicate-Work merge, progress single-source) so the first
shippable change is a phone-verified screen, not another document.
