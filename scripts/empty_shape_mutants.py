#!/usr/bin/env python3
"""Порожня форма рішення `case` мусить мати мутацію, яка її знімає (#1256).

Клас, який тут ловиться, — «перевірка, яка не перевіряє»: зелений тест, який
нічого не доводить. П'ятий випадок за дві цілі. У PR #1254 тест пінив ЛІТЕРАЛ
`"detached HEAD"` — одну з форм, яких набуває «гілку не названо», — і не пінив
саму умову. Зупинений `git am` лишає `rebase-apply` БЕЗ файлу `head-name`; у
записі, розділеному табом (а таб — це IFS-whitespace), порожнє поле зникало,
безіменний ребаз читався як ребаз чужої гілки, і гейт тихо зливав. Шапка
скрипта й документ обіцяли відмову — не пінив її ніхто.

Чому саме порожня форма, а не «кожна альтернатива». Альтернативу, яку можна
написати літералом (`--help`, `"Not Found"`), автор фікстури бачить і може
скопіювати в тест. Порожню форму копіювати нема звідки: у лозі її не видно, а
в shell вона ще й зникає під IFS-whitespace — рівно те, що сталося в #1254.
Тому правило вужче за «мутуй усі альтернативи» навмисно: повний набір на
645693a2 — це 14 мутантів і 8 живих, і кожен живий там — або тест на друге
написання того самого прапорця, або альтернатива, досяжна лише зламаним git;
гейт із таким шумом вимикають. Межа названа прямо: альтернативу, яку можна
назвати літералом, цей механізм не пінить, і не вдає, що пінить.

Що робить механізм — для кожного скрипта `*.sh` у дереві:

 1. знаходить arms `case`, де ПОРЯД стоїть порожній шаблон (`''`, `""`) і
    непорожній: код цим каже «нічого» і «ось це» — один вердикт;
 2. на кожну таку форму будує мутанта: прибирає порожні шаблони з arms, тож
    форма провалюється в `*` (або не збігається ні з чим) і вердикт стає іншим;
 3. жене мутанта проти тих модулів `scripts/tests`, які називають цей скрипт, і
    вимагає, щоб набір-свідок ПОЧЕРВОНІВ.

Зелений набір під мутантом означає, що порожню форму не виробляє жоден тест:
твердження «нічого = ось це» нічим не піниться. Це рівно та діра, яку знайшов
незалежний огляд #1254; на історичній парі (скрипт і тест з 9b653ad3) цей
механізм червоний, на виправленій — зелений.

Межі, названі прямо:

 - механізм бачить лише `case`-arms. Ту саму умову, записану як
   `[ -z "$x" ] || [ "$x" = y ]`, він не покриває: щоб покрити, довелося б
   мутувати операнди `-z`/`-n`, а серед них є перевірки внутрішнього стану
   (MERGE_PR_REPO не вказує на копію, порожній рядок у потоці), яких жодна
   фікстура не виробляє, — це вже той шум, якого тікет не хоче;
 - модуль-свідок шукається за іменем скрипта в тексті (той самий прийом, що
   MERGE_PR_GH і SLUKHAYKA_MUTATE_SCRIPT у самих тестах). Модуль, який згадує
   ім'я, але скрипт не запускає, дасть хибну ВІДМОВУ, а не хибний дозвіл —
   тобто помиляється в безпечний бік;
 - невідомість коштує відмову (код 2): нерозібраний `case`, відсутній
   модуль-свідок, червоний набір БЕЗ мутації, прогін, що не завершився;
 - вимикача немає навмисно: жодного `--skip`, жодної змінної, яка дозволяє.
   Маркер глибини (`SLUKHAYKA_EMPTY_SHAPE_DEPTH`) ставить сам механізм і може
   лише ВІДМОВИТИ: гейт, який можна вимкнути, — це побажання, а не механізм.

Виклик: scripts/empty_shape_mutants.py [--list] [--script <шлях>]... [--root <шлях>]
Коди виходу: 0 — усі порожні форми піняться; 1 — є непінені; 2 — перевірити не
вдалося.
"""

from __future__ import annotations

import argparse
import dataclasses
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS_DIR = Path("scripts/tests")

