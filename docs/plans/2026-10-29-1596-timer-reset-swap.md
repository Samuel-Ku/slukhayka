# План: #1596 — swap reset()/cancel() у таймері сну

Фінальний огляд #1587 знайшов розбіжність: `stopAndClear()` викликав
`sleepTimerModule.cancel()`, а `reset()` стояв у
`stopEngineForUnavailableBook()` — навпаки до commit/KDoc. Наслідок:
після `stopAndClear()` `module.snapshot` лишався живим, і
`extendSleepTimerBy15Minutes()` теоретично міг re-arm-нути старий залишок
(у поточному UI недосяжно, бо `PlayerState()` без книги не показує «+15»).

## Рішення

1. `stopEngineForUnavailableBook()` → `sleepTimerModule.cancel()` (зупинити
   відлік; стан екрана помилки не чіпати).
2. `stopAndClear()` → `sleepTimerModule.reset()` (скасувати й забути стан, не
   чіпаючи volume).
3. План #1573 виправлено: перелік портів тепер три volume-порти
   (`setFadeVolume` cast-aware, `restoreVolume` local-only,
   `restoreVolumeAfterFinish` cast-aware).
4. Контрактний тест модуля: після `reset()` `extendBy15Minutes()` повертає 0 і
   не стартує ticker; `cancel()` і далі лишає стан.

## Межі

- змінюються лише два виклики в менеджері, документ і один тест; модуль уже
  має `reset()`; UI/поведінка не змінюються.

## Перевірка

- `:app:testPureJvm -Ptest.selectedClasses=…SleepTimerModuleTest` — зелені
  (13 тестів).
- Мутація: прибрати `apply(SleepTimerSnapshot())` з `reset()` → новий тест
  червоніє; `:app:testRoomRobolectric …SleepTimerTest` — 21/21.
