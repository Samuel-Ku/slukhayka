#!/usr/bin/env python3
"""Число в доку, яке щось стверджує про дерево, мусить виводитися з дерева (#1277).

За серію **сім** чисел у документах виявилися нічим не перевіреними, і кожен раз
це знаходив незалежний огляд, не автор: «хибних відмов на реальній історії не
лишилося» (насправді 3 із 10); «931 шлях у дереві» (858/881/0/970 — жодне не
931); **вигадана команда** `--measure-head` (її не існувало, `git log --all -S`
мав єдине входження — сам рядок документа); «немає мережі» (`ubuntu-latest` має
egress; несла половина — відсутній `GH_TOKEN`); «326 тестів» (насправді 327);
«74.7 MiB» (насправді 76.2, виправлено в #1270); і «The seventh case of "a check
that does not check"». Спільне в усіх: **число в доку, яке ніщо не перевіряє**.
Восьмим випадком став сам цей механізм: його перша версія цитувала
`python3 scripts/guard_witnesses.py --check` — **опції, якої не існує**. Тому
друга половина механізму перевіряє команди, а не лише числа.

Три варіанти, які тікет просив розглянути, і що з них вийшло:

  (а) **лінтер, який вимагає команду біля кожного числа**, — форми немає, і це
      доведено, а не обіцяно: `python3 scripts/doc_numbers.py --audit` міряє
      корпус (`docs/agents/*.md`, `scripts/**/*.py`) і друкує, скільки в ньому
      фігур «число + одиниця» і скільки з них без дати на рядку. Обидва числа
      зараховані в `FIGURES` там, де вони написані
      (`docs/agents/local-environment.md`), а не в цьому файлі: якір мусить
      траплятися у файлі рівно раз, а рядок самого реєстру був би другим
      входженням. Решта фігур — **записи про минуле**: секунди й гігабайти
      конкретної збірки на конкретній машині 2026-10-12, яких жодна команда не
      відтворить на HEAD. Вимога «біля кожного числа — команда» перетворилася б
      на сотні винятків, а виняток — це та сама обіцянка в прозі, тільки довша;
  (б) **заборона числа там, де є команда**, — те, що цей механізм і робить:
      **число, яке щось стверджує про дерево СЬОГОДНІ, або виводиться з запису в
      дереві, або його немає.** Три види виведення, усі — дані:
      `("count", запис)` — число є довжиною реєстру (`CASES`, `GUARDS`…);
      `("position", серія, ключ)` — число є місцем ключа в серії; `("audit", …)` —
      число корпусу, яке міряє сам механізм;
  (в) **пінити відношення замість числа** — названа межа, а не те, що тут
      зроблено: число, яке мусить лишитися записом про минуле, пінить
      відношення, яке переживає дрейф (#1270: `naive > 10 × copied`, а не
      «76.2 MiB»), і міряє його тест, написаний саме для нього.

`FIGURES` — реєстр зарахованих чисел: (файл, якір рядка, джерело). Для кожного:
якір трапляється у файлі **рівно один раз**, рядок несе **рівно одне** число
того джерела, і це число мусить бути серед написань виведеного значення. Для
місця в серії є третя умова, і без неї запис не тримає нічого: **рядок мусить
називати СВІЙ ключ** (`#1270`), а не будь-який інший — інакше «eighth» стояло б
над посиланням на інший випадок, і ніхто б не почервонів.
`SCANNED` — файли, у яких механізм **шукає** числа цих джерел: незараховане
число в них — відмова, а не тиха згода. Тому нове число в доку не проходить
мовчки: воно або виводиться, або його немає.

**Команди.** Число має команду — і команда мусить існувати. Тому механізм читає
код-спани (там живуть команди), знаходить у них згадки `scripts/*.py|sh|mjs` і
перевіряє дві речі: файл є в дереві, і кожна названа опція **оголошена** цим
скриптом (для `.py` — `add_argument` в AST плюс `-h`/`--help`; для `.sh` і
`.mjs` — сама згадка опції у файлі, і це чесно слабший пін, названий тут). Опція
приписується тій команді, яка стоїть **перед** нею в тому самому рядку:
`scripts/mutate.sh --patch … --class …` називає два скрипти й одну команду.
Межа: команда, яка не називає скрипта з `scripts/` (`./gradlew …`, `git …`,
`npm ci`), не перевіряється — її опцій у дереві немає звідки взяти.

Що механізм знайшов на своєму першому прогоні (чим він червоний, а не «працює в
теорії»): `docs/agents/local-environment.md:337` казав «The **seventh** case…»
про випадок, який серія ставить **восьмим**; шапка `guard_witnesses.py` — те
саме як «**Дев'ятий**» і «до цього **вісім** разів» над переліком із семи issue;
її ж «**шість** записів» розходилося з `GUARDS`, де записів було сім; і
`empty_shape_mutants.py:5` «**П'ятий** випадок» — єдине з чотирьох, що було
правдою.

**Серія `CASES`.** У дереві два переліки цього класу, і вони не суперечать один
одному лише тому, що називають один випадок по-різному: `CONTRIBUTING.md`
(перелік п'яти, писаний тоді ж, `#1249`) ставить третім «знахідку огляду
**#1218**» — піни тримали, ЗВІДКИ ширина, і не тримали, ЯКА розкладка — і додає,
що розкладку міряє вже інший інструмент, щуп **#1239**; тіло #1270 ставить
третім **#1239**. #1239 — не випадок класу, а веб-щуп розкладки
(`scripts/web-layout-probe.mjs`, Refs #1224; `work-coordination.md` згадує його
як PR, що злився не тим вмістом). Обидва переліки ставлять #1254 **п'ятим**, і
щоб це було правдою в обох, третій член мусить бути тим самим випадком. Тому
`CASES` тримає його під ключем `#1218` і називає обидва посилання, а рахунок
випадків — **вісім**, не дев'ять: об'єднання переліків як дев'яти рахує один
випадок двічі. Якщо знайдеться справжній дев'ятий — це правка одного рядка
`CASES`, і механізм сам скаже, які числа поїхали.

Межі названі тут, у коді, а не лише в звіті:

  * **механізм бачить ЛИШЕ перелічені форми й файли.** `SCANNED` — це список, і
    число в формі, якої в списку немає, він не побачить: одиниці («1.4 MiB»),
    секунди («662 s»), «шість кіл огляду» лишаються поза ним. Такі числа — або
    запис про минуле (команди для них не існує, і механізм цього не вдає), або
    відношення, піноване тестом, який його міряє (#1270: `naive > 10 × copied`,
    а не «76.2 MiB»). Межа списку — в самому списку;
  * **зарахування числа — руками.** Жодне статичне правило не відрізняє «число,
    яке щось стверджує про дерево» від «числа в прикладі команди»; тому реєстр
    явний, і питання огляду звужується до одного — «де його запис?». Старі числа
    заднім числом не зараховуються: механізм каже, скільки він покриває, а не
    скільки мав би;
  * **перелік — не істина, і ключ випадку не виведено.** `CASES` зібраний з двох
    переліків, які є в дереві, і те, чому третій член — `#1218`, а не `#1239`,
    написано прозою вище, а не виведено з коду: механізм тримає лише те, що
    число в доку збігається з переліком, і робить розходження видимим. Повнота
    переліку — робота огляду;
  * **написання — таблиця до дванадцяти.** `CARDINALS`/`ORDINALS` знають
    «один..дванадцять» і «перший..дванадцятий» двома мовами, цифрові форми
    (`8th`, `8-й`) теж. Число, написане словом поза таблицею («тринадцять
    записів»), — **невідомість (2)**, а не тиша: `BEYOND` називає такі слова, щоб
    обіцянка не була сильнішою за код. Слово, якого немає й у `BEYOND`, не
    знаходиться взагалі — і це межа списку, а не обіцянка його розширити;
  * **`запис` — двозначне слово.** В `guard_witnesses.py` «записів» — це
    `GUARDS`, у `lint_baseline.py` — рядки базлайну; тому числівники прив'язані
    до джерел явно, а не вгадуються за словом;
  * **механізм читає прозу, не цитати.** Число в код-спані (`` `…` ``) або в
    блоці (```…```) — це те, що документ НАВОДИТЬ: команда, шлях, літерал,
    уривок зі старої шапки. Правило машинне (бектики видно), і воно потрібне,
    бо сама розповідь про цей клас цитує старі числа; твердження, сховане в
    бектики, побачить око оглядача, а не regex — і це названа межа, не діра,
    яку тут замовчують. Команди ж механізм читає САМЕ з код-спанів — там вони й
    живуть;
  * **опція команди в shell-скрипті піниться слабше, ніж у Python.** Для `.sh` і
    `.mjs` «оголошена» означає «згадана у файлі»: скрипт без argparse не має
    реєстру опцій, і вигадана опція, згадана в його ж `usage`, пройшла б. Це
    названа межа; для `.py` опція мусить бути в `add_argument`.

Коди виходу: 0 — кожне зараховане число виводиться, кожна названа команда існує
й оголошує свої опції, і жодного незарахованого числа в перелічених файлах
немає; 1 — знахідка (число не виводиться, його не зараховано, команди немає,
опції немає); 2 — перевірити не вдалося (файлу немає, якір не один, рядок не несе
числа, рядок не називає свого ключа, ключа немає в серії, число понад таблицю
написань, скрипт не розібрано, джерела немає).
"""

