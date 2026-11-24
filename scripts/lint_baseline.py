#!/usr/bin/env python3
"""Ґард зростання app/lint-baseline.xml і генератор його записів (#1222).

`app/lint-baseline.xml` — придушення боргу, який lint знайшов один раз. Нове
порушення ПОЗА baseline валить `:app:lintDebug` (це вже так), але сам список
придушення ріс мовчки: 1083 записи MissingTranslation на трьох локалях, і кожен
новий рядок інтерфейсу — це рукописний XML-запис із `errorLine1/2`, рядком і
колонкою. Ніщо не помічало, що записів побільшало.

Дві роботи, один опис запису — щоб ґард і генератор не розійшлися в тому, що
вважати записом і де він стоїть у файлі:

  check-growth <base-ref> [<head-ref>]
      Кількість записів у HEAD проти базового ref: ОКРЕМО MissingTranslation,
      ОКРЕМО кожне інше правило. Будь-який приріст — падіння (exit 1).
      Зменшення — норма (записи прибирають, коли порушення полагоджено).

  add [--report <lint-results-debug.xml>]
      Бере записи з XML-звіту lint і вставляє їх у baseline на канонічну
      позицію, з точними метаданими зі звіту (id, message, errorLine1/2,
      location) — без ручного копіювання рядка, колонки й екранованого коду.

  verify
      Доводить, що кожен наявний запис файлу цей самий генератор відтворює
      байт-у-байт. Якщо ні — `add` відмовиться писати: вставка «своїм» текстом
      у чужий формат зіпсувала б стиль файлу й дала б шумний диф.

Чому MissingTranslation окремо. Це 1083 з 1313 записів, і саме він росте від
кожної нової літери інтерфейсу (UI перекладено українською, англійською й
польською; `values-pl` тримає лише п'ять Material3-оверрайдів, тож базовий
`values/` лишається єдиним джерелом нових рядків). Решта правил росте від коду, і змішувати їх в
одному числі означало б ховати приріст перекладу за шумом решти.

Чому порівняння з базовим ref, а не з бюджетом у файлі. Бюджет («дозволено
1083») — це друге джерело правди, яке саме починає тихо рости. Базовий ref —
це те, що вже в main; приріст проти нього видно в дифі PR і не потребує
окремого числа, яке хтось мусить пам'ятати оновлювати.

Виняток для законного додавання — один, і він не в PR, а на рівні власника:
репозиторна змінна LINT_BASELINE_GROWTH_ALLOWANCE (той самий механізм, що
BASE_SYNC_MAX_AGE_HOURS у .github/workflows/ci.yml). Формат:
`Правило=+N@<тікет>[,Правило=+N@<тікет>]`, напр. `MissingTranslation=+3@1222`.
Порожня змінна = нуль допуску.

Тікет у значенні обов'язковий, і ось чому. Змінна живе ПОЗА git, без терміну, і
порівнюється з рухомою базою (base-ref PR), тож забутий дозвіл тихо пропускає
до +N у кожному наступному PR. Назва тікета робить дозвіл прив'язаним до
роботи, яку видно, а ґард друкує активний дозвіл ЗАВЖДИ — у лог і в підсумок
кроку, навіть коли приросту немає зовсім («0 із 5 — НЕ витрачено (@1222)»).
Знімається дозвіл тоді, коли закрито тікет, названий у значенні.

Виклик (локально, з кореня репозиторію):
    python3 scripts/lint_baseline.py check-growth origin/main HEAD
    python3 scripts/lint_baseline.py check-growth origin/main --worktree
    python3 scripts/lint_baseline.py add --report app/build/reports/lint-results-debug.xml
    python3 scripts/lint_baseline.py verify
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
BASELINE_DEFAULT = "app/lint-baseline.xml"
REPORT_DEFAULT = "app/build/reports/lint-results-debug.xml"
MISSING_TRANSLATION = "MissingTranslation"

# Дзеркало ґарда #1196 із джоби `lint`: той самий патерн, по символу. Тест
# витягує рядок `grep -qE '…'` із .github/workflows/ci.yml і порівнює його з
# цим шаблоном, тож розійтися вони не можуть.
MACHINE_PATH_RE = re.compile(r'file="[^"]*(\.worktrees/|\$HOME/|\$GRADLE_USER_HOME/)')

# Lint пише ці два записи ПРО сам baseline (скільки придушено, скільком записам
# уже нічого не відповідає). У baseline вони не потрапляють ніколи — у файлі
# таких id немає, — тож генератор мусить їх відкидати, а не вставляти.
META_ISSUE_IDS = ("LintBaseline", "LintBaselineFixed")

# Severity, на яких падає `:app:lintDebug` (lint abortOnError). Warning у звіті
# є завжди (напр. GradleDependency), але збірку він не валить — і запис у
# baseline для нього не потрібен, якщо автор не просив явно через --severity.
FAILING_SEVERITIES = ("fatal", "error")

USAGE_EPILOG = """\
Приклади:
  python3 scripts/lint_baseline.py check-growth origin/main HEAD
  python3 scripts/lint_baseline.py add --dry-run
  python3 scripts/lint_baseline.py add --id MissingTranslation --severity error
