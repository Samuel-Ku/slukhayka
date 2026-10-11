#!/usr/bin/env python3
"""Кожне поле, що вирішує вердикт, мусить мати мутацію-свідка (#1260).

Клас, який тут ловиться, — «поле, яке ніщо не тримає»: вердикт `mutate.sh`
(tests/failures/errors/skipped/artifactSha256) читається з артефакту і їде в
запис, і жоден тест не помічає, коли поле стає нулем. Знайдено двічі за одне
коло і обидва рази — незалежним оглядом, не автором:

 - Python-половина: мутація `"errors": "0"` у runner'і давала 20/20 зелених,
   хоч поле відрізняє `killed` від `no-verdict` — свідок, що падає error'ом, із
   справжнім runner'ом дає killed, а з мутантом no-verdict (PR #1257);
 - JVM-половина: `MUTATE_TEST_ERRORS` у підставному `gradlew` був завжди 0, тож
   шлях «errors у артефакті вирішує вердикт» не пінився ніде (той самий PR).

Авторське судження тут не працює — воно й не спрацювало двічі. Тому механізм
сам будує мутанта на КОЖНЕ поле, жене набір-свідок і вимагає, щоб той
почервонів: зелений набір під мутантом означає, що поле нічого не тримає.

Як будується мутант. Поле не має одного місця в коді — воно їде ланцюгом:
продюсер пише його в артефакт, `mutate.sh` читає його звідти, передає в запис,
а запис і правило вердикту його читають. Кожне місце, де значення поля
з'являється, механізм знаходить САМ (не з авторського списку) і на кожне будує
окремого мутанта, який робить рівно одне: поле несе свій нуль. Решта полів і
решта коду не змінюються — тож почервоніння може прийти лише від цього поля.

Види місць (кожен зі своїм правилом — що саме замінюється на нуль поля):

  артефакт-атрибут   Python: ключ поля в словнику атрибутів `ET.Element(
                     "testsuite", …)` — продюсер пише поле в JUnit XML.
                     Нуль — рядок `"0"`: атрибут XML завжди рядок.
  поле-запису        Python: ключ поля в словнику, який оголошує сам запис
                     (він несе `"schema"`). Нуль — `0` для лічильників і `None`
                     для хеша: рівно те, що поле несе, коли його немає.
  поле-відповіді     Python: той самий ключ у будь-якій іншій відповіді
                     (`status` віддає artifactSha256 у своєму JSON). Нуль —
                     `None`.
  читання-поля       Python: `щось.get("<поле>")` — читання поля зі запису.
                     Нуль — `None`.
  читання-артефакту  shell: `root.get("<поле>", "0")` у сніпеті, який витягує
                     лічильники з XML. Нуль — `"0"`.
  передача-в-запис   shell: токен-аргумент `"<поле>=$змінна"` у виклику
                     рекордера. Нуль — `"<поле>=<нуль>"`.
  правило-вердикту   shell: посилання на змінну поля всередині `[[ … ]]` —
                     там поле й вирішує. Нуль — те, чим став би рядок, якби
                     змінна несла нуль.

Чому саме ці поля. `tests`, `failures`, `errors`, `skipped` — чотири
лічильники, з яких `mutate.sh` складає вердикт: немає `tests` — немає вердикту;
`failures`/`errors` більше нуля — вбита; усі `skipped` — «нічого не виконалося»,
теж немає вердикту. `artifactSha256` — не лічильник, а доказ, і він вирішує
інший вердикт: `status` називає XML на диску артефактом мутації лише тоді, коли
хеш збігається із записаним. П'ять полів — це перелік тікета #1260, і кожне з
них справді міняє вердикт.

Межі, названі прямо (кожну доведено тестом або виміром, а не обіцянкою):

 - продюсер JVM-половини — Gradle, і його в дереві немає. JVM-шлях усе одно
   піниться мутантом на читанні артефакту: якщо жоден тест не проведе через
   підставний `gradlew` артефакт із `errors>0`, цей мутант виживає — рівно та
   діра, яку знайшов незалежний огляд #1257. Продюсер Python-половини в дереві
   є (`mutation_python_runner.py`), і його місце мутується як «атрибут артефакту»;
 - змінна, що обчислює хеш (`artifact_sha=$(sha256_of …)`), місцем не є: ім'я
   змінної не дорівнює імені поля, і в'язати їх здогадом про
   camelCase→snake_case механізм відмовляється. Хеш піниться на передачі в
   запис, у полі запису, на читанні поля й на порівнянні;
 - проза місцем не є: ні `detail="… errors=$errors)"`, ні коментар праворуч від
   правила. Поле там нічого не вирішує, і мутант на такій згадці був би
   еквівалентний — тобто виживав би завжди й давав би відмову за причини, якої
   немає. Тому shell сканується з заміненими пробілами коментарями (зсуви
   лишаються), а `detail` не потрапляє під жодне правило місць;
 - «набір виконав 0 тестів» окремою перевіркою не стоїть: цілі мутанта й
   базового прогону ті самі, а модулі-свідки мутація не чіпає, тож зелений
   базовий прогін доводить, що ті самі цілі виконують ті самі тести. Це названо
   в `verdict_of_run`, а не намальовано перевіркою, яку неможливо змусити
   впасти;
 - `validate` не ловить клас, який інакше пройшов би: інструмент, зламаний до
   прогону, червоний і на базовому прогоні. Він робить «убито» неможливим для
   мутанта, який не запускається, і називає причину ДО прогонів — саме це й
   пінить тест (`bash -n` у повідомленні, код 2), а не вигадана мутація;
 - `skipped` вирішує вердикт, але в запис не їде взагалі: його немає ні в
   `build_record`, ні у виклику рекордера, тож місця «поле запису» в нього
   немає — є читання з артефакту, правило вердикту й атрибут артефакту. Це факт
   про інструмент, а не тиха діра механізму: читач запису не відрізнить «усі
   тести пропущені» від «артефакту немає» за жодним полем, лише за `detail`;
 - поле без жодного місця «правило вердикту» — невідомість (код 2), а не зелене
   світло. Досить перенести рішення на іншу змінну (`[[ $failures -gt 0 ||
   $errors -gt 0 ]]` → без `errors`), і кожен мутант на решті місць виживе:
   поле їде в запис, а вердикт від нього вже не залежить. Цю дірку знайшов
   огляд #1263 — «обіцяли шість видів місць, а вимагали лише непорожній
   перелік»;

Вимикача немає навмисно: жодного `--skip`, жодної змінної, яка дозволяє.
`--field` і `--jobs` — звуження, не вимикачі: перше зменшує перелік полів (ним
користуються фікстури), друге лише вкорочує чергу. Маркер глибини
(`SLUKHAYKA_VERDICT_FIELD_DEPTH`) ставить сам механізм і може лише ВІДМОВИТИ:
свідок, який жене механізм, не може бути свідком самого себе. Таймаут теж можна
вкоротити змінною — це теж може лише додати відмов.

Хто це тримає. Механізм біжить у наявній джобі `script-tests`
(`python3 -m unittest discover -s scripts/tests -p "test_*.py"`): його жене
`scripts/tests/test_verdict_field_mutants.py`. Свідок фікстури задає оточення
підставного інструмента САМ (свіжим словником, як `scripts/tests/test_mutate.py`),
а не бере успадковане: у CI-раннера в оточенні може бути будь-що — експортована
функція затіняє `dirname`, `repo_root` стає `/`, і `python3` виходить із кодом 2
при незмінному файлі. Саме так цей модуль і почервонів у CI (шість падінь,
`CalledProcessError: … exit status 2`), і саме тому той факт пінить
`EnvironmentTest`. Той самий файл пінить КОЖНУ
перевірку механізму — з копії механізму знімається одна перевірка за раз, і
названий тест мусить почервоніти: вимога червоного набору, червоний базовий
прогін, таймаут, поле без місця, інструмент без свідка, неповна проводка,
синтаксис мутанта, нерозібраний файл, маркер глибини, копія дерева, покриття
всіх полів, кожна ланка пошуку місць, кожен вид місця й те, що мета-тест набору
свідком не буває.

Історичний доказ — на справжній дірі, не на моделі. Стан до #1257 (коміт
06fb1d40) не мав жодного тесту, який провів би через підставний `gradlew`
артефакт із `errors>0`:

  mkdir -p /tmp/hist1260/scripts/tests /tmp/hist1260/app
  for f in scripts/mutate.sh scripts/mutation_trace.py scripts/test_partitions.py \
           scripts/test-all.sh scripts/tests/test_mutate.py .gitignore app/.gitignore; do
    git show 06fb1d40:$f > /tmp/hist1260/$f
  done
  chmod +x /tmp/hist1260/scripts/*.sh
  cp scripts/verdict_field_mutants.py /tmp/hist1260/scripts/
  python3 scripts/verdict_field_mutants.py --root /tmp/hist1260 --field errors

На старому стані це код 1: чотири місця `errors` (читання з артефакту, правило
вердикту, передача в запис, поле запису) живі — «поле errors тут не пінить
жоден тест». На виправленому дереві той самий виклик зелений: `errors` має п'ять
місць, усі вбиті. Те саме повторює фікстура
`scripts/tests/fixtures/verdict_field/{unpinned,pinned}`: у неї байт-у-байт той
самий інструмент, різниця лише в наборі-свідку.

Виклик: scripts/verdict_field_mutants.py [--list] [--field <поле>]... [--jobs N] [--root <шлях>]
Коди виходу: 0 — усі місця всіх полів убиті мутантом; 1 — є живі мутанти
(поле нічого не тримає); 2 — перевірити не вдалося (невідомість).
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
import threading
import tempfile
import time
from concurrent.futures import CancelledError, ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TESTS_DIR = Path("scripts/tests")

# Теки, куди механізм не дивиться: або не код репозиторію, або кеші з копіями
# того самого коду, які дали б той самий мутант удруге.
SKIP_DIRS = {
    ".git", ".gradle", ".gradle-home", ".kotlin", ".mutations", ".robolectric-jars",
    ".scratch", ".worktrees", "build", "node_modules",
}

# Скільки мутантів гнати одночасно. Це швидкість, а не вимикач: кожен мутант
# усе одно буде перевірений — черга лише коротшає.
DEFAULT_JOBS = min(4, os.cpu_count() or 1)

# Скільки чекати на один прогін свідка. Таймаут — це НЕ «мутанта вбито»:
# невідомість мусить коштувати відмову, як і решта невідомостей тут. Змінна
# середовища вкорочує терпець і потрібна лише тестам, які пінять сам таймаут:
# коротший таймаут може лише ПЕРЕТВОРИТИ вердикт у невідомість, тобто додати
# відмов, а не дозволів.
DEFAULT_RUN_TIMEOUT_SECONDS = 240


def run_timeout() -> int:
    return int(os.environ.get("SLUKHAYKA_VERDICT_FIELD_TIMEOUT")
               or DEFAULT_RUN_TIMEOUT_SECONDS)


# Свідку, який сам жене механізм, глибина закриває двері: інакше прогін іде сам
# у себе. Це не вимикач: маркер може лише ВІДМОВИТИ, а не дозволити.
RECURSION_MARKER = "SLUKHAYKA_VERDICT_FIELD_DEPTH"

# Модуль, який жене цей механізм, свідком бути не може — його ім'я виводиться з
# імені самого механізму, щоб перейменування не лишило дірку.
SELF_TEST_MODULE = "test_%s.py" % Path(__file__).stem

# Проводка інструмента: mutate.sh сам називає свої частини
# (`trace_module=$repo_root/scripts/mutation_trace.py`). Список файлів читається
# звідси, а не з авторської константи: у дереві, де частини інші (історичний
# стан), механізм мусить дивитися на справжні, а не на ті, що є сьогодні.
WIRING_FILE = Path("scripts/mutate.sh")
# Лише сталі шляхи: `artifact_path=$repo_root/$artifact_relative/...` — не
# частина інструмента, а шлях артефакту поточного прогону, і змінної в ньому
# механізм не розв'язує.
WIRING_RE = re.compile(r"^(\w+)=\$repo_root/([A-Za-z0-9_][A-Za-z0-9_./-]*)\s*$", re.MULTILINE)


class FieldError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «полів немає»."""


