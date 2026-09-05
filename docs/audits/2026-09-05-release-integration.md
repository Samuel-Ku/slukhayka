# Зведення всієї зробленої роботи в 1.3.9

Користувач 5 вересня прямо попросив звести всю зроблену роботу в реліз.
База: cdf458af. Попередні результати телефона стосуються цієї бази,
а не нової інтегрованої збірки. Видалення remote-гілок зупинене.

## Послідовність інтеграції

1. Люди й діагностика: origin/codex/people-crash-wave (e9c80d1b).
   Конфлікти поєднані, код компілюється. 80 із 81 тесту пройшли разом;
   один SDK 35 після runtime-помилки змішаних SDK пройшов окремо.
   Два переходи реальних схем (release26 і people25) пройшли повну
   валідацію Room зі збереженням person_bookmarks. 38 Python-тестів пройшли.
   Зберігаються обидва набори тестів consent; розширений typed exit reporter
   стає єдиним підключеним детектором. Не дублювати діалоги згоди.
   Старі схеми 25/26 збережені; lastNotifiedCount переходить у 26→27.
2. Звірити й перенести WIP spec45/f4ec1049 і 282865f8: AppLocaleScreen,
   escaped LibriVox metadata та наскрізний запис мови Edition.
3. Перенести відсутні зміни pr-split: 808d121f популярність, 44a31caf
   рекомендації, 6b5e6c7d двері 4read, 8e331d96 прямий імпорт, e3125de8
   consent backup. 5c593a6a, c46e8ebb, bee2a9e7 звірити з новішими
   реалізаціями recovery/transport у кандидатові, не відкотити їх.
   Схема популярності також використовує 27: узгодити наступний номер.
4. Звірити решту старих гілок і резервів за поведінкою/патчами.
   Старий com/example пакет із backup не є відсутньою новою функцією.
5. Адресні тести після кожного блоку; повні передрелізні перевірки, CI
   та телефон після завершення інтеграції. Лише тоді новий кандидат.

## Інвентаризація

Кількість нижче — лише git cherry, а не число відсутніх функцій.
Squash, редаговані cherry-pick і merge-коміти потребують звірки вмісту.
Унікальних SHA серед усіх списків: 127. Main має нуль унікальних патчів
відносно кандидата, хоча один SHA не входить в його історію.

| Гілка | Патчі для звірки |
|---|---:|
| `codex/handoff-fix422-2026-08-29` | 3 |
| `codex/handoff-local-root-2026-08-29` | 1 |
| `codex/issue-394-download-controls-v2` | 2 |
| `codex/issue-401-people-tab` | 10 |
| `codex/issue-411-opt-in-crash-reports` | 1 |
| `codex/issue-413-unexpected-playback-exit` | 2 |
| `codex/issue-423-accessibility-emulator` | 5 |
| `codex/people-crash-wave` | 44 |
| `codex/rewiev-loading` | 45 |
| `codex/spec-452-android` | 13 |
| `main` | 0 |
| `release/v1.3.7-stability` | 0 |
| `release/v1.3.8` | 0 |
| `wip/spec45-snapshot` | 1 |
| `origin/backup-local-2026-08-27-before-sync` | 8 |
| `origin/backup/lane-stack-main-2026-08-30` | 7 |
| `origin/backup/pre-clean-2026-08-30` | 2 |
| `origin/backup/wave0-broken-merge-2026-08-30` | 0 |
| `origin/ci/parallel-room-legs` | 0 |
| `origin/codex/fix-accessibility-ci` | 12 |
| `origin/codex/handoff-fix422-2026-08-29` | 3 |
| `origin/codex/handoff-issue394-2026-08-29` | 1 |
| `origin/codex/handoff-local-root-2026-08-29` | 1 |
| `origin/codex/issue-394-download-controls` | 2 |
| `origin/codex/issue-401-people-tab` | 10 |
| `origin/codex/issue-411-opt-in-crash-reports` | 1 |
| `origin/codex/issue-413-unexpected-playback-exit` | 2 |
| `origin/codex/issue-423-accessibility-emulator` | 5 |
| `origin/codex/people-crash-wave` | 43 |
| `origin/codex/rewiev-loading` | 45 |
| `origin/codex/spec-452-android` | 13 |
| `origin/codex/spec42-t305` | 1 |
| `origin/codex/spec42-t307` | 1 |
| `origin/codex/spec42-t308` | 1 |
| `origin/fix/388-fk-crash` | 1 |
| `origin/fix/4read-import-persistence` | 0 |
| `origin/fix/507-cancel-stuck` | 0 |
| `origin/fix/519-alternative-source` | 12 |
| `origin/fix/jvm-flaky-baseline` | 0 |
| `origin/main` | 0 |
| `origin/pr-split/411-crash-consent` | 12 |
| `origin/pr-split/435-ci-atd` | 12 |
| `origin/pr-split/440-4read-search-doors` | 9 |
| `origin/pr-split/449-chapter-names` | 6 |
| `origin/pr-split/477-direct-import` | 10 |
| `origin/pr-split/485-popularity-signals` | 7 |
| `origin/pr-split/486-recommendations` | 8 |
| `origin/pr-split/508-edition-language` | 3 |
| `origin/pr-split/516-playback-without-dns` | 5 |
| `origin/pr-split/519-alternative-source` | 4 |
| `origin/pr-split/wip-base` | 2 |
| `origin/release/v1.3.7-stability` | 0 |
| `origin/release/v1.3.8` | 0 |
| `origin/wip/spec45-snapshot` | 1 |

