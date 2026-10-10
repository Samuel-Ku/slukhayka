# #968 — ручне злиття роздвоєної ідентичності твору

Статус: пропозиція для рішення власника. Етап D1 виконано як специфікацію;
production-дані та схема не змінені. Початкова база перевірки — `main`
`43a726e02f9292a723f742ebbf0ecb47853a9115`, Room v52, 43 сутності.
Поточна звірка — `main` `6961dd80a5a50cbca82ab7f299b1bc4d8e768832`,
Room v54, 45 таблиць після #1180. Матриця й майбутні перевірки нижче
враховують нові носії вимірів; сумісність production-merge ще не доведена.

## Рекомендоване рішення

Для неоднозначної пари слухач явно підтверджує «Це той самий твір».
Зливається бібліографічний Work. Усі наявні начитки, бібліотечні рядки,
розділи, фізичні джерела, файли й місця слухання зберігають свої ID.
Особиста поверхня показує один Work і відкриває всі його начитки та проходи.
Навіть дві схожі начитки з різними Edition ID лишаються окремими: ця дія
не доводить тотожності аудіо. Їхнє можливе об'єднання — інша підтверджена дія.

Збіг декорованої назви й автора дає кандидата для перевірки, а не згоду.
Порожній автор, збірка, переклад, омонім, інша редакція тексту та неповні
метадані мають бути видимими в прев'ю. Локальні книжки допускаються лише
через цей ручний шлях. `NEVER_MATCH` або `SPLIT` з явним наміром слухача
зупиняють підтвердження, доки він окремо не скасує саме цю корекцію.

