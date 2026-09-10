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
