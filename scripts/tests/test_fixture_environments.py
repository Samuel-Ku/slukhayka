"""Фікстура, яка вдає інструмент, не успадковує оточення раннера (#1265).

Два падіння CI за минулу ціль мали один корінь: підставний інструмент усередині
фікстури жив в оточенні, яке дісталося йому у спадок.

  1. Раннер приносить експортовану bash-функцію (`BASH_FUNC_<ім'я>%%`), і вона
     затіняє команду ВСЕРЕДИНІ інструмента — `dirname`, `cat`, `printf` — тож
     інструмент виходить із кодом 2 при незмінному файлі. Саме так упав CI
     #1260: `repo_root` підставного `mutate.sh` ставав `/`.
  2. Фікстура вдавала CLI не тієї форми, що справжній інструмент (#1260,
     `mutation_trace.py`: позиційний аргумент ПЕРЕД опцією замість підкоманди) —
     і це бачив лише CPython 3.12.3, тобто лише джоба.

Обидва рази локальна зелень цього не бачила. Тому тут — реєстр фікстур
репозиторію, які запускають підставний інструмент, і дві речі, які він справді
перевіряє:

  * **оточення**: КОЖЕН названий прогін (`ENVIRONMENT_TARGETS`) мусить лишитися
    зеленим і з порожнім оточенням (фікстура не читає зі спадку нічого), і з
    отруєним (затьмарені команди не доходять до інструмента). Обидва прогони —
    вкладені процеси `unittest`;
  * **покриття**: жоден файл-інструмент під `fixtures/` і жодна вбудована
    програма під `scripts/tests/` не лишаються без запису, запис указує на
    існуюче місце, а фікстурний інструмент названий тестом, який лежить поруч.
    Це перевірка НАЯВНОСТІ запису, а не запуску.

Форми тут немає навмисно: реєстр не пінить, що фікстура вдає справжній
інструмент тої самої форми. Була пара полів — названий справжній інструмент і пін
на форму, — але пін доводив лише те, що він НАЗИВАЄ файл чи маркер: у власному
тілі чи в коментарі, — а не те, що він форму ПОРІВНЮЄ. Поле, яке виглядає
захистом, не бувши ним, — та сама хвороба, яку тут лікують, тож його прибрано
разом із пінами. Форму пінять звичайні тести поруч
(`test_verdict_field_mutants`, `test_empty_shape_mutants`), і реєстр цього не
перевіряє і не обіцяє.

УРОК ШЕСТИ КІЛ ОГЛЯДУ. Кожне коло знаходило новий **неперевірений перемикач** у
реєстрі: запис міг щось оголосити — маркер, режим, свідка, виняток, — і ніщо не
перевіряло, що оголошене правда. Прибрати самі перемикачі виявилося мало: доки
«свідка» — тест, який мусить під двома оточеннями прогнати інструмент, —
**виводили з AST**, кожне наступне коло знаходило нову форму, у якій виведений
тест інструмента не запускає: присвоєння в хелпері, обгортка виклику,
dotted-ресивер. Бо «згадує» і «запускає» — це те саме посилання в AST;
розрізнити їх статично нема чим. Тому виведення прибрано **зовсім**, а прогони
названо списком: разом із виведенням зникли і обіцянка, і діра.

ЩО ТУТ БУЛО ПРИПУЩЕННЯМ, А СТАЛО ДОКАЗОМ (#1279). «Розрізнити статично нема чим»
вище — не спостереження, а доведене твердження, і воно лежить у
`test_witness_derivation`: один і той самий текст дає два різні вердикти (без
зовнішнього перемикача тест зелений, з ним — червоний), тож ТОТАЛЬНОЇ функції
від тексту, правильної на обох прогонах, немає; функція з «невідомо» мусить
сказати «невідомо» на самому свідкові й тому нічого не додає до названого
списку. Там же — слід шести кіл: п'ять форм, які критерій «тест згадує
підставного» приймає, і жодна з них інструмента не запускає.

ЧЕСНА МЕЖА, І ВОНА ТУТ, А НЕ ЛИШЕ В ЗВІТІ. **Автоматичного покриття нових
підставних немає.** Підставний інструмент, який успадковує оточення, зелений,
доки його не названо в `ENVIRONMENT_TARGETS` руками — список розширюється разом
із прогоном. Другу половину тієї межі закрито ПРОГОНОМ (#1294):
`EnvironmentTest.test_every_named_run_reddens_when_every_stand_in_is_broken`
ламає КОЖНОГО зареєстрованого підставного в копії дерева й вимагає, щоб КОЖЕН
названий прогін почервонів, — тож названий, який нічого не запускає, тепер
ВИДНО, а не припущено (трьох таких він і назвав, і вони зі списку пішли).
Лишається названим поіменний зв'язок — «саме цей прогін запускає саме цього
підставного»: він вимагав би мутації на кожного підставного, і його тут немає.
Реєстр ловить **незареєстроване** — файл, програму, читання чужої змінної, — і
не ловить «цей прогін справді ганяє цього підставного» поіменно. Ціну названого
показує ПРОГОНОМ
`EnvironmentTest.test_the_guard_sees_only_the_runs_it_is_named`: успадкований
підставний червоний під отрутою тоді й лише тоді, коли його прогін названо.

  * **оточення піниться на НАЗВАНОМУ наборі тестів** (`ENVIRONMENT_TARGETS`), а не
    на виведеному: список лежить у коді, піниться тестом нижче і резолвиться,
    тож звузити його мовчки не можна, а розширити — руками, разом із прогоном.
    Чи справді запускає названий прогін якогось підставного, ловить ґард
    виконанням (#1294); поіменного зв'язку не ловить ніщо, і це названо;
  * **фікстурний інструмент мусить бути НАЗВАНИЙ тестом, який лежить поруч**
    (`fixtures/*/*/scripts/tests/test_*.py`), і ці модулі ганяються в копії дерева
    в обох прогонах оточення. Це перевірка покриття, а не доказ запуску: новий
    файл із записом і прозою без такого модуля червоний (M10b), але згадка в прозі
    від запуску не відрізняється — і ця межа названа, а не замовчана;
  * `test_the_registry_has_no_declared_switches` пінить сам НАБІР полів: нове
    поле-оглядач червонить тест, і його доведеться обґрунтувати тут.

Що `EnvironmentTest` справді доводить: КОЖЕН названий прогін зелений і з порожнім
оточенням, і під отруєним; якщо підставний усередині нього успадкує оточення
раннера — прогін червоний. І окремо, прогоном (#1294): кожен названий прогін
справді ЗАПУСКАЄ якогось зареєстрованого підставного — під зламаним він
червоніє, а прогін, який нічого не запускає, лишається зелений і стає видимим.
Чого не доводить: що він запускає САМЕ ТОГО, за якого його тримали, і що новий
запис узагалі потрапив у список — він розширюється руками (межа названа біля
`ENVIRONMENT_TARGETS`).

СКІП-СЛІПОТА (#1270, знахідка огляду #1267). Прогін, який нічого не виконав,
виходить із НУЛЕМ: `@unittest.skip` на названому прогоні лишав `EnvironmentTest`
зеленим, а `unittest discover` із набором, якого немає, теж зелений. Ґард, який
дивився лише на код виходу, був сліпий до «нічого не сталося» — рівно та хвороба,
проти якої він написаний, і в коді вона не була названа. Тому прогін іде через
`NESTED_RUN`, який віддає ЛІЧИЛЬНИКИ САМОГО прогону (`testsRun`, `skipped`), і
вердикт ухвалюється тим самим правилом, що в `scripts/mutate.sh`: «виконав нуль
тестів» і «пропустив усі» — не зелень, а відмова. Лічильники, а не рядок звіту:
«OK (skipped=1)» — це підрядок, а `testsRun` — структура, і саме на підрядку
зламався сторож `--measure-head`, з якого ця серія почалася.

Покриття перевіряється СТРУКТУРНО, з трьох боків.

  * `fixtures/` — по файлах: жоден файл-інструмент не лишається без запису (і
    жоден запис не вказує на неіснуючий файл), а кожен файл мусить мати тест,
    який НАЗИВАЄ його ім'я. Сама лише наявність `test_*.py` поруч нею не
    проходить: новий файл із записом і прозою був зеленим (M10b);
  * `scripts/tests/**/*.py` — по AST: кожен ЛІТЕРАЛ, значення якого ПОЧИНАЄТЬСЯ
    шебангом, є вбудованою програмою, і кожна мусить бути названа ЗА СВОЇМ
    МІСЦЕМ (`Program.at`), а не «хоч якимсь маркером модуля». Прив'язка до місця
    навмисна: найімовірніший спосіб написати третього підставного `git` —
    скопіювати сусіднього, і копія мусить бути ВИДНА як окрема програма. Маркер
    лишається перевіркою, що запис не збрехав про вміст;
  * читання змінних — по тексту програми: усе, що підставний читає з оточення,
    мусить мати власний префікс запису або бути названим у `reads` (входи
    справжнього інструмента — `JAVA_HOME`, `TMPDIR`); змінні, які ставить сама
    оболонка (`BASH_SOURCE`, `PWD`, `PPID`), спадком не бувають. Дозвіл без
    використання — теж червоний: вигаданих дозволів не тримаємо.

Межі названі прямо:

  * прогони оточення — НАЗВАНИЙ список (`ENVIRONMENT_TARGETS`), а не виведений:
    новий зареєстрований підставний не додається туди сам, і ніщо не перевіряє,
    що названий тест справді його запускає. Покриття реєстру — про наявність
    запису (файл, програма), а не про запуск;
  * скан читає ЗНАЧЕННЯ літерала, тож літерал, у якому шебанг не з першого
    символу (`textwrap.dedent('''\n#!/…''')`), для нього невидимий. Цю сліпу
    пляму закриває окрема перевірка по ТОКЕНАХ: рядок із шебангом у рядковому
    літералі, якого скан не побачив, — червоний. Лишається тільки програма,
    зібрана з кількох шматків у рантаймі (`"#!" + "/bin/sh\n"`), — її не бачить
    ні скан, ні токени;
  * програма БЕЗ шебанга невидима ВСІМ трьом сканам (`_program_text`, перевірка
    по токенах, `program_texts`): усі три ловлять літерали, чиє значення
    ПОЧИНАЄТЬСЯ шебангом, а `"echo fake"`, який тест пише у файл і робить
    виконуваним, для них — рядок-дані. Розширити скан до «схоже на програму»
    означало б хибні спрацювання на кожному рядку-даних, а реєстр, який кричить
    на все, не читають; тож межа названа, а не закрита (знахідка E п'ятого
    кола): підставний інструмент мусить мати шебанг, як усі справжні
    інструменти репозиторію;
  * докстрінг, що ПОЧИНАЄТЬСЯ шебангом, навпаки потрапляє в скан: хибне
    спрацювання в безпечний бік (зайвий запис, а не пропущений);
  * скан обмежений `scripts/tests/**/*.py`. Програма поза цим деревом (у
    `app/**`, `web/**`, у хелпері поза тестами) не покрита жодним зі сканів;
  * підставного інструмента у `test_mutate_python.py` немає — і це перевірено, а
    не обіцяно: скан (разом із піном на відомий набір модулів) не бачить там
    жодної програми.
    Фікстура пише СУБʼЄКТ під тестом і цільовий набір — те, що справжній
    `mutation_python_runner.py` і мусить виконати; це не імперсонація;
  * форми реєстр не перевіряє взагалі: чи вдає фікстура справжній інструмент тої
    самої форми, вирішують тести поруч, а не запис. Пін на форму прибрано саме
    тому — він доводив, що НАЗИВАЄ файл чи маркер, а не що ПОРІВНЮЄ форму, і
    виглядав захистом, не бувши ним;
  * підставний, який успадковує оточення, ловиться лише тоді, коли його ганяє
    НАЗВАНИЙ прогін: реєстр не виводить прогони. Чи названий прогін справді
    ЗАПУСКАЄ якогось зареєстрованого підставного — тепер доводить прогоном ґард
    виконанням (#1294); поіменного зв'язку («саме цього») не доводить ніщо, і це
    названо. **Нові підставні автоматично не покриті** — `ENVIRONMENT_TARGETS`
    розширюють руками, і ніщо не червоніє, якщо цього не зробити.

Дві фікстури (`verdict_field`, `empty_shape`) отруюють оточення і самі, своїми
`EnvironmentTest`; тут вони в реєстрі, і їхні тести теж ганяються — у копії
дерева, щоб не смітити в репозиторії.
"""

