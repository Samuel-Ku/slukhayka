#!/usr/bin/env python3
"""Ґард премиси тікета: шляхи з тіла issue мусять існувати в `main` (#1264).

Тікет #1261 стверджував, що `AGENTS.md` посилається на `CODING_STANDARDS.md`.
У `main` того посилання немає (`git grep -l CODING_STANDARDS origin/main` →
порожньо), а сам файл і посилання з'явилися разом із **незакоміченою** правкою
іншої машини — тікет народився зі стану робочої копії, не зі стану `main`, і
його довелося закрити як недійсний. Це клас «премиса тікета не існує в `main`».

Механізм ловить його детерміновано: кожен шлях, названий у тілі issue, або
**є** в `origin/main`, або **позначений** як такий, що цей тікет створить.
Третього немає — і саме третій стан (шлях, якого в `main` немає й ніхто не
обіцяв створити) дає відмову.

    python3 scripts/issue_premise.py --body-file .scratch/1264.md   # чернетка
    python3 scripts/issue_premise.py --body-file -                   # з stdin
    python3 scripts/issue_premise.py --issue 1261                    # уже заведений
    python3 scripts/issue_premise.py --scan 200                      # переміряти шум
    python3 scripts/issue_premise.py --measure-bare-trees            # ціна правила про теку

Форма позначки — `NEW:` **усередині** того самого інтервалу в бектиках, перед
шляхом::

    - [ ] `NEW:docs/agents/premise.md` отримує правило.

Чому саме так — і чому це не «перевіряйте уважніше»:

* **Детермінованість.** Вердикт дає `git`, а не судження. Питання до кожного
  шляху одне: чи є дерево з такою назвою в `origin/main` (`git cat-file -e`,
  для повного шляху — байт-у-байт, без робочої копії, без `checkout`). Однакове
  тіло issue дає однаковий вердикт на кожній машині з тим самим `origin/main`.
* **Тихість.** Тікет, який згадує файл, що **буде** створений цим же тікетом,
  не валиться: позначка `NEW:` знімає вимогу «є в `main`» — і натомість
  вимагає протилежного: такого шляху в `main` бути **не повинно**. Тобто
  позначка не лазівка, а друге плече тієї самої перевірки: вона ловить і
  «оголосив новим те, що вже є».
* **Ціна хибного пропуску.** Замовчати можна лише одним способом — поставити
  `NEW:` там, де шлях не створюється. Це не забудькуватість, а письмове
  твердження в тілі тікета, яке видно оглядові й автору; тихе незнання
  неможливе, бо невиконаний шлях без позначки — відмова.

Пошук шляху, коли повного шляху немає: `ci.yml` замість
`.github/workflows/ci.yml`, `ui/adaptive/WindowLayout.kt` замість повного шляху
під `app/src/main/java/…`, `web/README` замість `web/README.md` — звичайна річ у
тілах issue, і валити її означало б шум. Тому хвіст шукається в дереві `main`
на межі сегмента, **разом із деревами** (`ls-tree -r -t`: без `-t` каталог,
який у `main` є, не знаходився ніколи). Одне входження — проходить (це той
самий файл), кілька — невідомість (код 2) з підказкою назвати повний шлях.
Виняток — односегментне ім'я без розширення, чиє єдине входження виявилося
**текою**: для теки «одне входження» не доводить «той самий файл», і таке ім'я
дає неоднозначність (#1281, розібрано в `judge`). Точний шлях питається першим
і короткої форми не потребує.

Шум міряний, а не обіцяний, і число відтворюється командою з розділу «Як
переміряти» в `docs/agents/issue-tracker.md`. На **200 останніх тікетах**
репозиторію (стан `origin/main` = `f0fbc576`, 2026-10-13): **91** — «премиса
є», **90** — невідомість «шляхів у бектиках немає» (тікети про символи коду),
**10** — невідомість «ім'я неоднозначне» (`mutate.sh` ×3 — у дереві є ще дві
фікстурні копії; `ui/components`, `ui/adaptive`, `data/ingest`+`data/source`,
`AndroidManifest.xml` ×2, і **три нові** за правилом про теку: `main` у #982 і
#1281 та `drawable` у #997 — останній із них **хибна** неоднозначність: тікет
каже «інші файли в `drawable/`», а `drawable` — справжня тека
`app/src/main/res/drawable`; механізм асиметрії й ціна названі нижче), **9** —
відмова, і серед них:

* **#1185** і **#828** — справжня ціль ґарда: тікети посилаються на ADR і план
  (`docs/adr/0061-…`, `docs/plans/2026-10-07-…`, `docs/adr/0042-…`), яких у
  `main` немає — вони живуть лише в **незамердженому** PR. Це той самий клас,
  що #1261, і саме він і є доказом, що ґард ловить клас, а не випадок;
* **#784** — `docs/licensing.md` у списку «оновлено», хоч файлу в `main` немає;
* **#1261** — тікет, з якого цей ґард народився;
* **#1272** — `NEW:docs/adr/0062-one-heavy-build-slot.md`, а ADR **уже** в
  `main`: друге плече позначки спрацювало, і відмова чесна — застарів тікет, а
  не ґард;
* **#816** — `web/prototype`, про який тікет **сам пише**, що в `main` він не
  йде: відмова чесна, і ліки — бектики або `NEW:`;
* **#962**, **#1029**, **#822** — голе ім'я локального звіту
  (`REPORT-continuation.md`), ім'я фікстури в чужому тесті
  (`2851-temna-materiia.html`) і проза з крапкою (`works.title/author/seriesTitle`).

Тобто після правок за оглядом PR #1268 хибних відмов на цьому вікні лишилося
**три** з дев'яти (#962, #1029, #822), і кожна названа з причиною. Ціна нового
правила про теку теж виміряна: воно перенесло з «премиса є» в неоднозначність
рівно **три** тікети з 200 — #982, #997 і сам #1281 (тіло якого наводить
`main/` як приклад межі). Ширша форма («будь-яка тека за хвостом —
неоднозначність») коштувала б більше, і вона не потрібна: межа названа в
`judge`. Вікно рухається, і це видно: на `f81c724f` (2026-10-14) той самий
`--scan 200` дає 93/87/11/9 — відмови ті самі 9 тікетів і 11 шляхів, а серед
неоднозначних додався сам #1301 (його тіло наводить `drawable/` і
`ui/components/`).

Межі, названі прямо:

* Перевіряються **шляхи**, не твердження про їхній вміст. «`AGENTS.md` обіцяє
  X» цей ґард не перевірить; #1261 упав на іншому — файлу, на який тікет
  посилався, не існувало. Твердження про вміст ловить огляд, і саме тому
  ґард не вдає, що замінює його.
* Вердикт — про **локальний** `origin/main`. Якщо ref не оновлено, ґард
  судить старе дерево; ліки — `git fetch origin main` перед прогоном. Сам
  ґард не робить мережевих запитів і не підтягує ref потай.
* Пропущений шлях у бектиках невидимий: шлях без бектиків, у посиланні
  Markdown чи в коментарі до issue — не перевіряється. Це межа **вибору
  премиси**, а не перевірки: що автор назвав шляхом, те й перевірено. Саме
  тому тікет без жодного шляху в бектиках — невідомість (код 2), а не зелене
  світло: «нічого не перевірено» не дорівнює «все гаразд».
* Цитата чужого хибного твердження теж дає відмову: ґард не читає контекст і
  не відрізняє «шлях є» від «шляху немає, і це і є проблема». Ліки названі й
  дешеві — прибрати бектики зі згадки-контрприкладу або позначити її `NEW:`.
  Це свідомий вибір: читати контекст означало б гадати, а здогад у ґарді
  дорожчий за рідкісну хибну відмову.
* Голе ім'я в **форматі вкладення** (`live-book-2f-screencap.png`,
  `18-person.png`) шляхом репозиторію не вважається: доказ лежить поруч із
  тікетом, а не в дереві. Голе ім'я з розширенням документа чи коду
  (`REPORT-continuation.md`, `2851-temna-materiia.html`) — вважається, і дає
  відмову: відрізнити локальний звіт від файлу репозиторію формою неможливо.
* Проза зі слешем ловиться **неповно**: `try/catch` (#1081) — так, а
  `works.title/author/seriesTitle` (#822) — ні, бо крапка в першому сегменті
  робить його невідрізненним від `ui/adaptive/WindowLayout.kt`.
* Гілки розпізнаються за головою зі списку (`feat`, `fix`, `release`, `spec`,
  `backup`, `chore`, …); хвіст-число відкидається, тож `spec-53/wave-1` — та
  сама голова `spec`. Гілка з голови поза списком (`my-branch/x`)
  лишиться кандидатом і дасть відмову. Голова, яка є ще й записом верхнього
  рівня `main`, у списку стояти не може — вона проковтнула б усі шляхи під
  собою. Саме тому `docs` і `doc` прибрано (вимір: `--measure-head docs` → 78
  шляхів у 68 тікетах на вікні 2026-10-13; вікно — 200 останніх тікетів, тож
  число пливе разом зі списком issue, а не з ref-ом), а `test` і `tests`
  лишаються (`--measure-head test` → 0,
  і `test/…` — реальна голова гілок репозиторію).
* Односегментне ім'я без розширення, чия коротка форма збіглася **лише з
  текою**, дає неоднозначність (код 2), а не «премиса є»: `main/` у тілі #982
  означало теку Kotlin-джерел, а ґард знаходив `app/src/main` і вважав премису
  наявною (#1281, приклад у `judge`). Форма правила **вужча** за «будь-яка тека
  за хвостом — неоднозначність» навмисно: багатосегментний хвіст (`ui/components`)
  лишається премисою, бо це свідоме рішення огляду PR #1268, а широка форма
  коштувала б хибних неоднозначностей на звичайних шляхах. Ціна виміряна
  **числом**: `--scan 200` на вікні, з якого тікет народився, переніс у
  неоднозначність **три** тікети — #982 (тег `tab_settings`, ціль правила), #997
  і сам #1281 (тіло якого наводить `main/` як приклад межі).
* **Асиметрія ВИВОДУ, названа тут у коді, а не лише в звіті (#1301).** Ім'я зі
  слешем, що зійшлося з текою **в корені**, лишається премисою: `docs/`, `web/`,
  `app/src/main/` — точний шлях питається першим і короткої форми не потребує.
  Тека **вглиб** дає неоднозначність: `drawable/` → `app/src/main/res/drawable`,
  `generator/`, `workflows/`, `mipmap-hdpi/` — усі чотири справжні теки, і всі
  чотири дають код 2 на справжньому дереві. **Це не правило про глибину:**
  дискримінатор — точний запис дерева (`main_has_path`), а глибина лише корелює
  з ним. Прибрати асиметрію не можна, і кожен спосіб названо:
    * **розширити форму** («гола тека за єдиним збігом-деревом — премиса») —
      тоді премисою стає й `main/` із #982: тіло пише його **з тим самим
      хвостовим слешем**, що й `drawable/`, тож форма їх не розрізняє. Доведено
      прогоном:
      `test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep`
      жене справжнє тіло #982 із правилом (код 2) і без нього (код 0);
    * **читати тіло як ціле** (розв'язати голу теку через повний шлях, названий
      у тому ж тілі: #997 сам називає `app/src/main/res/drawable/img_…jpg`). Це
      **здогад** про намір автора, і він навіть не прибирає асиметрію: тіло, яке
      пише лише `drawable/`, лишається кодом 2, а `docs/` — кодом 0. Тобто це
      маска на частині тіл, а не симетрія;
    * **симетрія вниз** (зробити кодом 2 і кореневий кінець) не прибирає
      асиметрію, а подвоює її: кореневе `docs/` — це ТОЧНИЙ запис дерева, і
      відмова на ньому була б хибною неоднозначністю на найчастішій формі.
  Тому лишається те, що є, і ціна названа **числом, яке виводить сам механізм**
  тими самими предикатами, що й вердикт (`_is_bare_name`, `_is_suffix_match`,
  `TREE`):

      python3 scripts/issue_premise.py --measure-bare-trees

  На `origin/main` (`f81c724f`) це **75** односегментних імен-тек без розширення,
  не кореневих, чия єдина коротка форма — дерево; серед них `drawable`,
  `generator`, `workflows` і `main`. Означення живе в `bare_tree_names`, і сусідні
  означення дають сусідні числа, які той самий вимір називає: «без крапки в
  імені» — 74 (не рахує `.aistudio`), «разом із кореневими іменами» — 78, але
  чотири з них (`app`, `docs`, `gradle`, `web`) **не платять**: точний шлях
  знаходить їх першим. Число «132» з попередньої редакції цієї шапки не
  відтворюється — найближче 131 («будь-який збіг-дерево», без вимоги єдиності).
  Це **хибна неоднозначність** — як #962, #1029 і #822 є хибними відмовами, — і
  серед тікетів, що рушили, хибний саме **#997**: він пише «інші файли в
  `drawable/`», а `drawable` — реальна тека `app/src/main/res/drawable`; ліки
  дешеві — назви повний шлях. Обидва кінці асиметрії піновані тестами: коренева
  тека — `test_an_exact_tree_path_is_still_the_premise`, тека вглиб —
  `test_a_bare_directory_name_is_ambiguous_through_real_git`; сам факт «форма та
  сама, різниця — у точності» — новий
  `test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep`, а
  число ціни — `test_the_documented_command_counts_the_same_class_the_verdict_makes_ambiguous`.
* Винятки з відбору кандидатів (рядки-команди `gh …`, Gradle-цілі
  `:app:lintDebug`, git-ref-и `origin/main`, назви гілок, стан усередині
  `.git/`, шляхи пристрою `filesDir/…`, еліпсис `...`, скорочення
  `errorLine1/2`, саме розширення `.png`, глоби, ігноровані репозиторієм
  каталоги) названі в коді й покриті тестами — щоб ґард не падав на тексті,
  який шляхом не є.

Вихідні коди: 0 — кожен шлях або є в `main`, або позначений `NEW:` і його там
немає; 1 — премиси немає (шлях без позначки, якого в `main` немає, або `NEW:`
на шлях, який у `main` уже є); 2 — невідомість (ref не читається, ім'я
неоднозначне, порожнє тіло).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MAIN_REF = "origin/main"

# Позначка «цей тікет створить шлях» — усередині того самого інтервалу в
# бектиках, перед шляхом. Саме в інтервалі, а не окремим абзацом: тоді вона
# невідривна від шляху й не може «загубитися» при перекладі тікета.
NEW_MARKER = "NEW:"

SPAN_RE = re.compile(r"`([^`\n]+)`")
# Сегмент шляху як його пишуть у тілах issue: літери, цифри, `_`, `.`, `-`,
# `*` (глоб) і `@` (плейсхолдер версії).
SEGMENT_RE = r"[A-Za-z0-9_.\-*@]+"
PATH_RE = re.compile(rf"^{SEGMENT_RE}(/{SEGMENT_RE})*/?$")
# Gradle-ціль: `:app:lintDebug`, `:app:testPureJvm`. Шляхом не є, хоч і містить
# двокрапки; відрізняється від `app/lint-baseline.xml` відсутністю слеша й
# наявністю двокрапки.
GRADLE_TASK_RE = re.compile(r"^:?[A-Za-z][\w.\-]*(:[A-Za-z][\w.\-]*)+$")
# Сегмент версії в назві гілки: `v1.3.9`, `1.6-milestone`. Єдине місце, де
# крапка не робить із назви шлях.
VERSION_SEGMENT_RE = re.compile(r"v?\d+(\.\d+)*(-[A-Za-z0-9._-]+)?")
# Git-ref: `origin/main`, `HEAD`. Формально це `шлях` для PATH_RE, але файлу з
# такою назвою в дереві не буває й бути не мусить — тож це не премиса про
# наявність файлу, і ґард на ньому не падає.
GIT_REF_RE = re.compile(r"^(?:[A-Za-z0-9_.\-]+/)*[A-Za-z0-9_.\-]+$")
KNOWN_REF_HEADS = frozenset({"origin", "upstream", "HEAD", "refs", "remotes", "tags"})
# Голови назв гілок цього репозиторію: `feat/ui-polish`, `fix/purge-4read-legacy`,
# `release/v1.3.9`, `backup/large-screens-900-prerebase`, `spec-53/wave-1`. Гілка —
# не файл, і вимагати її в `main` означало б валити тікети за назву смуги роботи
# (знахідка огляду PR #1268: 6 тікетів у вікні 200).
#
# Голова, яка є ще й справжнім записом верхнього рівня `main`, тут стояти не
# може: вона проковтнула б усі шляхи під собою. Такі голови прибираються **за
# виміром**, і вимір — це команда:
#
#   python3 scripts/issue_premise.py --measure-head docs   # 78 шляхів, 68 тікетів
#   python3 scripts/issue_premise.py --measure-head test   # 0 шляхів
#
# Число пливе разом зі списком issue (вікно — 200 останніх тікетів), а не з
# ref-ом: у шапці тікета #1281 стояло «83 шляхи / 72 тікети», і воно не
# відтворюється ні на новому, ні на старому коді — вікно зсунулося. Це не
# «зламалося», це межа виміру, і вона названа.
#
# `docs` і `doc` прибрано саме тому. `test` і `tests` **лишаються**: вимір дає
# 0, а `test/…` — реальна голова гілок цього репозиторію
# (`refs/heads/test/issue-703-hidden-boundaries`); прибирання коштувало б
# хибної відмови на `tests/wave-1/step-2` (перевірено: код 1 замість 2).
#
# `_is_branch_name` дивиться лише на `segments[0]`, тож `scripts/tests/test_x.py`
# і `app/src/test/java/A.kt` від цього списку не залежать ніколи — їхні голови
# `scripts` і `app`.
BRANCH_HEADS = frozenset(
    {
        "feat", "feature", "fix", "hotfix", "bugfix", "chore", "refactor", "perf",
        "test", "tests", "build", "ci", "revert", "release", "backup", "wip",
        "dependabot", "renovate", "spec",
    }
)
# Корені шляхів пристрою: `filesDir/local_imports` — тека в застосунку, не в
# репозиторії. Премисою про наявний файл у `main` вона не є.
DEVICE_ROOTS = frozenset(
    {"filesDir", "cacheDir", "externalCacheDir", "noBackupFilesDir", "dataDir", "getFilesDir"}
)

# Розширення, за якими шлях упізнається без слеша (`ci.yml`, `mutate.sh`,
# `AGENTS.md`) і зі слешем у короткій формі (`docs/voice.md`).
PATH_EXTENSIONS = frozenset(
    {
        ".md", ".py", ".sh", ".kt", ".kts", ".java", ".xml", ".json", ".yml",
        ".yaml", ".toml", ".properties", ".gradle", ".ts", ".tsx", ".js",
        ".mjs", ".html", ".css", ".txt", ".rules", ".sql", ".png", ".jpg",
        ".svg", ".csv", ".lock", ".cfg", ".ini", ".pro", ".gitignore",
    }
)

# Формати вкладення: `.png`, `.jpg`, `.mp4`. Голе ім'я з таким розширенням —
# це скріншот чи звіт на машині автора, а не шлях репозиторію: #991 пише
# «Скріншоти (файли існують): `live-book-2f-screencap.png`», і файли справді
# існують — у теці на пристрої, не в `main`. Виміряно: 6 із 11 відмов у вікні
# 200 тікетів були такими іменами (огляд PR #1268).
ATTACHMENT_EXTENSIONS = frozenset(
    {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".mp4", ".mov", ".webm", ".pdf", ".zip"}
)

# Розширення, якими дописується посилання без розширення (`web/README` →
# `web/README.md`). Лише документи: дописати `.kt` означало б угадувати код.
DOC_EXTENSIONS = (".md", ".txt", ".html")


@dataclass(frozen=True)
class Candidate:
    """Шлях, названий у тілі issue, і місце, де він названий."""

    path: str
    line_no: int
    marked_new: bool

    def display(self) -> str:
        return f"`{NEW_MARKER}{self.path}`" if self.marked_new else f"`{self.path}`"


@dataclass(frozen=True)
class Verdict:
    """Що ґард каже про один шлях."""

    candidate: Candidate
    code: int  # 0 — премиса є, 1 — відмова, 2 — невідомість
    detail: str

    def display(self) -> str:
        return self.candidate.display()


def extract_spans(body: str) -> list[tuple[str, int]]:
    """Інтервали в бектиках разом із номером рядка (1-based)."""
    spans: list[tuple[str, int]] = []
    for line_no, line in enumerate(body.splitlines(), start=1):
        for match in SPAN_RE.finditer(line):
            spans.append((match.group(1), line_no))
    return spans


def _is_command_span(span: str) -> bool:
    """Рядок-команда: `gh issue view <n> --comments`, `python3 x.py --flag`."""
    tokens = span.split()
    if not tokens:
        return True
    if tokens[0] in {"gh", "git", "python", "python3", "sh", "bash", "npm", "pnpm", "./gradlew", "gradlew", "curl"}:
        return True
    # Опція чи ціль Gradle як перший токен: `--tests`, `:app:lintDebug`.
    return tokens[0].startswith("-") or GRADLE_TASK_RE.match(tokens[0]) is not None


def _is_git_internal(span: str) -> bool:
    """Шлях усередині `.git/` — стан операції, не вміст репозиторію.

    `rebase-merge`, `rebase-apply`, `MERGE_HEAD` там живуть лише під час
    операції; у дереві `main` їх немає й бути не мусить, тож премисою про
    наявний файл вони не є.
    """
    return span == ".git" or span.startswith(".git/")


def _is_shorthand(span: str) -> bool:
    """Скорочення двох імен через слеш: `errorLine1/2`, `values-en/pl`.

    Останній сегмент без крапки й не слово — це не каталог, а друга половина
    пари. Валити його означало б падати на прозі.
    """
    head, _, tail = span.rpartition("/")
    return bool(head) and bool(tail) and "." not in tail and not re.search(r"[A-Za-z_]", tail)


def _is_ignored(path: str, repo: Path) -> bool:
    """Чи шлях ігнорує сам репозиторій (`.mutations`, `build`, `.scratch`).

    Такі каталоги живуть лише на машині й у `main` їх немає **навмисно** —
    тож «немає в `main`» про них не є відмовою. Питаємо `.gitignore` самого
    репозиторію (`git check-ignore --no-index`), а не власний список: список
    розійшовся б із файлом, який ігнорування й задає. `--no-index` потрібен,
    бо шляхи бувають і вже видалені; на помилку git відповідаємо «не
    ігнорується» — це лише запобіжник від шуму, і мовчазна відмова тут
    дорожча за хибний шум.
    """
    if not path:
        return False
    # Питаємо і з хвостовим слешем: `.gitignore` пише `.mutations/` — правило
    # саме для теки, і на голому імені `git check-ignore` його не бачить.
    for probe in (path, path.rstrip("/") + "/"):
        code, _, _ = git_output(["check-ignore", "--no-index", "-q", "--", probe], repo)
        if code == 0:
            return True
    return False


_TOP_LEVEL: dict[tuple[Path, str], frozenset[str]] = {}


def _top_level_entries(ref: str, repo: Path) -> frozenset[str]:
    """Записи верхнього рівня **того самого** ref-а, про який вердикт.

    Не `MAIN_REF`, а саме `ref`: `--ref` існує, і ґард, який судить одне
    дерево, а прозу відрізняє за іншим, розійшовся б сам із собою (знахідка
    огляду PR #1268). Це ж і робило тест залежним від чекауту: у CI `origin/main`
    не існує, дерево порожнє, і `docs/agents` читався як проза.

    Кешується на (repo, ref): один процес `git` на прогін.
    """
    key = (repo, ref)
    if key not in _TOP_LEVEL:
        code, out, _ = git_output(["ls-tree", "--name-only", ref], repo)
        _TOP_LEVEL[key] = frozenset(out.splitlines()) if code == 0 else frozenset()
    return _TOP_LEVEL[key]


def _is_git_ref(span: str) -> bool:
    """Чи це git-ref, а не файл: перший сегмент — відома голова ref-а.

    `origin/main`, `upstream/main`, `refs/heads/main` — ref. `docs/voice.md` —
    ні, хоч форма й та сама; саме тому потрібна голова, а не загальна форма.
    """
    if span in KNOWN_REF_HEADS:
        return True
    if not GIT_REF_RE.match(span):
        return False
    head = span.split("/", 1)[0]
    return head in KNOWN_REF_HEADS


def _is_branch_name(span: str) -> bool:
    """Чи це назва гілки, а не файл: `feat/ui-polish`, `release/v1.3.9`.

    Гілка не мусить існувати в `main` — і не мусить існувати взагалі, поки її
    не створили; тому питати про неї дерево безглуздо, а валити тікет за назву
    смуги роботи — хибна відмова. Розпізнається за головою з відомого списку
    (той самий прийом, що `_is_git_ref`), з відкиданням хвоста-числа: `spec-53`
    — та сама голова `spec`, що й `spec/x`.

    Сегмент версії (`v1.3.9`) — єдиний, де крапка не робить із назви шлях;
    у решті сегментів крапка означає файл (`try/catch` — проза, але
    `web/README` — шлях, і він не гілка).

    Межа: гілка з голови поза списком (`my-branch/x`) лишається кандидатом і
    дасть відмову. Список — не «будь-яке слово до слеша», бо тоді `docs/adr` і
    `app/src` теж перестали б перевірятися, а це вже вміст репозиторію.
    """
    if "/" not in span or not GIT_REF_RE.match(span):
        return False
    segments = span.split("/")
    head = re.sub(r"-\d+$", "", segments[0])
    if head not in BRANCH_HEADS:
        return False
    for segment in segments[1:]:
        if VERSION_SEGMENT_RE.fullmatch(segment):
            continue
        if "." in segment:
            return False
    return True


def _is_prose_fragment(span: str, ref: str, repo: Path) -> bool:
    """Чи це проза чи код зі слешем, а не шлях: `try/catch`.

    Питається **після** того, як дерево не дало нічого: два сегменти без
    крапки, чия голова не є записом верхнього рівня `main` (`try`, `values-en`),
    — це проза, а не шлях. Порядок не косметичний: спитати раніше означало б
    відкинути й `ui/components` — справжню теку в дереві (знахідка огляду
    PR #1268), бо `ui` теж не верхній рівень.

    Це свідомо **неповний** відбір прози, і межа названа: `try/catch` ловиться,
    а `works.title/author/seriesTitle` (крапка в першому сегменті, #822) — ні,
    бо відрізнити його формою від `ui/adaptive/WindowLayout.kt` неможливо.
    """
    segments = span.split("/")
    if len(segments) != 2:
        return False
    if any("." in segment for segment in segments):
        return False
    return segments[0] not in _top_level_entries(ref, repo)


def _is_device_path(span: str) -> bool:
    """Чи це шлях на пристрої: `filesDir/local_imports`, `cacheDir/x`.

    Тека в застосунку, не в репозиторії — премисою про наявний файл у `main`
    вона не є.
    """
    return span.split("/", 1)[0] in DEVICE_ROOTS


def _has_ellipsis_segment(span: str) -> bool:
    """Чи є в шляху сегмент-еліпсис: `app/src/androidTest/...`.

    `...` — це «і далі», а не ім'я каталогу.
    """
    return any(set(segment) == {"."} for segment in span.split("/"))


def _is_bare_extension(span: str) -> bool:
    """Чи це саме розширення, а не ім'я файлу: `.png`, `.html`.

    Ім'я вкладення чи формат у прозі; файлу з назвою `.png` у дереві немає й
    бути не мусить.
    """
    return span.startswith(".") and span.count(".") == 1 and "/" not in span


def _is_attachment_name(span: str) -> bool:
    """Чи це голе ім'я вкладення: `live-book-2f-screencap.png`, `18-person.png`.

    Без слеша й у форматі вкладення — скріншот, звіт чи відео на машині автора.
    Шляхом репозиторію таке ім'я не є: у `main` його може не бути **навмисно**,
    і вимагати його означало б валити тікет за доказ, який лежить поруч із
    тікетом, а не в дереві.

    Лише голе ім'я: `docs/screens/x.png` — це вже названий шлях, і він
    перевіряється як усі.
    """
    return "/" not in span and Path(span).suffix.lower() in ATTACHMENT_EXTENSIONS


def _is_path_like(span: str) -> bool:
    if " " in span or "\t" in span:
        return False
    if span.startswith(("./", "/", "~", "-")):
        return False
    if span.startswith("$") or "<" in span or ">" in span or "{" in span:
        return False
    if span.endswith("(") or span.endswith(")"):
        return False
    if not PATH_RE.match(span):
        return False
    if GRADLE_TASK_RE.match(span):
        return False
    if (
        _is_git_ref(span)
        or _is_branch_name(span)
        or _is_git_internal(span)
        or _is_shorthand(span)
        or _is_device_path(span)
        or _has_ellipsis_segment(span)
        or _is_bare_extension(span)
    ):
        return False
    if "/" in span:
        return True
    # Без слеша: або відоме розширення, або крапка в імені (`CONTEXT.md`).
    return Path(span).suffix.lower() in PATH_EXTENSIONS


def _named_paths(body: str) -> list[Candidate]:
    """Кандидати без звернення до `.gitignore` — та перевірка коштує процес."""
    found: list[Candidate] = []
    seen: set[str] = set()
    for span, line_no in extract_spans(body):
        marked_new = span.startswith(NEW_MARKER)
        path = span[len(NEW_MARKER):] if marked_new else span
        if _is_command_span(path):
            continue
        if not _is_path_like(path):
            continue
        if path.endswith("/"):
            # Каталог: перевіряється як дерево, тож хвостовий слеш прибираємо.
            path = path.rstrip("/")
            if not path:
                continue
        if any("*" in segment for segment in path.split("/")):
            # Глоб називає множину, а не премису про один шлях:
            # `app/src/main/res/values*/strings.xml`, `scripts/tests/test_*.py`.
            continue
        if marked_new and not PATH_RE.match(path):
            continue
        if path in seen:
            continue
        seen.add(path)
        found.append(Candidate(path=path, line_no=line_no, marked_new=marked_new))
    return found


def candidates(body: str, repo: Path = REPO_ROOT) -> list[Candidate]:
    """Шляхи, названі в тілі issue, у порядку появи.

    Винятки, які не є премисою про наявний файл: рядки-команди, Gradle-цілі,
    git-ref-и, стан усередині `.git/`, скорочення двох імен через слеш, глоби
    й те, що ігнорує сам репозиторій (`.mutations`, `build`, `.scratch`) —
    усе це покрито тестами, щоб ґард не падав на тексті, який шляхом не є.

    `.gitignore` питається останнім і лише про шляхи, які вже пройшли решту
    відбору: `git check-ignore` — це процес, і платити за нього на кожен
    інтервал у бектиках означало б платити за прозу.
    """
    return [
        candidate
        for candidate in _named_paths(body)
        if candidate.marked_new or not _is_ignored(candidate.path, repo)
    ]


def git_output(args: list[str], repo: Path = REPO_ROOT) -> tuple[int, str, str]:
    """`git` у названому репозиторії.

    `repo` — не дрібниця: у CI чекаут не має `origin/main` (раннер фетчить
    merge-ref), тож ґард, який завжди дивився б у `REPO_ROOT`, там не бачив би
    нічого, а тест — залежав би від того, що завантажив раннер. Те саме
    дозволяє тестам підняти **свій** репозиторій у `/tmp` зі своїм `origin/main`
    і перевіряти справжній git, а не підставку.
    """
    proc = subprocess.run(
        ["git", *args],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    return proc.returncode, proc.stdout, proc.stderr


def resolve_main_ref(ref: str, repo: Path = REPO_ROOT) -> tuple[bool, str]:
    """Чи читається ref. Невідомість, а не мовчазний відкат на HEAD."""
    code, out, err = git_output(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], repo)
    if code != 0:
        return False, (err.strip() or f"{ref} не читається")
    return True, out.strip()


def main_has_path(ref: str, path: str, repo: Path = REPO_ROOT) -> bool:
    """Чи є в дереві ref запис із такою назвою — файл або каталог.

    Точний шлях — найсильніший доказ, і він про **той самий** запис: тікет
    назвав його повністю. Тому тип тут не питається: `cat-file -e` відповідає й
    про блоб, і про дерево, і кожна з цих відповідей означає премису (межа
    названа в `judge`).
    """
    code, _, _ = git_output(["cat-file", "-e", f"{ref}:{path}"], repo)
    return code == 0


# Тип запису в дереві. Не косметика: коротка форма шукає хвіст по всьому
# дереву, і для БЛОБА унікальний хвіст означає «той самий файл», а для ДЕРЕВА —
# ні (#1281, див. `judge`).
BLOB = "blob"
TREE = "tree"

# Рядок `git ls-tree`: `<mode> <type> <object>\t<path>`. `--name-only` тут не
# стоїть навмисно — він відкидає тип, а без типу вердикт не відрізняє блоб від
# дерева. Імена з екзотичними байтами git віддає в лапках і з екрануванням;
# такий рядок не розбереться й **не стане збігом** — тобто вердикт піде в бік
# відмови, а не в бік тихого «премиса є».
LS_TREE_LINE_RE = re.compile(r"^\d+ (?P<kind>blob|tree) [0-9a-f]+\t(?P<path>.+)$")


@dataclass(frozen=True)
class Match:
    """Знайдений у дереві запис і його тип — те, чого не дає `--name-only`."""

    path: str
    kind: str  # BLOB | TREE

    def label(self) -> str:
        """Шлях у вердикті: тека названа текою, бо це змінює вагу доказу."""
        return f"{self.path} (тека)" if self.kind == TREE else self.path


def _is_suffix_match(path: str, suffix: str) -> bool:
    """Чи `path` закінчується на `suffix` на межі сегмента (або дорівнює йому).

    Питання короткої форми, і воно винесене окремо, бо його ставлять **двоє**:
    вердикт (`main_matches_suffix`) і вимір ціни правила (`bare_tree_names`).
    Два прочитання «на межі сегмента» розійшлися б — і число в шапці перестало
    б бути числом того самого правила, яке ухвалює вердикт.
    """
    return path == suffix or path.endswith("/" + suffix)


def _parse_tree(out: str) -> list[Match]:
    """Рядки `ls-tree` — у записи з типом.

    Спільне читання дерева для вердикту й виміру: незрозумілий рядок **не стає
    записом** навмисно (див. `LS_TREE_LINE_RE`) — вердикт іде в бік відмови, а
    не тихого «премиса є», а вимір не рахує те, чого не побачив.
    """
    entries: list[Match] = []
    for line in out.splitlines():
        parsed = LS_TREE_LINE_RE.match(line)
        if parsed is not None:
            entries.append(Match(parsed.group("path"), parsed.group("kind")))
    return entries


def tree_entries(ref: str, repo: Path = REPO_ROOT) -> tuple[Match, ...]:
    """Усе дерево ref із типами — одне `ls-tree` на виклик, для **виміру**.

    Вердикт лишається зі своїм `ls-tree` на кожен шлях (`main_matches_suffix`):
    тести пінять саме його аргументи (`-t`, без `--name-only`). Спільними тут
    лишаються `LS_TREE_LINE_RE`, `_is_suffix_match` і `TREE` — тобто означення
    класу, а не спосіб його прочитати.
    """
    code, out, _ = git_output(["ls-tree", "-r", "-t", ref], repo)
    if code != 0:
        return ()
    return tuple(_parse_tree(out))


def main_matches_suffix(ref: str, suffix: str, repo: Path = REPO_ROOT) -> list[Match]:
    """Записи дерева ref, що закінчуються на `suffix` на межі сегмента, з типом.

    Потрібне для короткої форми: тіло issue пише `ci.yml`, а файл лежить у
    `.github/workflows/ci.yml`; пише `ui/adaptive/WindowLayout.kt`, а файл — під
    `app/src/main/java/com/slukhayka/audiobooks/`. Одне входження — це той самий
    файл; кілька — невідомість, бо вибір між ними був би здогадом. Для теки
    «одне входження» слабше, і це розібрано в `judge`.

    `-t` обов'язковий: без нього `ls-tree` віддає лише блоби, і каталог, який у
    `main` **є**, не знаходився ніколи — `ui/components` давав 0 збігів, хоч у
    дереві є (`app/src/main/java/com/slukhayka/audiobooks/ui/components`,
    `app/src/test/java/…/ui/components`, `web/src/ui/components`). Каталог — така
    сама премиса, як файл. Знахідка огляду PR #1268.

    Тип береться з поля об'єкта, а не вгадується з імені: тека `ci.yml` — теж
    тека, хоч і виглядає файлом. Рядок, якого регулярка не розібрала, не стає
    збігом навмисно (див. `LS_TREE_LINE_RE`).
    """
    code, out, _ = git_output(["ls-tree", "-r", "-t", ref], repo)
    if code != 0:
        return []
    return sorted(
        (match for match in _parse_tree(out) if _is_suffix_match(match.path, suffix)),
        key=lambda match: match.path,
    )


def infer_extension(ref: str, path: str, repo: Path = REPO_ROOT) -> list[Match]:
    """Шлях без розширення, який насправді є файлом із розширенням.

    `web/README` у тілі — це `web/README.md` у дереві. Дописуються лише
    розширення, якими цей репозиторій справді користується (документи й
    README), і лише коли точного шляху й хвоста не знайшлося: інакше
    `web/prototype` — тека, якої в `main` немає **навмисно** (тікет #816 так і
    пише) — став би «є» через вигаданий `web/prototype.md`, і ґард перестав би
    бачити те, що мусить бачити.

    Повертає ті самі кандидати, що `main_matches_suffix`: одне входження
    проходить, кілька — невідомість.
    """
    found: dict[str, Match] = {}
    for extension in DOC_EXTENSIONS:
        for match in main_matches_suffix(ref, path + extension, repo):
            found[match.path] = match
    return sorted(found.values(), key=lambda match: match.path)


def _is_bare_name(path: str) -> bool:
    """Односегментне ім'я без розширення: `main`, `tab_settings`, `res`.

    Саме такі імена й дають межу #1281: `main/` у тілі тікета — це тека
    Kotlin-джерел (`app/src/main`), тег (`tab_settings`) або ключ, і жодне з
    них не є «тим самим файлом» лише тому, що в дереві знайшлася тека з таким
    іменем. Ім'я з розширенням (`ci.yml`) або зі слешем (`ui/components`) сюди
    не входить: там хвіст на межі сегмента — вже сильний доказ (межа названа в
    `judge`).
    """
    return "/" not in path and not Path(path).suffix


def bare_tree_names(ref: str, repo: Path = REPO_ROOT) -> list[str]:
    """Імена, які правило про теку переносить із «премиса є» (0) у 2 — ЦІНА.

    Це число, яким названа асиметрія правила, і означення живе тут, у коді, бо
    «75» без нього не перевірити. Ім'я входить у клас, коли ВСЕ це правда:

      * `_is_bare_name(name)` — односегментне й без розширення (те саме
        означення, що й у `judge`);
      * імені немає серед записів верхнього рівня `ref`: таке ім'я знаходить
        `main_has_path` **першим**, і вердикт каже «є» незалежно від глибини.
        Саме тому кореневе `docs/` не платить, а `drawable/` платить;
      * коротка форма (`_is_suffix_match`) дає **рівно один** збіг, і це
        **дерево** (`TREE`).

    Ім'я, чия коротка форма дає блоб, у клас не входить: воно лишається
    премисою і до, і після правила. Ім'я з двома й більше збігами — теж: воно
    було неоднозначним і до нього, тож переходу 0 → 2 не робить.

    Сусідні означення дають сусідні числа, і жодне з них не «те саме»:
    літеральне «без крапки в імені» — 74 (не рахує `.aistudio`), «разом із
    кореневими іменами» — 78, але кореневі (`app`, `docs`, `gradle`, `web`)
    не платять: точний шлях знаходить їх першим. Число «132» з попередньої
    редакції шапки не відтворюється; найближче — 131 («будь-який збіг-дерево»,
    без вимоги єдиності).
    """
    entries = tree_entries(ref, repo)
    top_level = _top_level_entries(ref, repo)
    names = sorted({match.path.rsplit("/", 1)[-1] for match in entries})
    counted: list[str] = []
    for name in names:
        if not _is_bare_name(name) or name in top_level:
            continue
        matches = [match for match in entries if _is_suffix_match(match.path, name)]
        if len(matches) == 1 and matches[0].kind == TREE:
            counted.append(name)
    return counted


def judge(candidate: Candidate, ref: str, repo: Path = REPO_ROOT) -> Verdict:
    """Вердикт про один шлях: повний шлях, потім коротка форма.

    Порядок саме такий, і він не косметичний: спершу питається точний шлях
    (`git cat-file -e`), і лише коли його в `main` немає — коротка форма
    (`ui/adaptive/WindowLayout.kt` замість повного шляху під
    `app/src/main/java/…`, `ci.yml` замість `.github/workflows/ci.yml`). Одне
    входження короткої форми — той самий файл; кілька — неоднозначність (код 2),
    бо вибір між ними був би здогадом.

    Межа, названа тут і закрита тікетом #1281: «одне входження = той самий
    файл» істинне для **блоба** й хибне для **дерева**, коли ім'я
    односегментне й без розширення. Тікет #982 пише «тега `tab_settings` у
    `main/` немає», а ґард знаходив теку `app/src/main` на довільній глибині й
    казав «премиса є» — хоч у тікеті йдеться про тег, а не про теку. Тому таке
    входження — **неоднозначність** (код 2): односегментне ім'я без розширення
    може бути тегом, символом чи ключем, і тека з таким ім'ям десь у дереві не
    доводить, що названо саму її.

    Що це правило НЕ чіпає (і чому саме так):

    * **точний шлях** — питається першим: тікет, який назвав теку повністю
      (`docs/adr/`, `app/src/main/`), премису має, і вердикт каже це без
      здогадів. **Звідси асиметрія ВИВОДУ, і вона названа тут: дискримінатор —
      не глибина, а ДОКАЗ.** Точний запис дерева є в `docs/` і `web/`, тож вони
      премиса; у `drawable/` його немає, і коротка форма лишає код 2. Прибрати
      цю різницю можна лише двома способами, і кожен названо: **розширити
      форму** (будь-яка гола тека за єдиним збігом-деревом — премиса) означає
      зробити премисою і `main/` із #982, тобто відкрити #982 назад (доведено
      прогоном у
      `test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep`);
      **читати тіло як ціле** (розв'язати `drawable/` через названий у тому ж
      тілі `…/drawable/img_…jpg`) — це здогад про намір автора, і він не
      прибирає асиметрію, а лише маскує її там, де тіло назвало повний шлях.
      Тому лишається те, що є, і його ціна — число:
      `python3 scripts/issue_premise.py --measure-bare-trees` друкує
      односегментні імена-теки без розширення, не кореневі, чия єдина коротка
      форма — дерево; на `origin/main` (`f81c724f`) їх **75**, серед них
      `drawable`, `generator`, `workflows` і `main`. Обидва кінці асиметрії
      піновані тестами;
    * **теку, знайдену за хвостом багатосегментного імені** (`ui/components`,
      `ui/adaptive`): тут хвіст на межі сегмента — вже сильний доказ, і це
      свідоме рішення огляду PR #1268, а не недогляд. Ширша форма («будь-яка
      тека, знайдена за хвостом, — неоднозначність») коштувала б хибних
      неоднозначностей на звичайних шляхах і не потрібна: ціна названа, форма
      вужча;
    * **позначку `NEW:`** — її друге плече («оголосив новим те, що вже є»)
      питає «чи є щось із таким ім'ям», а не «чи це той самий файл»; воно
      лишається відмовою, а не слабшає до невідомості;
    * **ім'я з розширенням**, знайдене лише як тека (`ci.yml`-тека): лишається
      «є» — таке ім'я майже завжди файл, і правило звужене до випадку, який
      справді дав хибне «премиса є».
    """
    if main_has_path(ref, candidate.path, repo):
        # Точний шлях — найсильніший доказ, і він не залежить від того, чи є в
        # дереві ще щось із таким хвостом.
        if candidate.marked_new:
            return Verdict(
                candidate,
                1,
                f"позначений `{NEW_MARKER}`, але в {ref} уже є — позначку зняти",
            )
        return Verdict(candidate, 0, f"є в {ref}")

    matches = main_matches_suffix(ref, candidate.path, repo)
    if not matches and not Path(candidate.path).suffix:
        # Посилання без розширення на наявний файл: тіло пише `web/README`, а
        # файл — `web/README.md`. Дописуємо лише відомі розширення й лише коли
        # без них не знайшлося нічого: інакше `web/prototype` (тека, якої в
        # `main` немає навмисно) став би «є» через вигаданий `web/prototype.md`.
        matches = infer_extension(ref, candidate.path, repo)
    if candidate.marked_new:
        if matches:
            return Verdict(
                candidate,
                1,
                f"позначений `{NEW_MARKER}`, але в {ref} уже є: {matches[0].label()} — "
                "позначку зняти",
            )
        return Verdict(candidate, 0, f"немає в {ref} — позначка `{NEW_MARKER}` доречна")
    if len(matches) == 1:
        if matches[0].kind == TREE and _is_bare_name(candidate.path):
            # #1281: «одне входження = той самий файл» хибне для теки, коли ім'я
            # односегментне й без розширення. Не відмова: тека з таким іменем
            # справді є, і стверджувати «премиси немає» було б другою
            # неправдою. Не «є»: у тікеті могло йтися про тег, символ чи ключ.
            return Verdict(
                candidate,
                2,
                f"ім'я неоднозначне в {ref}: єдина коротка форма — тека "
                f"{matches[0].path}, а односегментне ім'я без розширення може бути "
                "тегом, символом чи ключем; тека на довільній глибині не доводить, "
                "що названо саму її — назви повний шлях",
            )
        return Verdict(candidate, 0, f"є в {ref}: {matches[0].label()}")
    if len(matches) > 1:
        return Verdict(
            candidate,
            2,
            f"ім'я неоднозначне в {ref} ({len(matches)} входжень: "
            f"{', '.join(match.label() for match in matches[:4])}"
            f"{'…' if len(matches) > 4 else ''}) — назви повний шлях",
        )
    if _is_prose_fragment(candidate.path, ref, repo):
        # Дерево не дало нічого, і формою це не шлях — проза зі слешем.
        return Verdict(candidate, 0, "шляхом не є (проза чи код зі слешем) — не перевіряється")
    if _is_attachment_name(candidate.path):
        # Дерево не дало нічого, і це голе ім'я вкладення — доказ лежить поруч
        # із тікетом, а не в дереві.
        return Verdict(candidate, 0, "шляхом репозиторію не є (ім'я вкладення) — не перевіряється")
    return Verdict(
        candidate,
        1,
        f"немає в {ref} ні за повним шляхом, ні за хвостом — премиси немає; "
        f"якщо тікет його створює, познач `{NEW_MARKER}{candidate.path}`",
    )


def check_body(body: str, ref: str, repo: Path = REPO_ROOT) -> tuple[int, list[Verdict]]:
    ok, detail = resolve_main_ref(ref, repo)
    if not ok:
        # Порада залежить від ref-а: `git fetch origin main` лікує лише
        # віддалений ref. Для локального (`HEAD`, `main`) вона була б шумом.
        hint = (
            "зроби `git fetch origin main`"
            if "/" in ref and ref.split("/", 1)[0] in KNOWN_REF_HEADS
            else f"перевір, чи існує ref `{ref}`"
        )
        return 2, [
            Verdict(
                Candidate(path=ref, line_no=0, marked_new=False),
                2,
                f"ref `{ref}` не читається ({detail}) — {hint}; "
                "вердикт про невідомий ref був би здогадом",
            )
        ]
    found = candidates(body, repo)
    if not found:
        return 2, [
            Verdict(
                Candidate(path="", line_no=0, marked_new=False),
                2,
                "у тілі не знайдено жодного шляху в бектиках — перевіряти нічого; "
                "це невідомість, а не зелене світло",
            )
        ]
    verdicts = [judge(candidate, ref, repo) for candidate in found]
    worst = max((v.code for v in verdicts), default=0)
    return worst, verdicts


OUTCOME = {
    0: "ПРЕМИСА Є",
    1: "ВІДМОВА: премиси немає",
    2: "НЕВІДОМІСТЬ",
}


def format_report(ref: str, code: int, verdicts: list[Verdict]) -> str:
    lines = [f"issue_premise: ref {ref} — {OUTCOME[code]}", ""]
    for verdict in verdicts:
        where = f"рядок {verdict.candidate.line_no}: " if verdict.candidate.line_no else ""
        lines.append(f"  [{verdict.code}] {where}{verdict.candidate.display()} — {verdict.detail}")
    counted = [v for v in verdicts if v.candidate.path]
    if counted:
        exists = sum(1 for v in counted if v.code == 0 and not v.candidate.marked_new)
        marked = sum(1 for v in counted if v.code == 0 and v.candidate.marked_new)
        refused = sum(1 for v in counted if v.code == 1)
        unknown = sum(1 for v in counted if v.code == 2)
        lines.append("")
        lines.append(
            f"шляхів перевірено: {len(counted)} — є в {ref}: {exists}, "
            f"позначено `{NEW_MARKER}`: {marked}, відмов: {refused}, невідомість: {unknown}"
        )
    return "\n".join(lines)


def read_body(args: argparse.Namespace) -> str:
    if args.issue is not None:
        proc = subprocess.run(
            ["gh", "issue", "view", str(args.issue), "--json", "body", "--jq", ".body"],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SystemExit(
                f"issue_premise: не вдалося прочитати #{args.issue}: {proc.stderr.strip() or proc.returncode}"
            )
        return proc.stdout
    if args.body_file == "-":
        return sys.stdin.read()
    return Path(args.body_file).read_text(encoding="utf-8")


def measure_head(head: str, limit: int, ref: str, repo: Path = REPO_ROOT) -> int:
    """Скільки шляхів у тілах тікетів мають таку голову — і чи є верхня тека.

    Числа в коментарі до `BRANCH_HEADS` («docs — 78 шляхів») не мусять бути
    твердженням: та сама команда дає ті самі числа. Але тільки на **тому
    самому вікні**: 200 останніх тікетів — це список issue, а він рухається,
    тож число не піноване ні ref-ом, ні кодом (межа, названа в шапці).

        python3 scripts/issue_premise.py --measure-head docs

    Голова, яка є записом верхнього рівня `main`, у `BRANCH_HEADS` стояти не
    може: вона проковтнула б усі шляхи під собою. Саме цей вимір і вирішує,
    які голови прибрати, а які лишити.

    Нечитабельний ref — код 2, а не «верхня тека: немає»: на невідомому ref-і
    `_top_level_entries` віддає порожню множину, і «немає» тоді було б
    твердженням про дерево, якого ніхто не бачив. У CI `origin/main` немає, тож
    це не теорія. `--scan` уже відмовляється міряти такий ref — вимір робить
    те саме.
    """
    ok, _ = resolve_main_ref(ref, repo)
    if not ok:
        print(f"issue_premise: ref `{ref}` не читається — міряти нічого", file=sys.stderr)
        return 2
    top_level = _top_level_entries(ref, repo)
    listed = subprocess.run(
        ["gh", "issue", "list", "--state", "all", "--limit", str(limit), "--json", "number", "--jq", ".[].number"],
        capture_output=True, text=True, check=False,
    )
    if listed.returncode != 0:
        print(f"issue_premise: не вдалося перелічити issue: {listed.stderr.strip()}", file=sys.stderr)
        return 2
    paths, tickets = 0, set()
    for number in listed.stdout.split():
        body = subprocess.run(
            ["gh", "issue", "view", number, "--json", "body", "--jq", ".body"],
            capture_output=True, text=True, check=False,
        ).stdout
        for candidate in _named_paths(body):
            if candidate.path.split("/")[0] == head:
                paths += 1
                tickets.add(number)
    print(f"issue_premise: голова `{head}` на {limit} тікетах (ref {ref})")
    print(f"  шляхів у тілах: {paths}, тікетів: {len(tickets)}")
    print(f"  верхня тека `{head}` у дереві: {'є' if head in top_level else 'немає'}")
    return 0


def measure_bare_trees(ref: str, repo: Path = REPO_ROOT) -> int:
    """Ціна правила про теку — числом, яке виводить той самий механізм.

        python3 scripts/issue_premise.py --measure-bare-trees

    Число «75» у шапці не мусить бути твердженням, якому вірять на слово: та
    сама команда на тому самому `origin/main` друкує ті самі імена, а ревізію
    називає, щоб було видно, про який стан ішлося. Клас рахує `bare_tree_names`
    — тими самими предикатами, що й вердикт (`_is_bare_name`,
    `_is_suffix_match`, `TREE`), тож число належить правилу, а не переказу.

    Нечитабельний ref — код 2, а не «імен немає»: на невідомому ref-і
    `tree_entries` і `_top_level_entries` порожні, і нуль був би твердженням про
    дерево, якого ніхто не бачив (та сама причина, що в `measure_head`).
    """
    ok, revision = resolve_main_ref(ref, repo)
    if not ok:
        print(f"issue_premise: ref `{ref}` не читається — міряти нічого", file=sys.stderr)
        return 2
    names = bare_tree_names(ref, repo)
    print(
        f"issue_premise: ціна правила про теку (#1281) на ref {ref} ({revision[:8]})"
    )
    print(
        "  односегментних імен-тек без розширення, не кореневих, чия єдина "
        f"коротка форма — дерево: {len(names)}"
    )
    for name in names:
        print(f"    {name}")
    return 0


def scan(limit: int, ref: str, repo: Path = REPO_ROOT) -> int:
    """Шум на останніх тікетах репозиторію — числом, яке можна відтворити.

    Числа в шапці й у `docs/agents/issue-tracker.md` не мусять бути
    твердженням, якому вірять на слово: та сама команда на тому самому
    `origin/main` дає ті самі числа, і друкує ревізію, щоб було видно, про
    який стан ішлося.
    """
    ok, revision = resolve_main_ref(ref, repo)
    if not ok:
        print(f"issue_premise: ref `{ref}` не читається — переміряти нічого", file=sys.stderr)
        return 2
    listed = subprocess.run(
        ["gh", "issue", "list", "--state", "all", "--limit", str(limit), "--json", "number", "--jq", ".[].number"],
        capture_output=True,
        text=True,
        check=False,
    )
    if listed.returncode != 0:
        print(f"issue_premise: не вдалося перелічити issue: {listed.stderr.strip()}", file=sys.stderr)
        return 2

    premise, no_paths, ambiguous, refused = 0, 0, {}, {}
    for number in listed.stdout.split():
        body = subprocess.run(
            ["gh", "issue", "view", number, "--json", "body", "--jq", ".body"],
            capture_output=True, text=True, check=False,
        ).stdout
        code, verdicts = check_body(body, ref, repo)
        bad = [v.candidate.path for v in verdicts if v.code == 1]
        if bad:
            refused[number] = bad
        elif code == 0 and any(v.candidate.path for v in verdicts):
            premise += 1
        elif all(not v.candidate.path for v in verdicts):
            no_paths += 1
        else:
            ambiguous[number] = [v.candidate.path for v in verdicts if v.code == 2]

    total = len(listed.stdout.split())
    print(f"issue_premise: шум на {total} тікетах, ref {ref} ({revision[:8]})")
    print(f"  ПРЕМИСА Є: {premise}")
    print(f"  НЕВІДОМІСТЬ (шляхів у бектиках немає): {no_paths}")
    print(f"  НЕВІДОМІСТЬ (ім'я неоднозначне): {len(ambiguous)}")
    for number, paths in sorted(ambiguous.items()):
        print(f"    #{number}: {', '.join(paths)}")
    print(f"  ВІДМОВА: {len(refused)} тікетів, {sum(len(v) for v in refused.values())} шляхів")
    for number, paths in sorted(refused.items()):
        print(f"    #{number}: {', '.join(paths)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="issue_premise.py",
        description="Перевіряє, що кожен шлях із тіла issue існує в origin/main (або позначений NEW:).",
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--body-file", help="файл із тілом issue, або `-` для stdin")
    source.add_argument("--issue", type=int, help="номер issue: тіло читається через `gh issue view`")
    source.add_argument(
        "--scan",
        type=int,
        metavar="N",
        help="переміряти шум на N останніх тікетах (той самий вимір, що в шапці механізму)",
    )
    source.add_argument(
        "--measure-head",
        metavar="HEAD",
        help="скільки шляхів у тілах тікетів мають таку голову (вимір для BRANCH_HEADS)",
    )
    source.add_argument(
        "--measure-bare-trees",
        action="store_true",
        help="скільки імен платять за правило про теку: односегментне ім'я без "
             "розширення, не кореневе, чия єдина коротка форма — дерево",
    )
    # Help цієї опції НЕ називає `--measure-head`, і це не косметика: сама лише
    # згадка робила сторожа «команда з документа існує» фальшивим — вирізавши
    # блок `source.add_argument("--measure-head", …)`, той сторож лишався
    # зеленим, бо підрядок у help усе ще був (огляд PR #1268). Назва опції живе
    # там, де її оголошено, і ніде більше.
    parser.add_argument(
        "--scan-limit", type=int, default=200, help="скільки тікетів брати у вимірі голів"
    )
    parser.add_argument(
        "--ref",
        default=MAIN_REF,
        help=f"ref, стан якого вважається станом main (типово {MAIN_REF})",
    )
    parser.add_argument(
        "--repo",
        type=Path,
        default=REPO_ROOT,
        help=f"репозиторій, у якому питається git (типово корінь цього скрипта)",
    )
    parser.add_argument("--quiet", action="store_true", help="друкувати лише підсумок і відмови")
    args = parser.parse_args(argv)

    if args.measure_head is not None:
        return measure_head(args.measure_head, args.scan_limit, args.ref, args.repo)
    if args.measure_bare_trees:
        return measure_bare_trees(args.ref, args.repo)
    if args.scan is not None:
        return scan(args.scan, args.ref, args.repo)

    body = read_body(args)
    code, verdicts = check_body(body, args.ref, args.repo)
    if args.quiet:
        for verdict in verdicts:
            if verdict.code != 0:
                print(f"[{verdict.code}] {verdict.display()} — {verdict.detail}")
        print(f"issue_premise: {OUTCOME[code]} (ref {args.ref})")
    else:
        print(format_report(args.ref, code, verdicts))
    return code


if __name__ == "__main__":
    sys.exit(main())
