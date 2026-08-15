# 4read catalog data audit — wayfinder ticket «4read catalog data audit» (#31)

Status: resolved 2026-08-07. Verified against live pages (homepage, series listing, a book page).

## What the homepage poster carries (already parsed)

- Book title, author, cover image URL
- Series chip (`poster__series`: series name + URL) and volume badge (`poster__label--blue`)
- **No** duration, no narrator, no rating on the poster

## What the book page carries (verified: `4read.org/7589-neostannij-bij.html`)

| Field | Example | Notes |
|---|---|---|
| Rating + votes | 4.8, 127 голосів | numeric rating and vote count |
| **Multi-genre** | «Українська література / Сучасна проза / Пригоди / Фентезі» | slash-separated genre list — richer than the app's single genre column |
| **Author** | Костянтин Шелест | plain text; author page URL not confirmed |
| **Narrator** | «Читає: Валерій Завалко» | **narrator exists on the book page** |
| **Duration** | «Триває: 23:02:02» | **total duration exists** (HH:MM:SS) |
| **Series + number** | «Цикл: Максим Темний (7)» + full ordered list («Всі книги серії…» 1–8) | per-volume list, includes «У процесі написання…» placeholders |
| Reviews | «85 відгуків» | count |
| Related books | «Можливо, Тебе зацікавить» | similar books row (title + author, likely poster markup) |
| Donation/rights blurb | author/narrator links | not product data |

## Category and author pages

- Genre strings exist on book pages. Dedicated genre-listing URLs are **not confirmed**: `/xfsearch/cat/fantastika/` and `/xfsearch/genre/fantastika/` both 404. The site menu exposes categories (Казка, Вірші, Роман, Жахи, Драма, Проза, Фентезі, Містика, Пригоди, Біографії, Детектив, Фантастика, Саморозвиток, Аудіо-вистава, Українська література, Гумор) — their URL scheme needs one more investigation before the browse ticket can rely on them.
- Author and narrator landing pages: author/narrator names are plain text on the book page; dedicated pages not confirmed.

## Implications for the browse tab (feeds ticket «Browse tab expansion»)

- **Duration** and **narrator** are only on the book page → «короткі/довгі книги» and narrator browsing require per-book page fetches (costly) unless a category/search page exposes them. The app already fetches the book page for details (cover/chapters), so a per-book enrichment pass is feasible but not free.
- **Short/long** can only be implemented as an on-demand enrichment of known books, not as a server-side filter — unless the category URL scheme is found.
- Multi-genre strings mean the app's single `genre` column under-represents books; the browse ticket should consider a genre-tags model.
- The full per-series list on the book page (with volume numbers and «in progress» entries) is a better source for the «Продовжити серію» data than the series page order alone.

## Verdict

Buildable natively today: search (exists), series, new, popular (ratings). Narrator and duration browsing are buildable only via a per-book enrichment pass; short/long and author pages depend on finding the real category/author URL scheme (open question for the browse ticket, not a blocker for the rest).
