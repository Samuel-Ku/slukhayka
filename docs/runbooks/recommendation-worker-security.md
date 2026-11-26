# Захист CPU-воркера рекомендацій

Це контракт розгортання, а не підтвердження захищеності машини. 2026-11-26
власник змінив ціль з dragonborn на Omarchy-хост; dragonborn лишився
незміненим, і там нічого не встановлювалося. Фактичне приймання на Omarchy —
нижче.

## Межа доступу

Рекомендації не потребують вхідного з'єднання з телефоном або Cloud.
Не відкривати SSH чи вебпорт у роутері, не робити тунель публічного доступу,
не вмикати port forwarding через UPnP. Телефон бере graph.json зі статичного
HTTPS-сховища, яке не має маршруту або облікових даних для входу у dragonborn.
Компрометація сховища не повинна давати SSH-доступ до воркера.

Адміністрування — з довіреного Mac у LAN або через приватний VPN з ACL лише
для власника. Якщо SSH потрібен: ключі з passphrase/апаратним захистом,
PasswordAuthentication=no, KbdInteractiveAuthentication=no (коли MFA через
PAM не використовується), PermitRootLogin=no, AllowUsers для конкретного
адміністратора, без agent/X11/port forwarding. Не копіювати приватний ключ
на воркер. VPN-акаунт захистити MFA. Ці налаштування спершу перевіряються
на сумісність із чинним способом входу; не застосовувати їх наосліп до
єдиного адміністративного сеансу.

## Розділені процеси

1. Довірений оператор готує перевірений код, залежності та ваги. Немає
   автоматичного git pull/pip install під root або виконання команд із
   каталогу. Оновлення коду проходять огляд.
2. Модель працює офлайн як systemd DynamicUser, без домашніх папок, мережі,
   capabilities і доступу до ключів. Вона читає лише публічний каталог та
   локальні ваги, пише кеш і graph.json у свій StateDirectory.
3. Окремий процес доставки має лише право запису одного graph.json на хості
   результату. Немає shell/SSH-доступу до воркера, адміністрування Firebase,
   повного GitHub PAT або права запису довільних об'єктів. Credential доставки
   не передається процесу моделі. Вхідний каталог отримує окремий обмежений
   завантажувач; користувацькі дані він не приймає.

Процеси отримання/доставки ще не реалізовані й не ввімкнені. Для першої
проби оператор переносить публічні файли вручну. Немає нового HTTP API,
черги віддалених команд чи автооновлення коду.

## Підготовлені обмеження

`scripts/recommend/systemd/slukhayka-content-build.service` — НЕ встановлює
й НЕ запускає себе. Після огляду оператор встановлює код/venv у
/opt/slukhayka-content-worker, ваги в /opt/slukhayka-content-model, вхідний
публічний каталог у /srv/slukhayka-public/catalog.json. Код, venv, ваги й
усі батьківські теки мають належати root і бути недоступними для запису
користувачу сервісу. Каталог має бути доступний лише на читання; не давати
моделі право переписати свій вхід або програму.

Підготовка моделі виконується НЕ як root і НЕ під користувачем сервісу:

```sh
python scripts/recommend/prepare_model.py \
  --output /tmp/slukhayka-model-reviewed \
  --revision 73908c3438cf03b6a01bcb9611d62b23d0726f08
```

Після огляду цей каталог встановлюється адміністратором лише на читання.
Завантажуються safetensors і JSON; модель не використовує pickle або
trust_remote_code. Код установлених Python-пакетів усе одно виконується:
контрольні суми не є доказом відсутності вразливостей у залежностях.

requirements.lock фіксує прямі й транзитивні версії та SHA-256 артефактів.
Встановлення: `pip install --require-hashes -r scripts/recommend/requirements.lock`.
Lock підготовлений універсальним resolver для macOS/Linux та Python від 3.10;
перевірка встановлення виконана для поточного Python 3.12. До зміни lock
потрібен огляд, а не автоматичне прийняття нових версій.

