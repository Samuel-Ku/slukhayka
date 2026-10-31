# R8 smoke перед релізом

R8 увімкнений для `release` з #1702, але CI перевіряє лише те, що мініфікація
**збирається** (`:app:assembleMinifiedDebug`, job `R8 minified APK`). Чи
виживають keep-правила в рантаймі, видно тільки на пристрої. Цей рунбук — той
крок, який закриває різницю: та сама мініфікація й shrinking, але на
debug-пакеті, без release-ключа й без дотику до підписаного
`com.slukhayka.audiobooks`.

Коли запускати:

- перед тегом релізу;
- після зміни `app/build.gradle.kts`, `app/proguard-rules.pro`, версії
  NewPipeExtractor/Rhino/TDLib або будь-якої залежності, що ходить у reflection;
- після «дивного» падіння release-збірки, яке не відтворюється на debug.

## 1. Зібрати канар

```sh
.worktrees/gt ./gradlew :app:assembleMinifiedDebug
```

На робочій машині — через документований віддалений прогін
(`scripts/swarm/remote-run.py`), а не голим `./gradlew`.

Артефакт: `app/build/outputs/apk/minifiedDebug/app-minifiedDebug.apk`.
Перевірити, що це справді R8-збірка:

```sh
test -s app/build/outputs/mapping/minifiedDebug/mapping.txt
aapt2 dump badging app/build/outputs/apk/minifiedDebug/app-minifiedDebug.apk \
  | grep -E "package: name='com.slukhayka.audiobooks.debug'|application-debuggable"
```

`application-debuggable` бути **не повинно**: AGP вимикає оптимізацію й
обфускацію для debuggable-варіантів, тож канар навмисно `isDebuggable = false`.
Підпис — debug-ключ, тому пакет ставиться поруч із release-застосунком.

## 2. Поставити тільки debug-пакет

```sh
adb -s <device> install -r -d \
  app/build/outputs/apk/minifiedDebug/app-minifiedDebug.apk
adb -s <device> shell dumpsys package com.slukhayka.audiobooks.debug \
  | grep -E 'versionName|lastUpdateTime'
```

**Ніколи** не ставити `com.slukhayka.audiobooks` з цієї збірки: release-пакет
підписаний іншим ключем і є робочою копією слухача.

## 3. Дим

Кожен пункт — спостереження, а не припущення.

1. **Холодний старт.** Тричі:
   `am force-stop com.slukhayka.audiobooks.debug`, потім
   `am start -W -n com.slukhayka.audiobooks.debug/com.slukhayka.audiobooks.MainActivity`.
   Записати `TotalTime`; для порівняння — той самий прогін на
   `assembleDebug` (unminified). Порівнювати медіани, не один прогін.
2. **Слухати.** Стартовий екран рендерить полицю «Продовжити слухати» й
   «Нещодавно додані» (uiautomator dump або очі).
3. **Плеєр.** Відкрити книгу → «Продовжити слухати» → перевірити, що екран
   плеєра показує розділ, а `dumpsys media_session` має
   `state=PLAYING(3)` і позицію, що росте.
4. **Пошук.** «Огляд» → поле пошуку → запит → результати або чесне
   «Нічого не знайдено».
5. **NewPipe/Rhino.** «Мої книги» → «Додати аудіо» → «Надіслати посилання» →
   YouTube watch URL → «Переглянути». Успіх не обов'язковий (YouTube може
   віддати «unavailable»), але в logcat **не повинно** бути
   `NoClassDefFoundError`, `ClassNotFoundException`, `UnsatisfiedLinkError`
   на `org.schabi.newpipe.*` / `org.mozilla.javascript.*`.
6. **4read.** Якщо в медіатеці є 4read-видання — відкрити розділ і дочекатися
   `prepare` (cookie-шлях). **Станом на 2026-10-31 це неможливо на чистому
   пристрої:** 4read — retired scam source (ADR-0037/0041), hydration його не
   обходить, і в тестовій медіатеці немає жодного 4read-видання. Тому пункт
   або виконується на пристрої, де таке видання вже імпортоване, або
   фіксується як «not applicable» з причиною — не вигадувати успіх.
7. **Віджет.** `dumpsys appwidget | grep slukhayka` показує
   `AudiobookGlanceWidgetReceiver` для `.debug`. Якщо віджет стоїть на
   робочому екрані — перевірити, що він малюється й оновлюється; якщо ні —
   зафіксувати, що перевірено лише реєстрацію провайдера.

## 4. Логи

```sh
adb -s <device> logcat -d | grep -E \
  'FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError'
```

`uiautomator dump` інколи сам падає з `UiAutomationService … already registered`
— це не падіння застосунку; дивитися на PID у рядку `FATAL EXCEPTION` і
звіряти з `pidof com.slukhayka.audiobooks.debug`.

## 5. Повернути стан телефона

- Тему (Paper) і `stay_on_while_plugged_in` не змінювати під час диму; якщо
  змінювали — повернути попередні значення.
- Закрити застосунок після диму, не залишати активний плеєр.
- Переконатися, що `com.slukhayka.audiobooks` не оновлювався:
  `dumpsys package com.slukhayka.audiobooks | grep lastUpdateTime`.

## 6. Що робити зі зламом

- `NoClassDefFoundError`/`ClassNotFoundException` — додати точкове
  `-keep` для названого класу в `app/proguard-rules.pro`, перезібрати канар,
  повторити пункт, який зламався.
- `UnsatisfiedLinkError` — перевірити, що клас і нативні методи не
  перейменовані (`-keepclasseswithmembernames` уже в AGP-дефолті; для
  бібліотек без consumer-правил потрібен явний keep).
- Якщо злам не вдається пояснити й безпечно звузити — **не** випускати тег:
  записати stack trace, пристрій, APK і коментарем у PR/issue. Краще
  заблокувати реліз, ніж випустити мініфікований APK, що падає в слухача.