from __future__ import annotations

import argparse
import ast
import functools
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class NumberError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «число відтворено»."""


# --------------------------------------------------------------------------
# Дані: серія випадків класу «перевірка, яка не перевіряє»
# --------------------------------------------------------------------------
#
# Формат: (issue, чим випадок виглядав як перевірка). Порядок — це і є серія;
# `position` виводить місце ключа звідси. Сім перших названі двома переліками в
# дереві — `CONTRIBUTING.md` (п'ять, писаний тоді ж) і тіло #1270 (сім issue) —
# і восьмий це сам сторож, який ті переліки називає. Третій член у них різний:
# `CONTRIBUTING.md` каже «знахідка огляду #1218… розкладку міряє вже інший
# інструмент — щуп #1239», тіло #1270 — «#1239». Це один випадок: #1239 — не
# випадок класу, а веб-щуп розкладки (`scripts/web-layout-probe.mjs`), і обидва
# переліки ставлять #1254 п'ятим. Тому ключ тут — `#1218`, а не два члени.

CASES = (
    ("#1233", "мертвий парсер `check_runs`"),
    ("#1231", "незаякорений regex"),
    ("#1218", "піни на provenance — піни тримали, ЗВІДКИ ширина, і не тримали, "
              "ЯКА розкладка; тіло #1270 називає цей випадок `#1239`, але #1239 — "
              "щуп розкладки (`web-layout-probe.mjs`), не випадок"),
    ("#1246", "невинний блок убивства процесу"),
    ("#1254", "тест, що пінив ЛІТЕРАЛ (`empty_shape_mutants.py`, #1256)"),
    ("#1257", "`errors`"),
    ("#1265", "маркер модуля → `runs=False` → `witness=` → `own_poison` → "
              "`shape_pin` → множина свідків"),
    ("#1270", "сам сторож: `test_measure_head_exists_because_the_docs_cite_it`"),
)


# --------------------------------------------------------------------------
# Дані: числа, які механізм уміє вивести
# --------------------------------------------------------------------------
#
# Формат: (файл відносно кореня, якір рядка, джерело). Джерело — або
# ("count", запис), або ("position", серія, ключ). Якір мусить траплятися у
# файлі рівно один раз: рядок, який він називає, і є тим місцем, де число
# мусить виводитися.

FIGURES = (
    # Сьомий випадок тікета: «seventh» у доку проти восьмого місця `#1270`.
    ("docs/agents/local-environment.md",
     "gate is about the guards themselves",
     ("position", "cases", "#1270")),
    # Те саме число в шапці механізму, який цей клас і називає.
    ("scripts/guard_witnesses.py",
     "випадок класу",
     ("position", "cases", "#1270")),
    # Контроль: п'яте місце `#1254` — єдине число з чотирьох, що було правдою.
    ("scripts/empty_shape_mutants.py",
     "випадок за дві цілі",
     ("position", "cases", "#1254")),
    # Ціна реєстру: скільки в ньому записів, виводить сам реєстр.
    ("scripts/guard_witnesses.py",
     "не стає галасливим",
     ("count", "guards")),
    # Числа, якими відкинуто варіант (а), живуть у доку, який їх наводить:
    # у файлі самого реєстру якір не міг би траплятися рівно раз.
    ("docs/agents/local-environment.md",
     "figures of",
     ("audit", "units")),
    ("docs/agents/local-environment.md",
     "sit on a line",
     ("audit", "undated")),
)

# Корпус, який міряє `--audit`: документи для агентів і всі скрипти.
AUDIT_FILES = (("docs/agents", "*.md"), ("scripts", "**/*.py"))

# Фігура «число + одиниця» — те, чого вимагати команду біля кожного не можна.
UNITS = re.compile(
    r"\b\d+(?:[.,]\d+)?\s*(?:MiB|KiB|GiB|MB|GB|KB|TB|ms|s|%|dp|px)\b|\b\d+\s*×"
)
DATED = re.compile(r"\b20\d\d-\d\d-\d\d\b|\b[0-9a-f]{7,40}\b")

# Файли, у яких механізм ШУКАЄ числа перелічених джерел. Незараховане число в
# них — відмова. Кого тут немає — того механізм не бачить.
SCANNED = (
    "docs/agents/local-environment.md",
    "scripts/guard_witnesses.py",
    "scripts/empty_shape_mutants.py",
    "scripts/verdict_field_mutants.py",
    "scripts/declared_fields.py",
)

# Формат: (джерело, написання для місця в серії, написання для кількості).
# Числівник прив'язаний до джерела явно: те саме слово в іншому файлі означає
# інший запис (`записів` у `lint_baseline.py` — рядки базлайну, не `GUARDS`).
# Кількість міряють формою множини («шість записів», «6 guards»): «один запис» —
# це вказівний зворот, а не число, і формою кількості він не ловиться.
NOUNS = (
    ("cases", ("case", "випадок"), ("cases", "випадки", "випадків")),
    ("guards", (), ("guards", "записи", "записів", "сторожів")),
)

CARDINALS = (
    ("one", "один"), ("two", "два"), ("three", "три"), ("four", "чотири"),
    ("five", "п'ять"), ("six", "шість"), ("seven", "сім"), ("eight", "вісім"),
    ("nine", "дев'ять"), ("ten", "десять"), ("eleven", "одинадцять"),
    ("twelve", "дванадцять"),
)

ORDINALS = (
    ("first", "перший"), ("second", "другий"), ("third", "третій"),
    ("fourth", "четвертий"), ("fifth", "п'ятий"), ("sixth", "шостий"),
    ("seventh", "сьомий"), ("eighth", "восьмий"), ("ninth", "дев'ятий"),
    ("tenth", "десятий"), ("eleventh", "одинадцятий"), ("twelfth", "дванадцятий"),
)

# Числа-слова, які таблиця назвати не вміє (понад дванадцять і круглі десятки).
# Вони не «не знайшлися» — вони **невідомість (2)**: механізм бачить число, але
# не має чим його ні вивести, ні порівняти. Без цього списку «тринадцять
# записів» проходило б тихо, і обіцянка в шапці була б сильнішою за код.
BEYOND = (
    "тринадцять", "чотирнадцять", "п'ятнадцять", "шістнадцять", "сімнадцять",
    "вісімнадцять", "дев'ятнадцять", "двадцять", "тридцять", "сорок", "п'ятдесят",
    "шістдесят", "сімдесят", "вісімдесят", "дев'яносто", "сто",
    "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
    "eighty", "ninety", "hundred",
)

APOSTROPHES = str.maketrans({"\u2019": "'", "\u02bc": "'", "\u0060": "'", "\u00b4": "'"})

# Код-спани (`` `…` ``) і блоки (```…```) — цитати, не твердження: у них живуть
# команди, шляхи, літерали й рядки, які документ НАВОДИТЬ. Механізм читає прозу.
FENCED = re.compile(r"```.*?```", re.DOTALL)
# Спан може бути в подвійних бектиках (``…``) — тоді вміст сміє містити бектик.
INLINE = re.compile(r"(`+)(?:[^`]|(?!\1)`)*?\1")


def prose(text: str) -> str:
    """Текст без код-спанів: порожнє місце тієї ж довжини, рядки ті самі."""
    def blank(match: "re.Match") -> str:
        return "".join("\n" if character == "\n" else " " for character in match.group(0))

    return INLINE.sub(blank, FENCED.sub(blank, text))


# --------------------------------------------------------------------------
# Записи, з яких числа виводяться
# --------------------------------------------------------------------------


def guards() -> tuple:
    """`GUARDS` із `guard_witnesses.py` — реєстр сторожів і мутацій."""
    scripts = str(ROOT / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    import guard_witnesses

    return guard_witnesses.GUARDS


def record(name: str) -> tuple:
    """Реєстр, довжину якого виводить `("count", name)`."""
    if name == "cases":
        return CASES
    if name == "guards":
        return guards()
    raise NumberError("невідомий запис: %s" % name)


def keys(name: str) -> tuple:
    """Ключі серії, місце яких виводить `("position", name, key)`."""
    if name == "cases":
        return tuple(issue for issue, _ in CASES)
    raise NumberError("невідома серія: %s" % name)


def derive(source: tuple) -> int:
    """Значення, яке число в доку мусить мати."""
    if source[0] == "count":
        return len(record(source[1]))
    if source[0] == "position":
        series, key = source[1], source[2]
        place = keys(series)
        if key not in place:
            raise NumberError("ключа %s немає в серії %s" % (key, series))
        return place.index(key) + 1
    if source[0] == "audit":
        units, undated = audit_counts()
        if source[1] == "units":
            return units
        if source[1] == "undated":
            return undated
        raise NumberError("невідомий аудит: %s" % source[1])
    raise NumberError("невідоме джерело: %r" % (source,))


@functools.lru_cache(maxsize=None)
def audit_counts() -> tuple[int, int]:
    """Скільки фігур «число + одиниця» в корпусі й скільки з них без дати поруч.

    Це число міряє сам механізм, і саме тому воно має команду:
    `python3 scripts/doc_numbers.py --audit`. Кеш — бо `check` питає його на
    кожен прогін, а корпус не змінюється посеред прогону.
    """
    units = undated = 0
    for directory, pattern in AUDIT_FILES:
        for path in sorted((ROOT / directory).glob(pattern)):
            if not path.is_file():
                continue
            for line in path.read_text(encoding="utf-8").splitlines():
                found = UNITS.findall(line)
                units += len(found)
                if found and not DATED.search(line):
                    undated += len(found)
    return units, undated


def spelled(value: int, kind: str) -> str:
    """Слово, яким число цього виду пишуть у прозі (англійською — як у доку)."""
    words = ORDINALS if kind == "position" else CARDINALS
    return words[value - 1][0] if 1 <= value <= len(words) else str(value)


def spellings(source: tuple) -> tuple[str, ...]:
    """Усі написання виведеного значення, які механізм приймає."""
    value = derive(source)
    words = ORDINALS if source[0] == "position" else CARDINALS
    spelled_here = words[value - 1] if value <= len(words) else (str(value),)
    digits = (str(value), "%dth" % value, "%d-й" % value, "%d-ий" % value)
    return tuple(normalise(word) for word in tuple(spelled_here) + digits)


def normalise(word: str) -> str:
    """Слово до порівняння: нижній регістр, один апостроф, без крапок і ком."""
    return word.translate(APOSTROPHES).strip(" .,;:()«»\"'").lower()


# --------------------------------------------------------------------------
# Числа в тексте: де вони і які
# --------------------------------------------------------------------------


def _alternation(words) -> str:
    return "|".join(re.escape(word) for word in sorted(words, key=len, reverse=True))


# Цифрові форми того самого числа: `8th case`, `8-й випадок`. Друковане число —
# теж число, і «The 8th case of» мусить проходити, а не падати в невідомість.
DIGITS = r"\d+(?:-?(?:th|st|nd|rd|й|ий|ій))?"


def patterns() -> tuple[tuple[str, re.Pattern], ...]:
    """Три форми числа: місце (`seventh case`), кількість (6 guards), понад таблицю."""
    ordinals = _alternation(word for pair in ORDINALS for word in pair)
    cardinals = _alternation(word for pair in CARDINALS for word in pair)
    beyond = _alternation(BEYOND)
    places = _alternation(word for _, place, _ in NOUNS for word in place)
    counts = _alternation(word for _, _, count in NOUNS for word in count)
    shapes = []
    if places:
        shapes.append((
            "position",
            re.compile(
                r"\b(?P<word>%s|%s)\b[^\n.;:!?]{0,24}?\b(?P<noun>%s)\b"
                % (ordinals, DIGITS, places),
                re.IGNORECASE,
            ),
        ))
    if counts:
        shapes.append((
            "count",
            re.compile(
                r"(?<![\w#.])(?P<word>%s|\d+)\s+(?P<noun>%s)\b" % (cardinals, counts),
                re.IGNORECASE,
            ),
        ))
        shapes.append((
            "beyond",
            re.compile(
                r"(?<![\w#.])(?P<word>%s)\s+(?P<noun>%s)\b" % (beyond, counts),
                re.IGNORECASE,
            ),
        ))
    return tuple(shapes)


def source_of(name: str, noun: str) -> tuple | None:
    """Яке джерело називає це слово-іменник (і чи воно взагалі в переліку)."""
    word = normalise(noun)
    if name == "beyond":
        for source, _, count in NOUNS:
            if word in (normalise(one) for one in count):
                return ("beyond", source)
        return None
    for source, place, count in NOUNS:
        forms = place if name == "position" else count
        if word in (normalise(one) for one in forms):
            return ("position", source) if name == "position" else ("count", source)
    return None


NOUN_FORMS = tuple(
    normalise(word) for _, place, count in NOUNS for word in place + count
)


def hits(text: str) -> list[tuple[int, str, str, tuple]]:
    """Числа в ПРОЗІ тексту: (рядок, слово, іменник, джерело).

    Дешевий фільтр перед трьома регексами: у рядку мусить бути хоч одне з
    дев'яти слів-іменників. Без нього кожен рядок корпусу коштує три прогони.
    """
    found = []
    shapes = patterns()
    for number, line in enumerate(prose(text).splitlines(), 1):
        low = line.lower()
        if not any(form in low for form in NOUN_FORMS):
            continue
        for name, pattern in shapes:
            for match in pattern.finditer(line):
                source = source_of(name, match.group("noun"))
                if source is None:
                    continue
                found.append((number, match.group("word"), match.group("noun"), source))
    return found


def line_with_anchor(text: str, anchor: str, where: str) -> tuple[int, str]:
    """Рядок, у якому якір трапляється рівно один раз.

    Рахуються ВХОДЖЕННЯ, не рядки: якір, повторений в одному рядку, — це вже не
    одне місце, і вгадувати, яке з двох мав на увазі запис, механізм не буде.
    """
    if text.count(anchor) != 1:
        raise NumberError(
            "%s: якір мусить траплятися рівно раз, а трапляється %d: %r"
            % (where, text.count(anchor), anchor)
        )
    number = text.count("\n", 0, text.index(anchor)) + 1
    return number, text.splitlines()[number - 1]


BARE = re.compile(r"(?<![\w#.])\d+(?![\w])")


def line_hits(root: Path, relative: str, line_number: int) -> list[tuple]:
    """Числа одного рядка файлу."""
    path = root / relative
    if not path.is_file():
        raise NumberError("немає файлу: %s" % relative)
    return [one for one in hits(path.read_text(encoding="utf-8")) if one[0] == line_number]


def figure_hits(line: str, source: tuple) -> list[tuple]:
    """Числа рядка, які може мати на увазі ЦЕЙ запис.

    Аудит корпусу не має форми-слова (`154 figures`, `148 of them`), тож його
    число шукається як голе ціле — і лише для зарахованого запису: шукати голі
    числа по всьому корпусу означало б оголосити числом кожну «3» в доку.
    """
    if source[0] == "audit":
        return [(0, match.group(0), "", source) for match in BARE.finditer(prose(line))]
    return [one for one in hits(line) if key_of(one[3]) == key_of(source)]


def key_of(source: tuple) -> tuple:
    """Ключ, за яким число на рядку звіряється із зарахованим записом.

    Місце в серії знає, якої серії воно місце, але не якого ключа: рядок сам
    називає ключ (`#1270`), і саме тому виведене значення видно в повідомленні.
    """
    return source[:2] if source[0] == "position" else source


def spelling_of(source: tuple) -> str:
    """Ім'я джерела для повідомлення — щоб знахідку було чим виправити."""
    value = derive(source)
    note = "%d, «%s»" % (value, spelled(value, source[0]))
    if source[0] == "count":
        return "`%s` (%s)" % (source[1], note)
    if source[0] == "audit":
        return "аудит корпусу `%s` (%s)" % (source[1], note)
    return "місце %s у `%s` (%s)" % (source[2], source[1], note)


