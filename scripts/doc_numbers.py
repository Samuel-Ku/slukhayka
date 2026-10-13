#!/usr/bin/env python3
"""Число в доку, яке щось стверджує про дерево, мусить виводитися з дерева (#1277).

За серію **сім** чисел у документах виявилися нічим не перевіреними, і кожен раз
це знаходив незалежний огляд, не автор: «хибних відмов на реальній історії не
лишилося» (насправді 3 із 10); «931 шлях у дереві» (858/881/0/970 — жодне не
931); **вигадана команда** `--measure-head` (її не існувало, `git log --all -S`
мав єдине входження — сам рядок документа); «немає мережі» (`ubuntu-latest` має
egress; несла половина — відсутній `GH_TOKEN`); «326 тестів» (насправді 327);
«74.7 MiB» (насправді 76.2, виправлено в #1270); і «The seventh case of "a check
that does not check"» (механізм каже «дев'ятий»). Спільне в усіх: **число в
доку, яке ніщо не перевіряє**.

Форми «перевір слово поруч із цифрою» немає, і це доводиться тут, а не
обіцяється: у самих лише `docs/agents/*.md` і `scripts/*.py` — сотні чисел
(126 самих «число + одиниця», з них 120 не поруч із жодною датою), і більшість
із них — **записи про минуле**: секунди й гігабайти конкретної збірки на
конкретній машині 2026-10-12, які жодна команда не відтворить на HEAD. Вимога
«біля кожного числа — команда» перетворилася б на сотню винятків, а виняток —
це та сама обіцянка в прозі, тільки довша.

Тому механізм вужчий за клас і не вдає ширшого: **число, яке щось стверджує про
дерево СЬОГОДНІ, мусить виводитися з запису в дереві — або його немає.** Два
види виведення, обидва — дані:

  * `("count", запис)` — число є довжиною реєстру (`CASES`, `GUARDS`…);
  * `("position", серія, ключ)` — число є місцем ключа в серії (`#1270` —
    восьмий у `CASES`).

`FIGURES` — реєстр зарахованих чисел: (файл, якір рядка, джерело). Для кожного:
якір трапляється у файлі **рівно один раз**, рядок несе **рівно одне** число
того джерела, і це число мусить бути серед написань виведеного значення.
`SCANNED` — файли, у яких механізм **шукає** числа цих джерел: незараховане
число в них — відмова, а не тиха згода. Тому нове число в доку не проходить
мовчки: воно або виводиться, або його немає.

Що механізм знайшов на СВОЄМУ першому прогоні (тобто чим він червоний, а не
«працює в теорії»):

  * `docs/agents/local-environment.md:337` — «The **seventh** case…», тоді як
    `CASES` дає восьме місце для `#1270`: **сім і дев'ять — обидва не мають
    джерела**, а перелік, на який вони спираються (`#1233`, `#1231`, `#1239`,
    `#1246`, `#1254`, `#1257`, `#1265` і сам сторож), налічує **вісім**;
  * `scripts/guard_witnesses.py:4` — «**Дев'ятий** випадок…» і його ж
    «до цього **вісім** разів…» при переліку з **семи** issue: обидва числа
    розходяться з тим самим переліком на одиницю;
  * `scripts/guard_witnesses.py:74` — «**шість** записів разом — 1.4 с»:
    у `GUARDS` їх **сім** (#1276 додав запис, а число лишилося);
  * `scripts/empty_shape_mutants.py:5` — «**П'ятий** випадок…» — збігається з
    п'ятим місцем `#1254` у `CASES`, і це єдине з чотирьох, що було правдою.

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
  * **серія `CASES` — це перелік, а не істина.** Він зібраний з єдиного
    переліку, який існує (тіло #1270 плюс `empty_shape_mutants.py`), і саме
    тому він дає **вісім**, а не сім і не дев'ять. Повнота переліку — робота
    огляду; механізм тримає лише те, що число в доку збігається з переліком, і
    робить розходження видимим;
  * **написання — таблиця до дванадцяти.** `CARDINALS`/`ORDINALS` знають
    «один..дванадцять» і «перший..дванадцятий» двома мовами; інша форма
    (родовий відмінок, число понад дванадцять) не знаходиться взагалі — це
    невідомість (2), а не тиха згода;
  * **`запис` — двозначне слово.** В `guard_witnesses.py` «записів» — це
    `GUARDS`, у `lint_baseline.py` — рядки базлайну; тому числівники прив'язані
    до джерел явно, а не вгадуються за словом;
  * **механізм читає прозу, не цитати.** Число в код-спані (`` `…` ``) або в
    блоці (```…```) — це те, що документ НАВОДИТЬ: команда, шлях, літерал,
    уривок зі старої шапки. Правило машинне (бектики видно), і воно потрібне,
    бо сама розповідь про цей клас цитує старі числа; твердження, сховане в
    бектики, побачить око оглядача, а не regex — і це названа межа, не діра,
    яку тут замовчують.

Коди виходу: 0 — кожне зараховане число виводиться, і жодного незарахованого
числа в перелічених файлах немає; 1 — знахідка (число не виводиться або його не
зараховано); 2 — перевірити не вдалося (файлу немає, якір не один, рядок не несе
числа, ключа немає в серії, джерела немає).
"""

