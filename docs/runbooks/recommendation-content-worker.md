# Семантичний граф на CPU-воркері — пробний запуск #1796

Телефон не запускає модель. Воркер будує публічні зв'язки між Творами,
телефон завантажує один файл і персоналізує локально. Відмова мережі не
блокує Огляд. Серверного профілю слухача тут немає.

## Вхід

`catalog.json` має форму:

```json
{"works":[
  {"workKey":"канонічний ключ першого твору","text":"Публічні назва, автор, жанри, серія й опис"},
  {"workKey":"канонічний ключ другого твору","text":"Публічні назва, автор, жанри, серія й опис"}
]}
```

Це схема, не готовий каталог. `workKey` — точний `mergeKey` із Дзеркала
або `id` за порожнього mergeKey, як `recommendationWorkKey`. Не обчислювати
новий ключ Python-евристикою: він мусить збігатися з Android. `text` — відомі
публічні метадані твору. Жодних uid, оцінок, прогресу, шляхів локальних файлів,
приватних назв чи історії слухача. Не переносити на воркер всю базу телефона.
Генератор приймає лише два поля на твір, не друкує їх у логах і не робить
запитів до джерел. Фактичного production-вивантаження каталогу в цій сесії
не надано; перевірено лише smoke.

## Встановлення

На `dragonborn` під користувачем з `docs/agents/machines.md`, в окремій копії:

```sh
python3 -m venv .venv-recommend
.venv-recommend/bin/pip install --require-hashes -r scripts/recommend/requirements.lock
```