def derived_note(source: tuple, line: str) -> str:
    """Число, яке дерево виводить для цього рядка, — щоб знахідку було чим закрити.

    Для місця в серії ключ шукається на самому рядку: якщо він там не один,
    підказки немає — вгадувати, про який випадок рядок, механізм не буде.
    """
    try:
        if source[0] == "count":
            value = len(record(source[1]))
            return "; `%s` виводить «%s» (%d)" % (
                source[1], spelled(value, "count"), value
            )
        named = [key for key in keys(source[1]) if key in line]
        if len(named) != 1:
            return ""
        value = keys(source[1]).index(named[0]) + 1
        return "; `%s` виводить «%s» (%d)" % (
            source[1], spelled(value, "position"), value
        )
    except (NumberError, IndexError):
        return ""


# --------------------------------------------------------------------------
# Команди: число має команду — і команда мусить існувати
# --------------------------------------------------------------------------

# Згадка скрипта з дерева: `scripts/x.py`, `scripts/tests/test_y.py`. Префікс
# (`python3 `, `bash `, `../gt `) не має значення; має — що шлях починається з
# `scripts/`, а не з `root/scripts/` (це вже не команда, а змінна в коді).
CITED = re.compile(r"(?<![\w./-])(scripts/[\w./-]+\.(?:py|sh|mjs))")
OPTION = re.compile(r"(?<![\w-])(--[a-z][\w-]*)")


