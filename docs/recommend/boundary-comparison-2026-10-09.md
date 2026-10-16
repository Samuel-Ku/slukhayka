# Один paired Mac/Linux виклик #487

Порівняв один поточний production `OnnxEmbedder.embed` на Mac і Linux. Перша спостережена різниця — raw `last_hidden_state`, до production pooling. Конкретний оператор, kernel, provider або причина OS не встановлені. Повний eval на 18 188 Творах залишається NO-GO; ця діагностика його не переоцінює.

## Що зафіксовано

Публічний Твір: `1 2 and 3 john kjv|james king version`. Оригінальний текст — 1323 UTF-8 bytes, із production `query: ` — 1330 bytes і 302 токени. Це перший елемент раніше зафіксованої вибірки, вибраної незалежно від ranks. На Linux опубліковано лише цей один текст; інших 31 текстів і приватної історії слухача пакет не містить.

Обидва виклики використовують незмінні source-файли `022c786f33e860579fab78c7f91413c9b0b5325e`, compiler 2.2.10/JVM17, модель і токенізатор за frozen SHA, desktop ONNX Runtime 1.21.0 та production default SessionOptions. Перед Linux inference усі 21 backend class bytes збіглися з Mac. Усіх 10 зафіксованих input-файлів теж побітово однакові: текст, 302 token IDs, три heap buffers і три фактично передані INT64 tensors `[1,302]`.

Різні SHA вхідних TSV очікувані: Mac launcher читав первісний 32-row файл, Linux — один рядок без cached vector. Вибрані decoded текст/id і фактичні input tensors однакові.

Код діагностики додано через [PR #1190](https://github.com/Samuel-Ku/slukhayka/pull/1190), merge `bbac32443593297e6ab86ac3a4b9e5f3d5b75105`. [Єдиний дозволений Linux run 37952654294](https://github.com/Samuel-Ku/slukhayka/actions/runs/37952654294) на цьому main завершився success. Artifact `r1-one-public-work-linux-37952654294` має 871800 compressed bytes, 968352 expanded bytes і retention 3 дні. Сам текст і scripts залишаються в публічному Git, Actions logs мають окреме зберігання. Бінарні результати в репозиторій не додано.

## Результат

Вибраний output на обох платформах: FLOAT `last_hidden_state` `[1,302,384]`. Усередині кожного виклику native getter побітово дорівнює Java hidden, а production pool return дорівнює embed return і вектору caller. Mac return також збігається з його первісним cached vector. Це перевіряє фактичні boundaries, без другого inference чи копії алгоритму embedding.

| Boundary | Float words | Різних words | Max absolute difference | Cosine |
|---|---:|---:|---:|---:|
| Raw hidden | 115968 | 115968 | 0.1776690781 | 0.9952686519 |
| Pooled sum | 384 | 384 | 9.6117553711 | 0.9989118546 |
| Кінцевий вектор | 384 | 384 | 0.0074208081 | 0.9989118545 |

SHA-256 raw hidden: Mac `c3ccbf0e564bfbb2c58382b0997dcb1119034df48fbb645c2c119897d5e2a6c9`, Linux `4be86ad5737e1d7f1c5b2fda33864f01bc920f575dcc63103960d447ce77e67c`.

SHA-256 кінцевого вектора: Mac `61b798c5fdae93ccde7c927051fed4394422a652b83a1d7507e9ed0541dd9c6c`, Linux `7d63d1d55e36d87269242cffb4c2ab5efb640e650967476ce7c615bc81753ec9`.

## Межа висновку

Java version на обох платформах — 21.0.12.1, але Mac VM build — 21.0.12.1, Linux — 21.0.12.1+1-LTS; збірки Homebrew і Temurin різні. Native library mapping, provider/kernel dispatch та CPU ISA в цьому пакеті не записані. Тому це спостереження двох runtime, а не експеримент, де змінювалася лише OS.

Один поточний Linux-виклик не відновлює координати історичних Linux journals чи 32-vector вибірки. Вплив на порядок кандидатів і 24 folds не перевірено. Розбіжність уже видима до pooling, але вона сама не пояснює слабку якість E5 і не дозволяє послабити критерій переваги над baseline.

Нових моделей, weights, labels або алгоритмів ранжування тут немає. Перед наступним runtime або алгоритмічним експериментом потрібні окремий scope і preregistration; цей дозвіл використаний для одного dispatch без повтору.