import ast
import dataclasses
import functools
import importlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tokenize
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TESTS_DIR = REPO_ROOT / "scripts" / "tests"
FIXTURES = TESTS_DIR / "fixtures"

# `tmp_budget.link_tree` — ЄДИНЕ місце, де в репозиторії будується копія дерева
# (#1270): `scripts/` у ній справжня, решта записів кореня — симлінки. Копія для
# доказу ВИКОНАННЯМ будується нею ж, а не власною редакцією, яка розійшлася б із
# тією, що вже пережила CI.
sys.path.insert(0, str(REPO_ROOT / "scripts"))

import tmp_budget  # noqa: E402  (шлях додано вище: scripts/ не на sys.path у наборі)

# Скільки чекати на вкладений прогін. Таймаут — це провал, не «зелено».
NESTED_TIMEOUT_SECONDS = 600

# Прогін названого тесту РАЗОМ ІЗ ЛІЧИЛЬНИКАМИ (#1270): код виходу сам по собі
# сліпий до скіпа — `unittest` виходить із нулем, коли тест пропущено, тож ґард,
# який дивився лише на код, був зелений на прогоні, що не виконав нічого.
# Лічильники — з САМОГО прогону (`result.testsRun`, `result.skipped`), одним
# рядком JSON: рядок звіту «OK (skipped=1)» був би підрядком, а це структура.
# Це не програма-підставний інструмент: шебанга в неї немає, і скани реєстру
# (шебанг у літералі, читання змінних) її не бачать — межа названа в докстрінзі
# модуля, «програма БЕЗ шебанга невидима ВСІМ трьом сканам».
NESTED_RUN = '''
import json
import sys
import unittest

result = unittest.TextTestRunner(stream=sys.stderr, verbosity=1).run(
    unittest.TestLoader().loadTestsFromNames(sys.argv[1:])
)
print(json.dumps({
    "tests": result.testsRun,
    "skipped": len(result.skipped) + len(result.expectedFailures),
    "skippedIds": [test.id() for test, _reason in result.skipped],
}))
raise SystemExit(0 if result.wasSuccessful() else 1)
'''

# Прогін названих цілей із ПОІМЕННИМ вердиктом. Агрегатних лічильників мало, коли
# питання не «чи виконався прогін», а «КОТРИЙ із названих лишився зелений»: саме
# цей перелік і є вироком нового ґарда (#1294). Форма та сама, що NESTED_RUN, і
# так само без шебанга — скани реєстру її не бачать (межа названа в докстрінзі
# модуля, «програма БЕЗ шебанга невидима ВСІМ трьом сканам»).
NESTED_VERDICTS = '''
import json
import sys
import unittest


class Outcomes(unittest.TestResult):
    def __init__(self):
        super().__init__()
        self.outcomes = {}

    def addSuccess(self, test):
        self.outcomes[test.id()] = "green"

    def addFailure(self, test, error):
        self.outcomes[test.id()] = "red"

    def addError(self, test, error):
        self.outcomes[test.id()] = "red"

    def addSkip(self, test, reason):
        self.outcomes[test.id()] = "skipped"

    def addExpectedFailure(self, test, error):
        self.outcomes[test.id()] = "skipped"

    def addUnexpectedSuccess(self, test):
        self.outcomes[test.id()] = "red"


result = Outcomes()
unittest.TestLoader().loadTestsFromNames(sys.argv[1:]).run(result)
print(json.dumps(result.outcomes))
'''

# Отрута — рівно те, що приносить раннер: затьмарені команди, чужий `BASH_ENV`,
# `CDPATH` і `PYTHONPATH`. `BASH_FUNC_*` діє на bash-процеси, і саме тому ламає
# підставний інструмент, а не тест.
HOSTILE = {
    "BASH_FUNC_printf%%": "() { return 1; }",
    "BASH_FUNC_cat%%": "() { return 1; }",
    "BASH_FUNC_dirname%%": "() { return 1; }",
    "BASH_FUNC_pwd%%": "() { return 1; }",
    "BASH_FUNC_python3%%": "() { return 2; }",
    "BASH_ENV": "/nonexistent/rc",
    "CDPATH": "/tmp",
    "PYTHONPATH": "/nonexistent",
}

# Оточення без спадку взагалі: лише те, без чого не стартує жоден процес.
STRIPPED = {"PATH": "/usr/bin:/bin"}

# Шебанг — це шлях до інтерпретатора (`#!/usr/bin/env bash`), а не сама лише
# позначка: інакше скан ловив би власний рядок `"#!"` у цьому модулі. Межа, яку
# цей регекс задає: програма БЕЗ шебанга для скану не програма (докстрінг
# модуля, «Межі названі прямо»).
SHEBANG = re.compile(r"^#!\S*/")

# Читання змінних оточення: `$NAME`, `${NAME…}` у shell і `os.environ[…]`/
# `os.environ.get(…)` у Python. Локальні змінні підставних — малими літерами,
# тож фільтр «ім'я = верхній регістр» відсіює їх без окремого списку.
SHELL_READ = re.compile(r"\$\{?([A-Za-z_][A-Za-z0-9_]*)")
PYTHON_READ = re.compile(r"os\.environ(?:\.get)?[\[(]\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']")

# Змінні, які ставить САМА оболонка (`BASH_SOURCE` у `mutate.sh` фікстури,
# `PWD`/`PPID` у підставному gradlew). Спадком раннера вони не бувають — bash
# переписує їх сам, — тож у правилі «читає лише своє» вони не рахуються.
#
# МЕЖА, І ВОНА ТУТ (#1276): цей список НІХТО не порівнює зі справжньою
# оболонкою. Зайвий рядок у ньому — тихий дозвіл: ім'я, якого bash не ставить,
# перестане вважатися чужим читанням, і ніщо не почервоніє. Вивести список із
# самої оболонки не можна, не підмінивши питання: `env -i bash -c 'compgen -v'`
# віддає й `PATH` — ім'я, яке bash ставить сам, але яке в оточенні раннера
# приходить спадком, тобто дозволило б саме те, що правило ловить; а перевірка
# «кожен рядок справді bash-ів» була б сьомою перевіркою — медицина цього тікета
# виводити або прибирати, і жодне з двох тут не дає того самого правила. Тому
# межа названа, а не замовчана.
SHELL_PROVIDED = {
    "BASH_SOURCE", "BASH_LINENO", "BASH_VERSION", "BASHPID", "EPOCHSECONDS",
    "EPOCHREALTIME", "EUID", "FUNCNAME", "IFS", "LINENO", "OLDPWD", "PPID",
    "PWD", "RANDOM", "SECONDS", "SHELLOPTS", "SHLVL", "UID",
}


