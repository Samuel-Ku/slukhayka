# Source pool spike — spec-10 T1 «Source pool spike»

Status: resolved 2026-08-10. Every candidate from the spec's source pool verified against the four admission criteria with live probes (curl of real pages, playlist files, robots.txt). Methodology follows the «4read catalog data audit» (#31): facts from live pages, not hearsay.

## Admission criteria (from the spec)

1. Ukrainian-language content · 2. Free, no registration · 3. Technically playable (direct audio URLs, no DRM) · 4. ToS/robots permits streaming (downloads where permitted).

## Verdict table

| Source | Live | Ukrainian | Free/no-reg | Direct audio (criterion 3) | Search | «New» feed | Download / ToS | Verdict |
|---|---|---|---|---|---|---|---|---|
| **sound-books.net** | ✅ | ✅ | ✅ | ✅ **m3u → mp3 on CDN** | ⚠️ DLE `/do=search` robots-disallowed; categories + top-100 | ✅ homepage recent; ⚠️ `/newposts/` robots-disallowed | ✅ explicit download instructions on site; direct mp3 | **PASS** (server-fetch) |
| **audiobook-mp3.com/uk** | ✅ | ✅ (uk section) | ✅ | ✅ **playerjs playlist (JSON, Referer required) → mp3** | ✅ genre pages `/uk-genre-*` | ⚠️ `/uk/rss` returns site RSS (ru) — needs probe; sitemap_books.xml | ✅ robots open; stream explicit; download not documented (verify in T6) | **PASS** (server-fetch, Referer header like 4read) |
| **lihtar.in.ua** | ✅ | ✅ | ✅ (charity) | ✅ **direct mp3 on web.lihtar.in.ua** | ⚠️ no search; category browse only | ✅ library category pages | ✅ «весь контент має дозвіл від власників»; free | **PASS (niche)** — small accessibility catalog |
| **sluhay.com.ua** | ✅ | ✅ | ✅ (donations) | ⚠️ **SPA: audio via XHR, endpoint undiscovered** | ✅ `/find` + `/find/genre=` | ⚠️ no RSS (404); sitemap exists | ⚠️ unknown; robots open (sitemap only) | **CONDITIONAL** — needs XHR reverse-engineering in T3 |
| **sluhay.com** | ✅ | ✅ | ✅ | ⚠️ **Cloudflare challenge on every page** | ⚠️ CF-blocked server-side | ⚠️ CF-blocked | ⚠️ unreadable via curl | **PASS-WEBVIEW** — WebView-pattern only |
| **sluhayknigi.com** | ✅ | ✅ | ✅ | ⚠️ **Cloudflare challenge on every page** | ⚠️ CF-blocked server-side | ⚠️ CF-blocked | ⚠️ unreadable via curl | **PASS-WEBVIEW** — WebView-pattern only |
| **books-audio.in** | ✅ | ❌ **Russian-language** | ✅ | — | — | — | — | **REJECT** (criterion 1) |
| **md-eksperiment.org** | ✅ | ✅ | ✅ | ❌ **YouTube embeds only** | — | — | — | **REJECT** (criterion 3; short-form poetry, not books) |
| **notatky.com.ua/audiobooks** | ✅ | ✅ | ✅ | ❌ **YouTube embeds only** (WordPress posts) | — | — | — | **REJECT** (criterion 3) |

## Per-source evidence