"""


class BaselineError(Exception):
    """Baseline не такий, яким його пише lint — писати в нього небезпечно."""


# --------------------------------------------------------------------------
# Модель запису
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Location:
    file: str
    line: int | None = None
    column: int | None = None


@dataclass(frozen=True)
class Entry:
    """Один `<issue>` baseline: рівно ті атрибути, які пише lint."""

    issue_id: str
    message: str
    error_lines: tuple[str, ...]  # errorLine1, errorLine2 — може бути порожньо
    locations: tuple[Location, ...]

    @property
    def file(self) -> str:
        return self.locations[0].file if self.locations else ""

    @property
    def line(self) -> int:
        if not self.locations or self.locations[0].line is None:
            return -1
        return self.locations[0].line

    @property
    def column(self) -> int:
        if not self.locations or self.locations[0].column is None:
            return -1
        return self.locations[0].column


def canonical_key(entry: Entry) -> tuple:
    """Ключ, за яким lint сам сортує записи baseline.

    `LintBaseline$ReportedEntry.compareTo` (lint-api 32.1.1) усередині одного
    правила порівнює: basename файлу, рядок, message, повний шлях, колонку.
    Саме тому `values-pl/strings.xml` стоїть перед `values-en/strings.xml` за
    однакового basename — не шляхом, а рядком (порядок не залежить від того, що
    саме лежить у файлі; він і далі існує як Material3-оверрайд). Блоки різних правил
    упорядковані категорією/пріоритетом/id; baseline їх не зберігає, тож
    порядок блоків ця функція не чіпає — вона рахує позицію ЛИШЕ всередині
    блоку того самого id (див. insert_position).
    """
    return (
        os.path.basename(entry.file),
        entry.line,
        entry.message,
        entry.file,
        entry.column,
    )


def identity(entry: Entry) -> tuple[str, str, str]:
    """Те, за чим lint упізнає вже придушене порушення: id + файл + message.

    Рядок і колонка в цю тотожність не входять: у файлі є записи, чиї рядки
    застаріли (напр. `achievement_in_cycle` записано на 768, а він тепер 772),
    і lint їх усе одно придушує — інакше `lintDebug` падав би на кожному
    зсуві рядків.
    """
    return (entry.issue_id, entry.file, entry.message)


_ATTR_ESCAPES = (
    ("&", "&amp;"),
    ("<", "&lt;"),
    ('"', "&quot;"),
    ("'", "&apos;"),
    ("\n", "&#xA;"),
    ("\r", "&#xD;"),
    ("\t", "&#x9;"),
)


def escape_attr(value: str) -> str:
    """Екранування рівно як у файлі, а не «як взагалі правильно в XML».

    `>` НЕ екранується: у baseline 1163 атрибути з сирим `>` (уся кодова
    витримка з Kotlin і манифесту) і лише 9 записів із `&gt;` — і ті всі
    MissingTranslation, дописані руками. `XmlUtils.toXmlAttributeValue` з
    lint-api екранує й `>`, але baseline пише не він; підганяти файл під
    бібліотеку означало б переписати 1163 рядки чужого тексту.

    Точність перевіряє `verify`: 1304 з 1313 записів мусять відтворитися
    байт-у-байт, а 9 рукописних — принаймні семантично (див. verify_entries).
    """
    for raw, escaped in _ATTR_ESCAPES:
        value = value.replace(raw, escaped)
    return value


def serialize_entry(entry: Entry) -> str:
    """Текст запису в тому вигляді, в якому його пише lint (4/8/12 пробілів).

    Закривальний `>` (або `/>` для запису без location) дописується в кінець
    останнього рядка атрибута, а не стає на власний рядок — саме так виглядає
    файл, і `verify` тримає цю рівність байт-у-байт.
    """
    attributes = [f'id="{escape_attr(entry.issue_id)}"', f'message="{escape_attr(entry.message)}"']
    attributes += [
        f'errorLine{index}="{escape_attr(error_line)}"'
        for index, error_line in enumerate(entry.error_lines, start=1)
    ]
    if not entry.locations:
        attributes[-1] += "/>"
        return "    <issue\n" + "".join(f"        {line}\n" for line in attributes) + "    </issue>\n"

    attributes[-1] += ">"
    out = ["    <issue\n"] + [f"        {line}\n" for line in attributes]
    for location in entry.locations:
        location_attributes = [f'file="{escape_attr(location.file)}"']
        if location.line is not None:
            location_attributes.append(f'line="{location.line}"')
        if location.column is not None:
            location_attributes.append(f'column="{location.column}"')
        location_attributes[-1] += "/>"
        out.append("        <location\n")
        out += [f"            {line}\n" for line in location_attributes]
    out.append("    </issue>\n")
    return "".join(out)


def entry_from_xml(element: ET.Element) -> Entry:
    """`<issue>` зі звіту або baseline → Entry (зайві атрибути відкидаються).

    Звіт несе ще severity/category/priority/summary/explanation; baseline їх не
    зберігає, тож у моделі їх немає — і `add` фізично не може дописати у файл
    те, чого там не буває.
    """
    error_lines = tuple(
        element.get(f"errorLine{index}")
        for index in (1, 2)
        if element.get(f"errorLine{index}") is not None
    )
    return Entry(
        issue_id=element.get("id", ""),
        message=element.get("message", ""),
        error_lines=error_lines,
        locations=tuple(_flatten_locations(element)),
    )


def _flatten_locations(element: ET.Element):
    """Усі `<location>` запису, у порядку документа.

    Baseline тримає кілька location сіблінгами (так пише lint: `IconDuplicates`
    має два), а звіт для вторинних місць вкладає `<location>` один в один.
    Розгортаємо — щоб запис у baseline мав ту саму форму, що й решта файлу.
    """
    for child in element:
        if child.tag != "location":
            continue
        line = child.get("line")
        column = child.get("column")
        yield Location(
            file=child.get("file", ""),
            line=int(line) if line is not None else None,
            column=int(column) if column is not None else None,
        )
        yield from _flatten_locations(child)


# --------------------------------------------------------------------------
# Розбір файлу baseline
# --------------------------------------------------------------------------


@dataclass
class Baseline:
    """Файл як послідовність СИРИХ записів плюс проміжки між ними.

    Сирі тексти й проміжки зберігаються, щоб `add` вставляв лише новий запис, а
    решту файлу не переписував: 14 445 рядків не мають пройти через мій
    форматер навіть якщо він на один пробіл розійдеться з lint. Проміжки тут не
    зайвина: у файлі три місця, де між записами стоїть не один порожній рядок
    (2×4 і 1×31 нових рядків — сліди ручних правок), і «нормалізувати» їх
    означало б переписати чуже.
    """

    raw: str
    head: str  # усе до першого запису, включно з порожнім рядком
    blocks: list[str]  # сирий текст кожного запису, кожен кінчається "</issue>\n"
    spans: list[tuple[int, int]]  # межі блоків у raw
    gaps: list[str]  # текст МІЖ записами (len == len(blocks) - 1)
    tail: str  # "\\n</issues>\\n"
    entries: list[Entry]

    def render(self) -> str:
        out = [self.head]
        for index, block in enumerate(self.blocks):
            out.append(block)
            if index < len(self.gaps):
                out.append(self.gaps[index])
        out.append(self.tail)
        return "".join(out)

    def offset_before(self, index: int) -> int:
        """Зсув, ПЕРЕД яким вставляється новий запис, якщо якір — запис index.

        Порожній рядок перед новим записом дає вже наявний проміжок (він
        належить НАСТУПНОМУ запису), а порожній рядок після нього додає
        вставка. Інакше на межі з'являлися б два порожні рядки — саме так
        виглядала б вставка «після попереднього», а не «перед наступним».
        -1 — початок файлу: там проміжок лежить у head (порожній рядок після
        заголовка), тож вставка йде просто перед першим записом.
        """
        if index + 1 < len(self.spans):
            return self.spans[index + 1][0]
        closing = self.raw.rindex("</issues>")
        line_start = self.raw.rfind("\n", 0, closing)
        return line_start + 1 if line_start != -1 else 0


_BLOCK_RE = re.compile(r"^    <issue\b.*?^    </issue>\n", re.MULTILINE | re.DOTALL)


def parse_baseline_text(raw: str, source: str = BASELINE_DEFAULT) -> Baseline:
    """Розібрати файл і довести, що він збирається назад байт-у-байт.

    Якщо ні — краще відмовитись одразу: `add` пише у файл, який не розуміє
    цілком, а «майже розуміє» тут означає зіпсований baseline.
    """
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise BaselineError(f"{source}: не парситься як XML: {error}") from error
    children = [child for child in root if child.tag == "issue"]
    matches = list(_BLOCK_RE.finditer(raw))
    if len(children) != len(matches):
        raise BaselineError(
            f"{source}: XML бачить {len(children)} записів <issue>, "
            f"а текстовий розбір — {len(matches)}; файл не у формі lint, "
            "перегенеруй його (updateLintBaseline) або полагодь вручну"
        )
    if not matches:
        raise BaselineError(f"{source}: у файлі немає жодного запису <issue>")
    spans = [(match.start(), match.end()) for match in matches]
    blocks = [match.group(0) for match in matches]
    gaps = [raw[spans[i][1] : spans[i + 1][0]] for i in range(len(spans) - 1)]
    tail = raw[spans[-1][1] :]
    if "</issues>" not in tail:
        raise BaselineError(f"{source}: після останнього запису немає закритого </issues>")
    baseline = Baseline(
        raw=raw,
        head=raw[: spans[0][0]],
        blocks=blocks,
        spans=spans,
        gaps=gaps,
        tail=tail,
        entries=[entry_from_xml(child) for child in children],
    )
    if baseline.render() != raw:
        raise BaselineError(f"{source}: файл не збирається назад байт-у-байт")
    return baseline


def load_baseline(path: Path) -> Baseline:
    return parse_baseline_text(path.read_text(encoding="utf-8"), str(path))


def count_by_rule(entries: list[Entry]) -> Counter:
    return Counter(entry.issue_id for entry in entries)


def verify_entries(baseline: Baseline, source: str) -> tuple[list[str], list[str]]:
    """(семантичні розходження, косметичні).

    Семантичне розходження — форматер міняє `id`/`message`/`errorLine`/location
    запису. Такого бути не повинно ніколи: це вже не «інший стиль», а інший
    запис.
    Косметичне — текст той самий після розбору XML, різниця лише в записі
    сутності: 9 рукописних записів MissingTranslation екранують `>` як `&gt;`,
    чого lint не робить. Їх не чіпаємо (правка чужого тексту — не робота
    генератора), але й мовчати про них не можна.
    """
    broken: list[str] = []
    cosmetic: list[str] = []
    for entry, block in zip(baseline.entries, baseline.blocks):
        rendered = serialize_entry(entry)
        if rendered == block:
            continue
        where = f"{source}: {entry.issue_id} {describe(entry)}"
        try:
            reparsed = entry_from_xml(ET.fromstring(rendered))
        except ET.ParseError:
            reparsed = None
        if reparsed == entry:
            cosmetic.append(f"{where} — той самий запис, інший запис сутності")
        else:
            broken.append(f"{where} — форматер відтворює його інакше")
    return broken, cosmetic


# --------------------------------------------------------------------------
# check-growth
# --------------------------------------------------------------------------


def read_git_file(ref: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise BaselineError(
            f"git show {ref}:{path} не вдався — чи існує '{ref}' і файл у ньому? "
            f"({result.stderr.strip() or 'без stderr'})"
        )
    return result.stdout


@dataclass(frozen=True)
class Allowance:
    """Дозвіл на приріст одного правила: скільки і за яким тікетом.

    `ref` обов'язковий саме тому, що змінна живе поза git і без терміну: без
    назви тікета забутий дозвіл неможливо ні пояснити, ні прибрати — а він
    дозволяє до +N у КОЖНОМУ наступному PR (база порівняння рухома).
    """

    extra: int
    ref: str

    def __str__(self) -> str:
        return f"+{self.extra}@{self.ref}"


def parse_allowance(spec: str, known_rules: set[str]) -> dict[str, Allowance]:
    """LINT_BASELINE_GROWTH_ALLOWANCE → {правило: дозвіл}.

    Формат: `Правило=+N@<тікет>[,...]`, напр. `MissingTranslation=+3@1222`.
    Помилка в змінній — падіння, а не «нуль допуску»: мовчазний нуль виглядав
    би як звичайна червона збірка й автор шукав би причину в коді, а не в
    змінній. Невідоме правило — теж падіння (одруківка в id не має тихо
    дозволяти нічого), як і дозвіл без `@тікета`.
    """
    allowance: dict[str, Allowance] = {}
    for chunk in spec.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if "=" not in chunk:
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: '{chunk}' без '=' — формат Правило=+N@тікет"
            )
        rule, _, value = chunk.partition("=")
        rule = rule.strip()
        number, _, ref = value.strip().partition("@")
        number = number.strip().lstrip("+")
        ref = ref.strip()
        if not rule or not number.isdigit():
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: '{chunk}' не Правило=+N@тікет (N — ціле ≥ 0)"
            )
        if not ref:
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: '{chunk}' без '@тікета'. Дозвіл живе поза git "
                "і без терміну, тож мусить називати тікет, за яким його знімуть — інакше забутий "
                "дозвіл тихо пропускатиме приріст у кожному наступному PR"
            )
        if rule not in known_rules:
            raise BaselineError(
                f"LINT_BASELINE_GROWTH_ALLOWANCE: правила '{rule}' немає ні в base, "
                f"ні в HEAD — одруківка? Відомі: {', '.join(sorted(known_rules))}"
            )
        previous = allowance.get(rule)
        allowance[rule] = Allowance(
            extra=(previous.extra if previous else 0) + int(number),
            ref=f"{previous.ref},{ref}" if previous else ref,
        )
    return allowance


def allowance_counts(allowance: dict[str, Allowance]) -> dict[str, int]:
    return {rule: grant.extra for rule, grant in allowance.items()}


def allowance_lines(
    base_counts: Counter, head_counts: Counter, allowance: dict[str, Allowance]
) -> list[str]:
    """Рядки про активний дозвіл — друкуються ЗАВЖДИ, коли змінна непорожня.

    Саме тут закривається вада «забутий дозвіл мовчить»: доки змінна стоїть,
    кожен прогін — і лог, і `$GITHUB_STEP_SUMMARY` — каже, скільки нею
    дозволено, скільки витрачено і за яким тікетом її знімати. Дозвіл, який
    нічого не покрив, видно так само добре, як витрачений.
    """
    lines = []
    for rule in sorted(allowance):
        grant = allowance[rule]
        used = max(0, head_counts.get(rule, 0) - base_counts.get(rule, 0))
        state = "витрачено" if used else "НЕ витрачено"
        lines.append(f"  · {rule}: {used} із {grant.extra} — {state} ({grant.ref})")
    return lines


def summarize_counts(counts: Counter) -> tuple[int, int]:
    """(MissingTranslation, решта правил)."""
    missing = counts.get(MISSING_TRANSLATION, 0)
    return missing, sum(counts.values()) - missing


def growth_rows(
    base_counts: Counter, head_counts: Counter, allowance: dict[str, int]
) -> tuple[list[tuple], list[tuple], list[tuple]]:
    """(приріст без дозволу, приріст у межах дозволу, зменшення) — по правилах.

    Порівнюються кількості ПО КОЖНОМУ правилу, а не одне число на файл: інакше
    −5 UnusedResources і +5 MissingTranslation дали б «нуль» і сховали приріст
    перекладів — рівно те, що тікет просив бачити окремо.
    """
    over: list[tuple] = []
    allowed: list[tuple] = []
    shrunk: list[tuple] = []
    for rule in sorted(set(base_counts) | set(head_counts)):
        base_count = base_counts.get(rule, 0)
        head_count = head_counts.get(rule, 0)
        if head_count == base_count:
            continue
        row = (rule, base_count, head_count, head_count - base_count, allowance.get(rule, 0))
        if head_count > base_count + row[4]:
            over.append(row)
        elif head_count > base_count:
            allowed.append(row)
        else:
            shrunk.append(row)
    return over, allowed, shrunk


def cmd_check_growth(args: argparse.Namespace) -> int:
    baseline = args.baseline
    if args.worktree:
        path = Path(baseline)
        if not path.is_absolute():
            path = REPO_ROOT / path
        raw_head = path.read_text(encoding="utf-8")
        head_label = f"{baseline} (робоче дерево)"
    else:
        raw_head = read_git_file(args.head_ref, baseline)
        head_label = f"{baseline} @ {args.head_ref}"
    raw_base = read_git_file(args.base_ref, baseline)

    try:
        head_entries = parse_baseline_text(raw_head, head_label).entries
    except BaselineError as error:
        raise BaselineError(f"HEAD: {error}") from error
    try:
        base_entries = parse_baseline_text(raw_base, f"{baseline} @ {args.base_ref}").entries
    except BaselineError as error:
        raise BaselineError(f"{args.base_ref}: {error}") from error

    base_counts = count_by_rule(base_entries)
    head_counts = count_by_rule(head_entries)
    known_rules = set(base_counts) | set(head_counts)
    allowance = parse_allowance(os.environ.get("LINT_BASELINE_GROWTH_ALLOWANCE", ""), known_rules)

    base_missing, base_other = summarize_counts(base_counts)
    head_missing, head_other = summarize_counts(head_counts)

    print(f"{baseline}: {args.base_ref} → {head_label}")
    print(f"  {'MissingTranslation':<20} {base_missing:>5} → {head_missing:>5}  {_delta(head_missing - base_missing)}")
    print(f"  {'решта правил':<20} {base_other:>5} → {head_other:>5}  {_delta(head_other - base_other)}")
    print(f"  {'разом':<20} {sum(base_counts.values()):>5} → {sum(head_counts.values()):>5}  "
          f"{_delta(sum(head_counts.values()) - sum(base_counts.values()))}")

    over, allowed, shrunk = growth_rows(base_counts, head_counts, allowance_counts(allowance))
    for rule, base_count, head_count, delta, _extra in shrunk:
        print(f"  · {rule}: {base_count} → {head_count} {_delta(delta)}")

    # Дозвіл друкується завжди, коли він активний — навіть якщо приросту немає
    # зовсім: змінна живе поза git і без терміну, тож «тихо виставлений і
    # забутий» дозвіл мусить бути видно в кожному прогоні, а не лише тоді, коли
    # він щось пропустив.
    if allowance:
        print("Дозвіл LINT_BASELINE_GROWTH_ALLOWANCE (активний, знімається разом із тікетом):")
        for line in allowance_lines(base_counts, head_counts, allowance):
            print(line)
        print("  ↑ доки цей рядок у виводі, дозвіл діє; прибери змінну, коли тікет закрито")

    if not over:
        if allowed:
            print("Ґард зростання lint-baseline: приріст лише в межах дозволу.")
            details = "; ".join(f"`{rule}` +{delta} (дозвіл {extra})" for rule, _, _, delta, extra in allowed)
            _summary(f"⚠️ `app/lint-baseline.xml`: приріст у межах дозволу проти `{args.base_ref}` — {details}")
        else:
            print("Ґард зростання lint-baseline: приросту немає.")
            _summary(
                f"✅ `app/lint-baseline.xml`: без приросту проти `{args.base_ref}` "
                f"({sum(base_counts.values())} → {sum(head_counts.values())} записів)"
            )
        if allowance:
            _summary(
                "⚠️ активний дозвіл `LINT_BASELINE_GROWTH_ALLOWANCE`: "
                + "; ".join(line.strip() for line in allowance_lines(base_counts, head_counts, allowance))
            )
        return 0

    for rule, base_count, head_count, delta, extra in over:
        detail = f"приріст +{delta} ({base_count} → {head_count})"
        if extra:
            detail += f", дозвіл {extra} вичерпано"
        print(f"::error::{baseline}: {rule}: {detail}", file=sys.stderr)
    print(
        "\nЗапис у baseline — це придушення, а не полагодження. Якщо порушення "
        "справжнє (напр. новий рядок uk без перекладу), рішення про приріст ухвалює "
        "власник: він виставляє репозиторну змінну "
        "LINT_BASELINE_GROWTH_ALLOWANCE=<Правило>=+N@<тікет> і знімає її, коли тікет "
        "закрито. Вставити запис із звіту lint без ручного копіювання метаданих: "
        "python3 scripts/lint_baseline.py add",
        file=sys.stderr,
    )
    _summary(
        f"❌ `app/lint-baseline.xml`: приріст проти `{args.base_ref}` — "
        + "; ".join(f"`{rule}` +{delta}" for rule, _, _, delta, _ in over)
    )
    return 1


def _delta(value: int) -> str:
    if value > 0:
        return f"+{value}"
    if value < 0:
        return f"{value} ↓"
    return "0"


def _summary(line: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass  # підсумок — зручність, не ґард: його невдача не має валити крок


# --------------------------------------------------------------------------
# add
# --------------------------------------------------------------------------


def insert_anchor(entries: list[Entry], new_entry: Entry) -> tuple[int, bool]:
    """Після якого запису вставити новий; і чи є взагалі блок цього правила.

    Усередині блоку того самого id: останній запис, чий канонічний ключ не
    більший за ключ нового. Для повністю відсортованого блоку це точний bisect.
    Блок MissingTranslation відсортований НЕ повністю: 17 із 1083 його записів
    стоять не на своїй позиції канонічного порядку (решта 26 блоків канонічні;
    міра — «скільки позицій блоку відрізняється від того самого блоку,
    відсортованого канонічним ключем», не «скільки переміщень» і не «скільки
    рядків застаріло»: застарілі рядки мають сотні записів, і на порядок вони
    не впливають) — рахує й друкує `verify`. Через це якір — «останній ≤
    нового»: він ставить запис поряд із сусідами за метаданими й не перетасовує
    решту, тож диф = +1 запис.

    Якщо новий запис менший за всі наявні в блоці, якір — запис ПЕРЕД блоком
    (тобто вставка на початок блоку); -1 означає початок файлу.
    """
    target = canonical_key(new_entry)
    same = [index for index, entry in enumerate(entries) if entry.issue_id == new_entry.issue_id]
    if not same:
        return (-1, False)
    not_greater = [index for index in same if canonical_key(entries[index]) <= target]
    return (max(not_greater) if not_greater else same[0] - 1, True)


def module_relative(report_file: str, module_dir: Path) -> str | None:
    """Шлях зі звіту (абсолютний) → шлях baseline (відносний до модуля app/)."""
    candidate = Path(report_file)
    if not candidate.is_absolute():
        return report_file if not report_file.startswith("..") else None
    try:
        return candidate.resolve().relative_to(module_dir.resolve()).as_posix()
    except ValueError:
        return None


def collect_report_entries(
    report_path: Path,
    module_dir: Path,
    existing: set[tuple[str, str, str]],
    only_ids: set[str],
    severities: set[str],
) -> tuple[list[Entry], list[str]]:
    try:
        root = ET.parse(report_path).getroot()
    except (ET.ParseError, OSError) as error:
        raise BaselineError(f"{report_path}: не читається як XML-звіт lint: {error}") from error

    selected: list[Entry] = []
    skipped: list[str] = []
    for element in root:
        if element.tag != "issue":
            continue
        issue_id = element.get("id", "")
        severity = (element.get("severity") or "").lower()
        if issue_id in META_ISSUE_IDS:
            skipped.append(f"{issue_id}: запис про сам baseline, у файл не потрапляє")
            continue
        if only_ids and issue_id not in only_ids:
            continue
        if severity not in severities:
            skipped.append(f"{issue_id}: severity {element.get('severity')} — не те, на чому падає lint")
            continue
        entry = entry_from_xml(element)
        relative = module_relative(entry.file, module_dir)
        if relative is None:
            skipped.append(f"{issue_id}: {entry.file} — поза модулем {module_dir.name}/, AGP його тут не придушить")
            continue
        entry = Entry(
            issue_id=entry.issue_id,
            message=entry.message,
            error_lines=entry.error_lines,
            locations=tuple(
                Location(file=module_relative(loc.file, module_dir) or loc.file, line=loc.line, column=loc.column)
                for loc in entry.locations
            ),
        )
        if identity(entry) in existing:
            skipped.append(f"{issue_id}: {entry.file} — уже в baseline")
            continue
        selected.append(entry)
    return selected, skipped


def cmd_add(args: argparse.Namespace) -> int:
    baseline_path = Path(args.baseline)
    if not baseline_path.is_absolute():
        baseline_path = REPO_ROOT / baseline_path
    baseline = load_baseline(baseline_path)
    module_dir = baseline_path.parent

    broken, cosmetic = verify_entries(baseline, args.baseline)
    if cosmetic:
        print(f"Примітка: {len(cosmetic)} наявних запис(ів) мають інший запис сутності (вміст той самий) — не чіпаю.")
    if broken:
        # Не падаємо: наявні записи лишаються недоторканими, вставляється лише
        # новий текст. Але сказати треба — інакше «точні метадані» стануть
        # неправдою для наступного, хто правитиме файл.
        print(f"Увага: {len(broken)} запис(ів) файлу форматер відтворює інакше:", file=sys.stderr)
        for line in broken[:5]:
            print(f"  {line}", file=sys.stderr)

    report_path = Path(args.report)
    if not report_path.is_absolute():
        report_path = REPO_ROOT / report_path
    only_ids = set(args.id or [])
    severities = {value.lower() for value in (args.severity or FAILING_SEVERITIES)}
    if "any" in severities:
        severities = {"fatal", "error", "warning", "informational", "information", "hint", "ignore"}

    existing = {identity(entry) for entry in baseline.entries}
    selected, skipped = collect_report_entries(report_path, module_dir, existing, only_ids, severities)

    print(f"Звіт:     {report_path}")
    print(f"Baseline: {args.baseline} ({len(baseline.entries)} записів)")
    _print_skipped(skipped)
    if not selected:
        print("Немає чого додавати: усі відібрані порушення вже придушені (або не підпадають під фільтр).")
        return 1 if args.require_changes else 0

    selected.sort(key=lambda entry: (entry.issue_id, canonical_key(entry)))
    working = baseline
    for entry in selected:
        anchor, has_block = insert_anchor(working.entries, entry)
        if not has_block:
            anchor = len(working.blocks) - 1
            where = (
                f"у кінець файлу: правила {entry.issue_id} у baseline ще немає "
                "(порядок блоків = порядок lint, і його з baseline не відтворити)"
            )
        elif anchor < 0:
            where = "на початок блоку (менший за всі наявні записи цього правила)"
        else:
            neighbour = working.entries[anchor]
            where = f"після {neighbour.issue_id} {describe(neighbour)}"
        print(f"  + {entry.issue_id} {describe(entry)} — {where}")
        offset = working.offset_before(anchor)
        inserted = serialize_entry(entry) + "\n"
        # Перечитуємо після кожної вставки: файл, який перестав збиратися
        # байт-у-байт, — це вже не той файл, у який можна писати далі.
        working = parse_baseline_text(working.raw[:offset] + inserted + working.raw[offset:], args.baseline)

    for entry in selected:
        _summary_entry(entry)

    # Ґард машинних шляхів (#1196) стоїть ПЕРЕД --dry-run: сухий прогін має
    # показувати те, що реальний прогін справді зробив би, а не текст, який він
    # за тим самим ґардом відкидає.
    if not _machine_path_clean(working.raw):
        raise BaselineError(
            f"{args.baseline}: вставлений запис цитує машинний шлях "
            "(.worktrees/, $HOME/, $GRADLE_USER_HOME/) — ґард #1196 його не пропустить"
        )

    if args.dry_run:
        print("\n--dry-run: файл не змінено. Текст, який було б вставлено:\n")
        for entry in selected:
            print(serialize_entry(entry), end="")
        return 0

    before, after = count_by_rule(baseline.entries), count_by_rule(working.entries)
    if len(working.entries) != len(baseline.entries) + len(selected):
        raise BaselineError(
            f"{args.baseline}: після вставки записів {len(working.entries)}, "
            f"а мало бути {len(baseline.entries) + len(selected)} — файл не записано"
        )
    missing = [entry for entry in selected if identity(entry) not in {identity(e) for e in working.entries}]
    if missing:
        raise BaselineError(f"{args.baseline}: вставка не знайшлася після перечитування — файл не записано")

    baseline_path.write_text(working.raw, encoding="utf-8")
    delta = {rule: after[rule] - before.get(rule, 0) for rule in after if after[rule] != before.get(rule, 0)}
    print(
        f"{args.baseline}: {len(baseline.entries)} → {len(working.entries)} записів "
        f"({', '.join(f'{rule} +{value}' for rule, value in sorted(delta.items()))})"
    )
    return 0


def describe(entry: Entry) -> str:
    return f"{entry.file}:{entry.line}" if entry.line >= 0 else entry.file


def _print_skipped(skipped: list[str], limit: int = 10) -> None:
    for line in skipped[:limit]:
        print(f"  пропущено {line}")
    if len(skipped) > limit:
        print(f"  … і ще {len(skipped) - limit} пропущених")


def _machine_path_clean(rendered: str) -> bool:
    """Той самий ґард #1196, але на етапі генерації, а не в CI."""
    return not MACHINE_PATH_RE.search(rendered)