@dataclasses.dataclass(frozen=True)
class Program:
    """Вбудована програма: де вона лежить і який рядок її називає.

    `at` — місце, а не текст: `GRADLEW`, `Fixture.build: java`,
    `DeviceRunTest.setUp: self.adb`. Дві копії того самого тексту — дві різні
    програми, і кожна мусить мати свій `at`.
    """

    at: str
    marker: str


@dataclasses.dataclass(frozen=True)
class StandIn:
    """Один підставний інструмент.

    Полів-перемикачів немає навмисно: виключити запис із реєстру чи з прогонів
    оточення нічим — прогін додається до НАЗВАНОГО списку руками, а не полем
    запису. Кожне поле має механічну перевірку, і сам набір полів пінить
    `RegistryTest.test_the_registry_has_no_declared_switches`.

    Форми тут немає навмисно. Була пара полів — названий справжній інструмент і
    пін на форму, — але пін доводив лише те, що він НАЗИВАЄ файл чи маркер: у
    власному тілі чи в коментарі, — а не те, що він форму ПОРІВНЮЄ. Поле, яке
    виглядає захистом, не бувши ним, — це та сама хвороба, яку тут лікують, тож
    його прибрано разом із пінами. Форму фікстур пінять звичайні тести поруч
    (`test_verdict_field_mutants`, `test_empty_shape_mutants`), і реєстр цього не
    перевіряє і не обіцяє.
    """

    name: str
    where: tuple[str, ...]      # файли (відносно кореня репозиторію)
    prefix: str = ""            # власний префікс змінних, які він читає
    reads: tuple[str, ...] = ()  # названі входи справжнього інструмента
    programs: tuple[Program, ...] = ()  # вбудовані програми цього запису


CASES = (
    StandIn(
        name="підставний recorder фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutation_trace.py",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutation_trace.py",
        ),
    ),
    StandIn(
        name="підставний mutate.sh фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutate.sh",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutate.sh",
        ),
        prefix="VERDICT_FIXTURE_",
    ),
    StandIn(
        name="підставний gate.sh фікстури empty_shape",
        where=(
            "scripts/tests/fixtures/empty_shape/pinned/scripts/gate.sh",
            "scripts/tests/fixtures/empty_shape/unpinned/scripts/gate.sh",
        ),
    ),
    StandIn(
        name="підставний gradlew і jdk фікстури test_mutate",
        where=("scripts/tests/test_mutate.py",),
        prefix="MUTATE_TEST_",
        programs=(
            Program("GRADLEW", "MUTATE_TEST_GRADLE_LOG"),
            Program("Fixture.build: java", "openjdk version"),
        ),
    ),
    StandIn(
        name="підставний gh фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        prefix="FAKE_GH_",
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний старий git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        programs=(Program("FAKE_OLD_GIT", "--path-format"),),
    ),
    StandIn(
        name="підставний зламаний git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        programs=(Program("FAKE_BROKEN_GIT", "--cherry-mark"),),
    ),
    StandIn(
        name="підставний gh фікстури test_review_pass",
        where=("scripts/tests/test_review_pass.py",),
        prefix="FAKE_GH_",
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний gh фікстури test_issue_premise",
        where=("scripts/tests/test_issue_premise.py",),
        programs=(
            Program("GH_STUB", "issue list"),
            # Другий `gh` тієї самої фікстури: вимір чисел у ПРОЗІ (#1329) читає
            # інші тіла, ніж сторож команди `--measure-head`, і зливати їх в одну
            # програму означало б пінити одне місце двома різними доказами.
            Program("PROSE_GH_STUB", "issue list"),
        ),
    ),
    StandIn(
        name="підставний gh фікстури test_ci_job_time",
        where=("scripts/tests/test_ci_job_time.py",),
        prefix="FAKE_GH_",
        programs=(Program("Fixture.write_gh: self.gh", "FAKE_GH_FIXTURES"),),
    ),
    StandIn(
        name="підставний gh фікстури test_new_lane",
        where=("scripts/tests/test_new_lane.py",),
        prefix="NEW_LANE_TEST_",
        programs=(Program("FAKE_GH", "NEW_LANE_TEST_ISSUE_BODY"),),
    ),
    StandIn(
        # Підставний `post-checkout`, який валить `git worktree add` ПІСЛЯ
        # реєстрації worktree: так піниться відкат напівкопії (#1326). Нічого з
        # оточення він не читає — тому ні префікса, ні названих входів.
        name="підставний post-checkout фікстури test_review_clone",
        where=("scripts/tests/test_review_clone.py",),
        programs=(
            Program("UnknownTest.test_a_failed_worktree_add_leaves_no_half_copy: hook",
                    "exit 1"),
        ),
    ),
    StandIn(
        name="підставний adb фікстури test_wide_window",
        where=("scripts/tests/test_wide_window.py",),
        prefix="FAKE_ADB_",
        programs=(Program("DeviceRunTest.setUp: self.adb", "FAKE_ADB_LOG"),),
    ),
    StandIn(
        name="підставний adb фікстури test_android_emulator_readiness",
        where=("scripts/tests/test_android_emulator_readiness.py",),
        prefix="FAKE_ADB_",
        programs=(Program("AndroidEmulatorReadinessTest.setUp: self.fake_adb",
                          "FAKE_ADB_STATE"),),
    ),
    StandIn(
        name="підставні gradlew і java фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        prefix="WRAPPER_TEST_",
        reads=("JAVA_HOME", "TMPDIR"),
        programs=(
            Program("FullSuiteWrapperTest.setUp: self.java", "21.0.8"),
            Program("FullSuiteWrapperTest.write_gradle: self.fake_gradle",
                    "WRAPPER_TEST_LOG"),
            # Підставна збірка, яка НЕ закінчується: нею прогін убивають SIGKILL'ом
            # у польоті, і тека прогону лишається — так її й пінить сторож (#1305).
            Program("FullSuiteWrapperTest.write_hanging_gradle: self.fake_gradle",
                    "sleep 300"),
            # Друга збірка — ОКРЕМИМ файлом: переписувати ту саму, яку зараз
            # виконує живий прогін, не можна, і саме нею йдуть третій та
            # останній прогони сторожів (#1305).
            Program("FullSuiteWrapperTest.write_fast_gradle: fast",
                    "швидка підставна збірка"),
            Program("FullSuiteWrapperTest"
                    ".test_rejects_a_non_21_override_with_an_actionable_error: self.java",
                    "17.0.12"),
            Program("FullSuiteWrapperTest"
                    ".test_cleans_temp_when_interrupted: self.fake_gradle",
                    "kill -TERM"),
        ),
    ),
    StandIn(
        name="підставні gradlew, npm і npx фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        prefix="WRAPPER_TEST_",
        programs=(
            Program("ChangedTestWrapperWebCheckTest.setUp: self.java", "21.0.8"),
            Program("ChangedTestWrapperWebCheckTest.write_web_command: target",
                    "cwd=%s"),
            Program("ChangedTestWrapperWebCheckTest.write_gradle: self.gradle",
                    "gradle %s"),
        ),
    ),
    StandIn(
        name="підставні gradlew і java фікстури test-changed.sh у test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        prefix="WRAPPER_TEST_",
        programs=(
            Program("ChangedTestWrapperScratchTest.setUp: self.java", "21.0.8"),
            Program("ChangedTestWrapperScratchTest.write_gradle: self.gradle",
                    "WRAPPER_TEST_LOG"),
            # Підставна збірка, яка НЕ закінчується: нею прогін убивають SIGKILL'ом
            # у польоті, і тека прогону лишається — так її й пінить сторож (#1300).
            Program("ChangedTestWrapperScratchTest.write_hanging_gradle: self.gradle",
                    "sleep 300"),
        ),
    ),
    StandIn(
        name="SAMPLE у test_empty_shape_mutants — вхідний текст, не інструмент",
        where=("scripts/tests/test_empty_shape_mutants.py",),
        reads=("HEAD_REF",),
        programs=(Program("SAMPLE", "detached HEAD"),),
    ),
)