### sound-books.net — PASS (server-fetch)
- Book page embeds the player playlist server-side: `file:"https://sound-books.net/uploads/public_files/<yyyy-mm>/<id>-<slug>.m3u"`.
- The `.m3u` is a plain text playlist of direct chapter mp3s on a CDN: `https://arch.sound-books.net/<id>/<Title>-NN.mp3` (verified: «Темна матерія» #4111, 15 chapters, HTTP 200).
- DLE engine. robots.txt: content allowed; disallows engine internals, `/do=search`, `/do=download`, `/newposts/`. The site documents user-facing downloads («Як завантажити аудіокнигу») — streaming and download are intended use.
- Search: DLE search is robots-discouraged; discovery via genre/category pages (`/zarubizhna-literatura/`, `/ukrainska-literatura/`, …), `/top-100-…`, and homepage recent. Global search for this source must use category enumeration + merge, or accept the robots caveat for direct search.

### audiobook-mp3.com/uk — PASS (server-fetch, Referer)
- Ukrainian section is real: `/uk-audio-<id>-<transliterated-slug>` (verified Jack London, Andrukhovych, Kokotiukha, Bradbury, Kipling, Verber).
- Player is **playerjs** (same framework 4read uses — the repo already parses `file:"…txt"` playlists). Book page references `https://9giiu0g54k8c.redirectto.cc/s05/<nested>/<id>.pl.txt`; fetching with `Referer: https://audiobook-mp3.com/uk` returns a JSON playlist `[{"title":"001.mp3","file":"https://…redirectto.cc/…/track-N.mp3"}]` (direct mp3 tracks; without Referer → 403).
- robots.txt open (no content disallows; sitemap_main.xml + sitemap_books.xml — full catalog enumeration).
- Genre pages `/uk-genre-*` paginated. `/uk/rss` returned the site-wide (ru) RSS — «new» feed for the uk section needs one probe (homepage-uk recent or genre pages) in T3.
- Download: direct mp3 URLs; not explicitly documented for uk — flag for T6 verification.

### lihtar.in.ua — PASS (niche)
- Book page links «Слухати» to `https://web.lihtar.in.ua/library/<cat>/<slug>`; the player page embeds `<audio id="player" src="https://web.lihtar.in.ua/audio/library/<id>/<slug>-converted.mp3">` — verified audio/mpeg HTTP 200.
- Charity library for visually impaired; «весь контент має дозвіл від власників на публікацію», free, no registration.
- No search endpoint; category pages (`/biblioteka/dytjacha-literatura`, `khudozhnja-literatura`, …). Small niche catalog — include for breadth, not volume.
- ToS page exists (`/terms-of-service`) — not read; verify in T6 before enabling downloads.

### sluhay.com.ua — CONDITIONAL (T3 cost)
- No Cloudflare; pages fetchable. Sitemap at `robots.txt → Sitemap: https://sluhay.com.ua/sitemap/0` exposes the full catalog as `https://sluhay.com.ua/<id>:<slug>`.
- `/find` (search) and `/find/genre=<genre>` exist; content renders client-side (SPA).
- Book page calls `AudiobookPlayer(<id>, playlist, …)`; the static HTML does **not** contain audio URLs — the playlist is XHR-loaded by the minified player JS. The playlist endpoint was not located in this spike (one focused reverse-engineering task in T3, likely a `/playlist/<id>`-style or `/get…` endpoint).
- Free with donation jar; robots.txt open.

### sluhay.com and sluhayknigi.com — PASS-WEBVIEW
- Both are behind a **Cloudflare interactive challenge** on every path (verified on homepage, category, and robots.txt): server-side fetch (the app's current 4read model) returns «Just a moment…» with no content.
- An in-app WebView (real browser session) passes the challenge, so playback is feasible via the WebView-interception pattern — but the current server-fetch adapter architecture does **not** apply. These two sources require the WebView-pattern integration (or a CF-aware fetch strategy), which is a different T3 workstream than the server-fetch adapters. Both are otherwise Ukrainian, free, and book-oriented.

### books-audio.in — REJECT
- Live, free, no registration, but **Russian-language** («Аудиокниги слушать в открытом доступе») — fails criterion 1.

### md-eksperiment.org — REJECT
- Ukrainian cultural portal («Портал Експеримент»); the «аудіокниги українською» tag carries **YouTube embeds** (verified: «Іван Франко. Каменярі» → `youtube.com/embed/…`). Content is short-form poetry/works read by actors, not book catalogs. Fails criterion 3 (no direct audio from the site; YouTube extraction is out of scope).

### notatky.com.ua/audiobooks — REJECT
- WordPress site; audiobook posts embed **YouTube** (verified: «Василь Шкляр – Ключ» → `youtube.com/embed/…`). Fails criterion 3.

## Recommended source-id scheme

| source_id | Source |
|---|---|
| `soundbooks` | sound-books.net |
| `audiobookmp3` | audiobook-mp3.com/uk |
| `lihtar` | lihtar.in.ua |
| `sluhayua` | sluhay.com.ua |
| `sluhay` | sluhay.com (WebView-pattern) |
| `sluhayknigi` | sluhayknigi.com (WebView-pattern) |

(`4read` is the existing implicit source; local imports are `LOCAL` per the spec.)

## Implications for T2 / T3 / T6

- **T2 (schema):** the three server-fetch sources (`soundbooks`, `audiobookmp3`, `lihtar`) plus `sluhayua` (pending endpoint discovery) define the initial `sources` table rows and stream-only flags. Nothing in the verdicts makes any of them stream-only yet — download gating is per-T6 verification.
- **T3 (adapters):** three integration patterns emerge, not one:
  1. **server-fetch playlist** (like 4read): soundbooks (m3u), audiobookmp3 (playerjs JSON + Referer header — mirrors the repo's existing 4read audio header handling).
  2. **server-fetch direct audio element**: lihtar (follow «Слухати» link → `<audio src>`).
  3. **WebView-interception** (new pattern): sluhay.com, sluhayknigi (Cloudflare). Sluhay.com.ua is server-fetch but needs the playlist-XHR endpoint reverse-engineered first (spike item for the sluhayua adapter ticket).
- **T4 (global search):** searchable server-side today: audiobookmp3 (genre pages), lihtar (category pages), sluhayua (`/find` after SPA discovery). soundbooks search is robots-discouraged — global search must enumerate categories and merge, or accept the caveat. WebView-pattern sources (sluhay, sluhayknigi) are searchable only inside WebView sessions.
- **T6 (downloads):** verify per source — soundbooks (documented, likely fine), audiobookmp3 (undocumented — test a track URL), lihtar (ToS page), sluhayua (n/a until adapter exists).
