#!/usr/bin/env python3
"""Межа, названа лише в звіті, не існує — тому кожна названа межа має ПРОГІН (#1278).

Живий приклад, з якого це почалося. Смуга #1265 назвала межу — «програму,
присвоєну в хелпері, який кличе `setUp`, ця форма ще не ловить» — У ЗВІТІ.
Незалежний перевіряч довів, що діра справжня (модуль зелений, тест-користувач
червоний), і окремо — що в коді про неї не було ні слова, а рядки поруч
обіцяли сильніше, ніж код робив. Звіт не живе в репозиторії: наступна смуга
його не прочитає, рецензент не побачить, `git log` не згадає.

Що з цього виводиться, а що ні. «Механізм, який читає звіт», не існує і не може
існувати: вхід будь-якого механізму в дереві — дерево (`--root` — шлях у
ньому), а звіт у дереві не лежить. Це не спостереження, а показане прогоном:
та сама межа, записана ПОЗА покритим розділом — у тому самому файлі, в іншому
файлі дерева чи в файлі поза коренем, — лишає вердикт зеленим, і жодне статичне
правило цього не змінить (ціну такої спроби репозиторій уже сплатив:
`test_witness_derivation`, #1279). Тому механізм не вдає всеохопності: він
каже, СКІЛЬКОХ меж покриває, і друкує це на кожному зеленому прогоні.

Що він робить, і це рівно те, чого не було в #1265. Межа — це твердження про
СЛІПОТУ: «механізм не ловить форми X». Твердження про сліпоту перевіряється
так само, як будь-яке інше, — ПРОГОНОМ, і з двох боків:

  * свідок межі мусить бути ЗЕЛЕНИЙ БЕЗ мутації: сліпота справжня сьогодні, а
    не обіцяна в прозі;
  * той самий свідок мусить бути ЧЕРВОНИЙ ПІД мутацією, яка межу ЗАКРИВАЄ
    (механізм починає ловити ту саму форму). Зелений під нею означав би, що
    свідок не розрізняє нічого, — а це не свідок, а обіцянка.

Два боки разом — це не «дві перевірки», а одне твердження: межа тим і
відрізняється від прози, що її можна закрити, і коли її закривають
по-справжньому, свідок червоніє в CI, а не через рік у звіті.

Межа мусить жити в КОДІ, і це теж перевіряється, а не обіцяється:

  * `SECTIONS` — розділи шапок, пункти яких механізм БАЧИТЬ. Кожен пункт
    покритого розділу мусить бути чиїмось записом: межа, написана в коді без
    свідка, — відмова з назвою пункту. Саме так механізм ловить ту саму межу,
    яку #1265 назвала лише в звіті (живий приклад — прогоном, у
    `test_named_boundaries.SectionTest`);
  * `claim` кожного запису — якір у тексті: мусить траплятися РІВНО РАЗ і
    лежати в пункті покритого розділу. Межа, чий текст переписали або
    викинули, червонить механізм: межа без тексту — це межа, якої немає;
  * запис без мутації — відмова («межа без мутації — це обіцянка»), і це та
    сама вимога, що варіант (в) тікета.

Чому не «шаблон звіту з полем `названа в коді: файл:рядок`» (варіант (а)
тікета) і не «рецензент відмовляє межі без посилання на код» (варіант (б)).
Обидва — проза, і обидва міряються нулем: шаблону звіту в репозиторії НЕМАЄ
(`.github/` має `ISSUE_TEMPLATE` і workflows, і жодного
`pull_request_template.md`), а звіт смуги — це повідомлення в чат: жоден
механізм у дереві його не читає, жоден гейт його не бачить (`merge-pr.sh`
читає чеки, мітку вердикту й коміти, не прозу), і в `git log` звіт не
потрапляє. Поле, якого ніхто не читає, — це та сама межа в звіті, тільки
довша.

Межі названі тут, у коді, а не лише в звіті:

  * механізм бачить ЛИШЕ перелічені розділи (`SECTIONS`). Межа, названа в
    шапці поза ними — навіть у тому самому файлі, — для нього невидима: її не
    ловить ніщо, і це показано прогоном
    (`test_named_boundaries.SectionTest.test_a_boundary_outside_the_covered_section_is_not_seen`),
    а не обіцяно;
  * межа, названа лише в ЗВІТІ (у чаті, у тілі PR), невидима ЖОДНОМУ механізму
    в дереві: вхід механізму — дерево. Це не замовчана діра, а причина, чому
    межа мусить бути в коді: поки її там немає, її не існує;
  * механізм не перевіряє, що мутація ЗАКРИВАЄ саме ту форму, яку межа
    називає: «закрити» і «зламати щось інше» структурно не розрізнити (та сама
    межа, що в `guard_witnesses`: яку мутацію писати — судження автора).
    Мутація лежить у дифі поряд із межею, і око оглядача бачить її як дані.
    Одну форму цієї спокуси закрито машинно: закриття не сміє правити МОДУЛЬ
    СВІДКА (`scripts/tests/<модуль>.py`) — «зламай свідка, щоб довести, що
    межу видно» не свідчення. Наслідок названий: межа, чий механізм сам
    лежить під `scripts/tests/`, у покритий розділ не потрапляє — її закриття
    мусило б правити модуль свідка;
  * ЗАРАХУВАННЯ РОЗДІЛУ — РУКАМИ: новий розділ меж у шапці сам у `SECTIONS`
    не потрапляє, і ніщо не червоніє, якщо його не додати. Це та сама межа, що
    «зарахування нового сторожа — руками» (`guard_witnesses`) і «перелік
    записів розширюють руками» (`declared_fields`), і вона навмисна: «схоже на
    розділ меж» і «розділ меж» розрізняє оглядач, не AST;
  * пункт, який не можна ні підтвердити, ні закрити, у покритому розділі
    стояти не може: він вимагав би або вигаданої мутації, або винятку в
    реєстрі, а виняток — це та сама обіцянка в прозі, тільки довша. Його
    місце — ПОЗА покритим розділом, назване прозою (і тоді механізм каже,
    що його не бачить), або в реєстрі зі справжнім закриттям;
  * механізм НЕ покриває власний розділ меж (цей) і решту розділів у дереві:
    покритий — рівно той, що в `SECTIONS`, і кожен наступний коштує свідка й
    закриття, НАПИСАНИХ руками. Скільки розділів поза покриттям, тут не
    сказано: це число ніщо не перевіряє, і воно було б обіцянкою; `--list`
    називає покриті, а зелений прогін каже, скільки їх.

Ціна, і вона виміряна, а не обіцяна. Кожна межа реєстру — це два прогони в
копіях дерева (свідок без мутації й під нею), тією ж машинерією, що
`guard_witnesses`: машина, яка питає «що сталося з прогоном», тут та сама, і
другий спосіб питати те саме розійшовся б із першим. Межа друкує свій час —
0.5–1.7 с на межу на цій машині (вона спільна із сусідніми смугами, тож повний
час коливається: виміри давали від 1.1 до 4.3 с на шість меж). Копія дерева —
1.4 MiB на мутанта (`tmp_budget`), живі дерева — не більше за `--jobs`. Реєстр не
розширюється сам і не вдає, що розширився: `--list` називає межі, а зелений
прогін друкує, скільки їх.

АВТОРСЬКА ЦІНА БІЛЬША ЗА МАШИННУ, і це варто сказати вголос: кожна межа коштує
свідка й закриття, які треба НАПИСАТИ. Тому пункт, який ні підтвердити, ні
закрити нічим, у покритий розділ не потрапляє — його місце поза ним, і тоді
механізм каже, що його не бачить (межа в шапці).

Коди виходу: 0 — кожна межа покритого розділу названа в коді, має свідка,
свідок сліпий без мутації й червоний під нею; 1 — знахідка (пункт без запису,
запис без мутації, свідок зелений під закриттям); 2 — перевірити не вдалося
(розділу немає, якір не знайдено чи він не один, ціль не резолвиться, мутація
не застосовується, базовий прогін червоний або без лічильників, механізм
усередині себе, місця на копії немає).
"""

