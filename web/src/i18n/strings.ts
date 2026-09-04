/**
 * spec-45 (#405) T14 (#502) — the web UI stops hardcoding Ukrainian:
 * every user-visible string lives here in uk/en variants (mirror of the
 * Android `values/` + `values-en/` resource sets). `Strings` is the uk
 * record's type, so the en record is compile-time forced to the SAME key
 * set — key coverage parity is a type error, not a runtime surprise.
 *
 * Interpolation: `translate(locale, 'openBookAria', { title })` replaces
 * `{title}` tokens; a missing param renders the token verbatim (a visible
 * bug, never a crash).
 */

export type UiLocale = 'uk' | 'en'

const uk = {
  // app chrome
  tabListen: 'Слухати',
  tabCatalog: 'Огляд',
  tabProfile: 'Профіль',
  back: '← Назад',
  appSubtitle: 'аудіокниги українською · веб',
  docTitle: 'Слухайка — аудіокниги українською',
  langSwitchAria: 'Перемкнути мову інтерфейсу',
  stubInProgress: '{title} ще в роботі.',
  listenStubTitle: 'Продовження слухання',
  listenStubWhat: 'Оберіть книгу в Огляді й натисніть ▶ на розділі.',
  // profile
  profileStubWhat: 'Профіль з’явиться разом із першим запуском.',
  firebaseNotConfigured: 'Firebase не налаштовано на цьому деплої',
  restoreFailed: 'Не вдалося відновити — перевірте код і з’єднання',
  nickLabel: 'Нік',
  profileLabel: 'Профіль',
  restoreCodeLabel: 'Код відновлення з телефону',
  restoring: 'Відновлюємо…',
  restoreProfile: 'Відновити профіль',
  enterCodeHint: 'Введіть код з ⚙️ Профіль на телефоні, щоб побачити свій нік і відгуки тут.',
  boundHint: 'Профіль прив’язано — ваш нік і відгуки тепер тут.',
  syncTitle: 'Синхронізація прогресу',
  syncDescription: 'Коли ввімкнено, позиція дзеркалиться між телефоном і браузером (останній запис виграє). Вимкніть — дзеркалення зупиниться одразу, локальний прогрес лишиться.',
  unboundHint: 'Поки профіль не прив’язано, браузер не надсилає нічого особистого — прогрес лишається лише тут.',
  // catalog
  searchPlaceholder: 'Пошук…',
  allSources: 'Усі джерела',
  searching: 'Шукаємо…',
  nothingFound: 'Нічого не знайшли.',
  sourceFailed: 'Джерело не відповіло спробуйте пізніше.',
  loadingCatalog: 'Завантажуємо об’єднаний каталог…',
  cachedCatalogNotice: 'Показуємо останній збережений каталог{date}. Оновлення тимчасово недоступне.',
  appendFailed: 'Не вдалося оновити каталог. Уже завантажені книги залишилися тут.',
  loading: 'Завантажуємо…',
  retry: 'Спробувати ще раз',
  showMore: 'Показати більше',
  narrationSelect: 'Начитка',
  chooseNarrationAria: 'Обрати начитку: {title}',
  unknownNarrator: 'Невідомий диктор',
  languageFilterLabel: 'Мова:',
  all: 'Усі',
  openBookAria: 'Відкрити книгу: {title}',
  listenAria: 'Слухати: {title}',
  cancelCheckAria: 'Скасувати перевірку: {title}',
  cancel: 'Скасувати',
  checking: 'Перевіряємо доступність…',
  noNetwork: 'Немає мережі. Перевірте з’єднання та повторіть.',
  temporaryFailure: 'Джерело тимчасово не відповідає. Спробуйте пізніше.',
  audioMissing: 'Джерело не віддає аудіо для цієї книги.',
  sessionRequired: 'Це джерело потребує сесії на своєму сайті.',
  openSourceLabel: 'Відкрити {label}',
  // book page
  bookFailed: 'Книга недоступна — джерело не відповіло.',
  loadingBook: 'Завантажуємо книгу…',
  cachedBookNotice: 'Показуємо збережені дані книги. Оновлення каталогу тимчасово недоступне.',
  sessionCheckHint: 'Щоб перевірити доступ до аудіо, відкрийте джерело у браузері й пройдіть перевірку, якщо воно її попросить.',
  openSourcePage: 'Відкрити сторінку джерела',
  readBy: ' · читає {narrator}',
  chapters: 'Розділи',
  noChapters: 'Розділів не знайшли — джерело не віддає аудіо для цієї книги.',
  listenChapterAria: 'Слухати розділ {n}',
  otherNarrations: 'Інші начитки',
  // player
  miniChapter: 'Розділ {n} · {time}',
  close: '✕ Закрити',
  completed: 'Завершено',
  chapter: 'Розділ {n}',
  position: 'Позиція: {time}',
  prev: '‹ Попередній',
  next: 'Наступний ›',
  speed: 'Швидкість:',
  bookUnavailable: 'Книга недоступна',
}

