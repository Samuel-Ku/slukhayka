# Sources wave 47 spike — spec-47 T1 (#629): audiobook.co.ua, chytaylo.com.ua, ukrainianaudiobooks.com

Status: resolved 2026-09-08. Live probes (curl with the real Android WebView
User-Agent, range requests on audio endpoints, sitemap enumeration). Fixtures
trimmed into `research/fixtures/{audiobookcoua,chytaylo,ukrainianaudiobooks}/`.
Methodology follows the spec-10/11 spikes: facts from live pages, not hearsay.

## Verdicts

| Source | Verdict | Transport |
|---|---|---|
| audiobook.co.ua | **PASS** | server-fetch (WordPress + Playerjs playlist txt) |
| chytaylo.com.ua | **PASS** | server-fetch (Next.js SSR, inline tracks payload) |
| ukrainianaudiobooks.com | **GATED** | Cloudflare challenge on HTTP level — T4 proceeds as WebView-pattern per spec; a live in-app-browser session check on a device is the T4 follow-up |

No verdict changed the wave shape: both server-fetch candidates passed, the
Cloudflare candidate enters exactly as the spec planned (sluhay treatment).
No source is dropped.

## audiobook.co.ua — PASS (server-fetch)

| Criterion | Verdict | Evidence |
|---|---|---|
| Live, no challenge | ✅ | HTTP 200 on /, /novinki-ozvuchivaniya/, book pages, sitemaps; robots.txt is Cloudflare-managed but `Allow: /` for the generic agent |
| Catalog depth | ✅ | WordPress sitemap index → `post-sitemap.xml` (1000), `post-sitemap2.xml` (1000), `post-sitemap3.xml` (192) = **2192 books**; `seria-sitemap.xml` (126 series); `author-sitemap.xml` |
| Book page → playlist | ✅ | Playerjs init carries `"file":"https://audiobook.co.ua/playlist/<slug>.txt"` — a JSON array of `{title, file}` per chapter; stable across probed books («Під куполом», «Нужные вещи») |
| Direct audio | ✅ | Playlist files live on **archive.org** (`https://archive.org/download/<item>/NNN.mp3`); plain GET 302 (node redirect) → followed `206 audio/mpeg`, range requests work — seekable, downloadable |
| «New» feed | ✅ | `/novinki-ozvuchivaniya/` — server-rendered post-grid cards with title link «Назва - Автор», cover img alt «Назва - Автор», excerpt, views, rating |
| Search | ❌ weak | The post-grid search form (`?keyword=…`, WP nonce) does **not** filter server-side (18 items on plain and keyword pages alike); `search()` returns an empty list (honest refusal, per the seam contract and spec T2) |
| Metadata | ✅ | `og:title` = «Аудиокнига <Назва> - <Автор> …», `og:image` = direct jpg cover; series/authors via `/seria/…`, `/avtory/…`, `/ispolniteli/…` links (taxonomy pages, not per-book) |
| Ukrainian content | ✅ | Titles/authors in Ukrainian («Ґолем - Ґустав Майрінк», «Кров і пісок - Вісенте Бласко Ібаньєс»); UI chrome is Russian (irrelevant to parsing) |

Notes for T2:

- Playlist JSON keys: `title` (« 1   Під куполом - Стівен Кінг» — leading
  spaces, needs trim), `file` (absolute archive.org URL). Titles repeat the
  book title — chapter display may want «Розділ N» normalization, like 4read.
- `og:locale` says `ru_RU` (site chrome) — content is Ukrainian; the adapter
  declares `contentLanguage = "uk"` per the spec, per-book claims stay empty.
- The archive.org redirect chain must be followed by the shared transport;
  range requests verified on the resolved node.
- Sitemap enumeration for `fetchCatalog`: walk `post-sitemap*.xml` (books),
  optionally `seria-sitemap.xml` for series pages later (M2).

## chytaylo.com.ua — PASS (server-fetch)

