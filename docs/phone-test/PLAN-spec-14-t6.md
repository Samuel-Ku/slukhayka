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

## Underlying code paths (for failure triage)

| Door | UI entry | Code path |
|------|----------|-----------|
| Search-import | Огляд search → result tap | `searchAllSources` → `FourReadAdapter.search` → `playFromSource` → `importFromSourceUrl` → `adapter.fetchBookPage` (WebViewHtmlParser) → `importBookFromSource` |
| Link-import | 4read WebView surface, URL paste → «Слухати книгу» | `importAudiobookFrom4ReadUrl` → `importFromSourceUrl` (server-fetch) — **nullable** (T5) |
| WebView-import | 4read WebView surface, «Слухати книгу» (JS capture) | `importAudiobookFromHtml(url, html)` → `adapter.parseCapturedPage` (WebViewHtmlParser) → `importBookFromSource` — **nullable** (T5) |
| sluhay WebView-import (bonus, spec-13) | Слухати → «Більше книг на Sluhay» → browse → «Додати до медіатеки» | `importWebSourcePage` → `SluhayAdapter.detailFromCapturedHtml` → `importBookFromSource` |

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
- **Out of scope here**: sluhay/sluhayknigi (spec-13) device checks are separate
  (#88 is 4read doors); if the phone session covers them anyway, mark it bonus.

## Wrap-up after the run

1. Record every step in `RESULT.md` (pass/fail + evidence), including any real
   bugs found with their fixes (convention: bug → fix commit → re-verify).
2. Close #88 with a resolution comment (commits + test counts + device notes).
3. Spec-14 (#82) has no remaining tickets after T6 — propose closing the parent.
