# Матриця правил Firestore — spec-40 #283

Дата: 2026-08-24. Firebase Emulator Suite (firestore + auth); правила —
`firestore.rules` із репозиторію на момент прогону.

**Методологія.** Node-клієнти емулятора не передають X-Firebase-AppCheck,
тому матриця виміряна двома прогонами одного файлу правил:
- **OFF** — правила як в репозиторії: гейт чесно забороняє будь-який запис
  без токена навіть власнику (це і є перевірка `isAppCheckValid()`);
- **ON\*** — вираз `isAppCheckValid()` підмінено на `(true)` лише в копії,
  яку заливано в емулятор цього прогону: ізолюються володіння uid,
  валідація полів та вимога auth. У проді гейт стоїть першим в усіх
  create/update/delete — його обхід без токена неможливий, що і
  підтверджують OFF-рядки.

| OK | № | Операція | auth | AppCheck | Очікувано | Факт |
|---|---|---|---|---|---|---|
| ✅ | R1 | `GET book_reviews/qa_r1` | signed-out | OFF | ALLOW | ALLOW |
| ✅ | R2 | `CREATE book_reviews/qa_r2` | uid-alice | ON* | ALLOW | ALLOW |
| ✅ | R3 | `CREATE book_reviews/qa_r3` | uid-bob | ON* | DENY | DENY |
| ✅ | R4 | `CREATE book_reviews/qa_r4` | uid-alice | OFF | DENY | DENY |
| ✅ | R5 | `CREATE book_reviews/qa_r5` | signed-out | ON* | DENY | DENY |
| ✅ | R6 | `UPDATE book_reviews/qa_r6` | uid-alice | ON* | ALLOW | ALLOW |
| ✅ | R7 | `UPDATE book_reviews/qa_r7` | uid-bob | ON* | DENY | DENY |
| ✅ | R8 | `DELETE book_reviews/qa_r8` | uid-alice | ON* | ALLOW | ALLOW |
| ✅ | R9 | `DELETE book_reviews/qa_r9` | uid-bob | ON* | DENY | DENY |
| ✅ | R10 | `CREATE book_reviews/qa_r10` | uid-alice | ON* | DENY | DENY |
| ✅ | D1 | `GET device_bindings/qa_d1` | signed-out | OFF | ALLOW | ALLOW |
| ✅ | D2 | `CREATE device_bindings/qa_d2` | uid-alice | ON* | ALLOW | ALLOW |
| ✅ | D3 | `CREATE device_bindings/qa_d3` | uid-bob | ON* | DENY | DENY |
| ✅ | D4 | `CREATE device_bindings/qa_d4` | uid-alice | OFF | DENY | DENY |

Підсумок: **14/14**, розбіжностей: 0.

Глосарій (CONTEXT.md: «Відгук», «Код відновлення», примітка до Source
Binding) і розкриття приватності в README закриті комітом 81c8fa8.
Повторний прогін: `./run-all.sh` із цього каталогу (потрібен Java).