@dataclasses.dataclass(frozen=True)
class Field:
    """Поле вердикту: чим воно вирішує і як виглядає його нуль."""

    name: str
    decides: str
    python_zero: str
    shell_zero: str


FIELDS = (
    Field(
        name="tests",
        decides="без нього немає вердикту взагалі, і skipped == tests не з чим порівняти",
        python_zero="0",
        shell_zero="0",
    ),
    Field(
        name="failures",
        decides="failures > 0 — мутацію вбито",
        python_zero="0",
        shell_zero="0",
    ),
    Field(
        name="errors",
        decides="errors > 0 — мутацію вбито; без нього свідок, що падає error'ом, "
                "читається як no-verdict",
        python_zero="0",
        shell_zero="0",
    ),
    Field(
        name="skipped",
        decides="skipped == tests — не виконалося нічого, тож вердикту немає",
        python_zero="0",
        shell_zero="0",
    ),
    Field(
        name="artifactSha256",
        decides="status називає XML на диску артефактом мутації лише за цим хешем",
        # Хеша немає — це порожнеча, а не «нульовий хеш»: саме так поле несе
        # себе, коли артефакт не цього прогону.
        python_zero="None",
        shell_zero="",
    ),
)

FIELDS_BY_NAME = {field.name: field for field in FIELDS}

