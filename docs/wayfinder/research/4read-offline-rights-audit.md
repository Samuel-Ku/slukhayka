# 4read offline & rights audit — wayfinder ticket «4read offline rights audit» (#50)

Status: resolved 2026-08-10. Primary sources verified live today: `https://4read.org/robots.txt`,
`https://4read.org/shanovn-vlasniki-prav.html` («Правовласникам»), plus the
app's own stream-extraction code (HEAD 3c3c731).

## What the site itself declares

**robots.txt** (fetched verbatim):

```
User-agent: ia_archiver
Disallow: /

User-agent: *
Disallow: /engine/
Disallow: /bed/
Disallow: /m3u/
Disallow: /*do=download
Disallow: /*do=auth-social

Host: https://4read.org
Sitemap: https://4read.org/sitemap.xml
```

- `/m3u/` — the site's own **stream playlist endpoint is robots-blocked**.
- `/*do=download` — the site's **download endpoint is robots-blocked**.
- `ia_archiver` is fully excluded; the sitemap is public.

**«Правовласникам» page** (fetched verbatim, key sentences):

> «Всі права на книги, розміщені на сайті, належать їх авторам. Якщо ви автор
> твору, розміщеного на сайті і вважаєте, що розміщення порушує ваші права —
> напишіть нам … Матеріали будуть видалена на вимогу правовласника.»

Footer (same page):

> «Всі матеріали взято з публічно доступних джерел та надаються безкоштовно,
> виключно з метою ознайомлення та розширення читацької аудиторії.»

So the site: (a) disclaims ownership of the works, (b) licenses only
«ознайомлення» (personal acquaintance), (c) operates a takedown-on-demand
model, (d) explicitly robots-blocks its stream and download endpoints.

## What the app does today (verified in code)

1. **Metadata scraping** — homepage/series/book pages (public HTML, in-sitemap book URLs `<num>-<slug>.html`), used for catalogue, covers, series, search. Not robots-blocked.
2. **Streaming** — book pages expose PlayerJS variables; the app decodes the `{v1}` obfuscation to `https://4read.org/m3u/` (`AudiobookRepository.kt:1293`) and plays the CDN files (`s1.reasd.org`). The resolved stream endpoint is the robots-disallowed `/m3u/` path.
3. **Download-for-offline** — `downloadBook` fetches each chapter stream and copies it into app-internal storage (`filesDir/audio_downloads`, `AudiobookRepository.kt:695-720`); chapters play from the local copy offline. The app thus **materializes permanent copies from a robots-disallowed endpoint**.
4. No user registration, no accounts, no publishing, no redistribution — the app is a personal listening client.

## Risk assessment

| Area | Status | Notes |
|---|---|---|
| Catalog metadata (pages, sitemap) | Low risk | Public HTML; the site ships a sitemap explicitly; IA crawler is the only full exclusion |
| Streaming | Gray | The stream endpoint is robots-disallowed; a plain personal web player is how the site's own embeds work (PlayerJS), so this mirrors what the site itself does for any visitor |
| Download-to-disk | **Highest risk** | Converts «ознайомлення» streaming into durable copies; uses a robots-disallowed endpoint; explicitly the behaviour the site's robots.txt signals it does not want automated |
| Takedown compliance | App has none | No mechanism to honor «видалення на вимогу правовласника» (no per-work removal, no way to purge a book's local copies) |

The site is itself in a gray zone (works belong to authors, licensed only for
«ознайомлення»), so the app inherits that grayness; the audit's job is to say
where the app's *own* posture adds risk beyond the site's.

## Verdict — GO with restrictions

The app may continue to be a **streaming client** of 4read: metadata browsing
is low-risk, and streaming from `/m3u/` mirrors what the site serves to its own
visitors. Two restrictions are recommended, not as legal advice but as
risk-hygiene matching the site's own signals:

1. **Do not build further download/distribution surfaces** (bulk offline
   libraries, export/share of audiobook files, backup-restore of downloaded
   copies). The existing per-book download stays, but it is the border of the
   feature — anything beyond it (e.g. «зберегти всю серію») would double down
   on the highest-risk behaviour.
2. **Add a minimal takedown path**: honor work-removal requests by mapping
   book→4read URL and purging the local copies (the app already has the
   `deleteBook` cascade — the ticket is a UI/entry point, not a new mechanism).
   Also: the app should not store 4read content *after* the site removes it —
   offline downloads should arguably expire or be re-validated when the book
   page 404s.

Sources: robots.txt and «Правовласникам» page fetched 2026-08-10;
`AudiobookRepository.kt` stream/download code (main, 3c3c731).
