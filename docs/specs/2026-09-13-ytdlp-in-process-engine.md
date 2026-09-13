# Спека: рушій yt-dlp у процесі — Chaquopy

> Status: Proposed. Опубліковано в issue tracker: **#779** (`ready-for-agent`).
> Гілка: `feat/ytdlp-in-process-engine` (від `main`).
> Пов'язані: #783 (прибрати NewPipeExtractor) тримає #784 (ліцензія);
> #780, #781, #782 стоять за цим рушієм.

## Problem Statement

`yt-dlp` сьогодні запускається як **зовнішній бінарник**:

```kotlin
ProcessBuilder("yt-dlp", "-J", "--no-download", "--no-playlist", "--no-warnings", url)
```

Бінарника на пристрої немає, тож цей шлях у production не працює, а
`NewPipeExtractor` (GPL-3.0) лишається єдиним робочим рушієм YouTube — і саме він
блокує переліцензування всього застосунку під BSL (#783 → #784).

Мета: `yt-dlp` працює **всередині** застосунку (вбудований CPython), віддає той
самий JSON, і в production-шляху немає жодного зовнішнього бінарника.

## Що вже зроблено

- **Шов** (`4f6d12b9`): `YtDlpEngine` + `YtDlpArguments`. Обидва списки опцій
  чисті, ім'я програми в них **не входить** — процесний рушій додає його сам,
  а вбудований імпортує модуль. `ProcessYtDlpEngine` лишається dev-фолбеком і
  єдиним, кому потрібен `yt-dlp` на `PATH`.
- **Версія плгіна в каталозі** (`3a3aed74`): `[versions] chaquopy` +
  `[plugins] chaquopy`, підключення через `alias(libs.plugins.chaquopy)`.
  До цього версія була зашита в `app/build.gradle.kts` і **Renovate її не
  бачив** — оновлення не прийшло б ніколи.
- **Спайк #777** (закрито, GO): двигун запускався на пристрої, цифри нижче.

## Перевірені пастки — не наступати вдруге

**1. Configuration cache.** Chaquopy 17.0.0 із ним не просто попереджає, а
**валить збірку за 3 секунди** під час збереження запису:

```
- Plugin 'com.chaquo.python': external process started '.../check_build_python.py'
- Task ':app:extractDebugPythonBuildPackages': cannot serialize JavaCompile /
  error writing value of type 'java.lang.ref.ReferenceQueue'
FAILURE: Configuration cache state could not be cached
```

`org.gradle.configuration-cache.problems=warn` **сам цього не лікує** — він
понижує заявлені проблеми, а не збій збереження. Потрібні обидва:
`notCompatibleWithConfigurationCache` на задачах Chaquopy прибирає фатальний
збій, а прапорець поглинає решту 3379 проблем. Перевірено експериментом: без
прапорця `BUILD FAILED`, з ним `BUILD SUCCESSFUL` з **відкинутим записом**
(`Configuration cache entry discarded with 3379 problems`).

**Ціна виміряна** (той самий worktree, теплий демон, `./gradlew help` — фаза
конфігурації в чистому вигляді): з кешем 2049 мс, без кеша 2465/2554 мс. Тобто
втрата кеша коштує близько **пів секунди на збірку**, а не хвилин. Через це
варіант «наперед зібраний рантайм CPython» задля збереження кеша **не вартий
своєї складності**; прийнятні або явне вимкнення кеша, або прапорець.

**2. ABI.** `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` обов'язковий і
мусить стояти в `android.defaultConfig`, **не** всередині `chaquopy {}` — інакше
`Unresolved reference 'ndk'`.

**3. `install` живе тільки тут:** `chaquopy { defaultConfig { pip { install(...) } } }`.
Поза `defaultConfig` не працює.

**4. Версія `yt-dlp`** закріплюється разом із `install(...)`; щоб Renovate її
бачив, версію треба тримати в `gradle/libs.versions.toml`, а не рядком у
`app/build.gradle.kts`.

## Що лишається зробити

1. `app/src/main/python/ytdlp_bridge.py` — місток. **Не** покладатися на
   перехоплення stdout від `yt_dlp.main`: у процесі це крихко. Ідіоматичніше
   віддати рядок напряму — `YoutubeDL(opts).extract_info(url, download=False)`
   і повернути `json.dumps(...)`. Опції з `YtDlpArguments` транслюються в
   словник `YoutubeDL` (той самий зміст: resolve-only, без плейлиста,
   без попереджень).
2. `ChaquopyYtDlpEngine : YtDlpEngine` — `Python.getInstance().getModule(...)`,
   `callAttr`, з `Dispatchers.IO`; збій → `null` (як у `ProcessYtDlpEngine`),
   скасування → `CancellationException` назовні.
3. Підстановка вбудованого рушія як production-рушія (dev-фолбек лишається).
4. `install("yt-dlp==<версія>")` + версія в каталозі.
5. Визначитися з configuration cache (див. пастку 1) — рішення власника.

## Робоча конфігурація спайку (verbatim)

Єдине місце, де цей блок існував, — гілка `spike/chaquopy-step1`; у гілці #779
він **неповний** (без `pip`). Тримаю тут, щоб гілку можна було відпустити, не
втративши перевірену на пристрої конфігурацію:

```kotlin
chaquopy {
    defaultConfig {
        version = "3.14"
        buildPython("python3.14")
        pip {
            install("yt-dlp")
        }
    }
}
```

Спайк ставив `yt-dlp` **без піна**; версію закріплює #779 (критерій 2), і саме
тому її треба тримати в `gradle/libs.versions.toml`. Розв'язаний тоді артефакт:
`yt_dlp-2026.8.19-py3-none-any.whl` (3.2 МБ, чистий Python).

## Acceptance

| критерій #779 | як доводимо |
|---|---|
| JSON-провайдер працює в процесі | прогоном на пристрої: реальне відео → JSON, у звіті назва, тривалість, кількість форматів |
| Версія закріплена й трекована | версії в `gradle/libs.versions.toml`; плгін уже там |
| Жодного зовнішнього бінарника | `ProcessYtDlpEngine` не в production-шляху; перевірити, що робочий шлях іде через вбудований |

**Перевірка обов'язково на пристрої**: рушій ходить у мережу й на YouTube, тож
компіляція нічого не доводить.

## Виміряне спайком (для очікувань)

`pythonInitMs` 351/401/379; `resolveMs` 2133/2492/2488; тривалість 635 с,
форматів 53, audio-only 16; три прогони без збоїв.

Розмір APK: 178.4 МБ → 115.4 (лише abiFilters) → 154.4 (Chaquopy) = **нетто
−24.0 МБ**; власний внесок Chaquopy +39.0 МБ; рантайм ≈10.6 МБ/ABI
(6.5 нативний + 4.1 stdlib). Холодний старт 1.91 с проти 2.01 с — регресії
немає. Chaquopy 17.0.0 підтримує AGP 9.0–9.2.
