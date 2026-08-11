# sluhay.com.ua (sluhayua) spike — spec-11 T1 «sluhayua spike»

Status: resolved 2026-08-11. Live probes (curl of a real book page, the JS
bundle, the discovered JSON endpoints, an mp3 on the audio CDN). Methodology
follows the spec-10 T1 spike («source pool spike»): facts from live pages,
not hearsay.

## Verdict

**PASS (server-fetch, XHR JSON API)** — sluhay.com.ua is fully integrable as a
native server-fetch source, no WebView involved. The spec-10 spike's open item
(the "playlist-XHR endpoint") is resolved: **the playlist is inline in the book
page HTML, and the audio URL comes from the `/play` endpoint per file**. There
is also a rich JSON card API (`/find/allcards`) that serves search, the «new»
feed, and full metadata (author, narrator, cover, duration, genre) — better
metadata than any other source in the pool.

| Criterion | Verdict | Evidence |
|---|---|---|
| Live | ✅ | HTTP 200 on homepage, book pages, sitemap (`/sitemap/0`, 513 URLs) |
| Ukrainian | ✅ | «Ольга Кобилянська - Природа», «Григорій Квітка-Основ'яненко - Сердешна Оксана», «Іван Котляревський – Енеїда» |
| Free, no registration | ✅ | donation-funded, no accounts |
| Direct audio (criterion 3) | ✅ | `GET /play?bookId&fileId` → `https://mp3.sluhay.com.ua/<Author_Title_Narrator>/NN.mp3` — HTTP 200, `audio/mpeg`, `accept-ranges: bytes` (seekable), no DRM |
| Search | ✅ | `GET /find/allcards?search=<q>&page=N` → JSON cards with real title/author/narrator/cover/duration/genre |
| «New» feed | ✅ | `GET /find/allcards?sort=time&order=desc&page=1` → newest first (verified: 27.07.2026 entry) |
| Download / ToS | ✅ **allowed** | `robots.txt` has only `Sitemap:` — no Disallow at all; no ToS page found; the site itself tracks `downloadedTimes` per book (downloads are intended use) |

## Endpoint specs

### Search — `GET /find/allcards`

Params: `search=<query>`, `page=<n>` (1-based), optional `sort`/`order`
(`time`+`desc` for newest). Requires header `X-Requested-With: XMLHttpRequest`.
CSRF **not** required (verified: works with and without it). Returns JSON:

```json
{"cards": [{
  "_id": 1965454,
  "slug": "olga-kobilyanska-priroda",
  "title": "Ольга Кобилянська - Природа",
  "bookName": "Природа",
  "bookAuthor": ["Ольга Кобилянська"],
  "audioAuthor": ["Максим Тимченко"],
  "genre": ["проза", "новела"],
  "timeLength": "00:46:19",
  "kindSrc": "/uploads/1653672089.png",
  "views": 24455, "rating": 10
}], "pageCount": 18, "highlightWords": [...]}
```

Book URL: `https://sluhay.com.ua/<_id>:<slug>` (slug may be Cyrillic — URL-encode).
Metadata mapping: title=`bookName` (fallback: `title` before `" - "`), author=
`bookAuthor[0]`, narrator=`audioAuthor[0]`, cover=`kindSrc` (relative — prefix
`https://sluhay.com.ua`, normalize `//`), duration=`timeLength` (HH:MM:SS).
Collections without an author come as `bookAuthor: [" "]` — blank, no merge
(consistent with the T2 blank-author rule).

### «New» feed — same endpoint, `sort=time&order=desc`

First page = recent additions (verified «10 історій від пса Патрона», created
27.07.2026). `pageCount` gives total pages for M2 catalog work.

### Book page — `GET https://sluhay.com.ua/<id>:<slug>`

Static HTML, server-rendered (SPA only for interactive parts). Carries:

- `var playlist = [["0",0],["1",1],…]` — **inline chapter list**; length = chapter
  count, fileId = array index. (Single-file books: `[["0",0]]`.)
