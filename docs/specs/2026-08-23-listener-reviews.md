# Spec-40: Відгуки й оцінки слухачів — спільна гілка на сторінці книги

> **Status:** ready-for-agent. Синтезовано з сесії `/grilling` 2026-08-23
> (П1–П13; усі рішення ухвалені свідомо, відхилення від рекомендацій зафіксовані
> в тексті). Два нові шви (`ListenerReviewsStore`, `ListenerIdentity`), решта —
> розширення наявних.

## Problem Statement

Сторінка книги в «Слухайці» показує лише зірку джерела (★4.9, спарсену зі
сторінки сайту) — і все. Слухач не бачить, що думають інші слухачі саме цієї
програми, не може поставити свою оцінку чи написати коментар, а після
перевстановлення програми його голос взагалі губиться разом із профілем.
Джерела вже мають патерн «зірки + коментарі відвідувачів» — у «Слухайки» є все,
щоб зібрати таку ж спільну гілку, але сховища для неї та ідентичності немає.

## Solution

Спільна гілка **«Відгуки»** внизу сторінки книги, у стилі Google-відгуків:
над текстом коментаря — зірки автора, далі нік, дата й тег начитки. Заголовний
бал — чесна плоска середня між оцінками всіх джерел і оцінками наших слухачів.
Ідентичність — невидима (мовчазний анонімний акаунт, піднятий до постійного
згенерованими обліками), яка переживає перевстановлення: на тому самому
телефоні — мовчки через прив'язку пристрою, на новому — одним кодом
відновлення. Коментарі відвідувачів джерел показуються окремим підблоком без
змішування з нашими картками.

## User Stories

1. As a listener, I want to rate any book 1–5 stars right on its page, so that my opinion counts even without words.
2. As a listener, I want to optionally add text under my stars, so that I can explain my rating like a Google review.
3. As a listener, I want to publish a bare rating without text, so that leaving feedback takes five seconds.
4. As a listener, I want to see other listeners' reviews as cards with stars above the text, so that I can scan opinions quickly.
5. As a listener, I want to see which narration a reviewer listened to (narrator tag), so that I know whether their praise is about the story or the диктор.
6. As a listener, I want my currently open narration pre-filled as the tag on my review, so that tagging costs nothing.
7. As a listener, I want to change or clear the narration tag before publishing, so that I am never mislabelled.
8. As a listener, I want to edit my published review through the same form, so that fixing a typo does not duplicate anything.
9. As a listener, I want to delete my review behind an explicit confirmation quoting what is removed, so that destructive actions never look neutral.
10. As a listener, I want the headline score to combine source ratings and listener reviews into one average, so that I see one honest number for the book.
11. As a listener, I want the composition of that average labelled («джерела і слухачі») with the real count, so that the number matches what I can verify.
12. As a listener, I want the source's own ★ to stay visible as a separate row, so that crowd opinion never masquerades as the site's.
13. As a listener, I want reviews ordered newest first, so that fresh opinions are on top.
14. As a listener, I want to hide all reviews of an author I find noisy, so that my feed stays clean without waiting for any moderator.
15. As a listener, I want to unhide authors in settings, so that muting is reversible.
16. As a listener offline, I want to write a review that sends automatically when network returns, so that connectivity never eats my text.
17. As a listener offline, I want to see the cached reviews of a book, so that the page is not empty without network.
18. As a listener, I want an honest pending state on a not-yet-sent review, so that I never believe others have seen it before they did.
19. As a listener, I want a generated nickname by default, so that publishing reveals nothing about me unless I choose.
20. As a listener, I want to change my nickname in settings, so that my future reviews carry the name I want.
21. As a listener who reinstalls the app on the same phone, I want my profile restored silently, so that my votes stay mine with zero effort.
22. As a listener who moves to a new phone or resets this one, I want to restore my profile with one recovery code, so that my history survives hardware.
23. As a listener, I want the recovery code shown only after fingerprint/PIN confirmation, so that a borrowed phone cannot leak it.
24. As a listener with cloud backup enabled, I want everything restored automatically after reinstall, so that usually I do not even need the code.
25. As a listener, I want to see visitors' comments from the source site in a clearly-labelled separate subsection, so that extra context arrives without being confused with our community's reviews.
26. As a listener, I want books nobody rated to simply show no stars, so that I never see fabricated zeros.
27. As the maintainer, I want one review document per person per work (id `workId_uid`), so that double-voting is impossible by construction.
28. As the maintainer, I want writes gated by App Check AND `auth.uid == document.uid`, so that neither bots nor neighbours can forge or edit reviews.
29. As the maintainer, I want rating and length validated at the rules boundary, so that a hostile client cannot bloat or poison documents.
30. As the maintainer, I want the device-binding table writable only for the caller's own uid, so that silent restore can never be pointed at someone else's profile.
31. As the maintainer, I want only two new seams, so that the feature rides the existing deep-module architecture instead of adding parallel paths.
32. As the maintainer, I want each source's comment parser isolated behind the existing adapter seam with an "empty" default, so that sources without provable comments cost nothing.
33. As the maintainer, I want the combined-average rule pure and JVM-tested, so that number truthfulness regressions surface in CI, not on phones (ADR-0014).
34. As the maintainer, I want the local mute list as a tiny Room addition, so that it works offline and joins naturally later.
35. As the maintainer, I want the free Firebase tier to remain the operating boundary, so that the feature cannot bankrupt the project.
36. As the maintainer, I want the identity design to leave account-linking to a real login open, so that future sync and recommendations reuse the same anchor without migration.

