# Слухайка — вільний плеєр українських аудіокниг

![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)
![Android](https://img.shields.io/badge/Platform-Android%207%2B-green.svg)
![Latest release](https://img.shields.io/github/v/release/Samuel-Ku/slukhayka?label=Latest%20release)

> **Slukhayka is a free, open-source Ukrainian audiobook player for Android.**
> Multi-source catalog, offline mode, smart sleep timer, honest playback data.
> 100% free, no ads, GPL-3.0. Android-only (iOS is not planned).

**Слухайка** — сучасний плеєр українських аудіокниг: багатоджерельний
об'єднаний каталог, офлайн-режим, розумний таймер сну та чесні дані про
прослуховування. Повністю безкоштовний і відкритий — без реклами, без
підписок, без збору даних.

<p align="center">
  <img src="docs/screenshots/01-player.png" width="24%" alt="Плеєр"/>
  <img src="docs/screenshots/02-sleep-timer.png" width="24%" alt="Таймер сну"/>
  <img src="docs/screenshots/03-speed.png" width="24%" alt="Швидкість відтворення"/>
</p>

<!-- TODO: оновити скріншоти після spec-27 P0 (чисті «Слухати» та «Медіатека»). -->

## Фічі

- **Багатоджерельний каталог** — книги з кількох українських джерел об'єднані
  в один каталог без дублів («одна книга — одна картка»)
- **Офлайн-режим** — завантажуй книги і слухай без інтернету
- **Розумний таймер сну** — «до кінця розділу», плавне затихання, shake-to-extend
- **Швидкість 0.5–3.0×** з пам'яттю для кожної книги
- **Чесні дані** — реальні тривалості, кумулятивний прогрес, без «00:00» замість невідомих значень
- **Серії та всесвіти** — зв'язки «передує / продовжує», продовження серії на головній
- **Закладки, статистика, смарт-перемотка** — позиція відновлюється з відкатом на час паузи
- **Наскрізний міні-плеєр** — контекст прослуховування на всіх екранах
- **Темна тема**, Material 3, український інтерфейс

## Встановлення

1. Перейди на [Releases](https://github.com/Samuel-Ku/slukhayka/releases)
2. Завантаж останній `app-release.apk`
3. Відкрий файл на телефоні; Android один раз попросить дозволити встановлення
   з невідомих джерел — дозволь
4. Готово. ADB не потрібен.

Цілісність файлу можна перевірити за SHA-256, опублікованим поруч з APK.

> Play Store — у планах. iOS — не планується.

## Збірка з джерел

```bash
git clone https://github.com/Samuel-Ku/slukhayka.git
cd slukhayka
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release-збірка підписується власним keystore (див. `.github/workflows/release.yml`).

## Допомога проєкту

Помилки та ідеї — у [Issues](https://github.com/Samuel-Ku/slukhayka/issues)
(шаблон bug report). Перед внеском прочитай [CONTRIBUTING.md](CONTRIBUTING.md).

Структура домену й архітектури — [CONTEXT.md](CONTEXT.md) і
[docs/adr/](docs/adr/). Історія рішень — [docs/specs/](docs/specs/).
Навігація по коду по модулях — [docs/CODEMAPS/](docs/CODEMAPS/).

## Ліцензія

[GPL-3.0-or-later](LICENSE). Вільне використання, модифікація та розповсюдження.
