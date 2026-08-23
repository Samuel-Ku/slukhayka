---
status: accepted
---

# Appendix to ADR-0014: the honest crowd average (spec-40, #279)

ADR-0014 fixed the rule «numbers match the user's experience» for counters,
percents and durations. Spec-40 adds a number of a different kind — the
headline score of a book's «Відгуки» block — and it inherits the same rule by
appendix rather than by re-derivation. This document records what «honest»
means for an average that mixes two vote pools (source ratings and listener
ratings) and where the temptation to fake it lives.

## Decision

The headline score is one **flat arithmetic mean** computed by the pure
`CombinedAverage.average` rule (`data/reviews/CombinedAverage.kt`):

1. **One addend = one vote.** Every source WITH a rating contributes exactly
   one vote; every listener rating (integer 1–5) contributes exactly one
   vote. No weighting, no Bayesian shrinkage toward a prior, no
   recency decay — anything smarter is unverifiable by the listener and
   therefore dishonest under ADR-0014.
2. **A source without a rating is absent.** A null source rating contributes
   nothing to sum or count; it is never coerced into a 0. A book whose only
   unrated source exists must not read as «rated terribly».
3. **Zero addends → no stars at all.** When nobody rated the book — no
   source rating and no listener reviews — the result is null and the UI
   renders nothing. A fabricated 0.0 or «0 оцінок» average would state a
   fact nobody made.
4. **The count is real and travels with the value.** The result carries its
   own `count` of actually-averaged addends, so the composition label
   («джерела і слухачі · N оцінок») can never disagree with the number it
   labels.
5. **Raw, unrounded double.** Rounding is a presentation concern owned by
   the surface that shows the number; the rule never rounds on its way out,
   so two surfaces cannot show two different «true» values for the same
   votes.
6. **Invalid input cannot poison the number.** Listener ratings outside
   1..5 are skipped defensively at the boundary of the rule — hostile or
   corrupt data degrades to absence, never to a wrong average.
7. **Pure and CI-pinned.** The rule is a pure JVM function tested directly
   in unit tests (formula, null-source exclusion, listeners-only,
   sources-only, empty → null, honest count, invalid ratings). Number
   truthfulness regressions surface in CI, not on phones.

## Consequences

- The displayed number always equals what a patient listener could compute
  from the visible stars and reviews — the definition of honest here.
- A single source star next to zero reviews still shows (the source's own
  claim stays a separate row per US-12); it is the combined row that stays
  empty until at least one addend exists.
- Adding sources or listeners later changes the number only by adding real
  votes — there is no hidden prior to shift it.
- The rule knows nothing about Firestore, Room or screens; wiring reads page
  data it already has (source profiles + the review list), per П12 of
  spec-40 — no new fetches, no storage for the number itself.

## Considered options

- **Weighted average (sources by authority, listeners discounted)** —
  rejected: the weights would be an unexplained editorial claim, exactly the
  kind of number ADR-0014 forbids.
- **Bayesian/Laplace smoothing toward a mid prior** — rejected: a book with
  one 5-star review would display something other than what its voters said.
- **Separate averages per pool shown side by side** — deferred as a possible
  future surface detail; it does not replace the one honest headline the
  user stories ask for (US-10, US-11).
