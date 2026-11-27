# Активація підписаного графа

Граф активується лише тоді, коли збірка застосунку має **і** HTTPS-URL
`graph.json`, **і** pinned публічний ключ. Без ключа збірка падає; без URL
мережевий шлях вимкнений. Сам по собі граф не містить історії слухача.

## Тимчасова активація (debug, без production-хоста)

Це спосіб перевірити справжній шлях «підпис → HTTPS → Android» без домену й
сертифіката. Він **не** є production-публікацією.

1. Після запуску deliver_graph.py віддати один підписаний контейнер з локального loopback:

   ```sh
   python3 scripts/recommend/serve_graph.py \
     --store ~/.config/slukhayka/store --port 8455
   ```

   Сервер віддає тільки `/graph.json` (контейнер графа й підпису); лістингу й
   traversal немає.

2. Підняти тимчасовий HTTPS-тунель (вихідне з'єднання, не вхідний порт):

   ```sh
   cloudflared tunnel --url http://127.0.0.1:8455 --no-autoupdate
   ```

   Отриманий `https://<random>.trycloudflare.com` — тимчасовий. Після
   зупинки `cloudflared` URL помирає; це не production-адреса.

3. Зібрати debug APK із URL і публічним ключем:

   ```sh
   PUB=$(openssl pkey -pubin -in ~/.config/slukhayka/pub.pem -outform DER | base64 -w0)
   .worktrees/gt ./gradlew :app:assembleDebug \
     -Precommendation.graphUrl=https://<random>.trycloudflare.com/graph.json \
     -Precommendation.graphPublicKey="$PUB"
   ```

4. Встановити `app/build/outputs/apk/debug/app-debug.apk`, відкрити Home.
   Застосунок робить один запит на добу; перевірити кеш:

   ```sh
   adb shell run-as com.slukhayka.audiobooks.debug ls -l files/recommendations
   adb shell run-as com.slukhayka.audiobooks.debug cat files/recommendations/content-graph.signed.json
   ```

   `content-graph.signed.json` з'являється лише після перевірки підпису й
   формату графа. Це один атомарно оновлюваний файл; незавершений `.tmp`
   після переривання не замінює попереднього придатного кеша.

5. Після приймання зупинити `cloudflared` і локальний сервер. Тимчасовий URL
   не залишати як production.

## Production

Потрібні рішення власника:

- **Домен і TLS-сертифікат** (Let's Encrypt або керований хостинг).
- **Статичний хост** лише для одного підписаного `graph.json`-контейнера;
  без лістингу й доступу до кешу/каталогу/ключа. Оновлення — одним атомарним
  перейменуванням після перевірки підпису.
- **Публічний ключ оператора** (DER SPKI base64) у релізній збірці:
  `-Precommendation.graphUrl=https://<domain>/graph.json`
  `-Precommendation.graphPublicKey=<base64>`.
- **Підписувальний ключ** — лише в оператора доставки, 0600, поза `/opt` і
  репозиторієм; модельний процес його не має. Релізний keystore застосунку —
  окремий секрет власника.
- **Релізний keystore/підпис** застосунку надає власник; без нього
  `bundleRelease` не публікується. Команда після налаштування keystore:

  ```sh
  .worktrees/gt ./gradlew :app:bundleRelease \
    -Precommendation.graphUrl=https://<domain>/graph.json \
    -Precommendation.graphPublicKey=<base64>
  ```

Після першої production-публікації перевірити на справжньому пристрої:
один fetch, валідний підпис, кеш, повторний запуск без запиту, TTL і
повернення до локального TF-IDF після expiry. Розклад побудови вмикати лише
після цього приймання.

## Межа

Тимчасовий тунель відкриває **лише публічний артефакт** і не дає доступу до
воркера, каталогу, кешу чи ключа. Він не замінює production-хост: URL
ephemeral, а debug APK не є релізом.


Формат після огляду 2026-11-27: `formatVersion=1`, `graph` і `signature`
в одному JSON. Старий `<url>.sig`-хост потрібно перепублікувати через новий
скрипт доставки й оновити пробний APK. Деталі переходу — у runbook воркера.