Сервіс має PrivateNetwork, RestrictAddressFamilies=AF_UNIX, IPAddressDeny=any,
NoNewPrivileges, ProtectHome, ProtectSystem, порожні capabilities,
обмеження syscalls/namespaces/devices і UMask=0077. Пам'ять — 4 ГБ,
CPU — два ядра, до 64 tasks. Для `Type=oneshot` діє `TimeoutStartSec=30min`,
бо `RuntimeMaxSec` systemd ігнорує.

`MemoryDenyWriteExecute=yes` несумісний з цим навантаженням: OpenBLAS/numpy
створюють потоки через `clone`, і MDWE повертає EPERM на `pthread_create`
(`can't start new thread`). Робочий юніт ставить `MemoryDenyWriteExecute=no`
з поясненням; решта директив лишається. Це перевіряє
`scripts/tests/test_recommend_unit.py`, щоб дефект не повернувся.

`--model-dir` відхиляє відсутні локальні файли/іншу REVISION замість
завантаження з мережі, але лише коли треба рахувати вектори: на теплому кеші
`load_model` не викликається. Тому юніт перевіряє `ConditionPathExists` для
`REVISION` і всіх трьох файлів моделі до запуску.
Вхід обмежено 64 MiB і схемою works/workKey/text. Текст передається тільки
в tokenizer; команди, шляхи до програм і URL для виконання з нього не беруться.

Ці директиви зменшують наслідки компрометації процесу. Вони не захищають
від усіх вразливостей ядра або скомпрометованого root; для суворішої ізоляції
використовувати окрему VM з тими самими межами й без доступу до домашньої LAN.

## Підпис і доставка

Модель не підписує граф і не має ключа. Окремий операторський процес
`scripts/recommend/deliver_graph.py` читає готовий `graph.json`, підписує його
ECDSA P-256 / SHA-256 (`openssl dgst -sha256 -sign`) і перевіряє підпис перед
записом. Результат — точні байти `graph.json` і `graph.json.sig` (base64 DER)
у каталозі HTTPS-сховища.

Ключ зберігається поза `/opt` (у перевірці — `~/.config/slukhayka/key.pem`,
0600). Сервіс має `ProtectHome=yes`, тому ключа не бачить; у його Environment
немає жодного ключа. Приватний ключ не комітиться і не передається процесу
моделі.

Публічний ключ для Android — це DER SPKI у base64:

```sh
openssl pkey -pubin -in graph-key.pub.pem -outform DER | base64 -w0
```

Приватний ключ — 0600, без passphrase (або через openssl agent); скрипт його
не друкує і не передає моделі.

Android пінить публічний ключ (`recommendation.graphPublicKey`) і перевіряє
підпис як для завантаженого, так і для кешованого графа; без валідного підпису
граф не замінює кеш. URL і ключ задаються `-Precommendation.graphUrl` і
`-Precommendation.graphPublicKey`; URL без ключа валить збірку. Підпис
файлу — `<url>.sig`.

Перевірено локально: реальний публічний каталог — 20 000 творів:
5 675 українських (soundbooks, audiobook.co.ua, chytaylo, knigi-online,
sluhay) + 14 325 LibriVox. Chitaka (8 244 URL) віддала 403 під час масового
обходу, тому її частина не ввійшла. Cold `computed=5675`, warm `0`, topK 22,
8 277 865 байт. `build_graph.py` автоматично зменшує top-k під 8 MiB;
ефективний topK у логі. Підписаний граф віддано тимчасовим HTTPS-тунелем,
байти збігаються, `openssl ... Verified OK`; debug APK пінить URL і ключ
(див. `recommendation-graph-activation.md`). Kotlin: `SeedCatalogKeyTest`,
`SeedGraphSignatureTest`, `GraphSignatureVerifierTest`, `RecommendationGraphTest`,
`LibriVoxKeySampleTest`, `GraphScaleTest` (20 000 × 22); Python:
`test_deliver_graph.py`, `test_catalog_export.py`, `test_catalog_ukrainian.py`.

