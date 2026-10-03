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