# Теки, куди механізм не дивиться: або не код репозиторію, або кеші з копіями
# тих самих скриптів (`.worktrees/`), які дали б той самий мутант удруге.
SKIP_DIRS = {
    ".git", ".gradle", ".gradle-home", ".kotlin", ".mutations", ".robolectric-jars",
    ".scratch", ".worktrees", "build", "node_modules",
}

# Фікстури тестів — не скрипти репозиторію, а дані для цих самих тестів.
# `scripts/tests/fixtures/empty_shape/unpinned` навмисно НЕ піниться: це стан
# #1254, на якому механізм мусить бути червоним. Решта фікстур перевіряється
# явним шляхом із тестів, а не сканом дерева.
SKIP_PATHS = (Path("scripts/tests/fixtures"),)

# Скільки чекати на один прогін набору-свідка. Таймаут — це НЕ «мутанта вбито»:
# невідомість мусить коштувати відмову, як і решта невідомостей тут.
RUN_TIMEOUT_SECONDS = 240

# Свідку, який сам жене механізм, глибина закриває двері: інакше прогін іде сам
# у себе (перший прогін цього механізму так і скінчився таймаутом і 529 теками в
# /tmp). Це не вимикач: маркер може лише ВІДМОВИТИ, а не дозволити.
RECURSION_MARKER = "SLUKHAYKA_EMPTY_SHAPE_DEPTH"

# Модуль, який жене цей механізм, свідком бути не може — його ім'я виводиться з
# імені самого механізму, щоб перейменування не лишило дірку.
SELF_TEST_MODULE = "test_%s.py" % Path(__file__).stem


class ShapeError(Exception):
    """`case`, який механізм не розібрав. Невідомість, а не «форм немає»."""


@dataclasses.dataclass(frozen=True)
class Alternative:
    """Один шаблон у списку шаблонів arms: текст як у коді + межі у файлі."""

    text: str
    start: int
    end: int

    @property
    def value(self) -> str:
        """Значення шаблону без лапок: `''` і `""` — обидва порожні."""
        body = self.text
        if len(body) >= 2 and body[0] == body[-1] and body[0] in "'\"":
            body = body[1:-1]
        return body

    @property
    def is_empty(self) -> bool:
        return self.value == ""


@dataclasses.dataclass(frozen=True)
class Arm:
    """Один arm `case`: рядок `case`, рядок arms і його альтернативи."""

    case_line: int
    arm_line: int
    alternatives: tuple[Alternative, ...]

    @property
    def empty_shapes(self) -> tuple[Alternative, ...]:
        return tuple(alt for alt in self.alternatives if alt.is_empty)


@dataclasses.dataclass(frozen=True)
class Shape:
    """Порожня форма, яку код прирівняв до названих: те, що мусить бути піненe."""

    arm: Arm
    named: tuple[str, ...]

    @property
    def line(self) -> int:
        return self.arm.arm_line

    def describe(self) -> str:
        return "порожня форма (поряд із %s)" % ", ".join(self.named)


@dataclasses.dataclass
class Verdict:
    """Що сталося з однією порожньою формою: вбито мутанта чи ні."""

    shape: Shape
    killed_by: list[str] = dataclasses.field(default_factory=list)
    seconds: float = 0.0
    failure: str = ""

    @property
    def killed(self) -> bool:
        return bool(self.killed_by)


@dataclasses.dataclass
class Report:
    """Результат одного скрипта: вердикти по формах і те, що спинило перевірку."""

    script: Path
    verdicts: list[Verdict] = dataclasses.field(default_factory=list)
    blocked: list[str] = dataclasses.field(default_factory=list)

    @property
    def findings(self) -> list[str]:
        return [verdict.failure for verdict in self.verdicts if verdict.failure]

    @property
    def killed(self) -> list[Verdict]:
        return [verdict for verdict in self.verdicts if verdict.killed]


def _strip_comment(line: str) -> str:
    """Прибирає коментар, не чіпаючи `#` усередині лапок і в `$#`."""
    out: list[str] = []
    quote: str | None = None
    for index, char in enumerate(line):
        if quote:
            out.append(char)
            if char == quote:
                quote = None
        elif char in "'\"":
            quote = char
            out.append(char)
        elif char == "#" and (not out or out[-1] in " \t"):
            break
        else:
            out.append(char)
    return "".join(out)