def order_deviation_by_rule(entries: list[Entry]) -> dict[str, tuple[int, int]]:
    """{правило: (скільки записів не на позиції канонічного ключа, розмір блоку)}."""
    blocks: dict[str, list[Entry]] = {}
    for entry in entries:
        blocks.setdefault(entry.issue_id, []).append(entry)
    deviation: dict[str, tuple[int, int]] = {}
    for rule, block in blocks.items():
        ordered = sorted(block, key=canonical_key)
        deviation[rule] = (
            sum(1 for actual, expected in zip(block, ordered) if canonical_key(actual) != canonical_key(expected)),
            len(block),
        )
    return deviation


def order_deviation(entries: list[Entry]) -> tuple[int, int]:
    """Міра відхилення порядку і міра застарілих рядків.

    Перше число — те, на яке спирається `insert_anchor`: скільки ПОЗИЦІЙ
    списку відрізняється від того самого списку, відсортованого канонічним
    ключем (не «скільки переміщень», не «скільки інверсій» — саме позицій).
    Друге — окрема річ: запис зберігає рядок на момент, коли його записали, і
    файл з того часу зсувався (lint на рядок не дивиться — його тотожність це
    id + файл + message). Друге не впливає на перше, і саме тому вони не
    змішуються в одному числі.
    """
    misplaced = sum(count for count, _size in order_deviation_by_rule(entries).values())

    by_rule: dict[str, list[Entry]] = {}
    for entry in entries:
        by_rule.setdefault(entry.issue_id, []).append(entry)

    stale = 0
    file_lines: dict[str, list[str]] = {}
    for block in by_rule.values():
        for entry in block:
            if entry.line < 0 or not entry.error_lines:
                continue
            if entry.file not in file_lines:
                path = REPO_ROOT / "app" / entry.file
                file_lines[entry.file] = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
            lines = file_lines[entry.file]
            snippet = entry.error_lines[0].strip()
            if entry.line > len(lines) or lines[entry.line - 1].strip() != snippet:
                stale += 1
    return misplaced, stale


