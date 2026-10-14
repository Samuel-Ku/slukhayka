#!/usr/bin/env python3
"""Куди йдуть секунди набору `script-tests` — командою, а не на око (#1312).

Що було не так. `python3 scripts/ci_job_time.py` міряє ЧАС джоби там, де діє її
ліміт, і каже, що підлога запасу більше не тримається. Куди саме йдуть секунди,
він не каже — і будь-яке зрізання було б здогадом. Найдешевше місце серія
назвала на око (копіювання фікстур у `test_doc_numbers.FixtureMixin`, #1308) і
свідомо не чіпала. Питання цього механізму: де секунди НАСПРАВДІ.

Механізм робить дві речі, і обидві — дані, а не проза:

  * **міряє набір по модулях.** Команда, тека й шаблон ЧИТАЮТЬСЯ з блоку джоби в
    `ci.yml` — тим самим `ci_job_time.local_command`, що міряє час джоби; модулі
    беруться з теки за шаблоном, а не зі списку в коді. Той самий `discover` і
    той самий шаблон виконує дочірній процес, який записує інтервали КОЖНОГО
    тесту: початок і кінець. Число модуля — це вся секунда модуля: і те, що
    всередині тестів, і те, що ПОЗА ними;
  * **називає найбільших споживачів.** Три найбільші модулі друкуються числом і
    часткою, а проміжки поза тестами — окремим списком: саме там живе
    `setUpClass`, у якому біжать гейти.

Чому не `--durations`. У `unittest` є `--durations 0`, і він друкує час кожного
тесту — але час `setUpClass` у нього НЕ входить. Це перевірено прогоном, а не
прочитано: клас із двома тестами по пів секунди й `setUpClass` на дві секунди
дає три секунди набору й одну секунду в `--durations`. А `setUpClass` — це рівно
те місце, де живуть гейти: `test_verdict_field_mutants.RepositoryTest` у ньому
жене `verdict_field_mutants.py`. Тому `--durations` показав би гейт як дрібного
споживача, а його секунди лишилися б нічийними. Механізм міряє проміжки сам і
відносить проміжок ПЕРЕД тестом до модуля цього тесту.

Межі названі тут, у коді, а не лише в звіті:

  * **число — ЛОКАЛЬНЕ, і воно не сміє видаватися за час джоби.** Набір тут
    біжить на цій машині, яка ділить ядра й памʼять із сусідніми смугами; час
    джоби міряє `ci_job_time` на раннері. Тому позначку друкує ТОЙ САМИЙ
    `ci_job_time.local_label`, що й `--local`: локальне число виходить назовні
    лише разом із навантаженням машини й прямою відмовою зватися часом джоби;
  * **проміжок поза тестами механізм БАЧИТЬ, але не розкладає.** Проміжок між
    кінцем одного тесту й початком наступного — це `tearDownClass` попереднього
    класу ПЛЮС `setUpClass` наступного; розділити їх структурно нема чим, тож
    увесь проміжок віднесено до модуля НАСТУПНОГО тесту. Для модуля це майже
    завжди правильно (клас належить тому самому модулю), на межі двох модулів —
    ні, і це ціна правила, а не недогляд;
  * **час УСЕРЕДИНІ тесту не розкладено.** Механізм бачить, скільки тривав тест,
    і не бачить, на що саме: мутаційний прогін, копіювання дерева й очікування в
    тому самому тесті — одне число;
  * **збір набору — окреме число.** `discover` імпортує всі модулі до першого
    тесту, тож його час не належить жодному модулю; механізм друкує його окремо
    і не розмазує по модулях;
  * **старт інтерпретатора не входить.** Дитина — той самий інтерпретатор
    (`sys.executable`), а не `python3` з PATH, і відлік починається ПІСЛЯ його
    старту: запуск інтерпретатора в число набору не входить;
  * **порядок прогону — той, який дає `unittest`.** Проміжок відноситься до
    наступного тесту в ПОРЯДКУ ПРОГОНУ; паралельного прогону механізм не має —
    він міряв би інший порядок;
  * **механізм не має вимикача й не чіпає `ci.yml`.** Ліміт і команда читаються;
    підняти `timeout-minutes` — рішення власника, а не ліки від червоного;
  * **ці межі не під наглядом `named_boundaries`.** Той механізм читає лише свій
    `SECTIONS`, і цього розділу в ньому немає: межі тут названі в коді, але
    свідка-мутації на кожну з них не має — і механізм цього не вдає. Сам факт
    нового заголовка меж у дереві не проходить тихо: його рахує
    `test_named_boundaries.RegistryTest
    .test_the_unreadable_boundary_sections_are_the_named_ones`, і цей розділ
    змінив те число видимо.
  * **порожній модуль — знахідка (1), і вона не зарахована окремим сторожем.**
    Модуль, який є в теці за шаблоном, але не виконав жодного тесту, не випадає
    з розкладу тихо: він названий у звіті й робить код 1. Свідка-мутації на цю
    гілку в `guard_witnesses.py` немає — реєстр покриває НАЗВАНИХ, а не всіх, і
    це названа межа, а не тиха згода: 41-й запис вимагав би або «41 записів»
    (неграмотно), або однини в таблиці `doc_numbers`, якої там навмисно немає;

Коди виходу: 0 — розклад виведено, і кожне число позначене як локальне; 1 —
знахідка (тест не належить жодному модулю з теки, модуль за шаблоном без жодного
тесту, арифметика розкладу не сходиться); 2 — перевірити не вдалося (немає
`ci.yml` або блоку джоби, команда не є `-m unittest discover`, прапорець невідомий,
немає теки чи шаблону, шаблон не називає жодного модуля, `--module` не з теки,
прогін вийшов червоним або не вклався в час, інтервалів не розібрано, навантаження
машини не прочитано).
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import json
import subprocess
import sys
import time
import unittest
from pathlib import Path

import ci_job_time as job_time

ROOT = Path(__file__).resolve().parents[1]

# Джоба, чий набір розкладається: той самий запис, що міряє `ci_job_time`, — щоб
# два механізми не розійшлися в тому, ЩО вони міряють.
JOB_KEY = job_time.JOB_KEY

# Скільки найбільших споживачів механізм називає поіменно. Три — вимога #1312, і
# число стоїть у коді, а не в звіті.
TOP = 3

# Скільки чекати на прогін набору. Довший прогін — невідомість, а не «число є».
RUN_TIMEOUT_SECONDS = 3600

# Проміжок, коротший за це, — не `setUpClass`, а шум виміру: він усе одно входить
# у «поза тестами», але списком проміжків не називається, щоб той список
# лишався читаним. Число назване тут, а не підібране в звіті.
GAP_FLOOR_SECONDS = 0.05

# Ширина колонки модуля в таблиці. Окремою константою, а не `%-NNs`: ширина з
# `s` у рядку формату — це фігура «число + латинська одиниця», яку рахує
# `doc_numbers --audit`, і вона зрушила б число корпусу.
MODULE_WIDTH = 34

# Ознака дитини: той самий файл, але в режимі «прогнати набір і записати
# інтервали». Це не вимикач механізму: без цього режиму батько чисел не має.
TIMED_RUN = "--timed-run"

# Маркер рядка, у якому дитина віддає інтервали. Рядок іде ОСТАННІМ, бо тести
# друкують у той самий stdout, і брати «перший схожий» означало б читати чуже.
MARKER = "SUITE-TIME-JSON:"

# Прапорці `discover`, які механізм розуміє. Невідомий прапорець — невідомість, а
# не «схожа команда»: він міг би звузити набір, і тоді розклад міряв би не той
# набір, що джоба.
DISCOVER_FLAGS = {
    "-s": "start",
    "--start-directory": "start",
    "-p": "pattern",
    "--pattern": "pattern",
    "-t": "top",
    "--top-level-directory": "top",
}


class SuiteTimeError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «розклад є»."""