def code_spans(text: str) -> list[str]:
    """Код-спани тексту: блочні й рядкові — там живуть команди."""
    return [match.group(0) for match in FENCED.finditer(text)] + [
        match.group(0) for match in INLINE.finditer(text)
    ]


@functools.lru_cache(maxsize=None)
def declared_options(path: Path) -> set[str] | None:
    """Які опції оголошує скрипт; None — не розібрано (невідомість).

    `.py` питається через AST (`add_argument`), і це сильний пін. `.sh`/`.mjs`
    реєстру опцій не мають, тож «оголошена» для них означає «згадана у файлі» —
    слабший пін, названий у шапці.
    """
    text = path.read_text(encoding="utf-8")
    if path.suffix != ".py":
        return set(OPTION.findall(text))
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return None
    found = {"-h", "--help"}  # argparse додає їх сам
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        if not (isinstance(node.func, ast.Attribute) and node.func.attr == "add_argument"):
            continue
        for argument in node.args:
            if (isinstance(argument, ast.Constant)
                    and isinstance(argument.value, str)
                    and argument.value.startswith("-")):
                found.add(argument.value)
    return found


def command_findings(root: Path, scanned) -> tuple[list[str], list[str]]:
    """Кожна згадана в документі команда мусить існувати й мати свої опції.

    Опція приписується тій команді, яка стоїть ПЕРЕД нею в тому самому рядку:
    `scripts/mutate.sh --patch … --python scripts/tests/test_x.py` — це одна
    команда, хоч скриптів у рядку два. Опція без жодної команди перед собою не
    перевіряється: `--jobs` у прозі не належить нікому.
    """
    findings: list[str] = []
    unknowns: list[str] = []
    for relative in scanned:
        path = root / relative
        if not path.is_file():
            unknowns.append("немає файлу: %s" % relative)
            continue
        for span in code_spans(path.read_text(encoding="utf-8")):
            for line in span.splitlines():
                current: str | None = None
                for match in re.finditer(
                    r"(?<![\w./-])(scripts/[\w./-]+\.(?:py|sh|mjs))|(?<![\w-])(--[a-z][\w-]*)",
                    line,
                ):
                    if match.group(1):
                        current = match.group(1)
                        continue
                    option = match.group(2)
                    if current is None:
                        continue
                    script = root / current
                    if not script.is_file():
                        findings.append(
                            "%s — команда, якої немає в дереві: %s (опція %s)"
                            % (relative, current, option)
                        )
                        continue
                    known = declared_options(script)
                    if known is None:
                        unknowns.append("%s — не розібрано: %s" % (relative, current))
                    elif option not in known:
                        findings.append(
                            "%s — вигадана команда: %s не оголошує %s"
                            % (relative, current, option)
                        )
    return findings, unknowns


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


