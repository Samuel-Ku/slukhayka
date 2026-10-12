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
репозиторію, які запускають підставний інструмент, і дві перевірки на кожну:

  * **оточення**: кожен свідок мусить лишитися зеленим і з порожнім оточенням
    (фікстура не читає зі спадку нічого), і з отруєним (затьмарені команди не
    доходять до інструмента). Обидва прогони — вкладені процеси `unittest`;
  * **форма**: якщо фікстура вдає інструмент, який у репозиторії Є, пін на форму
    мусить існувати й бути названим тут. Якщо інструмент зовнішній (`gh`, `adb`,
    Gradle), живий пін вимагає того, чого джоба `script-tests` не має:
    `GH_TOKEN`, пристрою, прогону збірки (у джобі — лише checkout і
    `python3 -m unittest`, `ci.yml:643-655`). Це названа межа, не мовчазний
    пропуск.

УРОК П'ЯТИ КІЛ ОГЛЯДУ. Кожне коло знаходило новий **неперевірений
перемикач** у реєстрі: маркер модуля (програма проходила чужим маркером),
`runs=False` (справжній підставний оголошувався «текстом-даними» й випадав із
пінів), `witness` (свідка можна підмінити сусідом або стороннім тестом),
`own_poison` (виключення з прогонів підрядком в імені), і, нарешті, сама
**множина** свідків: доки зі списку зникали однакові імена, новий запис, чиє
виведення сідало на вже пінований свідок, не змінював нічого — підставний
інструмент, який успадковує оточення, додавався й «покривався» тестом, який
його ніколи не запускає. Спільне в них одне: **запис міг щось оголосити, і ніщо
не перевіряло, що оголошене — правда**. Тому полів-перемикачів тут більше немає:

  * **свідка не оголошують — його виводять** з місця програми: свідок — тест,
    який дістає місце, де програму створено (`Fixture.build`, `self.adb`,
    константа модуля). Спершу — тест, що сам його створює (власник), далі тести
    того самого класу, далі будь-який тест модуля; підмінити нічого, бо поля
    немає. І дістає він його **власним тілом**, а не позиченим `setUp`: `setUp`
    спільний на весь клас, тож програму, яку він створив, «дістає» будь-який
    тест класу, нічого не роблячи. Чотири підставні названі тілом тесту саме
    тому — двоє з них довелося для цього переписати на `self.fake_adb.parent` і
    `self.java.parent.parent`. Виняток один: програму, **покладену у файл** у
    `setUp` (`self.adb.write_text(<текст>)`), тест дістає через хелпер
    (`environment`), і `setUp` лишається останньою спробою. Програма,
    **присвоєна** під `setUp` — у самому `setUp` або в хелпері, який він кличе, —
    свідка так не дістає: якщо її не назве тест власним тілом, свідка немає (це
    відмова, а не «клас її запускає»; M14 рецензента, і H п'ятого кола, де
    ловився лише скоуп із буквальним іменем `setUp`);
  * **місце мусить щось називати**: якір-ресивер (`self` у
    `self.write_tool(<текст>)`) є в посиланнях БУДЬ-ЯКОГО тесту класу, тож така
    програма свідка не дістає взагалі — `_location` лишає замість цілі `виклик`
    (знахідка C п'ятого кола);
  * **виведення піниться ПО КОЖНІЙ програмі**, а не множиною імен: `at → свідок`
    видно в канарейці, і програма без свідка стоїть у ній теж — з `None`;
  * **виключити нікого не можна**: у прогонах оточення бере участь кожен запис, а
    фікстурний інструмент ганяє свідок, який НАЗИВАЄ його файл
    (`fixtures/*/*/scripts/tests/test_*.py`) у копії дерева. Модуль, який імені
    не називає, інструмента не запускає — той самий прийом, що `paired_modules`
    у `scripts/empty_shape_mutants.py`, і помиляється він у бік ВІДМОВИ;
  * **форма або піниться, або межа названа — і це видно із самого запису**:
    `real` не може бути гаслом, бо `shape_pin` і `real` ходять парою (пін ⟺
    названий справжній інструмент), кожне ім'я в `real` мусить існувати в дереві
    або в `PATH`, а фікстурний інструмент піниться завжди: його справжній
    лежить у цьому ж дереві. Пін мусить назвати у СВОЄМУ тілі те, що пінить —
    файл фікстури (`gate.sh`) або маркер програми (`--cherry-mark`): без цього
    `shape_pin` був оголошуваним, і піном сходив будь-який тест із модуля, де
    згадано теку фікстури, — аж до `test_every_registered_name_is_unique`
    (знахідка D п'ятого кола). `real=()` — єдине, чим запис каже «живого
    порівняння тут бути не може», і саме тому список таких записів піниться:
    перевести запис із піна на межу можна лише разом із цією правкою;
  * `test_the_registry_has_no_declared_switches` пінить сам НАБІР полів: нове
    поле-оглядач червонить тест, і його доведеться обґрунтувати тут.

МЕЖІ ВИВЕДЕННЯ СВІДКА названі, а не закриті: свідка вибирають за ЗГАДКОЮ якоря,
а не за виконанням програми.

  * Тест, який лише згадує `self.adb` (читає шлях, перевіряє журнал) і не
    запускає інструмент, може стати свідком: ґард тоді зелений, хоч тест, який
    інструмент запускає, під оточенням раннера червоний. Статично це не
    розрізнити — «згадує» і «запускає» це те саме посилання в AST, — тож свідок
    і названий **вимірювальною допомогою** (кого саме ганяти під двома
    оточеннями), а не доказом, що програма під ними побувала;
  * у програми, якої не називає власним тілом жоден тест (`GRADLEW`,
    `Fixture.build: java`, `FAKE_GH`), свідком стає перший тест, який дістає її
    через `setUp`: це «клас його запускає», а не «цей тест»;
  * закрити ці дві межі можна було б прогоном УСІХ тестів класу-власника під
    обома оточеннями — перевірено: шість таких класів дають 14.3 с проти 2.5 с на
    оточення, і це в джобу `script-tests` уже не вміщається.

Що `EnvironmentTest` справді доводить: КОЖЕН виведений свідок зелений і з
порожнім оточенням, і під отруєним; якщо підставний усередині нього успадкує
оточення раннера — свідок червоний. Чого не доводить: що свідок цей підставний
ЗАПУСКАЄ (межі вище).

Покриття перевіряється СТРУКТУРНО, з трьох боків.

  * `fixtures/` — по файлах: жоден файл-інструмент не лишається без запису (і
    жоден запис не вказує на неіснуючий файл), а кожен файл мусить мати тест,
    який НАЗИВАЄ його ім'я. Сама лише наявність `test_*.py` поруч свідком не
    була: новий файл із записом і прозою проходив зеленим (M10b);
  * `scripts/tests/**/*.py` — по AST: кожен ЛІТЕРАЛ, значення якого ПОЧИНАЄТЬСЯ
    шебангом, є вбудованою програмою, і кожна мусить бути названа ЗА СВОЇМ
    МІСЦЕМ (`Program.at`), а не «хоч якимсь маркером модуля». Прив'язка до місця
    навмисна: найімовірніший спосіб написати третього підставного `git` —
    скопіювати сусіднього, і копія мусить бути ВИДНА як окрема програма. Маркер
    лишається перевіркою, що запис не збрехав про вміст, і той самий маркер
    мусить назвати у своєму тілі пін на форму;
  * читання змінних — по тексту програми: усе, що підставний читає з оточення,
    мусить мати власний префікс запису або бути названим у `reads` (входи
    справжнього інструмента — `JAVA_HOME`, `TMPDIR`); змінні, які ставить сама
    оболонка (`BASH_SOURCE`, `PWD`, `PPID`), спадком не бувають. Дозвіл без
    використання — теж червоний: вигаданих дозволів не тримаємо.

Межі названі прямо:

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
    не обіцяно: скан (разом із канарейкою) не бачить там жодної програми.
    Фікстура пише СУБʼЄКТ під тестом і цільовий набір — те, що справжній
    `mutation_python_runner.py` і мусить виконати; це не імперсонація;
  * `real=()` — це слово автора: «живого порівняння цього підставного з
    оригіналом у цій джобі бути не може» (`gh` без `GH_TOKEN`, `adb` без
    пристрою, Gradle без прогону збірки, `npm` без мережі). Механічно
    перевіряється не сама неможливість, а те, що запис не сходить із піна на
    межу мовчки: перелік записів без піна піниться, і кожен із них мусить мати
    названу межу.

Дві фікстури (`verdict_field`, `empty_shape`) отруюють оточення і самі, своїми
`EnvironmentTest`; тут вони в реєстрі, і їхні свідки теж ганяються — у копії
дерева, щоб не смітити в репозиторії.
"""

import ast
import dataclasses
import functools
import importlib
import inspect
import io
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

# Скільки чекати на вкладений прогін. Таймаут — це провал, не «зелено».
NESTED_TIMEOUT_SECONDS = 600

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

    Полів-перемикачів немає навмисно: `witness` виводиться з місця програми,
    виключити запис із прогонів нічим. Усе, крім `boundary`, має механічну
    перевірку — і сам набір полів пінить
    `RegistryTest.test_the_registry_has_no_declared_switches`. `boundary` — це
    проза, і перевіряється лише те, що вона не порожня та що перелік записів із
    нею піниться; саму неможливість живого піна механічно не довести.

    `real` і `shape_pin` ходять парою: пін без названого справжнього інструмента
    — гасло, а названий інструмент без піна — обіцянка, якої ніхто не тримає.
    """

    name: str
    where: tuple[str, ...]      # файли (відносно кореня репозиторію)
    prefix: str = ""            # власний префікс змінних, які він читає
    reads: tuple[str, ...] = ()  # названі входи справжнього інструмента
    real: tuple[str, ...] = ()  # справжні інструменти, З ЯКИМИ ЗВІРЯЮТЬ форму:
                                # шлях у дереві або ім'я в PATH. Порожньо —
                                # звіряти нема з чим, і тоді межа обов'язкова
    shape_pin: str | None = None  # module.Class.test — пін на форму: його тіло
                                  # мусить назвати файл фікстури або маркер
    boundary: str = ""          # чому живого піна немає — названа межа
    programs: tuple[Program, ...] = ()  # вбудовані програми цього запису


CASES = (
    StandIn(
        name="підставний recorder фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutation_trace.py",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutation_trace.py",
        ),
        real=("scripts/mutation_trace.py",),
        shape_pin=("test_verdict_field_mutants.HistoricalStateTest"
                   ".test_the_fixture_cli_is_shaped_like_the_real_one"),
    ),
    StandIn(
        name="підставний mutate.sh фікстури verdict_field",
        where=(
            "scripts/tests/fixtures/verdict_field/pinned/scripts/mutate.sh",
            "scripts/tests/fixtures/verdict_field/unpinned/scripts/mutate.sh",
        ),
        prefix="VERDICT_FIXTURE_",
        real=("scripts/mutate.sh",),
        shape_pin=("test_verdict_field_mutants.HistoricalStateTest"
                   ".test_the_fixture_verdict_rule_is_the_real_one"),
        boundary=("фікстура вдає ЛАНКУ інструмента (артефакт → лічильники → "
                  "вердикт → запис), а не його CLI: у неї нуль argv, у "
                  "справжнього `scripts/mutate.sh` на нуль argv — usage. Форму "
                  "саме цієї ланки пінить ПРАВИЛО вердикту — тим самим рядком, "
                  "що й у справжнього інструмента, — а не argv."),
    ),
    StandIn(
        name="підставний gate.sh фікстури empty_shape",
        where=(
            "scripts/tests/fixtures/empty_shape/pinned/scripts/gate.sh",
            "scripts/tests/fixtures/empty_shape/unpinned/scripts/gate.sh",
        ),
        real=("scripts/merge-pr.sh",),
        shape_pin=("test_empty_shape_mutants.HistoricalStateTest"
                   ".test_the_fixture_gate_is_shaped_like_the_real_one"),
    ),
    StandIn(
        name="підставний gradlew і jdk фікстури test_mutate",
        where=("scripts/tests/test_mutate.py",),
        prefix="MUTATE_TEST_",
        boundary=("живий пін проти Gradle — це прогін збірки; джоба `script-tests` "
                  "робить лише `python3 -m unittest` (`ci.yml:643-655`), і ця "
                  "робота забороняє Gradle. Форму виклику пінять тести: журнал "
                  "інвокацій мусить мати `:app:testPureJvm` і "
                  "`-Ptest.selectedClasses=<FQCN>`. `real` порожній навмисно: "
                  "підставні вдають `./gradlew` і `java`, а звіряти форму з ними "
                  "тут нема чим."),
        programs=(
            Program("GRADLEW", "MUTATE_TEST_GRADLE_LOG"),
            Program("Fixture.build: java", "openjdk version"),
        ),
    ),
    StandIn(
        name="підставний gh фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        prefix="FAKE_GH_",
        boundary=("живий пін проти `gh` вимагає авторизації (`GH_TOKEN`), якої "
                  "джоба `script-tests` не передає (`ci.yml:643-655`), тож "
                  "`real` порожній: звіряти нема з чим. "
                  "Підставний падає кодом 3 на незнайомій формі, тож дрейф форми "
                  "виклику не мовчить."),
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний старий git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        real=("git",),
        shape_pin=("test_merge_pr.GitStandInShapeTest"
                   ".test_a_stub_refuses_an_option_the_real_git_has"),
        boundary=("argv у нього — не свій, а справжнього git: обгортка прозора "
                  "(`exec git \"$@\"`), відмова — рівно на `--path-format`, який "
                  "справжній git знає. Прозорість і справжність опціону пінить "
                  "`GitStandInShapeTest` проти встановленого git."),
        programs=(Program("FAKE_OLD_GIT", "--path-format"),),
    ),
    StandIn(
        name="підставний зламаний git фікстури test_merge_pr",
        where=("scripts/tests/test_merge_pr.py",),
        real=("git",),
        shape_pin=("test_merge_pr.GitStandInShapeTest"
                   ".test_the_patch_comparison_stub_refuses_a_real_git_revision_option"),
        boundary=("та сама прозора обгортка; ламає рівно `--cherry-mark`, який "
                  "справжній git знає."),
        programs=(Program("FAKE_BROKEN_GIT", "--cherry-mark"),),
    ),
    StandIn(
        name="підставний gh фікстури test_review_pass",
        where=("scripts/tests/test_review_pass.py",),
        prefix="FAKE_GH_",
        boundary=("той самий зовнішній `gh`, що й у test_merge_pr: без `GH_TOKEN` "
                  "живого піна немає, `real` порожній. Підставний падає кодом 3 "
                  "на незнайомій формі."),
        programs=(Program("FAKE_GH", "FAKE_GH_DIR"),),
    ),
    StandIn(
        name="підставний gh фікстури test_issue_premise",
        where=("scripts/tests/test_issue_premise.py",),
        boundary=("той самий зовнішній `gh`, що й у test_merge_pr: без `GH_TOKEN` "
                  "живого піна немає, `real` порожній. Підставний віддає сталі "
                  "відповіді (`GH_STUB`: список із двох issue), тож форма тут — "
                  "не argv gh, а те, що ґард премиси читає саме з нього; "
                  "справжнього gh у джобі `script-tests` немає (`ci.yml:643-655`), "
                  "і звіряти форму нема з чим."),
        programs=(Program("GH_STUB", "issue list"),),
    ),
    StandIn(
        name="підставний adb фікстури test_wide_window",
        where=("scripts/tests/test_wide_window.py",),
        prefix="FAKE_ADB_",
        boundary=("живий пін проти `adb` вимагає пристрою або емулятора; джоба "
                  "`script-tests` його не піднімає (`ci.yml:643-655`), тож `real` "
                  "порожній. "
                  "Підставний падає кодом 2 на незнайоме слово, тож форма виклику "
                  "не може розійтися мовчки."),
        programs=(Program("DeviceRunTest.setUp: self.adb", "FAKE_ADB_LOG"),),
    ),
    StandIn(
        name="підставний adb фікстури test_android_emulator_readiness",
        where=("scripts/tests/test_android_emulator_readiness.py",),
        prefix="FAKE_ADB_",
        boundary=("той самий зовнішній `adb`; `real` порожній, підставний падає на "
                  "незнайоме слово, тож форма виклику не може розійтися мовчки."),
        programs=(Program("AndroidEmulatorReadinessTest.setUp: self.fake_adb",
                          "FAKE_ADB_STATE"),),
    ),
    StandIn(
        name="підставні gradlew і java фікстури test_test_wrappers",
        where=("scripts/tests/test_test_wrappers.py",),
        prefix="WRAPPER_TEST_",
        reads=("JAVA_HOME", "TMPDIR"),
        boundary=("живий пін проти Gradle і JDK — це прогін збірки; джоба "
                  "`script-tests` його не робить (`ci.yml:643-655`), тож `real` "
                  "порожній. Журнал "
                  "інвокацій пінить форму (шість партицій і "
                  "`-Ptest.selectedClasses=`), а `SLUKHAYKA_JAVA_HOME` — те, що "
                  "читає справжній wrapper. `JAVA_HOME` і `TMPDIR` — входи "
                  "справжнього `gradlew`, не фікстури."),
        programs=(
            Program("FullSuiteWrapperTest.setUp: self.java", "21.0.8"),
            Program("FullSuiteWrapperTest.write_gradle: self.fake_gradle",
                    "WRAPPER_TEST_LOG"),
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
        boundary=("Gradle і npm — зовнішні, `real` порожній; журнал пінить точні "
                  "три команди "
                  "веб-перевірки (`npm ci`, `tsc --noEmit`, `vitest run`)."),
        programs=(
            Program("ChangedTestWrapperWebCheckTest.setUp: self.java", "21.0.8"),
            Program("ChangedTestWrapperWebCheckTest.write_web_command: target",
                    "cwd=%s"),
            Program("ChangedTestWrapperWebCheckTest.write_gradle: self.gradle",
                    "gradle %s"),
        ),
    ),
    StandIn(
        name="SAMPLE у test_empty_shape_mutants — вхідний текст, не інструмент",
        where=("scripts/tests/test_empty_shape_mutants.py",),
        reads=("HEAD_REF",),
        boundary=("це не підставний інструмент, а вхідний текст для "
                  "`empty_shape_mutants.shapes()`: жоден тест його не запускає. "
                  "Названий тут, бо він виглядає як програма (шебанг), і мовчазне "
                  "виключення було б дірою в покритті; `HEAD_REF` — змінна "
                  "прикладу, не читання оточення, і справжнього інструмента, з "
                  "яким звіряти форму, теж немає: `real` порожній."),
        programs=(Program("SAMPLE", "detached HEAD"),),
    ),
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

    Голий ресивер — теж «виклик»: `self.write_tool(<текст>)` не називає програму,
    бо `self` є в посиланнях БУДЬ-ЯКОГО тесту класу (`_body_references` додає
    `path.split(".")[0]`), тож ціль «`self`» робила б свідком перший-ліпший тест
    (п'яте коло огляду, знахідка C). Місце, яке нічого не називає, свідка не
    дістає — реєстр червоний, а не зелений позиченим свідком.
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
                          and candidate not in {"self", "cls"}
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


# --- виведення свідка ---------------------------------------------------------


@dataclasses.dataclass
class Scope:
    """Функція чи метод модуля: що згадує і що викликає."""

    key: str
    cls: str
    name: str
    line: int
    is_test: bool
    refs: set
    calls: set


def _attribute_path(node: ast.Attribute) -> str | None:
    parts = []
    current: ast.AST = node
    while isinstance(current, ast.Attribute):
        parts.append(current.attr)
        current = current.value
    if not isinstance(current, ast.Name):
        return None
    parts.append(current.id)
    return ".".join(reversed(parts))


def _body_references(function: ast.AST) -> tuple[set, set]:
    refs: set = set()
    calls: set = set()
    stack = list(function.body)
    while stack:
        node = stack.pop()
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            refs.add(node.name)
            continue
        if isinstance(node, ast.Name):
            refs.add(node.id)
        elif isinstance(node, ast.Attribute):
            path = _attribute_path(node)
            if path:
                refs.add(path)
                refs.add(path.split(".")[0])
        elif isinstance(node, ast.Call):
            callee = node.func
            if isinstance(callee, ast.Name):
                calls.add(callee.id)
            elif isinstance(callee, ast.Attribute):
                calls.add(callee.attr)
        stack.extend(ast.iter_child_nodes(node))
    return refs, calls


@functools.lru_cache(maxsize=None)
def module_index(module: Path) -> tuple[dict[str, Scope], dict[str, tuple[str, ...]]]:
    """Скоупи модуля (за ключем `Клас.метод`) і його класи з базами.

    Кеш навмисний: один і той самий модуль питають раз на програму, а програм
    шістнадцять — розбір `test_merge_pr.py` зайвих десять разів коштував би
    часу джоби, нічого не додаючи.
    """
    source = module.read_text(encoding="utf-8")
    tree = ast.parse(source)
    parents = {child: parent for parent in ast.walk(tree)
               for child in ast.iter_child_nodes(parent)}
    classes: dict[str, tuple[str, ...]] = {}
    scopes: dict[str, Scope] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            classes[node.name] = tuple(base.id for base in node.bases
                                       if isinstance(base, ast.Name))
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            cls = ""
            current = node
            while current in parents:
                current = parents[current]
                if isinstance(current, ast.ClassDef):
                    cls = current.name
                    break
                if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    break  # вкладена функція — не метод
            key = f"{cls}.{node.name}" if cls else node.name
            refs, calls = _body_references(node)
            scopes[key] = Scope(key, cls, node.name, node.lineno,
                                node.name.startswith("test_"), refs, calls)
    return scopes, classes


def _resolve(scope: Scope, name: str, scopes: dict[str, Scope]) -> str | None:
    for candidate in (f"{scope.cls}.{name}" if scope.cls else "", name,
                      f"{name}.__init__"):
        if candidate and candidate in scopes:
            return candidate
    for key, candidate in scopes.items():
        if candidate.name == name:
            return key
    return None


def _scope_closure(keys: list[str], scopes: dict[str, Scope]) -> set:
    """Скоупи, досяжні з даних за викликами: самі ключі й усе, що вони кличуть."""
    seen: set = set()
    queue = list(keys)
    while queue:
        current = queue.pop()
        if current in seen or current not in scopes:
            continue
        seen.add(current)
        for call in scopes[current].calls:
            called = _resolve(scopes[current], call, scopes)
            if called:
                queue.append(called)
    return seen


def _reachable(key: str, scopes: dict[str, Scope],
               classes: dict[str, tuple[str, ...]],
               through_setup: bool = False) -> set:
    """Посилання, досяжні з ВЛАСНОГО тіла тесту: його рядки й те, що він кличе.

    `setUp` додається лише за `through_setup`, і лише як остання спроба: він
    спільний на весь клас, тож програму, яку він створив, «дістає» будь-який
    тест класу, нічого не роблячи (п'яте коло огляду, мутація з `self.slow_tool`).
    `setUp` шукається і в базових класах: `MutationRunTest` успадковує свій
    `setUp` від `MutateFixtureTest`, і фікстура народжується саме там.
    """
    keys = [key]
    if through_setup and key in scopes:
        cls = scopes[key].cls
        seen_classes: set = set()
        while cls and cls not in seen_classes:
            seen_classes.add(cls)
            if f"{cls}.setUp" in scopes:
                keys.append(f"{cls}.setUp")
            bases = classes.get(cls, ())
            cls = bases[0] if bases else ""
    refs: set = set()
    for current in _scope_closure(keys, scopes):
        refs |= scopes[current].refs
    return refs


def _creator_key(node: ast.AST, parents: dict) -> str:
    """Ключ скоупу, у якому програму створено: `Клас.метод` або ім'я функції.

    Порожньо для програми на рівні модуля (константа `GRADLEW`): у неї скоупу
    немає, і `setUp` її не створює.
    """
    current = node
    while current in parents:
        current = parents[current]
        if isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef)):
            function = current
            while function in parents:
                function = parents[function]
                if isinstance(function, ast.ClassDef):
                    return f"{function.name}.{current.name}"
                if isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    break
            return current.name
    return ""


def _assigned_under_setup(module: Path, at: str) -> bool:
    """Чи створено програму ПРИСВОЄННЯМ під `setUp` — а не покладено у файл.

    `self.adb.write_text(<текст>)` кладе підставного туди, звідки його бере
    інструмент (теку на `PATH`, `SLUKHAYKA_*`), і тест дістає його через хелпер.
    `self.slow_tool = <текст>` не кладе його нікуди: програма живе в атрибуті, і
    якщо жоден тест її не назве, її не запускає ніщо — а `setUp` «покривав» би її
    іменем першого-ліпшого тесту класу (M14 рецензента).

    «Під `setUp`» — це не лише буквальний `setUp`: хелпер, який він кличе, теж
    виконується під ним і теж створює програму не для одного тесту (п'яте коло
    огляду, знахідка H: ловився лише скоуп із іменем `setUp`, тож присвоєння в
    хелпері давало `False` і позиченого свідка). Тому перевіряється все замикання
    викликів від `setUp` — усіх класів модуля, бо хелпер може бути й у базовому.

    Присвоєння обриває `_location` на собі, тож місце такої програми — сама лише
    ціль (`self.slow_tool`), і саме за нею її тут і знаходять.
    """
    source = module.read_text(encoding="utf-8")
    tree = ast.parse(source)
    parents = {child: parent for parent in ast.walk(tree)
               for child in ast.iter_child_nodes(parent)}
    scopes, classes = module_index(module)
    under_setup: set = set()
    for cls in classes:
        if f"{cls}.setUp" in scopes:
            under_setup |= _scope_closure([f"{cls}.setUp"], scopes)
    for node in ast.walk(tree):
        if _program_text(node) is None:
            continue
        if _location(node, parents, source) != at:
            continue
        if not isinstance(parents.get(node), (ast.Assign, ast.AnnAssign)):
            return False
        return _creator_key(node, parents) in under_setup
    return False


def _in_class(key: str, cls: str, scopes: dict[str, Scope],
              classes: dict[str, tuple[str, ...]]) -> bool:
    current = scopes[key].cls
    seen: set = set()
    while current and current not in seen:
        if current == cls:
            return True
        seen.add(current)
        bases = classes.get(current, ())
        current = bases[0] if bases else ""
    return False


@functools.lru_cache(maxsize=None)
def witness_for(module: Path, at: str) -> str | None:
    """Свідок виводиться з місця програми — оголосити його ніде.

    Порядок: тест-власник (той, у тілі якого програму створено), далі тести того
    самого класу, далі будь-який тест модуля. Якір мусить бути у ВЛАСНОМУ тілі
    свідка (нехай і через викликаний хелпер); `setUp` доглядається останнім і
    лише для програми, покладеної у файл. Присвоєння під `setUp` (`self.x = <текст>`
    у самому `setUp` або в хелпері, який він кличе) свідка через `setUp` не
    дістає: тест мусить назвати таку програму сам.

    Це ВИМІРЮВАЛЬНА ДОПОМОГА, а не доказ: свідка вибирають за згадкою якоря, а не
    за виконанням програми, тож тест, який лише згадує `self.adb` і не запускає
    інструмент, може стати свідком — і тоді ґард зелений, хоч інструмент під
    оточенням раннера падає. Межі названі в докстрінзі модуля; закрити їх
    статично нема чим («згадує» і «запускає» — те саме посилання в AST).
    """
    scopes, classes = module_index(module)
    prefix, separator, anchor = at.partition(": ")
    if not separator:
        prefix, anchor = "", prefix
    tests = sorted((key for key, scope in scopes.items() if scope.is_test),
                   key=lambda key: scopes[key].line)
    if prefix in scopes and scopes[prefix].is_test:
        return prefix

    owner = prefix.partition(".")[0] if prefix else ""
    candidates = [key for key in tests if not owner or _in_class(key, owner, scopes, classes)]
    for group in (candidates, tests):
        for key in group:
            if anchor in _reachable(key, scopes, classes):
                return key
    if _assigned_under_setup(module, at):
        return None
    for group in (candidates, tests):
        for key in group:
            if anchor in _reachable(key, scopes, classes, through_setup=True):
                return key
    return None


@functools.lru_cache(maxsize=None)
def program_witnesses() -> list[tuple[str, str, str | None]]:
    """`(модуль, at, свідок)` — ПО ОДНОМУ запису на кожну програму.

    Саме список програм, а не множина імен свідків: два записи з тим самим
    свідком — це два рядки тут. Множина імен ховала новий запис, чиє виведення
    сідало на вже пінований свідок (п'яте коло огляду: підставний інструмент
    додавався й «покривався» тестом, який його ніколи не запускає). Програма без
    свідка стоїть тут із `None` — це відмова, а не пропуск. Що виведення при
    цьому НЕ доводить (свідок вибирається за згадкою якоря, а не за виконанням),
    сказано в докстрінзі модуля.
    """
    found = []
    for case in CASES:
        module = REPO_ROOT / case.where[0]
        for program in case.programs:
            found.append((str(module.relative_to(REPO_ROOT)), program.at,
                          witness_for(module, program.at)))
    return sorted(found)


def derived_targets() -> list[str]:
    """`module.Class.test` — прогони, які мусять бути зелені в обох оточеннях."""
    targets = set()
    for module, _at, witness in program_witnesses():
        if witness is not None:
            targets.add(f"{Path(module).stem}.{witness}")
    return sorted(targets)


@functools.lru_cache(maxsize=None)
def fixture_witnesses() -> list[tuple[Path, str, tuple[str, ...]]]:
    """`(варіант фікстури, файл, модуль-свідок)` — по запису на КОЖЕН файл.

    Свідок мусить НАЗИВАТИ файл: модуль, який імені не називає, інструмента не
    запускає і свідком бути не може — той самий прийом, що `paired_modules` у
    `scripts/empty_shape_mutants.py`. Файл без такого модуля лишається тут із
    порожнім свідком: це відмова, а не тихий пропуск (мутація M10b: новий
    `extra_tool.sh` без жодного тесту, який його згадує).
    """
    found = set()
    for case in CASES:
        for where in case.where:
            path = Path(where)
            if "fixtures" not in path.parts:
                continue
            tests = REPO_ROOT / path.parent / "tests"
            naming = tuple(sorted(
                witness.name for witness in tests.glob("test_*.py")
                if path.name in witness.read_text(encoding="utf-8")
            )) if tests.is_dir() else ()
            found.add((tests.parent.parent, path.name, naming))
    return sorted(found)


# --- перевірки ----------------------------------------------------------------


class RegistryTest(unittest.TestCase):
    """Перелік мусить бути повним з усіх боків — і кожен пін існувати."""

    def test_the_registry_has_no_declared_switches(self):
        """Набір полів — рівно ті, чию правду щось перевіряє.

        П'ять кіл огляду поспіль знаходили новий неперевірений перемикач: маркер
        модуля, `runs=False`, `witness`, `own_poison`, значення `shape_pin`/
        `boundary`/`real`. Тут їх немає: `witness` виводиться з місця програми,
        `real` і `shape_pin` ходять парою, а виключити запис із прогонів нічим.
        Нове поле червонить цей тест, і його доведеться обґрунтувати.
        """
        self.assertEqual(
            {"name", "where", "prefix", "reads", "real", "shape_pin", "boundary",
             "programs"},
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
             "scripts/tests/test_empty_shape_mutants.py",
             "scripts/tests/test_issue_premise.py",
             "scripts/tests/test_merge_pr.py",
             "scripts/tests/test_mutate.py",
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
        червоним. Канарейка вище пінить відомий набір; ця перевірка — саму межу.
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

    def test_every_program_has_a_derived_witness(self):
        """Свідка не оголошують — його виводять, і він мусить знайтися.

        Якщо жоден тест не дістає місця програми, пінити її оточення нічим: це
        відмова, а не тихе «нічого не ганяли». Перевірка йде ПО ПРОГРАМАХ, а не
        по множині імен: два записи з тим самим свідком — це два рядки, і новий
        запис, чиє виведення сідає на вже пінований свідок, видно тут.

        «Дістає» тут — за згадкою якоря; чи свідок справді ЗАПУСКАЄ програму, це
        не доводить (межі виведення — у докстрінзі модуля).
        """
        found = program_witnesses()

        self.assertEqual(sum(len(case.programs) for case in CASES), len(found))
        self.assertEqual(len(found), len({(module, at) for module, at, _ in found}),
                         "кожна програма мусить стояти у виведенні рівно раз")
        for module, at, witness in found:
            with self.subTest(module=module, program=at):
                self.assertTrue(witness, "жоден тест не дістає %s — свідка немає" % at)

    def test_every_fixture_instrument_has_a_witness_that_names_it(self):
        """Фікстурний інструмент ганяє тест, який НАЗИВАЄ його файл.

        «Поруч лежить якийсь `test_*.py`» — не свідок: файл під `fixtures/`, якого
        не згадує жоден тест, лишався б зеленим, а підставний інструмент —
        неперевіреним (M10b). Модуль, який імені не називає, інструмента не
        запускає — той самий прийом, що `paired_modules` у
        `scripts/empty_shape_mutants.py`.
        """
        for root, name, modules in fixture_witnesses():
            with self.subTest(fixture=str(root.relative_to(REPO_ROOT)), file=name):
                self.assertTrue(modules, "%s нічим не піниться: жоден тест поруч не "
                                         "називає його файл" % name)

    def test_the_derived_witnesses_are_pinned_program_by_program(self):
        """Канарейка: `at → свідок` — ПО КОЖНІЙ програмі, а не множиною імен.

        Це пін на виведення, а не на оголошення: якщо воно почне вибирати
        сусідній тест (клас помилки, за який реєстр лаявся чотири кола), буде
        видно тут. Два підставні git — найгостріший випадок: їхні тексти схожі,
        і саме їх найлегше переплутати.

        Множина імен (те, що тут було) ховала новий запис, чиє виведення сідало
        на вже пінований свідок: підставний інструмент, який успадковує
        оточення, додавався й «покривався» тестом, який його ніколи не запускає
        (M14 рецензента). Рядок на програму — щонайменше один рядок на кожну
        програму, і жоден новий запис не проходить повз цей список.

        Назва не обіцяє «тест, який ЗАПУСКАЄ програму»: виведення вибирає за
        згадкою якоря, і що це саме так — сказано в докстрінзі модуля.
        """
        merge_pr = REPO_ROOT / "scripts" / "tests" / "test_merge_pr.py"

        self.assertEqual("MergePrTest.test_fails_closed_when_the_working_copy_scan_fails",
                         witness_for(merge_pr, "FAKE_OLD_GIT"))
        self.assertEqual("MergePrTest.test_fails_closed_when_the_patch_comparison_fails",
                         witness_for(merge_pr, "FAKE_BROKEN_GIT"))
        self.assertEqual("FullSuiteWrapperTest.test_cleans_temp_when_interrupted",
                         witness_for(REPO_ROOT / "scripts" / "tests" / "test_test_wrappers.py",
                                     "FullSuiteWrapperTest"
                                     ".test_cleans_temp_when_interrupted: self.fake_gradle"))
        self.assertEqual(
            [("scripts/tests/test_android_emulator_readiness.py",
              "AndroidEmulatorReadinessTest.setUp: self.fake_adb",
              "AndroidEmulatorReadinessTest"
              ".test_waits_until_android_package_service_is_usable"),
             ("scripts/tests/test_empty_shape_mutants.py", "SAMPLE",
              "ScannerTest.test_reads_arms_with_offsets_and_keeps_the_source_intact"),
             ("scripts/tests/test_issue_premise.py", "GH_STUB",
              "ExtractionTest.test_measure_head_exists_because_the_docs_cite_it"),
             ("scripts/tests/test_merge_pr.py", "FAKE_BROKEN_GIT",
              "MergePrTest.test_fails_closed_when_the_patch_comparison_fails"),
             ("scripts/tests/test_merge_pr.py", "FAKE_GH",
              "MergePrTest.test_refuses_a_failing_check"),
             ("scripts/tests/test_merge_pr.py", "FAKE_OLD_GIT",
              "MergePrTest.test_fails_closed_when_the_working_copy_scan_fails"),
             ("scripts/tests/test_mutate.py", "Fixture.build: java",
              "MutationRunTest.test_runs_the_named_class_and_records_a_caught_mutation"),
             ("scripts/tests/test_mutate.py", "GRADLEW",
              "MutationRunTest.test_runs_the_named_class_and_records_a_caught_mutation"),
             ("scripts/tests/test_review_pass.py", "FAKE_GH",
              "ReviewPassTest.test_reissues_the_verdict_on_a_pr_that_already_has_the_label"),
             ("scripts/tests/test_test_wrappers.py",
              "ChangedTestWrapperWebCheckTest.setUp: self.java",
              "ChangedTestWrapperWebCheckTest"
              ".test_web_path_runs_the_web_check_and_no_android_partition"),
             ("scripts/tests/test_test_wrappers.py",
              "ChangedTestWrapperWebCheckTest.write_gradle: self.gradle",
              "ChangedTestWrapperWebCheckTest"
              ".test_web_path_runs_the_web_check_and_no_android_partition"),
             ("scripts/tests/test_test_wrappers.py",
              "ChangedTestWrapperWebCheckTest.write_web_command: target",
              "ChangedTestWrapperWebCheckTest"
              ".test_web_path_runs_the_web_check_and_no_android_partition"),
             ("scripts/tests/test_test_wrappers.py",
              "FullSuiteWrapperTest.setUp: self.java",
              "FullSuiteWrapperTest"
              ".test_rejects_a_non_21_override_with_an_actionable_error"),
             ("scripts/tests/test_test_wrappers.py",
              "FullSuiteWrapperTest.test_cleans_temp_when_interrupted: self.fake_gradle",
              "FullSuiteWrapperTest.test_cleans_temp_when_interrupted"),
             ("scripts/tests/test_test_wrappers.py",
              "FullSuiteWrapperTest"
              ".test_rejects_a_non_21_override_with_an_actionable_error: self.java",
              "FullSuiteWrapperTest"
              ".test_rejects_a_non_21_override_with_an_actionable_error"),
             ("scripts/tests/test_test_wrappers.py",
              "FullSuiteWrapperTest.write_gradle: self.fake_gradle",
              "FullSuiteWrapperTest.test_uses_jdk_21_and_a_fresh_temp_then_cleans_it"),
             ("scripts/tests/test_wide_window.py",
              "DeviceRunTest.setUp: self.adb",
              "DeviceRunTest"
              ".test_derives_the_density_from_the_device_and_reports_the_width")],
            program_witnesses(),
        )
        self.assertEqual(
            ["test_android_emulator_readiness.AndroidEmulatorReadinessTest"
             ".test_waits_until_android_package_service_is_usable",
             "test_empty_shape_mutants.ScannerTest"
             ".test_reads_arms_with_offsets_and_keeps_the_source_intact",
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
             "test_test_wrappers.ChangedTestWrapperWebCheckTest"
             ".test_web_path_runs_the_web_check_and_no_android_partition",
             "test_test_wrappers.FullSuiteWrapperTest"
             ".test_cleans_temp_when_interrupted",
             "test_test_wrappers.FullSuiteWrapperTest"
             ".test_rejects_a_non_21_override_with_an_actionable_error",
             "test_test_wrappers.FullSuiteWrapperTest"
             ".test_uses_jdk_21_and_a_fresh_temp_then_cleans_it",
             "test_wide_window.DeviceRunTest"
             ".test_derives_the_density_from_the_device_and_reports_the_width"],
            derived_targets(),
        )

    def test_the_fixture_witnesses_are_the_fixtures_own_tests(self):
        """Свідок фікстурного інструмента — тест самої фікстури, і він НАЗИВАЄ файл."""
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
             for root, name, modules in fixture_witnesses()],
        )

    def test_a_shape_pin_and_a_named_real_tool_come_together(self):
        """Пін і названий справжній інструмент ходять парою — в обидва боки.

        `shape_pin` без `real` — гасло: пінити форму нема з чим. Названий
        інструмент без піна — обіцянка, якої ніхто не тримає, і саме нею
        (проза замість піна) запис тихо сходив із крючка (M5b/M5c). Тому
        `real` — це РІВНО те, з чим запис звіряє форму піном.

        Фікстурний інструмент не може піти межею взагалі: його справжній лежить
        у цьому ж дереві, тож порівняння можливе завжди (M10b: новий
        `extra_tool.sh` із прозою замість піна).
        """
        for case in CASES:
            with self.subTest(stand_in=case.name):
                fixture = any("fixtures" in Path(where).parts for where in case.where)
                if case.shape_pin is None:
                    self.assertEqual((), case.real,
                                     "інструмент названо — мусить бути пін, а не межа")
                    self.assertFalse(fixture, "фікстурний інструмент мусить мати пін")
                    self.assertTrue(case.boundary, "немає ні піна, ні межі")
                    continue
                self.assertTrue(case.real, "пін на форму без названого інструмента")
                target = self.resolve(case.shape_pin)
                self.assertTrue(callable(target), case.shape_pin)
                # Пін мусить назвати у СВОЄМУ тілі те, що пінить: файл фікстури
                # (її вміст) або маркер програми. Перевірка «пін живе в модулі,
                # де згадано теку фікстури» була оголошуваною: теку називає й сам
                # цей реєстр, тож піном сходив будь-який тест звідси — аж до
                # `test_every_registered_name_is_unique` (огляд, знахідка D).
                body = inspect.getsource(target)
                needles = ({Path(where).name for where in case.where
                            if "fixtures" in Path(where).parts}
                           or {program.marker for program in case.programs})
                self.assertTrue(needles, "піну нема чого називати: ні файлу, ні маркера")
                for needle in sorted(needles):
                    self.assertIn(needle, body,
                                  "пін не називає у своєму тілі те, що пінить: %s"
                                  % needle)

    def test_every_named_real_tool_exists(self):
        """`real` — не гасло: кожне ім'я мусить існувати в дереві або в `PATH`.

        Інакше `real="totally/fake/tool"` проходило б зеленим (M5a): пін
        «звіряв» би форму з тим, чого немає, і саме це реєстр і обіцяє
        перевіряти.
        """
        for case in CASES:
            for tool in case.real:
                with self.subTest(stand_in=case.name, tool=tool):
                    if "/" in tool:
                        self.assertTrue((REPO_ROOT / tool).is_file(),
                                        "%s немає в дереві" % tool)
                    else:
                        self.assertTrue(shutil.which(tool),
                                        "%s немає в PATH" % tool)

    def test_the_records_that_go_by_a_named_boundary_are_pinned(self):
        """Хто піниться, а хто йде межею, видно ПО КОЖНОМУ запису.

        `real=()` — єдине, чим запис каже «живого порівняння тут бути не
        може» (`gh` без `GH_TOKEN`, `adb` без пристрою, Gradle без прогону
        збірки, `npm` без мережі). Механічно перевірити саму неможливість не
        можна, тож піниться перелік: перевести запис із піна на межу можна лише
        разом із цією правкою, а не мовчки.
        """
        self.assertEqual(
            ["підставний gradlew і jdk фікстури test_mutate",
             "підставний gh фікстури test_merge_pr",
             "підставний gh фікстури test_review_pass",
             "підставний gh фікстури test_issue_premise",
             "підставний adb фікстури test_wide_window",
             "підставний adb фікстури test_android_emulator_readiness",
             "підставні gradlew і java фікстури test_test_wrappers",
             "підставні gradlew, npm і npx фікстури test_test_wrappers",
             "SAMPLE у test_empty_shape_mutants — вхідний текст, не інструмент"],
            [case.name for case in CASES if case.shape_pin is None],
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


class EnvironmentTest(unittest.TestCase):
    """Кожен свідок мусить бути зелений і без спадку, і під отрутою."""

    def run_witnesses(self, environment: dict) -> list[subprocess.CompletedProcess]:
        results = []
        targets = derived_targets()
        if targets:
            results.append(subprocess.run(
                [sys.executable, "-m", "unittest", *targets],
                cwd=str(TESTS_DIR), check=False, capture_output=True, text=True,
                env=environment, timeout=NESTED_TIMEOUT_SECONDS,
            ))
        # Файлів у варіанті більше, ніж модулів: свідка ганяємо один раз на
        # (варіант, модуль), а не на кожен файл, який він називає.
        for root, witness in sorted({(root, module)
                                     for root, _name, modules in fixture_witnesses()
                                     for module in modules}):
            results.append(self.run_fixture_witness(root, witness, environment))
        return results

    def run_fixture_witness(self, root: Path, witness: str,
                            environment: dict) -> subprocess.CompletedProcess:
        """Свідок фікстури — у КОПІЇ дерева: фікстура пише артефакти поруч із собою."""
        with tempfile.TemporaryDirectory(prefix="fixture-witness-") as temporary:
            work = Path(temporary) / "root"
            shutil.copytree(root / "scripts", work / "scripts")
            return subprocess.run(
                [sys.executable, "-m", "unittest", "discover",
                 "-s", "scripts/tests", "-p", witness],
                cwd=str(work), check=False, capture_output=True, text=True,
                env=environment, timeout=NESTED_TIMEOUT_SECONDS,
            )

    def assertWitnessesGreen(self, results: list[subprocess.CompletedProcess]) -> None:
        self.assertTrue(results, "жодного свідка не прогнано")
        for result in results:
            self.assertEqual(0, result.returncode, result.stderr[-3000:])

    def test_a_stripped_environment_is_enough_for_every_stand_in(self):
        """Порожнє оточення: фікстура не читає зі спадку нічого.

        Якщо свідок зеленіє лише тоді, коли йому дісталися змінні раннера, —
        він читає спадок, і CI це побачить, а локальна зелень ні.
        """
        self.assertWitnessesGreen(self.run_witnesses(dict(STRIPPED)))

    def test_a_poisoned_inherited_environment_does_not_reach_any_stand_in(self):
        """Отруєне оточення: затьмарені команди не доходять до інструмента.

        `BASH_FUNC_printf%%` і решта роблять підставний інструмент червоним, якщо
        той успадковує оточення. Саме так упав CI #1260 — шість падінь із кодом
        2 при незмінних файлах фікстури.
        """
        self.assertWitnessesGreen(self.run_witnesses({**os.environ, **HOSTILE}))


if __name__ == "__main__":
    unittest.main()
