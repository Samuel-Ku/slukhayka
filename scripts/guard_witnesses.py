#!/usr/bin/env python3
"""Кожен сторож мусить мати мутацію, яка доводить, що він ЧЕРВОНІЄ (#1270).

Дев'ятий випадок класу «перевірка, яка не перевіряє» — і найповчальніший:
сторож, написаний проти цього самого класу, сам виявився фальшивим.
`test_measure_head_exists_because_the_docs_cite_it` мав не дати команді з
документа знову стати вигаданою, а тримався на **чужому рядку help**
(`--scan-limit … для --measure-head`): вирізана опція лишала підрядок у `--help`,
і сторож був зелений на зламаному дереві. Те саме питання — «чи цей сторож
справді щось тримає?» — до цього вісім разів знаходив незалежний огляд, не автор
(#1233, #1231, #1239, #1246, #1254, #1257, #1265, і тепер сам сторож).

Механізм тут один: **сторож мусить почервоніти на своїй мутації, і це
перевіряється прогоном, а не словом.** Для кожного записаного сторожа (`GUARDS`)
він будує копію дерева, вносить мутацію, жене названий тест і вимагає, щоб той
був червоний — тим самим прогоном, що й CI
(`python3 -m unittest discover -s scripts/tests -p "test_*.py"`).

Чому не «вимога в процесі» (запис мутації в `.mutations/runs.jsonl`). Запис
потрібен, корисний і лишається (він доводить, що прогін був), але гарантією він
бути не може: `.mutations/` — у `.gitignore`, тож у диф, який читає оглядач,
запис не потрапляє; він живе на машині автора й у CI його немає; і він каже, що
мутація **пройшла**, а не що вона **та сама**, від якої сторож мусить
почервоніти. Перевіряти запис очима — це та сама обіцянка в прозі, тільки
довша. Тут же вердикт не читають, а **відтворюють**: мутація — це дані в дифі
(`find`/`replace` поруч зі назвою сторожа), і CI щоразу доводить її прогоном.

Що перевіряється на кожен запис (усе — відмова, а не зелений):

  * названий тест існує: `test_<модуль>.<Клас>.<тест>` резолвиться у файл
    `scripts/tests/test_<модуль>.py`, у клас і в метод (і прогін це підтверджує:
    ціль, якої немає, лишає «жодного вердикту»);
  * мутація застосовується: кожен `find` трапляється у файлі **рівно один раз**
    (зник `find` — це відмова, а не «ну, отже, вже виправлено»; саме так запис
    старіє видимо, а не тихо);
  * мутація не править **власний модуль сторожа**: «зламай себе, щоб довести, що
    тебе видно» — не свідок;
  * мутант синтаксично цілий (`ast.parse` для Python, `bash -n` для shell):
    інакше «червоний» означав би «інструмент не запускається», а не «сторож
    побачив зміну»;
  * базовий прогін (без мутації) зелений і справді виконав свої тести: червоний
    базовий прогін — невідомість, а не вбитий мутант, і прогін, який виконав нуль
    тестів (або пропустив усі), теж не вердикт — та сама трійця, що в
    `scripts/mutate.sh` (0 убито · 1 вижив · 3 немає вердикту).

Межа названа прямо, і вона в коді, а не лише в звіті:

  * **яку мутацію писати — судження автора.** Механізм не вигадує мутацій: він
    відтворює названі. Сторож, який червоніє на *не тій* мутації, тут пройде —
    розрізнити «мутація зняла те, що сторож пінить» від «мутація зламала щось
    інше» структурно нема чим. Звужує це око оглядача: `find`/`replace` лежать у
    дифі поряд зі сторожем;
  * **зарахування нового сторожа — руками.** Жодне статичне правило не відрізняє
    «схоже на сторожа» від «сторож»: «згадує» і «запускає» — те саме посилання в
    AST, і цей репозиторій уже заплатив за спробу такого виведення
    (`test_fixture_environments`, шість кіл огляду). Тому реєстр явний: новий
    сторож приходить разом із записом, а питання огляду звужується до одного —
    «де його запис?». Ніщо не червоніє, якщо запису не додати: **старі сторожа не
    зараховуються заднім числом**, і механізм цього не вдає — він каже, скількох
    він покриває, а не скількох мав би;
  * **механізм не можна назвати свідком самого себе в тому самому корені**: коли
    він жене сторожа, у оточенні прогону стоїть корінь цієї копії
    (`SLUKHAYKA_GUARD_WITNESS_DEPTH`), і виклик механізму на тому самому корені —
    відмова, а не прогін у себе. Інший корінь (підставлене дерево фікстури) — не
    рекурсія: саме так власні перевірки механізму лишаються свідками.

Коди виходу: 0 — кожен записаний сторож червоний на своїй мутації; 1 — сторож
лишився зеленим (він не пінить того, що обіцяє); 2 — перевірити не вдалося
(невідомість: ціль не резолвиться, мутація не застосовується, мутант не
розібрано, базовий прогін червоний або без вердикту, механізм усередині себе,
місця немає на копії).
"""