# Словник, який оголошує сам запис, упізнається за цим ключем: без нього
# «поле запису» не відрізнити від будь-якої іншої відповіді.
RECORD_MARKER = "schema"

# Словник атрибутів JUnit-артефакту впізнається за цим елементом.
ARTIFACT_ELEMENT = "testsuite"

# Вид місця, у якому поле й вирішує. Поле без жодного такого місця — не поле
# вердикту: рішення можна перенести на іншу змінну, і жоден мутант на решті
# місць не почервоніє. Тому цей вид не «один із», а вимога (див. plan).
VERDICT_RULE = "правило вердикту"


@dataclasses.dataclass(frozen=True)
class Site:
    """Місце, де значення поля з'являється: вид, межі у файлі, нуль поля."""

    field: Field
    kind: str
    path: Path
    line: int
    start: int
    end: int
    original: str
    replacement: str

    def describe(self) -> str:
        return "%s:%d — %s (%s)" % (self.path, self.line, self.field.name, self.kind)


@dataclasses.dataclass
class Verdict:
    """Що сталося з одним місцем: убито мутанта чи ні."""

    site: Site
    killed_by: list[str] = dataclasses.field(default_factory=list)
    seconds: float = 0.0
    failure: str = ""

    @property
    def killed(self) -> bool:
        return bool(self.killed_by)


@dataclasses.dataclass
class Report:
    """Результат одного прогону: вердикти по місцях і те, що спинило перевірку."""

    sites: list[Site] = dataclasses.field(default_factory=list)
    verdicts: list[Verdict] = dataclasses.field(default_factory=list)
    blocked: list[str] = dataclasses.field(default_factory=list)

    @property
    def findings(self) -> list[str]:
        return [verdict.failure for verdict in self.verdicts if verdict.failure]

    @property
    def killed(self) -> list[Verdict]:
        return [verdict for verdict in self.verdicts if verdict.killed]


# --------------------------------------------------------------------------
# Проводка інструмента
# --------------------------------------------------------------------------


def tool_files(root: Path) -> list[Path]:
    """Файли, з яких складається інструмент, — прочитані з його ж проводки.

    Один рядок `mutate.sh` називає одну частину; список виходить із коду, тож
    він не старіє. Відсутня частина — невідомість: дивитися на половину
    інструмента й казати «усе піниться» означало б брехати.
    """
    wiring = root / WIRING_FILE
    if not wiring.is_file():
        raise FieldError("немає %s — проводку інструмента нема звідки прочитати" % WIRING_FILE)
    text = wiring.read_text(encoding="utf-8")
    relatives = {WIRING_FILE}
    for match in WIRING_RE.finditer(text):
        relatives.add(Path(match.group(2)))
    files = []
    for relative in sorted(relatives):
        path = root / relative
        if not path.is_file():
            raise FieldError("%s називає %s, якого немає — інструмент розібраний не весь"
                             % (WIRING_FILE, relative))
        files.append(path)
    return files


# --------------------------------------------------------------------------
# Пошук місць
# --------------------------------------------------------------------------


def _line_offsets(text: str) -> list[int]:
    """Зсув початку кожного рядка в СИМВОЛАХ — у тих самих, у яких і `text`."""
    offsets = [0]
    for line in text.splitlines(keepends=True):
        offsets.append(offsets[-1] + len(line))
    return offsets