## Implementation Decisions

- **Одна сутність «Відгук».** Документ у колекції `book_reviews`: `{workId
  (mergeKey), uid, authorName, rating (int 1–5, обов'язково), body? (≤2000
  знаків), editionTag? (назва начитки), createdAt, editedAt?}`. Ідентифікатор
  документа — `workId_uid`: одна людина = один відгук на книгу, дубля
  гарантована базою. Голий рейтинг і короткий коментар — це той самий документ,
  окремих сутностей немає (рішення П2).
- **Якір — Work, тег начитки — необов'язковий.** Гілка відгуків одна на Work
  (mergeKey), як канонічні обкладинки; поле начитки автопідставляється з
  відкритої версії, змінюється вручну або «Не вказувати» (П4).
- **Заголовна середня — плоска арифметична.** Одне джерело з оцінкою = один
  голос, один відгук слухача = один голос: середнє всіх доданків, підпис складу
  («джерела і слухачі · N оцінок»). Джерело без оцінки не входить; нікого —
  жодних зірок. Рахується з уже наявних даних сторінки (профілі джерел + список
  відгуків), нових запитів і сховища для числа немає (П12).
- **Показ — тільки на сторінці книги.** Блок «Відгуки» після опису/профіля:
  заголовна середня, список карток (новіші зверху), кнопка «Написати відгук»;
  свій відгук редагується тією ж формою. Зірочок на картках стрічок поки немає
  (П5).
- **Пишуть усі.** Єдина передумова — валідний застосунок (App Check) +
  ідентичність; вимогу «книга в бібліотеці» відхилено як клієнтську косметику
  без захисної цінності (П7).
- **Модерація v1 — мінімальна й чесна:** ліміти довжини, редагування/видалення
  лише свого (правилами), локальний м'ют автора (таблиця `hidden_reviewers`,
  Room v20; діє тільки в того, хто зам'ютив). Серверних скарг немає — без
  процесу розгляду це театр (П8).
- **Ідентичність, що переживає перевстановлення (П3, П13):**
  - перший запуск — мовчазний Firebase Anonymous Auth, одразу піднятий до
    постійного password-акаунта через `linkWithCredential(EmailAuthProvider)`
    зі **згенерованими** обліками (випадковий `…@slukhayka.local` + випадковий
    пароль) — публічний API, без нашого сервера;
  - обліки зберігаються локально, входять в Android Auto Backup (backup-правила)
    і показуються в ⚙️ як **«Код відновлення профілю»**, показ якого охороняє
    BiometricPrompt (відбиток/PIN пристрою);
  - тихе відновлення на тому ж телефоні: `device_bindings/{ANDROID_ID} → uid`
    у Firestore; на чистій установці пошук за ANDROID_ID → мовчазний вхід у
    свій профіль; правило запису дозволяє прив'язувати лише власний `uid`;
  - новий телефон / factory reset / вимкнений бекап — введення коду відновлення
    → `signInWithCredential` → той самий uid;
  - біометрія — замок на секрет, не ідентичність; IMEI та інші апаратні
    ідентифікатори відхилено (недоступні з Android 10 / політика Play /
    помирають разом із телефоном).
  - Нік за замовчуванням генерується («Слухач-4821»), редагується в ⚙️; зміна
    ніка не переписує старі відгуки (authorName денормалізований на момент
    публікації).
- **Офлайн — вбудована черга Firestore.** SDK персистентний кеш уже увімкнений
  (`getInstance()` без вимкнення): написаний офлайн відгук дошлються сам,
  переживаючи перезапуск. З нашого боку — оптимістичний UI і чесний стан
  «надішлемо при мережі». Свого аутбокса немає (П9).
