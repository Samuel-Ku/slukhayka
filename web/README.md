# Слухайка Web (spec-43/T2)

Каркас вебзастосунку: Vite + React + TypeScript, PWA з темною темою і тихим
анонімним профілем. Це ще не плеєр — тільки фундамент: встановлення «на
головний екран», стабільний uid без екрана входу, App Check. Огляд, книга і
плеєр прийдуть у наступних тікетах spec-43.

## Локальний запуск

```bash
cd web
npm install
npm run dev
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
каталог результату `dist`. Або вручну:

```bash
npm run build && npx wrangler pages deploy dist
```

Змінні оточення налаштовуються в дашборді Pages (Production/Preview).
