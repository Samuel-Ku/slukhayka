# Спека 50: хвиля джерел knigi-online + chitaka

> Status: Proposed → Implemented (треки: #676–#680, батько #675).
> Спайк: `docs/wayfinder/research/sources-wave-50-spike.md`.

## Problem Statement

Тисячі україномовних аудіокниг живуть на двох сайтах, невидимих для
застосунку: knigi-online.com.ua (бібліотека загального профілю) і
chitaka.com.ua (каталог з прямими mp3). «Книга є на цих сайтах — а в
застосунку її немає» (той самий дефіцит, що spec-47 закривала для першої
трійки).

## Solution

Обидва сайти — за наявним швом `SourceAdapter`, без нових швів і без зміни
схеми. Кожне джерело входить лише через вердикт спайка T1.

- **knigionline** — server-fetch: sitemap-каталог, AudioIgniter
  JSON-плейлист (`?audioigniter_playlist_id=N`), серверний пошук `?s=`.
- **chitaka** — server-fetch: лістинг `/audioknyhy/`, нативний `<audio>`
  на сторінці. **Лише аудіо** (межа в CONTEXT.md, як chytaylo в spec-47).
- Реєстрація за прецедентом spec-47 T5 (реєстр, бейджі, DownloadPolicy,
  REFUSABLE_SOURCES) + web-паритет (ADR-0034).

## Implementation Decisions

- Ідентичності: `knigionline` → knigi-online.com.ua, «Knigi-Online»;
  `chitaka` → chitaka.com.ua, «Читака» (схема доменних імен, як
  sluhayua/soundbooks; id не ренаменяться без міграції).
- Провалений вердикт знімає таски джерела з хвилі (прецедент spec-47).
- Відхилені в спайку (не чіпати без нового спайка): ua-ebook.com
  (doorway на 4read), uk.audioreads.org (YouTube-вбудовування — матеріал
  для ingest, не адаптер), abuk.com.ua (комерція), audiokazky.com
  (GATED — треба API-спайк), golosom.org (SKIP — російськомовний).
