#!/usr/bin/env python3
"""Кожне оголошене поле мусить бути або виведеним, або пінованим (#1276).

Шість перемикачів однієї форми знайшли шість кіл огляду за одну ціль: запис
реєстру міг щось ОГОЛОСИТИ про себе — маркер, режим, свідка, виняток, форму, —
і ніщо не перевіряло, що оголошене правда. Ліки, доведені тричі, — не додавати
сьому перевірку, а прибирати можливість оголошувати: `runs=False` закрили тим,
що **поля не стало**; `witness` **виводився** з місця програми, а потім був
прибраний зовсім (виведення теж тече); `shape_pin` **прибрано**, бо пін доводив,
що поле НАЗИВАЄ, а не що воно ПОРІВНЮЄ.

Цей механізм робить рівно одну річ, і вона не про твердження про світ: він
пінить **набір полів** кожного запису-оголошення в машинерії перевірок. Нове
поле в записі червонить його — автор мусить або назвати, хто це поле тримає,
або прибрати поле. Полів-перемикачів він не шукає й не вдає, що шукає: він не
знає, що поле означає, і не будує на цьому жодного вердикту.

Що тут дані, а що проза — навмисно розділено. Дані — `RECORDS`: перелік
записів і РІВНО ті поля, які в них оголошені (порядок теж: `Program` будують
позиційно). Проза — коментарі біля записів, які кажуть, хто кожне поле пінить,
а хто не пінить ніхто. Проза не стає полем: поле, яке лише НАЗИВАЄ свідка, —
це та сама хвороба (`shape_pin`), і тому свідків у даних немає.

Межа названа тут, у коді, а не лише в звіті:

  * механізм бачить ЛИШЕ перелічені записи. Новий запис-оголошення (нова
    датаклас-форма в машинерії) сюди сам не потрапляє: список розширюють
    руками, і ніщо не червоніє, якщо цього не зробити. Це та сама межа, що
    «зарахування нового сторожа — руками» в `guard_witnesses.py`, і вона
    навмисна: «схоже на запис-оголошення» і «запис-оголошення» — те саме
    посилання в AST, а репозиторій уже заплатив за спробу такого виведення
    (шість кіл огляду `test_fixture_environments`);
  * записи, які будує ПРОГІН (`Site`, `Arm`, `Shape`, `Verdict`, `TestClass`,
    `NestedRun`), сюди не входять навмисно: їх не оголошує автор, їх виробляє
    код, і оголосити в них нічого. Кого в переліку немає — того механізм не
    бачить; межа списку в самому списку;
  * пін ловить ПОЯВУ поля, а не його правду. Поле, додане разом із рядком у
    `RECORDS`, проходить — і це видно в дифі як правка даних, а не як тиха
    згода; довести, що нове поле щось тримає, — робота автора й оглядача, і
    жоден статичний механізм її не зробить;
  * проза в коментарях не перевіряється нічим і не обіцяє нічого: вона каже
    оглядачеві, куди дивитися, і саме тому вона коментар, а не поле запису;
  * константи й реєстри ПОЗА датакласами сюди не попадають — вони не поля
    запису. Одну таку знайдено й ВИВЕДЕНО окремо
    (`test_partitions.web_check_steps` читає кроки з `scripts/test-changed.sh`,
    який їх і виконує), решта названа в тікеті #1276;
  * дві сліпі плями самого читання, названі, а не замовчані: поле, оголошене в
    БАЗОВОМУ класі, механізм не бачить — він читає тіло САМОГО класу, хоч
    `dataclasses.fields` у рантаймі таке поле бачить, тож нове поле в базі
    пройшло б тихо; і анотація, вкладена в `if`/`try` у тілі класу, теж не
    читається. Сьогодні жоден перелічений запис такої форми не має, і це межа
    читання, а не обіцянка його розширити.

Коди виходу: 0 — набір полів кожного запису той самий; 1 — знахідка (нове
оголошене поле або застарілий запис переліку); 2 — перевірити не вдалося (файл
не розібрано, класу немає, він не датаклас).
"""

