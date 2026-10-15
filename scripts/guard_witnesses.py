#!/usr/bin/env python3
"""Кожен сторож мусить мати мутацію, яка доводить, що він ЧЕРВОНІЄ (#1270).

Восьмий випадок класу «перевірка, яка не перевіряє» (`#1270`) — і найповчальніший:
сторож, написаний проти цього самого класу, сам виявився фальшивим.
`test_measure_head_exists_because_the_docs_cite_it` мав не дати команді з
документа знову стати вигаданою, а тримався на **чужому рядку help**
(`--scan-limit … для --measure-head`): вирізана опція лишала підрядок у `--help`,
і сторож був зелений на зламаному дереві. Те саме питання — «чи цей сторож
справді щось тримає?» — знаходив незалежний огляд, не автор
(#1233, #1231, #1218, #1246, #1254, #1257, #1265), і тепер сам сторож. Місце
кожного з восьми виводить `CASES` у `scripts/doc_numbers.py`; третій там під
ключем `#1218`, а не `#1239` — #1239 це щуп розкладки, і тіло #1270 називає його
помилково. «Дев'ятий» і «сьомий» були обидва числами, яких не тримав ніхто
(#1277).

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

Ціна реєстру, і саме тому він не стає галасливим: разом 50 записів (множина
навмисна — число міряє `doc_numbers`, а його таблиця не має однини: «один запис»
це вказівний зворот, а не кількість), і кожен
коштує ДВА прогони одного тесту (базовий і мутант) у копії дерева. Записи йдуть
паралельно (`--jobs`), і кожен друкує свій час під час прогону
(`python3 scripts/guard_witnesses.py`) — 0.5–1.7 с на запис на цій машині (вона
спільна із сусідніми смугами, тож повний час коливається), тож дорогий сторож
видно, а не вгадується. Запис без мутації не заводиться — `GUARDS` і є переліком
того, що гейт перевіряє; старі сторожа без запису не отримують ні тихої згоди,
ні червоного — механізм просто каже, скількох покриває.
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
    Guard(
        test="test_witness_derivation.DerivationTest.test_the_same_ast_gives_two_truths",
        protects="доказ, що ТОТАЛЬНОГО виведення немає (#1279): один текст мусить "
                 "дати ДВІ правди. Прибраний рантайм-перемикач робить обидва "
                 "прогони однаковими — і свідчення зникає",
        edits=(
            Edit(
                file="scripts/tests/test_witness_derivation.py",
                find="        if not SWITCH.exists():\n            return\n",
                replace="        if False:  # mutated\n            return\n",
            ),
        ),
    ),
    Guard(
        test="test_witness_derivation.DerivationTest"
             ".test_every_form_is_accepted_and_runs_nothing",
        protects="слід шести кіл (#1279): п'ять форм прийнято свідками, і жодна не "
                 "запускає інструмента. Чесний запуск, який перестав читати вердикт "
                 "інструмента, робить доказ порожнім",
        edits=(
            Edit(
                file="scripts/tests/test_witness_derivation.py",
                find="            text=True, env=ENVIRONMENT,\n"
                     "        )\n"
                     "        self.assertEqual(0, result.returncode, result.stderr)\n",
                replace="            text=True, env=ENVIRONMENT,\n"
                        "        )\n"
                        "        self.assertTrue(True)  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_fixture_environments.EnvironmentTest"
             ".test_the_guard_sees_only_the_runs_it_is_named",
        protects="ціна без виведення (#1279): прогін поза названим списком не "
                 "запускається взагалі. Прогін, який почав виконувати ВСЕ замість "
                 "названого, стирає цю межу — і свідчення разом із нею",
        edits=(
            Edit(
                file="scripts/tests/test_fixture_environments.py",
                find="    unittest.TestLoader().loadTestsFromNames(sys.argv[1:])\n",
                replace='    unittest.TestLoader().discover(".")  # mutated\n',
            ),
        ),
    ),
    Guard(
        test="test_declared_fields.RepositoryTest"
             ".test_every_record_declares_exactly_the_listed_fields",
        protects="набір полів запису-оголошення: НОВЕ оголошене поле мусить червонити пін, "
                 "а не проходити тихо — саме на цьому впали шість перемикачів однієї форми "
                 "(#1276)",
        edits=(
            # Живий приклад тікета: мутація ДОДАЄ полю-оголошенню нове поле. Поле з
            # нулем у кінці, щоб мутант лишався датакласом, який будується, — інакше
            # «червоний» означав би «не імпортується», а не «пін побачив поле».
            Edit(
                file="scripts/guard_witnesses.py",
                find="    test: str\n    protects: str\n    edits: tuple[Edit, ...]\n",
                replace="    test: str\n    protects: str\n    edits: tuple[Edit, ...]\n"
                        '    owner: str = ""  # mutated\n',
            ),
        ),
    ),
    Guard(
        test="test_doc_numbers.RepositoryTest.test_the_cited_commands_all_exist",
        protects="команду, згадану в документі: вигадана опція (`--check`, якої не "
                 "існує) мусить червонити сторожа — клас `--measure-head`, на якому "
                 "спіткнувся сам цей PR (#1277)",
        edits=(
            # Живий приклад тікета, і він же — урок: перша версія цієї мутації
            # дописувала вигадану опцію в команду, і **сам запис реєстру** ставав
            # цитатою з вигаданою опцією (реєстр лежить у сканованому файлі) —
            # базовий прогін червонів без мутації. Тому ламається ОГОЛОШЕННЯ:
            # опція, на яку документ посилається, зникає з `add_argument`.
            Edit(
                file="scripts/doc_numbers.py",
                find='parser.add_argument("--list", action="store_true",',
                replace='parser.add_argument("--lisst", action="store_true",',
            ),
        ),
    ),
    Guard(
        test="test_named_boundaries.SectionTest"
             ".test_a_boundary_named_in_code_without_a_witness_is_refused",
        protects="межу, названу В КОДІ без свідка (#1278): пункт покритого розділу "
                 "без запису мусить червонити механізм, а не лишатися прозою — саме "
                 "так ловиться межа, названа лише в звіті",
        edits=(
            Edit(
                file="scripts/named_boundaries.py",
                find="            if not owners:\n",
                replace="            if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_doc_numbers.RepositoryTest"
             ".test_every_number_derives_on_the_real_tree",
        protects="число в доку, яке щось стверджує про дерево: місце, зникле з серії, "
                 "лишає число без джерела — і сторож мусить це побачити, а не "
                 "лишитися зеленим (живий приклад: «сьомий» проти восьмого місця, "
                 "#1277)",
        edits=(
            # Живий приклад тікета: серія втрачає місце, на яке посилається документ.
            # Сторож мусить почервоніти — рядок більше не має джерела.
            Edit(
                file="scripts/doc_numbers.py",
                find='    ("#1257", "`errors`"),\n',
                replace="",
            ),
        ),
    ),
    Guard(
        test="test_doc_numbers.RepositoryTest"
             ".test_the_enrolled_prices_are_the_recorded_measurement",
        protects="ЦІНУ правила про теку в шапках (#1308): число в шапці "
                 "`issue_premise.py` (і те саме в `docs/agents/issue-tracker.md`) "
                 "мусить дорівнювати реєстру `PRICES`. До #1308 ці файли не читав "
                 "жоден гейт, і заміна 132 на 133 — у шапці механізму та в доку — "
                 "проходила зеленим, хоч шапка й обіцяла протилежне",
        edits=(
            # Живий приклад тікета, відтворений оглядом PR #1306 і автором:
            # шапка каже 133, реєстр — 132. Сторож мусить почервоніти саме на
            # цьому рядку, а не лишитися зеленим, як було до #1308.
            Edit(
                file="scripts/issue_premise.py",
                find="«будь-який збіг-дерево» — 132",
                replace="«будь-який збіг-дерево» — 133",
            ),
        ),
    ),
    Guard(
        test="test_named_boundaries.SectionTest"
             ".test_a_record_without_a_mutation_is_refused",
        protects="межу без мутації, яка її підтверджує (#1278, варіант (в)): запис, "
                 "який нічого не закриває, — обіцянка в реєстрі, і механізм мусить "
                 "відмовити, а не прийняти її за свідчення",
        edits=(
            Edit(
                file="scripts/named_boundaries.py",
                find="        if not entry.edits:\n",
                replace="        if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_named_boundaries.SectionTest"
             ".test_a_closing_mutation_that_edits_the_witness_module_is_refused",
        protects="«зламай свідка, щоб довести, що межу видно» (#1278): закриття, "
                 "яке править МОДУЛЬ СВІДКА, — не свідчення. Механізм мусить "
                 "відмовити, а не прийняти його за закриття межі",
        edits=(
            Edit(
                file="scripts/named_boundaries.py",
                find="            if edit.file == witness_module:\n",
                replace="            if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_named_boundaries.RegistryTest"
             ".test_the_unreadable_boundary_sections_are_the_named_ones",
        protects="«чотири з дванадцяти» зі шапки меж (#1278): число, яке щось "
                 "стверджує про дерево, мусить мати джерело. Мутація вчить "
                 "механізм читати ще одну форму пункту — множина нерозібраних "
                 "розділів змінюється, і пін мусить почервоніти",
        edits=(
            Edit(
                file="scripts/named_boundaries.py",
                find="        if BULLET_RE.match(line):\n",
                replace="        if BULLET_RE.match(line) or line.strip().startswith(\"- \"):\n",
            ),
        ),
    ),
    Guard(
        test="test_issue_premise.TreeEvidenceTest"
             ".test_a_bare_name_that_matches_only_a_tree_is_ambiguous",
        protects="межу короткої форми на ТЕЦІ (#1281): односегментне ім'я без "
                 "розширення, чия єдина коротка форма — дерево, мусить дати "
                 "неоднозначність, а не «премиса є». Витята гілка робить із теки "
                 "той самий файл — і #982 (`main/` → `app/src/main`) знову зелений",
        edits=(
            # Живий приклад тікета: гілка, яка відрізняє дерево від блоба в
            # короткій формі. Без неї вердикт падає в «є в main: <тека>» —
            # рівно те хибне «премиса є», з якого народився #1281.
            Edit(
                file="scripts/issue_premise.py",
                find="        if matches[0].kind == TREE and _is_bare_name(candidate.path):\n",
                replace="        if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_issue_premise.TreeEvidenceTest"
             ".test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep",
        protects="асиметрію ВИВОДУ (#1301) як доведену ціну, а не недогляд: та сама "
                 "форма (`docs/` у корені, `drawable/` вглиб) дає різний вердикт, "
                 "бо дискримінатор — ТОЧНИЙ запис дерева, а не глибина. "
                 "Розширення форми робить премисою й `main/` із #982 — тіло пише "
                 "його з тим самим хвостовим слешем. Витятий `_is_bare_name` "
                 "(розширення наосліп) повертає #982 у код 0 — те саме хибне «є», "
                 "від якого його врятував #1281",
        edits=(
            # Розширення форми — рівно те, чим «прибирають» асиметрію: гола тека
            # за єдиним збігом-деревом стає премисою. Тут воно й червоне: #982
            # (`main/`) вертається в 0.
            Edit(
                file="scripts/issue_premise.py",
                find='    return "/" not in path and not Path(path).suffix\n',
                replace="    return False  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_issue_premise.BareTreeMeasureTest"
             ".test_the_documented_command_counts_the_same_class_the_verdict_makes_ambiguous",
        protects="ЧИСЛА, якими названа ціна асиметрії (#1301): "
                 "`--measure-bare-trees` мусить рахувати рівно той клас, який "
                 "`judge` переносить із 0 у 2 — односегментне ім'я без "
                 "розширення, не кореневе, чия єдина коротка форма — дерево, — "
                 "і друкувати сусідні означення тим самим кодом (шапка називає "
                 "74/78/80/131/132 — їх рахує режим зі спільних предикатів). "
                 "Забути «не кореневе» — і в ціну входить `docs/`, який не "
                 "платить: точний шлях знаходить його першим. Самі ЛІТЕРАЛИ "
                 "шапок пінить інший запис (#1308: `doc_numbers` проти реєстру "
                 "`PRICES`), а тут доведено, що режим рахує той самий клас",
        edits=(
            Edit(
                file="scripts/issue_premise.py",
                find="        if not include_root and name in top_level:\n"
                     "            continue\n",
                replace="        if False:  # mutated\n"
                        "            continue\n",
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.ChangedTestWrapperScratchTest"
             ".test_no_scratch_directory_survives_a_run",
        protects="теку прогону `test-changed.sh`: `exec` замінює процес і не виконує "
                 "EXIT-пастку, тож прибирання, яке стоїть ЛИШЕ в пастці, не "
                 "виконується ніколи — і кожен відкат на повний набір лишає "
                 "порожню `slukhayka-changed-tests.*` у `$TMPDIR` (#1280). `exec` "
                 "там у ДВОХ місцях — невідома база й відкат селектора, — і сторож "
                 "проходить кожне. Межа: сусідні скрипти сімейства (`test-all.sh`, "
                 "`merge-pr.sh`, `review-pass.sh`, `pre-release.sh`, `mutate.sh`) "
                 "цим сторожем не покриті — течі ЦЬОГО класу в них немає (`exec` "
                 "після пастки немає), і це перевірено окремо, а не ним. Інший клас "
                 "— тека, лишена SIGKILL'ом (ескалація `build-slot-guard.py`, OOM, "
                 "перервана сесія), — тут не міряється взагалі: він не проходить "
                 "крізь `exec`, і сторож про нього не обіцяє. Його міряє інший "
                 "запис — `…test_a_scratch_left_by_a_sigkilled_run_is_reaped_by_the_"
                 "next_run` (#1300)",
        edits=(
            # Мутація повертає причину в ДРУГОМУ місці `exec` — щоденному
            # (селектор не відображає `strings.xml` на партицію). Саме вона
            # відрізняє сторожа з двома ногами від одноногого: зняти прибирання з
            # `run_full_suite` цілком червонить і першу ногу, тож така мутація
            # лишалася б зеленою на сторожі, який друге місце не проходить —
            # рівно це й знайшов огляд PR #1297. Запис у реєстрі один на сторожа,
            # тож тут лежить гостріша з двох мутацій; перше місце пінить перша
            # нога того самого сторожа.
            Edit(
                file="scripts/test-changed.sh",
                find=(
                    "  printf '%s\\n' \"Changed-test selection fell back to the "
                    "full suite: $reason\"\n"
                    "  run_full_suite\n"
                ),
                replace=(
                    "  printf '%s\\n' \"Changed-test selection fell back to the "
                    "full suite: $reason\"\n"
                    '  exec "$repo_root/scripts/test-all.sh"\n'
                ),
            ),
        ),
    ),
    Guard(
        test="test_tmp_budget.ScratchTest"
             ".test_the_prefixes_the_scripts_claim_are_the_reaped_ones",
        protects="префікс теки прогону: прибиральник упізнає залишок SIGKILL'а ЗА "
                 "ПРЕФІКСОМ, і префікс у константі мусить бути той самий, що в "
                 "скрипті (#1300). Розійшлися — і тека прогону лишається в tmpfs "
                 "назавжди, при зеленому наборі: саме так 95 тек "
                 "`slukhayka-mutation.*` і 65 `slukhayka-changed-tests.*` пережили "
                 "свої прогони",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find='SCRATCH_PREFIXES = ("slukhayka-mutation.", "slukhayka-changed-tests.", '
                     '"slukhayka-tests.")',
                replace="SCRATCH_PREFIXES = ()",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.LimitTest"
             ".test_a_changed_limit_moves_the_number_without_touching_the_mechanism",
        protects="ліміт джоби, прочитаний із `ci.yml` (#1299): число, вписане в "
                 "механізм, лишається незмінним, коли `timeout-minutes` у "
                 "воркфлоу інший, — і документ разом із ним розходиться з "
                 "деревом, а не червоніє",
        edits=(
            # Живий приклад тікета: «300» як константа механізму замість читання
            # блоку. Підставлене дерево з `timeout-minutes: 9` дає 540 с, і
            # мутант на цьому червоніє — саме тому число в доку виводиться.
            Edit(
                file="scripts/ci_job_time.py",
                find='        minutes = int(job_field(job_block("\\n".join('
                     'workflow_lines(root, workflow)), key),\n'
                     '                                "timeout-minutes"))\n',
                replace="        minutes = 5  # mutated: ліміт узято з механізму\n",
            ),
        ),
    ),
    Guard(
        test="test_tmp_budget.ScratchTest"
             ".test_an_old_scratch_without_a_marker_is_reaped_for_both_prefixes",
        protects="сам ВИКЛИК `sweep_stale` у прибиральнику теки прогону: тека зникає "
                 "лише тому, що прибиральник кличе справжній `sweep_stale`, а не тому, "
                 "що тест підміняє прибирання (шов `sweep=` знято). Без виклику стара "
                 "тека лишається на місці — і цей свідок мусить почервоніти, а не "
                 "пройти повз прибирання, якого не було",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find="    freed = sweep_stale(scratch_root(base, relocate=relocate), "
                     "(prefix,), grace=grace)\n",
                replace="    freed = []  # mutated: sweep_stale not called\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.MeasurementTest"
             ".test_the_relation_floor_reddens_on_the_slowest_run",
        protects="підлогу запасу (#1299): прогін на 200 с лишає відношення 1.5×, і "
                 "це мусить бути знахідкою, а не «зелено з числом». Знята підлога "
                 "лишає документ із названим відношенням без жодного свідка",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    if limit < HEADROOM_RATIO * slowest:\n",
                replace="    if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.MeasurementTest"
             ".test_the_window_asks_the_api_for_main_push_runs_only",
        protects="джерело вікна (#1307): підлога — твердження про `main`, тож "
                 "прогони питаються з `branch=main&event=push` у САМОМУ запиті. "
                 "Знятий фільтр міряє ВСІ прогони воркфлоу — і повільний "
                 "PR-прогін ламає підлогу `main`, про яку каже документ: у вікні "
                 "#1307 найповільнішим був саме чужий прогін, і механізм "
                 "повідомив про порушення, якого на `main` не було",
        edits=(
            # Рівно те, що було до правки: запит без гілки й події.
            Edit(
                file="scripts/ci_job_time.py",
                find='        "repos/%s/actions/workflows/%s/runs?per_page=%d'
                     '&branch=%s&event=%s"\n'
                     "        % (repo, workflow, window * (1 + FETCH_SLACK), "
                     "MAIN_BRANCH, MAIN_EVENT),\n",
                replace='        "repos/%s/actions/workflows/%s/runs?per_page=%d"\n'
                        "        % (repo, workflow, window * (1 + FETCH_SLACK)),\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.RefusalTest"
             ".test_a_run_from_another_branch_is_an_unknown_not_a_measurement",
        protects="другий бік того самого фільтра (#1307): прогін, який API віддав "
                 "ПОПРИ `branch=main&event=push`, не міряється й не відкидається "
                 "тихо — це невідомість (2). Прийнятий чужий прогін повертає ту "
                 "саму змішаницю джерел, від якої підлога `main` і похитнулася",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    if foreign:\n",
                replace="    if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.ChangedTestWrapperScratchTest"
             ".test_a_scratch_left_by_a_sigkilled_run_is_reaped_by_the_next_run",
        protects="прибирання теки прогону на СТАРТІ наступного: EXIT-пастки SIGKILL "
                 "не виконує ФІЗИЧНО, тож прибрати теку може лише наступний прогін "
                 "— і без цього вона лишається в tmpfs назавжди (#1300). Мутація "
                 "повертає `mktemp`, як було до правки: тека лишається, і наступний "
                 "прогін її не бачить",
        edits=(
            Edit(
                file="scripts/test-changed.sh",
                find='scratch=$(python3 "$budget_module" claim --prefix '
                     'slukhayka-changed-tests. --owner-pid $$) ||',
                replace='scratch=$(mktemp -d "${TMPDIR:-/tmp}/slukhayka-changed-tests.XXXXXX") ||',
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.RefusalTest.test_a_missing_gh_is_an_unknown_not_a_zero",
        protects="невідомість як код 2 (#1299): без `gh` часу джоби взяти нема "
                 "звідки, і «нуль» тут означав би «джоба триває нуль секунд» — "
                 "клас, проти якого весь механізм і написаний",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    if unknowns:\n        return 2\n",
                replace="    if unknowns:\n        return 0  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.ChangedTestWrapperScratchTest"
             ".test_the_scratch_of_a_killed_run_names_that_run_as_its_owner",
        protects="маркер власника теки прогону: його мусить писати СКРИПТ-обгортка "
                 "(`--owner-pid $$`), а не короткоживучий `python3`-хелпер (#1300). "
                 "Маркер на хелпері робить живу теку «застарілою» за мілісекунди "
                 "після створення, і сусідній прогін зніс би її посеред роботи — "
                 "тобто маркер перестав би захищати того, кого заведений захищати",
        edits=(
            Edit(
                file="scripts/test-changed.sh",
                find='scratch=$(python3 "$budget_module" claim --prefix '
                     'slukhayka-changed-tests. --owner-pid $$) ||',
                replace='scratch=$(python3 "$budget_module" claim --prefix '
                        'slukhayka-changed-tests.) ||',
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.ClaimTest"
             ".test_a_local_figure_without_a_named_load_is_a_finding",
        protects="межу локального виміру (#1299): 159 с, виміряні на спільній "
                 "машині під навантаженням 5.4, сходили за час джоби. Знята "
                 "вимога позначки приймає будь-яке локальне число як час джоби — "
                 "рівно те, за що заплатила серія",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="            if int(float(figure)) == limit or labelled_local(line):\n",
                replace="            if True:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.ClaimTest"
             ".test_a_timeout_minutes_literal_that_is_not_the_limit_is_a_finding",
        protects="друге число ліміту на зарахованому рядку (#1319): ліміт стоїть "
                 "у прозі і секундами, і літералом `timeout-minutes: N`. Гейт, "
                 "який читав лише секунди, змушував правити лише їх — і копія "
                 "хвилинами пережила б ту правку брехнею: рядок зі старим "
                 "літералом при правильних секундах (480 с) був зелений (це "
                 "знайшов не "
                 "автор, а проба на підставленому дереві). Знята перевірка "
                 "хвилин повертає саме це",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="            if int(minutes) * 60 == limit:\n"
                     "                continue\n",
                replace="            if True:  # mutated: хвилини не міряються\n"
                         "                continue\n",
            ),
        ),
    ),
    Guard(
        test="test_mutate.MutationRunTest"
             ".test_a_refused_run_still_reaps_the_scratch_a_sigkill_left",
        protects="прибирання на САМОМУ СТАРТІ, до перевірок: SIGKILL лишає теку "
                 "прогону І застосовану мутацію, тож наступний прогін відмовляє на "
                 "брудному дереві — і прибирання, яке стоїть після перевірок, не "
                 "виконалося б ніколи, а тека лишалася б у tmpfs назавжди (#1300). "
                 "Мутація знімає ранній `sweep`: відмова лишає залишок на місці",
        edits=(
            Edit(
                file="scripts/mutate.sh",
                find='python3 "$budget_module" sweep --prefix slukhayka-mutation. ||\n'
                     '  note "прибирання застарілих тек прогону не вдалося (причина вище)"\n',
                replace=": # mutated — прибирання лишилося після перевірок\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.LocalRunTest"
             ".test_the_local_mode_reports_a_local_measurement_and_not_the_job_time",
        protects="рядок благополуччя ЛОКАЛЬНОГО режиму (#1299, огляд PR #1302): "
                 "`--local` не питає `gh` і джоби не міряє, тож «час джоби "
                 "міряно» в ньому — рядок, який суперечить позначці просто над "
                 "ним. Механізм, який каже це на локальному вимірі, і є та "
                 "плутанина, проти якої він написаний",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find='    if mode == "local":\n',
                replace="    if False:  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.MeasurementTest"
             ".test_the_tree_revision_is_read_from_git_and_moves_with_a_commit",
        protects="ревізію дерева, прочитану з git (#1317): вікно прив'язується до "
                 "того, ЯКИЙ код прогін будував, і ревізія мусить рухатися разом "
                 "із деревом. Ревізія-константа в механізмі лишається незмінною, "
                 "коли дерево зрушило, — і прогони ІНШОГО дерева знову сходять за "
                 "час джоби на цьому",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    revision = result.stdout.strip()\n",
                replace='    revision = "0" * 40  # mutated: ревізія не з дерева\n',
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.MeasurementTest"
             ".test_a_window_where_every_run_built_this_tree_behaves_as_before",
        protects="другий бік прив'язки (#1317): коли всі прогони вікна будували це "
                 "дерево, вивід лишається тим самим — ті самі прогони, ті самі "
                 "числа, той самий вердикт. Межа, яку друкують завжди, робить гейт "
                 "галасливим там, де межі немає, і привчає не читати її",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    mixed = len(about) != len(runs)\n",
                replace="    mixed = True  # mutated: межа друкується завжди\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.MeasurementTest"
             ".test_a_window_that_crosses_the_change_boundary_names_the_run_about_this_tree",
        protects="прив'язку вікна до дерева (#1317): підлога рахується лише з "
                 "прогонів, чия ревізія дорівнює ревізії цього дерева, і прогін "
                 "про це дерево названий. Вікно, яке міряють цілком, подає час "
                 "джоби чотири правки тому за сьогоднішній — і червоніє на "
                 "виправленні та зеленіє на регресії",
        edits=(
            # Рівно те, що було до правки: межу зміни не читано, вікно міряне цілком.
            Edit(
                file="scripts/ci_job_time.py",
                find="    about = [run for run in runs if run.revision == revision]\n",
                replace="    about = list(runs)  # mutated: межу зміни не читано\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.RefusalTest"
             ".test_a_window_with_no_run_for_this_tree_is_an_unknown",
        protects="невідомість як код 2 там, де жоден прогін вікна не будував це "
                 "дерево (#1317): числа про це дерево немає, і підставити "
                 "найновіше число з ІНШОГО дерева — це та сама тиха неправда. "
                 "Замість відмови мутант міряє все вікно — і видає чужий час за "
                 "час джоби на цьому дереві",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="    if not about:\n"
                     "        raise JobTimeError(\n"
                     '            "жоден із %d прогонів вікна не будував це дерево '
                     '(%s): у вікні — %s. "\n'
                     '            "Числа про час джоби на цьому дереві немає"\n'
                     "            % (len(runs), revision,\n"
                     '               ", ".join(sorted({run.revision for run in runs})))\n'
                     "        )\n",
                replace="    if not about:\n"
                        "        about = list(runs)  # mutated: відмова замінена на "
                        "«міряти ціле вікно»\n",
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.RefusalTest.test_a_run_without_a_revision_is_an_unknown",
        protects="ревізію прогону як умову виміру (#1317): без `head_sha` вікно до "
                 "дерева не прив'язати, і це відмова (2), а не «візьмемо прогін як "
                 "є». Мутант повертає порожню ревізію — і прогін, про яке дерево "
                 "він, лишається невидимим",
        edits=(
            Edit(
                file="scripts/ci_job_time.py",
                find="        raise JobTimeError(\n"
                     '            "прогін %s: API не дав `head_sha` — вікно до дерева '
                     'не прив\'язати, і "\n'
                     '            "число джоби з нього не міряється" % run_id\n'
                     "        )\n",
                replace='        return ""  # mutated: прогін без ревізії приймається\n',
            ),
        ),
    ),
    Guard(
        test="test_ci_job_time.ClaimTest"
             ".test_a_word_that_merely_contains_the_mark_is_not_a_label",
        protects="межу позначки як СЛОВА (#1299, огляд PR #1302): `download` і "
                 "`upload` містять `load`, і рядок, який локальне число "
                 "ЗАПЕРЕЧУЄ, сходив за позначений локальний вимір — тобто "
                 "перевірка перевіряла не те, що обіцяє",
        edits=(
            # Підрядкове читання — рівно те, що було до правки.
            Edit(
                file="scripts/ci_job_time.py",
                find='    return any(\n'
                     '        re.search(r"(?<!\\w)%s(?!\\w)" % re.escape(mark), '
                     'line, re.IGNORECASE)\n'
                     '        for mark in marks\n'
                     '    )\n',
                replace="    return any(mark.lower() in line.lower() for mark in marks)\n",
            ),
        ),
    ),
    Guard(
        test="test_tmp_budget.ScratchTest"
             ".test_no_run_dir_prefix_in_the_tree_is_left_outside_the_sweeper",
        protects="повнота списку префіксів (#1305): тека прогону, якої немає в "
                 "прибиральнику, не прибирається НІКИМ — саме так `slukhayka-tests.*` "
                 "й пережила свої прогони. Скануються самі скрипти, тож мутація, яка "
                 "виносить префікс зі списку, лишає теку в tmpfs при зеленому наборі",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find='SCRATCH_PREFIXES = ("slukhayka-mutation.", "slukhayka-changed-tests.", '
                     '"slukhayka-tests.")',
                replace='SCRATCH_PREFIXES = ("slukhayka-mutation.", "slukhayka-changed-tests.")',
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.FullSuiteWrapperTest"
             ".test_a_sigkilled_run_is_reaped_by_the_next_run",
        protects="теку ПОВНОГО набору прибирає наступний прогін (#1305): її не "
                 "прибирав ніхто, бо `test-all.sh` не писав маркера й не кликав "
                 "прибиральника. Мутація повертає `mktemp`, як було: залишок "
                 "SIGKILL'а лишається в tmpfs назавжди — і це видно прогоном",
        edits=(
            Edit(
                file="scripts/test-all.sh",
                find='run_temp=$(python3 "$budget_module" claim --prefix slukhayka-tests. '
                     '--owner-pid $$) ||',
                replace='run_temp=$(mktemp -d "${TMPDIR:-/tmp}/slukhayka-tests.XXXXXX") ||',
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.FullSuiteWrapperTest"
             ".test_two_live_runs_coexist_and_a_sweep_takes_neither",
        protects="маркер власника в теці повного набору (#1305): його мусить писати "
                 "САМ прогін (`--owner-pid $$`). Без нього маркер називає "
                 "короткоживучий хелпер, жива тека виглядає застарілою — і сусідній "
                 "прогін зніс би `TMPDIR` у прогону, що саме працює",
        edits=(
            Edit(
                file="scripts/test-all.sh",
                find='run_temp=$(python3 "$budget_module" claim --prefix slukhayka-tests. '
                     '--owner-pid $$) ||',
                replace='run_temp=$(python3 "$budget_module" claim --prefix slukhayka-tests.) ||',
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.FullSuiteWrapperTest"
             ".test_a_stale_run_dir_that_cannot_be_removed_does_not_fail_the_run",
        protects="голосне «не вдалося» замість тихого (#1305): теку, яку прибирання "
                 "не змогло зняти, воно називає в stderr, а прогін іде далі. Тиха "
                 "невдача лишає місце зайнятим і нікого не попереджає — саме те, "
                 "проти чого цей рядок і стоїть",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find='            announce("застаріле тимчасове дерево %s прибрати не вдалося (%s) — "\n'
                     '                     "місце лишається зайнятим" % (entry, error))\n',
                replace="            pass  # mutated: тихе «не вдалося»\n",
            ),
        ),
    ),
    Guard(
        test="test_test_wrappers.FullSuiteWrapperTest"
             ".test_an_unavailable_tmpdir_refuses_with_a_named_reason",
        protects="названа причина замість тихого коду 2 (#1305): до правки тут був "
                 "`mktemp -d … || exit 2`, і причина не називалася взагалі — а тека "
                 "прогону це `TMPDIR` для всього Gradle, тож «не стартувало» без "
                 "причини лишає оператора без того, що саме полагодити",
        edits=(
            Edit(
                file="scripts/tmp_budget.py",
                find='        print("tmp_budget: %s" % error, file=sys.stderr)\n',
                replace="        pass  # mutated: причина не названа\n",
            ),
        ),
    ),
    Guard(
        test="test_verdict_field_mutants.FailfastTest"
             ".test_the_mutant_run_stops_at_the_first_red",
        protects="прогін мутанта, який спиняється на першій червоній цілі (#1313): "
                 "вердикт — це код виходу, тож прапорець може лише ВКОРОТИТИ прогін "
                 "і не може змінити, убитий мутант чи ні. Єдине, чим він видимий, — "
                 "скільком свідкам прогін устигає впасти. Знятий прапорець лишає "
                 "вердикт тим самим, а прогін — повним: саме те число й червоніє",
        edits=(
            Edit(
                file="scripts/verdict_field_mutants.py",
                find='MUTANT_FLAGS = ("-f",)',
                replace="MUTANT_FLAGS = ()  # mutated: прогін договорює всі цілі\n",
            ),
        ),
    ),
    Guard(
        test="test_verdict_field_mutants.WitnessTest"
             ".test_the_narrow_phases_of_every_witness_come_before_the_full_ones",
        protects="порядок фаз МІЖ свідками (#1313): вузькі всіх перед повними "
                 "будь-кого. Це перестановка, а не звуження — набір фаз той самий, "
                 "і якщо жодна вузька не вб'є, повні біжать усі. Повернутий порядок "
                 "не міняє жодного вердикту й тому не червонить нічого іншого: без "
                 "цієї перевірки зрізання тихо верталося б разом із секундами",
        edits=(
            Edit(
                file="scripts/verdict_field_mutants.py",
                find="    return narrow + full\n",
                replace="    return full + narrow  # mutated: повні перед вузькими\n",
            ),
        ),
    ),
    Guard(
        test="test_verdict_field_mutants.WarmTest"
             ".test_the_warmed_baselines_are_the_first_phases_of_every_site",
        protects="прогрів базових прогонів (#1313): та сама робота, але без черги "
                 "на замку ключа — виміряно 41 с із 95 с роботи (43 %), які воркери "
                 "СТОЯЛИ, поки ядра простоювали. Прогріваються ПЕРШІ фази (дешеві); "
                 "`phases[-1]` прогрів би повні набори модулів і повернув би "
                 "секунди, нічого не поламавши видимо",
        edits=(
            Edit(
                file="scripts/verdict_field_mutants.py",
                find="        module, _phase, targets = phases[0]",
                replace="        module, _phase, targets = phases[-1]  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_suite_time.DecompositionTest"
             ".test_the_module_with_the_slow_setup_is_the_top_consumer",
        protects="проміжок перед тестом (#1312): час `setUpClass`, у якому біжить "
                 "гейт, мусить належати СВОЄМУ модулю. Без цього правила число "
                 "модуля — це лише його тести, гейт виглядає дрібним споживачем, а "
                 "його секунди лишаються нічийні — і зрізання було б здогадом",
        edits=(
            Edit(
                file="scripts/suite_time.py",
                find="            seconds[module] += end - previous\n",
                replace="            seconds[module] += end - start  # mutated\n",
            ),
        ),
    ),
    Guard(
        test="test_verdict_field_mutants.FailClosedTest"
             ".test_an_unparsable_witness_module_is_unknown_not_a_traceback",
        protects="обіцянку «невідомість — код 2, а не трейсбек» там, де свідків "
                 "читає ПРОГРІВ (#1313, знахідка огляду): ключі перших фаз "
                 "рахуються до першої мутації, поза `judged`, і непарсибельний "
                 "модуль-свідок падав звідти трейсбеком із кодом 1. Знята обгортка "
                 "нічого не пропускає — але вердикт і діагностика стають іншими",
        edits=(
            Edit(
                file="scripts/verdict_field_mutants.py",
                find="            try:\n"
                     "                warm = first_phase_baselines(report.sites, witnesses)\n"
                     "            except (OSError, FieldError) as error:\n",
                replace="            if True:  # mutated: невідомість падає трейсбеком\n"
                        "                warm = first_phase_baselines(report.sites, witnesses)\n"
                        "            elif False:\n",
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