def _split_alternatives(span: str, base: int) -> tuple[Alternative, ...]:
    """Розбиває список шаблонів arms на альтернативи, зберігаючи зсуви.

    `|` усередині лапок — не роздільник (`*"HTTP 404"* | *"Not Found"*`).
    """
    raw: list[tuple[int, int, str]] = []
    buffer: list[str] = []
    quote: str | None = None
    start = 0
    for index, char in enumerate(span):
        if quote:
            buffer.append(char)
            if char == quote:
                quote = None
        elif char in "'\"":
            quote = char
            buffer.append(char)
        elif char == "|":
            raw.append((start, index, "".join(buffer)))
            buffer = []
            start = index + 1
        else:
            buffer.append(char)
    raw.append((start, len(span), "".join(buffer)))

    alternatives = []
    for begin, end, text in raw:
        lead = len(text) - len(text.lstrip())
        trail = len(text) - len(text.rstrip())
        alternatives.append(Alternative(text.strip(), base + begin + lead, base + end - trail))
    return tuple(alternatives)


def scan(text: str) -> list[Arm]:
    """Усі arms усіх `case` файлу — з межами, щоб будувати мутантів.

    Fail-closed: якщо `case` на початку рядка не дав розібраного arms, це
    помилка розбору, а не «форм немає» — інакше зламаний парсер тихо виглядав
    би як чистий репозиторій.
    """
    arms: list[Arm] = []
    stack: list[dict] = []
    offset = 0
    cases_on_line_starts = 0
    for lineno, raw in enumerate(text.splitlines(keepends=True), 1):
        line = _strip_comment(raw)
        if re.match(r"\s*case\s", line):
            cases_on_line_starts += 1
        position = 0
        while position < len(line):
            rest = line[position:]
            opening = re.match(r"\s*case\s", rest)
            if opening:
                stack.append({"line": lineno, "expect": True})
                position += opening.end()
                into = re.search(r"\bin\b", line[position:])
                position += into.end() if into else 0
                continue
            closing = re.match(r"\s*esac\b", rest)
            if closing:
                if stack:
                    stack.pop()
                position += closing.end()
                continue
            separator = re.match(r"\s*;;", rest)
            if stack and separator:
                stack[-1]["expect"] = True
                position += separator.end()
                continue
            if stack and stack[-1]["expect"]:
                # Список шаблонів — до `)` верхнього рівня.
                index = 0
                quote: str | None = None
                while index < len(rest):
                    char = rest[index]
                    if quote:
                        if char == quote:
                            quote = None
                    elif char in "'\"":
                        quote = char
                    elif char == ")":
                        break
                    elif rest[index:index + 2] == ";;":
                        break
                    index += 1
                if index < len(rest) and rest[index] == ")":
                    alternatives = _split_alternatives(rest[:index], offset + position)
                    if alternatives and alternatives[0].text:
                        arms.append(Arm(stack[-1]["line"], lineno, alternatives))
                    stack[-1]["expect"] = False
                    position += index + 1
                    continue
            position += 1
        offset += len(raw)

    parsed_cases = {arm.case_line for arm in arms}
    if cases_on_line_starts != len(parsed_cases):
        raise ShapeError(
            "розібрано %d `case` з %d — парсер відстав від мови скрипта"
            % (len(parsed_cases), cases_on_line_starts)
        )
    return arms


def shapes(text: str) -> list[Shape]:
    """Порожні форми: arms, де порожній шаблон стоїть поряд із названими.

    Arm з однією альтернативою нічого не прирівнює — це не форма цього класу.
    """
    found = []
    for arm in scan(text):
        if len(arm.alternatives) < 2:
            continue
        empties = arm.empty_shapes
        if not empties:
            continue
        found.append(Shape(arm, tuple(alt.text for alt in arm.alternatives if not alt.is_empty)))
    return found


