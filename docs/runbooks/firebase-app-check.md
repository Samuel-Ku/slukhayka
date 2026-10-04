# App Check перед поверненням хмарних записів

Наразі у `slukhayka` діють аварійні Firestore Rules: усі клієнтські записи
закрито. Встановлений у клієнті provider сам по собі не захищає сервер.
Для повернення записів потрібні service Enforcement, перевірені токени
й нормальні правила авторства. Старі відкриті правила не повертати.

## Android

Застосунок використовує reCAPTCHA Enterprise для Android. За
[офіційним контрактом Firebase](https://firebase.google.com/docs/app-check/android/recaptcha-enterprise-provider)
потрібні Android-type key для `com.slukhayka.audiobooks` і реєстрація цього
ключа у Firebase Console → App Check → Apps. `google-services.json`
цього ключа не постачає. Ключ публічний; це не service account або пароль.

BoM 34.19.0 містить API `RecaptchaAppCheckProviderFactory.getInstance(siteKey)`.
Ініціалізація у `App.onCreate` відбувається перед іншими Firebase-сервісами.

Для локальної збірки передай ключ змінною `APP_CHECK_RECAPTCHA_SITE_KEY`
або Gradle property `appCheckRecaptchaSiteKey`. CI та release читають GitHub
repository variable `ANDROID_APP_CHECK_RECAPTCHA_SITE_KEY`. Не вставляй
Android-ключ у web provider: той має окремий ключ і дозволені домени.
Workflow публічного release відмовляє без цієї змінної; внутрішній smoke
і локальна збірка можуть лишатися без неї для перевірки решти коду.

Порожній ключ залишає provider невстановленим. Це допустимо для локальної
роботи, але така збірка не підтверджує готовність до Enforcement.
Debug provider чи debug tokens у реліз не додавати.

## Перевірка й увімкнення

1. Зібрати підписаний APK з правильною Firebase-конфігурацією і ключем.
   Перевірити його на реальному підтримуваному Android-пристрої.
2. Перевірити справжній App Check token і метрики Verified для Firestore.
   Токен не виводити в лог, чат чи issue. Аналогічно перевірити web-клієнт.
3. Лише після оцінки впливу на чинні версії ввімкнути Enforcement для
   Firestore. Увімкнення до цього відмовить неатестованим клієнтам.
4. Після підтвердження Enforcement замінити недійсну умову
   `request.appCheck.token` у звичайних Rules на перевірену Auth-політику.
   Повторити тести авторства та приватності на емуляторі й контрактний
   сценарій справжнього клієнта. Аварійні Rules заміняти лише цим комплектом.

Збірка й локальні тести не доводять, що хмарна реєстрація працює.
Provider може не видати токен на непідтримуваному пристрої або з чужим
типом ключа. До живої перевірки хмарні записи лишаються закритими.

## Стан підготовки 2026-10-04

reCAPTCHA Enterprise API увімкнено після підтвердження власника. Створено
production Android-ключ `Slukhayka Android App Check`: лише пакет
`com.slukhayka.audiobooks`, перевірка назви пакета увімкнена, поширення
поза Google Play дозволене. Android provider у Firebase має статус
**Registered**. Публічний ключ збережено у GitHub repository variable
`ANDROID_APP_CHECK_RECAPTCHA_SITE_KEY`.

Окремий workflow `security-recovery.yml` у гілці
`codex/security-cloud-recovery` перевіряє тільки змінені Android-межі,
будує APK із наявним release-підписом і перевіряє його `apksigner`.
Артефакт зберігається в Actions на 3 дні. Публічного релізу чи тега цей
workflow не створює. У ньому підготовка E5 передує checksum-гейту.

[Підписана CI-збірка](https://github.com/Samuel-Ku/slukhayka/actions/runs/37148317723)
успішна. Локально перевірено APK: checksum збігається, `apksigner` підтверджує
підпис, сертифікат збігається з опублікованою v1.4.2. Ключ присутній у DEX,
старий `FirestoreDeviceBindings` відсутній, cleartext заборонено,
довіра лише до system CA, `debuggable` не увімкнено.
SHA-256 кандидата:
`b15f05f0ee521455b59f67f3502ac0904bd9b1d2fdbed4a2e54abd7f620253ff`.
Локальна копія — `app/build/outputs/security-recovery/app-release.apk`.
Це кандидат для перевірки: versionName 1.4.2, versionCode 30;
нового публічного релізу не створено. На телефоні ще не встановлювався.

Auth-кандидат правил перевіряється окремо:

```sh
SECURITY_RULES_PHASE=auth-candidate npx --yes --ignore-scripts --package=firebase-tools@15.32.1 -- firebase emulators:exec --only firestore --project demo-slukhayka-security --config firebase.security-tests.json 'npm --prefix web run test:security-rules'
```

106 перевірок цього кандидата пройшли. Це перевірка Auth, схем і авторства
лише на localhost; вихідний legacy guard у `firestore.rules` та аварійні
правила продакшену лишаються закритими. Кандидат не можна розгортати до
підтвердження service Enforcement і плану для старих профілів.

Admin-авторизацію для Firestore поновлено, UID-інвентар отримано без поля
`cred`: 156 bindings, 156 різних UID. Firebase Auth Admin не приймає
стандартний gcloud OAuth client ID; це
[документоване обмеження](https://firebase.google.com/docs/admin/setup#test_with_gcloud_end_user_credentials).
Наявний service account не дозволяє власнику отримувати impersonated token.
Новий приватний ключ та IAM-грант не створювалися. Власник окремо дозволив
запитувані права Firebase CLI; офіційний OAuth-вхід завершено. Auth Admin
перевірив усі 156 UID: профілі існують, не вимкнені, мають password provider
і технічну адресу `@slukhayka.local`. Паролі, Recovery Codes і сесії
не змінювалися. Інвентар із UID лишається поза Git із доступом лише власника.

Ці адреси не є поштовими скриньками слухачів, тому email reset не дає
безпечного способу відновити їхній доступ. Масове скидання паролів або
вимкнення профілів може позбавити власників доступу. Старий пароль і
Recovery Code не доводять власність після потенційного розкриття bindings.
До окремого плану перевірки власників і заміни облікових даних хмарні
записи цих профілів не відкривати; App Check не вирішує цю проблему.

Веб-застосунків у Firebase-проєкті наразі немає. Перед підключенням живого
веб-клієнта треба визначити його домен, зареєструвати його й перевірити
власний provider; Android-ключ не підходить для web. Телефон підключено
через Wi-Fi. Перша спроба встановлення підписаного кандидата завершилась
`INSTALL_FAILED_USER_RESTRICTED`; потрібне підтвердження на телефоні.
Наявна debug-версія та її дані не змінені. Живу атестацію ще не перевірено.
