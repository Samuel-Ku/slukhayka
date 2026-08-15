# WebView-pattern sources spike (sluhay.com + sluhayknigi.com) — spec-13 T1

Status: resolved 2026-08-12. Live device session (OnePlus 8 Pro, wireless adb)
with the extended interception prototype from #71, plus decisive curl probes
from macOS (a completely different TLS stack, no cookies). Both sites are
behind Cloudflare, so the WebView session is the only way past the challenge —
the same pattern #71 verified for sluhay.com.

## Verdict

**sluhayknigi.com is byte-for-byte the same backend and player as sluhay.com** —
same DLE `audiobookspbn-final-light` template, same playerjs player, same audio
CDN (`redirectto.cc`), same Referer gate, direct mp3. Nothing new to
reverse-engineer for T2: **the playlist URL is inline in the book page HTML**.

**Hydrate path (T4's key fact): server-fetch works for BOTH sites.** After the
user passes the challenge once, the app's own HTTP stack (same UA, session
cookies from the WebView jar) gets **200, `cfChallenge=false`** from the
homepage of both sluhay.com and sluhayknigi.com — no DOM snapshot via
`evaluateJavascript` needed for the «Нове з Sluhay» feed.

| Fact | Verdict | Evidence |
|---|---|---|
| sluhayknigi behind CF | ✅ managed challenge, auto-passes | first load → turnstile; auto-resolved without user action; subsequent loads no challenge (persistent session, cf. research `webview-session-persistence`) |
| Hydrate sluhay.com | ✅ 200 | probe (cookies + UA): `status=200 server=cloudflare cfChallenge=false bytes=46531` |
| Hydrate sluhayknigi.com | ✅ 200 | probe: `status=200 server=cloudflare cfChallenge=false bytes=45367` |
| Player format | direct mp3, seekable | playlist `…/26528.pl.txt` → JSON `[{"title":"Кларк Ештон Сміт - Метаморфоза Землі.mp3","file":"https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/2/8/track-0.mp3"}]` |
| Referer gate | **same as #71, not CF** | mp3 with `Referer: https://sluhayknigi.com/` → **206 `audio/mpeg`**; without → **403**; `Server: nginx/1.18.0 (Ubuntu)` — plain nginx, no TLS fingerprinting |
| Cookies needed for audio | **no** | macOS curl with only `Referer` + UA (zero cookies) got 206 — cookies are only needed for HTML |
| Playlist URL location | **inline in page HTML** | `var playerjs1 = new Playerjs({id:"playerjs1",file:"https://9giiu0g54k8c.redirectto.cc/s05/2/6/5/2/8/26528.pl.txt"});` — no playlist-XHR reverse-engineering (contrast: sluhayua spec-11 had an XHR `/play` endpoint) |
| og:title | `Назва - Автор » <Site>` (strip suffix) | «Трохи ненависті - Джо Аберкромбі » Слухай…», «Метаморфоза Землі - Кларк Ештон Сміт » 💙💛…» |
| og:url | canonical book URL | `https://sluhayknigi.com/svitova-literatura/6066-klark-eshton-smit-metamorfoza-zemli.html` |
| og:description | annotation (real content) | both sites |
| og:image | **absent** | covers only in `<img data-src=…>` / `data-poster` attributes — T2 must take `data-src` and prefix `https://<site>` |
| Narrator | **absent from og-tags** | only a «Ютуб канал диктора» link (channel handle, not a display name); **no «читає X»** in description — the sluhayua narrator pattern does **not** transfer; narrator stays empty for these sources |
| Book meta rows | Назва / Автор / Тривалість / Дата публікації | `<li><span>Автор</span> <span><a …>Кларк Ештон Сміт</a></span></li>`, «Тривалість 01:22:13» |
| Home poster rows | `poster-item grid-item` blocks | title `Назва - Автор`, cover in `data-src`, slash-separated genres; «Нові» rows carry `ЧасHH:MM:SS` duration labels |

## Endpoint / parse specs (for T2, T4)

- **Book URL:** `<category>/<id>-<slug>.html`, e.g. `svitova-literatura/6066-klark-eshton-smit-metamorfoza-zemli.html`. `<id>` doubles as the CDN file id (`/s05/2/6/5/2/8/` = digits of `26528`).
- **Playlist:** regex `Playerjs\(\{id:"playerjs1",file:"(<url>\.pl\.txt)"\}\)` from the page HTML. Playlist JSON per track: `{"title": "<file name>", "file": "<track-N.mp3 url>"}`.
- **Audio:** `GET track-N.mp3` with `Referer: https://<site>/` → 206, `audio/mpeg`, seekable.
- **«Нове з Sluhay» (T4):** server-fetch homepage with cookies + UA (session from WebView), parse `poster-item` rows: title `Назва - Автор` (split on `" - "`), cover `data-src`, genres, `Час…` duration.
- **Cover:** relative `data-src` → `https://<site><data-src>`.
- **Title:** og:title / h1 `«Назва - Автор» (слухати онлайн)` — strip ` » <site>` and the trailing phrase.

## Fixtures (committed)

`docs/wayfinder/research/fixtures/webview/`:
- `sluhay-home.html`, `sluhayknigi-home.html` — poster rows for the T4 feed parser
- `sluhay-book-trohi-nenavisti.html`, `sluhayknigi-book-metamorfoza-zemli.html` — og tags + meta rows + cover `data-src` + inline Playerjs playlist (decision-rich parts, trimmed from 269 KB / 196 KB live captures)

## Implications for T2

1. **Per-source Referer seam must be source-aware, not host-aware:** both sites stream from the same CDN host `redirectto.cc` but need different `Referer` values (`https://sluhay.com/` vs `https://sluhayknigi.com/`). Keying download/stream headers by URL host alone (the current `downloadHeadersFor`) is insufficient — this is exactly the seam spec-13 T2 builds, and it also fixes the latent audiobookmp3 streaming gap noted in the T2 body.
2. **No narrator, no og:image** — these fields are absent for WebView-pattern sources; UI shows author only; cover comes from `data-src`.
3. **Playlist is parseable offline from captured HTML** — the adapter's chapter list can come from the page HTML alone (no interception of the playlist XHR needed).