def _span(text: str, offsets: list[int], node: ast.AST) -> tuple[int, int]:
    """Межі вузла в символах `text`.

    `col_offset` у `ast` — це зсув у БАЙТАХ UTF-8, а `text` — рядок символів:
    без перекладу межа їде на кожному не-ASCII символі перед місцем, і мутант
    замінює не той шматок (або рве файл). Переклад іде від початку рядка:
    `line.encode()[:col].decode()`.
    """
    lines = text.splitlines()

    def position(lineno: int, byte_column: int) -> int:
        column = len(lines[lineno - 1].encode("utf-8")[:byte_column].decode("utf-8"))
        return offsets[lineno - 1] + column

    return position(node.lineno, node.col_offset), position(node.end_lineno, node.end_col_offset)


def _constant_key(node: ast.AST, field: str) -> bool:
    return isinstance(node, ast.Constant) and node.value == field


def _dict_role(node: ast.Dict, parents: dict, field: str) -> str:
    """Що це за словник: артефакт, запис чи відповідь `status`."""
    keys = [key.value for key in node.keys if isinstance(key, ast.Constant)]
    parent = parents.get(node)
    if (
        isinstance(parent, ast.Call)
        and len(parent.args) > 1
        and parent.args[1] is node
        and isinstance(parent.args[0], ast.Constant)
        and parent.args[0].value == ARTIFACT_ELEMENT
    ):
        return "атрибут артефакту"
    if RECORD_MARKER in keys:
        return "поле запису"
    return "поле відповіді"


def python_sites(relative: Path, text: str, field: Field) -> list[Site]:
    """Місця поля в Python-файлі: словниковий ключ і читання `get`.

    Порожній файл або `SyntaxError` — невідомість: якщо файл не розібрано,
    «місць немає» й «нічого не знайдено» — різні речі.
    """
    try:
        tree = ast.parse(text)
    except SyntaxError as error:
        raise FieldError("%s не розібрано (%s) — місця поля %s невідомі"
                         % (relative, error, field.name))
    offsets = _line_offsets(text)
    parents: dict = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parents[child] = node

    sites: list[Site] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Dict):
            role = _dict_role(node, parents, field.name)
            for key, value in zip(node.keys, node.values):
                if not _constant_key(key, field.name):
                    continue
                start, end = _span(text, offsets, value)
                zero = field.python_zero
                if role == "атрибут артефакту":
                    # Атрибут JUnit XML — завжди рядок, тож нуль тут рядковий.
                    zero = '"%s"' % field.shell_zero
                sites.append(Site(field, role, relative, value.lineno, start, end,
                                  text[start:end], zero))
        elif (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr == "get"
            and node.args
            and _constant_key(node.args[0], field.name)
        ):
            start, end = _span(text, offsets, node)
            sites.append(Site(field, "читання поля", relative, node.lineno, start, end,
                              text[start:end], field.python_zero))
    return sites


def _blank_comments(text: str) -> str:
    """Той самий текст, але коментарі замінені пробілами — зсуви не зсуваються.

    Місце поля — це код, а не проза. Згадка в рядку `detail` або в коментарі
    праворуч від правила нічого не вирішує, і мутант на ній був би еквівалентним
    (вижив би завжди) — тобто відмовою за причини, якої немає. Пробіли, а не
    вирізання: межі решти місць лишаються тими самими, і жодне місце не
    посувається.
    """
    out: list[str] = []
    for line in text.splitlines(keepends=True):
        quote: str | None = None
        blanking = False
        previous = ""
        for char in line:
            if blanking:
                out.append("\n" if char == "\n" else " ")
                continue
            if quote:
                out.append(char)
                if char == quote:
                    quote = None
            elif char in "'\"":
                quote = char
                out.append(char)
            elif char == "#" and previous in ("", " ", "\t"):
                # Коментар починається лише на початку слова: `$#` і `#` у
                # лапках — це дані, а не коментар.
                blanking = True
                out.append("#")
            else:
                out.append(char)
            previous = char
    return "".join(out)


def _shell_variable(text: str, field: Field) -> str:
    """Ім'я змінної, у якій поле їде далі, — узяте з передачі в запис.

    Здогад про camelCase→snake_case тут не працює: `artifactSha256` у shell —
    це `artifact_sha`, і вигадувати це перетворення означало б будувати мутанта
    на імені, яке ніхто не обіцяв. Тому ім'я читається з токена
    `"<поле>=$змінна"`, а коли токена немає — з `read -r`, де mutate.sh сам
    називає змінні, у які кладе прочитані лічильники.
    """
    transport = re.search(r'"%s=(\$[A-Za-z_][A-Za-z0-9_]*)"' % re.escape(field.name), text)
    if transport:
        return transport.group(1)[1:]
    read = re.search(r"^\s*read -r ([^\n<]*?)<<<", text, re.MULTILINE)
    if read and field.name in read.group(1).split():
        return field.name
    return ""


