# Активація підписаного графа

Граф активується лише тоді, коли збірка застосунку має **і** HTTPS-URL
`graph.json`, **і** pinned публічний ключ. Без ключа збірка падає; без URL
мережевий шлях вимкнений. Сам по собі граф не містить історії слухача.

## Тимчасова активація (debug, без production-хоста)

Це спосіб перевірити справжній шлях «підпис → HTTPS → Android» без домену й
сертифіката. Він **не** є production-публікацією.

1. Віддати лише два файли з локального loopback:

   ```sh
   python3 scripts/recommend/serve_graph.py \
     --store ~/.config/slukhayka/store --port 8455
   ```

   Сервер віддає тільки `/graph.json` і `/graph.json.sig`; лістингу й
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
   adb shell run-as com.slukhayka.audiobooks ls -l files/recommendations
   adb shell run-as com.slukhayka.audiobooks cat files/recommendations/content-graph.json.sig
   ```

   `content-graph.json` і `.sig` разом означають, що підпис перевірено й
   граф прийнято. Без валідного підпису файли не з'являться.

5. Після приймання зупинити `cloudflared` і локальний сервер. Тимчасовий URL
   не залишати як production.

## Production

Потрібні рішення власника:

- **Домен і TLS-сертифікат** (Let's Encrypt або керований хостинг).
- **Статичний хост** лише для `graph.json` і `graph.json.sig`; без лістингу,
  без доступу до кешу/каталогу/ключа. Оновлення — атомарне, обидва файли
  разом; старий граф лишається, поки новий не підписано й не перевірено.
- **Публічний ключ оператора** (DER SPKI base64) у релізній збірці:
  `-Precommendation.graphUrl=https://<domain>/graph.json`
  `-Precommendation.graphPublicKey=<base64>`.
- **Підписувальний ключ** — лише в оператора доставки, 0600, поза `/opt` і
  репозиторієм; модельний процес його не має. Релізний keystore застосунку —
  окремий секрет власника.
- **Релізний keystore/підпис** застосунку надає власник; без нього
  `bundleRelease` не публікується.

Після першої production-публікації перевірити на справжньому пристрої:
один fetch, валідний підпис, кеш, повторний запуск без запиту, TTL і
повернення до локального TF-IDF після expiry. Розклад побудови вмикати лише
після цього приймання.

## Межа

Тимчасовий тунель відкриває **лише публічний артефакт** і не дає доступу до
воркера, каталогу, кешу чи ключа. Він не замінює production-хост: URL
ephemeral, а debug APK не є релізом.
