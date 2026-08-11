# WebView sessions and Cloudflare challenge persistence (T3 research)

**Ticket:** [#72 — T3 Cloudflare challenge and WebView session persistence](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/72) (wayfinder map #70)
**Date:** 2026-08-11 · **AFK** — Android/WebView/Cloudflare documentation only, no emulator.

## Verdict

- **Користувач проходить challenge раз на TTL, а не раз на запуск.** `cf_clearance` — це *персистентна* cookie з явним терміном дії (zone «Challenge Passage»: зазвичай 30 хв–24 год), а не session-cookie. Cookie-джар Android WebView зберігається на диску в приватному каталозі застосунку, тому clearance переживає вбивство процесу і перезапуск застосунку — за умови виклику `flush()`.
- **Критичне обмеження для патерну:** clearance прив'язаний до IP + ASN + User-Agent + TLS-відбитка (JA3/JA4). WebView (Chromium) і Media3/ExoPlayer (OkHttp) мають **різні TLS-відбитки** — на строгій CF-зоні пряме відтворення перехопленого mp3 через плеєр може бути відхилено навіть з валідною cookie. Це треба виміряти в прототипі #71: чи є mp3-хост під CF, і чи приймає CF cookie з чужої TLS-відбитки.
- **`shouldInterceptRequest` бачить лише запити самого WebView** (включно з медіа та XHR), на фоновому потоці; він може *спостерігати* URL, але не може за запитом віддати байти довільної URL.
- **Сесія живе в джарі, а не у в'ю.** Стан вкладки (DOM, історія, поточна URL) не переживає смерть процесу; перезавантаження вкладки після рестарту НЕ потребує нового challenge (джар збережений). Тримати WebView живим у фоні не можна (throttling ~5 хв JS, вбивство рендерера) і не треба — програвання відбувається у плеєрі застосунку.

## 1. CookieManager — що переживає рестарт, а що ні

| Аспект | Факт |
|---|---|
| Джар | Один на застосунок (`CookieManager.getInstance()` — синглтон), спільний для всіх WebView-інстансів; фізично — Chromium-профіль у приватному каталозі застосунку (`app_webview/Cookies`). |
| Персистентні cookies | З `Max-Age`/`Expires` — пишуться на диск, переживають вбивство процесу та перезапуск застосунку. |
| Session cookies | Без терміну — живуть лише в пам'яті, губляться з процесом. |
| `flush()` | Записи батчаться; `flush()` форсує запис на диск. Викликати після проходження challenge і перед бекграундом — інакше свіжий clearance може загубитися при раптовому вбивстві процесу. |
| Очищення | `removeAllCookies`/`removeSessionCookies` прибирає все разом; для патерну не чистити джар між джерелами (різні домени співіснують без конфлікту). |

`cf_clearance` має явний термін (зона задає Challenge Passage), отже він належить до персистентних — переживає рестарт у межах TTL. `__cf_bm` (bot management) — 30 хв; `__cfuvid` — без явного терміну; усі лежать у тому ж джарі.

## 2. Валідність CF-challenge сесій

- **Що таке clearance:** `cf_clearance` = доказ, що відвідувач пройшов challenge; містить challenge-clearance + precursor-clearance (поведінковий, постійно переоцінюється; підозріла поведінка може інвалідувати сесію до закінчення TTL).
- **Рівні:** Interactive (high) обходить interactive + managed + non-interactive; Managed — managed + non-interactive; Non-Interactive — лише non-interactive. sluhay.com ставить interactive challenge → після проходження обходить усі нижчі рівні на час TTL.
- **Прив'язка (критично):** cookie дійсна лише для тієї ж комбінації **IP + ASN + User-Agent + TLS/JA3-відбитка**, що її отримала. Наслідки:
  1. Зміна мережі (WiFi↔мобільний, VPN) → ре-challenge.
  2. Застосунок повинен використовувати **один стабільний UA** для WebView і для будь-яких власних запитів. У застосунку UA вже перевизначений у `FourReadWebScreen` — його треба зробити єдиною константою і не давати йому дрейфувати (включно з Media3-запитами).
  3. TLS-відбитка WebView (Chromium) ≠ TLS-відбитка OkHttp/HttpURLConnection. На строгій зоні CF перевіряє рукостискання, а не лише cookie → пряме відтворення через Media3 може дати 403 навіть із валідним джаром + UA.
- **TTL:** «Challenge Passage» — конфігурація зони; типовий діапазон 30 хв–24 год (Enterprise до 24 год, налаштовується). Після закінчення — новий challenge.
- **Ліміти:** жодного жорсткого ліміту на кількість сесій; на практиці — ліміт розміру cookie (≤4096 байт на cookie; `cf_clearance` не може перевищити) та рекомендація CF клієнтам рейт-лімитити за `cf_clearance` (інтенсивний даунлоад на одній clearance може тригернути precursor-інвалідацію). Нормальний темп прослуховування — не проблема.

## 3. `shouldInterceptRequest` — межі

- **Викликається на фоновому потоці** (не UI) — безпечно робити мережеву роботу; блокування колбеку блокує завантаження цього ресурсу (Chromium поступово виносить виклик з критичного шляху).
- **Бачить запити тільки свого WebView:** головний фрейм, субресурси, XHR, медіа (`<audio>`/`<video>`), — але НЕ запити інших стеків застосунку (OkHttp, Media3). Це не перехоплювач усього трафіку.
- **Повернення:** `WebResourceResponse` (статус + заголовки + потік) підміняє відповідь; `null` — штатний мережевий фетч. Доступні заголовки запиту (включно з `Range` для медіа).
- **Немає on-demand API:** не можна попросити WebView «завантаж URL і віддай байти». Щоб спровокувати внутрішній запит довільної URL — треба завантажити прихований елемент (iframe/audio/fetch з JS); його запит пройде через `shouldInterceptRequest`.
- **Передача байтів назовні:** JS `fetch()` + JS-міст — можливо для цілих файлів, але погано для стримінгу довгих аудіокниг (пам'ять, Range). Реально корисні сценарії: (1) спостереження playback-URL і метаданих, (2) блокування трекерів/реклами, (3) ін'єкція CORS-заголовків у відповідь — але це не вирішує TLS-відбитку.
- **Кеш:** ресурси, віддані з WebView-кешу, можуть оминати колбек на деяких версіях; для стрімінгових медіа це зазвичай неактуально.
- Безпековий нюанс: у застосунку вже є `addJavascriptInterface` (аудит SEC-003) — патерн має не розширювати JS-міст; краще `evaluateJavascript` з перевіркою origin або спостереження через `shouldInterceptRequest`.

## 4. Фонове виконання та lifecycle

- **Без `onPause()` WebView працює у фоні** (та навантажує CPU); `WebView.onPause()` «паузує додаткову обробку»: анімації, таймери, геолокацію (JS-задача, що виконується, завершується; таймери зупиняються). `pauseTimers()` (статичний) паузує JS усіх WebView у застосунку.
- **`setBackgroundThrottling(true)`** (API 33+, default true) — знижує CPU прихованого WebView.
- **Практичний факт:** JS у фоновому WebView фактично зупиняється/різко троттлиться після ~5 хв у бекграунді. Тримати «keepalive-сторінку» неможливо; отже WebView не може бути довгоживучим фоном.
- **Вбивство рендерера:** система може вбити renderer-процес для пам'яті (`onRenderProcessGone`, API 26+; `setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, …)` — API 26+, доступні). Після цього інстанс WebView непридатний — destroy + створити новий. Новий WebView одразу перевикористовує джар → без ре-challenge (той самий UA+IP, TTL не минув).
- **Смерть процесу (LMK):** джар на диску → clearance переживає; стан вкладки (DOM/історія/URL) — ні. Застосунок має сам зберігати поточну URL книги (Room/DataStore) і відновлювати її — перезавантаження без нового challenge.
- **Висновок для патерну:** в'ю жити не повинно під час відтворення — destroy на виході з поверхні (застосунок уже робить це у `FourReadWebScreen`), сесія живе в джарі.

## 5. Наслідки для патерну (для #71 прототип і #73 UX)

1. **Solve-once UX:** користувач проходить interactive challenge один раз на TTL (30 хв–24 год), далі сторінки книг відкриваються без challenge — у т.ч. у нових WebView-інстансах (свіже в'ю, той самий джар).
2. **Вирішальний тест прототипу #71:** після проходження challenge взяти перехоплений mp3-URL і завантажити його **власним HTTP-стеком застосунку** (cookie з джару + той самий UA): 200 → Media3-відтворення працює напряму (ймовірно для mp3-CDN без строгого CF); 403 → аудіо має гратися через WebView-сесію (прихований `<audio>`) або через проксі байтів. Від цього залежить вся архітектура — T1 має це виміряти першим.
3. **UA-дисципліна:** єдина константа UA для WebView і Media3; жодного дрейфу версій Chrome.
4. **`CookieManager.flush()`** після challenge та перед бекграундом — щоб не втратити свіжий clearance при вбивстві процесу.
5. **Власне збереження позиції перегляду** (URL книги) у сховищі застосунку; джар відповідає за решту.
6. Обмеження JS-моста (аудит SEC-003) — перехоплення через `shouldInterceptRequest`/`evaluateJavascript`, не через розширення `addJavascriptInterface`.

## References

- Cloudflare — [Clearance · Cloudflare challenges docs](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/) (challenge/precursor clearance, рівні, Challenge Passage, прив'язка до пристрою, 4096 байт)
- Cloudflare — [Cloudflare Cookies](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/cloudflare-cookies/) (`cf_clearance`, `__cf_bm`, `__cfuvid`)
- Android — [Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview) (onRenderProcessGone, Renderer Importance, Safe Browsing)
- Android — [WebViewClient.shouldInterceptRequest](https://developer.android.com/reference/android/webkit/WebViewClient) (background thread, WebResourceResponse, null = network)
- Android — [CookieManager](https://developer.android.com/reference/android/webkit/CookieManager) (per-app jar, flush, removeAllCookies)
- Спільнота — прив'язка `cf_clearance` до IP + UA + TLS-відбитка (dev.to / scrapeless / capsolver огляди 2025–2026); Android WebView session-cookies не переживають рестарт (StackOverflow), JS у фоновому WebView зупиняється ~5 хв (StackOverflow)