def shell_sites(relative: Path, text: str, field: Field) -> list[Site]:
    """Місця поля в shell-інструменті: читання з XML, передача в запис, правило."""
    sites: list[Site] = []
    code = _blank_comments(text)
    variable = _shell_variable(code, field)

    pattern = re.compile(r'root\.get\(\s*"%s"\s*,\s*"0"\s*\)' % re.escape(field.name))
    for match in pattern.finditer(code):
        line = code.count("\n", 0, match.start()) + 1
        sites.append(Site(field, "читання з артефакту", relative, line,
                          match.start(), match.end(), match.group(0),
                          '"%s"' % field.shell_zero))

    transport = re.compile(r'"%s=\$[A-Za-z_][A-Za-z0-9_]*"' % re.escape(field.name))
    for match in transport.finditer(code):
        line = code.count("\n", 0, match.start()) + 1
        sites.append(Site(field, "передача в запис", relative, line,
                          match.start(), match.end(), match.group(0),
                          '"%s=%s"' % (field.name, field.shell_zero)))

    if variable:
        # Правило вердикту: посилання на змінну поля в `[[ … ]]`. Нуль — це те,
        # чим став би рядок, якби змінна несла нуль поля: `$tests` із нулем
        # усередині — це `0`, `"$tests"` — це `"0"`, а порожній хеш у
        # `[[ $artifact_sha == … ]]` — це `""` (порожній операнд у `[[` — те
        # саме, але написаний явно, щоб мутант лишався синтаксично цілим).
        quoted = re.compile(r'"\$\{?%s\}?"' % re.escape(variable))
        bare = re.compile(r"\$\{?%s\}?" % re.escape(variable))
        for match in re.finditer(r"^.*\[\[.*$", code, re.MULTILINE):
            for reference in quoted.finditer(match.group(0)):
                start = match.start() + reference.start()
                sites.append(Site(field, VERDICT_RULE, relative,
                                  code.count("\n", 0, start) + 1, start,
                                  match.start() + reference.end(), reference.group(0),
                                  '"%s"' % field.shell_zero))
            stripped = quoted.sub("", match.group(0))
            for reference in bare.finditer(stripped):
                start = match.start() + reference.start()
                zero = field.shell_zero or '""'
                sites.append(Site(field, VERDICT_RULE, relative,
                                  code.count("\n", 0, start) + 1, start,
                                  match.start() + reference.end(), reference.group(0),
                                  zero))
    return sites


def sites_of(root: Path, files: list[Path], field: Field) -> list[Site]:
    """Усі місця одного поля в усіх файлах інструмента."""
    found: list[Site] = []
    for path in files:
        relative = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        if path.suffix == ".py":
            found.extend(python_sites(relative, text, field))
        else:
            found.extend(shell_sites(relative, text, field))
    return sorted(found, key=lambda site: (str(site.path), site.start))


# --------------------------------------------------------------------------
# Мутант і його перевірка на осмисленість
# --------------------------------------------------------------------------


def mutate(text: str, site: Site) -> str:
    """Текст, у якому поле несе свій нуль рівно в цьому місці."""
    return text[:site.start] + site.replacement + text[site.end:]


def validate(mutant: str, path: Path) -> None:
    """Мутант мусить бути синтаксично цілим.

    Зламаний мутант червонить набір із причини, якої механізм не перевіряв:
    «убито» тоді означало б «інструмент не запускається», а не «поле щось
    тримає». Тому синтаксис перевіряється до прогону, і невідомість тут —
    окремий код, а не дозвіл.
    """
    if path.suffix == ".py":
        try:
            ast.parse(mutant)
        except SyntaxError as error:
            raise FieldError("мутант %s не розібрано (%s)" % (path, error))
        return
    if shutil.which("bash") is None:
        raise FieldError("немає bash — синтаксис мутанта %s не перевірити" % path)
    result = subprocess.run(
        ["bash", "-n"], input=mutant, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise FieldError("мутант %s не проходить bash -n: %s" % (path, result.stderr.strip()))


# --------------------------------------------------------------------------
# Свідки
# --------------------------------------------------------------------------


def paired_modules(root: Path, tests_dir: Path, path: Path) -> list[Path]:
    """Модулі, які називають цей файл: свідки, проти яких іде мутант.

    Пошук за іменем файлу — той самий прийом, що `SLUKHAYKA_MUTATE_SCRIPT` у
    самих тестах: якщо модуль імені не називає, він цей файл не запускає і
    свідком бути не може. Модуль самого механізму серед свідків не стоїть: він
    жене механізм, а не перевіряє інструмент.
    """
    modules = []
    for module in sorted((root / tests_dir).glob("test_*.py")):
        if module.name == SELF_TEST_MODULE:
            continue
        if path.name in module.read_text(encoding="utf-8"):
            modules.append(module)
    return modules


def _mentions(node: ast.AST, field: str) -> bool:
    """Чи називає цей код поле — в коді, а не в прозі.

    Рядок у docstring або в повідомленні («every test was skipped») полем не є:
    якщо його рахувати, «вузький» набір стане всім модулем і нічого не
    звузить. Тому збігається або рядковий літерал, рівний імені поля, або
    ідентифікатор, у якому ім'я поля стоїть як слово (MUTATE_TEST_ERRORS).
    """
    for child in ast.walk(node):
        if isinstance(child, ast.Constant) and isinstance(child.value, str):
            if child.value == field:
                return True
        elif isinstance(child, ast.Name) and field.lower() in child.id.lower():
            return True
        elif isinstance(child, ast.Attribute) and field.lower() in child.attr.lower():
            return True
        elif isinstance(child, ast.keyword) and child.arg and field.lower() in child.arg.lower():
            return True
    return False


def runs_the_suite(node: ast.ClassDef) -> bool:
    """Чи цей клас ганяє набір у собі (через `sys.executable -m unittest`).

    Такий клас — свідок НАБОРУ, а не інструмента: він сам підсовує інструментові
    копії й вимагає, щоб укладена в нього мутація почервонила названий тест.
    Порахувати його свідком поля означало б визнати вбитим будь-якого мутанта:
    він вимагає, щоб `intact`-прогін був зелений, а `intact` тут — той самий
    мутант. Мета-тест, який ловить «щось змінилося», не є свідком того, ЩО саме.
    """
    for child in ast.walk(node):
        if not (isinstance(child, ast.Call) and isinstance(child.func, ast.Attribute)):
            continue
        if child.func.attr not in {"run", "Popen", "check_output", "check_call"}:
            continue
        spelled = ast.unparse(child)
        if "sys.executable" in spelled and "unittest" in spelled:
            return True
    return False


def test_targets(module: Path, field: str | None = None) -> list[str]:
    """Тести модуля — усі або лише ті, що називають поле.

    Спільний список для обох фаз: вузька (лише ті, що називають поле) — швидкий
    шлях до вбивства, повна (усі) — те, чим механізм закриває питання, коли
    вузька не вбила. Звузити можна лише ВБИВСТВО, тож хибної відмови звуження
    не робить. Мета-тести набору (`runs_the_suite`) не входять до жодної фази.
    """
    try:
        tree = ast.parse(module.read_text(encoding="utf-8"))
    except SyntaxError as error:
        raise FieldError("%s не розібрано (%s) — свідків поля не знайти" % (module, error))

    # Тестовий клас — той, що (через свої бази в цьому ж модулі) доходить до
    # `unittest.TestCase`. Спільна фікстура на кшталт `MutateFixtureTest` — теж
    # предок, тож прямий пошук `TestCase` у базах не побачив би жодного тесту.
    classes = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.ClassDef)}

    def is_test_class(name: str, seen: frozenset = frozenset()) -> bool:
        if name in seen or name not in classes:
            return False
        for base in classes[name].bases:
            spelled = ast.unparse(base)
            if "TestCase" in spelled:
                return True
            if is_test_class(spelled.split(".")[-1], seen | {name}):
                return True
        return False

    targets = []
    for name, node in classes.items():
        if not is_test_class(name) or runs_the_suite(node):
            continue
        for item in node.body:
            if not isinstance(item, ast.FunctionDef) or not item.name.startswith("test"):
                continue
            if field is not None:
                # Проза не рахується: рядок у docstring або в повідомленні
                # («every test was skipped») полем не є, інакше «вузький» набір
                # стане всім модулем. Збігається або рядковий літерал, рівний
                # імені поля, або ідентифікатор, у якому ім'я поля стоїть як
                # слово (MUTATE_TEST_ERRORS).
                body = [statement for statement in item.body
                        if not (isinstance(statement, ast.Expr)
                                and isinstance(statement.value, ast.Constant))]
                if not any(_mentions(statement, field) for statement in body):
                    continue
            targets.append("%s.%s.%s" % (module.stem, name, item.name))
    return sorted(targets)