from __future__ import annotations

import argparse
import ast
import dataclasses
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class DeclaredFieldError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «полів немає»."""


# --------------------------------------------------------------------------
# Дані: записи-оголошення машинерії перевірок
# --------------------------------------------------------------------------
#
# Формат запису: (файл відносно кореня, клас, поля В ПОРЯДКУ оголошення).
# Порядок у даних, а не лише склад: записи будують і позиційно
# (`Program("GRADLEW", …)` у `CASES`), тож перестановка полів міняє їхні
# значення місцями — це частина оголошення.
#
# Хто що тримає (проза для оглядача; жодне з цих слів машина не перевіряє):
#
#   guard_witnesses.Guard
#     test     — набір і резолв пінить `test_guard_witnesses.RegistryTest`
#                (`test_the_registry_is_the_named_set`, `test_every_named_guard_resolves`);
#     edits    — непорожнечість, наявний файл і `find != replace` пінить той самий
#                клас; застосовність рівно один раз і почервоніння — прогін
#                механізму, який щепить `test_guard_witnesses.RepositoryTest`;
#     protects — ПРОЗА: його друкує `--list` і текст відмови, а машина читає лише
#                те, що воно непорожнє; правду прози не перевіряє ніщо. Межа
#                названа тут, а не замовчана.
#
#   guard_witnesses.Edit
#     file, find, replace — дані мутації: наявність файлу пінить RegistryTest,
#                застосовність (`find` рівно один раз) і результат — прогін.
#
#   verdict_field_mutants.Field
#     name     — покриття місцями пінить
#                `test_verdict_field_mutants.PlanTest.test_the_default_call_covers_every_declared_field`;
#     python_zero, shell_zero — нуль поля, яким мутант його замінює: пінить
#                прогін механізму (еквівалентний мутант виживає й червонить
#                `RepositoryTest`). Чесна межа: нуль, який лишає мутанта
#                НЕеквівалентним (`"1"` замість `0`), не ловиться — поле й далі
#                робить мутанта іншим, тож «поле несе свій нуль» лишається
#                судженням автора.
#
#   test_fixture_environments.StandIn / Program — кожне поле пінить RegistryTest
#                того самого модуля: `where` — рівність файлів у два боки,
#                `prefix`/`reads` — читання з оточення в два боки, `programs`/
#                `at`/`marker` — прив'язка до місця й маркер у своїй програмі,
#                `name` — унікальність.
RECORDS = (
    ("scripts/guard_witnesses.py", "Guard", ("test", "protects", "edits")),
    ("scripts/guard_witnesses.py", "Edit", ("file", "find", "replace")),
    ("scripts/verdict_field_mutants.py", "Field", ("name", "python_zero", "shell_zero")),
    ("scripts/tests/test_fixture_environments.py", "StandIn",
     ("name", "where", "prefix", "reads", "programs")),
    ("scripts/tests/test_fixture_environments.py", "Program", ("at", "marker")),
)


# --------------------------------------------------------------------------
# Читання оголошених полів із джерела
# --------------------------------------------------------------------------


def _is_dataclass(node: ast.ClassDef) -> bool:
    for decorator in node.decorator_list:
        spelled = ast.unparse(decorator)
        if spelled.endswith("dataclass") or ".dataclass" in spelled:
            return True
    return False


def declared_fields(root: Path, relative: str, class_name: str) -> tuple[str, ...]:
    """Поля запису, прочитані з ДЖЕРЕЛА, — рівно те, що оголошено в класі.

    Читається AST, а не імпорт: механізм мусить працювати на будь-якому корені
    (зокрема на копії дерева), а імпорт притягнув би залежності того дерева.
    Не датаклас — невідомість: «полів немає» і «клас більше не запис» — різні
    речі, і друга не сміє виглядати як перша.
    """
    path = root / relative
    if not path.is_file():
        raise DeclaredFieldError("немає %s — запису не прочитати" % relative)
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"))
    except (SyntaxError, OSError) as error:
        raise DeclaredFieldError("%s не розібрано (%s)" % (relative, error))
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef) or node.name != class_name:
            continue
        if not _is_dataclass(node):
            raise DeclaredFieldError("%s.%s — уже не датаклас: поля не оголошені"
                                     % (relative, class_name))
        return tuple(
            statement.target.id
            for statement in node.body
            if isinstance(statement, ast.AnnAssign) and isinstance(statement.target, ast.Name)
        )
    raise DeclaredFieldError("%s не оголошує класу %s" % (relative, class_name))


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


@dataclasses.dataclass
class Finding:
    """Одна розбіжність між оголошеним і переліченим."""

    where: str
    detail: str


@dataclasses.dataclass
class Report:
    """Що сталося з переліком: розбіжності й те, що спинило перевірку."""

    findings: list[Finding] = dataclasses.field(default_factory=list)
    blocked: list[str] = dataclasses.field(default_factory=list)
    checked: int = 0


def check(root: Path, records=RECORDS) -> Report:
    """Кожен перелічений запис мусить оголошувати рівно перелічені поля."""
    report = Report()
    for entry in records:
        # Форма самого переліку теж перевіряється: запис із зайвим елементом або
        # полями-рядком розібрався б як «поля» й дав би знахідки про літери.
        # Невідомість, а не вигадана розбіжність.
        if not isinstance(entry, tuple) or len(entry) != 3 or not isinstance(entry[2], tuple):
            report.blocked.append(
                "FAIL  запис переліку — це (файл, клас, поля), а не %r: полів не прочитати"
                % (entry,)
            )
            return report
        relative, class_name, expected = entry
        where = "%s.%s" % (relative, class_name)
        try:
            found = declared_fields(root, relative, class_name)
        except DeclaredFieldError as error:
            report.blocked.append("FAIL  %s" % error)
            return report
        report.checked += 1
        if found == tuple(expected):
            continue
        added = [name for name in found if name not in expected]
        removed = [name for name in expected if name not in found]
        if added:
            report.findings.append(Finding(
                where,
                "НОВЕ оголошене поле: %s. Поле мусить бути виведеним (щоб оголошувати "
                "було нічим) або пінованим (назви, хто червоніє, коли воно збреше) — "
                "або його не мусить бути. Додати його в RECORDS можна лише разом із "
                "цією відповіддю." % ", ".join(added),
            ))
        if removed:
            report.findings.append(Finding(
                where,
                "застарілий запис: %s більше не оголошено. Зниклого поля не тримає "
                "ніщо, тож і рядок у RECORDS тримав би саму лише обіцянку."
                % ", ".join(removed),
            ))
        if not added and not removed:
            report.findings.append(Finding(
                where,
                "порядок полів інший: оголошено %s, перелічено %s. Порядок — частина "
                "оголошення: записи будують позиційно."
                % (", ".join(found), ", ".join(expected)),
            ))
    return report


# --------------------------------------------------------------------------
# Виклик
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Набір оголошених полів кожного запису мусить бути переліченим.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева (типово — репозиторій)")
    parser.add_argument("--list", action="store_true", help="лише назвати записи й поля")
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    if arguments.list:
        for relative, class_name, expected in RECORDS:
            print("%s.%s — %s" % (relative, class_name, ", ".join(expected)))
        return 0

    report = check(root)
    for finding in report.findings:
        print("FAIL  %s: %s" % (finding.where, finding.detail), file=sys.stderr)
    for failure in report.blocked:
        print(failure, file=sys.stderr)
    if report.blocked:
        return 2
    if report.findings:
        return 1
    print("OK    записів у переліку: %d, кожен оголошує рівно перелічені поля; "
          "хто пінить поля — у коментарях біля RECORDS, межа — у шапці" % report.checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
