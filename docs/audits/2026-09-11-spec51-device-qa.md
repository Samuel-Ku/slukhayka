# QA spec-51 — збірка, встановлення й пристрій (2026-09-11)

## Що це

Прогін QA поточного дерева `spec-51-f1-librivox-admission` (багатомовний
LibriVox + Дзеркало каталогу + First Language Choice) на OnePlus 8 Pro
(Android 14). Мета — зібрати, поставити поверх чинного пакета без втрати
даних і перевірити ключові сценарії.

## Збірка й встановлення

- Під час роботи дерево **не компілювалося** (F2-рефактор паралельного
  агента був у процесі): `MainActivity.kt:1243 Unclosed comment`,
  `FirstLanguageChoiceSheet.kt:62 Unresolved reference 'accessibilityPane'`,
  `ContentLanguageChip.kt:39 @Composable invocations…`. Після завершення
  правок агента реліз зібрався.
- `./gradlew :app:assembleRelease` — **BUILD SUCCESSFUL**.
- APK: `app/build/outputs/renamed_apks/release/slukhayka-v1.3.9-spec51.apk`
  (versionCode 14, versionName 1.3.9-spec51), SHA-256
  `8fca18a3f7da3413969c37421d9a0bc5ed26f20590bcd7e06bb5dd6032f21cc1`.
- Підпис: сертифікат SHA-256
  `292b9d69fde8919b41e413867c2eb2a8364b6d6add29acccaa6ec96a7e358464` — той
  самий, що у встановленого `1.3.9-integrated` (vc13), тому `install -r`
  оновив пакет без видалення.
- `adb install -r` → **Success**: vc13 (1.3.9-integrated) → vc14
  (1.3.9-spec51), `firstInstallTime` збережено, Медіатека/прогрес на місці.
- Примітка: injected-властивості AGP `android.injected.version.code`
  більше не застосовуються в AGP 9 — versionCode піднято тимчасовою
  правкою `build.gradle.kts`, яку одразу повернено.

## Цільові JVM-тести

`./gradlew :app:testDebugUnitTest` для `LanguageCodeTest`,
`LibriVoxAdapterTest`, `LibriVoxCatalogTest`, `UnifiedCatalogRepositoryTest`,
`CatalogMirrorWriteThroughTest` — **BUILD SUCCESSFUL** (на стабільному
стані дерева).

## Що підтверджено фізично

- Застосунок стартує, процес живий, `FATAL EXCEPTION`/ANR у logcat немає.
- **First Language Choice** з'явився на першому запуску: «Якими мовами
  показувати книжки?», усі мови ввімкнено, кнопки «Лише українські» /
  «Готово».
- Медіатека збереглася: «ПРОДОВЖИТИ СЕРІЮ» (Гаррі Поттер), «ЩОСЬ КОРОТКЕ»,
  «НЕЩОДАВНО ДОДАНІ», Медіатека в навігації.
- **Багатомовний пошук LibriVox працює**: «Alice in Wonderland» → 18
  результатів, картки з бейджем **EN** і міткою джерела «LibriVox»;
  «Stevenson» → 20 результатів (напр. «An Island Voyage», EN).
- Екран «Мови контенту» відкривається, показує «Усі» + «Українська».

## Проблеми

### 1. (середня, до перевірки) Розведення джерел для перелічених LibriVox-творів

У logcat після оновлення каталогу:

```
W SourceCatalog: No chapters for bookId=librivox-bothsides_2608_librivox
and 4read fetch returned none; refusing to fabricate placeholder audio.
```

Твір із `sourceId=librivox`, а шлях отримання розділів каже «4read fetch».
Або функціональне розведення джерел зламане для творів із Дзеркала каталогу,
або це лише оманливий текст логу. Потрібно перевірити `getPlayableChapters`
для творів, записаних `persistEnumerated` (spec-51 + ADR-0041).

### 2. (середня) Мови контенту лишаються uk-only після оновлення каталогу

Після оновлення каталогу «Мови контенту» (і First Language Choice) не
пропонують `en`/`de`, хоч багатомовний LibriVox уже admitted, а
`knownEditionLanguages()` читає всі `editions`/`edition_facets`, не лише
Медіатеку. Отже або union/фід не записав багатомовні Edition-рядки
(`persistEnumerated`), або API-транспорт LibriVox не перелічився. Перевірити
фактичний вміст `editions.language` після refresh.

### 3. (низька) LibrarySeeder ходить на cleartext `http://archive.org`

У logcat повторювано:

```
W HttpFetcher: HEAD http://archive.org/download/.../01.mp3 failed
java.net.UnknownServiceException: CLEARTEXT communication to archive.org not permitted by network security policy
```

Авто-сід перевіряє досяжність стрімів `http://` і падає на політиці
cleartext. Сід деградує, але це сміття в логах і пропущені перевірки;
для archive.org очікується `https`.

### 4. (низька, спостереження) Пошук німецької назви дав 0

«Schatzinsel» → 0 результатів у всіх джерелах, тоді як англомовні
запити/автори дають 18–20. Можливо, немає відповідного німецького
елемента в `librivoxaudio`, або архівний повнотекстовий пошук не матчить
німецьку назву. Потрібен запит із точно відомим німецьким твором.

### 5. (низька) KSP2 NPE у логах release-збірки

```
Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException:
… ksp.com.intellij.openapi.application.ApplicationManager.getApplication() is null
```

Відомий шум KSP на headless JVM; білд не валить, але засмічує лог.

### 6. (низька) Тест-хелпер `FakeAudiobookDao` не компілюється (F2, у роботі)

`FakeAudiobookDao.kt:500–507` — `List<String>` vs `List<String?>` і
nullable-отримувачі. Паралельний агент ще править; на момент прогону
`:app:testDebugUnitTest` падав на `compileDebugUnitTestKotlin`.

## Прибирання гілок (окремо, виконано)

- Локально видалено 15 гілок (12 повністю влитих у `release/v1.3.10` /
  `spec-51-f1`, 3 застарілі `wip`/`rescue`, що лишились на `origin`),
  прибрано stale worktree `.scratch/release`, `main` підтягнуто до
  `origin/main`.
- На `origin` видалено 5 влитих `wave/*`.
- Лишено за рішенням: `codex/*`, `spec-49`, `wip`, `rescue`,
  `ci/node24-actions`, bundle-remotes.

## Відкриті пункти

1. Пункти 1–2 перевірити на чистому пакеті після повного каталожного
   sync; за потреби — окремі issue з міткою `ready-for-human`.
2. Повторити збірку/встановлення, коли F2 остаточно закомітять і CI
   буде зелений.