# Прогони оточення — НАЗВАНІ, а не виведені.
#
# ЧЕСНА МЕЖА: автоматичного покриття нових підставних НЕМАЄ. Новий підставний
# інструмент, який успадковує оточення, буде зелений, доки його не назвуть тут
# руками; ніщо не червоніє, якщо цього не зробити. А чи названий прогін справді
# ЗАПУСКАЄ підставного — видно не звідси, а з
# `EnvironmentTest.test_every_named_run_reddens_when_every_stand_in_is_broken`
# (#1294): він ламає кожного зареєстрованого підставного і вимагає, щоб КОЖЕН
# прогін із цього списку почервонів.
#
# ТРИ ПРОГОНИ, ЯКИХ ТУТ БІЛЬШЕ НЕМАЄ, І ЧОМУ (#1294). Той ґард назвав їх
# знахідками — вони лишаються зелені під зламаними підставними, тобто не
# запускають жодного з них, і ґард оточення дивився на них у порожнє:
#
#   * `test_empty_shape_mutants.ScannerTest`
#     `.test_reads_arms_with_offsets_and_keeps_the_source_intact` — читає
#     ВХІДНИЙ ТЕКСТ (`SAMPLE`), а не запускає інструмент: `SAMPLE` зареєстрований
#     лише тому, що починається шебангом, і так його й названо в `CASES`;
#   * `test_fixture_environments.EnvironmentTest`
#     `.test_a_named_run_that_is_skipped_is_not_green` — ганяє фікстурний
#     `test_gate` з дописаним `@unittest.skip`, тож `gate.sh` не виконується
#     взагалі: предмет цього прогону — скіп-сліпота (#1270), не підставний;
#   * `test_test_wrappers.FullSuiteWrapperTest`
#     `.test_rejects_a_non_21_override_with_an_actionable_error` — сам переписує
#     підставну `java` власним текстом, і вердикт не залежить від її виводу.
#
# Прибрати їх — не послаблення: ґард оточення для них був зелений НІ НА ЧОМУ, і
# саме тому вони звідси пішли. Прогони лишаються в наборі — їх ганяє `unittest
# discover`, — а їхні власні правила пінять їхні власні сторожа.
#
# Звузити список мовчки не можна: його пінить
# `RegistryTest.test_the_environment_runs_are_the_named_set` і резолвить кожне
# імʼя. Розширюють його разом із прогоном, а не полем запису.
ENVIRONMENT_TARGETS = (
    "test_android_emulator_readiness.AndroidEmulatorReadinessTest"
    ".test_waits_until_android_package_service_is_usable",
    "test_issue_premise.ExtractionTest"
    ".test_measure_head_exists_because_the_docs_cite_it",
    "test_merge_pr.MergePrTest.test_fails_closed_when_the_patch_comparison_fails",
    "test_merge_pr.MergePrTest.test_fails_closed_when_the_working_copy_scan_fails",
    "test_merge_pr.MergePrTest.test_refuses_a_failing_check",
    "test_mutate.MutationRunTest"
    ".test_runs_the_named_class_and_records_a_caught_mutation",
    "test_review_pass.ReviewPassTest"
    ".test_reissues_the_verdict_on_a_pr_that_already_has_the_label",
    "test_test_wrappers.ChangedTestWrapperScratchTest"
    ".test_no_scratch_directory_survives_a_run",
    "test_test_wrappers.ChangedTestWrapperWebCheckTest"
    ".test_web_path_runs_the_web_check_and_no_android_partition",
    "test_test_wrappers.FullSuiteWrapperTest.test_cleans_temp_when_interrupted",
    "test_test_wrappers.FullSuiteWrapperTest"
    ".test_uses_jdk_21_and_a_fresh_temp_then_cleans_it",
    "test_wide_window.DeviceRunTest"
    ".test_derives_the_density_from_the_device_and_reports_the_width",
)


# --- пошук вбудованих програм -------------------------------------------------


