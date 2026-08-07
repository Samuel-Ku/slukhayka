# Enrichment data spike — wayfinder ticket «Enrichment data spike» (#30)

Status: resolved 2026-08-07. Verdict for the smart-collections idea (ticket «Smart collections design»).

## Method

Sampled the real 4read catalog (classics, modern Ukrainian prose, translated genre fiction): Кобзар (Шевченко), Мертвецький великдень (Квітка-Основ'яненко), Клуб боягузів (Кокотюха), Народження Сталевого Щура (Гаррісон), Трохи ненависті (Аберкромбі), Роздоріжжя круків (Сапковський), Марсіанські хроніки (Бредбері), Право на чари (Пратчетт). Checked against Google Books API, OpenLibrary API, Goodreads, Wikipedia.

## Per-source findings

| Source | Classics (Кобзар etc.) | Modern UA (Кокотюха) | Translated fiction (EN originals) | Genre/mood tags | Verdict |
|---|---|---|---|---|---|
| **Google Books API** | ✅ many UA editions | ✅ | ✅ via EN/translations | categories + subjects | Best bibliographic coverage; needs an API key (rate-limited without one — verified 429) and intitle/inauthor matching |
| **OpenLibrary API** | ✅ (verified live: Кобзар 2022 UA edition found) | ~ thin | ✅ classics + EN | subjects (mixed quality) | Keyless JSON API, directly queryable; good secondary source |
| **Wikipedia** | ✅ but **needs disambiguation** (Кобзар is the kobzar-musician page; the book is «Кобзар (збірка)») | ~ | ✅ EN pages | categories + awards (e.g. ПЕН-клуб top-100) | Best for descriptions, awards, «Нобелівські лауреати»-type collections; matching needs title normalization |
| **Goodreads** | ~ | ~ | ✅ | ✅ shelves (rich mood/tag data) | Thinnest for Ukrainian editions; best tag source for EN-original fiction |

## Verdict

- **Match rate estimate**: 60–85 % of the 4read catalog is findable externally, but it is **not uniform**: translated genre fiction and Ukrainian classics match well; niche modern Ukrainian audiobook content (e.g. Кокотюха's lesser-known books) matches thinly. A realistic blended rate is **~65–75 %**, and every collection needs a per-book fallback to hide non-matches rather than showing gaps.
- **Recommended source order**: Google Books API (primary, requires a free API key) → OpenLibrary (keyless secondary) → Wikipedia (descriptions/awards only, needs disambiguation) → Goodreads (tags only, optional).
- **Smart collections are GO** with this caveat: build the matcher as title+author normalization (Cyrillic/Latin, punctuation, subtitle stripping) against Google Books/OpenLibrary, and design collections to show only matched books. The data spike for this session is done; the collections design ticket takes over from here.