@dataclasses.dataclass(frozen=True)
class Row:
    """Один модуль у розкладі: уся його секунда, з неї — у тестах."""

    module: str
    seconds: float
    inside: float
    tests: int

    @property
    def outside(self) -> float:
        """Секунди модуля ПОЗА тестами: `setUpClass`, `tearDownClass`, проміжки."""
        return self.seconds - self.inside


@dataclasses.dataclass(frozen=True)
class Gap:
    """Проміжок перед тестом: час, який не належить жодному тесту."""

    seconds: float
    test: str


@dataclasses.dataclass
class Report:
    """Розклад одного прогону: числа, з яких складається вердикт."""

    total: float
    discover: float
    inside: float
    tests: int
    rows: list[Row]
    gaps: list[Gap]
    strangers: list[str]
    empty: tuple[str, ...]

    @property
    def outside(self) -> float:
        """Уся секунда набору поза тестами — сума проміжків."""
        return self.total - self.discover - self.inside


# --------------------------------------------------------------------------
# Команда, тека й шаблон — з блоку джоби, а не зі списку тут
# --------------------------------------------------------------------------


def command(root: Path = ROOT) -> tuple[str, ...]:
    """Команда джоби — з `ci.yml`, тим самим читанням, що міряє час джоби."""
    try:
        return job_time.local_command(root)
    except job_time.JobTimeError as error:
        raise SuiteTimeError(str(error))