# --------------------------------------------------------------------------
# Прогони
# --------------------------------------------------------------------------


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


def _run(work: Path, tests_dir: Path, targets: list[str]) -> tuple[int | None, str]:
    """Один прогін свідка в копії. `None` — прогін не завершився.

    Цілі вантажаться як `test_mutate.Class.test` з каталогу тестів — рівно те,
    що робить джоба script-tests, коли `discover` знаходить модуль.
    """
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment[RECURSION_MARKER] = str(int(environment.get(RECURSION_MARKER) or 0) + 1)
    try:
        result = subprocess.run(
            [sys.executable, "-m", "unittest", *targets],
            cwd=str(work / tests_dir), capture_output=True, text=True,
            env=environment, timeout=run_timeout(),
        )
    except subprocess.TimeoutExpired:
        return None, "прогін не завершився за %d с" % run_timeout()
    except OSError as error:
        return None, "прогін не вдався: %s" % error
    return result.returncode, (result.stderr or result.stdout)[-4000:]


def failing_tests(output: str, targets: list[str]) -> list[str]:
    """Назви тестів, які почервонили, — щоб відмова й доказ називали свідка.

    Лишаються тільки ті, що механізм справді гнав: у AssertionError тесту, який
    запускає інструмент, потрапляє stderr вкладеного прогону разом із його
    `FAIL:`-рядками, і без цього фільтра відмова називала б свідком тест, якого
    в цьому прогоні не було.
    """
    known = set(targets)
    return [name for name in re.findall(r"^(?:FAIL|ERROR): \S+ \((\S+)\)", output, re.MULTILINE)
            if name in known]


def verdict_of_run(status: int | None, output: str) -> tuple[str, str]:
    """Що сказав прогін: `green`, `red` чи невідомість із причиною.

    Невідомість тут — це прогін, який не завершився (таймаут, незапуск): без
    неї «мутанта вбито» не відрізнити від «набір не дійшов до кінця».

    «Набір виконав 0 тестів» окремою перевіркою тут НЕ стоїть навмисно. Цілі
    мутанта й базового прогону — ті самі, а модулі-свідки мутація не чіпає (вона
    живе лише у файлі інструмента), тож зелений базовий прогін доводить, що ті
    самі цілі виконують ті самі тести. Перевірка, яку неможливо змусити впасти,
    — це перевірка, яка нічого не тримає; тут вона названа, а не намальована.
    """
    if status is None:
        return "unknown", output
    return ("green" if status == 0 else "red"), ""


