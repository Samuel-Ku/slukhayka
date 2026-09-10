# Спайк хвилі 50: knigi-online + chitaka (T1, #676)

Живі захоплення 2026-09-10, звичайний Android UA, без JS. Фікстури:
`research/fixtures/knigionline/`, `research/fixtures/chitaka/`.

## knigi-online.com.ua — PASS

- `robots.txt`: `Allow: /` для загального агента + `sitemap_index.xml`.
- `post-sitemap.xml`: 471 запис, свіжий (`lastmod` днями); книжкові URL
  форми `/audioknyha-<slug>-<avtor>/`.
- Сторінка книги: блок `data-tracks-url="https://knigi-online.com.ua/?audioigniter_playlist_id=531"`
  (плеєр AudioIgniter, `data-player-type="full"`).
- Плейлист — чистий JSON: `[{title, subtitle(автор), audio(прямий same-host mp3),
  cover}]`, без авторизації.
- Range-доказ (`range-proof-mp3.txt`): `Range: bytes=0-99` → **206**,
  `content-type: audio/mpeg`, `accept-ranges: bytes`, файл 12.9 МБ.
- Механіка адаптера: sitemap → каталог, AudioIgniter JSON → треки,
  пошук серверно відсутній (чесна порожнеча, як chytaylo).

> Errata (гриль 2026-09-10, ADR-0040): вердикт «пошук серверно відсутній»
> був ПОМИЛКОВИМ. Живий захват `/?s=нестайко` (2026-09-10, звичайний
> Android UA; фікстура `search-s-nestayko.html`) показав: WordPress віддає
> post-card результати — ebook «Неймовірні детективи…» + аудіокнига
> «Тореадори з Васюківки». Пошук ПРАЦЮЄ; адаптер бере лише
> `/audioknyha-/`-картки, а anchor з «Аудіокнига »-префіксом знімається в
> `splitTitleAuthor`.

## chitaka.com.ua — PASS

- `robots.txt` дозволяє лістинг (заборонені лише query-рядки і
  `*.txt/epub/fb2/rtf` — mp3 дозволено).
- `/audioknyhy/`: картки (`book-image` + `recomend-book-title`), жанри
  (`/zhanryi/`), пагінація; обкладинки — lazyload (`data-src`).
- Сторінка `/knigi/<slug>/`: нативний `<audio class="lib_book_audio">` +
  прямий same-host mp3 в `<source src>`.
- Range-доказ (`range-proof-mp3.txt`): **206**.
- Книги переважно однофайлові (один розділ на книгу — нормально).
- Межа (як chytaylo): fb2/epub/txt/читання — поза каталогом, лише аудіо.

## Розглянуті й відхилені (зафіксовано, щоб не повторювати)

- ua-ebook.com — RED: кнопка «Слухати» веде на 4read.org, власного аудіо
  немає (WP API відкритий — лише discovery-цінність).
- uk.audioreads.org — RED: playerjs вбудовує YouTube, власного транспорту
  немає (матеріал для ingest-рукава #601, не адаптер).
- abuk.com.ua — RED: комерційна книгарня + застосунки, безкоштовного
  стріму немає.
- audiokazky.com — GATED: 900+ казок за JS/API застосунку, треба окремий
  API-спайк.
- golosom.org — SKIP: російськомовний каталог, поза uk/en-рамкою.
