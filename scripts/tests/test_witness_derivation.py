"""Тотального виведення свідка з AST немає — і це доведено прогоном, а не припущено (#1279).

Питання тікета: чи можна надійно вивести з AST **свідка** — тест, який справді
ЗАПУСКАЄ підставний інструмент? Шість кіл огляду PR #1267 відповідали «ще ні»:
кожна правка закривала ФОРМУ (маркер модуля, `runs=False`, поле `witness`,
`own_poison`, множина імен, присвоєння в хелпері, літерал в обгортці виклику,
dotted-ресивер), а наступне коло знаходило нову. Критерій щоразу виконувався:
ґард зелений, а тест, який справді запускає інструмент, червоний під отруєним
оточенням. Механізм прибрано в PR #1267 (у main він увійшов squash'ем `a6dd324d`,
досяжним з `origin/main`); цей модуль відповідає, чому його не можна було
полагодити.

ВІДПОВІДЬ: НІ, і причина не в тому, що правил бракувало.

Причина, виміром (`test_the_same_ast_gives_two_truths`). Один файл, один AST,
однаковий байт-у-байт у двох прогонах — і два різні вердикти: без зовнішнього
перемикача тест зелений (інструмента не запускав), з перемикачем червоний
(запустив і побачив відмову). Виведення читає ТЕКСТ; істинність твердження
«цей тест запускає цей інструмент» ухвалює РАНТАЙМ. **Тотальної** функції, яка
на однаковому вході дає правильну відповідь обом прогонам, не існує — не «важко
знайти», а немає. Сильнішого тут не доведено, і не треба: функція, якій
дозволено відповісти «невідомо», обидва прогони переживає — і мусить сказати
«невідомо» рівно там, де автоматизацію й хотіли, на свідкові. Тобто вона не
додає нічого до названого списку, який мала замінити. Тому кожне нове правило
приймання лише звужувало множину прийнятого, і наступне коло знаходило в
доповненні ЗВИЧАЙНИЙ код (`textwrap.dedent("…")`, `self.builder.install("…")`) —
не екзотику.

Те саме видно й з іншого боку, і це той самий факт: «згадує» і «запускає» — це
те саме посилання в AST. Розрізнити їх статично нема чим, бо різниця не в
тексті. А той, хто різницю таки бачить, її ВИКОНУЄ — тобто вже не виводить, а
прогоняє. Єдине надійне виведення свідка — прогін.

Те саме загальніше, і тому «розумнішого» тотального правила не буде: для
будь-якої програми `P` можна написати тест «якщо `P` зупиняється — запусти
інструмент», і питання «чи цей тест запускає інструмент» стає питанням зупинки.
Виведення не «недостатньо розумне» — тотального не існує, а те, що відповідає
«невідомо», і Є названий список.

Другий тест (`test_every_form_is_accepted_and_runs_nothing`) — це слід шести
кіл, а не доказ: п'ять форм, які критерій «тест згадує підставного» приймає,
і жодна з них інструмента не запускає; чесний запуск серед них один. Форм було
вісім і буде більше — саме тому доказом тут є не перелік, а той самий AST із
двома правдами. Перелік лишається як регресія для наступної спроби виведення:
кожна нова форма мусить приходити разом із поясненням, чому вона не дев'ята.

Три з восьми форм сюди не потрапили, і це не пропуск: `runs=False`, поле
`witness` і `own_poison` були ПОЛЯМИ ЗАПИСУ реєстру — можливістю оголосити
свідка, а не формою посилання. Їх закрили не правилом, а прибиранням самих
полів (`RegistryTest.test_the_registry_has_no_declared_switches` пінить набір
полів), тож відтворити ці форми нічим: оголосити ніде. Це той самий принцип, що
й тут, — не додавати перевірку, а прибирати можливість оголосити.

ЦІНА, І ВОНА В КОДІ. Відмовитись від виведення означає тримати зв'язок
«прогін ↔ підставний» у НАЗВАНОМУ списку
(`test_fixture_environments.ENVIRONMENT_TARGETS`), а не виводити його. Що при
цьому втрачається, показано виконанням там, де ця ціна й живе:
`EnvironmentTest.test_the_guard_sees_only_the_runs_it_is_named` — новий
підставний, який успадковує оточення, червоний під отрутою ТОДІ Й ЛИШЕ ТОДІ,
коли його прогін названо; неназваний не запускається взагалі. Компенсує це
реєстр (`RegistryTest`), який ловить НЕЗАРЕЄСТРОВАНЕ, і сам список, який не
звузити мовчки. Чого не компенсує ніщо — автоматичного покриття нових
підставних: їх додають у список руками, разом із прогоном, і ця межа названа
біля списку, а не тут. Компенсація цієї межі — окремий тікет (#1294): зламати
кожного зареєстрованого підставного в копії й вимагати, щоб кожен названий
прогін почервонів. Тут її немає — і це названо, а не обіцяно.

Підстави цього висновку — не лише цей модуль. Дві проби по тому самому коду
виведення, який мав репозиторій, лежать у звіті тікета. Того коду в main немає:
його прибрано в PR #1267, і дістати останній стан перед прибиранням можна з
гілки PR — `git fetch origin pull/1267/head` (там це
`scripts/tests/test_fixture_environments.py`). Проби дали: обгортка виклику —
місце `textwrap` і свідком тест, який кличе `textwrap.dedent` для чужого рядка;
dotted-ресивер — місце `FixtureBase.write_tool: self.builder` і свідком тест,
який дивиться на сусідній атрибут того самого об'єкта. Обидва свідки зелені на
зламаному інструменті.
"""