class Runner:
    """Ганяє свідків по копіях дерева й пам'ятає, що вже перевірено зеленим."""

    def __init__(self, root: Path, tests_dir: Path):
        self.root = root
        self.tests_dir = tests_dir
        self._baselines: dict[tuple, str] = {}
        self._lock = threading.Lock()
        self._temporary = tempfile.TemporaryDirectory(prefix="slukhayka-field-")

    def cleanup(self) -> None:
        self._temporary.cleanup()

    def baseline(self, module: Path, targets: list[str]) -> str:
        """Зелений прогін БЕЗ мутації, або причина, чому його немає.

        Без нього «мутанта вбито» не відрізнити від «набір зламаний»: червоний
        набір під мутантом доводить щось лише тоді, коли без мутанта він зелений.
        """
        key = (module.name, tuple(targets))
        with self._lock:
            if key in self._baselines:
                return self._baselines[key]
            tmp = Path(tempfile.mkdtemp(dir=self._temporary.name))
            work = _roots(self.root, tmp)
            status, output = _run(work, self.tests_dir, targets)
            state, why = verdict_of_run(status, output)
            if state == "unknown":
                self._baselines[key] = "набір-свідок %s не дав вердикту: %s" % (module.name, why)
            elif state == "red":
                self._baselines[key] = (
                    "набір-свідок %s червоний БЕЗ мутації — зеленого мутанта тут не "
                    "відрізнити від зламаного набору:\n%s" % (module.name, output)
                )
            else:
                self._baselines[key] = ""
        with self._lock:
            return self._baselines[key]

    def mutant(self, site: Site, module: Path, targets: list[str],
               seconds: list[float]) -> tuple[bool, str, str]:
        """Прогін мутанта. Повертає (червоний, вивід, причина-невідомості)."""
        tmp = Path(tempfile.mkdtemp(dir=self._temporary.name))
        work = _roots(self.root, tmp)
        target_file = work / site.path
        started = time.monotonic()
        try:
            target_file.write_text(mutate(target_file.read_text(encoding="utf-8"), site),
                                   encoding="utf-8")
        except OSError as error:
            return False, "", "мутанта не записати: %s" % error
        status, output = _run(work, self.tests_dir, targets)
        seconds.append(time.monotonic() - started)
        state, why = verdict_of_run(status, output)
        if state == "unknown":
            return False, output, why
        return state == "red", output, ""


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


def phases_for(module: Path, field: str) -> list[tuple[str, list[str]]]:
    """Дві фази свідка: вузька (називає поле), потім повна (увесь модуль).

    Вузька може лише ВБИТИ — зелений вузький набір не дозволяє нічого, і саме
    тому звуження не робить хибної відмови. Повна фаза — те, чим закривається
    питання: поле могло вирішити вердикт і в тесті, який його не називає
    (`assertEqual("killed", record["outcome"])`).
    """
    narrow = test_targets(module, field)
    full = test_targets(module)
    phases = []
    if narrow:
        phases.append(("вузький набір", narrow))
    if full and full != narrow:
        phases.append(("повний модуль", full))
    return phases


def judge_site(site: Site, tests_dir: Path, witnesses: dict, runner: Runner):
    """Один мутант: вердикт або рядок-невідомість.

    Фази йдуть від дешевої до повної, і перше червоне вікно закриває питання:
    мутант, який червонить хоч один тест, — убитий. Якщо не почервонив жоден,
    поле тут не тримає нічого, і це відмова, а не «ну, майже».
    """
    modules = witnesses[site.path]
    if not modules:
        return (
            "FAIL  %s — жоден модуль %s не називає %s, тож мутанта нема кому ловити.\n"
            "      Далі: додай тест, який запускає %s і читає поле %s."
            % (site.describe(), tests_dir, site.path, site.path, site.field.name)
        )
    verdict = Verdict(site)
    seconds: list[float] = []
    done: set = set()
    for module in modules:
        for phase, targets in phases_for(module, site.field.name):
            if not targets or (module.name, tuple(targets)) in done:
                continue
            done.add((module.name, tuple(targets)))
            broken = runner.baseline(module, targets)
            if broken:
                return "FAIL  %s: %s" % (site.describe(), broken)
            red, output, unreadable = runner.mutant(site, module, targets, seconds)
            if unreadable:
                return "FAIL  %s: %s" % (site.describe(), unreadable)
            if red:
                verdict.killed_by.extend(
                    failing_tests(output, targets) or ["%s (%s)" % (module.name, phase)]
                )
                break
        if verdict.killed_by:
            break
    verdict.seconds = max(seconds) if seconds else 0.0
    if not verdict.killed_by:
        verdict.failure = (
            "FAIL  %s — поле %s тут не пінить жоден тест: мутант, який лишає полю "
            "його нуль, дав зелений набір (%s).\n"
            "      Далі: додай свідка, який ВИРОБЛЯЄ поле ненульовим і вимагає, щоб "
            "вердикт це побачив, або прибери місце, якщо поле тут не вирішує."
            % (site.describe(), site.field.name,
               ", ".join(module.name for module in modules))
        )
    return verdict