def check(root: Path = ROOT, figures=FIGURES, scanned=SCANNED) -> tuple[list[str], list[str]]:
    """Що механізм побачив: (знахідки, невідомості).

    Невідомість не ховає знахідок: якщо в дереві є і те, й те, видно обидва, а
    код виходу — 2. Ховати знахідку за невідомістю означало б, що одне зламане
    посилання робить невидимим усе інше.
    """
    findings = []
    unknowns = []
    covered: dict[tuple[str, int], list[tuple]] = {}
    for relative, anchor, source in figures:
        path = root / relative
        if not path.is_file():
            unknowns.append("немає файлу: %s" % relative)
            continue
        text = path.read_text(encoding="utf-8")
        try:
            number, _ = line_with_anchor(text, anchor, relative)
        except NumberError as error:
            unknowns.append(str(error))
            continue
        try:
            accepted = spellings(source)
            where = spelling_of(source)
            on_line = figure_hits(text.splitlines()[number - 1], source)
        except NumberError as error:
            unknowns.append("%s:%d: %s" % (relative, number, error))
            continue
        if not on_line:
            unknowns.append(
                "%s:%d: рядок більше не несе числа джерела %s — запис застарів"
                % (relative, number, where)
            )
            continue
        if len(on_line) > 1:
            # Шапка обіцяє «рівно одне число того джерела» — і це мусить бути код,
            # а не проза: два числа на зарахованому рядку не дають сказати, котре
            # з них запис тримає.
            unknowns.append(
                "%s:%d: рядок несе %d числа джерела %s — а мусить рівно одне"
                % (relative, number, len(on_line), where)
            )
            continue
        # Місце в серії мусить називати СВІЙ ключ: «eighth» над посиланням на
        # інший випадок — це число без джерела, хоч би яке воно було правильне.
        if source[0] == "position":
            line = text.splitlines()[number - 1]
            named = [key for key in keys(source[1]) if key in line]
            if source[2] not in named:
                unknowns.append(
                    "%s:%d: рядок %s — а %s виводиться з %s"
                    % (
                        relative,
                        number,
                        "не називає ключа серії" if not named
                        else "називає інший ключ (%s)" % ", ".join(named),
                        spelling_of(source),
                        source[2],
                    )
                )
                continue
        for _, word, _, _ in on_line:
            if normalise(word) not in accepted:
                findings.append(
                    "%s:%d — рядок каже «%s», а виводиться %s"
                    % (relative, number, word, where)
                )
        covered.setdefault((relative, number), []).append(key_of(source))
    command_found, command_unknown = command_findings(root, scanned)
    findings.extend(command_found)
    unknowns.extend(command_unknown)
    for relative in scanned:
        path = root / relative
        if not path.is_file():
            continue  # уже названо вище, командною половиною
        text = path.read_text(encoding="utf-8")
        for number, word, noun, source in hits(text):
            if key_of(source) in covered.get((relative, number), []):
                continue
            if source[0] == "beyond":
                unknowns.append(
                    "%s:%d — «%s %s»: число поза таблицею написань; виведи його або "
                    "прибери" % (relative, number, word, noun)
                )
                continue
            line = text.splitlines()[number - 1]
            findings.append(
                "%s:%d — «%s %s»: числа немає в жодному записі дерева%s; виведи його "
                "або прибери (реєстр: FIGURES у scripts/doc_numbers.py)"
                % (relative, number, word, noun, derived_note(source, line))
            )
    return findings, unknowns


