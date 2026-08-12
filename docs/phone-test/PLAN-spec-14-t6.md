# Phone session — spec-14 T6 (#88): import doors + missing-book behavior

> Runbook for the final device verification of spec-14 (one 4read parser behind
> a complete source seam), per repo convention. Device: **OnePlus 8 Pro**
> (IN2023), Android 14, wireless ADB. Fill the step table into
> `docs/phone-test/RESULT.md` (or a `## spec-14 T6` section) with pass/fail +
> screenshots + logcat + DB evidence after the run.
>
> Build under test: HEAD of `main` — the seam landed in `6696b2d` (search),
> `e0613ef` (link), `8b28091` (WebView), `9a54aef` (fork deleted, all doors
> nullable). JVM suite: 279 tests / 0 failures.

## What we verify (from the user's perspective)

1. **Search-import** — a book found via Огляд search becomes one library card
   with the enriched profile (author, narrator «читає», genres, rating ★,
   series if any) and the «Можливо, Тебе зацікавить» row, then plays.
2. **Link-import** — the same book pasted as a URL into the 4read WebView
   surface and «Слухати книгу» produces the **same single card** (merge), not
   a duplicate.
3. **WebView-import (captured DOM)** — the same book imported through the
   captured-page path (JS capture in the 4read WebView surface) also merges
   into that one card; the three doors' cards agree field-for-field.
4. **Missing-book ⇒ absent** — a nonexistent book URL never produces a card:
   nothing fabricated appears, no crash, no player; DB stays clean.
5. **sluhay WebView door (spec-13, bonus that closes the spec-13 device gap)** —
   the source's browser surface passes the Cloudflare challenge in-session,
   «Додати до медіатеки» on a book page becomes a normal library card with the
   «Sluhay» badge, playback plays with the per-source `Referer` (the
   redirectto.cc CDN gate), the «Нове з Sluhay» row hydrates after the
   challenge, and a book that exists on both 4read and Sluhay merges into one
   card.

## Preconditions

```bash
# 1. Build + install (debug signing, AGP default keystore)
export JAVA_HOME="/usr/local/opt/openjdk@21"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# if INSTALL_FAILED_UPDATE_INCOMPATIBLE -> uninstall first (debug-signing note)
```

```bash
# 2. Wireless ADB (Android 11+)
adb pair <ip>:<pairing_port> <code>
adb connect <ip>:<connect_port>
adb devices -l
```

```bash
# 3. Network sanity — the known phone-DNS quirk (Tailscale/MagicDNS):
#    previous rounds hit ERR_NAME_NOT_RESOLVED for 4read.org in-app while
#    ping worked. Check before starting the golden path:
adb shell "ping -c 2 4read.org || echo DNS_OR_NET_DOWN"
# then in-app: Огляд must load НОВИНКИ/ЦИКЛИ rows. If it fails, the session
# is BLOCKED for the server-fetch doors (mark ⚠️, log, and rely on the JVM
# seam tests — same as earlier rounds).
```

```bash
# 4. Clean start for the test: uninstall + reinstall, so the library is empty
#    and every door's card is attributable to this run.
adb uninstall com.aistudio.audiobook.read || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aistudio.audiobook.read/com.example.MainActivity
```

## Golden path (step table)

| # | Step | Expected | Evidence |
|---|------|----------|----------|
| 01 | Огляд → search → query a real book (e.g. «спадщина» or «кобзар») | Global search returns 4read result card(s) | `spec14_t6_01_search_results.png` |
| 02 | Tap the result | Card opens / plays; Медіатека shows the book | `spec14_t6_02_card_detail.png` |
| 03 | Book detail | Enriched profile: author, narrator («Читає …»), genre pills, `★ <rating>` (>0), series («Частина N») when the book has one; «Можливо, Тебе зацікавить» row loads | `spec14_t6_03_detail_enriched.png` |
| 04 | Playback | Audio plays, `audioEngineMode` correct, position advances | `spec14_t6_04_player.png`; logcat `AudioPlayer`/`PlaybackService` |
| 05 | Book page → «Відкрити на сайті» → FourReadWebScreen → paste the SAME book URL → «Слухати книгу» (link door, server-fetch path) | Still **one** card (merge), fields identical to step 03 | `spec14_t6_05_link_import.png` |
| 06 | Same surface, same book, «Слухати книгу» again (now the captured-DOM path, `html.length > 50` → `importAudiobookFromHtml`) | Still **one** card — no duplicate, no double source row | `spec14_t6_06_webview_import.png` |
| 07 | Медіатека count + book detail | Exactly one card for the book; `sources` has ≤ 1 4read row for it | `spec14_t6_07_library.png`; DB dump |
| 08 | Missing book, link door: paste `https://4read.org/99999999-neisnuucha-knyha.html` → «Слухати книгу» | **Nothing happens**: no card, no player, no crash, no fabricated fallback | `spec14_t6_08_missing_book.png`; logcat |
| 09 | Missing book, WebView door: navigate the 4read surface to the same fake URL → «Слухати книгу» (captured 404-page DOM) | Same: absent stays absent, no crash | `spec14_t6_09_missing_webview.png` |
| 10 | DB proof | `audiobooks`/`sources` have no rows for the fake id | DB dump (below) |