def discover_parts(root: Path = ROOT,
                   tokens: tuple[str, ...] | None = None) -> tuple[str, str, str | None]:
    """Тека, шаблон і (якщо є) верхня тека з команди джоби.

    Невідомий прапорець або позиційний аргумент — відмова: команда, якої механізм
    не розібрав, могла звузити набір, і розклад по модулях міряв би не той набір.
    """
    words = list(tokens if tokens is not None else command(root))
    if len(words) < 4 or words[1] != "-m" or words[2] != "unittest" or words[3] != "discover":
        raise SuiteTimeError(
            "команда джоби не є `… -m unittest discover …`: %s" % " ".join(words)
        )
    found: dict[str, str] = {}
    index = 4
    while index < len(words):
        flag = words[index]
        if not flag.startswith("-"):
            raise SuiteTimeError(
                "у команді є позиційний аргумент %r — набір звужено, і розклад по "
                "модулях міряв би не той набір" % flag
            )
        if flag not in DISCOVER_FLAGS:
            raise SuiteTimeError(
                "прапорця %r механізм не знає — він міг би звузити набір, тож "
                "числа немає" % flag
            )
        if index + 1 >= len(words):
            raise SuiteTimeError("прапорець %r без значення" % flag)
        key = DISCOVER_FLAGS[flag]
        if key in found:
            raise SuiteTimeError("прапорець %r названо двічі" % flag)
        found[key] = words[index + 1]
        index += 2
    for key, what in (("start", "теки"), ("pattern", "шаблону")):
        if key not in found:
            raise SuiteTimeError(
                "у команді джоби немає %s — модулів нема з чого взяти" % what
            )
    return found["start"], found["pattern"], found.get("top")


def modules(root: Path, tests_dir: str, pattern: str) -> list[str]:
    """Модулі набору: файли теки за шаблоном — не список у коді."""
    directory = root / tests_dir
    if not directory.is_dir():
        raise SuiteTimeError("теки %s немає — модулів не взяти" % tests_dir)
    try:
        names = sorted(path.name for path in directory.glob(pattern) if path.is_file())
    except (OSError, ValueError) as error:
        raise SuiteTimeError("шаблон %r не розібрано: %s" % (pattern, error))
    if not names:
        raise SuiteTimeError(
            "шаблон %r у %s не називає жодного модуля — розкладати нічого"
            % (pattern, tests_dir)
        )
    return names


