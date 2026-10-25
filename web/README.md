# Слухайка Web (spec-43)

Вебзастосунок: Vite + React + TypeScript, PWA з темною темою і тихим
анонімним профілем. Спека паритету — `docs/specs/2026-09-06-web-parity.md`
(ADR-0034); платформенний ledger (що навмисно не закривається в браузері) —
у ній і нижче.

## Стан у коді

Усе, що нижче, — **наявність у коді**, а не оголошене device-приймання:
еталон приймання (iOS Safari PWA, другий — desktop Chromium) ще не
пройдено, тож реліз паритету не вважається прийнятим.

- **Web Transport** (ADR-0024): серверні двері до джерел у
  `src/worker/` — адаптери дванадцяти джерел (4read, Sluhay, SoundBooks,
  Lihtar, Knigionline, Chitaka, Chytaylo, AudiobookMP3, AudiobookCoua,
  Librivox, PlayerJS, SluhayUA), огляд каталогу й сторінка книги.
- **Каталог і пошук:** Огляд, індекси (серії, колекції, ТОП, виконавці),
  жанрові фільтри, глобальний пошук зі збереженням місця.
- **Плеєр:** відтворення розділів, швидкість, Smart Rewind, MediaSession,
  сон-таймер, закладки — в геометрії Android-плеєра.
- **Медіатека й синхронізація:** IndexedDB-домен (Work, Library Entry,
  Tombstone, Listening State, Person Bookmarks), Progress Sync
  (`work_relationships`, LWW), прив'язка профілю кодом відновлення;
  без прив'язки браузер не пише нічого.
- **Відгуки й оцінки:** `book_reviews`/`edition_ratings` під App Check,
  одна форма відгуку.
- **Колекції, рекомендації, офлайн-політика:** `src/collections/`,
  `src/recommend/`, `src/offline/` (eviction-детектор і повідомлення).

Реліз паритету **номера не має** (рішення власника 2026-10-11, #817):
web поїде зі спільною версією продукту після приймання на iOS Safari та
Chromium; Android лишається основним напрямком. Адаптивна доставка — #1236.

## Платформенний ledger (не закривається)

1. Приватний маршрут (Tor/проксі) у браузері неможливий (ADR-0024 п.4):
   запити йдуть через воркер, аудіо — напряму.
2. BROWSER-джерела (4read): безшумної WebView-сесії немає
   (ADR-0026/0027); лише Cross-resolve або явне «Відкрити на сайті».
3. Фонове аудіо iOS: Safari обмежує довге життя вкладки; MediaSession є,
   гарантій фону — ні.
4. Явні завантаження (OPFS/менеджер файлів) — окремий тікет після
   паритету (R-W5).
5. Android Auto, віджети — поза браузером фізично.

## Локальний запуск

```bash
cd web
npm install
npm run dev:worker   # транспорт на http://127.0.0.1:8787 (термінал 1)
npm run dev          # клієнт на http://localhost:5173, /api проксіюється (термінал 2)
```

Без змінних оточення застосунок працює у деградованому режимі: профіль
`local-…` замість Firebase-uid. Так задумано — так само, як `LocalOnlyIdentity`
на Android.

## Змінні оточення

| Змінна | Навіщо |
|---|---|
| `VITE_FIREBASE_API_KEY` | Web-ключ Firebase-проєкту |
| `VITE_FIREBASE_PROJECT_ID` | Ідентифікатор проєкту |
| `VITE_FIREBASE_APP_ID` | ID вебзастосунку |
| `VITE_FIREBASE_AUTH_DOMAIN` | Необов'язкова, за замовчуванням `<project>.firebaseapp.com` |
| `VITE_RECAPTCHA_SITE_KEY` | Ключ reCAPTCHA v3 для App Check; без нього записів не буде |

## Команди

```bash
npm run test        # vitest — чисте ядро ідентичності та конфігу
npm run typecheck   # tsc --noEmit
npm run build       # typecheck + production-bundle у dist/
npm run icons       # перегенерувати заглушки-іконки PWA
```

## Приватність веб-версії

Поки профіль не прив'язано кодом відновлення, застосунок у браузері не
надсилає нічого особистого. Важливо знати: у вебі немає приватного маршруту,
який є на Android (Tor/проксі). Запити до каталогу йдуть через транспорт
Слухайки, а аудіо — прямо з джерела браузером. Це чесне обмеження платформи,
а не прихована поведінка.

## Деплой на Cloudflare Pages

Проєкт Pages дивиться на каталог `web/`; команда збірки `npm run build`,
каталог результату `dist`. Транспорт деплоїться окремо як Worker:

```bash
npm run deploy:worker   # wrangler deploy за wrangler.toml
```

Або вручну:

```bash
npm run build && npx wrangler pages deploy dist
```

Змінні оточення налаштовуються в дашборді Pages (Production/Preview).