- **Коментарі відвідувачів джерел (рішення П10 — «Б», свідоме відхилення від
  рекомендації):** нова здібність на наявному шві адаптерів
  `parseComments(html): List<String>` із дефолтом «порожньо». Реальний парсер у
  цій спеці — лише 4read (єдине джерело з доведеними коментарями); sluhay,
  sound-books, audiobookmp3 поки віддають порожньо, кожен майбутній парсер —
  одна функція + фікстури. Показ — окремим підблоком «Коментарі відвідувачів
  {джерело}» простим списком під нашими картками (без авторів/дат вони не
  витримують форму картки), їдуть разом із профілем сторінки, окремого сховища
  немає.
- **Шви:** `ListenerReviewsStore` (чистий JVM-seam у формі
  `SharedBookMetaStore`: батч-читання відгуків Work, запис/оновлення/видалення
  свого, best-effort і мовчазний за контрактом) і `ListenerIdentity`
  («хто я»: uid + нік, вся магія виживання всередині). Розширення наявних:
  `SourceAdapter.parseComments`, чистий `CombinedAverage`, таблиця м'юту.
  Екрани читають модулі напряму (ADR-0008).
- **Правила Firestore:** читання `book_reviews` публічне (як решта крауд-фактів);
  створення/оновлення/видалення — App Check **і** володіння
  (`request.auth.uid == …uid` / `resource.data.uid`); валідація полів правилами
  (rating — ціле 1..5, довжини, обов'язковість хоча б рейтингу);
  `device_bindings` — запис лише `request.auth.uid == request.resource.data.uid`.

## Testing Decisions

- **Що таке добрий тест:** зовнішня поведінка — документ повертається кругом,
  чужий uid не може писати, порожня множина оцінок дає «нічого», офлайн-запис
  переживає рестарт — а не внутрішності Firestore чи структуру композитів.
- **`ListenerReviewsStore` (чистий JVM):** get/put/delete, батч-читання,
  відсутності й збої дають порожнечу — на in-memory фейку; приклади — фікскурні
  тести universe/book-meta store.
- **`CombinedAverage` (чистий JVM):** формула, чесність (джерело без оцінки не
  входить, порожньо → нічого, реальний лічильник), крайові випадки; поруч з
  іншими чистими правилами (приклад: `SmartRewind`, `computeResumeStart`).
- **`parseComments` (фікстури HTML):** 4read — справжні тексти; негативні
  фікстури інших джерел — порожньо; приклади — тестовi фікстури адаптерів
  (FourReadAdapterTest, WebViewHtmlParserTest).
- **Локальний м'ют (Room):** таблиця, приховування/повернення, міграція v19→v20;
  приклади — патерн DeepModulesRoomTest.
- **UI-блок (Roborazzi-снапшот):** блок відгуків із картками, підблоком джерела
  і станами (порожньо, pending); приклад — SourceProfileBlockSnapshotTest.
- **Ідентичність (Robolectric + пристроєний smoke):** bootstrap, тихе відновлення
  за прив'язкою, відновлення кодом, біометричний замок — фейк `ListenerIdentity`
  у JVM + phone-test для живого ланцюга (патерн spec-38/39 T-верифікацій).
- **Правила:** перевірка матриці «свій/чужий/без токена» на staging-консолі
  перед мерджем; результат зафіксовано в описі PR.

## Out of Scope

- Серверні скарги, черга модерації, консоль розгляду — з'являться разом із
  процесом, який їх читатиме.
- Голосування «корисно», відповіді на відгуки, фото/зображення, заголовки
  відгуків.
- Окремі гілки чи рейтинги по начитках (Edition) — тег є, окремої осі немає.
- Парсинг коментарів sluhay / sound-books / audiobookmp3 — до живого HTML із
  доведеними коментарями.
- Реальний логін (Google/email) як UI — двері відкриті через linking, але
  екрана входу немає.
- Крос-пристрійний синк прогресу, бібліотеки та рекомендації — наступні
  споживачі uid-якоря, окремі спецефи.
- Переписування authorName старих відгуків після зміни ніка.
- Будь-який платний рівень Firebase.

## Further Notes

- Разом із кодом оновлюються: `CONTEXT.md` (нові статті глосарію: «Відгук» /
  Listener Review, «Код відновлення»; зауваження до «Source Binding» про
  device-bindings у Firestore — це не той Binding), новий ADR про
  ідентичність, що переживає перевстановлення, та ADR-додаток про чесність
  спільної середньої (ADR-0014).
- Політика приватності/опис у магазині доповнюється розкриттям: анонімний
  профіль, згенеровані обліки, прив'язка пристрою лише для відновлення.
- Споріднені поверхи: спільна метабаза spec-30/32 (патерн шва і App Check),
  ADR-0020 (ключ mergeKey), spec-35 (профіль 4read — звідки парсяться
  коментарі).
- Прийняті ризики: вільний публічний текст без скарг (v1) — свідомо, ліміти +
  м'ют + App Check; ANDROID_ID як ключ відновлення — розкритий, owner-only
  запис, не використовується нідля чого іншого.