## Golden path — sluhay WebView door (spec-13 T3/T4)

> This section exercises the browser surface end-to-end and doubles as the
> device check for spec-13 T3 (#80) and the «Нове з Sluhay» row (T4, #81),
> which were left open pending the phone. It runs AFTER the 4read doors; the
> session cookies persist in the WebView jar for the app's lifetime, so the
> order matters only for screenshots, not correctness.

| # | Step | Expected | Evidence |
|---|------|----------|----------|
| S01 | Слухати → «Більше книг на Sluhay →» → browser surface opens | Fullscreen pushed browser loads `https://sluhay.com/`; **Cloudflare Turnstile auto-passes or resolves on tap** (in-session challenge — the user's real session does the gate, no bypass); homepage renders | `spec14_t6_sluhay_01_challenge.png`; logcat `WebSource` |
| S02 | Browse/search in-session (site's own UI), open any book page | Book page loads inside the surface; ads/trackers blocked (no banner noise) | `spec14_t6_sluhay_02_browse.png` |
| S03 | «Додати до медіатеки» on the book page | Toolbar action captures the DOM (origin-checked), `importWebSourcePage` imports: card appears in Медіатека with **badge «Sluhay»**, author present, **narrator absent** (measured negative finding — not a bug), cover from `data-src`, chapters from the inline Playerjs playlist | `spec14_t6_sluhay_03_add.png` |
| S04 | Tap the card → play | Audio plays from `*.redirectto.cc` **with `Referer: https://sluhay.com/`** — 206 `audio/mpeg`, position advances, no 403; chapter auto-advance works | `spec14_t6_sluhay_04_playing.png`; logcat `AudioPlayer`/`PlaybackService` + HTTP 206 evidence |
| S05 | Pause → reopen the book (or replay) | Position remembered **per source** (`sourceKey = "sluhay"`) — resuming does not touch the 4read-source position of the same Work | player screen |
| S06 | Close the browser surface (back) | `closeWebSource` re-hydrates the feeds — **«Нове з Sluhay» row appears on Слухати** (fresh session cookies → server-fetch 200) | `spec14_t6_sluhay_06_row.png` |
| S07 | Tap a row card | `playFromSource` → card plays with the sluhay Referer | `spec14_t6_sluhay_07_row_play.png` |
| S08 | Optional — merge: pick a book that exists on both 4read and Sluhay | «Додати» from the sluhay surface merges into the existing 4read card (one Work, two badges: «4read» + «Sluhay»), no duplicate | `spec14_t6_sluhay_08_merged.png`; DB dump |
| S09 | Optional — download a sluhay book | Download works (sluhay is **allowed**, not stream-only): per-chapter files with the Referer in the download path | `spec14_t6_sluhay_09_download.png` |

Checkpoints required by the request: **S01 (challenge)**, **S03 (Додати до медіатеки)**, **S04 (playback with Referer)**. S02/S05–S07 are natural completion; S08/S09 optional.

## Underlying code paths (for failure triage)

| Door | UI entry | Code path |
|------|----------|-----------|
| Search-import | Огляд search → result tap | `searchAllSources` → `FourReadAdapter.search` → `playFromSource` → `importFromSourceUrl` → `adapter.fetchBookPage` (WebViewHtmlParser) → `importBookFromSource` |
| Link-import | 4read WebView surface, URL paste → «Слухати книгу» | `importAudiobookFrom4ReadUrl` → `importFromSourceUrl` (server-fetch) — **nullable** (T5) |
| WebView-import | 4read WebView surface, «Слухати книгу» (JS capture) | `importAudiobookFromHtml(url, html)` → `adapter.parseCapturedPage` (WebViewHtmlParser) → `importBookFromSource` — **nullable** (T5) |
| sluhay WebView-import (spec-13) | Слухати → «Більше книг на Sluhay» → browser surface → challenge → «Додати до медіатеки» | `importWebSourcePage` → `SluhayAdapter.detailFromCapturedHtml` (inline playlist) → `importBookFromSource`; playback/download headers via `headersFor("sluhay", …)` → `Referer: https://sluhay.com/` |

## Evidence

Screenshots → `docs/phone-test/screenshots/spec14_t6_*.png` (or `audit/spec14-t6/`).

```bash
adb exec-out screencap -p > docs/phone-test/screenshots/spec14_t6_NN_step.png
adb logcat -d -v time > docs/phone-test/spec14_t6.logcat.log
```

DB dump (enriched profile + merge + missing-absent proof):

```bash
adb shell "run-as com.aistudio.audiobook.read \
  cp databases/audiobooks.db /data/local/tmp/ab.db && \
  chmod 644 /data/local/tmp/ab.db"
adb pull /data/local/tmp/ab.db /tmp/ab.db
sqlite3 /tmp/ab.db \
  "SELECT id,title,author,rating,series_title,merge_key FROM audiobooks; \
   SELECT b.title,s.type,s.url FROM sources s JOIN audiobooks b ON b.id=s.book_id;"
# missing-book proof (expect 0 rows):
sqlite3 /tmp/ab.db \
  "SELECT COUNT(*) FROM audiobooks WHERE id LIKE '4read-99999999%';
   SELECT COUNT(*) FROM sources WHERE url LIKE '%99999999%';"
```

Logcat tags to watch: `AudiobookRepo` (import outcome / failures), `MainViewModel`
(null guards), `FourReadWeb` (WebView lifecycle, capture), `WebSource`
(sluhay surface), `AudioPlayer`/`PlaybackService` (playback).

## Expected-pass matrix for the acceptance criteria

- [ ] Search-import works; card shows the enriched profile fields (author/narrator/genres/rating/series/related)
- [ ] Link-import works; card agrees with search-import (same single Work, no duplicate)
- [ ] WebView-import (captured DOM) works; card agrees with both above
- [ ] Playback works after each import (per convention)
- [ ] Missing-book case: nothing fabricated appears (no card, no player, no crash)
- [ ] sluhay: challenge passes in-session; «Додати до медіатеки» makes a card with the «Sluhay» badge; playback plays with the sluhay Referer (no 403)
- [ ] sluhay: «Нове з Sluhay» row appears after closing the browser surface (fresh session)
- [ ] Results recorded per `docs/phone-test` convention (RESULT.md section)

## Known risks / notes

- **Phone DNS quirk** (4read.org `ERR_NAME_NOT_RESOLVED` under Tailscale/MagicDNS
  in previous rounds): step 00 decides the whole session. If down, the
  server-fetch doors (search, link) are BLOCKED on-device — JVM seam tests
  already pin them; the WebView-captured door may still work through the
  browser surface's own network path.
- **Live markup**: if a door fails while the parser passes its fixtures, capture
  the live page HTML (logcat `FourReadWeb` dump or `evaluateJavascript` output)
  and drop it into the fixture seam — markup changed upstream, not the code.
- **Debug signing**: previous installs may be signed with the old gitignored
  keystore → uninstall before install (step 0).
- **Related row** needs a live page fetch; if the page loads but the related
  section is empty on the real book, that's a site state, not a regression
  (fixture tests pin the markup).
- **sluhay session quirks**: the challenge is interactive by design — if
  Turnstile needs a tap, the tester does it; a 403 on the homepage after the
  session means cookies are stale (re-open the surface). The «Нове з Sluhay»
  row and the browser surface depend on the app's own network path — if the
  phone-DNS quirk also blocks sluhay.com, mark S01–S09 ⚠️ and rely on the
  fixtures (sluhay markup is pinned in the T1 captures).
- **sluhayknigi** is NOT part of this runbook (spec-13 T1 fixtures cover its
  format; its device pass can reuse this section verbatim with the knigi
  domain + Referer).

## Wrap-up after the run

1. Record every step in `RESULT.md` (pass/fail + evidence), including any real
   bugs found with their fixes (convention: bug → fix commit → re-verify).
2. Close #88 with a resolution comment (commits + test counts + device notes).
3. Spec-14 (#82) has no remaining tickets after T6 — propose closing the parent.
