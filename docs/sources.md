# Джерела — повний список сайтів

Канонічна таблиця всіх сайтів, які Слухайка знає або розглядала.
Факти впроваджених джерел живуть у кореневому `sources.json` (ADR-0038) і
припінаються `SourceRegistryConformanceTest`; цей файл — людський перелік.

## Впроваджені (доступні на Android)

| id | Сайт | Режим доступу | Мова | Примітки |
|---|---|---|---|---|
| `soundbooks` | sound-books.net | DIRECT | uk | m3u → mp3 на `arch.sound-books.net` |
| `sluhayua` | sluhay.com.ua | DIRECT | uk | SPA, playlist-XHR; `/find` пошук |
| `sluhay` | sluhay.com | BROWSER | uk | WebView-сесія (Cloudflare); playerjs |
| `sluhayknigi` | sluhayknigi.com | BROWSER | uk | WebView-сесія; той самий DLE/playerjs, окремий Referer |
| `audiobookmp3` | audiobook-mp3.com/uk | DIRECT | uk | playerjs + Referer |
| `lihtar` | lihtar.in.ua | DIRECT | uk | пряме `<audio>`; стрім-онлі (ToS) |
| `librivox` | librivox.org | DIRECT | en | англомовний каталог; аудіо з archive.org |
| `audiobookcoua` | audiobook.co.ua | DIRECT | uk | WordPress/sitemap; аудіо з archive.org |
| `chytaylo` | chytaylo.com.ua | DIRECT | uk | Next.js SSR; лише аудіо (текстові книги — поза каталогом) |
| `ukrainianaudiobooks` | ukrainianaudiobooks.com | BROWSER | uk | WebView-сесія (Cloudflare); стрім-онлі |
| `telegram` | t.me (публічне превʼю) | UNKNOWN | uk | лише надходження від слухачів, metadata-only (ADR-0035) |
| `local` | — | DIRECT | — | локальні файли/папки користувача |
| `4read` | 4read.org | BROWSER | uk | метадані/каталог; аудіо може бути відмовлене (ADR-0037/0035) |

Не-сайтові канали: надходження YouTube-посилань (YouTube-відео/плейлисти,
ADR-0035) входять локальним Source `youtube` через «+ Додати → Надіслати
посилання», окремим каталогом не є.

## Розглянуті та відхилені

| Сайт | Вердикт | Причина |
|---|---|---|
| books-audio.in | REJECT | російськомовний контент |
| md-eksperiment.org | REJECT | лише YouTube-вбудовування, не книжковий каталог |
| notatky.com.ua/audiobooks | REJECT | лише YouTube-вбудовування |

## Кандидати на майбутнє

Новий сайт входить лише через спайк за критеріями приймання (українська мова,
безкоштовно/без реєстрації, прямі аудіо-URL без DRM, ToS/robots дозволяють
стрімінг) із фікстурами живих захоплень — прецедент:
`docs/wayfinder/research/source-pool-spike.md` і
`docs/wayfinder/research/sources-wave-47-spike.md`. Провалений спайк
знімає кандидата без нової архітектури.

Джерело-профільні дані: `docs/adr/0036-shared-browser-recovery-profile.md`,
`docs/adr/0038-source-registry-one-carrier-of-source-facts.md`.

## Живі фікстури для перевірки стрімінгу (перевірено 2026-09-26)

Набір перевірених живих книг для ґейта #533 (AC5). Кожен рядок пройдено
мережею: discovery → chapters → stream → Range. Тримайте його оновленим —
сторінки джерел зникають (див. нижче).

| Джерело | Книга | Discovery | Розділів | Потік | Перевірка |
|---|---|---|---|---|---|
| `sluhayua` | Стороженко Олекса — Марко Проклятий | `/find/allcards?search=стороженко` | 8 | `/play?bookId=1403735&fileId=0..7` → `mp3.sluhay.com.ua` | GET 200 `audio/mpeg` 61 542 400 B; RANGE 206 |
| `sluhayua` | Григорій Квітка-Основ'яненко — Сердешна Оксана | `/find/allcards?search=квітка` | 7 | `/play?bookId=5931576&fileId=N` | 200 |
| `soundbooks` | Роберт Шеклі — Безглузді запитання | `/zarubizhna-literatura/2827-bezgluzdi-zapytannia.html` | 1 | m3u → `arch.sound-books.net/3261/…mp3?expires=…` | GET 200 `audio/mpeg`; RANGE 206 |
| `soundbooks` | Джоан Ролінґ — Гаррі Поттер і таємна кімната | `/zarubizhna-literatura/1860-garri-potter-i-taiemna-kimnata.html` | 1 | m3u → `reasd.org/2519/…mp3?expires=…` | 200 |
| `audiobookmp3` | Браян Ламлі — Мій дивний пятниця | `/uk-audio-6217-brajan-lamli-mij-divnij-pjatnicja` | 1 | `.pl.txt` → JSON `[{title,file}]` → `redirectto.cc/…/track-0.mp3` | GET 200 `audio/mpeg` 43 548 672 B; RANGE 206 |
| `audiobookmp3` | Кассандра Клевеленд — Мовчазна пацієнтка | `/uk-audio-6287-movchazna-paciyentka` | ≥1 | те саме | 200 |
| `lihtar` | (стрім-онлі) Олена і Тимур Литовченки — І знову про любов | `lihtar.in.ua/biblioteka/…/i-znovu-pro-lubov` | — | JS-плеєр; прямого mp3 у HTML немає | потребує UI-прогону, не перевірки мережею |

### Що ламається і як

- **Referer обов'язковий.** `arch.sound-books.net`, `reasd.org` і
  `redirectto.cc` віддають **403** без нього; правильний Referer оголошено в
  `SourceRegistry` (ADR-0038), і застосунок його посилає. Перевіряючи `curl`-ом,
  додавайте `-H "Referer: https://sound-books.net/"` (або `…/audiobook-mp3.com/`),
  інакше побачите фальшивий 403.
- **Не кожна книга має розділи.** SluhayUA «Адриан Кащенко — Над кодацьким
  порогом» віддає `var playlist = []` — нуль глав. Фікстуру беріть із
  перевіреним `playlist`, інакше тест хибно впаде на «немає глав».
- **Сторінки джерел зникають.** SoundBooks «Доктор Сон»
  (`/zarubizhna-literatura/2841-doktor-son.html`) тепер віддає **301** на
  індекс розділу — книгу прибрали, хоч сусідні URL живі. Саме тому фікстуру
  не варто хардкодити за номером сторінки.
- **Range важливий.** Перевіряйте `206`, а не лише `200`: від Range залежать
  seek і локальне завантаження глави, тобто половина AC5.