from __future__ import annotations

import argparse
import ast
import dataclasses
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import tmp_budget

ROOT = Path(__file__).resolve().parents[1]
TESTS_DIR = Path("scripts/tests")

# Копія дерева, у якій біжить сторож, і вимір її обсягу — з `tmp_budget`: там
# саме копіювання, там і набір тек, які в копію не йдуть.
COPY_ENTRY = tmp_budget.LINK_COPY_ENTRY
COPY_IGNORE = tmp_budget.LINK_COPY_IGNORE
SKIP_DIRS = tmp_budget.LINK_SKIP_DIRS

# Прогін сторожа — той самий, що в `mutate.sh`: той самий runner, той самий
# артефакт, та сама трійця (0 зелено · 1 червоно · 3 немає вердикту). Другий
# спосіб питати «що сталося з прогоном» розійшовся б із першим.
RUNNER = Path("scripts/mutation_python_runner.py")
ARTIFACT_NAME = "TEST-%s.xml"

# Префікс тимчасових дерев механізму: за ним застарілі дерева попередніх падінь
# відрізняються від чужих тек (і прибираються, як у сусідніх механізмів).
TEMP_PREFIX = "slukhayka-guard-"

# Маркер кореня: у оточенні прогону сторожа лежить корінь копії, у якій він
# біжить. Виклик механізму на ТОМУ САМОМУ корені — прогін у себе (відмова);
# інший корінь — підставлене дерево, і це не рекурсія.
RECURSION_MARKER = "SLUKHAYKA_GUARD_WITNESS_DEPTH"

DEFAULT_RUN_TIMEOUT_SECONDS = 240
DEFAULT_JOBS = min(4, os.cpu_count() or 1)

# Ціль — те, що вантажить `python3 -m unittest` у джобі `script-tests`: модуль,
# клас, тест. Той самий запис, що бере `mutate.sh --python`.
TARGET_RE = re.compile(
    r"^(test_[A-Za-z0-9_]+)\.([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$"
)