def _summary_entry(entry: Entry) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(f"* `{entry.issue_id}` `{describe(entry)}`\n")
    except OSError:
        pass


# --------------------------------------------------------------------------
# verify
# --------------------------------------------------------------------------


def cmd_verify(args: argparse.Namespace) -> int:
    baseline_path = Path(args.baseline)
    if not baseline_path.is_absolute():
        baseline_path = REPO_ROOT / baseline_path
    baseline = load_baseline(baseline_path)  # усередині вже є перевірка render() == raw
    broken, cosmetic = verify_entries(baseline, args.baseline)
    if cosmetic:
        print(f"{len(cosmetic)} запис(ів) у файлі мають інший запис сутності (вміст той самий):")
        for line in cosmetic[:3]:
            print(f"  {line}")
    if broken:
        for line in broken[:20]:
            print(f"::error::{line}", file=sys.stderr)
        print(f"::error::{len(broken)} із {len(baseline.entries)} записів форматер не відтворює", file=sys.stderr)
        return 1
    exact = len(baseline.entries) - len(cosmetic)
    misplaced, stale = order_deviation(baseline.entries)
    print(
        f"{args.baseline}: {len(baseline.entries)} записів, з них {exact} відтворюються "
        f"байт-у-байт, {len(cosmetic)} — семантично."
    )
    print(
        f"Порядок: {misplaced} із {len(baseline.entries)} записів стоять не на позиції канонічного "
        f"ключа (міра, на яку спирається insert_anchor); застарілий рядок мають {stale}."
    )
    for rule, (count, size) in sorted(order_deviation_by_rule(baseline.entries).items()):
        if count:
            print(f"  · {rule}: {count} із {size} — блоку цього правила не вистачає канонічного порядку")
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lint_baseline.py",
        description="Ґард зростання app/lint-baseline.xml і генератор його записів (#1222).",
        epilog=USAGE_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--baseline", default=BASELINE_DEFAULT, help=f"шлях до baseline (типово {BASELINE_DEFAULT})")
    subparsers = parser.add_subparsers(dest="command", required=True)

    growth = subparsers.add_parser(
        "check-growth",
        help="падає, якщо записів у baseline побільшало проти базового ref",
    )
    growth.add_argument("base_ref", help="базовий ref, напр. origin/main")
    growth.add_argument("head_ref", nargs="?", default="HEAD", help="ref із новим baseline (типово HEAD)")
    growth.add_argument(
        "--worktree",
        action="store_true",
        help="брати head-бік із робочого дерева (для перевірки ще не закоміченого baseline)",
    )
    growth.set_defaults(func=cmd_check_growth)

    add = subparsers.add_parser("add", help="вставити записи зі звіту lint у відсортовану позицію")
    add.add_argument("--report", default=REPORT_DEFAULT, help=f"XML-звіт lint (типово {REPORT_DEFAULT})")
    add.add_argument("--id", action="append", help="узяти лише це правило (можна кілька разів)")
    add.add_argument(
        "--severity",
        action="append",
        help="severity зі звіту; типово fatal,error (те, на чому падає lintDebug); 'any' — усі",
    )
    add.add_argument("--dry-run", action="store_true", help="показати вставку, не змінюючи файл")
    add.add_argument(
        "--require-changes",
        action="store_true",
        help="вийти з кодом 1, якщо додавати нічого (для скриптів)",
    )
    add.set_defaults(func=cmd_add)

    verify = subparsers.add_parser("verify", help="довести, що кожен запис файлу відтворюється байт-у-байт")
    verify.set_defaults(func=cmd_verify)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except BaselineError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