from __future__ import annotations

import ast
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GATE = (REPO_ROOT / "scripts" / "tests" / "fixtures" / "empty_shape" / "pinned"
        / "scripts" / "gate.sh")

# Ім'я, за яким критерій шістьох кіл упізнавав підставного: файл і константа, за
# якою він лежить.
TOOL = "gate.sh"

# Прогін, який віддає вердикт ПО КОЖНОМУ ТЕСТУ, а не рядок звіту.
#
# Це той самий прийом, що `NESTED_RUN` у `test_fixture_environments`: програма
# БЕЗ шебанга, яку всі три скани реєстру не бачать (межа названа в докстрінзі
# того модуля, «програма БЕЗ шебанга невидима ВСІМ трьом сканам»). Структура, а
# не підрядок: «OK» у звіті не відрізняє «виконався» від «упав і написав OK».
RESULT_RUN = '''
import json
import sys
import unittest


class Result(unittest.TextTestResult):
    """Вердикт по кожному тесту — те, що звіт `unittest` друкує словами."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.outcomes = {}

    def addSuccess(self, test):
        super().addSuccess(test)
        self.outcomes[test.id()] = "ok"

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self.outcomes[test.id()] = "failure"

    def addError(self, test, err):
        super().addError(test, err)
        self.outcomes[test.id()] = "error"

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self.outcomes[test.id()] = "skipped"


suite = unittest.TestLoader().loadTestsFromNames(sys.argv[2:])
result = unittest.TextTestRunner(stream=sys.stderr, verbosity=0,
                                 resultclass=Result).run(suite)
print(json.dumps({"root": sys.argv[1], "outcomes": result.outcomes}))
'''

# Той самий AST у двох прогонах: чи запускати інструмент, вирішує ЗОВНІШНІЙ
# файл, а не текст. Посилання — ідеальне: прямий `subprocess.run` зі шляхом
# підставного в тілі тесту; кращої форми для виведення не буває.
TWIN = '''"""Один текст — дві правди: рішення ухвалює рантайм."""

import subprocess
import unittest
from pathlib import Path

GATE = Path(__file__).resolve().parents[1] / "gate.sh"
MARKER = Path(__file__).resolve().parents[2] / "marker"
SWITCH = Path(__file__).resolve().with_name("run.switch")


class TwinTest(unittest.TestCase):
    def test_reports_the_gate_verdict(self):
        if not SWITCH.exists():
            return
        result = subprocess.run(
            ["bash", str(GATE), str(MARKER)], check=False, capture_output=True,
            text=True, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
        )
        self.assertEqual(0, result.returncode, result.stderr)
'''

