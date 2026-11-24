# R8 smoke перед релізом

R8 увімкнений для `release` з #1702. Перевірок дві, і вони доводять різне:

- **канар `minifiedDebug`** — та сама мініфікація й shrinking, але на
  debug-пакеті: без release-ключа, без google-services, з
  `CRASH_REPORTING_ENABLED=false`. Дешевий, тож його можна ганяти після
  кожної зміни keep-правил;
- **release candidate** — справжній підписаний release-APK із
  `CRASH_REPORTING_ENABLED=true` і google-services. **Обов'язковий перед
  тегом релізу**, навіть коли канар зелений.

CI job `R8 minified APK` перевіряє лише build-time половину: що
`assembleMinifiedDebug` реально пройшов через R8 (непорожній `mapping.txt`),
що APK **не** `application-debuggable`, має applicationId
`com.slukhayka.audiobooks.debug` і підписаний debug-ключем. Рантайм-дим на
пристрої — цей рунбук.

## Що доводить канар, а що — ні

Канар доводить:

- R8 і `shrinkResources` справді виконуються на release-налаштуваннях
  (непорожній `mapping.txt`, відсутній `application-debuggable`);
- мініфікований застосунок ставиться, стартує й проходить вправи з
  [розділу 3](#3-спільний-список-диму) без `NoClassDefFoundError`,
  `ClassNotFoundException` та `UnsatisfiedLinkError`;
- це саме canary-пакет: applicationId `.debug` і debug-підпис, тож він не
  може випадково стати релізним бінарником.

Канар **не** доводить:

- що релізний бінарник працює: інший applicationId, інший ключ підпису;
- релізну конфігурацію під ним: у канарі `CRASH_REPORTING_ENABLED=false`,
  google-services немає, а задача `uploadCrashlyticsMappingFileMinifiedDebug`
  навмисно вимкнена;
- release-маніфест і ресурси, Play-доставку (split/ABI), підпис і
  встановлюваність підписаного артефакта;
- шлях ідентичності/Firestore, який оживає лише з google-services
  (ADR-0021);
- доставку звітів Crashlytics і завантаження `mapping.txt`.

Тому «keep-правила перевірені на канарі» — це твердження про канар.
Перед тегом потрібен дим release candidate.

## Коли запускати

- канар — після зміни `app/build.gradle.kts`, `app/proguard-rules.pro`,
  версії NewPipeExtractor/Rhino/TDLib або будь-якої залежності, що ходить у
  reflection;
- канар — після «дивного» падіння release-збірки, яке не відтворюється на
  debug;
- release candidate — **завжди перед тегом**, навіть якщо канар зелений.

## 1. Канар: зібрати й перевірити ідентичність

```sh
.worktrees/gt ./gradlew :app:assembleMinifiedDebug
```

(зі смуги — `../gt`). На робочій машині — через документований віддалений
прогін (`scripts/swarm/remote-run.sh`), а не голим `./gradlew`.

Артефакт: `app/build/outputs/apk/minifiedDebug/app-minifiedDebug.apk`.
Перевірити, що це справді R8-збірка й саме канар:

```sh
apk=app/build/outputs/apk/minifiedDebug/app-minifiedDebug.apk
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
badging=$(mktemp)

test -s app/build/outputs/mapping/minifiedDebug/mapping.txt
"$bt/aapt2" dump badging "$apk" > "$badging"
grep -E "^package:" "$badging"
# debuggable-канар означав би, що AGP вимкнув R8
if grep -q '^application-debuggable' "$badging"; then
  echo "ЗЛАМ: debuggable — AGP вимкнув R8"
fi
rm -f "$badging"
"$bt/apksigner" verify --print-certs "$apk" | grep 'certificate DN'
```

Очікування:

- `package: name='com.slukhayka.audiobooks.debug'`;
- рядка `application-debuggable` **немає**. AGP вимикає оптимізацію й
  обфускацію для debuggable-варіантів, тож debuggable-канар означав би, що
  job зелений, а R8 не працював;
- `certificate DN` містить `CN=Android Debug` — це debug-ключ, не release.

Підпис — debug-ключ, тому пакет ставиться поруч із release-застосунком.
**Ніколи** не ставити `com.slukhayka.audiobooks` з канарної збірки: це
release-пакет, підписаний іншим ключем.

## 2. Release candidate: обов'язково перед тегом

### 2.1 Передумови

- **Release-ключ.** Або gitignored `keystore.properties`, або змінні
  `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` — ті самі, що читає
  `release.yml`. Не тримати пароль у shell history: краще
  `keystore.properties` або секрети CI.
- **Google-services.** `app/src/release/google-services.json` має існувати
  (у CI його відновлює `release.yml` із
  `GOOGLE_SERVICES_RELEASE_JSON_BASE64`). Без нього збірка не падає
  (`MissingGoogleServicesStrategy.WARN`), але застосунок тихо переходить на
  LocalOnlyIdentity — за ADR-0021 це не релізний кандидат.
- **E5-модель.** `:app:assembleRelease` виконує `verifyE5ModelAssets` і падає,
  якщо моделі немає; на чистій машині її тягне `:app:downloadE5Model`.
- **Пристрій.** Release candidate має production applicationId
  `com.slukhayka.audiobooks`, тож `install -r` **оновлює** пакет на пристрої.
  Використовувати окремий тестовий пристрій, не щоденний телефон слухача.
  Якщо іншого немає — спитати власника явно, записати поточний `versionCode`
  і APK, і пам'ятати, що дані слухача на цьому пристрої лишаться.

### 2.2 Зібрати

```sh
.worktrees/gt ./gradlew :app:downloadE5Model :app:assembleRelease
```

Якщо ключ не в `keystore.properties`:

```sh
KEYSTORE_PATH=/path/to/upload.jks \
STORE_PASSWORD='…' KEY_PASSWORD='…' \
  .worktrees/gt ./gradlew :app:assembleRelease
```

Артефакт: `app/build/outputs/apk/release/app-release.apk` (версійна копія —
у `app/build/outputs/renamed_apks/release/`).

### 2.3 Перевірити артефакт

```sh
apk=app/build/outputs/apk/release/app-release.apk
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
badging=$(mktemp)

test -s app/build/outputs/mapping/release/mapping.txt
"$bt/aapt2" dump badging "$apk" > "$badging"
grep -E "^package:" "$badging"
if grep -q '^application-debuggable' "$badging"; then
  echo "ЗЛАМ: debuggable — це не release candidate"
fi
rm -f "$badging"
"$bt/apksigner" verify --print-certs "$apk"
grep -q 'CRASH_REPORTING_ENABLED = true' \
  app/build/generated/source/buildConfig/release/com/slukhayka/audiobooks/BuildConfig.java
"$bt/aapt2" dump resources "$apk" | grep -q 'string/google_app_id'
sha256sum "$apk"
```

Очікування:

- `package: name='com.slukhayka.audiobooks'` — без `.debug`;
- `application-debuggable` немає;
- `apksigner` показує **release**-сертифікат: той самий SHA-256, що в
  попереднього опублікованого релізу (звірити з
  `docs/agents/qa-v1.3.9-device-gate.md`, розділ 0);
- `mapping.txt` непорожній — його завантажить `release.yml` під час тегу;
- `CRASH_REPORTING_ENABLED = true` у generated `BuildConfig`;
- у ресурсах APK є `string/google_app_id` — google-services справді увійшов
  у збірку;
- `sha256sum` записати в результат диму.

Якщо хоч один пункт не збігається — це не кандидат, тег не ставити.

### 2.4 Поставити на тестовий пристрій

```sh
adb -s <device> install -r app/build/outputs/apk/release/app-release.apk
adb -s <device> shell dumpsys package com.slukhayka.audiobooks \
  | grep -E 'versionName|versionCode|lastUpdateTime'
```

`-d` не потрібен: release candidate має бути не нижчим за встановлений
`versionCode`. Якщо пристрій — не тестовий, спершу прочитати 2.1.

### 2.5 Дим і Crashlytics

Пройти [розділ 3](#3-спільний-список-диму) з
`PKG=com.slukhayka.audiobooks`. Додатково:

```sh
adb -s <device> logcat -d | grep -E \
  'FirebaseApp initialization unsuccessful|FirebaseApp|Crashlytics'
```

- `FirebaseApp initialization unsuccessful` бути не повинно;
- у release логи ініціалізації можуть бути невидимими. Тоді підтвердити
  Firebase іншим спостереженням — створення/відновлення identity або звіт
  Crashlytics після керованого зламу. Якщо ні того, ні того — записати
  «ініціалізацію не підтверджено» і не вважати дим повним.

Якщо є керований спосіб викликати злам — перевірити, що звіт доходить до
Crashlytics. Якщо ні — записати чесно: «перевірено ініціалізацію, доставку
звітів не перевірено». Не називати це перевіркою доставки.

Після диму:

- на тестовому пристрої — `adb uninstall com.slukhayka.audiobooks`
  (дані зникнуть; це нормально для тестового);
- на пристрої з даними слухача — **не** видаляти пакет; поставити поверх
  опублікований APK попереднього релізу, якщо кандидат не став релізом;
- переконатися, що `com.slukhayka.audiobooks.debug` не оновлювався.

## 3. Спільний список диму

Кожен пункт — спостереження, а не припущення. `PKG` — пакет того варіанта,
який димите (`…audiobooks.debug` для канару, `…audiobooks` для кандидата).

1. **Холодний старт.** Тричі:
   `am force-stop $PKG`, потім
   `am start -W -n $PKG/com.slukhayka.audiobooks.MainActivity`.
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
   `AudiobookGlanceWidgetReceiver` для потрібного пакета. Якщо віджет стоїть
   на робочому екрані — перевірити, що він малюється й оновлюється; якщо ні —
   зафіксувати, що перевірено лише реєстрацію провайдера.

## 4. Логи

```sh
adb -s <device> logcat -d | grep -E \
  'FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError'
```

`uiautomator dump` інколи сам падає з `UiAutomationService … already registered`
— це не падіння застосунку; дивитися на PID у рядку `FATAL EXCEPTION` і
звіряти з `pidof $PKG`.

## 5. Що робити зі зламом

- `NoClassDefFoundError`/`ClassNotFoundException` — додати точкове
  `-keep` для названого класу в `app/proguard-rules.pro`, перезібрати той
  самий варіант, повторити пункт, який зламався.
- `UnsatisfiedLinkError` — перевірити, що клас і нативні методи не
  перейменовані (`-keepclasseswithmembernames` уже в AGP-дефолті; для
  бібліотек без consumer-правил потрібен явний keep).
- Злам на release candidate — **безумовний блокер тегу**, навіть якщо канар
  зелений.
- Якщо злам не вдається пояснити й безпечно звузити — **не** випускати тег:
  записати stack trace, пристрій, APK і коментарем у PR/issue.

## 6. Записати результат

У release issue або PR — на кожен варіант окремо:

- commit SHA і назва варіанта (`minifiedDebug` / `release`);
- `sha256sum` APK;
- SHA-256 сертифіката підпису;
- пристрій (модель, Android, `versionCode`);
- кожен пункт розділу 3: pass / fail / not applicable з причиною;
- для release candidate — чи підтверджено google-services і Crashlytics.

Без цього запису «keep-правила перевірені» — не доказ, а переказ.