def mutate(text: str, shape: Shape) -> str:
    """Текст без порожньої форми: решта шаблонів arms лишається на місці.

    Прибираються ВСІ порожні шаблони arms: якщо в списку стоять і `""`, і `''`,
    зняття одного лишило б форму досяжною, і мутант нічого не доводив би.
    """
    alternatives = shape.arm.alternatives
    kept = [alt.text for alt in alternatives if not alt.is_empty]
    return text[:alternatives[0].start] + " | ".join(kept) + text[alternatives[-1].end:]


def shell_scripts(root: Path) -> list[Path]:
    """Усі `*.sh` дерева, крім тек, де лежать копії того самого коду.

    Пропуски рахуються від кореня, а не від `/`: робоча копія може сама лежати
    в `.worktrees/`, і абсолютний шлях тоді виглядав би як чужий.
    """
    found = []
    for path in sorted(root.rglob("*.sh")):
        relative = path.relative_to(root)
        if SKIP_DIRS & set(relative.parts[:-1]):
            continue
        if any(relative.is_relative_to(skipped) for skipped in SKIP_PATHS):
            continue
        found.append(path)
    return found


def paired_modules(script: Path, tests_dir: Path) -> list[Path]:
    """Модулі, які називають цей скрипт: свідки, проти яких іде мутант.

    Пошук за іменем файлу — той самий прийом, що `MERGE_PR_GH` у тестах: якщо
    модуль імені не називає, він скрипт не запускає і свідком бути не може.
    Модуль самого механізму серед свідків не стоїть: він жене механізм, а не
    перевіряє скрипт, і прогін у себе скінчився б таймаутом.
    """
    name = script.name
    modules = []
    for module in sorted(tests_dir.glob("test_*.py")):
        if module.name == SELF_TEST_MODULE:
            continue
        if name in module.read_text(encoding="utf-8"):
            modules.append(module)
    return modules


def _roots(root: Path, tmp: Path) -> Path:
    """Копія дерева, де `scripts/` справжня, а решта — посилання.

    Мутант живе лише в копії, тож дерево репозиторію не змінюється. Каталоги з
    кешами й `.git` не переносяться: свідку потрібні тести, а не історія.
    """
    work = tmp / "root"
    work.mkdir()
    shutil.copytree(
        root / "scripts",
        work / "scripts",
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
    )
    for entry in root.iterdir():
        if entry.name in {"scripts"} | SKIP_DIRS:
            continue
        (work / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
    return work


def _run_module(work: Path, module: Path, tests_dir: Path) -> tuple[int | None, str]:
    """Один прогін модуля-свідка у копії. `None` — прогін не завершився."""
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment[RECURSION_MARKER] = str(int(environment.get(RECURSION_MARKER) or 0) + 1)
    try:
        result = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", str(tests_dir),
             "-p", module.name],
            cwd=str(work), capture_output=True, text=True,
            env=environment, timeout=RUN_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return None, "прогін не завершився за %d с" % RUN_TIMEOUT_SECONDS
    except OSError as error:
        return None, "прогін не вдався: %s" % error
    return result.returncode, (result.stderr or result.stdout)[-2000:]


