# Виразний дизайн: технологічний напрямок

> Статус: рекомендація для дизайну, 16.09.2026.
> Гілка: `codex/media-library-ux`. Залежності та код застосунку не змінювали.
> Офіційні версії та маніфести вибраних артефактів перевірено 16.09.2026.

Новий вигляд будуємо на чинному Jetpack Compose. Додаємо виразну типографіку,
обкладинки, тональні поверхні та зрозумілий рух між станами. Нову навігацію
спираємо на [уточнену карту Goodreads і друзів](2026-09-16-goodreads-reading-and-friends.md)
та [аудит чинних екранів](2026-09-16-app-information-architecture.md).
Переїзд екранів зберігає функції з її матриці; нові технології їх не замінюють.

## Чинний і цільовий стек

| Частина | Що є в репозиторії | Запропонований напрямок |
| --- | --- | --- |
| Android UI | Kotlin 2.2.10, Compose BOM 2025.06.01, Material 3, власні компоненти | Зберегти Compose; окремо оновити узгоджений stable BOM і компоненти |
| Android навігація | `navigation-compose` 2.8.9 підключено; екрани фактично перемикає ручний `when` і прапорці | Типізовані маршрути та окремі стеки вкладок через stable Navigation 3 |
| Великі екрани | Переважно однопанельні екрани; Adaptive не підключено | Нижня навігація / rail; список і деталі поруч за достатнього місця |
| Відтворення та дані | Media3 1.3.1, Room 2.7.0, локальні модулі, джерела, синхронізація | Зберегти їхні контракти, дані та поведінку; оновлення версій — окремі зміни |
| Web | React 18.3.1, Vite 5.4.21 у lockfile, TypeScript, PWA, IndexedDB, Firebase | Зберегти React/PWA; перенести спільні дизайн-токени, назви та сценарії |
| Android платформа | `minSdk 24`, `targetSdk 36`, AGP 9.1.1, Gradle 9.3.1 | Зберегти підтримку API 24 у межах цього напрямку |

Факти: [версії Android](../../gradle/libs.versions.toml),
[збірка Android](../../app/build.gradle.kts), [Web](../../web/package.json),
[Web lockfile](../../web/package-lock.json). Перехід на інший UI-фреймворк
або переписування плеєра не входять у цей дизайн.

## Що вже стабільне

| Бібліотека | Stable на дату перевірки | Preview | Як використовувати |
| --- | --- | --- | --- |
| Compose Material 3 | 1.4.0 | 1.5.0-alpha28 | Stable — основа; API Expressive перевіряти окремо |
| Navigation 3 | 1.1.7 | 1.2.0-rc01 | Ціль для нової моделі маршрутів — stable |
| Material 3 Adaptive | 1.3.0 | 1.4.0-alpha02 | Stable, включно з інтеграцією `adaptive-navigation3` |