from __future__ import annotations

import argparse
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
# `position` виводить місце ключа звідси. Сім перших — перелік із шапки
# `guard_witnesses.py` і тіла #1270; восьмий — сам сторож, який цей перелік
# називає.

CASES = (
    ("#1233", "мертвий парсер `check_runs`"),
    ("#1231", "незаякорений regex"),
    ("#1239", "піни на provenance"),
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
)

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
    raise NumberError("невідоме джерело: %r" % (source,))


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


def patterns() -> tuple[tuple[str, re.Pattern], ...]:
    """Дві форми числа: місце в серії (`seventh case`) і кількість (6 guards)."""
    ordinals = _alternation(word for pair in ORDINALS for word in pair)
    cardinals = _alternation(word for pair in CARDINALS for word in pair)
    places = _alternation(word for _, place, _ in NOUNS for word in place)
    counts = _alternation(word for _, _, count in NOUNS for word in count)
    shapes = []
    if places:
        shapes.append((
            "position",
            re.compile(
                r"\b(?P<word>%s)\b[^\n]{0,24}?\b(?P<noun>%s)\b" % (ordinals, places),
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
    return tuple(shapes)


def source_of(name: str, noun: str) -> tuple | None:
    """Яке джерело називає це слово-іменник (і чи воно взагалі в переліку)."""
    word = normalise(noun)
    for source, place, count in NOUNS:
        forms = place if name == "position" else count
        if word in (normalise(one) for one in forms):
            return ("position", source) if name == "position" else ("count", source)
    return None


def hits(text: str) -> list[tuple[int, str, str, tuple]]:
    """Числа в ПРОЗІ тексту: (рядок, слово, іменник, джерело)."""
    found = []
    shapes = patterns()
    for number, line in enumerate(prose(text).splitlines(), 1):
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


def line_hits(root: Path, relative: str, line_number: int) -> list[tuple]:
    """Числа одного рядка файлу."""
    path = root / relative
    if not path.is_file():
        raise NumberError("немає файлу: %s" % relative)
    return [one for one in hits(path.read_text(encoding="utf-8")) if one[0] == line_number]


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
            on_line = [
                one for one in line_hits(root, relative, number)
                if key_of(one[3]) == key_of(source)
            ]
        except NumberError as error:
            unknowns.append("%s:%d: %s" % (relative, number, error))
            continue
        if not on_line:
            unknowns.append(
                "%s:%d: рядок більше не несе числа джерела %s — запис застарів"
                % (relative, number, where)
            )
            continue
        for _, word, _, _ in on_line:
            if normalise(word) not in accepted:
                findings.append(
                    "%s:%d — рядок каже «%s», а виводиться %s"
                    % (relative, number, word, where)
                )
        covered.setdefault((relative, number), []).append(key_of(source))
    for relative in scanned:
        path = root / relative
        if not path.is_file():
            unknowns.append("немає файлу: %s" % relative)
            continue
        text = path.read_text(encoding="utf-8")
        for number, word, noun, source in hits(text):
            if key_of(source) in covered.get((relative, number), []):
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
    lines = ["CASES (%d): %s" % (len(CASES), ", ".join(issue for issue, _ in CASES))]
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
    options = parser.parse_args(argv)
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