# Слід шести кіл: п'ять форм, у яких тест ЗГАДУЄ підставного, і один чесний
# запуск. Критерій «згадує» приймає всі шість; інструмент запускає один.
FORMS = '''"""П'ять форм обходу з шести кіл огляду + один чесний запуск."""

import shlex
import subprocess
import textwrap
import unittest
from pathlib import Path

GATE = Path(__file__).resolve().parents[1] / "gate.sh"
GATE_TEXT = textwrap.dedent("gate.sh --work")
MARKER = Path(__file__).resolve().parents[2] / "marker"
ENVIRONMENT = {"PATH": "/usr/bin:/bin", "LC_ALL": "C"}

# маркер модуля: ім'я підставного лежить у константі модуля
MODULE_MARKER = "gate.sh"
# множина імен
NAMES = {"gate.sh", "gradlew", "adb"}


class Builder:
    """Об'єкт, у якого підставного «встановлюють»."""

    def install(self, what):
        return what


class FormsTest(unittest.TestCase):
    def setUp(self):
        self.builder = Builder()

    def helper(self):
        return "gate.sh"

    def test_module_marker(self):
        self.assertIn("gate.sh", MODULE_MARKER)

    def test_name_set(self):
        self.assertIn("gate.sh", NAMES)

    def test_helper_assignment(self):
        self.assertEqual("gate.sh", self.helper())

    def test_literal_in_a_call_wrapper(self):
        self.assertEqual("gate.sh", shlex.split(GATE_TEXT)[0])

    def test_dotted_receiver(self):
        self.assertEqual("gate.sh", self.builder.install("gate.sh"))

    def test_runs_the_gate(self):
        result = subprocess.run(
            ["bash", str(GATE), str(MARKER)], check=False, capture_output=True,
            text=True, env=ENVIRONMENT,
        )
        self.assertEqual(0, result.returncode, result.stderr)
'''


def criterion(source: str) -> list[str]:
    """Критерій, до якого зійшлися шість кіл: тест ЗГАДУЄ підставного.

    Згадка — пряма (ім'я `gate.sh` у рядку, константа `GATE` зі шляхом) або
    через константу модуля (`MODULE_MARKER`, `NAMES`), яку тест називає: саме
    тому критерій і був сильнішим за пошук підрядка. Це та сама думка, що
    `_reachable` у прибраному виведенні, лише коротко.
    """
    tree = ast.parse(source)
    holds_tool = set()
    for node in tree.body:
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        value = ast.get_source_segment(source, node.value) or ""
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        if TOOL in value:
            holds_tool |= {target.id for target in targets
                           if isinstance(target, ast.Name)}
    holds_tool.add("GATE")

    found = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef) or not node.name.startswith("test_"):
            continue
        names = {child.id for child in ast.walk(node) if isinstance(child, ast.Name)}
        strings = [child.value for child in ast.walk(node)
                   if isinstance(child, ast.Constant) and isinstance(child.value, str)]
        if names & holds_tool or any(TOOL in text for text in strings):
            found.append(node.name)
    return sorted(found)


def strict_criterion(source: str) -> list[str]:
    """Найсуворіше правило, яке спадає на думку: у САМОМУ тілі тесту мусить бути
    виклик `subprocess`, у аргументах якого — шлях підставного.

    Це межа, до якої могло б дійти виведення, якби звужувало приймання далі:
    «згадка» тут уже не проходить, потрібен запуск процесу зі шляхом інструмента.
    Доказ (`test_the_same_ast_gives_two_truths`) показує, що й цього мало.
    """
    tree = ast.parse(source)
    found = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef) or not node.name.startswith("test_"):
            continue
        for child in ast.walk(node):
            if not isinstance(child, ast.Call):
                continue
            callee = ast.get_source_segment(source, child.func) or ""
            argv = " ".join(ast.get_source_segment(source, argument) or ""
                            for argument in child.args)
            if "subprocess" in callee and "GATE" in argv:
                found.append(node.name)
                break
    return sorted(found)


