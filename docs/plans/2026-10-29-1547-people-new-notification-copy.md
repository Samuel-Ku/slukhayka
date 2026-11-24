# План: #1547 — текст сповіщення «Новинки від людей» з ресурсів, множина чесна

Сповіщення `PeopleNewArrivalWorker` збирало ВЕСЬ текст у коді:
`"${count} нові книги"`, `"Від $people"`, `"Переглянути"`, `"Новинки від людей"`,
`"ваших авторів"`, `joinToString(" та ")`. Наслідки: «1 нові книги» для однієї
книги; EN/PL-інтерфейс отримує українське сповіщення (ADR-0033); назва каналу
теж українська незалежно від мови.

## Рішення

1. **Новий `PeopleNewArrivalNotificationText`** — уся копія сповіщення як дані:
   `title` (`plurals people_new_notification_title` — uk one/few/many/other,
   en one/other, pl one/few/many/other), `body` (`people_new_notification_from`
   + `people_new_notification_two_people` для двох імен +
   `people_new_notification_some_people` як запасний), `action`
   (`people_new_notification_action`), `channelName`
   (`people_new_notification_channel_name`).
2. **Worker** бере все з `copy`, у файлі не лишається жодного
   користувацького рядка; логіка (id, канал, EXTRA_* переходу на сторінку
   людини, `markNotified`) не змінена.
3. **Рядки** в `values/` · `values-en/` · `values-pl/` (uk — досі ті самі слова,
   але множина виправлена: «1 нова книга» / «3 нові книги» / «5 нових книг»).
4. **Тест** `PeopleNewArrivalNotificationTextTest` (Robolectric): uk 1/3/5/21,
   імена 0/1/2, EN і PL — без кирилиці; мутація «множина завжди one» або
   «прибрати pl-рядок» — червоніє.

## Межі (названі)

- лише сповіщення «Новинки від людей»; Source Watch та інші сповіщення не
  чіпаємо;
- назва вже створеного каналу на старих інсталяціях не перейменовується
  (Android так не робить): нові інсталяції отримують локалізовану назву;
- EN/PL тексти перекладені в межах цього PR (ADR-0033), польська кампа­нія
  інших екранів — окремо.

## Перевірка

- `:app:testRoomRobolectric -Ptest.selectedClasses=…PeopleNewArrivalNotificationTextTest`
  — зелено; наявний `PersonNewArrivalsTest` (логіка рішення) без змін;
- мутації: (а) `getQuantityString(…, 1, count)` замість `count` → uk
  «3 нові книги»/«5 нових книг» червоні; (б) прибрати `few`/`many` з pl →
  pl-тест червоніє; (в) повернути хардкод у worker — статичний тест копії не
  ловить, тому мутація (в) названа межею (worker-дріт покривається лише
  компіляцією та існуючим `PersonNewArrivalsTest`; винесення копії — і є
  зменшення межі).
