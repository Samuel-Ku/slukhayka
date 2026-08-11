---
name: webview-pattern-sources
label: wayfinder:map
created: 2026-08-11
status: charting
tracker: github-issues
map_issue: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/70
---

# Wayfinder Mirror — `webview-pattern-sources`

> **Canonical artifact lives on the GitHub issue tracker.**
> This file is a local pointer so a checkout without network access still
> shows the destination and current frontier.
>
> Map issue: [#70](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/70)

## Destination

sluhay.com і sluhayknigi.com (вердикт спайка spec-10 T1: **PASS-WEBVIEW** —
Cloudflare interactive challenge на кожному шляху, серверний fetch не працює)
інтегровані як джерела застосунку через **WebView-interception** патерн:
користувач у реальній браузерній сесії проходить challenge, сторінки книг
з'являються нативно (картки з бейджем джерела), аудіо відтворюється плеєром
застосунку з перехоплених playback-URL, позиція — per-source, як у будь-якого
іншого джерела. WebView лишається браузерним поверхом пошуку/перегляду, а не
центром продукту (рішення грилю: «WebView лише як додаткова дія»).

Мапа вважається закінченою, коли:

1. Патерн перехоплення доведений end-to-end на одному джерелі (сторінка →
   challenge → аудіо → картка медіатеки → відтворення у плеєрі).
2. UX-поверхня WebView-джерела затверджена людиною (де живе браузер, як книга
   зберігається в медіатеку, як працює пошук у межах сесії).
3. Друге джерело (sluhayknigi) підключається тим самим патерном без нових
   архітектурних рішень.

## Notes

Domain: Android (Kotlin, Compose, Media3), WebView-інтеграція, CF-захищені
сайти. Це НЕ скрапінг і НЕ обхід Cloudflare: challenge проходить сам
користувач у реальній браузерній сесії — автоматичне обходження CF поза
скоупом (етичний і ToS-бар'єр).

Критерії допуску джерел і мердж-ключ — зі спеки spec-10 (4 критерії,
начиточно-чутливий MergeKey, per-source позиція). Precedent: таб «4read Web»
— єдиний існуючий WebView-поверх; патерн може його узагальнити.

Skills для сесій: `/prototype` (HITL — дешеві артефакти UX/перехоплення),
`/grilling` (destination формується з людиною).

## Not yet specified (fog)

- **Як саме перехоплюються playback-URL** з живої WebView-сесії: який формат
  віддає плеєр sluhay/sluhayknigi (прямі mp3? m3u8? XHR всередині сторінки?),
  який шар застосунку їх ловить (shouldInterceptRequest? Debugger API?).
- **Де живе браузерний поверх**: повноекранний браузер per-source? bottom-sheet?
  заміна чи узагальнення таба «4read Web»?
- **Як книга з WebView-сесії стає карткою медіатеки**: ручний імпорт із панелі?
  автозахоплення при програванні? які метадані беремо (назва/автор зі сторінки)?
- **Пошук у межах сесії**: глобальний пошук лишається server-fetch (sluhayua),
  чи є місток «пошук у WebView»?
- **Сесії та challenge**: персистентність CF-cookies, поведінка при вбивстві
  процесу, Android lifecycle для фонового WebView.
- **Чи працює взагалі**: спочатку довести перехоплення на одному джерелі —
  поки це не доведено, решта туман.

## Tickets (children of map #70)

- [#71 — T1 WebView audio interception prototype (sluhay.com)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/71) `wayfinder:prototype` — **frontier**.
- [#72 — T3 Cloudflare challenge and WebView session persistence](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/72) `wayfinder:research` — **frontier**.
- [#73 — T2 UX surface of a WebView source](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/73) `wayfinder:grilling` — **blocked by #71**.

Fog у тілі GitHub-мапи. Локально — лише цей міррор.

## Out of scope

- **Автоматичне обходження Cloudflare** — користувач проходить challenge сам;
  скрипти CF-байпасів не є інтентом.
- **sluhayua (sluhay.com.ua)** — окрема спека spec-11, server-fetch без WebView.
- **Android Auto / Cast для WebView-джерел** — окремі майлстоуни.
- **Повні каталоги (M2)** — жанри/автори per-source — як і для spec-10.