## Незакриті умови релізу

- Перевірена міграція всіх релевантних схем без втрати даних.
- Уся зроблена функціональність або інтегрована, або доказово вже присутня.
- Зелений CI та підписаний APK саме інтегрованого коміту.
- Повтор основних сценаріїв на актуальній збірці телефона.
- Фізичні Tor/proxy/повернення мережі ще потребують дозволу на тимчасовий тест.
- Відновлення трьох карток на попередньому Xiaomi не підтверджено.

## Додаткове виправлення доступності

CI бази cdf458af: повернення фокуса до розділу та картки бібліотеки
пройшли; падіння перемістилося до library_overflow_button після виходу
з профілю (MainActivityAccessibilityTest:459). Кнопка отримала той самий
canFocus=true для Touch mode. Додано opt-in LiveChapterFocusTest для
цього точного маршруту; фізичний прогін потрібен на інтегрованому APK.

Весь оригінальний ланцюжок мовних виправлень і популярності від
f4ec1049 до e3125de8 входить в origin/pr-split/435-ci-atd (2d1210e9).
Це наступна цілісна гілка для інтеграції; нові merge-вершини окремих
pr-split не слід плутати з відсутньою функціональністю.

## Другий блок інтеграції

Зведено ланцюжок `origin/pr-split/435-ci-atd` (2d1210e9): мова
застосунку й контенту, escaped JSON LibriVox, мова Edition у фасетах,
популярність і рекомендації, прямий імпорт та пошукові двері 4read,
consent backup, попередня збірка APK для ATD-перевірки.

Конфлікт двох різних схем 27 розв'язано спільною схемою 28. Міграція
ідемпотентно додає поля людей і таблицю popularity_assertions. Чотири
реальні експорти (people25, release26, people27, popularity27) пройшли
Room-валідацію та перевірку збереження рядків. Цільова група каталогу,
імпорту, transport і popularity пройшла (`integrate-catalog-tests-fixed.log`).
Вісім тестів web workFeed також пройшли.

У transport залишилися актуальні клієнти, прив'язані до вибраного маршруту,
скасування запитів при зміні маршруту та cookies. Додано обмежені
same-protocol редиректи з підтримкою відносного Location. Стару реалізацію
`findAlternativeSource` з автоматичним вибором не повернуто: її намір
реалізує поточний явний `findAnotherSource` і NarrationSwitchGate.

Перевірка першого merge знайшла й дала підставу виправити три дефекти:
успішний Firestore Task<Void> помилково вважався невдалим; нова Edition
шукала Work за ID картки без library_entries; remote sync скидав локальні
позначки перегляду та сповіщень. Додано перевірку null-result Task,
збереження watermark і проєкції реального імпорту.

Окрема робоча папка Slukhayka-v1.3.7 перевірена: незакомічених змін немає.

## Звірка залишків гілок

- `codex/spec-452-android`: усі 13 patch-different комітів присутні за
  поведінкою. Web player, api, availability, cursor і private cache модулі
  ідентичні; Catalog має додаткову локалізацію та мови. AuthorIndex
  ідентичний. Android session/availability/MediaRangeValidator збережені,
  coordinator додатково підтримує новіше відновлення й прямий імпорт.
