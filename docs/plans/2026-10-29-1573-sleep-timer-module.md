# План: #1573 — таймер сну як один глибокий модуль

Сьогодні «Таймер сну» (spec-22 T5) живе розсипом у `AudioPlayerManager`:
поля `sleepTimer`/`shakeDetector`/`fadeWarningEmitted`, `CountDownTimer` з
`onTick`/`onFinish`, крива згасання, прослуховування шейка, авто-закладка,
пауза, `TIMER_STOP`-подія, переозброєння «до кінця розділу» та +15 хв —
усе всередині 2 800-рядкового менеджера. Кожен ефект важко тестувати без
реального лупера.

## Рішення

1. **`SleepTimerModule`** — власник відліку й усіх timer-caused ефектів:
   - режими: `start(minutes)`, `startEndOfChapter(remainingMs)`, `clear()`,
     `cancel()`, `extendBy15Minutes()`, `onChapterSwitch(...)`;
   - уся крива згасання, one-shot `FadeWarning`, armed shake, finish
     (авто-закладка → пауза → TIMER_STOP → volume 1.0 → reset), переозброєння
     «до кінця розділу» — за швом.
2. **Інжектовані адаптери:** `SleepTicker` (прод: `CountDownSleepTicker` на
   `CountDownTimer`, 1-секундний такт) і `ShakeSource` (прод:
   `ShakeDetectorSource` на акселерометрі); тести керують тактами вручну.
3. **Порти ефектів `SleepTimerEffects`:** `onState`, `setFadeVolume`
   (cast-aware, як старий tick), `restoreVolume` (лише локальний engine, як
   старий cancel/extend), `restoreVolumeAfterFinish` (cast-aware, як старий
   `onFinish`), `pause`, `writeAutoBookmark`, `recordTimerStop`, `emitNotice`,
   `onEndOfChapterArmed`, `stopContext`. Менеджер лише постачає їх і делегує
   публічні команди; стан модуль видає СИНХРОННО, тож `PlayerState`
   оновлюється в момент такту (без корутинного стрибка й тестових гонок).
4. **Поведінка не змінюється:** ті самі режими, та сама 1-секундна cadence,
   той самий порядок finish-ефектів, той самий callback армування (автоматичне
   переозброєння не рахується як нове прохання режиму), ті самі повідомлення.
5. **Контрактні тести** `SleepTimerModuleTest` (чистий JVM, ручні фейки):
   армування, fade-значення по секундах, one-shot warning, shake→+15 хв,
   finish-порядок, заборона розширення після finish, переозброєння на межі
   розділу без другого arm, `clear`/`cancel`.

## Межі

- менеджер не рефакториться далі: лише видалення timer-полів/методів і
  делегування; публічні `setSleepTimer`, `extendSleepTimerBy15Minutes`,
  `handleSleepTimerShake`, `sleepTimerNotices` зберігають контракт;
- `SleepTimerNotice`, `PlayerState`-поля, UI не змінюються;
- рівно один новий прод-файл модуля + один тестовий; наявний
  `SleepTimerTest` (інтеграція з реальним `CountDownTimer`) лишається зеленим.

## Перевірка

- `:app:testRoomRobolectric -Ptest.selectedClasses=…SleepTimerModuleTest,…SleepTimerTest`
  — зелені (контракт + наявна інтеграція).
- Мутації: (а) зламати fade-криву; (б) прибрати `pause()` з finish;
  (в) `+900` → `+600`; (г) зробити re-arm no-op — кожна має впасти у
  відповідному контрактному тесті.
- Повний `:app:testPureJvm`/Robolectric — CI.