def module_of(test_id: str) -> str:
    """Модуль тесту за його id: `test_mutate.Class.test_x` → `test_mutate.py`."""
    return test_id.split(".")[0] + ".py"


# --------------------------------------------------------------------------
# Вимір: дитина жене той самий discover і записує інтервали
# --------------------------------------------------------------------------


class IntervalResult(unittest.TextTestResult):
    """Результат, який до вердикту додає інтервал кожного тесту.

    Інтервал — це `startTest` і кінець тесту; час `setUpClass` у нього НЕ
    входить, і саме тому батько міряє ще й проміжки.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.intervals: list[tuple[str, float, float, str]] = []
        self._started = 0.0

    def startTest(self, test):
        self._started = time.perf_counter()
        super().startTest(test)

    def _record(self, test, state: str) -> None:
        self.intervals.append((test.id(), self._started, time.perf_counter(), state))

    def addSuccess(self, test):
        super().addSuccess(test)
        self._record(test, "ok")

    def addError(self, test, err):
        super().addError(test, err)
        self._record(test, "error")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._record(test, "fail")

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._record(test, "skip")

    def addExpectedFailure(self, test, err):
        super().addExpectedFailure(test, err)
        self._record(test, "expected-failure")

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self._record(test, "unexpected-success")


def timed_child(root: Path, pattern_override: str | None = None) -> int:
    """Дитина: прогнати набір і віддати інтервали рядком-маркером.

    Той самий `discover`, той самий шаблон і та сама тека, що в джобі, — різниця
    лише в класі результату, який міряє інтервали. Червоний прогін інтервалів не
    віддає: час невдалого прогону не міряють.
    """
    try:
        tests_dir, pattern, top = discover_parts(root)
    except SuiteTimeError as error:
        print("НЕВІДОМО: %s" % error, file=sys.stderr)
        return 2
    if pattern_override:
        pattern = pattern_override
    # Той самий шлях, що дає `python3 -m unittest`: корінь дерева на `sys.path`,
    # тож модуль, який робить `from scripts.x import …`, імпортується.
    sys.path.insert(0, str(root))
    loader = unittest.TestLoader()
    started = time.perf_counter()
    suite = loader.discover(tests_dir, pattern=pattern, top_level_dir=top)
    discover_end = time.perf_counter()
    result = unittest.TextTestRunner(verbosity=0, resultclass=IntervalResult).run(suite)
    finished = time.perf_counter()
    record = {
        "started": started,
        "discover_end": discover_end,
        "finished": finished,
        "tests": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "intervals": result.intervals,
    }
    print("%s%s" % (MARKER, json.dumps(record)))
    if result.testsRun == 0:
        return 2
    return 0 if result.wasSuccessful() else 1


def child_command(root: Path, pattern: str | None = None) -> list[str]:
    """Команда дитини: той самий файл, той самий інтерпретатор, корінь — явний."""
    words = [sys.executable, str(Path(__file__).resolve()), "--root", str(root), TIMED_RUN]
    if pattern:
        words += ["--pattern", pattern]
    return words


def parse_record(stdout: str) -> dict:
    """Інтервали з виводу дитини — або невідомість, а не порожній розклад."""
    index = stdout.rfind(MARKER)
    if index < 0:
        raise SuiteTimeError("дитина не надрукувала інтервалів — числа немає")
    line = stdout[index + len(MARKER):].splitlines()[0]
    try:
        record = json.loads(line)
    except ValueError as error:
        raise SuiteTimeError("інтервали не розібрано: %s" % error)
    for key in ("started", "discover_end", "finished", "tests", "intervals"):
        if key not in record:
            raise SuiteTimeError("в інтервалах немає %r" % key)
    if not isinstance(record["intervals"], list):
        raise SuiteTimeError("інтервали не список")
    return record


def run_suite(root: Path, pattern: str | None = None) -> dict:
    """Прогнати набір дитиною й віддати її запис.

    Червоний прогін — невідомість (2), як і в `ci_job_time.local_measurement`:
    час невдалого прогону міряє не роботу, а падіння.
    """
    try:
        result = subprocess.run(
            child_command(root, pattern), cwd=str(root), capture_output=True,
            text=True, check=False, timeout=RUN_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise SuiteTimeError(
            "прогін набору не вклався в %d с — числа немає" % RUN_TIMEOUT_SECONDS
        )
    except OSError as error:
        raise SuiteTimeError("прогін набору не вдався: %s" % error)
    if result.returncode != 0:
        raise SuiteTimeError(
            "прогін набору вийшов із кодом %d — час невдалого прогону не міряють:\n%s"
            % (result.returncode, "\n".join(result.stderr.strip().splitlines()[-3:]))
        )
    return parse_record(result.stdout)


# --------------------------------------------------------------------------
# Розклад: уся секунда модуля, і те, що поза тестами
# --------------------------------------------------------------------------


def decomposition(record: dict, names: list[str]) -> Report:
    """Розкласти запис дитини по модулях.

    Проміжок ПЕРЕД тестом віднесено до модуля цього тесту: `setUpClass` біжить
    саме там, і без цього правила секунди гейтів лишилися б нічийними.
    """
    intervals = record["intervals"]
    if len(intervals) != record["tests"]:
        raise SuiteTimeError(
            "інтервалів %d, а тестів %d — розклад не сходиться"
            % (len(intervals), record["tests"])
        )
    known = set(names)
    seconds: dict[str, float] = collections.defaultdict(float)
    inside: dict[str, float] = collections.defaultdict(float)
    counts: collections.Counter = collections.Counter()
    gaps: list[Gap] = []
    strangers: list[str] = []
    previous = float(record["discover_end"])
    for test, start, end, _state in intervals:
        module = module_of(test)
        # Проміжок ПЕРЕД тестом: `start - previous`. Він і є та секунда, якої
        # `--durations` не бачить, — `setUpClass` наступного класу.
        gap = start - previous
        if module in known:
            seconds[module] += end - previous
            inside[module] += end - start
            counts[module] += 1
            if gap > 0:
                gaps.append(Gap(gap, test))
        else:
            strangers.append(test)
        previous = end
    rows = [
        Row(module, seconds[module], inside[module], counts[module])
        for module in names
        if counts[module]
    ]
    rows.sort(key=lambda row: (-row.seconds, row.module))
    # Модуль, який є в теці за шаблоном, але не виконав жодного тесту, не має
    # жодної секунди — і саме тому його не можна лишити непоміченим: він випав би
    # з розкладу тихо, а разом із ним і його секунди. Це знахідка (1), а не
    # порожній рядок у таблиці.
    empty = tuple(module for module in names if not counts[module])
    return Report(
        total=float(record["finished"]) - float(record["started"]),
        discover=float(record["discover_end"]) - float(record["started"]),
        inside=sum(inside.values()),
        tests=int(record["tests"]),
        rows=rows,
        gaps=sorted(gaps, key=lambda gap: (-gap.seconds, gap.test)),
        strangers=strangers,
        empty=empty,
    )


# --------------------------------------------------------------------------
# Звіт
# --------------------------------------------------------------------------


def percent(part: float, whole: float) -> float:
    """Частка у відсотках; нульовий набір — не ділення на нуль, а відмова."""
    if whole <= 0:
        raise SuiteTimeError("набір тривав нуль — частку не взяти")
    return part * 100.0 / whole


def row_line(index: int, row: Row, whole: float) -> str:
    # Ширина — окремою константою, а не полем формату: `--audit` у `doc_numbers`
    # читає «число + латинська одиниця», і ширина з латинською `s` у рядку формату
    # зрушила б число корпусу (це вже сталося раз на цьому рядку).
    return (
        "  %2d. %s %8.1f с  %5.1f %%  · у тестах %6.1f с · поза тестами %6.1f с "
        "· %d тест(ів)"
        % (index, row.module.ljust(MODULE_WIDTH), row.seconds,
           percent(row.seconds, whole), row.inside, row.outside, row.tests)
    )


def report_lines(root: Path, tests_dir: str, pattern: str, names: list[str],
                 report: Report, label: str) -> list[str]:
    """Розклад: команда, локальна позначка, топ-3 і проміжки."""
    lines = [
        "джоба: %s (`%s`)" % (JOB_KEY, job_time.WORKFLOW),
        "команда: %s" % " ".join(command(root)),
        "тека модулів: %s · шаблон: %s · модулів: %d"
        % (tests_dir, pattern, len(names)),
        label,
        "розклад — ЛОКАЛЬНИЙ: він залежить від машини й навантаження і НЕ є часом "
        "джоби; час джоби міряє `python3 scripts/ci_job_time.py`",
        "",
        "розклад по модулях (уся секунда модуля: і тести, і те, що поза ними):",
    ]
    for index, row in enumerate(report.rows, start=1):
        lines.append(row_line(index, row, report.total))
    top = report.rows[:TOP]
    if top:
        lines.append(
            "найбільші %d: %s — разом %.1f с (%.1f %% набору)"
            % (len(top),
               " · ".join("%s %.1f с" % (row.module, row.seconds) for row in top),
               sum(row.seconds for row in top),
               percent(sum(row.seconds for row in top), report.total))
        )
    lines.append(
        "разом: %.1f с — збір набору %.1f с · у тестах %.1f с · поза тестами %.1f с "
        "· тестів %d" % (report.total, report.discover, report.inside, report.outside,
                         report.tests)
    )
    if report.empty:
        lines.append(
            "модулів без жодного тесту: %d — %s (їхні секунди в розклад не "
            "потрапили, і це знахідка, а не тихий пропуск)"
            % (len(report.empty), ", ".join(report.empty))
        )
    lines.append("")
    lines.append(
        "найбільші проміжки поза тестами (перед першим тестом класу — там біжить "
        "`setUpClass`; механізм бачить ЧАС, а не те, що в ньому):"
    )
    shown = [gap for gap in report.gaps if gap.seconds >= GAP_FLOOR_SECONDS]
    for gap in shown[:TOP]:
        lines.append("  %8.1f с  перед %s" % (gap.seconds, gap.test))
    if not shown:
        lines.append("  (проміжків, довших за %.2f с, немає — уся секунда набору "
                     "всередині тестів)" % GAP_FLOOR_SECONDS)
    return lines


def listing(root: Path = ROOT) -> list[str]:
    """Що механізм покриває — без прогону набору."""
    lines = ["джоба: %s (`%s`)" % (JOB_KEY, job_time.WORKFLOW)]
    try:
        tests_dir, pattern, _top = discover_parts(root)
        names = modules(root, tests_dir, pattern)
    except (SuiteTimeError, OSError) as error:
        lines.append("команда: НЕВІДОМО (%s)" % error)
    else:
        lines.append("команда: %s" % " ".join(command(root)))
        lines.append("тека модулів: %s · шаблон: %s" % (tests_dir, pattern))
        lines.append("модулі: %d — %s" % (len(names), ", ".join(names)))
    lines.append("найбільших споживачів називається: %d" % TOP)
    lines.append(
        "проміжок перед тестом віднесено до модуля цього тесту — саме там живе "
        "`setUpClass`, і без цього правила секунди гейтів були б нічийні"
    )
    lines.append(
        "числа локальні: позначку друкує той самий `ci_job_time.local_label`, що й "
        "`--local`"
    )
    lines.append("коди виходу: 0 — розклад виведено; 1 — знахідка; 2 — невідомість")
    return lines


def success_line(report: Report, mode: str) -> str:
    """Рядок благополуччя — свій у кожного режиму, бо міряно різне."""
    if mode == "module":
        return (
            "OK    модуль %s зміряно окремим прогоном тієї самої команди, звуженої "
            "шаблоном: %.1f с (з них поза тестами %.1f с) — це незалежна перевірка "
            "розкладу, а не той самий прогін"
            % (report.rows[0].module if report.rows else "?", report.total,
               report.outside)
        )
    return (
        "OK    розклад набору виведено: модулів %d, тестів %d; кожне число "
        "позначене як локальне, і жодне не видається за час джоби"
        % (len(report.rows), report.tests)
    )


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Куди йдуть секунди набору `script-tests`: розклад по модулях "
                    "командою, а не на око.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева")
    parser.add_argument("--list", action="store_true",
                        help="лише назвати команду, теку, шаблон і модулі — без прогону")
    parser.add_argument("--module", default=None,
                        help="зміряти ОДИН модуль окремим прогоном (незалежна перевірка)")
    parser.add_argument("--pattern", default=None,
                        help="звузити шаблон (потрібно `--timed-run`)")
    parser.add_argument(TIMED_RUN, dest="timed_run", action="store_true",
                        help="дитина: прогнати набір і віддати інтервали")
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    if arguments.timed_run:
        return timed_child(root, arguments.pattern)

    if arguments.list:
        for line in listing(root):
            print(line)
        return 0

    unknowns: list[str] = []
    findings: list[str] = []
    try:
        tests_dir, pattern, _top = discover_parts(root)
        names = modules(root, tests_dir, pattern)
    except SuiteTimeError as error:
        print("НЕВІДОМО: %s" % error, file=sys.stderr)
        return 2

    mode = "suite"
    if arguments.module:
        wanted = arguments.module if arguments.module.endswith(".py") else \
            arguments.module + ".py"
        if wanted not in names:
            print("НЕВІДОМО: --module %r не з теки %s — міряти нічого"
                  % (arguments.module, tests_dir), file=sys.stderr)
            return 2
        pattern = wanted
        names = [wanted]
        mode = "module"

    try:
        load_before = job_time.machine_facts()[0]
        record = run_suite(root, pattern if mode == "module" else None)
        load, available, total_gb = job_time.machine_facts()
        report = decomposition(record, names)
        label = job_time.local_label(report.total, max(load, load_before),
                                     available, total_gb)
    except (SuiteTimeError, job_time.JobTimeError) as error:
        print("НЕВІДОМО: %s" % error, file=sys.stderr)
        return 2

    if report.strangers:
        findings.append(
            "тестів поза текою %s — %d (напр. %s): їхні секунди не належать жодному "
            "модулю, тож розклад неповний"
            % (tests_dir, len(report.strangers), report.strangers[0])
        )
    if not report.rows:
        unknowns.append("жоден модуль не виконав жодного тесту — розкладу немає")
    if report.empty:
        findings.append(
            "модулів у теці за шаблоном без жодного тесту — %d (%s): їхні секунди в "
            "розклад не потрапили. Модуль, який випав, сховав би свої секунди — "
            "саме тому це знахідка, а не порожній рядок"
            % (len(report.empty), ", ".join(report.empty))
        )
    if report.total <= 0:
        unknowns.append("набір тривав нуль — частку й розклад не взяти")
    if report.inside > report.total + 0.5:
        findings.append(
            "у тестах %.1f с, а весь набір %.1f с — арифметика розкладу не сходиться"
            % (report.inside, report.total)
        )

    if not unknowns:
        for line in report_lines(root, tests_dir, pattern, names, report, label):
            print(line)

    for finding in findings:
        print(finding, file=sys.stderr)
    for unknown in unknowns:
        print("НЕВІДОМО: %s" % unknown, file=sys.stderr)
    if unknowns:
        return 2
    if findings:
        return 1
    print(success_line(report, mode))
    return 0


if __name__ == "__main__":
    sys.exit(main())