- Старі #400/#394/#388: production wiring закладок, дії завантажень,
  PAUSED→видалення→IDLE та transactional FK guards присутні в наступних
  комітах; повторне накладання старих патчів не потрібне.
- Browser #427–433: ізоляція сесії, захист ідентичності, coordinator,
  фактичний Player verdict і verified profile вже є в історії кандидата.
- `5984ad3a` перенесено як `4ae0d8a9`: Referer лише для archive SoundBooks,
  підписані URL не змінюються, назви декодуються зі збереженням `+`.
  Для пошкодженої назви обрано пізніший fallback «Глава N» замість сирих
  percent-послідовностей. Обидва набори перевірок адаптовано й пройдено.
- `7b4197c6` (#450/#451): вирівнювання рядків, окремий рядок циклу,
  test tags і відсутність старої гідратації вже є. Актуальні 48dp цілі
  натискання та відновлення фокуса зберігаються.
- Старі com/example backups: це попередній пакет; обкладинки, URL/JSON
  decoding, каталог та імпорт уже розвинені в com.slukhayka.audiobooks.
  Резервна bundle зберігає повну історію, включно зі старими знімками.
- `d54aecc9` — старий handoff/spec/version snapshot, перекритий поточними
  діагностикою й release docs. Його номер збірки не повертаємо.

Не включений незавершений WIP `2e83981d`: додаткові швидкі кнопки прямо
в рядках PeopleScreen. Він використовує displayName замість role-scoped
ідентичності та локальний початковий notify state. Завершені кнопки на
сторінках людини/автора/книги включено. Гілка й bundle зберігають цей WIP.

## Перевірка інтеграційних виправлень

51 цільовий тест пройшов після виправлення Task<Void>, narrator projection,
local watermark і сумісності мов/відновлення. Окремий наступний прогін
SoundBooks та прямого імпорту також пройшов, включно з проєкцією реально
імпортованої Edition через library_entries.

Другий review виявив три виправлені крайові випадки: Authorization при
переході на інший порт; SYSTEM locale при старому pref і порожньому
LocaleManager; повернення пошукових результатів після мови, що дала
порожній список. Останній випадок використовує спільний updateSearchQuery,
включно зі станами очікування та помилки, а не окрему неповну копію.

Остання адресна група AppLocale, PlaybackTransport, ContentLanguageSurfaces
пройшла. Повна вебперевірка: 222 тести, TypeScript і production build
пройшли. Розподіл Android-тестів валідний (297 класів).

## Фізична перевірка інтеграції

На debug APK `b1bf0f2d…` (3bb6c34e) пройшли recovery, керування частково
скачаною книгою та автоматичний перехід (3 тести, 20,646 с). Повністю
нескачана 4read-книга пройшла керування й стрімінг (12,613 с).

Сценарій невдалого джерела виявив реальне падіння нової полиці на Work
без автора: PersonNewArrivals передавав порожнє ім'я в strict identity.
Цей кандидат не придатний до публікації. Додано пропуск відсутніх імен
лише в detector новинок (самі книги лишаються), regression для автора,
виконавця, NBSP, проєкції та сповіщення; потрібен повтор на новому APK.

CI 3bb6c34e зупинив accessibility ще до маршруту: fresh install тепер
правильно бере English від ATD, тоді як тест очікує uk. Додано явну
українську локаль test fixture до запуску Activity, із поверненням
попереднього вибору після тесту. Продуктовий SYSTEM default збережено.

Перший повтор cancel/resume на виправленому APK отримав близько 492 МБ
за 300 секунд, але наступний великий розділ не встиг завершитися. Саме
скачування лишалося активним, а `finally` його скасував. Device-harness
тепер приймає як доказ продовження або новий готовий розділ, або реальний
приріст байтів; хеші всіх уже готових файлів двох книг усе одно звіряються
після кожного скасування.

Повтор уточненого сценарію пройшов за 7,717 с: два цикли продовження дали
реальний мережевий прогрес, після кожного скасування стан повернувся в
PAUSED, а хеші готових файлів обох книг не змінилися.

CI 5e119409 виконав SDK35 тести успішно, але Gradle позначив задачу
`UP-TO-DATE` без службового `test-worker-identities.tsv`, якого не було в
переліку outputs. Upload крок через це впав. Файл identity оголошено output
нативних Room-задач і очищається перед реальним виконанням; кеш тепер або
повертає його разом із тестами, або відсутній output змушує тест запуститися.