- `window.CSRF = '…'` — parsed but **not actually required** by `/play` (site-wide
  constant; verified `/play` works with a wrong/absent token).
- `og:title` = «Автор - Назва. Слухай аудіокнигу онлайн» — real author + title.
- `og:description` = «Аудіокнигу онлайн <Назва>, читає <Диктор>. …» — real narrator.
- `og:image` = absolute cover (may contain `//` — normalize).

### Audio per chapter — `GET /play?bookId=<id>&fileId=<index>`

Requires `X-Requested-With: XMLHttpRequest` (plain GET → 404). No cookies, no
Referer, no CSRF needed. Returns the bare mp3 URL (e.g.
`https://mp3.sluhay.com.ua/Grygoriy_Kvitka-Osnovyanenko_Serdeshna_Oksana_Diana_Goncharenko/03.mp3`);
`0`/`404` responses mean the file does not exist — stop the loop. The CDN folder
name even encodes the narrator («…_Diana_Goncharenko»).

## Adapter requirements

- **`HttpFetcher` needs extra headers.** `X-Requested-With: XMLHttpRequest` on
  `/find/allcards` and `/play` is the only gate. Options: add an optional
  per-request header param to `HttpFetcher.getText` (FakeFetcher serves by URL
  and ignores headers, so the seam keeps working), or a dedicated fetcher
  subclass for sluhayua. Referer for `/play`/CDN is not required (unlike
  audiobookmp3).
- **fetchBookPage:** fetch page → parse `playlist` length + `og:title`/`og:description`
  → loop `fileId` 0..N-1 calling `/play` → ordered `SourceChapter`s (titles
  «Глава N» — `/play` gives no names). Stop at first `0`/`404`.
- **search/fetchNew:** one `/find/allcards` call each; map JSON cards to
  `SourceBook` directly (no extra page fetches — the JSON already has the
  metadata the merge needs, like the 4read poster parse).
- **Downloads:** allowed. `DownloadPolicy.streamOnlyFor("sluhayua")` = false;
  the mp3 CDN needs no special headers for range requests (plain curl 200).

## Fixture shapes (for T2 fixture tests)

Live captures trimmed into `fixtures/sluhayua/` (committed with this spike):
`book-multi-chapter.html` (Сердешна Оксана — 7-file playlist + CSRF + og tags),
`book-single-file.html` (Природа — `[["0",0]]`), `search-kobzar.json`
(`/find/allcards` cards), `new-sort-time.json` (newest-first cards),
`play-response.txt` (plain mp3 URL). No network in tests — serve canned
HTML/JSON via FakeFetcher by URL.

## Risks / caveats

- **X-Requested-With gate is a soft anti-bot measure** — the app's server-fetch
  adapter must always send it; if the site hardens the gate later, the parser
  fixture tests will catch it (single-source failure, per the seam).
- **No chapter names** from `/play` — chapters are «Глава N» like 4read.
- **Multi-narrator `audioAuthor`** (e.g. «Григорій Решетник, Тімур Мірошниченко,
  …») goes into the merge key as one string — such books merge only with a
  source carrying the identical string (correct: different narration).
- **Cyrillic slugs** in book URLs need URL-encoding.
- The main site is **not** Cloudflare-challenged (that was sluhay.com /
  sluhayknigi.com); the mp3 CDN is CF-fronted but serves direct content.

## Implications for T2 / T3

- T2 (adapter): one `HttpFetcher` extension (extra headers) + `SluhayuaAdapter`
  (search, fetchNew, fetchBookPage) + `DownloadPolicy` entry + fixture tests.
- T3 (wiring): registry entry `sluhayua` — global search and feeds work with no
  UI changes; `sourceDisplayName("sluhayua") = "Sluhay"`; device check.
- The spec-11 fallback note («if the endpoint is a dead end → re-classify as
  WebView») is **moot** — the server-fetch path is confirmed.
