# Свідки Android-приймання #1796

Зведений стан — [звіт](../2026-11-27-1796-mac-acceptance.md). Тут результати власних ізольованих тестових пакетів. Немає бібліотеки власника, серійного номера чи приватних адрес. Це докази конкретних прогонів, не самостійний production-gate.

- `device-https-test.log` і `SignedGraphHttpsDeviceAcceptanceTest.kt.txt` — виконаний device-тест. Розширення txt навмисне: архів harness, не новий production або CI source set.
- `https-apk-verification.json` — APK digests та перевірені URL/key/class.
- `operator-host-verification.json`, `https-negatives-host-verification.json` — результат отримання й криптографічної перевірки операторських артефактів на Mac.
- `offline-ui-aggregate.json` — системний offline cold-start і відновлення мережі.
- `playback30passive-summary.json` — завершене відтворення початкового candidate. Екран Awake, порівняння з baseline відсутнє.
- `paired-startup-aggregate.json`, `paired-input-audit.json` — чотири ABBA запуски Overview та перевірка незмінності змістових даних. Початковий строгий guard лишається false через службові timestamps.
- `listener-protocol.md` — сценарій для справжньої оцінки. Відгуків у ньому немає.
- `sha256.json` — контрольні суми цих файлів. README поза маніфестом.

Артефакти мають кінцевий TTL. Поточний позитивний контейнер спливає 2026-12-04T08:58:41Z; після цього повторний тест має відхиляти його. Не змінювати дату телефона або verifier, щоб отримати зелений результат. Новий позитивний прогін потребує нового операторського підписаного контейнера й окремих контрольних URL.

## Аргументи виконаного HTTPS-тесту

Пакет `com.slukhayka.audiobooks.recommend1796`, instrumentation runner `com.slukhayka.audiobooks.IsolatedDatabaseTestRunner`. Перед збіркою harness було скопійовано в androidTest source set окремого checkout. Не встановлювати поверх основного застосунку.

```sh
adb -d shell am instrument -w -r \
  -e class com.slukhayka.audiobooks.data.recommend.SignedGraphHttpsDeviceAcceptanceTest \
  -e graphUrl https://raw.githubusercontent.com/Samuel-Ku/slukhayka/96a60a4ef1edced9366cb0d5dacef2c1b1050e7c/graph.signed.json \
  -e tampered https://raw.githubusercontent.com/Samuel-Ku/slukhayka/96a60a4ef1edced9366cb0d5dacef2c1b1050e7c/negative/tampered-graph-old-signature.json \
  -e wrongSignature https://raw.githubusercontent.com/Samuel-Ku/slukhayka/96a60a4ef1edced9366cb0d5dacef2c1b1050e7c/negative/wrong-signature.json \
  -e expired https://raw.githubusercontent.com/Samuel-Ku/slukhayka/96a60a4ef1edced9366cb0d5dacef2c1b1050e7c/negative/expired-graph.json \
  com.slukhayka.audiobooks.recommend1796.test/com.slukhayka.audiobooks.IsolatedDatabaseTestRunner
```

Тест використовує окрему директорію cacheDir і прибирає тільки її. Не змінює основний кеш. У test store передається окремий годинник; системний час незмінний. Перевірки відповідей стоять поза fetch callback, щоб обробка transport exceptions у store не приховала провал assertion.