export type Strings = typeof uk

const en: Strings = {
  // app chrome
  tabListen: 'Listen',
  tabCatalog: 'Catalog',
  tabProfile: 'Profile',
  back: '← Back',
  appSubtitle: 'audiobooks in Ukrainian · web',
  docTitle: 'Слухайка — audiobooks in Ukrainian',
  langSwitchAria: 'Switch interface language',
  stubInProgress: '{title} is still a work in progress.',
  listenStubTitle: 'Continue listening',
  listenStubWhat: 'Pick a book in Catalog and press ▶ on a chapter.',
  // profile
  profileStubWhat: 'Profile will appear after the first launch.',
  firebaseNotConfigured: 'Firebase is not configured on this deployment',
  restoreFailed: 'Could not restore — check the code and your connection',
  nickLabel: 'Nickname',
  profileLabel: 'Profile',
  restoreCodeLabel: 'Recovery code from your phone',
  restoring: 'Restoring…',
  restoreProfile: 'Restore profile',
  enterCodeHint: 'Enter the code from ⚙️ Profile on your phone to see your nickname and reviews here.',
  boundHint: 'Profile bound — your nickname and reviews are here now.',
  syncTitle: 'Progress sync',
  syncDescription: 'When on, position mirrors between phone and browser (the latest write wins). Turn it off and mirroring stops immediately; local progress stays.',
  unboundHint: 'Until the profile is bound, the browser sends nothing personal — progress stays here only.',
  // catalog
  searchPlaceholder: 'Search…',
  allSources: 'All sources',
  searching: 'Searching…',
  nothingFound: 'Nothing found.',
  sourceFailed: 'The source did not answer — try later.',
  loadingCatalog: 'Loading the combined catalog…',
  cachedCatalogNotice: 'Showing the last saved catalog{date}. Updating is temporarily unavailable.',
  appendFailed: 'Could not update the catalog. Already loaded books stay here.',
  loading: 'Loading…',
  retry: 'Try again',
  showMore: 'Show more',
  narrationSelect: 'Narration',
  chooseNarrationAria: 'Choose narration: {title}',
  unknownNarrator: 'Unknown narrator',
  languageFilterLabel: 'Language:',
  all: 'All',
  openBookAria: 'Open book: {title}',
  listenAria: 'Listen: {title}',
  cancelCheckAria: 'Cancel check: {title}',
  cancel: 'Cancel',
  checking: 'Checking availability…',
  noNetwork: 'No network. Check your connection and try again.',
  temporaryFailure: 'The source is temporarily unresponsive. Try later.',
  audioMissing: 'The source serves no audio for this book.',
  sessionRequired: 'This source requires a session on its site.',
  openSourceLabel: 'Open {label}',
  // book page
  bookFailed: 'Book unavailable — the source did not answer.',
  loadingBook: 'Loading the book…',
  cachedBookNotice: 'Showing saved book data. Updating the catalog is temporarily unavailable.',
  sessionCheckHint: 'To check audio access, open the source in your browser and pass its check if it asks.',
  openSourcePage: 'Open the source page',
  readBy: ' · read by {narrator}',
  chapters: 'Chapters',
  noChapters: 'No chapters found — the source serves no audio for this book.',
  listenChapterAria: 'Listen to chapter {n}',
  otherNarrations: 'Other narrations',
  // player
  miniChapter: 'Chapter {n} · {time}',
  close: '✕ Close',
  completed: 'Completed',
  chapter: 'Chapter {n}',
  position: 'Position: {time}',
  prev: '‹ Previous',
  next: 'Next ›',
  speed: 'Speed:',
  bookUnavailable: 'Book unavailable',
}

export const STRINGS: Record<UiLocale, Strings> = { uk, en }

export type StringKey = keyof Strings

/** `translate('uk', 'openBookAria', { title })` — `{token}` interpolation. */
export function translate(
  locale: UiLocale,
  key: StringKey,
  params: Readonly<Record<string, string | number>> = {},
): string {
  let out: string = STRINGS[locale][key]
  for (const [name, value] of Object.entries(params)) {
    out = out.replaceAll(`{${name}}`, String(value))
  }
  return out
}