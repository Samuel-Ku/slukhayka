---
status: accepted
---

# Narration ratings are a second concept beside the Work-level Listener Review (#348)

A reader's review asked for the obvious missing verdict: rate THE NARRATION,
not only the book («оцінки як самій книзі, так і тому, хто читає»). The
glossary until now explicitly avoided an Edition-scoped rating: `book_reviews`
holds one shared review per Work (`workId_uid`), its optional `editionTag` is a
free-form string, and the honest headline average (ADR-0022) counts those
reviews. This decision revises that stance deliberately — a narration IS a
domain thing listeners judge, and the Edition identity
(`mergeKey|narrator|language`) is already stable across devices, so a crowd
fact can anchor to it safely.

## Decision

Two concepts live side by side, never merged:

1. **Listener Review stays exactly what it was** — one per (Work, listener),
   stars + bounded text + optional tag, anchored at the Work mergeKey, feeding
   the ADR-0022 headline. Nothing about the review changes.
2. **A new «Оцінка начитки» (Narration Rating)** — integer stars 1–5, no text,
   no tags, one per (Work × Edition × listener). The shared-base collection is
   `edition_ratings`, document key `${workId}_${uid}_${editionId}`: double
   voting is impossible by construction, same trick as the review's
   `workId_uid`. Security mirrors `book_reviews` rule-for-rule: public read;
   create/update/delete only by the payload/stored `uid` owner under App
   Check; a shape gate whose bounds mirror the client codec.
3. **No mixing into the book headline.** Narration averages render beside the
   narrator's name and on the «Інші начитки» cards. They never enter the
   CombinedAverage headline — that number must keep meaning «impression of the
   Work», or ADR-0022's honesty contract quietly loses its referent.
4. **Zero side effects in v1.** No influence on recommendations, source
   ratings, or anything else that consumes votes today. A vote pool grows only
   when a surface deliberately starts reading it.
5. **Stars only.** Text and structured tags stay the review's territory for
   now; adding fields later needs no migration (new optional keys decode as
   absent).

## Consequences

- A listener can finally say «this voiceover is great» about a book they rated
  three stars — and other listeners can pick a rendition by more than vibes.
- Thin-data fragmentation stays contained: narration scores appear only where
  votes exist (honest absence everywhere else), and the book headline never
  dilutes across renditions.
- The glossary's old «avoid: Edition-scoped rating» warning is retired and
  replaced by the two-concept boundary above.
- Firestore gains one collection and one composite index candidate
  (`workId ASC, editionId ASC, createdAt DESC` shape) when queries need order.

## Considered options

- **Reshape Listener Review to be per-Edition** (`workId_editionId_uid`)
  — rejected: a book's crowd score would fragment across renditions, thin
  data would get thinner, and ADR-0022 would have to re-decide what its
  addends even mean.
- **Keep the free-form editionTag and do nothing** — rejected: it ignores the
  actual ask and leaves narration quality unexpressible.
- **Stars + ready-made quality tags (tempo, expression)** — deferred: a
  mini-questionnaire behind a narrator name is overkill before any signal
  exists.