| Criterion | Verdict | Evidence |
|---|---|---|
| Live, no challenge | ✅ | HTTP 200 everywhere; no Cloudflare, no UA gate |
| Book listing | ✅ | `/audiobooks` (+ `?categoryKey=…`, 20+ categories, `?page=2` works) — Next.js SSR HTML carries 60 `<a href="/books/<slug>">` cards per page |
| Book page metadata | ✅ | schema.org JSON-LD `@type: Book` with `name`, `author.name` («Шарлотта Бронте»), `image` (`/api/uploads/book-cover-….webp`), `inLanguage: "uk-UA"`, `bookFormat: ["AudiobookFormat"]`, description; og:* mirrors |
| Chapters inline | ✅ | The SSR payload embeds the full player props: `"tracks":[{"title":"Частина 1","url":"/api/audio-local/book-<slug>-part-001-<hash>.mp3"}, …]` + `coverUrl` («Джейн Ейр» = 38 parts) |
| Direct audio | ✅ | `GET /api/audio-local/…mp3` with `Range: bytes=0-1023` → `206 audio/mpeg` — direct, seekable, no session |
| Audio/text boundary | ✅ observed | Probed book pages all carry tracks (`audio-local` present); the catalog lists `/books/…` links only — no text-only book found in sampling. Boundary enforced per-page in the adapter: **no tracks in the payload → the page is not an audiobook → skip** (spec's audio-only rule) |
| Search | ⚠️ to verify in T3 | No server-side search endpoint found in the SSR payload; a `?s=` probe is a T3 task. If none works, `search()` returns empty honestly and discovery rides listing pages + the union |

Notes for T3:

- Track URLs are root-relative — prefix `https://chytaylo.com.ua`.
- The tracks payload sits inside the React server-component props (escaped
  JSON); the fixture shows the exact escaping to parse.
- «Частина N» titles are real chapter names — keep them.
- Listing pages are the feed+catalog source (60/page, category-filtered);
  `/audiobooks` and `/books` page 1 are identical sets today.

## ukrainianaudiobooks.com — GATED (Cloudflare), proceeds as WebView-pattern

| Criterion | Verdict | Evidence |
|---|---|---|
| HTTP-level access | ❌ | `HTTP/2 403`, `cf-mitigated: challenge`, `server: cloudflare` on `/` **and** `/robots.txt` — with the real Android WebView UA (headers captured in `fixtures/ukrainianaudiobooks/response-headers.txt`) |
| Challenge page | ✅ captured | «Just a moment…» full interstitial (`fixtures/ukrainianaudiobooks/cloudflare-challenge.html`) — the same family as sluhay/sluhayknigi (spec-13) |
| Server-fetch verdict | FAIL | Automated fetching cannot pass; per spec this source never becomes a server-fetch adapter |
| WebView-pattern verdict | To confirm on device (T4) | The pattern (live in-app browser session → cookies → capture → `parseCapturedPage`) is the sluhay treatment already shipped; a device session proving the challenge passes in the app WebView is T4's first step. Fixtures for the parser come from that session |

This is exactly the shape the spec anticipated: `sessionBound = true`,
debug-only browser doors, release → system browser (ADR-0026/0027 untouched).
No release WebView source is created.

## Fixture inventory (for T2–T4 fixture tests)

`research/fixtures/audiobookcoua/`:

- `book-pid-kupolom-stiven-king.html` — og:* + the Playerjs init with the
  playlist URL
- `book-nuzhnye-veshhi-stiven-king.html` — second book, same shape
- `playlist-pid-kupolom-stiven-king.txt` — `{title, file}` JSON (first 10 of
  45 tracks)
- `post-sitemap-head.xml` — first 20 of 1000 locs
- `novinki-ozvuchivaniya.html` — the post-grid cards window (title links
  «Назва - Автор», covers, pagination start)
- `home-taxonomy.html` — seria/avtory/ispolniteli link samples

`research/fixtures/chytaylo/`:

- `book-dzheyn-eyr.html` — Book JSON-LD + the embedded tracks payload window
  (38 tracks, «Частина N»)
- `audiobooks-listing.html` — book links + category links from `/audiobooks`

`research/fixtures/ukrainianaudiobooks/`:

- `cloudflare-challenge.html` — the unmodified challenge interstitial
- `response-headers.txt` — the 403 headers with `cf-mitigated: challenge`

All captures 2026-09-08, trimmed to the parser-relevant parts with the
trimmed regions marked; no audio bytes committed.

## Implications for T2–T6

- **T2 (audiobookcoua):** playlist-URL extraction from the Playerjs init →
  playlist JSON → ordered chapters; `search()` = empty; `fetchNew()` =
  novinki grid; `fetchCatalog()` = post-sitemap walk. Download policy:
  archive.org serves ranges to plain GETs — **download allowed** (evidence:
  206 audio/mpeg; archive.org is built for downloads).
- **T3 (chytaylo):** tracks payload + Book JSON-LD parse; listing pages for
  feed/catalog; audio-only boundary per-page; search probe in T3; download
  allowed (direct 206 mp3).
- **T4 (ukrainianaudiobooks):** first step is the on-device session check;
  parser fixtures come from the captured session; BrowserGatingTest
  expectations per spec (debug → IN_APP_BROWSER, release → SYSTEM_BROWSER).
- **T6 (web):** audiobookcoua + chytaylo are plain-fetch — both portable to
  worker adapters; ukrainianaudiobooks has no web path (worker has no
  WebView session) — the honest absence per the spec.
