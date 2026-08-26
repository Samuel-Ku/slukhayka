# Слухайка Web (spec-43)

Каркас вебзастосунку: Vite + React + TypeScript, PWA з темною темою і тихим
анонімним профілем. З T3 тут уже є **Web Transport** — серверні двері до
джерел (ADR-0024): огляд каталогу 4read і сторінка книги працюють, решта
п'яти джерел уже спарсена й чекає включення в Огляд (T4), плеєр наступний
(T5).

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
