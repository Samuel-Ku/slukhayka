# План: #1673 — заголовки «Люди»/«Закладки» у «Збереженому» читні в ink-темі

QA на пристрої (main, Ink): у «Мої книги → Збережене» секційні заголовки
«Люди» і «Закладки» — темно-сірі на майже чорному, практично не читаються
(у Paper — гаразд).

## Причина

Обидва `Text` мали `style = MaterialTheme.typography.titleSmall` і **не
задавали `color`**, тож брали `LocalContentColor`, який ink-тема лишає темним
на власній майже чорній поверхні. Решта рядків екрана кольори задають явно.

## Рішення

1. Спільний `LibrarySavedSectionHeader(text, testTag)` з
   `color = MaterialTheme.colorScheme.onSurface` — та сама роль, що решта
   екрана задає руками; обидва виклики («Люди», «Закладки») переходять на
   нього. Жодного зміненого тексту/відступів: padding 16/8 і testTag
   збережені, тож семантичні тести й еталони не мають змінитися.
2. Новий `LibrarySavedHeaderContrastTest` (pure JVM):
   - source-guard: `typography.titleSmall` у тілі хелпера рівно один, і він
     має `color = MaterialTheme.colorScheme.onSurface`; обидва testTag-и
     рендеряться через хелпер (2 call-sites, декларація не рахується); у
     файлі є й інші `titleSmall` (решта екрана) — їх фікс не чіпає;
   - theme-contrast: `onSurface` на `background` і `surface` обох схем
     (ink `paperInkDarkScheme()` і paper `paperInkLightScheme()`) проходить
     текстову планку 4.5:1 через `ColorContrast.meetsTextFloor`.

## Перевірка

- `:app:testPureJvm -Ptest.selectedClasses=…LibrarySavedHeaderContrastTest`
  (dragonborn/hub за build-slot).
- CI: повний гейт (зміни в main/ тим самим зачіпають Android-ноги; Room-
  ноги довгі — норма).
- Еталони Roborazzi: очікувано без змін (текст того самого стилю/розміру;
  ink-кадрів «Збереженого» в наборі немає — це названа межа: візуальний
  еталон не додається, бо панель вбудована в `LibraryScreen` без
  ін'єкційного шва, а колір піниться source-guard-ом + contrast-тестом).

## Межі

- Source-guard — це захист від повернення «голого» Text, а не рендер-тест
  кольору: Compose не віддає колір тексту через семантику, а Robolectric-
  скріншотів цей репозиторій не використовує (тільки Roborazzi-еталони).
- Якщо QA захоче інший тон (не onSurface), це зміна ролі, а не цього фікса.