Джерела: [Material 3](https://developer.android.com/jetpack/androidx/releases/compose-material3),
[Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3),
[Adaptive](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive).

**Material 3 Expressive — напрямок дизайну, а не гарантія стабільності всіх API.**
Експериментальні Expressive API прибрали з гілки 1.4 перед stable та продовжили
в 1.5 alpha. Частину API згодом стабілізують; перевіряємо конкретний компонент
і версію. Stable-артефакт теж може містити API з experimental-анотацією.
Наприклад, в Adaptive окремі API порядку панелей і зміни їхнього розміру
залишаються експериментальними. Latest alpha не є вимогою цього документа.

## Мінімальна версія Android

Перевірено `uses-sdk` у `AndroidManifest.xml` опублікованих AAR Google Maven:

| Артефакт | Версія | Заявлений minSdk |
| --- | --- | --- |
| `navigation3-runtime-android` | 1.1.7 | 23 |
| `navigation3-ui-android` | 1.1.7 | 23 |
| `adaptive-android` | 1.3.0 | 23 |
| `adaptive-layout-android` | 1.3.0 | 23 |
| `adaptive-navigation3-android` | 1.3.0 | 23 |

Офіційні перевірені AAR: [Nav3 runtime](https://dl.google.com/dl/android/maven2/androidx/navigation3/navigation3-runtime-android/1.1.7/navigation3-runtime-android-1.1.7.aar),
[Nav3 UI](https://dl.google.com/dl/android/maven2/androidx/navigation3/navigation3-ui-android/1.1.7/navigation3-ui-android-1.1.7.aar),
[Adaptive](https://dl.google.com/dl/android/maven2/androidx/compose/material3/adaptive/adaptive-android/1.3.0/adaptive-android-1.3.0.aar),
[Adaptive layout](https://dl.google.com/dl/android/maven2/androidx/compose/material3/adaptive/adaptive-layout-android/1.3.0/adaptive-layout-android-1.3.0.aar),
[Adaptive Nav3](https://dl.google.com/dl/android/maven2/androidx/compose/material3/adaptive/adaptive-navigation3-android/1.3.0/adaptive-navigation3-android-1.3.0.aar).

Ці артефакти самі по собі не вимагають підвищення API 24 до API 28.
Це ще не перевірка повного графа залежностей. Перед підключенням потрібні
Gradle resolution, manifest merge, перевірка AAR metadata та запуск на API 24.
`minSdk` і вимоги до `compileSdk` — різні обмеження. Підвищення мінімальної
версії Android не можна ховати всередині візуального оновлення.

## Як отримати сучасний вигляд

- Продовжити [кольори](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Color.kt),
  [типографіку](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Type.kt),
  [розміри](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Dimens.kt)
  та [тему](../../app/src/main/java/com/slukhayka/audiobooks/ui/theme/Theme.kt).
  Темна і світла теми, масштабування тексту й контраст лишаються частиною дизайну.
- Виразність зосередити на продовженні книги, обкладинках і головних діях.
  Щільні списки, прогрес і параметри мають залишатися спокійними та читабельними.
- Для нових іконок брати окремі Material Symbols як vector assets.
  Google рекомендує їх замість повної старої бібліотеки Material Icons:
  [примітки Material 3 1.4](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0).
- Рух пояснює відкриття, повернення та зміну стану. Не додаємо автозапуску
  аудіо, постійних анімацій чи обов'язкового blur для читання екрана.

## Послідовність без переписування

1. Узгодити вигляд і компоненти на чинних екранах. Зберегти всі дії книги,
   плеєра, бібліотеки та налаштувань із карти екранів.
2. Оновити Compose окремою технічною зміною за
   [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping).
   Перевірити сумісність Kotlin, compiler, Lifecycle та тестових бібліотек.
3. Перенести оболонку маршрутів на Navigation 3, використовуючи наявні
   composable-екрани. Серіалізовані `NavKey` та `rememberNavBackStack`
   допомагають відновлювати стек після перезапуску процесу:
   [збереження стану](https://developer.android.com/guide/navigation/navigation-3/save-state).
   Фоновий Media3-сеанс не належить життєвому циклу окремого маршруту.
4. Додати [адаптивну навігацію](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)
   і [список із деталями](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail).
   Перевірити Back, фокус і зміну розміру вікна за правилами карти екранів.
5. Expressive alpha-компоненти, якщо вони потрібні, випробувати окремо.
   Не робити їх умовою перенесення чинних функцій. Web отримує ту саму
   інформаційну архітектуру зі своїми реальними можливостями за ADR-0034.

## Межі перевірки

Макет показує композицію, стани й переходи. HTML-макет не доводить роботу
Compose, Media3, Cast, фонового відтворення, системного Back або TalkBack.
У цьому кроці прочитано код, офіційні документи та маніфести п'яти AAR.
Збірку, dependency resolution і тести не запускали; файли залежностей не змінювали.
Перед доставкою потрібні цільові перевірки змінених маршрутів і компонентів,
великий шрифт, TalkBack, API 24, телефон і широке вікно; прогрес і файли зберігаються.