from __future__ import annotations

import argparse
import ast
import dataclasses
import re
import sys
from pathlib import Path

import guard_witnesses as witnesses

ROOT = Path(__file__).resolve().parents[1]
TESTS_DIR = witnesses.TESTS_DIR

# Пункт розділу — це рядок, який починається двома пробілами, зірочкою і
# пробілом; продовження пункту — наступні рядки до наступного такого. Форма
# взята з шапок, які вже є, а не вигадана: розділ меж пишуть саме так.
BULLET_RE = re.compile(r"^  \* ")


class BoundaryError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «меж немає»."""


# --------------------------------------------------------------------------
# Дані: розділи, які механізм бачить, і межі, які в них названі
# --------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Section:
    """Розділ шапки, пункти якого мусять бути записами реєстру."""

    where: str
    heading: str


@dataclasses.dataclass(frozen=True)
class Boundary:
    """Названа межа: якір у тексті, свідок сліпоти й мутація, що її закриває.

    `claim` — не назва, а ТЕКСТ, який мусить лежати в коді: межа без тексту не
    існує, і саме тому якір перевіряється, а не цитується. `witness` — прогін,
    який показує сліпоту (зелений без мутації), `edits` — те, що цю сліпоту
    закриває (той самий прогін під нею червоний).
    """

    id: str
    claim: str
    where: str
    witness: str
    edits: tuple[witnesses.Edit, ...]


# Покриті розділи. Розширюють РУКАМИ, разом із записами: «схоже на розділ меж»
# і «розділ меж» розрізняє оглядач, не AST (межа механізму, шапка).
SECTIONS = (
    Section(
        where="scripts/declared_fields.py",
        heading="Межа названа тут, у коді, а не лише в звіті:",
    ),
)

# Реєстр меж: рівно пункти покритих розділів, і жодного пункту без запису.
# Другий бік — у `test_named_boundaries`, який пінить цей набір буквально.
#
# Хто що тримає (проза для оглядача; машина читає лише дані):
#
#   #1276-list      — межа списку записів: новий запис-оголошення сам у `RECORDS`
#                     не потрапляє. Закриття (мутація) — авто-виявлення датакласів.
#   #1276-run-built — записи, які будує ПРОГІН, у перелік не входять навмисно.
#                     Закриття — дописати такий запис у `RECORDS`.
#   #1276-appearance— пін ловить ПОЯВУ поля, а не його правду. Закриття —
#                     вимагати, щоб піноване поле хтось справді тримав.
#   #1276-prose     — проза в коментарях не перевіряється нічим. Закриття —
#                     почати читати коментарі.
#   #1276-constants — константи й реєстри поза датакласами не поля запису.
#                     Закриття — вважати модульні константи записами теж.
#   #1276-reader    — дві сліпі плями читання AST (поле в базовому класі й
#                     анотація під `if`/`try`). Закриття — читати бази й тіло
#                     наскрізь.
BOUNDARIES = (
    Boundary(
        id="#1276-list",
        claim="механізм бачить ЛИШЕ перелічені записи",
        where="scripts/declared_fields.py",
        witness="test_declared_fields.BoundaryTest"
                ".test_a_new_record_is_not_caught_and_the_limit_is_named",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find="    report = Report()\n    for entry in records:\n",
                replace=(
                    "    report = Report()\n"
                    "    discovered = []\n"
                    '    for path in sorted((root / "scripts").rglob("*.py")):\n'
                    "        try:\n"
                    '            tree = ast.parse(path.read_text(encoding="utf-8"))\n'
                    "        except (SyntaxError, OSError):\n"
                    "            continue\n"
                    "        for node in ast.walk(tree):\n"
                    "            if isinstance(node, ast.ClassDef) and _is_dataclass(node):\n"
                    "                discovered.append((str(path.relative_to(root)), node.name, ()))\n"
                    "    records = tuple(records) + tuple(discovered)\n"
                    "    for entry in records:  # mutated\n"
                ),
            ),
        ),
    ),
    Boundary(
        id="#1276-run-built",
        claim="записи, які будує ПРОГІН",
        where="scripts/declared_fields.py",
        witness="test_named_boundaries.BlindnessTest"
                ".test_a_record_that_a_run_builds_is_outside_the_list_deliberately",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find='    ("scripts/tests/test_fixture_environments.py", "Program", ("at", "marker")),\n',
                replace='    ("scripts/tests/test_fixture_environments.py", "Program", ("at", "marker")),\n'
                        '    ("scripts/tests/test_fixture_environments.py", "NestedRun",\n'
                        '     ("name", "status", "tests", "skipped", "output", "skipped_ids")),\n',
            ),
        ),
    ),
    Boundary(
        id="#1276-appearance",
        claim="пін ловить ПОЯВУ поля, а не його правду",
        where="scripts/declared_fields.py",
        witness="test_named_boundaries.BlindnessTest"
                ".test_a_pinned_field_that_nothing_holds_still_passes",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find="        report.checked += 1\n        if found == tuple(expected):\n",
                replace=(
                    "        report.checked += 1\n"
                    "        source = (root / relative).read_text(encoding=\"utf-8\")\n"
                    "        for name in found:\n"
                    "            if source.count(name) < 2:\n"
                    "                report.findings.append(Finding(\n"
                    "                    where, \"поле %s оголошено, але його ніхто не тримає\" % name))\n"
                    "        if found == tuple(expected):  # mutated\n"
                ),
            ),
        ),
    ),
    Boundary(
        id="#1276-prose",
        claim="проза в коментарях не перевіряється нічим",
        where="scripts/declared_fields.py",
        witness="test_named_boundaries.BlindnessTest"
                ".test_prose_beside_the_registry_is_read_by_nothing",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find="    report = Report()\n    for entry in records:\n",
                replace=(
                    "    report = Report()\n"
                    "    prose = (root / \"scripts/declared_fields.py\").read_text(encoding=\"utf-8\")\n"
                    "    for entry in records:\n"
                    "        for line in prose.splitlines():\n"
                    "            if line.startswith(\"#\") and \" — \" in line:\n"
                    "                named = line.lstrip(\"# \\t\").split()[0]\n"
                    "                if named not in [field for _f, _c, fields in records\n"
                    "                                 for field in fields]:\n"
                    "                    report.findings.append(Finding(\n"
                    "                        \"RECORDS\", \"проза називає поле %s, якого немає\" % named))\n"
                    "                    return report\n"
                    "    for entry in records:  # mutated\n"
                ),
            ),
        ),
    ),
    Boundary(
        id="#1276-constants",
        claim="константи й реєстри ПОЗА датакласами сюди не попадають",
        where="scripts/declared_fields.py",
        witness="test_named_boundaries.BlindnessTest"
                ".test_a_registry_outside_a_dataclass_is_not_a_record",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find="    report = Report()\n    for entry in records:\n",
                replace=(
                    "    report = Report()\n"
                    "    module_level = []\n"
                    "    for path in sorted((root / \"scripts\").rglob(\"*.py\")):\n"
                    "        try:\n"
                    "            tree = ast.parse(path.read_text(encoding=\"utf-8\"))\n"
                    "        except (SyntaxError, OSError):\n"
                    "            continue\n"
                    "        for node in tree.body:\n"
                    "            if (isinstance(node, ast.Assign) and isinstance(node.value, ast.Tuple)\n"
                    "                    and isinstance(node.targets[0], ast.Name)\n"
                    "                    and node.targets[0].id.endswith(\"_FIELDS\")):\n"
                    "                module_level.append(node.targets[0].id)\n"
                    "    for name in module_level:\n"
                    "        report.findings.append(Finding(\n"
                    "            \"RECORDS\", \"реєстр поза датакласом не перелічено: %s\" % name))\n"
                    "    for entry in records:  # mutated\n"
                ),
            ),
        ),
    ),
    Boundary(
        id="#1276-reader",
        claim="дві сліпі плями самого читання, названі, а не замовчані",
        where="scripts/declared_fields.py",
        witness="test_named_boundaries.BlindnessTest"
                ".test_a_field_in_a_base_class_and_one_under_if_are_invisible_to_the_pin",
        edits=(
            witnesses.Edit(
                file="scripts/declared_fields.py",
                find=(
                    "        return tuple(\n"
                    "            statement.target.id\n"
                    "            for statement in node.body\n"
                    "            if isinstance(statement, ast.AnnAssign) "
                    "and isinstance(statement.target, ast.Name)\n"
                    "        )\n"
                ),
                replace=(
                    "        names = [\n"
                    "            statement.target.id\n"
                    "            for statement in ast.walk(node)\n"
                    "            if isinstance(statement, ast.AnnAssign)\n"
                    "            and isinstance(statement.target, ast.Name)\n"
                    "        ]\n"
                    "        for base in node.bases:\n"
                    "            if not isinstance(base, ast.Name):\n"
                    "                continue\n"
                    "            for candidate in ast.walk(tree):\n"
                    "                if isinstance(candidate, ast.ClassDef) and candidate.name == base.id:\n"
                    "                    names.extend(\n"
                    "                        statement.target.id\n"
                    "                        for statement in ast.walk(candidate)\n"
                    "                        if isinstance(statement, ast.AnnAssign)\n"
                    "                        and isinstance(statement.target, ast.Name)\n"
                    "                    )\n"
                    "        return tuple(names)  # mutated\n"
                ),
            ),
        ),
    ),
)

BOUNDARIES_BY_ID = {boundary.id: boundary for boundary in BOUNDARIES}


# --------------------------------------------------------------------------
# Читання розділів і покриття
# --------------------------------------------------------------------------


@dataclasses.dataclass
class Finding:
    """Одна межа, яка не тримається прогоном."""

    where: str
    detail: str


@dataclasses.dataclass
class Report:
    """Що сталося з реєстром: знахідки, те, що спинило перевірку, і прогони."""

    findings: list[Finding] = dataclasses.field(default_factory=list)
    blocked: list[str] = dataclasses.field(default_factory=list)
    shown: list[str] = dataclasses.field(default_factory=list)
    covered_bullets: int = 0


def section_bullets(root: Path, section: Section) -> list[tuple[int, str]]:
    """Пункти розділу: `(рядок, текст)` — рівно те, що написано в шапці.

    Межі розділу — порожній рядок після пунктів. Розділ, якого немає або який
    трапляється не один раз, — невідомість: «пунктів немає» і «розділ
    переписали» різні речі.
    """
    path = root / section.where
    if not path.is_file():
        raise BoundaryError("немає %s — розділу не прочитати" % section.where)
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise BoundaryError("%s не прочитано (%s)" % (section.where, error))
    hits = [index for index, line in enumerate(lines) if line.strip() == section.heading]
    if len(hits) != 1:
        raise BoundaryError(
            "заголовок «%s» трапляється у %s %d раз(и), а мусить рівно раз"
            % (section.heading, section.where, len(hits))
        )
    block: list[tuple[int, str]] = []
    for offset, line in enumerate(lines[hits[0] + 1:]):
        number = hits[0] + 2 + offset
        if not line.strip():
            # Порожній рядок ПІСЛЯ пунктів — кінець розділу; порожній рядок
            # одразу після заголовка — ні (шапки пишуть і так, і так).
            if block:
                break
            continue
        if BULLET_RE.match(line):
            block.append((number, line))
        elif block:
            block[-1] = (block[-1][0], block[-1][1] + "\n" + line)
        else:
            raise BoundaryError(
                "%s:%d — під заголовком «%s» рядок, який не є пунктом (%r): "
                "розділ переписано" % (section.where, number, section.heading, line)
            )
    if not block:
        raise BoundaryError(
            "%s — під заголовком «%s» немає жодного пункту: або розділ переписано, "
            "або межа зникла" % (section.where, section.heading)
        )
    return block


def coverage(root: Path, boundaries: tuple[Boundary, ...] = BOUNDARIES,
             sections: tuple[Section, ...] = SECTIONS) -> Report:
    """Кожен пункт покритого розділу — запис, і кожен запис — пункт.

    Це та половина, якої не було в #1265: межа, написана в коді БЕЗ свідка, —
    знахідка з її текстом, а не тиха проза. Другий бік — теж знахідка: запис
    на межу, якої в коді немає, тримав би саму лише обіцянку.
    """
    report = Report()
    claimed: dict[str, str] = {}
    for section in sections:
        try:
            bullets = section_bullets(root, section)
        except BoundaryError as error:
            report.blocked.append("FAIL  %s" % error)
            return report
        for _number, bullet in bullets:
            owners = [entry for entry in boundaries if entry.claim in bullet]
            report.covered_bullets += 1
            if not owners:
                report.findings.append(Finding(
                    section.where,
                    "межа названа В КОДІ БЕЗ СВІДКА: %s\n"
                    "      Свідка немає — отже, межа обіцяє більше, ніж робить код: "
                    "її або підтверджують прогоном (свідок сліпий без мутації й "
                    "червоний під закриттям), або не називають зовсім."
                    % _first_line(bullet),
                ))
                continue
            if len(owners) > 1:
                report.findings.append(Finding(
                    section.where,
                    "два записи на один пункт (%s): %s"
                    % (", ".join(entry.id for entry in owners), _first_line(bullet)),
                ))
                continue
            claimed[owners[0].id] = bullet

    for entry in boundaries:
        path = root / entry.where
        if not path.is_file():
            report.blocked.append(
                "FAIL  %s: немає %s — межі не прочитати" % (entry.id, entry.where)
            )
            return report
        occurrences = path.read_text(encoding="utf-8").count(entry.claim)
        if occurrences != 1:
            report.blocked.append(
                "FAIL  %s: якір межі трапляється у %s %d раз(и), а мусить рівно раз "
                "(«%s») — текст межі змінився" % (entry.id, entry.where, occurrences,
                                                 entry.claim)
            )
            return report
        if not entry.edits:
            report.findings.append(Finding(
                entry.id,
                "межа без мутації: обіцянка в реєстрі, а не свідчення. Межа мусить "
                "назвати те, що її ЗАКРИВАЄ, — інакше зелений свідок не відрізнити "
                "від свідка, який нічого не перевіряє.",
            ))
        witness_module = "scripts/tests/%s.py" % entry.witness.split(".", 1)[0]
        for edit in entry.edits:
            if edit.file == witness_module:
                report.findings.append(Finding(
                    entry.id,
                    "закриття править МОДУЛЬ СВІДКА (%s): «зламай свідка, щоб довести, що "
                    "межу видно» — не свідчення. Закриття мусить правити МЕХАНІЗМ, який "
                    "межу не ловить; межа, чий механізм сам лежить під `scripts/tests/`, "
                    "у покритий розділ не потрапляє — і це названа межа, не тиха."
                    % edit.file,
                ))
        if entry.id not in claimed:
            report.findings.append(Finding(
                entry.id,
                "запис є, а межі в коді немає: якір лежить поза пунктами покритих "
                "розділів (%s). Межа мусить бути названа там, де її видно."
                % ", ".join(section.where for section in sections),
            ))
    return report


def _first_line(bullet: str) -> str:
    """Перший рядок пункту — щоб знахідка називала межу, а не цитувала шапку."""
    return bullet.splitlines()[0].strip().removeprefix("* ").strip()


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


def check(root: Path, jobs: int = witnesses.DEFAULT_JOBS,
          boundaries: tuple[Boundary, ...] = BOUNDARIES,
          sections: tuple[Section, ...] = SECTIONS,
          run: tuple[Boundary, ...] | None = None) -> Report:
    """Покриття плюс прогони: свідок сліпий без мутації й червоний під нею.

    Прогони — не друга машинерія, а та сама, що в `guard_witnesses`: той самий
    runner, той самий артефакт, та сама трійця (0 зелено · 1 червоно · 3 немає
    вердикту), і той самий бюджет тимчасової ФС. Другий спосіб питати «що
    сталося з прогоном» розійшовся б із першим.

    `run` — те, що ГНАТИ (`--boundary`): звуження прогонів не сміє звужувати
    ПОКРИТТЯ, бо тоді решта пунктів виглядала б як межа без свідка. Покриття
    завжди йде повним реєстром.
    """
    report = coverage(root, boundaries, sections)
    if report.blocked:
        return report
    entries = boundaries if run is None else run
    if not entries:
        report.blocked.append("FAIL  реєстр меж порожній — жодної межі не перевірено.")
        return report

    guards = tuple(
        witnesses.Guard(
            test=entry.witness,
            protects="межа %s: «%s» — свідок мусить бути сліпий без мутації й "
                     "червоний під мутацією, яка її закриває" % (entry.id, entry.claim),
            edits=entry.edits,
        )
        for entry in entries
    )
    inner = witnesses.check(root, TESTS_DIR, guards, jobs)
    by_test = {entry.witness: entry for entry in entries}
    for verdict in inner.verdicts:
        entry = by_test[verdict.guard.test]
        report.shown.append(
            "%s — свідок сліпий без мутації (%s) і червоний під нею"
            % (entry.id, verdict.baseline.detail if verdict.baseline else "")
        )
    for failure in inner.findings:
        entry = next((candidate for candidate in entries
                      if candidate.witness in failure), None)
        if entry is None:
            report.blocked.append("FAIL  %s" % failure)
            continue
        report.findings.append(Finding(
            entry.where,
            "межа %s не тримається прогоном: %s\n"
            "      Межа: «%s»\n"
            "      Далі: або свідок, який справді розрізняє цю сліпоту (червоний під "
            "мутацією, що межу закриває), або межа названа поза покритим розділом — "
            "бо зелений під закриттям свідок не доводить нічого."
            % (entry.id, failure.split(": ", 1)[-1], entry.claim),
        ))
    report.blocked.extend(inner.blocked)
    return report


# --------------------------------------------------------------------------
# Виклик
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Кожна названа в коді межа мусить мати свідка, сліпого без "
                    "мутації й червоного під нею.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева (типово — репозиторій)")
    parser.add_argument("--list", action="store_true", help="лише назвати межі, не гнати свідків")
    parser.add_argument("--boundary", action="append", default=[],
                        help="обмежитись однією межею (її id); можна кілька разів — це "
                             "звуження, а не вимикач: кожна названа все одно перевіряється")
    parser.add_argument("--jobs", type=int, default=witnesses.DEFAULT_JOBS,
                        help="скільки меж гнати одночасно (типово %d); це швидкість, а не "
                             "вимикач" % witnesses.DEFAULT_JOBS)
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    unknown = [name for name in arguments.boundary if name not in BOUNDARIES_BY_ID]
    if unknown:
        print("FAIL  немає в реєстрі: %s (записані: %s)."
              % (", ".join(unknown), ", ".join(BOUNDARIES_BY_ID)), file=sys.stderr)
        return 2
    entries = tuple(BOUNDARIES_BY_ID[name] for name in arguments.boundary) or BOUNDARIES

    if arguments.list:
        for section in SECTIONS:
            print("%s — розділ «%s»" % (section.where, section.heading))
        for entry in entries:
            print("%s — «%s» (%s)" % (entry.id, entry.claim, entry.where))
            for edit in entry.edits:
                print("      закриває: %s: %s" % (edit.file, edit.find.splitlines()[0].strip()))
        return 0

    report = check(root, max(1, arguments.jobs), BOUNDARIES, SECTIONS, entries)
    for shown in report.shown:
        print("OK    %s" % shown)
    for finding in report.findings:
        print("FAIL  %s: %s" % (finding.where, finding.detail), file=sys.stderr)
    for failure in report.blocked:
        print(failure, file=sys.stderr)
    if report.blocked:
        return 2
    if report.findings:
        return 1
    print("OK    меж у реєстрі: %d (показано прогоном: %d), кожна названа в коді, її "
          "свідок сліпий без мутації й червоний під нею; пунктів у покритих "
          "розділах: %d. Межі ПОЗА цими розділами — і названі лише в звіті — механізм "
          "не бачить: це названа межа в шапці, а не повнота."
          % (len(BOUNDARIES), len(report.shown), report.covered_bullets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