Для production ще потрібні: реальний HTTPS-хост із валідним сертифікатом,
публічний ключ оператора в релізній збірці, повний каталог Дзеркала (20 000
LibriVox — це публічний каталог джерела, але не українське Дзеркало) і
приватний ключ доставки поза репозиторієм.

## Приймання на справжній машині

Перед встановленням переглянути listening ports (`ss -lntup`), ефективні
параметри sshd (`sudo sshd -T`), активні правила firewall і переадресації
IPv4/IPv6 на роутері. З'ясувати, які чинні служби потрібні власнику.
Перевірити оновлення ОС, права ключів/authorized_keys та журнали входу.

Після встановлення виконати systemd-analyze verify/security для сервісу й
реальний cold/warm запуск. Перевірити відмову процесу на вихідний TCP-запит,
читання домашньої папки та запис поза StateDirectory. Перевірити, що він
не має credential доставки. Лише після цього приймати ізоляцію.
`systemd-analyze verify/security` і реальний cold/warm виконано на Omarchy
2026-11-26; на dragonborn розгортання не виконувалося.

### Omarchy, 2026-11-26

Розгорнуто як systemd-сервіс: `/opt/slukhayka-content-worker` (базова
ревізія 7e029fe6, поверх неї замінено лише `build_graph.py` для авто-підбору
top-k; venv Python 3.12), `/opt/slukhayka-content-model` (ревізія 73908c34),
стан у `/var/lib/slukhayka-content`. Юніт `static`/`inactive`,
без таймера; без `/srv/slukhayka-public/catalog.json` не стартує.

Перевірено: cold `computed=20000`, warm `computed=0` (topK 22, 8 110 360 байт); помилковий вхід і SIGKILL
зберегли попередній граф; probe під DynamicUser (uid 63397) дав `CapEff=0`,
`/home`, `/root` і SSH-ключ недоступні, запис у `/opt`, `/srv`, `/etc`,
`/usr` заборонено, запис у state дозволено; TCP `EAFNOSUPPORT`; нових
listening-портів немає. `systemd-analyze security` — 0.6 SAFE.

Межі, які треба знати:

- `getaddrinfo` може відповісти через Unix-сокет systemd-resolved — це не
  мережевий вихід; доказом є TCP-проба.
- Probe не перелічує інтерфейси: `ProcSubset=pid` ховає `/proc/net`, а
  `AF_NETLINK` заборонений. Це недоступність переліку, не доказ мережі.
- На Omarchy `PasswordAuthentication=yes`, `PermitRootLogin=prohibit-password`,
  `AllowTcpForwarding=yes`; UFW пускає 22 лише з `192.168.2.0/24`, решта
  вхідних — 1714-1764 (KDE Connect), 8788, 53317. Посилення SSH не
  застосовано; це окреме рішення власника.

### Mac

На Mac локальний smoke-local.sh — звичайний користувацький процес, не цей
Linux sandbox. Для проби використовувати окремого стандартного користувача
або ізольовану Linux VM без спільного HOME/SSH agent; сам venv не є межею
кібербезпеки. Публічний граф підписується окремим ключем (ECDSA P-256); клієнт перевіряє
підпис і для завантаженого, і для кешованого графа. Production URL і ключ
ще не надані.

## Що ще не готово

Підпис і доставка реалізовані й перевірені локально. Для production ще
потрібні: реальний HTTPS-хост із валідним сертифікатом, публічний ключ
оператора в релізній збірці, повний український каталог (Chitaka/Дзеркало,
а не лише доступні sitemap-джерела) і приватний ключ доставки поза
репозиторієм. Розклад не ввімкнений; production-публікації немає.