Модель — `minishlab/potion-multilingual-128M`, ваги близько 512 МБ. Потрібні
Hugging Face і його CDN для першого завантаження; далі працює локальний кеш.
Взяти повний 40-символьний commit SHA моделі, перевірити й зафіксувати його
в параметрах запуску. Плаваючий `main` генератор відхиляє. Для пробного CI припінано [73908c3438cf03b6a01bcb9611d62b23d0726f08](https://huggingface.co/minishlab/potion-multilingual-128M/commit/73908c3438cf03b6a01bcb9611d62b23d0726f08). Python завантажує
саме snapshot цієї ревізії, а не поточну модель за назвою.

```sh
nice -n 10 taskset -c 0-5 .venv-recommend/bin/python scripts/recommend/build_graph.py \
  --catalog /srv/slukhayka/catalog.json \
  --cache /srv/slukhayka/cache/vectors.sqlite \
  --output /srv/slukhayka/output/graph.json \
  --revision 73908c3438cf03b6a01bcb9611d62b23d0726f08
```

Той самий каталог удруге не завантажує модель і не обчислює вектори.
Зміна тексту або ревізії інвалідує відповідний кеш. Матриця схожості рахується
блоками, а не N×N цілком. Ліміт — 20 000 творів, до 32 сусідів; типово 24.
Артефакт не більший за 8 MiB; за перевищення зменшити `--top-k`. Вихід
підміняється атомарно лише після успішної побудови. TTL — до семи діб.

## Доставка і пробна Android-збірка

Спершу згенерувати каталог із реального seed застосунку:

```sh
python3 scripts/recommend/catalog_from_seed.py \
  --output scripts/recommend/seed-catalog.json
```

Це 23 реальні книжки з `app/src/main/assets/catalog_seed.json`; `workKey`
обчислюється тією самою формулою `MergeKey.keyFor`, що й в Android, і це
перевіряє `SeedCatalogKeyTest`. Для масштабу є реальний публічний каталог
LibriVox:

```sh
python3 scripts/recommend/catalog_from_librivox.py \
  --output /srv/slukhayka/catalog.json --limit 20000
```

Це 20 000 книжок із тим самим ключем; `LibriVoxKeySampleTest` звіряє вибірку з
`MergeKey.keyFor`. `build_graph.py` сам зменшує top-k, якщо 24 сусіди не
вкладаються в 8 MiB (для 20 000 це 22).

Для української частини:

```sh
python3 scripts/recommend/catalog_from_ukrainian.py \
  --output /srv/slukhayka/catalog.json --limit 20000 --workers 8 \
  --chitaka-limit 8000 --fill-librivox /srv/slukhayka/librivox-catalog.json
```

Скрипт бере sitemap/JSON підтримуваних джерел (soundbooks, audiobook.co.ua,
chytaylo, knigi-online, sluhay, chitaka) і добирає решту з LibriVox. Chitaka
має окремий `--chitaka-limit`: під час масового обходу вона віддає 403, тож
для повторної спроби зменшити `--workers`; `--chitaka-limit 0` пропускає її.
Поточний результат: 5 675 українських + 14 325 LibriVox. Для повного
українського Дзеркала потрібне вивантаження з пристрою/сховища.

Після побудови підписати граф окремим процесом (модель ключа не має):

```sh
python3 scripts/recommend/deliver_graph.py \
  --graph /srv/slukhayka/output/graph.json \
  --key /etc/slukhayka/graph-key.pem \
  --public-key /etc/slukhayka/graph-key.pub.pem \
  --output-dir /srv/slukhayka/public
```

Опублікувати рівно `graph.json` і `graph.json.sig` на власному HTTPS-хості як
статичні файли; кеш векторів, вхідний каталог, приватний ключ і credentials не
публікувати. Хост не має отримувати історію чи список потрібних слухачу
книжок: кожен клієнт отримує той самий артефакт. Підготувати атомарне
оновлення обох файлів. Потім зібрати пробний APK:

```sh
.worktrees/gt ./gradlew :app:assembleDebug \
  -Precommendation.graphUrl=https://YOUR_HOST/graph.json \
  -Precommendation.graphPublicKey=PASTE_BASE64_PUBLIC_KEY
```

Без URL мережевий шлях вимкнений; URL без публічного ключа валить збірку, бо
непідписаний граф не приймається. Телефон читає кеш у IO, робить максимум одну
спробу на добу між перезапусками, перевіряє підпис/формат/ліміти/TTL й
атомарно замінює файл. Помилка лишає попередній свіжий граф; після TTL
локальний TF-IDF повертається. При активному графі нові твори без зв'язків
підбираються за метаданими та у відкриттях, без локального векторного
індексування. Аудіо й Source-фільтри не розширюються.

Локальне приймання 2026-11-26: 20 000 творів (5 675 українських +
14 325 LibriVox), cold `computed=5675`, warm `0`, topK 22, 8 277 865 байт;
підписаний граф віддано тимчасовим HTTPS-тунелем, `openssl ... Verified OK`;
Android `GraphScaleTest` парсить 20 000 × 22, debug APK пінить URL і ключ.
Для production ще потрібні реальний HTTPS-хост, повний український каталог
(Chitaka/Дзеркало) і ключ доставки; розклад не ввімкнений.

## Пробний запуск на Mac

GPU та Linux не потрібні. Для невеликого smoke-каталогу достатньо Mac із
8 ГБ RAM; орієнтир вільного диска — 2–3 ГБ на Python, модель і кеш. Реальний
час залежить від процесора, тому його не обіцяємо наперед.

У локальній копії гілки `t1796-local-recommendations`, з Python 3.10–3.13:

```sh
scripts/recommend/smoke-local.sh
```

Скрипт однаковий для macOS/Linux, не потребує taskset. Він створює окремий
venv і model-cache у тимчасовій теці, друкує її шлях, завантажує припінані
ваги, двічі будує граф чотирьох синтетичних творів та перевіряє
cold computed=4 / warm computed=0. Результат — локальний graph.json;
публікації й розкладу немає. Теку можна прибрати після огляду.

Cloud-чат не отримує доступу до Mac автоматично. Запустити цей скрипт
у Mac Terminal або продовжити в локальному Codex-чаті цієї копії.

## Перед розгортанням на dragonborn

Власник додав вимогу захистити машину від неавторизованого доступу.
Чинний контракт: [recommendation-worker-security.md](recommendation-worker-security.md).
Немає вхідних портів для рекомендацій, модель працює в офлайн-сервісі,
отримання даних і доставка відділені. Попередні команди прямого запуску —
лише проба функціональності, не прийнятий захищений production-сервіс.
Встановлення залежностей використовує requirements.lock з --require-hashes.

## Фактичне розгортання 2026-11-26

Ціль змінена власником: воркер розгорнуто на Omarchy-хості, не на dragonborn.
Це systemd-сервіс із `DynamicUser`, `/opt/slukhayka-content-worker`,
`/opt/slukhayka-content-model` і станом у `/var/lib/slukhayka-content`.
Cold/warm, ізоляція, межі й відкриті частини (production-каталог, доставка,
підпис) — [recommendation-worker-security.md](recommendation-worker-security.md).
