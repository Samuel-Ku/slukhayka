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

## Decisions so far

- [T3 — Cloudflare challenge and WebView session persistence (#72)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/72) — clearance = персистентна cookie (TTL «Challenge Passage», 30 хв–24 год), WebView-джар переживає вбивство процесу → challenge проходить раз на TTL; стан вкладки — ні (URL зберігає застосунок сам). Повний розбір: `docs/wayfinder/research/webview-session-persistence.md`.
- [T1 — WebView audio interception prototype (sluhay.com) (#71)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/71) — **PASS, перехоплення доведено**: `shouldInterceptRequest` бачить медіа-запити; формат = прямий mp3 (playerjs, `*.redirectto.cc`, Range 206); відтворюваний поза сесією через Media3 **з `Referer: https://sluhay.com/`** (проба без Referer → 403 nginx; curl з Referer → 206 audio/mpeg, без cookies). Аудіо-хост — **НЕ Cloudflare** → TLS-відбитковий ризик #72 до аудіо не застосовується; той самий Referer-механізм, що в audiobookmp3. #73 розблоковано.

## Not yet specified (fog)

- **Де живе браузерний поверх**: повноекранний браузер per-source? bottom-sheet?
  заміна чи узагальнення таба «4read Web»? (тепер — тікет #73, розблокований)
- **Як книга з WebView-сесії стає карткою медіатеки**: ручний імпорт із панелі?
  автозахоплення при програванні? які метадані беремо (назва/автор зі сторінки)?
- **Пошук у межах сесії**: глобальний пошук лишається server-fetch (sluhayua),
  чи є місток «пошук у WebView»?
- **Друге джерело (sluhayknigi)**: чи той самий патерн (mp3 + Referer) чи інший
  формат — виміряти при підключенні.

## Tickets (children of map #70)

- [#71 — T1 WebView audio interception prototype (sluhay.com)](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/71) `wayfinder:prototype` — **closed** (verdict PASS: mp3 + Referer → Media3; resolution on the issue).
- [#72 — T3 Cloudflare challenge and WebView session persistence](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/72) `wayfinder:research` — **closed** (resolution → `docs/wayfinder/research/webview-session-persistence.md`).
- [#73 — T2 UX surface of a WebView source](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/73) `wayfinder:grilling` — **frontier** (was blocked by #71; unblocked by the verdict).

Fog у тілі GitHub-мапи. Локально — лише цей міррор.

## Out of scope

- **Автоматичне обходження Cloudflare** — користувач проходить challenge сам;
  скрипти CF-байпасів не є інтентом.
- **sluhayua (sluhay.com.ua)** — окрема спека spec-11, server-fetch без WebView.
- **Android Auto / Cast для WebView-джерел** — окремі майлстоуни.
- **Повні каталоги (M2)** — жанри/автори per-source — як і для spec-10.