def listing(root: Path = ROOT) -> list[str]:
    """Що механізм покриває: серія, реєстр і кожне зараховане число."""
    units, undated = audit_counts()
    lines = [
        "CASES (%d): %s" % (len(CASES), ", ".join(issue for issue, _ in CASES)),
        "audit корпусу: %d фігур «число + одиниця», %d без дати" % (units, undated),
    ]
    try:
        lines.append("guards (%d): %s" % (len(guards()), "GUARDS"))
    except Exception as error:  # реєстр не прочитався — це видно, а не замолчано
        lines.append("guards (?): %s" % error)
    for relative, anchor, source in FIGURES:
        try:
            number, _ = line_with_anchor(
                (root / relative).read_text(encoding="utf-8"), anchor, relative
            )
        except (OSError, NumberError) as error:
            lines.append("%s — %s" % (relative, error))
            continue
        lines.append(
            "%s:%d — «%s» = %s" % (relative, number, anchor, spelling_of(source))
        )
    return lines


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Кожне число в доку мусить мати команду, яка його відтворює."
    )
    parser.add_argument("--root", default=str(ROOT), help="корінь дерева")
    parser.add_argument("--list", action="store_true",
                        help="лише назвати серію й зараховані числа")
    parser.add_argument("--audit", action="store_true",
                        help="лише зміряти корпус: фігури «число + одиниця»")
    options = parser.parse_args(argv)
    if options.audit:
        units, undated = audit_counts()
        print("фігур «число + одиниця» в корпусі: %d" % units)
        print("з них без дати на рядку: %d" % undated)
        return 0
    if options.list:
        for line in listing(Path(options.root)):
            print(line)
        return 0
    findings, unknowns = check(Path(options.root))
    for finding in findings:
        print(finding)
    for unknown in unknowns:
        print("НЕВІДОМО: %s" % unknown, file=sys.stderr)
    if unknowns:
        return 2
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