def plan(root: Path, tests_dir: Path = TESTS_DIR,
         only: list[str] | None = None) -> tuple[list[Site], dict, list[str]]:
    """Що механізм перевірятиме: місця, свідки й те, що вже видно як невідомість.

    Окремо від прогонів, щоб питання «а чи покриє типовий виклик усі поля?»
    можна було поставити без жодного мутанта.
    """
    names = list(only) if only else [field.name for field in FIELDS]
    unknown = [name for name in names if name not in FIELDS_BY_NAME]
    if unknown:
        return [], {}, ["FAIL  поля %s немає серед полів, що вирішують вердикт (%s)."
                        % (", ".join(unknown), ", ".join(FIELDS_BY_NAME))]
    fields = [FIELDS_BY_NAME[name] for name in names]

    try:
        files = tool_files(root)
        found = {field.name: sites_of(root, files, field) for field in fields}
    except (OSError, FieldError) as error:
        return [], {}, ["FAIL  %s" % error]

    blocked: list[str] = []
    sites: list[Site] = []
    for field in fields:
        places = found[field.name]
        if not places:
            # «Поле не знайдено» — не дозвіл, а невідомість: механізм не знає,
            # що мутувати, і не вдає, що поле піниться.
            blocked.append(
                "FAIL  поле %s не знайдено в жодному місці ланцюга (%s) — механізм не знає, "
                "що мутувати."
                % (field.name, ", ".join(str(path.relative_to(root)) for path in files))
            )
        elif not any(site.kind == VERDICT_RULE for site in places):
            # Поле, яке ніде не вирішує, — не поле вердикту: досить перенести
            # рішення на іншу змінну, і мутанти на решті місць лишаться
            # зеленими. Обіцянка «кожне місце ланцюга, в тому числі правило
            # вердикту» мусить триматися, а не мовчати: без цієї вимоги
            # інструмент, де поле їде лише в запис, проходив би як зелений
            # (знахідка огляду #1263).
            blocked.append(
                "FAIL  поле %s має місця (%s), але жодного «%s»: поле, яке ніде не вирішує "
                "вердикт, мутантом не перевірити — перенесення рішення на іншу змінну "
                "лишило б набір зеленим."
                % (field.name, ", ".join(sorted({site.kind for site in places})), VERDICT_RULE)
            )
        sites.extend(places)

    witnesses: dict[Path, list[Path]] = {
        path.relative_to(root): paired_modules(root, tests_dir, path) for path in files
    }
    for site in sites:
        try:
            source = root / site.path
            validate(mutate(source.read_text(encoding="utf-8"), site), site.path)
        except (OSError, FieldError) as error:
            blocked.append("FAIL  %s: %s" % (site.describe(), error))
    return sites, witnesses, blocked


def check(root: Path, tests_dir: Path = TESTS_DIR, only: list[str] | None = None,
          jobs: int = DEFAULT_JOBS) -> Report:
    """Будує мутанта на кожне місце кожного поля і вимагає, щоб набір почервонів."""
    report = Report()
    if os.environ.get(RECURSION_MARKER):
        report.blocked.append(
            "FAIL  механізм усередині механізма (%s=%s) — прогін у себе не доводить "
            "нічого." % (RECURSION_MARKER, os.environ[RECURSION_MARKER])
        )
        return report

    report.sites, witnesses, blocked = plan(root, tests_dir, only)
    report.blocked.extend(blocked)
    if report.blocked:
        return report

    try:
        runner = Runner(root, tests_dir)
    except OSError as error:
        report.blocked.append("FAIL  тимчасового каталогу для мутантів не створити: %s" % error)
        return report
    try:
        with ThreadPoolExecutor(max_workers=max(1, jobs)) as pool:
            def judged(site: Site):
                try:
                    return judge_site(site, tests_dir, witnesses, runner)
                except (OSError, FieldError) as error:
                    # Копіювання дерева, тимчасовий каталог, читання файлу —
                    # усе це може не вдатися (диск, права, ліміти). Контракт
                    # каже «невідомість — код 2», а не «трейсбек і код 1».
                    return "FAIL  %s: перевірити не вдалося: %s" % (site.describe(), error)

            futures = [pool.submit(judged, site) for site in report.sites]
            outcomes = []
            for position, future in enumerate(futures):
                try:
                    outcome = future.result()
                except CancelledError:
                    break
                outcomes.append(outcome)
                if isinstance(outcome, str):
                    # Невідомість спиняє перевірку: решта мутантів уже нічого не
                    # додасть, а прогін, який не завершується, коштував би свого
                    # таймауту ЧЕРГУ разів.
                    for pending in futures[position + 1:]:
                        pending.cancel()
                    break
    finally:
        runner.cleanup()

    for outcome in outcomes:
        if isinstance(outcome, Verdict):
            report.verdicts.append(outcome)
        else:
            report.blocked.append(outcome)
    return report


# --------------------------------------------------------------------------
# Виклик
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Кожне поле, що вирішує вердикт, мусить мати мутацію-свідка.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева (типово — репозиторій)")
    parser.add_argument("--list", action="store_true", help="лише назвати місця, не гнати мутантів")
    parser.add_argument("--field", action="append", default=[],
                        help="обмежитись одним полем; можна кілька разів")
    parser.add_argument("--jobs", type=int, default=DEFAULT_JOBS,
                        help="скільки мутантів гнати одночасно (типово %d); це швидкість, "
                             "а не вимикач — кожне місце однаково перевіряється"
                             % DEFAULT_JOBS)
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    if arguments.list:
        unknown = [name for name in arguments.field if name not in FIELDS_BY_NAME]
        if unknown:
            # Той самий перелік, що й у перевірочному шляху: `--list` не має
            # відповідати мовчки на поле, якого механізм не знає.
            print("FAIL  поля %s немає серед полів, що вирішують вердикт (%s)."
                  % (", ".join(unknown), ", ".join(FIELDS_BY_NAME)), file=sys.stderr)
            return 2
        try:
            files = tool_files(root)
            for field in FIELDS:
                if arguments.field and field.name not in arguments.field:
                    continue
                found = sites_of(root, files, field)
                if not found:
                    print("FAIL  поле %s не знайдено" % field.name)
                    return 2
                for site in found:
                    print("%s — %s" % (site.describe(), site.replacement))
        except (OSError, FieldError) as error:
            print("FAIL  %s" % error, file=sys.stderr)
            return 2
        return 0

    report = check(root, TESTS_DIR, arguments.field or None, max(1, arguments.jobs))
    for verdict in report.killed:
        print("OK    %s убита %s (%.1f с)"
              % (verdict.site.describe(), ", ".join(verdict.killed_by), verdict.seconds))

    for failure in report.findings:
        print(failure, file=sys.stderr)
    if report.blocked:
        for failure in report.blocked:
            print(failure, file=sys.stderr)
        return 2
    if report.findings:
        return 1
    print("OK    місць полів, що вирішують вердикт: %d, усі піняться мутантом"
          % len(report.killed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