def _program_text(node: ast.AST) -> str | None:
    """Текст програми, якщо літерал нею є: значення ПОЧИНАЄТЬСЯ шебангом."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value if SHEBANG.match(node.value) else None
    if isinstance(node, ast.JoinedStr) and node.values:
        first = node.values[0]
        if (isinstance(first, ast.Constant) and isinstance(first.value, str)
                and SHEBANG.match(first.value)):
            return "".join(
                part.value
                if isinstance(part, ast.Constant) and isinstance(part.value, str)
                else "\x00"
                for part in node.values
            )
    return None


def _location(node: ast.AST, parents: dict, source: str) -> str:
    """Місце програми: `Клас.метод: ціль` або ім'я константи.

    Саме місце, а не текст, робить копію сусіда окремою програмою. Ціль береться
    лише тоді, коли схожа на ім'я чи шлях (`self.adb`); інакше — «виклик», бо ключ
    із ресивера-літерала (`"".join([...])` → `""`) не назвав би нічого.
    """
    scopes: list[str] = []
    target = ""
    current = node
    while current in parents:
        current = parents[current]
        if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            scopes.append(current.name)
        if not target and isinstance(current, ast.Assign):
            target = ast.get_source_segment(source, current.targets[0]) or ""
            break
        if not target and isinstance(current, ast.Call):
            function = current.func
            if isinstance(function, ast.Attribute):
                candidate = ast.get_source_segment(source, function.value) or ""
                target = (candidate if re.fullmatch(r"[A-Za-z_][\w.]*", candidate)
                          else "виклик")
    prefix = ".".join(reversed(scopes))
    return f"{prefix}: {target}" if prefix else target


def embedded_programs(module: Path) -> dict[str, str]:
    """Вбудовані програми модуля: `at` → текст."""
    source = module.read_text(encoding="utf-8")
    tree = ast.parse(source)
    parents = {child: parent for parent in ast.walk(tree)
               for child in ast.iter_child_nodes(parent)}
    found: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(parents.get(node), ast.JoinedStr):
            continue
        text = _program_text(node)
        if text is None:
            continue
        key = _location(node, parents, source)
        unique, index = key, 2
        while unique in found:
            unique, index = f"{key}#{index}", index + 1
        found[unique] = text
    return found


@functools.lru_cache(maxsize=1)
def all_embedded_programs() -> dict[str, dict[str, str]]:
    """Усі вбудовані програми під `scripts/tests`, за модулями."""
    return {
        str(module.relative_to(REPO_ROOT)): programs
        for module in sorted(TESTS_DIR.rglob("*.py"))
        if (programs := embedded_programs(module))
    }


def literals_with_unseen_shebang(module: Path) -> list[int]:
    """Рядки, де шебанг лежить у рядковому літералі, якого скан НЕ побачив.

    Ловить саме ту сліпу пляму: літерал, у якому шебанг не з першого символу
    (обгортка `textwrap.dedent`, лапки й новий рядок). Токенізатор знає, що це
    саме літерал, а не коментар, — тож перевірка не залежить від того, як його
    записано.
    """
    source = module.read_text(encoding="utf-8")
    discovered: set = set()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, (ast.Constant, ast.JoinedStr)) and _program_text(node):
            discovered.update(range(node.lineno, (node.end_lineno or node.lineno) + 1))

    hidden = []
    for token in tokenize.generate_tokens(io.StringIO(source).readline):
        if token.type != tokenize.STRING or token.start[0] in discovered:
            continue
        for offset, line in enumerate(token.string.splitlines()):
            if SHEBANG.match(line.lstrip(" \t")):
                hidden.append(token.start[0] + offset)
                break
    return hidden


def program_texts(case: StandIn) -> dict[str, str]:
    """Тексти програм запису: вбудовані — із літералів, фікстурні — з файлів.

    Файли читаються лише під `fixtures/`: `where` вбудованого запису — це тестовий
    модуль, і його код читає власні змінні (журнали, фікстурні перемикачі), які до
    тексту підставної програми не належать.
    """
    embedded = all_embedded_programs()
    texts = {program.at: embedded[case.where[0]][program.at]
             for program in case.programs}
    for where in case.where:
        path = REPO_ROOT / where
        if "fixtures" in Path(where).parts and path.is_file():
            texts[where] = path.read_text(encoding="utf-8")
    return texts


def environment_reads(text: str) -> set[str]:
    """Імена змінних оточення, які програма читає (`$NAME`, `os.environ[...]`)."""
    names = set(SHELL_READ.findall(text)) | set(PYTHON_READ.findall(text))
    return {name for name in names if name == name.upper()} - SHELL_PROVIDED


@functools.lru_cache(maxsize=None)
def fixture_naming_tests() -> list[tuple[Path, str, tuple[str, ...]]]:
    """`(варіант фікстури, файл, модулі, які НАЗИВАЮТЬ його ім'я)` — на кожен файл.

    Це перевірка покриття, а не доказ запуску: модуль мусить назвати файл (той
    самий прийом, що `paired_modules` у `scripts/empty_shape_mutants.py`), бо
    «поруч лежить якийсь `test_*.py`» нею не проходить — новий файл із записом і
    прозою був зеленим (M10b). Чи цей модуль справді ЗАПУСКАЄ інструмент,
    звідси не видно: згадка в прозі — теж згадка. Межа названа в докстрінзі
    модуля; у прогонах оточення ці модулі ганяються в копії дерева, тож
    непрацюючий модуль там просто нічого не перевірить.
    """
    found = set()
    for case in CASES:
        for where in case.where:
            path = Path(where)
            if "fixtures" not in path.parts:
                continue
            tests = REPO_ROOT / path.parent / "tests"
            naming = tuple(sorted(
                module.name for module in tests.glob("test_*.py")
                if path.name in module.read_text(encoding="utf-8")
            )) if tests.is_dir() else ()
            found.add((tests.parent.parent, path.name, naming))
    return sorted(found)


# --- перевірки ----------------------------------------------------------------


class RegistryTest(unittest.TestCase):
    """Перелік мусить бути повним з усіх боків — і кожен запис указувати на місце."""

    def test_the_registry_has_no_declared_switches(self):
        """Набір полів — рівно ті, чию правду щось перевіряє.

        Шість кіл огляду поспіль знаходили в реєстрі новий неперевірений
        перемикач. Тут їх немає: виведення прогонів прибрано зовсім (вони —
        НАЗВАНИЙ список, а не поле запису), а поле форми прибрано разом із
        пінами, бо пін доводив лише «називає», не «порівнює». Нове поле
        червонить цей тест, і його доведеться обґрунтувати.
        """
        self.assertEqual(
            {"name", "where", "prefix", "reads", "programs"},
            {field.name for field in dataclasses.fields(StandIn)},
        )
        self.assertEqual(
            {"at", "marker"},
            {field.name for field in dataclasses.fields(Program)},
        )

    def test_the_registry_names_every_tool_under_the_fixtures(self):
        """Жоден файл-інструмент у `fixtures/` не лишається поза реєстром.

        Рівність у два боки: і зайвий файл у дереві без запису, і запис на файл,
        якого немає, — червоні.
        """
        declared = {
            Path(where)
            for case in CASES for where in case.where
            if where.startswith("scripts/tests/fixtures/")
        }
        found = {
            path.relative_to(REPO_ROOT)
            for path in FIXTURES.rglob("*")
            if path.is_file()
            and "tests" not in path.relative_to(FIXTURES).parts
        }

        self.assertTrue(found, "під fixtures/ мусять бути підставні інструменти")
        self.assertEqual(sorted(found), sorted(declared))

    def test_every_registered_path_exists(self):
        """Запис на неіснуючий файл — це не «менше роботи», а діра в покритті."""
        for case in CASES:
            for where in case.where:
                with self.subTest(stand_in=case.name, path=where):
                    self.assertTrue((REPO_ROOT / where).is_file(), where)

    def test_every_embedded_program_is_claimed_at_its_own_place(self):
        """Кожна вбудована програма названа ЗА СВОЇМ МІСЦЕМ — і навпаки.

        Прив'язка до місця (`Program.at`), а не до маркера модуля: копія сусіда з
        тим самим текстом (найімовірніший спосіб написати третього підставного
        `git`) дістає власний `at` і без запису червонить цей тест. Маркер
        перевіряється окремо — він мусить лежати в ТІЙ програмі, яку запис називає.
        """
        for path, programs in all_embedded_programs().items():
            with self.subTest(module=path):
                claimed: dict[str, str] = {}
                for case in CASES:
                    if case.where != (path,):
                        continue
                    for program in case.programs:
                        self.assertIn(program.at, programs,
                                      "запис вказує на програму, якої немає")
                        self.assertNotIn(program.at, claimed, "програму названо двічі")
                        self.assertIn(program.marker, programs[program.at],
                                      "маркер не лежить у своїй програмі")
                        claimed[program.at] = case.name

                self.assertEqual(
                    sorted(programs), sorted(claimed),
                    "є програми без запису в реєстрі: %s"
                    % (sorted(set(programs) - set(claimed)),),
                )

    def test_the_scan_finds_the_programs_it_is_supposed_to(self):
        """Скан не порожній і бачить відомі місця — інакше він «зелений» ні на чому."""
        found = all_embedded_programs()

        self.assertEqual(
            ["scripts/tests/test_android_emulator_readiness.py",
             "scripts/tests/test_ci_job_time.py",
             "scripts/tests/test_empty_shape_mutants.py",
             "scripts/tests/test_issue_premise.py",
             "scripts/tests/test_merge_pr.py",
             "scripts/tests/test_mutate.py",
             "scripts/tests/test_new_lane.py",
             "scripts/tests/test_review_clone.py",
             "scripts/tests/test_review_pass.py",
             "scripts/tests/test_test_wrappers.py",
             "scripts/tests/test_wide_window.py"],
            sorted(found),
        )
        self.assertIn("FAKE_OLD_GIT", found["scripts/tests/test_merge_pr.py"])
        # `test_mutate_python.py` тут НЕМАЄ: субʼєкт під тестом — не підставний
        # інструмент, і це перевірено сканом, а не обіцяно докстрінгом.
        self.assertNotIn("scripts/tests/test_mutate_python.py", found)

    def test_no_literal_hides_a_shebang_the_scan_cannot_see(self):
        """Сліпа пляма сканера закрита окремою перевіркою по токенах.

        Скан читає значення літерала: `textwrap.dedent('''\\n#!/…''')` значенням
        шебанга не має. Токенізатор бачить, що шебанг лежить у РЯДКОВОМУ
        літералі, — і такий літерал мусить бути або названою програмою, або
        червоним. Пін вище пінить відомий набір модулів; ця перевірка — саму межу.
        """
        for module in sorted(TESTS_DIR.rglob("*.py")):
            with self.subTest(module=str(module.relative_to(REPO_ROOT))):
                self.assertEqual(
                    [], literals_with_unseen_shebang(module),
                    "шебанг у літералі, якого скан не бачить",
                )

    def test_every_program_reads_only_its_own_prefixed_variables(self):
        """Читання з оточення — або власний префікс, або названий вхід інструмента.

        `reads` — дозвіл на КОНКРЕТНЕ ім'я, і він перевіряється у два боки: чуже
        читання червоне, вигаданий дозвіл (названо, а не читається) — теж.
        """
        for case in CASES:
            with self.subTest(stand_in=case.name):
                texts = program_texts(case)
                self.assertTrue(texts, "немає жодного тексту програми")
                reads = set().union(*(environment_reads(text) for text in texts.values()))
                allowed = {name for name in reads
                           if case.prefix and name.startswith(case.prefix)}
                allowed |= set(case.reads)

                foreign = {where: sorted(environment_reads(text) - allowed)
                           for where, text in texts.items()
                           if environment_reads(text) - allowed}
                self.assertEqual({}, foreign, "програма читає чужі змінні: %s" % foreign)
                self.assertEqual(
                    set(), set(case.reads) - reads,
                    "дозвіл названо, а читання немає: %s"
                    % sorted(set(case.reads) - reads),
                )

    def test_every_fixture_instrument_is_named_by_a_test(self):
        """Фікстурний інструмент мусить бути названий тестом, що лежить поруч.

        Це перевірка ПОКРИТТЯ, а не доказ запуску: «поруч лежить якийсь
        `test_*.py`» нею не проходить (M10b: новий `extra_tool.sh` із записом і
        прозою був зеленим), а чи названий модуль справді ЗАПУСКАЄ інструмент —
        звідси не видно: згадка в прозі теж згадка. Межа названа в докстрінзі
        модуля.
        """
        for root, name, modules in fixture_naming_tests():
            with self.subTest(fixture=str(root.relative_to(REPO_ROOT)), file=name):
                self.assertTrue(modules, "%s не названий: жоден тест поруч не "
                                         "називає його файл" % name)

    def test_the_environment_runs_are_the_named_set(self):
        """Прогони оточення — РІВНО названий набір, і кожен із них існує.

        Виведення прогонів з AST тут немає навмисно: шість кіл огляду поспіль
        знаходили нову форму, у якій виведений тест інструмента не запускає —
        присвоєння в хелпері, обгортка виклику, dotted-ресивер, — і кожна правка
        закривала ФОРМУ, а не клас: «згадує» і «запускає» — це те саме посилання
        в AST. Замість виведення — список, який видно: звузити його мовчки не
        можна (він піниться тут), а розширити — руками, разом із прогоном.
        """
        self.assertEqual(
            ["test_android_emulator_readiness.AndroidEmulatorReadinessTest"
             ".test_waits_until_android_package_service_is_usable",
             "test_issue_premise.ExtractionTest"
             ".test_measure_head_exists_because_the_docs_cite_it",
             "test_merge_pr.MergePrTest"
             ".test_fails_closed_when_the_patch_comparison_fails",
             "test_merge_pr.MergePrTest"
             ".test_fails_closed_when_the_working_copy_scan_fails",
             "test_merge_pr.MergePrTest.test_refuses_a_failing_check",
             "test_mutate.MutationRunTest"
             ".test_runs_the_named_class_and_records_a_caught_mutation",
             "test_review_pass.ReviewPassTest"
             ".test_reissues_the_verdict_on_a_pr_that_already_has_the_label",
             "test_test_wrappers.ChangedTestWrapperScratchTest"
             ".test_no_scratch_directory_survives_a_run",
             "test_test_wrappers.ChangedTestWrapperWebCheckTest"
             ".test_web_path_runs_the_web_check_and_no_android_partition",
             "test_test_wrappers.FullSuiteWrapperTest"
             ".test_cleans_temp_when_interrupted",
             "test_test_wrappers.FullSuiteWrapperTest"
             ".test_uses_jdk_21_and_a_fresh_temp_then_cleans_it",
             "test_wide_window.DeviceRunTest"
             ".test_derives_the_density_from_the_device_and_reports_the_width"],
            list(ENVIRONMENT_TARGETS),
        )
        for target in ENVIRONMENT_TARGETS:
            with self.subTest(target=target):
                self.assertTrue(callable(self.resolve(target)),
                                "%s не резолвиться — прогону немає" % target)

    def test_the_fixture_naming_tests_are_the_fixtures_own(self):
        """Файл фікстури називає тест самої фікстури, і саме він лежить поруч."""
        self.assertEqual(
            [("scripts/tests/fixtures/empty_shape/pinned", "gate.sh", ("test_gate.py",)),
             ("scripts/tests/fixtures/empty_shape/unpinned", "gate.sh", ("test_gate.py",)),
             ("scripts/tests/fixtures/verdict_field/pinned", "mutate.sh", ("test_gate.py",)),
             ("scripts/tests/fixtures/verdict_field/pinned", "mutation_trace.py",
              ("test_gate.py",)),
             ("scripts/tests/fixtures/verdict_field/unpinned", "mutate.sh",
              ("test_gate.py",)),
             ("scripts/tests/fixtures/verdict_field/unpinned", "mutation_trace.py",
              ("test_gate.py",))],
            [(str(root.relative_to(REPO_ROOT)), name, modules)
             for root, name, modules in fixture_naming_tests()],
        )

    def test_every_registered_name_is_unique(self):
        names = [case.name for case in CASES]

        self.assertEqual(len(names), len(set(names)))

    def resolve(self, target: str):
        """`module.Class.test` → сам обʼєкт тесту (імпорт із `scripts/tests`)."""
        module_name, _, path = target.partition(".")
        value = importlib.import_module(module_name)
        for part in path.split("."):
            value = getattr(value, part)
        return value


@dataclasses.dataclass(frozen=True)
class NestedRun:
    """Один вкладений прогін: код виходу і лічильники з нього самого."""

    name: str
    status: int
    tests: int | None
    skipped: int | None
    output: str
    skipped_ids: tuple[str, ...] = ()

    @property
    def green(self) -> bool:
        """Зелений — це «виконав свої тести й не впав». Код виходу тут — не все.

        `@unittest.skip` лишає код нулем, тож «зелений» за кодом і «зелений» за
        прогоном — різні речі, і саме на цій різниці ґард і був сліпий (#1270,
        знахідка огляду #1267).
        """
        return self.status == 0 and bool(self.tests) and self.skipped == 0

    def describe(self) -> str:
        if self.tests is None:
            return "%s: лічильників немає (код %d)" % (self.name, self.status)
        if self.skipped_ids:
            # Хто саме не виконався — з лічильників прогону, без жодного зайвого
            # процесу: «пропущено 1» без імені змушувало б шукати винного руками.
            return ("%s: tests=%d, skipped=%d (%s) — не виконався"
                    % (self.name, self.tests, self.skipped, ", ".join(self.skipped_ids)))
        return "%s: tests=%d, skipped=%d (код %d)" % (self.name, self.tests, self.skipped,
                                                      self.status)


# Дерево для ціни без виведення (#1279): підставний `gate.sh` фікстури, який
# УСПАДКОВУЄ оточення, — та сама форма, що впала в CI #1260. Програми без
# шебанга, тож скани реєстру їх не бачать (межа названа в докстрінзі модуля).
INHERITING_RUN = '''"""Прогін, який віддає підставному оточення, а не своє (#1260)."""

import os
import subprocess
import unittest
from pathlib import Path

GATE = Path(__file__).resolve().parents[1] / "gate.sh"
MARKER = Path(__file__).resolve().parents[2] / "marker"


class InheritsTest(unittest.TestCase):
    def test_agrees_when_the_branch_is_named(self):
        result = subprocess.run(
            ["bash", str(GATE), str(MARKER)], check=False, capture_output=True,
            text=True, env=os.environ.copy(),
        )
        self.assertEqual(0, result.returncode, result.stderr)
'''

UNRELATED_RUN = '''"""Прогін, який підставного не запускає взагалі."""

import unittest


class UnrelatedTest(unittest.TestCase):
    def test_adds(self):
        self.assertEqual(4, 2 + 2)
'''


# --- доказ ВИКОНАННЯМ: зламати кожного підставного й вимагати червоного -------

# Підставного ламають ДВОМА різними способами, і різниця тут не косметична.
#
#   * файл під `fixtures/` — це інструмент ЗА ВИЗНАЧЕННЯМ: реєстр пінить рівність
#     «кожен файл під `fixtures/` — зареєстрований підставний»
#     (`RegistryTest.test_the_registry_names_every_tool_under_the_fixtures`), а
#     фікстури кличуть його ЯВНИМ інтерпретатором (`bash gate.sh`) — отже, шебанг
#     не читається взагалі, і ламати треба ВЕСЬ текст;
#   * вбудована програма — це НЕ обов'язково інструмент. `SAMPLE` у
#     `test_empty_shape_mutants` зареєстрований лише тому, що ПОЧИНАЄТЬСЯ
#     шебангом, а насправді це ВХІДНИЙ ТЕКСТ сканера (так його й названо в
#     `CASES`). Тому вбудовану програму ламають ВИКОНАННЯМ, а не вмістом:
#     інтерпретатора, названого в шебангові, більше немає. Прогін, який справді
#     ЗАПУСКАЄ підставного, червоніє; прогін, який лише ЧИТАЄ текст, не зауважує
#     нічого — і саме тому лишається зеленим, тобто видимим як «жодного
#     підставного не виконав». Саме так кандидат тікета #1294
#     (`test_empty_shape_mutants.ScannerTest`
#     `.test_reads_arms_with_offsets_and_keeps_the_source_intact`) став видимим, а
#     не припущеним.
#
# Межа цієї другої мутації, і вона тут: програму, яку кличуть ЯВНИМ
# інтерпретатором (`python3 file`), шебанг не ламає — такий прогін лишиться
# зелений і буде названий знахідкою ґарда. Це відмова в безпечний бік (зайва
# знахідка, а не пропущена діра), і сьогодні серед названих прогонів такої немає.
#
# ШЕБАНГИ ЗІБРАНІ З ШМАТКІВ НАВМИСНО. Скан реєстру бачить ЛІТЕРАЛ, значення
# якого ПОЧИНАЄТЬСЯ шебангом, і вимагав би запису в `CASES` на кожен із цих
# рядків — а це не підставні інструменти, а мутація. Межа названа в докстрінзі
# модуля: «програма, зібрана з кількох шматків у рантаймі, — її не бачить ні
# скан, ні токени»; тут нею користуються свідомо.
SHEBANG_MARK = "#!"
UNRUNNABLE_INTERPRETER = SHEBANG_MARK + "/nonexistent/slukhayka-stand-in"
BROKEN_SHELL_TOOL = SHEBANG_MARK + "/usr/bin/env bash\nexit 1\n"
BROKEN_PYTHON_TOOL = SHEBANG_MARK + "/usr/bin/env python3\nimport sys\nsys.exit(1)\n"


def break_the_run(text: str) -> str:
    """Той самий текст, але виконатися йому більше НЕМА ЧИМ.

    Шебанг указує на неіснуючий інтерпретатор — запуск підставного ядром падає.
    Байти, які читає сканер, лишаються незмінними: ламається ВИКОНАННЯ, а не
    вміст, і саме тому ця мутація відрізняє «прогін запустив підставного» від
    «прогін прочитав його як текст».
    """
    _first, separator, rest = text.partition("\n")
    return UNRUNNABLE_INTERPRETER + separator + rest


def broken_tool_text(where: str) -> str:
    """Текст, у який обертається файл-інструмент фікстури: він не спрацює взагалі.

    Шебанга тут мало: фікстури кличуть інструмент явним `bash`/`python3`, тож
    ламається весь текст, а не перший рядок.
    """
    return BROKEN_PYTHON_TOOL if where.endswith(".py") else BROKEN_SHELL_TOOL


def _claimed_program_spans(source: str, ats: set[str]) -> list[tuple[int, int, str]]:
    """Зсуви в джерелі для названих програм — разом із їхнім текстом.

    Ключ той самий, що в `embedded_programs` (`Program.at` плюс дедуплікація
    `#2`): місце літерала і є особистістю програми, а дві копії того самого
    тексту — дві різні програми. Зсуви від початку файлу, щоб правити джерело
    одним проходом і не зсунути те, що ще не замінено.
    """
    tree = ast.parse(source)
    parents = {child: parent for parent in ast.walk(tree)
               for child in ast.iter_child_nodes(parent)}
    found: dict[str, tuple[ast.AST, str]] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(parents.get(node), ast.JoinedStr):
            continue
        text = _program_text(node)
        if text is None:
            continue
        key = _location(node, parents, source)
        unique, index = key, 2
        while unique in found:
            unique, index = f"{key}#{index}", index + 1
        found[unique] = (node, text)

    lines = source.splitlines(keepends=True)

    def offset(line: int, column: int) -> int:
        return sum(len(part) for part in lines[:line - 1]) + column

    spans = []
    for at in sorted(ats):
        node, text = found[at]
        spans.append((offset(node.lineno, node.col_offset),
                      offset(node.end_lineno, node.end_col_offset), text))
    return spans


def stand_in_breakages(root: Path) -> list[str]:
    """Місця, які зачепить мутація, — БЕЗ самої мутації.

    Порожній перелік означав би перевірку, яка не ламає нічого і тому зелена про
    будь-що; тож його видно окремо (`assertTrue` у ґарді) і коштує він нуль
    прогонів. Назви — ті самі місця, якими реєстр і тримає підставних.
    """
    touched: list[str] = []
    for case in CASES:
        touched.extend(where for where in case.where
                       if "fixtures" in Path(where).parts)
    for case in CASES:
        if case.programs:
            touched.extend("%s:%s" % (case.where[0], program.at)
                           for program in case.programs)
    return touched


def apply_breakages(root: Path) -> None:
    """Зламати КОЖНОГО зареєстрованого підставного в копії дерева.

    Два способи — за видом підставного, і чому саме такі, названо над
    `UNRUNNABLE_INTERPRETER`: файл під `fixtures/` — інструмент, і ламається
    цілком; вбудована програма ламається ВИКОНАННЯМ, щоб прогін, який її лише
    читає, лишився зеленим і був видимий.
    """
    for case in CASES:
        for where in case.where:
            if "fixtures" in Path(where).parts:
                (root / where).write_text(broken_tool_text(where), encoding="utf-8")

    by_module: dict[str, set[str]] = {}
    for case in CASES:
        if case.programs:
            by_module.setdefault(case.where[0], set()).update(
                program.at for program in case.programs)

    for relative, ats in by_module.items():
        module = root / relative
        source = module.read_text(encoding="utf-8")
        for start, end, text in sorted(_claimed_program_spans(source, ats), reverse=True):
            source = source[:start] + repr(break_the_run(text)) + source[end:]
        module.write_text(source, encoding="utf-8")


class EnvironmentTest(unittest.TestCase):
    """Кожен названий прогін мусить бути зелений і без спадку, і під отрутою."""

    def run_named(self, name: str, targets: tuple[str, ...], environment: dict,
                  cwd: Path) -> NestedRun:
        """Названі цілі в каталозі `cwd` — зі своїм оточенням, із лічильниками."""
        result = subprocess.run(
            [sys.executable, "-c", NESTED_RUN, *targets],
            cwd=str(cwd), check=False, capture_output=True, text=True,
            env=environment, timeout=NESTED_TIMEOUT_SECONDS,
        )
        counts: dict = {}
        lines = [line for line in result.stdout.strip().splitlines() if line.strip()]
        if lines:
            try:
                parsed = json.loads(lines[-1])
            except json.JSONDecodeError:
                parsed = None
            if isinstance(parsed, dict):
                counts = parsed
        tests, skipped = counts.get("tests"), counts.get("skipped")
        if not isinstance(tests, int) or not isinstance(skipped, int):
            # Немає лічильників — немає й вердикту: «не виконався» і «виконався
            # зелено» мусять лишатися різними відповідями.
            tests = skipped = None
        skipped_ids = counts.get("skippedIds")
        return NestedRun(
            name, result.returncode, tests, skipped, result.stderr[-3000:],
            tuple(skipped_ids) if isinstance(skipped_ids, list) else (),
        )

    def run_environments(self, environment: dict) -> list[NestedRun]:
        """Названі прогони одним процесом — лічильники беруться з нього самого.

        Один процес на всі цілі — те, чим цей ґард був і до #1270: ціна
        лічильників не в тому, щоб гнати кожну ціль окремо.
        """
        runs = [self.run_named("названі прогони", ENVIRONMENT_TARGETS, environment, TESTS_DIR)]
        # Файлів у варіанті більше, ніж модулів: модуль ганяємо один раз на
        # (варіант, модуль), а не на кожен файл, який він називає.
        for root, module in sorted({(root, module)
                                    for root, _name, modules in fixture_naming_tests()
                                    for module in modules}):
            runs.append(self.run_fixture(root, module, environment))
        return runs

    def run_fixture(self, root: Path, module: str, environment: dict) -> NestedRun:
        """Тест фікстури — у КОПІЇ дерева: фікстура пише артефакти поруч із собою."""
        with tempfile.TemporaryDirectory(prefix="fixture-environment-") as temporary:
            work = Path(temporary) / "root"
            shutil.copytree(root / "scripts", work / "scripts")
            return self.run_named("%s/%s" % (root.name, module), (module[:-3],),
                                  environment, work / "scripts" / "tests")

    def assertRunsGreen(self, runs: list[NestedRun]) -> None:
        failures = [run for run in runs if not run.green]
        self.assertTrue(runs, "жодного прогону не зроблено")
        self.assertEqual(
            [], [run.describe() for run in failures],
            "названі прогони мусять бути зелені Й ВИКОНАНІ: код виходу нуль при "
            "пропущеному тесті — не зелень (#1270)\n"
            + "\n".join(run.output for run in failures),
        )

    @staticmethod
    def runs_that_stayed_green(verdicts: dict[str, str]) -> list[str]:
        """Названі прогони, які НЕ почервоніли, — і саме вони є вироком ґарда.

        Одна функція на ґард і на демонстрацію: якби демонстрація ухвалювала
        вердикт власним рядком, вона пінила б себе, а не правило.
        """
        return sorted(name for name, verdict in verdicts.items() if verdict == "green")

    def run_verdicts(self, targets: tuple[str, ...], environment: dict,
                     cwd: Path) -> dict[str, str]:
        """Поіменний вердикт прогону: `test.id()` → `green` / `red` / `skipped`.

        `run_named` каже, ЧИ прогін зелений; тут питають, КОТРИЙ із названих
        лишився зеленим під зламаними підставними, — і без імені вердикт ґарда
        не назвав би винного.
        """
        result = subprocess.run(
            [sys.executable, "-c", NESTED_VERDICTS, *targets],
            cwd=str(cwd), check=False, capture_output=True, text=True,
            env=environment, timeout=NESTED_TIMEOUT_SECONDS,
        )
        lines = [line for line in result.stdout.strip().splitlines() if line.strip()]
        if not lines:
            return {}
        try:
            parsed = json.loads(lines[-1])
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def run_verdicts_in(self, root: Path, environment: dict) -> dict[str, str]:
        """Вердикти КОЖНОГО названого прогону в копії `root` — разом із фікстурними.

        Фікстурні модулі ганяються в копії ФІКСТУРИ (вона пише артефакти поруч із
        собою) — тією самою, що бере `run_fixture`, лише взятою з копії дерева,
        тож злам підставного файлу в ній видно.
        """
        verdicts = dict(self.run_verdicts(ENVIRONMENT_TARGETS, environment,
                                          root / "scripts" / "tests"))
        for fixture, module in sorted({(fixture, module)
                                       for fixture, _name, modules in fixture_naming_tests()
                                       for module in modules}):
            label = "%s: %s" % (fixture.relative_to(REPO_ROOT), module)
            with tempfile.TemporaryDirectory(prefix="fixture-environment-") as temporary:
                work = Path(temporary) / "root"
                shutil.copytree(root / fixture.relative_to(REPO_ROOT) / "scripts",
                                work / "scripts")
                found = self.run_verdicts((module[:-3],), environment,
                                          work / "scripts" / "tests")
            verdicts[label] = ("green" if found and all(v == "green" for v in found.values())
                               else "red")
        return verdicts

    def test_a_run_that_executes_no_stand_in_is_a_finding(self):
        """Демонстрація мусить вижити: прогін, який НІЧОГО не запускає, ВИДНО (#1294).

        Той самий код, що ґард вище (`broken_tool_text`, `run_verdicts`,
        `runs_that_stayed_green`), на дереві фікстури `empty_shape/pinned`, зі
        зламаним `gate.sh`:

          * `INHERITING_RUN` запускає підставний — під зламаним він червоний, і
            ґард його пропускає (порожній перелік знахідок);
          * `UNRELATED_RUN` не запускає нічого — він зелений і під зламаним, і
            ґард МУСИТЬ назвати його знахідкою.

        Без другої половини «кожен названий прогін мусить почервоніти» було б
        зеленим і на дереві, де не зламано нічого: твердження перевіряється
        прогоном з ОБОХ боків, а не припускається.
        """
        fixture = FIXTURES / "empty_shape" / "pinned"
        with tempfile.TemporaryDirectory(prefix="executes-nothing-") as temporary:
            work = Path(temporary) / "root"
            shutil.copytree(fixture / "scripts", work / "scripts")
            tests = work / "scripts" / "tests"
            (tests / "test_inherits.py").write_text(INHERITING_RUN, encoding="utf-8")
            (tests / "test_unrelated.py").write_text(UNRELATED_RUN, encoding="utf-8")
            marker = work / "marker"
            marker.mkdir()
            (marker / "head-name").write_text("release\n", encoding="utf-8")
            (work / "scripts" / "gate.sh").write_text(
                broken_tool_text("gate.sh"), encoding="utf-8")

            executing = self.run_verdicts(("test_inherits",), dict(STRIPPED), tests)
            nothing = self.run_verdicts(("test_unrelated",), dict(STRIPPED), tests)

        self.assertEqual(["red"], sorted(executing.values()),
                         "прогін, який ЗАПУСКАЄ зламаного підставного, мусить "
                         "почервоніти: %s" % executing)
        self.assertEqual(["green"], sorted(nothing.values()),
                         "прогін, який нічого не запускає, лишається зеленим — у "
                         "цьому й ціна: %s" % nothing)
        self.assertEqual([], self.runs_that_stayed_green(executing),
                         "ґард мусить пропустити прогін, який справді залежить від "
                         "підставного")
        self.assertEqual(["test_unrelated.UnrelatedTest.test_adds"],
                         self.runs_that_stayed_green(nothing),
                         "ґард мусить назвати знахідкою прогін, який не запустив "
                         "жодного підставного")

        # І ЧОМУ кандидат тікета (#1294) лишається зелений — не «так склалося», а
        # тому що мутація ламає ВИКОНАННЯ, а не вміст. `SAMPLE` — вхідний текст
        # сканера; під `break_the_run` його ТІЛО незмінне, тож і `scan(SAMPLE)`
        # незмінний, і зелений `ScannerTest` справді не запускав підставного.
        sample = embedded_programs(TESTS_DIR / "test_empty_shape_mutants.py")["SAMPLE"]
        broken_sample = break_the_run(sample)
        self.assertEqual(
            sample.partition("\n")[2], broken_sample.partition("\n")[2],
            "мутація зачепила ВМІСТ, а не виконання — тоді «зелений» `ScannerTest` "
            "нічого не доводив би",
        )
        self.assertNotEqual(sample.split("\n")[0], broken_sample.split("\n")[0],
                            "шебанг мусив змінитися — інакше підставний не зламаний")

    def test_every_named_run_reddens_when_every_stand_in_is_broken(self):
        """Зв'язок «названий прогін ↔ підставний» доводиться ВИКОНАННЯМ (#1294).

        `ENVIRONMENT_TARGETS` — НАЗВАНИЙ список (виведення з AST прибрано, #1279),
        і ціна названого в тому, що ніщо не перевіряло, чи названий прогін
        справді ЗАПУСКАЄ зареєстрованого підставного. Тут це доводиться
        прогоном, а не читанням:

          * у копії дерева (`tmp_budget.link_tree` — та сама копія, що в
            `guard_witnesses`) кожен названий прогін мусить бути зелений;
          * потім ламається КОЖЕН зареєстрований підставний (`apply_breakages`) —
            і кожен названий прогін мусить почервоніти.

        Прогін, який лишився зелений під зламаними підставними, або не запускає
        жодного з них, або не читає їхнього вердикту — для ґарда це те саме:
        успадковане оточення ламає підставного, і прогін, який не зауважує його
        відмови, свідком не був (тікет #1294).

        Це доводить, що прогін запускає ЯКОГОСЬ зареєстрованого підставного й
        читає його вердикт, — і НЕ доводить, що він запускає САМЕ ТОГО, за якого
        його тримали: поіменний зв'язок вимагав би окремої мутації на кожного
        підставного. Межа названа тут, у коді, а не лише в звіті.
        """
        with tempfile.TemporaryDirectory(prefix="broken-stand-ins-") as temporary:
            root = tmp_budget.link_tree(REPO_ROOT, Path(temporary))
            breakages = stand_in_breakages(root)
            self.assertTrue(
                breakages,
                "жодного підставного не зламано — перевірка порожня, і зелена вона "
                "про будь-що: %s" % breakages,
            )
            intact = self.run_verdicts_in(root, dict(STRIPPED))
            apply_breakages(root)
            broken = self.run_verdicts_in(root, dict(STRIPPED))

        self.assertEqual(sorted(intact), sorted(broken),
                         "обидва прогони мусять бачити той самий набір цілей — "
                         "інакше червоне в копії не від зламу")
        self.assertEqual(
            [], sorted(name for name, verdict in intact.items() if verdict != "green"),
            "копія з ЦІЛИМИ підставними мусить бути зелена — інакше червоне в "
            "копії зі зламаними не від зламу",
        )
        self.assertEqual(
            [], self.runs_that_stayed_green(broken),
            "названий прогін, зелений при ЗЛАМАНИХ підставних, не запускає жодного "
            "з них і не читає їхнього вердикту — свідком він не був (#1294)",
        )

    def test_a_named_run_that_is_skipped_is_not_green(self):
        """Прогін, який нічого не виконав, — не зелений (скіп-сліпота, #1270).

        `@unittest.skip` лишає код виходу нулем, і ґард, який дивився лише на код,
        був зелений на прогоні, що не виконав нічого, — знахідка огляду #1267.
        Прогін тут справжній: підпроцес у копії фікстури, зі скіпом, дописаним у
        копію. Саме тому це дешевий свідок правила: гнати заради нього всі названі
        прогони означало б платити секундами CI за те, що вже видно з одного.
        """
        fixture = FIXTURES / "verdict_field" / "pinned"
        with tempfile.TemporaryDirectory(prefix="skipped-run-") as temporary:
            work = Path(temporary) / "root"
            shutil.copytree(fixture / "scripts", work / "scripts")
            module = work / "scripts" / "tests" / "test_gate.py"
            module.write_text(
                module.read_text(encoding="utf-8").replace(
                    "class GateTest(unittest.TestCase):",
                    '@unittest.skip("мутація: прогін більше не виконується")\n'
                    "class GateTest(unittest.TestCase):",
                ),
                encoding="utf-8",
            )
            run = self.run_named("скіпнутий прогін", ("test_gate",), dict(STRIPPED),
                                 work / "scripts" / "tests")

        self.assertFalse(run.green, run.describe())
        with self.assertRaises(AssertionError):
            self.assertRunsGreen([run])

    def test_the_guard_sees_only_the_runs_it_is_named(self):
        """ЦІНА БЕЗ ВИВЕДЕННЯ — прогоном, а не словом (#1279).

        Виведення свідка з AST прибрано: ТОТАЛЬНОГО не існує, і це доведено
        (`test_witness_derivation`: той самий AST дає і «запускає», і «не
        запускає»; функція з «невідомо» мусила б сказати «невідомо» саме тут).
        Лишився НАЗВАНИЙ список прогонів — і його ціна показана тут,
        тим самим кодом, що й ґард (`run_named`, `HOSTILE`, `NestedRun`), на
        дереві з підставним `gate.sh`, який успадковує оточення:

          * прогін НАЗВАНО: порожнє оточення зелений, отруєне червоний — отрута
            дійшла до успадкованого підставного, і сторож це бачить;
          * той самий прогін НЕ названо: він не запускається взагалі, і вердикт
            про дерево зелений — хоч підставний у ньому й успадковує оточення.

        Друга половина — і є ціна: список розширюють руками, разом із прогоном, і
        ніщо не червоніє, якщо цього не зробити. Автоматичного покриття нових
        підставних немає — виведення його не давало (воно давало хибних свідків),
        а реєстр ловить незареєстроване, не неназване.
        """
        fixture = FIXTURES / "empty_shape" / "pinned"
        with tempfile.TemporaryDirectory(prefix="named-runs-only-") as temporary:
            work = Path(temporary) / "root"
            shutil.copytree(fixture / "scripts", work / "scripts")
            tests = work / "scripts" / "tests"
            (tests / "test_inherits.py").write_text(INHERITING_RUN, encoding="utf-8")
            (tests / "test_unrelated.py").write_text(UNRELATED_RUN, encoding="utf-8")
            marker = work / "marker"
            marker.mkdir()
            (marker / "head-name").write_text("release\n", encoding="utf-8")

            stripped = self.run_named("названий, порожнє оточення", ("test_inherits",),
                                      dict(STRIPPED), tests)
            poisoned = self.run_named("названий, отруєне оточення", ("test_inherits",),
                                      {**os.environ, **HOSTILE}, tests)
            unnamed = self.run_named("неназваний, отруєне оточення", ("test_unrelated",),
                                     {**os.environ, **HOSTILE}, tests)

        self.assertEqual((1, 0), (stripped.tests, stripped.skipped), stripped.describe())
        self.assertTrue(stripped.green,
                        "порожнє оточення: підставний мусить спрацювати\n" + stripped.describe())
        self.assertEqual((1, 0), (poisoned.tests, poisoned.skipped), poisoned.describe())
        self.assertFalse(poisoned.green,
                         "отрута мусить дійти до успадкованого підставного — це те, що "
                         "сторож ловить, коли прогін НАЗВАНО\n" + poisoned.describe())
        self.assertEqual((1, 0), (unnamed.tests, unnamed.skipped), unnamed.describe())
        self.assertTrue(unnamed.green,
                        "неназваний прогін не запускається — і сторож про нього нічого "
                        "не каже: саме в цьому ціна\n" + unnamed.describe())

    def test_a_stripped_environment_is_enough_for_every_named_run(self):
        """Порожнє оточення: підставний не читає зі спадку нічого.

        Якщо названий прогін зеленіє лише тоді, коли йому дісталися змінні
        раннера, — він читає спадок, і CI це побачить, а локальна зелень ні.
        """
        self.assertRunsGreen(self.run_environments(dict(STRIPPED)))

    def test_a_poisoned_inherited_environment_does_not_reach_any_stand_in(self):
        """Отруєне оточення: затьмарені команди не доходять до інструмента.

        `BASH_FUNC_printf%%` і решта роблять підставний інструмент червоним, якщо
        той успадковує оточення. Саме так упав CI #1260 — шість падінь із кодом
        2 при незмінних файлах фікстури.
        """
        self.assertRunsGreen(self.run_environments({**os.environ, **HOSTILE}))


if __name__ == "__main__":
    unittest.main()