class DerivationTest(unittest.TestCase):
    """Доказ і слід шести кіл — обидва прогонами, а не читанням коду."""

    def build(self, source: str, marker_behaves: bool) -> tuple[Path, Path]:
        """Дерево з підставним інструментом: `marker_behaves` вирішує його вердикт.

        Підставний — справжній `gate.sh` фікстури `empty_shape`, скопійований у
        дерево: він відмовляє, коли гілку не названо, і погоджується, коли названо
        `release`. Тобто «зламаний інструмент» і «інструмент із іншими входами» —
        це тут те саме: прогін, який інструмента не запускає, не помітить ні того,
        ні того.
        """
        directory = Path(tempfile.mkdtemp(prefix="witness-derivation-"))
        self.addCleanup(shutil.rmtree, directory, True)
        scripts = directory / "root" / "scripts"
        tests = scripts / "tests"
        tests.mkdir(parents=True)
        shutil.copy2(GATE, scripts / "gate.sh")
        marker = directory / "root" / "marker"
        marker.mkdir()
        if marker_behaves:
            (marker / "head-name").write_text("release\n", encoding="utf-8")
        (tests / "test_subject.py").write_text(source, encoding="utf-8")
        return tests, directory

    def outcomes(self, tests: Path, module: str) -> dict:
        """Вердикт по кожному тесту модуля — вкладеним прогоном `unittest`."""
        result = subprocess.run(
            [sys.executable, "-c", RESULT_RUN, "run", module],
            cwd=str(tests), check=False, capture_output=True, text=True,
            env={"PATH": "/usr/bin:/bin", "HOME": str(tests), "LC_ALL": "C"},
        )
        self.assertEqual(0, result.returncode, result.stderr[-3000:])
        counts = json.loads(result.stdout.strip().splitlines()[-1])
        return {test.rpartition(".")[2]: verdict
                for test, verdict in counts["outcomes"].items()}

    def test_the_same_ast_gives_two_truths(self):
        """Той самий текст — і «запускає», і «не запускає» (доказ, #1279).

        Інструмент в обох прогонах відмовляє (його гілку не названо), тож
        червоний вердикт означає рівно одне: тест інструмент запустив і побачив
        його відмову. Джерело між прогонами не змінюється — це перевіряється
        хешем і `ast.dump`, а не словом: різницю несе ЗОВНІШНІЙ файл.

        Тому ТОТАЛЬНОЇ функції від тексту тут бути не може: на однаковому вході
        вона мусить дати ту саму відповідь, а правда різна. Функція з
        «невідомо» обидва прогони переживає — і каже «невідомо» рівно на тому
        тесті, якого автоматизація й шукала, тож автоматизації не додає. Хто цю
        різницю бачить — той прогін і робить, тобто вже не виводить.
        """
        tests, _ = self.build(TWIN, marker_behaves=False)
        source = (tests / "test_subject.py").read_bytes()
        before = hashlib.sha256(source).hexdigest()
        tree_before = ast.dump(ast.parse(source.decode("utf-8")))

        without_switch = self.outcomes(tests, "test_subject")
        (tests / "run.switch").write_text("", encoding="utf-8")
        with_switch = self.outcomes(tests, "test_subject")

        after = hashlib.sha256((tests / "test_subject.py").read_bytes()).hexdigest()
        self.assertEqual(before, after, "джерело між прогонами змінилося — це не доказ")
        self.assertEqual(tree_before,
                         ast.dump(ast.parse((tests / "test_subject.py").read_text(
                             encoding="utf-8"))))
        self.assertEqual(["test_reports_the_gate_verdict"],
                         criterion(TWIN), "критерій мусить приймати цей тест свідком")
        self.assertEqual(["test_reports_the_gate_verdict"], strict_criterion(TWIN),
                         "і найсуворіше правило теж: посилання тут ідеальне — "
                         "`subprocess` зі шляхом інструмента в тілі тесту")
        self.assertIn("ok", without_switch.values(),
                      "без перемикача тест мусить бути зелений: інструмента не запущено")
        self.assertEqual({"test_reports_the_gate_verdict": "failure"}, with_switch,
                         "з перемикачем тест мусить почервоніти: інструмент запущено")

    def test_every_form_is_accepted_and_runs_nothing(self):
        """П'ять форм прийнято свідками — і жодна інструмента не запускає.

        Це не доказ (форм було вісім, і буде більше), а слід: критерій не
        відрізняє згадку від запуску на ЗВИЧАЙНОМУ коді. Прогін із названою
        гілкою — контроль: він мусить бути зелений, інакше червоні вердикти
        нижче нічого не доводили б.
        """
        accepts = criterion(FORMS)
        self.assertEqual(
            ["test_dotted_receiver", "test_helper_assignment",
             "test_literal_in_a_call_wrapper", "test_module_marker",
             "test_name_set", "test_runs_the_gate"],
            accepts, "критерій мусить приймати всі шість — і чесний запуск теж")

        behaves, _ = self.build(FORMS, marker_behaves=True)
        green = self.outcomes(behaves, "test_subject")
        self.assertEqual({}, {name: verdict for name, verdict in green.items()
                              if verdict != "ok"},
                         "з працюючим інструментом мусять бути зелені всі")

        refuses, _ = self.build(FORMS, marker_behaves=False)
        red = {name: verdict for name, verdict in self.outcomes(refuses, "test_subject").items()
               if verdict != "ok"}
        self.assertEqual(["test_runs_the_gate"], sorted(red),
                         "інструмент запускає рівно один тест із шести прийнятих")
        self.assertEqual(
            ["test_dotted_receiver", "test_helper_assignment",
             "test_literal_in_a_call_wrapper", "test_module_marker",
             "test_name_set"],
            sorted(name for name in accepts if name not in red),
            "п'ять форм прийнято — і всі п'ять зелені на відмові інструмента")


if __name__ == "__main__":
    unittest.main()