def check(script: Path, tests_dir: Path, root: Path) -> Report:
    """Жене мутантів цього скрипта проти свідків.

    Порожній `report.findings` і `report.blocked` — усі порожні форми скрипта
    піняться. Кожна відмова називає форму, причину й те, що з нею робити.
    """
    report = Report(script)
    if os.environ.get(RECURSION_MARKER):
        report.blocked.append(
            "FAIL  %s: механізм усередині механізму (%s=%s) — прогін у себе не доводить "
            "нічого." % (script, RECURSION_MARKER, os.environ[RECURSION_MARKER])
        )
        return report
    try:
        text = script.read_text(encoding="utf-8")
        found = shapes(text)
    except (OSError, ShapeError) as error:
        report.blocked.append("FAIL  %s: %s" % (script, error))
        return report
    if not found:
        return report

    modules = paired_modules(script, root / tests_dir)
    if not modules:
        report.blocked.append(
            "FAIL  %s:%d — %s нічим не піниться: жоден модуль %s не називає %s\n"
            "      (модуль, який називає сам механізм, свідком не буває — він його жене).\n"
            "      Далі: додай тест, який запускає цей скрипт і виробляє порожню форму."
            % (script, found[0].line, found[0].describe(), tests_dir, script.name)
        )
        return report

    with tempfile.TemporaryDirectory(prefix="slukhayka-shape-") as tmp:
        baseline = _roots(root, Path(tmp))
        for module in modules:
            status, output = _run_module(baseline, module, tests_dir)
            if status is None:
                report.blocked.append(
                    "FAIL  %s: набір-свідок %s не дав вердикту (%s) — мутанта нема з чим "
                    "порівнювати." % (script, module.name, output)
                )
                return report
            if status != 0:
                report.blocked.append(
                    "FAIL  %s: набір-свідок %s червоний БЕЗ мутації — зеленого мутанта тут "
                    "не відрізнити від зламаного набору.\n%s" % (script, module.name, output)
                )
                return report

        for shape in found:
            verdict = Verdict(shape)
            mutant_text = mutate(text, shape)
            for module in modules:
                with tempfile.TemporaryDirectory(prefix="slukhayka-shape-") as tmp:
                    work = _roots(root, Path(tmp))
                    (work / script.relative_to(root)).write_text(mutant_text, encoding="utf-8")
                    started = time.monotonic()
                    status, output = _run_module(work, module, tests_dir)
                    verdict.seconds = max(verdict.seconds, time.monotonic() - started)
                if status is None:
                    # Невідомість, не відмова: «мутанта не вбито» і «прогін не
                    # завершився» — різні речі, і код виходу в них різний.
                    report.blocked.append(
                        "FAIL  %s:%d — %s: мутант не дав вердикту (%s)."
                        % (script, shape.line, shape.describe(), output)
                    )
                    return report
                if status != 0:
                    verdict.killed_by.append(module.name)
                    break
            else:
                verdict.failure = (
                    "FAIL  %s:%d — %s не пінить жоден тест: мутант без неї дав зелений "
                    "набір (%s).\n"
                    "      Далі: додай тест, який ВИРОБЛЯЄ порожню форму (значення, якого "
                    "у фікстурі немає), або прибери альтернативу, якщо її не досягти."
                    % (script, shape.line, shape.describe(),
                       ", ".join(module.name for module in modules))
                )
            report.verdicts.append(verdict)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Порожня форма рішення `case` мусить мати мутацію, яка її знімає.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева (типово — репозиторій)")
    parser.add_argument("--list", action="store_true", help="лише назвати форми, не гнати мутантів")
    parser.add_argument("--script", type=Path, action="append", default=[],
                        help="обмежитись одним скриптом (можна кілька разів)")
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()
    scripts = [path.resolve() for path in arguments.script] or shell_scripts(root)
    outside = [script for script in scripts if not script.is_relative_to(root)]
    if outside:
        for script in outside:
            print("FAIL  %s не під коренем %s — шлях мутанта й свідка не зіставити."
                  % (script, root), file=sys.stderr)
        return 2

    if arguments.list:
        for script in scripts:
            try:
                found = shapes(script.read_text(encoding="utf-8"))
            except (OSError, ShapeError) as error:
                print("FAIL  %s: %s" % (script, error), file=sys.stderr)
                return 2
            for shape in found:
                print("%s:%d — %s" % (script.relative_to(root), shape.line, shape.describe()))
        return 0

    failures: list[str] = []
    unknown: list[str] = []
    killed = 0
    for script in scripts:
        report = check(script, TESTS_DIR, root)
        relative = script.relative_to(root)
        for verdict in report.killed:
            print("OK    %s:%d — %s убита %s (%.1f с)"
                  % (relative, verdict.shape.line, verdict.shape.describe(),
                     ", ".join(verdict.killed_by), verdict.seconds))
            killed += 1
        failures.extend(verdict.failure for verdict in report.verdicts if verdict.failure)
        unknown.extend(report.blocked)

    for failure in failures:
        print(failure, file=sys.stderr)
    if unknown:
        for failure in unknown:
            print(failure, file=sys.stderr)
        return 2
    if failures:
        return 1
    print("OK    порожніх форм у рішеннях `case`: %d, усі піняться мутантом" % killed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
