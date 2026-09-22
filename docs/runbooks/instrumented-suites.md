# Інструментовані сюїти: запуск і межа CI

Інструментовані тести живуть у `app/src/androidTest/` і виконуються лише на
пристрої або емуляторі. У CI біжить **рівно один клас** — решта компілюється
кожним прогоном і не виконується ніде. Це борг **#984**, зафіксований списком
у `InstrumentedCoverageGuardTest`: додати клас у прогін можна лише явним
рішенням, бо це ціна CI, а не код.

Цей документ — як прогнати набір локально **однією командою**, як підняти
емулятор і на які граблі вже наступили.

## Коротко

```bash
scripts/run-instrumented-suites.sh          # п'ять сюїт, послідовно, по одному класу
scripts/run-instrumented-suites.sh com.slukhayka.audiobooks.accessibility.UiSurfaceAuditTest
```

Скрипт вимагає пристрій у стані `device`, ставить 3-button navigation і
вимикає анімації (як CI), жене класи по черзі й падає з ненульовим кодом,
якщо хоч один червоний. Логи — `/tmp/instrumented-<Class>.log` (каталог
перекривається `SLUKHAYKA_INSTRUMENTED_LOG_DIR`).

## Що саме бігає

| Клас | Що перевіряє |
|---|---|
| `accessibility.MainActivityAccessibilityTest` | вендорський Google ATF на реальному `MainActivity` + подорож accessibility-вузлами |
| `audio.AudioPlaybackEspressoTest` | бібліотека → тап по картці → сторінка книги → `isPlaying` за 3 с |
| `accessibility.UiSurfaceAuditTest` | 21 контрольована сцена × `fontScale` 1f і 2f: твірні розміри, перекриття, порожні стани |
| `accessibility.SettingsNavigationTest` | чотири корені × шестерня → налаштування → BACK; сім маршрутів двома способами повернення; панель не переживає зміну таба |
| `accessibility.BottomBarLargeTextLayoutTest` | підписи нижнього бару при 200 % тексту (винесено із `SettingsNavigationTest`) |

Перші чотири — це набір тікета **#852**; `BottomBarLargeTextLayoutTest`
з'явився, коли з'ясувалось, що двом тестам одного класу потрібні різні хости.

## Емулятор (перевірено 2026-09-22, API 35)

```bash
ls -l /dev/kvm                      # має існувати; режим 0666 достатній навіть без групи kvm
SDK=$HOME/android-sdk

"$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n slukhayka-api35 \
  -k 'system-images;android-35;google_apis;x86_64' -d pixel_6

ANDROID_AVD_HOME=$HOME/.config/.android/avd \
ANDROID_SDK_ROOT=$SDK \
  "$SDK/emulator/emulator" -avd slukhayka-api35 \
  -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -no-metrics &

adb wait-for-device
adb shell getprop sys.boot_completed   # 1 — можна запускати
```

**`ANDROID_AVD_HOME` тут обов'язковий.** `avdmanager` кладе AVD у
`$XDG_CONFIG_HOME/.android/avd` (`~/.config/.android/avd`), а емулятор шукає
його в `$ANDROID_AVD_HOME`, `$ANDROID_SDK_HOME/avd` і `$HOME/.android/avd` —
без змінної він відповідає `Unknown AVD name`.

## Три граблі, на які вже наступили

1. **Власне дерево тесту → контент-вільний хост.** `MainActivity` ставить
   контент у `onCreate`. Тест, який малює своє дерево поверх нього
   (`rule.activity.setContent` з `runOnUiThread`), не реєструє семантичного
   кореня — і кожен запит падає з `No compose hierarchies found in the app`.
   Таким тестам потрібен `TestHostActivity` (#766 A2) і `rule.setContent`
   (див. `UiSurfaceAuditTest`, `BottomBarLargeTextLayoutTest`).
2. **Дозволи.** Застосунок просить `POST_NOTIFICATIONS` на старті
   (`MainActivity.onCreate`). Без `GrantPermissionRule` системний діалог
   виходить на передній план, активність не досягає RESUMED — і Compose-кореня
   немає взагалі. Так падав `SettingsNavigationTest`.
3. **`UiAutomation`.** Зовнішній демон `app_process` (mobilecli) тримає
   `UiAutomation` зайнятим, і тоді падає кожен інструментований тест:
   `adb shell pkill -f app_process` перед прогоном.

## Чому по одному класу

І CI, і скрипт женуть по класу. Історична причина — «кілька класів перебивають
`UiAutomation` одне одному» — не підтвердилась (#957 показав, що зайнятий
`UiAutomation` давав зовнішній демон, а не сусідні класи), але послідовний
запуск лишається: він дає пофайловий вердикт і не змішує логи. Фільтр
`-Pandroid.testInstrumentationRunnerArguments.class=` приймає й `package=`, якщо
треба прогнати пакет цілком.

## Межа CI

Щоб клас почав бігати в CI, треба **дві** правки в одному PR:

1. додати його клас-фільтр у `.github/scripts/run-accessibility-test.sh`;
2. прибрати його з `KNOWN_UNEXECUTED_BY_CI` і, за потреби, дописати в
   `EXECUTED_BY_CI` у `InstrumentedCoverageGuardTest`.

Guard навмисно валить збірку на новому класі, якого немає в жодному списку:
мовчазна поява «скомпільованого, але не запущеного» тесту — це та сама ілюзія
покриття, проти якої писався #984.
