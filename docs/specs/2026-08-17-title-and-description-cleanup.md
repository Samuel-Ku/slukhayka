# Spec-31: Стандартизація назв і справжній опис книги

> **Status:** ready-for-agent. Синтезовано з сесії systematic-debugging 2026-08-17
> (два баги, root cause підтверджено фактами з коду). Без нових швів: одне
> pure-JVM правило назв + один точковий фікс опису.

## Problem Statement

Слухач бачить у назвах книг SEO-сміття — слово «аудіокнига» на початку або в
кінці назви, емодзі, маркетингові хвости («слухай аудіокнигу онлайн», «слухай
безкоштовні аудіокниги онлайн українською мовою») — замість чистої назви твору.
Крім того, в полі опису деяких книг замість тексту про книгу показується
шматок посилання. Обидва дефекти псують перегляд і підривають принцип «чесних
даних» (ADR-0014): назва має бути саме назвою, а опис — саме описом.

## Solution

Звести назви до одного чистого правила і змусити імпорт зберігати справжній
опис. Усе прибирання назв живе в одному pure-JVM правилі, яке застосовується на
кожному write-path і в стартовому проході по вже збережених рядках; пошук тепер
показує назви через те саме правило. Опис береться з реального значення джерела,
а не підміняється URL-шаблоном.

## User Stories

1. As a listener, I want a title like «Метро 2033 - аудіокнига» to show as «Метро 2033», so that the card shows the real book name without marketing noise.
2. As a listener, I want a title like «Аудіокнига: Метро 2033» to show as «Метро 2033», so that the leading "audiobook" label is stripped.
3. As a listener, I want emoji such as «💙💛» removed from titles, so that the list looks clean.
4. As a listener, I want marketing suffixes like «слухай аудіокнигу онлайн» stripped, so that titles are standardized.
5. As a listener, I want the same clean title in search results and in my library, so that the app is consistent everywhere.
6. As a listener, I want a real description of the book on its page, so that I can decide whether to listen, without seeing a raw link.
7. As a listener, I want already-imported books to be cleaned up automatically on the next launch, so that I do not have to re-import anything.
8. As a listener, I want a title that genuinely ends in «…про аудіокниги» (a natural last word) to stay intact, so that cleaning never mangles real names.
9. As the maintainer, I want the title rule to be one pure function, so that every write path and the startup scrub cannot drift apart.
10. As the maintainer, I want the rule to be a curated list of real source phrases plus separator-gated bare words, so that it stays predictable rather than a lossy heuristic.
11. As the maintainer, I want import to store the source's parsed description when present, so that the description field is truthful.
12. As the maintainer, I want the import fallback to carry no raw URL, so that no surface shows a link where a description belongs.

## Implementation Decisions

- **One title rule.** All title cleaning stays in a single pure function: emoji
  removal, a leading «Аудіокнига…» prefix strip, a separator-gated bare
  «аудіокнига/аудіокниги/аудіокнигу» suffix strip, and a curated list of
  multi-word SEO suffixes stripped from the end. Longest-phrase-first keeps the
  nested cases clean.
- **Separator-gated bare word.** The bare «аудіокнига»-family word is stripped
  only as a SEPARATED suffix (`-`, `—`, `,`, `|`, `(` before it), never as a
  natural last word — so «Метро 2033 - аудіокнига» cleans while «…про аудіокниги»
  stays intact.
- **Emoji removal.** Emoji are stripped via the portable Unicode symbol
  property plus the variation selector and zero-width joiner, so the rule works
  identically on the JVM and on Android.
- **Search normalizes too.** Global-search cards now run the same title rule, so
  search, catalog and library agree on the name.
- **Truthful description.** The source-page import stores the source's parsed
  description when it is non-blank; when absent, the fallback is a clean
  source-name phrase with no URL. Catalog upserts use the same no-URL fallback.
- **Startup scrub.** The existing startup pass re-applies the rule to stored
  rows, so already-imported books are cleaned on the next launch without
  re-import or schema change.
- **No schema change, no new seam.** Both fixes ride existing seams.

## Testing Decisions

- **What makes a good test:** assert the external, observable result — a title
  string in → the clean title out, and a stored description equals the source
  claim — not the internals of the regex.
- **Title rule (pure JVM).** Table-driven cases over the normalization function:
  multi-word suffixes, prefix, bare-word suffix, emoji, and the untouched cases
  (natural last word, whole-title word). Prior art: `MetadataAssertionsTest`.
- **Search seam (pure JVM).** A search result with a dirty title comes out clean.
  Prior art: `GlobalSearchMergeTest`.
- **Write path (Robolectric).** Import stores the real description, and the
  fallback carries no URL. Prior art: `TitleScrubWritePathTest`.
- **Startup scrub (Robolectric).** Stored dirty titles are rewritten on the
  startup pass. Prior art: `StoredTitleScrubRoomTest`.

## Out of Scope

- A generic "smart" title cleaner beyond the curated phrases (no ML/LLM, no
  rule-less heuristics).
- Stripping a bare «аудіокнига» word when it is a natural last word with no
  separator (kept intact to avoid mangling real names).
- Any description scrubbing beyond using the parsed value (no HTML-entity or
  markdown processing in this pass).
- Changing how other metadata fields (author, narrator, cover) are normalized.

## Further Notes

- The bare-word suffix and emoji rules are additions to an existing curated
  mechanism; new source suffixes must still be added to the list as they appear.
- Existing users are cleaned automatically by the startup scrub on first launch
  after this ships — no migration required.
- The description fix uses the parsed `og:description` value the source adapters
  already provide; it was previously discarded in favor of a URL template.