class GuardError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «сторож підтверджений»."""


def run_timeout() -> int:
    """Скільки чекати на прогін сторожа.

    Змінна вкорочує терпець і потрібна лише тестам, які пінять таймаут: коротший
    таймаут може тільки ПЕРЕТВОРИТИ вердикт у невідомість, тобто додати відмов.
    """
    return int(
        os.environ.get("SLUKHAYKA_GUARD_WITNESS_TIMEOUT")
        or DEFAULT_RUN_TIMEOUT_SECONDS
    )


@dataclasses.dataclass(frozen=True)
class Edit:
    """Одна правка мутанта: `find` у файлі мусить трапитися рівно один раз."""

    file: str
    find: str
    replace: str


@dataclasses.dataclass(frozen=True)
class Guard:
    """Сторож і мутація, на якій він мусить почервоніти."""

    test: str
    protects: str
    edits: tuple[Edit, ...]


@dataclasses.dataclass(frozen=True)
class Outcome:
    """Що сказав один прогін: трійця `mutate.sh` плюс лічильники артефакту."""

    state: str  # green | red | unknown
    tests: int = 0
    skipped: int = 0
    detail: str = ""


@dataclasses.dataclass
class Verdict:
    """Що сталося з одним записом: убито (сторож червоний) чи ні."""

    guard: Guard
    files: tuple[str, ...] = ()
    seconds: float = 0.0
    baseline: Outcome | None = None
    failure: str = ""

    @property
    def killed(self) -> bool:
        return not self.failure


@dataclasses.dataclass
class Report:
    """Результат одного прогону: вердикти по записах і те, що спинило перевірку."""

    verdicts: list[Verdict] = dataclasses.field(default_factory=list)
    blocked: list[str] = dataclasses.field(default_factory=list)

    @property
    def killed(self) -> list[Verdict]:
        return [verdict for verdict in self.verdicts if verdict.killed]

    @property
    def findings(self) -> list[str]:
        return [verdict.failure for verdict in self.verdicts if verdict.failure]


# --------------------------------------------------------------------------
# Реєстр: сторож і мутація, на якій він мусить почервоніти
# --------------------------------------------------------------------------
#
# Записи тут — дані, які читає оглядач: `protects` каже, який симптом
# повернеться, якщо сторож фальшивий, `edits` — що саме ламається. Звузити
# реєстр мовчки не можна: його пінить
# `test_guard_witnesses.RegistryTest.test_the_registry_is_the_named_set`, і
# зникнення запису видно як правку тесту. Розширюють його разом зі сторожем.

GUARDS = (
    Guard(
        test="test_issue_premise.ExtractionTest"
             ".test_measure_head_exists_because_the_docs_cite_it",
        protects="команду `--measure-head`, на яку посилаються документи: вирізана "
                 "опція мусить червонити сторожа, а не лишати підрядок у `--help`",
        edits=(
            Edit(
                file="scripts/issue_premise.py",
                find=(
                    "    source.add_argument(\n"
                    '        "--measure-head",\n'
                    '        metavar="HEAD",\n'
                    '        help="скільки шляхів у тілах тікетів мають таку голову '
                    '(вимір для BRANCH_HEADS)",\n'
                    "    )\n"
                ),
                replace="",
            ),
        ),
    ),
    Guard(
        test="test_fixture_environments.EnvironmentTest"
             ".test_a_named_run_that_is_skipped_is_not_green",
        protects="названий прогін, який перестав ВИКОНУВАТИСЯ: `@unittest.skip` "
                 "лишав код виходу нулем, і ґард оточення був до цього сліпий",
        edits=(
            Edit(
                file="scripts/tests/test_fixture_environments.py",
                find="        return self.status == 0 and bool(self.tests) "
                     "and self.skipped == 0\n",
                replace="        return self.status == 0 and bool(self.tests)  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_verdict_field_mutants.FailClosedTest"
             ".test_a_marker_that_cannot_be_written_is_unknown_and_leaves_no_carrier",
        protects="теку-носій без власника: невдалий маркер мусить лишити по собі "
                 "відмову (код 2), а не сміття, яке добиває наступний прогін",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find=(
                    "    except OSError:\n"
                    "        shutil.rmtree(created, ignore_errors=True)\n"
                    "        raise\n"
                ),
                replace="    except OSError:\n        raise\n",
            ),
        ),
    ),
    Guard(
        test="test_guard_witnesses.FindingTest"
             ".test_a_guard_that_stays_green_under_its_mutation_is_a_finding",
        protects="вердикт механізму: зелений під мутацією сторож — це відмова, а не "
                 "свідок. Хто ж інакше пінить сам механізм, як не він сам",
        edits=(
            Edit(
                file="scripts/guard_witnesses.py",
                find='        if mutant.state == "green":\n',
                replace="        if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_guard_witnesses.RefusalTest"
             ".test_a_mutation_that_no_longer_applies_is_an_unknown",
        protects="старіння запису: мутація, якої більше нема де застосувати, — "
                 "відмова (код 2), а не тихо пройдений сторож",
        edits=(
            Edit(
                file="scripts/guard_witnesses.py",
                find="    if occurrences != 1:\n",
                replace="    if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_guard_witnesses.RefusalTest"
             ".test_a_mutation_that_edits_the_guards_own_test_is_refused",
        protects="«зламай себе, щоб довести, що тебе видно»: мутація в рядках самого "
                 "тесту сторожа — не свідок",
        edits=(
            Edit(
                file="scripts/guard_witnesses.py",
                find="        if edit.file == guard_relative and inside_test(text, edit.find, "
                     "span):\n",
                replace="        if False:  # mutated\n",
            ),
        ),
    ),
)

GUARDS_BY_TEST = {guard.test: guard for guard in GUARDS}


# --------------------------------------------------------------------------
# Розбір цілі й застосування мутації
# --------------------------------------------------------------------------


def guard_module(root: Path, test: str) -> tuple[Path, tuple[int, int]]:
    """Модуль сторожа і рядки самого тесту: `test_<модуль>.<Клас>.<тест>`.

    Резолвиться структурою: модуль, клас і метод мусять існувати в тексті
    модуля. Прогін підтвердить це вдруге (тієї цілі, якої немає, `unittest` не
    завантажить), і ці дві перевірки не залежать одна від одної. Рядки потрібні
    третій: мутація не сміє правити САМ ТЕСТ сторожа (див. `apply_edits`).
    """
    match = TARGET_RE.match(test)
    if not match:
        raise GuardError(
            "не ціль unittest: %s (треба test_<модуль>.<Клас>.<тест>)" % test
        )
    module, class_name, method = match.groups()
    path = root / TESTS_DIR / ("%s.py" % module)
    if not path.is_file():
        raise GuardError("немає модуля сторожа %s" % path.relative_to(root))
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"))
    except SyntaxError as error:
        raise GuardError("%s не розібрано (%s) — сторожа не знайти" % (path, error))
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef) or node.name != class_name:
            continue
        for item in node.body:
            if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name == method:
                return path, (item.lineno, item.end_lineno or item.lineno)
        raise GuardError(
            "%s не оголошує %s.%s" % (path.relative_to(root), class_name, method)
        )
    raise GuardError("%s не оголошує класу %s" % (path.relative_to(root), class_name))


def inside_test(text: str, find: str, span: tuple[int, int]) -> bool:
    """Чи трапляється `find` у рядках САМОГО тесту сторожа."""
    start = 0
    while True:
        offset = text.find(find, start)
        if offset < 0:
            return False
        if span[0] <= text.count("\n", 0, offset) + 1 <= span[1]:
            return True
        start = offset + 1


def validate(mutant: str, path: Path) -> None:
    """Мутант мусить бути синтаксично цілим.

    Зламаний мутант червонить сторожа з причини, якої ніхто не перевіряв:
    «червоний» тоді означав би «не запускається», а не «сторож побачив зміну».
    """
    if path.suffix == ".py":
        try:
            ast.parse(mutant)
        except SyntaxError as error:
            raise GuardError("мутант %s не розібрано (%s)" % (path, error))
        return
    if shutil.which("bash") is None:
        raise GuardError("немає bash — синтаксис мутанта %s не перевірити" % path)
    result = subprocess.run(
        ["bash", "-n"], input=mutant, capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise GuardError("мутант %s не проходить bash -n: %s" % (path, result.stderr.strip()))


def apply_edits(root: Path, guard_relative: str, span: tuple[int, int],
                edits: tuple[Edit, ...]) -> tuple[str, ...]:
    """Внести мутацію в копію дерева. Повертає змінені шляхи (від кореня).

    Кожен `find` мусить трапитися рівно один раз: нуль означає, що код, на який
    писався запис, зник, і мовчазне «отже, вже не треба» було б тим самим
    зеленим ні про що. Два збіги — невідомо, який саме ламати.

    Мутація не сміє правити РЯДКИ САМОГО ТЕСТУ сторожа: «зламай себе, щоб
    довести, що тебе видно» — не свідок (досить дописати `self.fail()` у тест, і
    він «червоніє» на чому завгодно). Хелпери того самого модуля — можна, і це
    навмисно: правило сторожа живе в них, і мутація саме правила й доводить, що
    тест його тримає.
    """
    texts: dict[str, str] = {}
    changed: list[str] = []
    for edit in edits:
        path = (root / edit.file).resolve()
        if not path.is_relative_to(root):
            raise GuardError("мутація %s веде поза корінь %s" % (edit.file, root))
        if edit.find == edit.replace:
            raise GuardError("мутація %s нічого не змінює: find і replace ті самі" % edit.file)
        if not path.is_file():
            raise GuardError("мутація %s: немає файлу %s" % (edit.file, edit.file))
        text = texts.get(edit.file)
        if text is None:
            try:
                text = path.read_text(encoding="utf-8")
            except OSError as error:
                raise GuardError("мутація %s: %s" % (edit.file, error))
        if edit.file == guard_relative and inside_test(text, edit.find, span):
            raise GuardError(
                "мутація править САМ ТЕСТ сторожа (%s, рядки %d–%d) — «зламай себе, щоб "
                "довести, що тебе видно» не свідок"
                % (edit.file, span[0], span[1])
            )
        occurrences = text.count(edit.find)
        if occurrences != 1:
            raise GuardError(
                "мутація %s не застосовується: `find` трапляється %d раз(и), а мусить "
                "рівно раз — код, на який писався запис, змінився" % (edit.file, occurrences)
            )
        texts[edit.file] = text.replace(edit.find, edit.replace, 1)
    for relative, text in texts.items():
        path = root / relative
        validate(text, path)
        try:
            path.write_text(text, encoding="utf-8")
        except OSError as error:
            raise GuardError("мутанта %s не записати: %s" % (relative, error))
        changed.append(relative)
    return tuple(changed)


# --------------------------------------------------------------------------
# Прогін сторожа
# --------------------------------------------------------------------------


def read_counts(artifact: Path) -> dict[str, int] | None:
    """Лічильники з JUnit-артефакту прогону. `None` — артефакту немає або він не той."""
    try:
        root = ET.parse(artifact).getroot()
    except (ET.ParseError, OSError):
        return None
    counts = {}
    for key in ("tests", "skipped", "failures", "errors"):
        try:
            counts[key] = int(root.get(key, "0"))
        except (TypeError, ValueError):
            return None
    return counts


def outcome_of(status: int, counts: dict[str, int] | None, output: str) -> Outcome:
    """Що сказав прогін — тією самою трійцею, що `scripts/mutate.sh`.

    «Прогін виконав нуль тестів» і «всі тести пропущені» — не зелень: сторож,
    який не виконався, нічого не доводить. Це та сама межа, що в `mutate.sh`
    (`skipped == tests`), і саме вона закриває скіп-сліпоту ґарда оточення.
    """
    tail = "\n".join(output.strip().splitlines()[-6:])
    if counts is None:
        return Outcome("unknown", 0, 0,
                       "прогін не лишив артефакту (код %d)\n%s" % (status, tail))
    tests, skipped = counts["tests"], counts["skipped"]
    if tests == 0 or skipped == tests:
        return Outcome(
            "unknown", tests, skipped,
            "прогін виконав %d тест(ів), пропущено %d — виконаного немає, тож вердикту "
            "немає\n%s" % (tests, skipped, tail),
        )
    if status != 0 or counts["failures"] or counts["errors"]:
        return Outcome("red", tests, skipped,
                       "червоний (tests=%d failures=%d errors=%d)\n%s"
                       % (tests, counts["failures"], counts["errors"], tail))
    return Outcome("green", tests, skipped, "зелений (tests=%d)" % tests)


def run_guard(copy_root: Path, test: str, artifact_dir: Path, environment: dict) -> Outcome:
    """Один прогін сторожа в копії дерева — через той самий runner, що в `mutate.sh`."""
    artifact = artifact_dir / (ARTIFACT_NAME % test)
    try:
        result = subprocess.run(
            [sys.executable, str(copy_root / RUNNER),
             "--tests-dir", str(copy_root / TESTS_DIR),
             "--target", test, "--artifact", str(artifact)],
            cwd=str(copy_root / TESTS_DIR), capture_output=True, text=True,
            env=environment, timeout=run_timeout(),
        )
    except subprocess.TimeoutExpired:
        return Outcome("unknown", detail="прогін не завершився за %d с" % run_timeout())
    except OSError as error:
        return Outcome("unknown", detail="прогін не вдався: %s" % error)
    return outcome_of(result.returncode, read_counts(artifact), result.stderr)


def guard_environment(home: Path, copy_root: Path) -> dict:
    """Оточення прогону сторожа — своє, а не успадковане (#1265).

    Сторож, який зеленіє лише тоді, коли йому дісталися змінні раннера, — не
    сторож: у CI раннер приносить будь-що (експортовану функцію, `PYTHONPATH`), і
    локальна зелень цього не бачить. `HOME` і `TMPDIR` — свої, щоб прогін не
    писав у репозиторій і не читав чужих налаштувань. Маркер називає корінь
    копії: виклик механізму на тому самому корені — прогін у себе.
    """
    home.mkdir(parents=True, exist_ok=True)
    (home / "tmp").mkdir(exist_ok=True)
    return {
        "PATH": os.pathsep.join(["/usr/local/bin", "/usr/bin", "/bin"]),
        "HOME": str(home),
        "TMPDIR": str(home / "tmp"),
        "LC_ALL": "C",
        "LANG": "C",
        "PYTHONDONTWRITEBYTECODE": "1",
        RECURSION_MARKER: str(copy_root.resolve()),
    }


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


def judge(guard: Guard, root: Path, tests_dir: Path, temporary: Path) -> Verdict | str:
    """Один запис: базовий прогін, мутант і вердикт.

    Невідомість повертається РЯДКОМ, вердикт — `Verdict` (як у сусідніх
    механізмах): «мутація не застосовується» і «сторож червоний без мутації» — це
    не знахідка про фальшивого сторожа, а відмова перевірити, і код виходу в них
    різний (2 проти 1). Змішати їх означало б назвати невідомість знахідкою.
    """
    started = time.monotonic()
    entry: Path | None = None
    try:
        module, span = guard_module(root, guard.test)
        guard_relative = str(module.relative_to(root))
        entry = tmp_budget.claim(temporary, TEMP_PREFIX)

        baseline_dir = entry / "baseline"
        baseline_dir.mkdir()
        baseline_root = tmp_budget.link_tree(
            root, baseline_dir, copy_entry=COPY_ENTRY, ignore=COPY_IGNORE, skip_dirs=SKIP_DIRS
        )
        baseline = run_guard(baseline_root, guard.test, baseline_dir,
                             guard_environment(entry / "baseline-home", baseline_root))
        if baseline.state == "red":
            return ("FAIL  %s: сторож червоний БЕЗ мутації (%s) — зеленого мутанта тут не "
                    "відрізнити від зламаного сторожа."
                    % (guard.test, baseline.detail))
        if baseline.state == "unknown":
            return "FAIL  %s: базовий прогін не дав вердикту — %s" % (guard.test, baseline.detail)

        mutant_dir = entry / "mutant"
        mutant_dir.mkdir()
        mutant_root = tmp_budget.link_tree(
            root, mutant_dir, copy_entry=COPY_ENTRY, ignore=COPY_IGNORE, skip_dirs=SKIP_DIRS
        )
        try:
            changed = apply_edits(mutant_root, guard_relative, span, guard.edits)
        except GuardError as error:
            return "FAIL  %s: %s" % (guard.test, error)
        mutant = run_guard(mutant_root, guard.test, mutant_dir,
                           guard_environment(entry / "mutant-home", mutant_root))
        if mutant.state == "unknown":
            return "FAIL  %s: прогін мутанта не дав вердикту — %s" % (guard.test, mutant.detail)
        if mutant.state == "green":
            return Verdict(
                guard, files=changed, seconds=time.monotonic() - started, baseline=baseline,
                failure="FAIL  %s — сторож зелений під СВОЄЮ мутацією: він не червоніє, "
                        "тобто не перевіряє того, що обіцяє (%s).\n"
                        "      Мутація: %s\n"
                        "      Далі: або переписати сторожа так, щоб він тримався "
                        "структури, а не підрядка з чужого тексту, або назвати в реєстрі "
                        "мутацію, яка його справді червонить."
                        % (guard.test, guard.protects, ", ".join(changed)),
            )
        return Verdict(guard, files=changed, seconds=time.monotonic() - started,
                       baseline=baseline)
    except (OSError, GuardError) as error:
        # Копіювання дерева, тимчасовий каталог, читання файлу — усе це може не
        # вдатися (диск, права, ліміти). Контракт каже «невідомість — код 2».
        return "FAIL  %s: перевірити не вдалося: %s" % (guard.test, error)
    finally:
        if entry is not None:
            shutil.rmtree(entry, ignore_errors=True)


def check(root: Path, tests_dir: Path = TESTS_DIR, guards: tuple[Guard, ...] | None = None,
          jobs: int = DEFAULT_JOBS) -> Report:
    """Кожен записаний сторож мусить почервоніти на своїй мутації.

    `guards` — це записи, які перевіряються: типовий виклик (і CI) бере `GUARDS`,
    а власні тести механізму підставляють свої, щоб пінити кожну відмову окремо.
    Підстановка не звужує типовий прогін: його пінить
    `test_guard_witnesses.RepositoryTest`, який кличе `check` без неї.
    """
    report = Report()
    marker = os.environ.get(RECURSION_MARKER)
    if marker and Path(marker).resolve() == root.resolve():
        report.blocked.append(
            "FAIL  механізм усередині себе: прогін на тому самому корені (%s) не доводить "
            "нічого." % root
        )
        return report
    entries = GUARDS if guards is None else guards
    if not entries:
        report.blocked.append("FAIL  реєстр порожній — жодного сторожа не перевірено.")
        return report

    try:
        temporary = tmp_budget.prepare(
            tmp_budget.OWN_PREFIXES, tmp_budget.tree_bytes(root / COPY_ENTRY, COPY_IGNORE)
        )
    except tmp_budget.BudgetError as error:
        report.blocked.append("FAIL  %s" % error)
        return report

    with ThreadPoolExecutor(max_workers=max(1, jobs)) as pool:
        outcomes = list(pool.map(lambda guard: judge(guard, root, tests_dir, temporary), entries))
    for outcome in outcomes:
        if isinstance(outcome, str):
            report.blocked.append(outcome)
        else:
            report.verdicts.append(outcome)
    return report


# --------------------------------------------------------------------------
# Виклик
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Кожен сторож мусить мати мутацію, яка доводить, що він червоніє.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева (типово — репозиторій)")
    parser.add_argument("--list", action="store_true", help="лише назвати сторожів, не гнати мутантів")
    parser.add_argument("--guard", action="append", default=[],
                        help="обмежитись одним сторожем (його ціллю); можна кілька разів — "
                             "це звуження, а не вимикач: кожен названий усе одно перевіряється")
    parser.add_argument("--jobs", type=int, default=DEFAULT_JOBS,
                        help="скільки записів гнати одночасно (типово %d); це швидкість, а не "
                             "вимикач — кожен запис однаково перевіряється" % DEFAULT_JOBS)
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    unknown = [name for name in arguments.guard if name not in GUARDS_BY_TEST]
    if unknown:
        # Той самий перелік, що й у перевірочному шляху: `--list` і `--guard` не
        # мають відповідати мовчки на сторожа, якого реєстр не знає.
        print("FAIL  немає в реєстрі: %s (записані: %s)."
              % (", ".join(unknown), ", ".join(GUARDS_BY_TEST)), file=sys.stderr)
        return 2
    entries = tuple(GUARDS_BY_TEST[name] for name in arguments.guard) or GUARDS

    if arguments.list:
        for guard in entries:
            print("%s — %s" % (guard.test, guard.protects))
            for edit in guard.edits:
                print("      %s: %s" % (edit.file, edit.find.splitlines()[0].strip()))
        return 0

    report = check(root, TESTS_DIR, entries, max(1, arguments.jobs))
    for verdict in report.killed:
        print("OK    %s — червоний на мутації %s (%.1f с; базовий прогін %s)"
              % (verdict.guard.test, ", ".join(verdict.files), verdict.seconds,
                 verdict.baseline.detail if verdict.baseline else ""))
    for failure in report.findings:
        print(failure, file=sys.stderr)
    for failure in report.blocked:
        print(failure, file=sys.stderr)
    if report.blocked:
        return 2
    if report.findings:
        return 1
    print("OK    сторожів у реєстрі: %d, кожен червоний на своїй мутації; решта тестів "
          "набору мутацій не має — межа названа в шапці механізму" % len(report.killed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