Рішення власника в [#968](https://github.com/Samuel-Ku/slukhayka/issues/968)
зупиняє ремонт збережених імен до погодження похідної ідентичності.
Цей документ не скасовує його. Погодження D1 дає контракт наступній роботі,
але саме по собі не запускає міграцію чи припарковану гілку ремонту імен.

## Що вже є і де проходить нова межа

[`DuplicateWorkMerger`](../../app/src/main/java/com/slukhayka/audiobooks/data/merge/DuplicateWorkMerger.kt)
вже автоматично обробляє доведені remote SEO duplicates. Він перечитує
докази в транзакції: однакові відомі наратори, сумісні мови, ті самі
(type, URL) джерела, однакові непорожні назви й тривалості розділів,
суцільні індекси треків; жодних downloads, збережених шляхів або хешів.
Work з іншою живою бібліотечною начиткою теж захищений. Чинний вузький
алгоритм і [його тести](../../app/src/test/java/com/slukhayka/audiobooks/data/merge/DuplicateWorkMergerRoomTest.kt)
не треба реалізовувати знову або послаблювати. Його операція переносить
новіший прогрес і видаляє дубльовані розділи та історію; для загального
ручного злиття вона непридатна. Новий шлях має власний контракт збереження.

Є важлива різниця між доменною мовою й expand-схемою:

- `library_entries.id = audiobooks.id = bookId`; `library_entries.workId`
  посилається на бібліографічний Work. Кілька фізичних рядків під одним
  Work ще потрібні чинним download і playback читанням.
- `editions.workId` для бібліотечної начитки означає **bookId**;
  для каталожної Edition без бібліотечного рядка може означати **Work.id**.
  Це підтверджують `BOOK_SELECT`, `getEditionForWork`, `getBookByEditionId`,
  `workFeedPage` у [DAO](../../app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDao.kt).
  Не можна механічно переписати всі `editions.workId` на канонічний Work.
- `edition_facets.workId` означає бібліографічний Work.
- У [52.json](../../app/schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/52.json)
  тільки `work_sources.workId → works.id` має FK, `ON DELETE CASCADE`,
  `ON UPDATE NO ACTION`. Інші 42 сутності не мають оголошених FK.
  Успішний `foreign_key_check` не доводить їхньої цілісності.
- `corrections.MERGE` вже містить також локальне import-preview значення
  `sourceBookId->targetBookId`
  ([ImportPlanner](../../app/src/main/java/com/slukhayka/audiobooks/data/imports/ImportPlanner.kt)).
  Це не готовий типізований Work redirect. Наявність enum MERGE не доводить,
  що каталожні письменники читають його як alias.

Контракт спирається на ADR [0001](../adr/0001-separate-work-edition-source-and-listener-state.md),
[0011](../adr/0011-multi-edition-library-rendition-cards.md),
[0046](../adr/0046-personal-library-spans-formats.md),
[0048](../adr/0048-one-work-card-opens-every-narration.md),
[0056](../adr/0056-manual-chapter-order.md),
[0057](../adr/0057-local-folder-grouping-and-provenance.md).
Він не підміняє чинну схему бажаною моделлю одного фізичного Library Entry.

## Команда й канонічний Work

Майбутній seam: `prepareManualWorkMerge(a, b)` повертає незмінне прев'ю;
`applyManualWorkMerge(approvedPlan)` повертає `Applied(operationId)`,
`AlreadyApplied(operationId)`, `Stale`, `NeedsDecision(conflicts)` або
`Refused(reason)`. Прев'ю містить обидва ID й mergeKey, назви/авторів,
всі Edition з мовою/наратором/розділами, позиції, bookmarks, Readthrough,
файли/downloads, конфлікти особистих полів, tombstones і перелік redirects.
Показує точну кількість збережених об'єктів і факт «позиції лишаються окремими».

Канонічний ключ K — непорожній `MergeKey.keyFor` **підтверджених** назви
та автора. Якщо існує Work з `id = K` і `mergeKey = K`, він є ціллю C.
Якщо такої цілі немає, створюється C з `id = mergeKey = K`, а обидва старі
Work стають aliases. Якщо K належить третьому Work, його включення потребує
нового прев'ю й окремої згоди; прихованого третього учасника немає.
Неоднозначний lookup кількох Work на K відхиляється для окремого ремонту.

Канонічні title/author обирає слухач у прев'ю. Поля seriesTitle/URL/index
та cover зберігають цільові відомі значення; порожнє заповнюється одним
незапереченим значенням. Дві різні відомі величини — явний вибір,
не «останній запис виграв». `addedAt` — найраніший відомий додатний час;
якщо всі часи невідомі, він не вигадується з часу злиття.
Непереможні значення і provenance лишаються в історії операції.

Новий implementation scope потребує двох типізованих локальних носіїв
(назви нижче описують контракт, а не вже наявні таблиці):

1. `WorkIdentityRedirect`: `(namespace, oldKey)` — PK; `canonicalWorkId`,
   `operationId`. Namespace розрізняє Work ID і mergeKey. Ціль завжди живий
   Work; самопосилання й цикли відхиляються. Два активні призначення одного
   alias різним Work — конфлікт, який потребує рішення, не LWW.
2. `WorkMergeOperation`: стабільний ID, `contractVersion`, підтверджені
   учасники/рішення, before-images і after-images змінених та видалених
   рядків, fingerprint, стан `APPLIED`/`UNDONE`, час. Журнал приватний,
   зберігається в тій самій Room-базі й транзакції; токени/сесії до нього
   не потрапляють. Поки Undo доступний, before-images не видаляються.
   Типізований `preservedParticipants` містить усі підтверджені bookId та
   Edition ID обох Work і прапорець `autoMergeExcluded=true` для кожного.
   Цей durable consent-захист не виводиться з поточних Work ID, URL,
   download flags або лише наявності активного redirect. Окремої третьої
   таблиці для нього не потрібно; читач має індексований lookup усередині
   того самого носія операцій.

Номер нової схеми визначається тільки після повторної перевірки main й
паралельних міграцій. D1 не резервує номер наступної схеми і не додає таблиць у production.

Redirect — пам'ять ручного рішення цього слухача. Він не стає загальною
бібліографічною правдою в shared catalog. Усі місцеві читання та письменники
мають спершу розв'язувати відомі aliases: catalog/ingest/search, імпорт,
facets, sync, tracked works, tombstone gate, recommendations, book navigation.
Refresh старого K не створює вдруге donor Work. Старий deep link відкриває C.
Read-only каталожний alias не означає дозволу імпорту чи зняття tombstone.

### Захист від повторного startup merge

[App.kt](../../app/src/main/java/com/slukhayka/audiobooks/App.kt) на старті
після scrub викликає `DuplicateWorkMerger.mergeOnce()`. У
`canAutoMerge` sibling guard спрацьовує тільки коли
`loserWork != survivorWork`. Після ручного переприв'язування обидва entry
можуть вказувати на C; однакові narrator/language/source URL/topology і
відсутність downloads тоді дозволили б видалити одну з двох збережених
начиток. Redirect чи byte-exact progress самі цього не зупиняють.

Наступна реалізація має **посилити** `canAutoMerge` перевіркою
`preservedParticipants`. Усередині тієї самої `writeBatchRunner`/Room
транзакції, після перечитування обох books та Editions і до будь-якого
перенесення або delete, перевіряються **обидва** bookId та **обидва**
Edition ID. Будь-який `autoMergeExcluded=true` повертає false. Результат
кандидатного grouping або кешована перевірка до транзакції не є доказом.
Manual apply, Undo і автоматичний merge поділяють writer gate; автоматичний
шлях повторно читає захист після очікування. Усі нинішні narrator/language/
source/topology/file/sibling guards лишаються; нова перевірка звужує дозвіл
і не змінює поведінку непричетних доведених remote SEO duplicates.

Захист записується атомарно з manual apply й переживає process death,
restart, повтор команди та refresh/import replay. Undo відновлює бібліографію
і redirects, але **не є згодою злити начитки**: `preservedParticipants`
лишається чинним і для журналу зі станом `UNDONE`. Безпечний відкат не
дозволяє startup pass одразу видалити щойно відновлений progress.
До окремого підтвердженого merge Edition захист не знімається й не
видаляється при очищенні before-images. Якщо після Undo існували раніші
exclusions, вони лишаються разом із новим захистом цих participants.
Exception до commit не створює ані domain changes, ані нового protection.
Зняття protection — окремий consent-контракт майбутньої операції Edition;
D1 його не дозволяє.

### Edition resolver після злиття

Старі Edition ID не перераховуються через K. `EditionId.forBook` — генератор
нового ID, а не дозвіл змінити існуючий. Імпорт спершу шукає чинний
(type, exact source-page URL) під aliases C і перевіряє Edition/топологію.
Єдине доведене відповідне джерело веде до старої Edition. Якщо є дві
сумісні за описом Edition, показуються обидві для явного вибору; випадковий
`LIMIT 1`, однаковий narrator чи кількість chapters не обирають позицію.
Нове доказано відмінне аудіо додає власну Edition звичайними дверима.

Для кожної Edition preflight встановлює роль anchor за фактичними
`audiobooks`/`library_entries`, `sources`, `chapters` і `playback_progress`,
а не за самим збігом рядка ID. Бібліотечний owner bookId лишається незмінним;
каталожний anchor donor Work змінюється на C. Суперечливі власники,
неіснуюча Edition для живого стану або null-anchor без єдиного доведеного
зіставлення дають `NeedsDecision`; записів до рішення немає.

## Покриття чинних 45 таблиць

Позначення: **K→C** означає переприв'язування бібліографічного Work/ключа
через типізований resolver; bookId і Edition ID цим правилом не змінюються.
**Історія** означає byte-exact before-image з можливістю Undo, не тиху втрату.
Початкові 43 таблиці звірені з v52; зміни вимірів — з v54.
Джерела визначень —
[Entities](../../app/src/main/java/com/slukhayka/audiobooks/data/db/Entities.kt),
[FacetEntities](../../app/src/main/java/com/slukhayka/audiobooks/data/db/FacetEntities.kt),
[додаткові сутності db](../../app/src/main/java/com/slukhayka/audiobooks/data/db/).

| Таблиця / ключ | Злиття та конфлікт |
|---|---|
| `works` / id | Створити/оновити C за підтвердженими полями; donor видалити тільки після переприв'язування всіх бібліографічних залежностей та створення redirects. Before-images обов'язкові. |
| `library_entries` / id=bookId | Кожен рядок і його origin/favorite/createdAt/downloads лишається; змінюється тільки workId. Особиста Work-картка має favorite=OR; час додавання=min відомих. Origin кожного рядка видно окремо; ручне злиття саме не перекласифікує UNKNOWN/AUTO як особистий імпорт. |
| `audiobooks` / id | Усі rows, поля, sourceTreeUri й ID лишаються. Перелік начиток групується через entry.workId. Загальна назва картки береться з C; rendition-specific narrator/genre/rating не переписуються. |
| `editions` / id | Зберегти id, narrator/language/totals/addedAt. Library anchor bookId зберегти; тільки доведений catalog anchor K→C. Різні Edition з тим самим narrator теж лишаються окремими. |
| `sources` / id | Усі bookId/editionId/type/URL/streamOnly/scanFingerprint незмінні. Однакові URLs у різних Edition не дедуплікувати. |
| `source_tracks` / id | Усі sourceId/індекси/URL/localFilePath/hash/flags byte-exact. Жодних rename/delete/link файлів. |
| `chapters` / id | Усі bookId/editionId/індекси/назви/тривалості лишаються. Інша топологія не переводиться в секунди чужої Edition. |
| `playback_progress` / editionId | Весь рядок byte-exact, включно completion, pause і speed. Дві Edition з двома позиціями мають дві позиції. Конфлікт owner для одного editionId зупиняє merge; max(position), max(timestamp) і OR(completed) тут не застосовуються. |
| `bookmarks` / auto id | Усі bookmark ID, bookId, editionId, original chapterIndex, note, timestamps лишаються. Навіть однакові мітки не зливаються без окремої дії. |
| `playback_events` / auto id | Уся історія з bookId/sourceKey/позиціями/deviceId лишається; не реконструює live progress. |
| `playback_failures` / auto id | Діагностична історія лишається зі старим bookId і streamUrl; bookId досі живий. |
| `listening_stats` / dateIso | Увесь рядок незмінний, включно verifiedListenedMillis та доданими у v54 offlineListenedMillis/castListenedMillis/nightListenedMillis. Це денний виміряний час, не сума позицій; merge/Undo не реконструюють і не скидають його. |
| `playback_sessions` / auto id | Усі id/startedAt/endedAt/verifiedMillis/offlineMillis/castMillis лишаються. У схемі немає bookId, editionId або workId; приписати сеанс Work, rekey, дедуп чи перерахувати endedAt через merge не можна. |
| `achievement_counters` / key | Усі keys/count незмінні. Це монотонні лічильники спостережених дій; Work merge/Undo не переозброюють таймер і не створюють, не підсумовують та не скидають факти. |
| `edition_settings` / bookId,sourceKey | Нічого не змінювати. Наявний source-scoped legacy ключ не перетворювати на Work-scoped. |
| `readthroughs` / id | Змінити тільки бібліографічний workId. libraryEntryId, format/state/dates/editionId/unit/value/journalJson лишаються. Усі повторні проходи й несумісні одиниці збережені. |
| `work_sources` / id; FK Work | Переприв'язати до C, відновити детермінований id тією самою функцією письменника. Дедуп тільки exact(sourceId,sourceUrl). Для повтору min addedAt; відомий cover/duration/streamOnly конфлікт — вибір у плані, усі donor fields в історії. Спершу UPDATE/INSERT залежностей, потім DELETE donor Work. Жодного REPLACE parent Work з FK CASCADE. |
| `series_members` / workId,seriesId | Union серій. Одна серія з різними position — явний вибір з обома resolvedAt в історії. Після вибору derived refresh не переважає USER_MADE correction. |
| `series` / id | Збереження без змін. Злиття Work не доводить злиття серій. |
| `universes` / id | Збереження без змін. |
| `work_facets` / workId | Одне поле canonicalAuthorId для C: однакове/єдине відоме переноситься; різні відомі person ID потребують окремого підтвердження автора. Merge Work не є автоматично merge Person. |
| `work_facet_series` / workId,seriesId | Union з K→C; rebuild за підтвердженими memberships, не за відкинутим position. |
| `genre_facets` / id | Словник зберігається; жанр не перейменовується й не вгадується з назви. |
| `genre_assertion_states` / workId,sourceId | K→C. Якщо два документи одного sourceId: ENUMERATION переважає SEARCH; однаковий rank — новіший documentUpdatedAt. Рівний rank+time з різним payload — NeedsDecision. Непереможний повний документ в історії. |
| `genre_assertions` / id | Документ-переможець переноситься цілком: assertions, rawText/sourceId/observedAt; id відтворюється штатною функцією на C. Не змішувати частини двох різних source-document. Непереможний payload та assertionId в історії. |
| `work_genres` / workId,genreId,sourceId | Projection тільки перенесених source-documents, rebuild усередині транзакції. Union різних sources; не union старого й нового документа одного source. |
| `edition_facets` / editionId | Усі Edition ID й заявлені поля лишаються; бібліографічний workId K→C. Несумісність з реальною library Edition дає NeedsDecision, не copy поверх чужої Edition. Availability не стає Work-wide. |
| `author_facets` / id | Work merge не змінює person ID або ім'я. Похідний canonicalAuthorId конфлікт вирішується до apply. Окремий атомарний name repair описано нижче. |
| `author_aliases` / authorId,normalizedAlias,sourceId | Збереження без змін. Не переписувати rawAlias як «чисте» джерельне свідчення. |
| `person_bookmarks` / kind,id | Збереження всіх полів без змін під час Work merge; згортання Work не є toggle person bookmark. Author/Narrator role не змішуються. Name repair має власний контракт нижче. |
| `works_fts` / FTS4 без Work PK | Видалити всі donor і C rows; відновити рівно одну C row через штатні fold/indexability правила, з narrator через обидві підтверджені ролі Edition. Tombstone/scam залишаються неіндексованими. |
| `corrections` / mergeKey,kind,value | Усі raw rows лишаються. Resolver дає логічну union aliases; USER_MADE переважає DERIVED. Різні активні FIELD одного поля — рішення слухача. NEVER_MATCH/SPLIT проти злиття блокують його. `chapter-order:bookId` і `local-folder:hash` не є Work ключами, зберігаються byte-exact. Work redirect сюди без типізованої версії не записується. |
| `tombstones` / bookId | Raw tombstones зберегти. Перевіряти C та всі Work/bookId aliases. Якщо будь-який учасник видалений, merge не знімає видалення; потрібна окрема явна дія повторного додавання або Refused. Старе видалення на sync не може воскресити donor. |
| `recommendation_preferences` / kind,targetKey | Raw verdicts зберегти; effective HIDE_WORK/REDUCE_SIMILAR і sourceWorkId розв'язуються через aliases. Однакові kind+canonicalKey рахуються один раз. HIDE_AUTHOR лишається person-scoped; merge Work не міняє його target. |
| `embedding_vectors` / workId | Derived cache: інвалідувати C/donor entries, before-images в історії. Повторне embedding лише коли потрібне; вектор donor не присвоюється іншому textHash. Це не потребує нового ranking experiment. |
| `popularity_assertions` / id | Raw mergeKey/provenance/value/time лишаються; read joins aliases. Effective source kind signal обирає найновіший observedAt, рівний час з різним rawValue не дає вигаданого значення. listener_rating aggregate не сумується між aliases: rebuild з дедуплікованих підтверджених відгуків; до rebuild stale aggregate не показувати як нову спільну середню. |
| `feed_snapshots` / sourceId,feedKey,pageCursor | Raw cardsJson/fetchedAt лишаються, provenance не переписується. Replay карток проходить canonical resolver і не створює donor. |
| `submission_states` / sourceId | Source, bookId, metadataJson і pending state лишаються; callbacks досі прив'язані до того самого Source. Злиття не означає playing verdict/publication. |
| `listener_collections` / id | Всі колекції, тексти та fork attribution лишаються. |
| `listener_collection_items` / collectionId,bookId | Raw rows/причини/addedAt лишаються під живими bookId. Work-level UI dedup через entry; earliest addedAt задає порядок, різні reason доступні в деталях. Не DROP другого reason через INSERT IGNORE. |
| `hidden_reviewers` / authorName | Це м'ют автора відгуку, не автора книжки; збереження без змін. |
| `achievements` / id | earnedAt/seenAt/pinnedAt лишаються. Злиття не відкликає здобуте й не видає нову нагороду саме за merge. |
| `achievement_facts` / key | Raw action facts лишаються. При оцінюванні Work-подій resolver не рахує alias удруге. Невідомі формати key не переписуються. |
| `friendship_states` / pseudonym | Збереження без змін. |
| `social_blocks` / pseudonym,direction | Збереження без змін. |

## Дані поза Room і повний шлях читання

Збереження таблиць недостатнє, якщо дані стають недосяжними.

| Носій / чинний seam | Контракт наступної реалізації |
|---|---|
| [`OfflineDownloads`](../../app/src/main/java/com/slukhayka/audiobooks/data/downloads/OfflineDownloads.kt), local audio files, active jobs | bookId/chapterId/sourceId лишаються, файли не переміщуються. Перший реліз ручного merge відхиляє APPLIED під час активного playback/download/import/rescan будь-якого учасника; після завершення можна повторити. PAUSED зі збереженими файлами допускається, стан/темпи не скидаються. Перевірка активності повторюється під спільним per-book write gate; заборона має блокувати й новий job між перевіркою та commit. |
| [`ChapterOrder`](../../app/src/main/java/com/slukhayka/audiobooks/data/imports/ChapterOrder.kt), [`LocalFolderMemory`](../../app/src/main/java/com/slukhayka/audiobooks/data/imports/LocalFolderMemory.kt), ImportGrantStore | bookId і chapter IDs збережені, FIELD order та lineage, SAF grant/grouping/доступ лишаються. Reparent lineage не потрібен і не викликається. Reorder не переносить progress до displayed index. |
| [`ProgressSyncLedger`](../../app/src/main/java/com/slukhayka/audiobooks/data/listening/ProgressSyncLedger.kt), listening_state `{uid}_{editionId}` | Ключі й server timestamps незмінні; sync тієї самої Edition працює після merge. Злиття Work не створює нове прослуховування. |
| [`WorkRelationships`](../../app/src/main/java/com/slukhayka/audiobooks/data/listening/WorkRelationships.kt), work_relationships `{uid}_{mergeKey}` | Старі документи не видаляти й не публікувати tombstone заради redirect. Локальний resolver читає відомі aliases; entry/tombstone арбітрується чинним server-time policy, tie=tombstone. Ручне повернення потребує звичайної явної дії. Перенесення ручного merge на інший пристрій потребує окремого owner-private versioned sync контракту redirects; до нього не обіцяти cross-device merge. |
| [`ListenerReviewCodec`](../../app/src/main/java/com/slukhayka/audiobooks/data/reviews/ListenerReview.kt), `{workId}_{uid}` | Shared rows залишаються під первісним workId. Work-сторінка читає aliases, показує кожен own conflict із можливістю вибору. Aggregate враховує одну confirmed review на uid: новіша createdAt, на точному tie — документ канонічного Work, далі лексикографічний documentId. Непереможна review доступна в історії aliases; pending/failed/deleting не підміняють confirmed. Редагування/видалення старої review використовує її первісний documentId і permission. Жодного видалення чужих reviews. |
| [`NarrationRatingCodec`](../../app/src/main/java/com/slukhayka/audiobooks/data/reviews/NarrationRating.kt), `{workId}_{uid}_{editionId}` | Читати aliases Work, Edition ID лишається. Effective одна confirmed rating на uid×Edition з тим самим часовим/tie правилом; різні Edition не усереднювати як одну начитку. Якщо backend відмовляє alias read, merge preview повідомляє про межу shared view й apply зупиняється до придатного reader; втрату приховуванням не дозволено. |
| [`SourceWatchStore`](../../app/src/main/java/com/slukhayka/audiobooks/data/watch/SourceWatchStore.kt), prefs | Raw watches лишаються. Effective watch=OR aliases, seenSourceIds=union, canonical notification target C. Отримане audio не робить auto-import. Seen чи watch не переписувати `.apply()` у середині Room commit: resolver працює поверх raw keys. |
| CoverOverrideStore / SharedBookMetaStore, collective cards/blocks/facets/graph | Особисті FIELD overrides розв'язуються через aliases; різні user cover choices — конфлікт прев'ю. Shared documents/provenance не переписуються глобально. Усі локальні ingest/read paths розв'язують aliases; recommendation graph self-edge після resolution відкидається, дубльовані edges не сумуються як два незалежні докази. Shared block source order лишається, повторні Work у його local view dedup першим входженням. |
| PersonBookmarksSync / PendingPersonBookmarkDeletes | Work merge не змінює person IDs, cloud docs або pending deletes. Окрема зміна person IDs потребує власного outbox і правила нижче. |

До видалення donor Work потрібно інтегрувати reader contracts: бібліотечні
полиці й деталі; bookmarks → navigation; колекції; люди та їхні новинки;
FTS/card hydration; narrator/language/duration filters; autoplay/fallback;
readthrough «Мій рік»; achievements/recommendation exclusions; ingress і sync.
У DAO зараз є прямі `e.workId=w.id` читання поруч із правильним library join
`e.workId=le.id`. Для нового union читача перевіряється **обидва** типи anchor,
із DISTINCT editionId; дубль на join не створює дві начитки або нове завершення.
Старий bookId залишається валідним для OrphanListeningStatePurge.

## Ремонт імен і person identity: окремий атомарний контракт

Рекомендація: робити його окремою підтвердженою операцією після Work resolver,
із повним набором залежностей однієї доведеної person identity, а не
startup scrub чотирьох текстових стовпців. Work merge до цього лишає імена
людей як є й не вважає двох людей тотожними за очищеним текстом.

Якщо власник погодить repair, preview явно підтверджує role і old→new person
ID. Author і Narrator — різні namespaces. Декодування HTML у display name
саме не доводить, що дві людини одна. Зовнішній canonical author ID з
Metadata Assertion зберігається; перерахунок `boundedId` стосується тільки
доведених локально похідних IDs. Повний closure охоплює всі Works/Edition
цієї person у базі, навіть поза початковою парою #968.

Одна Room-транзакція змінює відповідний display text у works/audiobooks/
editions, author_facets, work_facets.canonicalAuthorId,
edition_facets.narratorId, person_bookmarks і derived normalizedName/search
keys; author_aliases переносить provenance зі старими rawAlias, додає
нові search keys штатним AuthorIndex. works_fts rebuild для всього closure.
Edition ID не перераховується навіть після виправлення narrator text;
import resolver зв'язує стару proven source identity з чинною Edition.
Person aliases зберігають old role+ID; HIDE_AUTHOR й старі person deep links
та notification intents використовують їх. Неповний closure дає Stale.

Якщо bookmark є під обома IDs: createdAt=min, lastSeenAt=max;
notifyEnabled береться з новішого updatedAt, точний tie → false;
lastNotifiedAt і lastNotifiedCount переносяться **однією парою** з рядка
з новішим lastNotifiedAt, tie → більший count. updatedAt стає часом
підтвердженої операції. Обидва raw rows в історії; жодної синтетичної
новинки або повідомлення саме через repair. USER_MADE FIELD конфлікти
обираються в preview. Author_alias key collision з різним rawAlias зберігає
новіший observedAt у live projection, обидва raw claims в історії.

Зміна зовнішніх person documents не є Room transaction. Спершу commit
локального alias/journal та durable outbox; потім owner-only idempotent
copy підтвердженого bookmark, acknowledgement, і тільки тоді delete старого
own document. PendingPersonBookmarkDeletes застосовується до всього alias
набору, щоби старий cloud snapshot не повернув видалене. Offline/rejection
лишає локальне рішення й outbox, ніколи не видає false acknowledgement.
Поки цей контракт sync не реалізований і не перевірений, linked-device
name repair не випускається. База правил безпеки цим документом не змінюється.

## Preflight, commit, повтор і Undo

1. Побудувати dependency closure за ID/mergeKey aliases, бібліотечними
   власниками й активними corrections. Перевірити referential/card/Edition
   bindings, наявність paths/hash без зміни файлів, усі конфлікти таблиці,
   повноваження доступу до shared aliases. Підтверджене прев'ю має SHA-256
   canonical serialization усіх raw рядків, які впливають на рішення;
   включно memberships, latest genre payloads, redirects, tombstones,
   collections, readthroughs, facet dictionaries і конфліктами.
2. Слухач підтверджує саме цю версію прев'ю. Canonical serialization
   задає sorted table+PK order, explicit null, byte arrays як bytes hash;
   час виконання не входить у fingerprint. operationId — UUID, збережений
   у approvedPlan, повтор команди використовує той самий ID.
3. Захопити write gates всіх bookId у стабільному порядку; start нового
   playback/download/import/rescan блокується цим самим gate. У Room
   `withTransaction` перечитати closure і fingerprint. Зміни після preview,
   новий tombstone/correction/participant, missing file proof або активний
   job дають Stale/Refused **до першого domain write**. Неконтрольований
   writer, який не проходить resolver/gate, є release blocker, не best effort.
4. Зафіксувати before-images; створити C через UPDATE/INSERT, не REPLACE
   існуючого FK parent; переприв'язати бібліографічні залежності;
   зберегти старі особисті rows, типізовані redirects, durable
   `preservedParticipants` exclusions і chosen conflicts;
   перескласти derived projections; видалити donor Work; записати
   after-images й `APPLIED` в тому самому commit. Навмисного network/file
   I/O в транзакції немає. Counts незмінних Edition/Chapter/Source/Track/
   Bookmark/Progress/Events/Readthrough ID-set мають збігтися byte-exact.
5. Перед commit перевірити всі правила актуальної 45-table матриці, FK і власні orphan queries,
   exact source-file references, one canonical FTS row, old-key navigation,
   absence циклів і donor resurrection. Exception/cancellation до commit
   відкатує весь запис. Після commit replay outbox окремий; читачі не
   залежать від успіху переписування prefs або backend.
6. Повтор того самого operationId і plan повертає AlreadyApplied без
   повторного журналу/перерахунку/сповіщення. Інший payload на той самий ID
   → Refused. Новий ID на вже канонічну пару → Unchanged, без нової події.
   Crash після commit до UI acknowledgement розв'язується через журнал.

Undo — інверсія конкретної локальної операції в Room transaction. Воно
відновлює before-images, donor parents до FK children, old redirects та
derived index. Доступне, коли всі touched after-images досі byte-exact і
немає нових залежностей C/aliases або новішого злиття/особистої корекції.
Повторно перевіряються writer gates й fingerprints. Якщо після merge
слухач створив нові пов'язані rows, безпечний результат `UndoNeedsDecision`
із переліком, а не повний restore старої DB поверх нового прогресу.
Незмінений progress після merge не потребує restore; нові тики не стираються.
Remote writes не «відкочуються» локальним SQL: у Work-only шляху старі shared
документи не видаляються; для окремого person repair Undo має компенсаційний
outbox і чесний pending/rejected стан. Revert APK не замінює Undo даних;
rollback binary після schema-change потребує сумісного reader або backup
із підтвердженням, ніколи destructive downgrade.

## Приклади конфліктів

**Роздвоєний Work, різні начитки.** A=`імx27я тіні|айя нея`,
B=`імя тіні|айя нея`; слухач підтверджує K=B. A має bookId a1,
Edition ea з 8 розділами, uk, наратор N1, chapter 5 / 70 s.
B має b1, eb з 11 розділами, en, N2, chapter 2 / 360 s.
Після merge entries a1/b1.workId=B; ea.workId=a1, eb.workId=b1;
обидва progress rows дослівно збережені. Дані не переводяться між
розділами або мовами. Один Work у списку, дві досяжні начитки в деталях.
Bookmark на a1/ea відкриває саме ea; завантажений трек a1 лишається на диску.

**Два старі ID тієї самої описаної начитки.** ea і eb мають того самого
наратора і 8 розділів; ea завершена, eb — повторне слухання на chapter 1.
Manual Work merge зберігає обидві Edition і обидва стани. Навіть більший
lastListenedAt eb не скасовує завершення ea. Re-import exact source ea
повертається до ea; опис без proven source identity просить вибір.

**Жанрові документи та серія.** A/sourceX має ENUMERATION t=10 із двома
жанрами, B/sourceX — SEARCH t=20 з одним. На C лишається весь enumeration
документ; search payload лишається в історії. A в series S має position=2,
B — 3; без підтвердженого position apply повертає NeedsDecision.

**Person bookmark після декодування.** Автор старого encoded ID має
notify=false updatedAt=20, чистого — true updatedAt=10. Окремий погоджений
repair обирає false, createdAt=min, lastSeenAt=max; не поєднує чужі
notification time/count. Work-only merge цих двох bookmarks не міняє.

**Видалення і crash.** Після preview sync приносить tombstone A.
Повторний preflight дає Stale, C не записаний. Crash після APPLIED commit
і до відповіді UI не повторює перенесення: retry operationId повертає
AlreadyApplied. Новий Readthrough після merge блокує сліпий Undo,
але збереження обох старих progress rows від цього не змінюється.

## Перевірки наступного implementation scope

Це заплановані acceptance tests, не звіт про вже виконані тести.
D1 не запускає Gradle й не стверджує придатність production-міграції.

- Реальна Room v54 fixture із **кожною з 45 таблиць**, усі relevant
  composite-PK collisions, перевірка raw before/after sets і no-loss полів.
  Schema upgrade перевіряє тільки нові носії, merge — окрема user operation.
- Foreign keys ON, UPDATE parent без cascade loss; explicit orphan queries
  всіх bookId/Edition/source/Work carriers. Обидва типи editions.workId,
  ID який одночасно є bookId і Work.id, corrupted ambiguous/null bindings.
- Два narrator/language/topology, одна narrator з двома старими Edition,
  completed+relisten, два speed/pause states, chapter reorder + bookmarks.
- **Manual merge → restart → startup merger** на реальній Room fixture:
  два різні bookId/Edition ID з однаковими narrator/language, exact source
  URL, назвами й тривалостями chapters, без paths/hash/downloads, але з
  двома різними progress і bookmarks. Apply переприв'язує обидва entries
  до C; database close/reopen, потім реальний `DuplicateWorkMerger.mergeOnce`
  зі startup transaction seam. Він видаляє 0 цих participants: exact
  ID-sets і rows `audiobooks`, `library_entries`, `editions`,
  `playback_progress`, `bookmarks`, `sources`, `source_tracks`, `chapters`
  лишаються такими самими, як після apply. Повторити після Undo й повторного
  startup: restored Work rows/redirects відповідають before-images, обидві
  позиції й захист лишаються. Окремо race: auto grouping до manual commit,
  auto transaction після commit все одно бачить exclusion. Control fixture
  непричетних доведених SEO duplicates досі зливається штатно.
- Real filesystem fixture: PAUSED/partial/stale flags/download hashes,
  two SAF trees, renamed folders, original order/lineage byte-exact;
  active-job race блокує apply. Жоден file operation не виконується merge.
- Key/redirect replay через **кожні** catalog, ingest, search, import,
  tracked write/sync doors; refresh/restart не воскресають donor;
  old navigation/collection/bookmark/people/notification залишаються дієвими.
- Genre rank/time/tie collisions, series position/cover/FIELD conflicts,
  person roles й canonical IDs; inference не замінює явні рішення.
- Fault injection після кожного кроку transaction; retry після commit,
  conflicting operationId, cancellation, new dependency; Undo без змін,
  UndoNeedsDecision після нових rows і повторний Undo.
- Emulator/adapter acceptance для aliases review/rating/relationship,
  offline cache, pending/rejected/acknowledged own writes; confirmed
  aggregate без double-count uid, без присвоєння права на чужі документи.
- Якщо окремо погоджено name repair: full closure atomicity, bookmark
  collision/deletion tombstones, notification silence, rejected outbox,
  дві linked installations. Repair чотирьох display fields без цього не
  закриває #968.

Завершення реалізації доводиться однією Work-карткою **та** досяжністю
кожної збереженої начитки/позиції/закладки/файла після restart/rescan/sync.
Зелений вузький merger-test не замінює цей обсяг.

## Уточнення поточного контракту після main acdf0758

Початкова база вище — історичний D1 snapshot. Повторна перевірка
`acdf075839e3d4d6bff3c3abf76ea244161e0d25` бачить runtime Room v53
і ті самі 43 таблиці. `MIGRATION_52_53` змінює жанрові дані, а не структуру:
нормалізує словник та посилання `genre_assertions`/`work_genres` за rawText.
Наступний merge перевіряє актуальні genre IDs; старі hashed IDs не стають
окремим жанром. Raw джерельні свідчення не переписуються.
У tracked `53.json` внутрішній `database.version` досі дорівнює 52;
його entities збігаються з `52.json`. Це окрема розбіжність export metadata,
не доказ нової таблиці або нового runtime тесту. D1 її не виправляє.
На цій історичній базі матриця 43 таблиць була структурно повною.
Поточні fixtures мають спиратися на v54 та ланцюг 52→53→54, як нижче.

Новий чинний стан `ReadingState.ABANDONED` належить одному Readthrough.
При Work-only merge змінюється лише його бібліографічний workId; id,
libraryEntryId, editionId, state, дати, unitValue і raw journalJson зберігаються.
Два проходи не отримують спільного state через одну Work-картку.
`AbandonedBooks` шукає audio pass за незмінним bookId/libraryEntryId;
створює відсутній pass як `rt-audio-<bookId>`, але вміє знайти наявний pass
з іншим id. Merge не створює другого pass і не змінює цей вибір.
DAO Flow повертає libraryEntryId всіх AUDIO/ABANDONED rows; бейдж і дія
скасування лишаються на відповідній начитці через цей незмінний ключ.
### Completion після main ec19e50 (#1191)

У попередньому зрізі `acdf0758` завершення лише ховало бейдж/дію,
а stored pass лишався ABANDONED. Це історичний стан, не поведінка всіх
чинних completion paths.

Після #1191 у main `ec19e50` локальне завершення має окремий writer:
`AbandonedBooks.finish` знаходить AUDIO/ABANDONED pass і через
`ReadthroughPolicy.finish` записує FINISHED та finishedAt. Player end-of-book
перед цим записом зберігає факт FINISHED_AFTER_ABANDON через achievement store;
ручне «Прослухано» викликає той самий finish без цього факту. Після успішного
запису pass видаляється його AbandonUndo note. Merge та ordinary import/metadata
refresh не викликають finish і не відтворюють completion чи reward.

`observeLiveAbandonedBookIds` фільтрує stored marks за спільним completion
verdict. Завершення, отримане з іншого пристрою, або невдалий локальний finish
можуть залишити raw ABANDONED pass, хоча бейдж і дія вже приховані. Merge
зберігає фактичний raw state; прихований бейдж не є доказом записаного FINISHED.

`SharedPreferencesAbandonUndo` — ще один зовнішній носій: private prefs
`abandon_undo`, ключ `before:<bookId>`, raw value `0|` для створеного mark
pass або `1|<ReadingState.name>` для попереднього pass. До closure належать
і відсутність ключа, і точні raw bytes; merge не rekey, не consume, не
переобчислює нотатку через Work resolver. Чинний remember робить `.apply()`
до Room persist і повторний abandon не перезаписує першу нотатку; cancel
recall/forget робить перед delete/restore pass. Finish, навпаки, викликає
forget після успішного persist FINISHED. Player fact capture, запис pass і
прибирання prefs note виконуються послідовно; спільна транзакція між ними
не заявляється. Це не атомарна Room/prefs операція і не cross-device контракт.

Перед merge та Undo перераховуються всі фактичні external closure fingerprints.
Abandon/cancel і обидва локальні completion writer paths мають бути включені
до спільного participant write gate наступної реалізації, як інші writer дії;
його наявність сьогодні не стверджується. Closure охоплює raw pass, AbandonUndo
note та чинні achievement facts; merge/Undo не відтворюють і не відкочують
факт завершення, записаний після preview або merge.
Зміна raw нотатки, state або видалення pass після preview дає Stale;
після merge блокує сліпий Undo як UndoNeedsDecision. Undo не відновлює
спожиту нотатку і не воскресає pass, який слухач свідомо прибрав cancel.
Room commit не викликає prefs `.apply()` і не компенсує cancel старим snapshot.

До запланованих acceptance checks додати actual Room v54 + справжні private
prefs: PLANNED→ABANDONED→merge→cancel повертає PLANNED; mark-created pass
після merge/cancel зникає без phantom «Мій рік»; існуючий нестандартний pass id,
два окремі abandoned passes та restart зберігаються. Повторний abandon не
замінює before-note. Cancel до побудови preview входить до нового snapshot і сам по собі
не є stale. Cancel між preview та apply дає Stale; cancel після merge
перед Undo дає UndoNeedsDecision без втрати нової дії. Окремо заплановано:
локальний finish після merge зберігає FINISHED/finishedAt і прибирає note;
player capture перед persist зберігається, а ручний finish не створює цього
факту. Finish між preview та apply дає Stale, після merge — UndoNeedsDecision,
без воскресіння ABANDONED або спожитої note. Для sync completion і невдалого
локального writer badge-hidden case зберігає фактичний raw ABANDONED; його не
ремонтує merge. Ці перевірки заплановані, не виконані в D1. Три рішення
власника нижче не змінюються;
production/schema/name repair та D2 не запускаються цим уточненням.


## Уточнення після main 6961dd80: виміри v54

PR #1180 злитий; `MIGRATION_53_54` додає три NOT NULL колонки з default 0
до `listening_stats`, а також `playback_sessions` і `achievement_counters`.
Export [54.json](../../app/schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase/54.json)
має database.version=54 і 45 entities. Інші 42 таблиці не змінили структури
порівняно з v52; нові дві таблиці не мають оголошених FK або Work-якорів.
Історичні v52/v53 позначення вище не задають номер майбутньої міграції.

`PlaybackSessionEntity` зберігає тільки id, початок/останній спостережений
кінець і verified/offline/cast мілісекунди. Тимчасовий `OpenSession.bookId`
належить живому записувачу `ListeningStateStore`, а не durable схемі.
Збережені bookId під час Work-only merge незмінні, тому новий resolver
не переприв'язує цей recorder і не викликає Stopped/Played або повторний tick.
Чинна заборона apply під час активного playback учасника лишається.
Виміри й лічильники не стають before-images для відновлення старих глобальних
значень: ні merge, ні Undo не відкочують нові спостереження слухача.

До запланованого приймання додати непорожні реальні rows обох нових таблиць
і всіх шести колонок listening_stats. На спокійній fixture exact ID-sets і
raw fields лишаються однаковими після apply, close/reopen, retry, startup
merge та Undo. Після merge записати новий verified tick та приріст реального
лічильника чинними writer-ами; Undo не зменшує й не повертає ці виміри.
Обидві старі позиції/Edition зберігаються незалежно від сеансів; зв'язок
сеансу з книгою не вигадується. Відмова active playback не пише журнал
злиття або нові виміри. Upgrade 52→53→54 зберігає старі stats і жанрові
свідчення, нові колонки стартують із нуля, нові таблиці порожні; наступна
міграція додає тільки погоджені носії manual merge без повтору #1180.
Це майбутні перевірки, не виконані runtime-докази D1.

Контракт v54 потребує нового review та CI після синхронізації гілки з main.
CI попереднього HEAD `0f4395be` доводить тільки попередній документаційний
кандидат. Три рішення власника нижче, локальна межа першого злиття й
окремий person repair не змінені; D2 цим уточненням не запускається.

## Три рішення власника перед наступним кодом

1. **Правило #968:** прийняти ручний Work-only merge з незмінними
   Edition/Source/bookId, локальними redirects і durable participant
   exclusions від startup merge, чинними також після Undo; кандидат не стає
   автозгодою.
   Рекомендація — так. Production repair і майбутній merge Edition лишаються
   окремими дорученнями.
2. **Особисті та shared конфлікти:** прийняти явний вибір несумісних
   бібліографічних/серійних/FIELD значень і наведене confirmed-review
   дедуп правило; активні jobs та tombstones спершу блокують apply.
   Рекомендація — так. Це дозволяє implementation без вигадування winner.
3. **Ремонт encoded імен:** прийняти окремий атомарний person repair із
   повним closure/aliases/outbox після Work resolver; до його перевірки
   припарковану display-only гілку залишити зупиненою. Рекомендація — так.
   Cross-device передачу ручних Work redirects погодити окремо; перший
   scope чесно обмежений локальним об'єднанням.

Конкретні title/author/серія/обкладинка пари й відповідь «ці люди одна
person» обираються в runtime preview. Це дані слухача, а не нові архітектурні
питання для кожного виконавця. До трьох рішень вище #968 лишається відкритим.
