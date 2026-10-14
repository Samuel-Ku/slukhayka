import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import issue_premise as ip  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

# Записи верхнього рівня — з дерева **того чекаута, у якому біжить тест**, а не
# з `origin/main`. Причина: у CI `origin/main` немає (раннер фетчить merge-ref),
# і тест, який тримався б за нього, просто **не біг би там** — тобто не
# охороняв би нічого. `HEAD` є завжди; інваріант від цього не слабшає, бо він і
# мусить перевіряти те дерево, у якому біжить.
TOP_LEVEL_ENTRIES = (
    ".env.example", ".github", ".gitignore", "AGENTS.md", "AUTHORS.md", "CONTEXT.md",
    "CONTRIBUTING.md", "LICENSE", "README.md", "RELEASE-BRANCH.md", "app", "assets",
    "build.gradle.kts", "docs", "firestore.rules", "gradle", "gradle.properties",
    "gradlew", "gradlew.bat", "metadata.json", "opencode.json", "renovate.json",
    "scripts", "settings.gradle.kts", "sources.json", "web",
)

TOP_LEVEL_IN_TREE = frozenset(
    subprocess.run(
        ["git", "ls-tree", "--name-only", "HEAD"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    ).stdout.split()
)


def build_repo(base: Path, files: dict[str, str], gitignore: str = "") -> Path:
    """Репозиторій зі **своїм** `origin/main` — у `/tmp`, не в чекауті.

    Той самий прийом, що в `test_mutate.py` і `test_review_pass.py`: тест, який
    питає git, мусить мати власний git-стан, а не позичати його в машини, де
    біжить. `origin/main` створюється як **віддалений** ref (push у bare
    `origin`), бо саме на нього дивиться ґард.
    """
    base.mkdir(parents=True, exist_ok=True)
    remote, repo = base / "origin.git", base / "repo"
    run = lambda *args, cwd: subprocess.run(  # noqa: E731
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    )
    subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    run("config", "user.email", "test@example.com", cwd=repo)
    run("config", "user.name", "Test", cwd=repo)
    for relative, content in files.items():
        target = repo / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    if gitignore:
        (repo / ".gitignore").write_text(gitignore, encoding="utf-8")
    run("add", "-A", cwd=repo)
    run("commit", "-q", "-m", "base", cwd=repo)
    run("remote", "add", "origin", str(remote), cwd=repo)
    run("push", "-q", "origin", "HEAD:refs/heads/main", cwd=repo)
    run("fetch", "-q", "origin", "main", cwd=repo)
    return repo

# Тіло тікета #1261 — справжній приклад класу, який ловить ґард. Текст той
# самий, що в тілі issue; у тесті він лежить фікстурою, бо тест мусить бігти
# без мережі й без `gh`. Прогін по справжньому #1261 — у звіті тікета.
ISSUE_1261_BODY = """## What to build

Кореневий `AGENTS.md` каже агенту першим ділом прочитати `CODING_STANDARDS.md` — **файлу в репозиторії немає**. Тікет приводить репозиторій до згоди із самим собою: або файл з'являється зі справжнім змістом, або посилання прибирається (і тоді названо, де стандарти живуть насправді).

## Acceptance criteria

- [ ] Кожне посилання на `CODING_STANDARDS.md` у репозиторії або веде на наявний файл, або прибране — перевірено `grep`-ом по всьому дереву, не лише по `AGENTS.md`.
- [ ] Якщо файл створюється — він містить **справжні** правила, які вже діють у репозиторії (а не переказ `CONTRIBUTING.md`), і кожне правило посилається на код або перевірку, яка його тримає.

## Blocked by

- None (can start immediately).
"""

# Тіло тікета #982 — справжній приклад межі, яку закриває #1281: тікет пише
# «тега `tab_settings` у `main/` немає», а ґард знаходив у дереві **теку**
# `app/src/main` (на довільній глибині) і казав «премиса є». Текст той самий, що
# в тілі issue (перші два абзаци, де названо шлях); у тесті він фікстурою, бо
# тест мусить бігти без мережі й без `gh`. Прогін по справжньому #982 — у звіті
# тікета.
ISSUE_982_BODY = """**Знайдено під час аудиту патерну очікування — але це НЕ гонка, а той самий клас, що #956 і #958: тест посилається на UI, якого вже немає.**

`SearchReturnNavigationTest.kt:48` клікає `onNodeWithTag("tab_settings")`, **а тега `tab_settings` у `main/` немає від #860** (перевірено `grep`-ом). Тобто тест або **падає**, або **не запускається** взагалі.
"""

# Дерево, у якому #982 дав хибне «премиса є»: єдина коротка форма `main` —
# **тека** `app/src/main` (перевірено: `git ls-tree -r -t origin/main`).
TREE_OF_982 = {"app/src/main", "app/src/main/AndroidManifest.xml"}

# Тікет, чия премиса в `main` є: `AGENTS.md`, `CONTRIBUTING.md`,
# `docs/agents/work-coordination.md`, `docs/voice.md` — усі в main.
VALID_PREMISE_BODY = """## What to build

`AGENTS.md` і `CONTRIBUTING.md` ведуть агента до стандартів, але не називають
`docs/agents/work-coordination.md` там, де агент читає. Тікет додає покажчик.

## Acceptance criteria

- [ ] `AGENTS.md` згадує `docs/agents/work-coordination.md` одним рядком.
- [ ] `docs/voice.md` згадано там, де пишуть тексти назовні.

## Blocked by

- None (can start immediately).
"""


# Підставний `gh` для сторожа, який ВИКЛИКАЄ команду з документа: без мережі й
# без автентифікації. `gh issue list` віддає номери, `gh issue view <n>` — те
# саме тіло, у якому названо шлях із головою `docs`.
GH_STUB = """#!/bin/sh
case "$1 $2" in
  "issue list") printf '1\\n2\\n' ;;
  "issue view") printf 'читай `docs/voice.md`\\n' ;;
  *) exit 1 ;;
esac
"""


class FakeTree:
    """Дерево `origin/main` як множина шляхів — із ТИПОМ запису (#1281).

    Ґард судить стан `main`, тож тест підставляє саме дерево: без цього кожен
    тест залежав би від того, що лежить у робочій копії, — тобто від того
    самого, з чого народився #1261. Тип (`blob`/`tree`) — не подробиця: вердикт
    про коротку форму залежить від нього, і підставка без типу підставляла б не
    те дерево, яке дає `git ls-tree`.
    """

    def __init__(self, paths, trees=()):
        self.paths = set(paths)
        self.trees = set(trees)
        if not self.trees <= self.paths:
            # Тека, якої немає в дереві, — фікстура про інше дерево: без цієї
            # відмови тест тихо пінив би не те, що назвав.
            raise AssertionError("тека, якої немає в дереві: %s" % (self.trees - self.paths))
        self.calls = []
        self.top = frozenset(p.split("/")[0] for p in self.paths)

    def _kind(self, path):
        # Тип мусить бути названий явно: мовчазний `blob` за замовчуванням
        # зробив би з підставленої теки блоб і тихо вимкнув правило (#1281).
        return ip.TREE if path in self.trees else ip.BLOB

    def __call__(self, ref, path, repo=None):
        self.calls.append(path)
        return path in self.paths

    def suffixes(self, ref, suffix, repo=None):
        self.calls.append(f"suffix:{suffix}")
        return sorted(
            (ip.Match(p, self._kind(p)) for p in self.paths
             if p == suffix or p.endswith("/" + suffix)),
            key=lambda match: match.path,
        )

    def top_level(self, ref, repo):
        self.calls.append(f"top:{ref}")
        return self.top


def judge_body(body, paths, ref="origin/main", trees=()):
    """Вердикт по тілу, де дерево `main` підставне, а не взяте з машини.

    `trees` — ті шляхи, які в дереві є **теками**, а не блобами: тип віддає
    `git ls-tree`, і вердикт про коротку форму залежить саме від нього (#1281).

    Патчиться й `_top_level_entries`: у CI `origin/main` не існує (раннер
    фетчить merge-ref), тож справжній виклик повернув би порожню множину — і
    `docs/agents` прочитався б як проза. Саме це й упало в CI PR #1268:
    локально тест був зелений, бо на машині `origin/main` є.
    """
    tree = FakeTree(paths, trees)
    with mock.patch.object(ip, "resolve_main_ref", return_value=(True, "0" * 40)), mock.patch.object(
        ip, "main_has_path", tree
    ), mock.patch.object(ip, "main_matches_suffix", tree.suffixes), mock.patch.object(
        ip, "_top_level_entries", tree.top_level
    ), mock.patch.object(
        ip, "_is_ignored", lambda path, repo=None: False
    ):
        code, verdicts = ip.check_body(body, ref)
    return code, verdicts, tree


class ExtractionTest(unittest.TestCase):
    def test_backticked_paths_are_candidates_in_order(self):
        found = ip.candidates("спершу `AGENTS.md`, потім `docs/voice.md`")
        self.assertEqual([c.path for c in found], ["AGENTS.md", "docs/voice.md"])
        self.assertEqual([c.line_no for c in found], [1, 1])

    def test_line_numbers_are_per_line(self):
        found = ip.candidates("`AGENTS.md`\n\nтекст\n\n`docs/voice.md`\n")
        self.assertEqual([c.line_no for c in found], [1, 5])

    def test_repeated_path_is_judged_once(self):
        found = ip.candidates("`AGENTS.md` і ще раз `AGENTS.md`")
        self.assertEqual([c.path for c in found], ["AGENTS.md"])

    def test_commands_are_not_paths(self):
        body = "`gh issue view <number> --comments` і `git grep -l X origin/main`"
        self.assertEqual(ip.candidates(body), [])

    def test_gradle_tasks_are_not_paths(self):
        body = "`./gradlew :app:lintDebug` і `:app:testPureJvm`"
        self.assertEqual(ip.candidates(body), [])

    def test_git_refs_are_not_paths(self):
        body = "база `origin/main`, гілка `refs/heads/main`, `upstream/main`"
        self.assertEqual(ip.candidates(body), [])

    def test_git_internals_are_not_paths(self):
        # Стан операції, а не вміст репозиторію: у `main` їх немає й бути не
        # мусить, тож премисою про наявний файл вони не є (#1247).
        body = "`rebase-merge`, `rebase-apply`, `.git/rebase-merge`, `MERGE_HEAD`"
        self.assertEqual(ip.candidates(body), [])

    def test_slash_shorthand_is_not_a_path(self):
        # `errorLine1/2` — пара імен, не каталог (#1222). Друга половина без
        # літер і без крапки; `values-en/pl` так не виглядає і лишається
        # кандидатом.
        self.assertEqual(ip.candidates("поля `errorLine1/2`"), [])
        self.assertEqual(ip.candidates("каталог `values-en/pl`")[0].path, "values-en/pl")

    def test_branch_names_are_not_paths(self):
        # Справжні назви гілок із тіла #1106. Гілка не мусить бути в `main` —
        # валити за неї означало б валити тікет за назву смуги роботи.
        body = (
            "`release/v1.3.9`, `release/v1.3.10`, `spec-53/wave-1`, "
            "`fix/purge-4read-legacy`, `chore/device-verification`, "
            "`backup/large-screens-900-prerebase`, `feat/ui-polish`"
        )
        self.assertEqual(ip.candidates(body), [])

    def test_docs_paths_are_not_mistaken_for_branches(self):
        # Ціна голови `docs` у списку гілок: `docs/voice.md` і `docs/adr` —
        # справжні шляхи. Перевірка саме на них, а не на формі гілки.
        self.assertEqual(
            [c.path for c in ip.candidates("`docs/voice.md` і `docs/adr`")],
            ["docs/voice.md", "docs/adr"],
        )
        self.assertFalse(ip._is_branch_name("docs/voice.md"))
        self.assertFalse(ip._is_branch_name("docs/adr"))

    def test_paths_under_scripts_and_app_never_depend_on_branch_heads(self):
        # `_is_branch_name` дивиться лише на `segments[0]`: голови цих шляхів —
        # `scripts` і `app`, тож від списку голів вони не залежали ніколи
        # (огляд PR #1268 поправив саме це моє хибне обґрунтування).
        self.assertEqual(
            [c.path for c in ip.candidates("`scripts/tests/test_x.py` і `app/src/test/java/A.kt`")],
            ["scripts/tests/test_x.py", "app/src/test/java/A.kt"],
        )

    def test_test_head_is_a_branch_not_a_path(self):
        # `test/…` — реальна голова гілок цього репозиторію
        # (`refs/heads/test/issue-703-hidden-boundaries`). Прибирання `test` зі
        # списку коштувало б хибної відмови, і це піниться **вердиктом**, а не
        # самою лише `_is_branch_name`: саме так зміну можна зламати непомітно.
        self.assertTrue(ip._is_branch_name("test/issue-703-hidden-boundaries"))
        self.assertTrue(ip._is_branch_name("tests/wave-1/step-2"))
        code, verdicts, _ = judge_body(
            "гілка `test/issue-703-hidden-boundaries`, крок `tests/wave-1/step-2`", set()
        )
        self.assertEqual(code, 2)
        self.assertEqual({v.code for v in verdicts}, {2})

    def test_no_branch_head_shadows_a_real_top_level_entry(self):
        # Справжній інваріант списку: голова, яка є записом верхнього рівня,
        # проковтнула б усі шляхи під собою. `docs` прибрано саме тому (вимір:
        # 83 шляхи в 72 тікетах), і цей тест не дає повернути голову, яка
        # затьмарює реальну теку.
        #
        # Герметично: репозиторій будується тут, із тими самими верхніми
        # записами, що в справжньому дереві. Читати справжнє дерево не можна:
        # `verdict_field_mutants` ганяє цей модуль свідком у копії **без
        # `.git`**, і тест, який там падав, робив червоним набір-свідок — а
        # червоний свідок БЕЗ мутації механізм читає як «зеленого мутанта тут
        # не відрізнити від зламаного набору» і відмовляє на всьому прогоні.
        # Саме так цей тест і поклав `test_verdict_field_mutants`.
        base = Path(tempfile.mkdtemp(prefix="issue-premise-shadow-"))
        self.addCleanup(shutil.rmtree, base, True)
        repo = build_repo(base, {name: "x\n" for name in TOP_LEVEL_ENTRIES})
        shadowing = ip.BRANCH_HEADS & ip._top_level_entries("origin/main", repo)
        self.assertEqual(shadowing, frozenset())

    def test_the_real_repository_is_free_of_shadowing(self):
        # Другий бік того самого: у **справжньому** дереві інваріант теж мусить
        # триматися. Тут дерево читається з `HEAD` — того чекаута, у якому тест
        # біжить; у CI `HEAD` є завжди, тож скіпу немає. Порожній `ls-tree`
        # означає, що це копія без git (свідок у `verdict_field_mutants`) —
        # тоді інваріант не перевіряється, і це названо вголос, а не видано за
        # зелене.
        if not TOP_LEVEL_IN_TREE:
            self.skipTest("копія без .git — справжнє дерево тут не читається")
        self.assertEqual(ip.BRANCH_HEADS & TOP_LEVEL_IN_TREE, frozenset())

    def test_measure_head_exists_because_the_docs_cite_it(self):
        # Доки посилаються на `--measure-head` як на джерело чисел. Команда,
        # названа в документі, мусить існувати: одного разу вона вже була
        # вигадана (огляд PR #1268), і це найдорожчий різновид неправди —
        # твердження, яке неможливо виконати.
        #
        # Сторож ВИКЛИКАЄ команду, а не читає `--help`. Підрядок
        # `--measure-head` у help — свідок непрямий: він лишався там із help
        # опції `--scan-limit` («скільки тікетів брати для `--measure-head`»),
        # тож вирізаний блок `source.add_argument("--measure-head", …)` тест не
        # ловив — зелений на зламаному дереві. `argparse` на невідомому прапорці
        # дає рівно 2 і друкує підказку в stderr; саме це тут і перевіряється
        # самою командою.
        base = Path(tempfile.mkdtemp(prefix="issue-premise-measure-"))
        self.addCleanup(shutil.rmtree, base, True)
        repo = build_repo(
            base, {"AGENTS.md": "# Agent instructions\n", "docs/voice.md": "voice\n"}
        )
        stub = base / "bin"
        stub.mkdir()
        gh = stub / "gh"
        gh.write_text(GH_STUB, encoding="utf-8")
        gh.chmod(0o755)
        # Оточення — своє, а не успадковане (#1265): раннер приносить експортовані
        # функції (`BASH_FUNC_printf%%`), а `/bin/sh` підставного `gh` — це bash,
        # який їх імпортує; підставний тоді не друкує списку, і ґард бачить
        # «не вдалося перелічити issue» при незмінному файлі. Спадок `os.environ`
        # тут був рівно цим; `PATH` підставного і `HOME` для `git` — усе, що
        # потрібно самому `issue_premise.py`.
        environment = {
            "PATH": os.pathsep.join([str(stub), "/usr/bin:/bin"]),
            "HOME": str(base),
            "LC_ALL": "C",
        }

        proc = subprocess.run(
            [
                sys.executable, str(REPO_ROOT / "scripts" / "issue_premise.py"),
                "--measure-head", "docs", "--scan-limit", "2",
                "--ref", "origin/main", "--repo", str(repo),
            ],
            capture_output=True, text=True, check=False, env=environment,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("голова `docs`", proc.stdout)
        self.assertIn("шляхів у тілах: 2", proc.stdout)
        self.assertIn("верхня тека `docs` у дереві: є", proc.stdout)

    def test_measure_head_counts_paths_with_that_head(self):
        # Сам вимір — без `gh`: підставлений список issue і підставлені тіла.
        # Голова `docs` у тілах є, верхньої теки немає — саме те, що вирішує,
        # чи прибирати голову зі списку.
        bodies = {
            "1": "читай `docs/voice.md` і `docs/adr`",
            "2": "читай `docs/plans/x.md`, `app/src/main/A.kt`",
            # Третє тіло лише мусить мати голову, відмінну від `docs`. Воно НЕ
            # називає скрипт мутацій — файл, з якого складається інструмент
            # вердикту: та згадка робила цей модуль свідком для його сайтів у
            # `verdict_field_mutants` — марним (модуль того скрипта не запускає,
            # тож мутанта не ловить) і дорогим (зайвий прогін на кожен сайт).
            # Свідків шукають саме за іменем файлу, тож імені тут бути не
            # повинно. Знахідка огляду PR #1268.
            "3": "читай `AGENTS.md`",
        }

        def fake_run(args, **kwargs):
            if args[:2] == ["gh", "issue"] and args[2] == "list":
                return subprocess.CompletedProcess(args, 0, "1\n2\n3\n", "")
            number = args[3] if len(args) > 3 else ""
            return subprocess.CompletedProcess(args, 0, bodies.get(number, ""), "")

        with mock.patch.object(ip, "resolve_main_ref", return_value=(True, "0" * 40)), \
                mock.patch.object(ip, "_top_level_entries", lambda ref, repo: frozenset({"app"})), \
                mock.patch.object(ip.subprocess, "run", fake_run):
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                code = ip.measure_head("docs", 3, "origin/main")
        self.assertEqual(code, 0)
        output = printed.getvalue()
        self.assertIn("шляхів у тілах: 3", output)
        self.assertIn("тікетів: 2", output)
        self.assertIn("верхня тека `docs` у дереві: немає", output)

    def test_branch_head_with_a_path_like_tail_is_not_a_branch(self):
        # `docs/voice.md` — файл: крапка в останньому сегменті знімає назву
        # гілки навіть якби голова була в списку. Саме тому ціна голови
        # `docs` — не про такі шляхи, а про теки (`docs/adr`, `docs/agents`).
        self.assertFalse(ip._is_branch_name("docs/voice.md"))
        with mock.patch.object(ip, "BRANCH_HEADS", ip.BRANCH_HEADS | {"docs"}):
            self.assertTrue(ip._is_branch_name("docs/adr"))

    def test_device_paths_are_not_paths(self):
        # `filesDir/local_imports` — тека в застосунку, не в репозиторії
        # (#1163, #1185).
        body = "тека `filesDir/local_imports`, кеш `cacheDir/x`"
        self.assertEqual(ip.candidates(body), [])

    def test_ellipsis_segment_is_not_a_path(self):
        # `app/src/androidTest/...` — «і далі», а не каталог (#764).
        self.assertEqual(ip.candidates("теки `app/src/androidTest/...`"), [])

    def test_bare_extension_is_not_a_path(self):
        # `.png` і `.html` — формат вкладення, не ім'я файлу (#991, #1029).
        self.assertEqual(ip.candidates("вкладення `.png` і `.html`"), [])

    def test_attachment_names_are_not_refused(self):
        # Справжні імена вкладень із #991 і #962: файли існують на машині, у
        # `main` їх немає навмисно. Голе ім'я вкладення — не премиса про дерево.
        code, verdicts, _ = judge_body(
            "скріншоти `live-book-2f-screencap.png`, `crop-2f-live-pill.png`, `18-person.png`",
            {"AGENTS.md"},
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})
        self.assertIn("вкладення", verdicts[0].detail)

    def test_named_path_with_attachment_extension_is_still_checked(self):
        # Лише голе ім'я: названий шлях перевіряється як усі.
        code, _, _ = judge_body("`docs/screens/x.png`", {"AGENTS.md"})
        self.assertEqual(code, 1)

    def test_attachment_name_present_in_main_still_passes(self):
        # Якщо таке ім'я в дереві **є**, воно проходить як звичайний шлях.
        code, verdicts, _ = judge_body("`icon.png`", {"app/src/main/res/icon.png"})
        self.assertEqual(code, 0)
        self.assertIn("icon.png", verdicts[0].detail)

    def test_ignored_paths_are_dropped(self):
        # `.mutations` у `main` немає **навмисно** — такі каталоги живе лише на
        # машині, і «немає в main» про них не є відмовою.
        with mock.patch.object(ip, "_is_ignored", lambda path, repo=None: path in {".mutations", "build"}):
            self.assertEqual(ip.candidates("стан у `.mutations` і в `build`"), [])

    def test_ignore_lookup_is_asked_only_about_surviving_paths(self):
        # `git check-ignore` — процес; платити за нього на кожен інтервал у
        # бектиках означало б платити за прозу.
        asked = []

        def fake_ignored(path, repo=None):
            asked.append(path)
            return False

        with mock.patch.object(ip, "_is_ignored", fake_ignored):
            ip.candidates("`detached HEAD`, `gh issue view 1`, `AGENTS.md`")
        self.assertEqual(asked, ["AGENTS.md"])

    def test_new_marked_path_skips_the_ignore_lookup(self):
        with mock.patch.object(ip, "_is_ignored", lambda path, repo=None: True):
            self.assertEqual([c.path for c in ip.candidates("`NEW:docs/x.md`")], ["docs/x.md"])

    def test_ignore_question_goes_to_the_repository_gitignore(self):
        calls = []

        def fake_git(args, repo=None):
            calls.append(args)
            return 0, "", ""

        with mock.patch.object(ip, "git_output", fake_git):
            self.assertTrue(ip._is_ignored(".mutations", REPO_ROOT))
        self.assertEqual(calls[0][:4], ["check-ignore", "--no-index", "-q", "--"])
        self.assertEqual(calls[0][4], ".mutations")

    def test_ignore_failure_means_not_ignored(self):
        # Запобіжник від шуму не мусить сам ставати відмовою: помилка git
        # означає «не ігнорується», і шлях перевіряється як усі.
        with mock.patch.object(ip, "git_output", lambda args, repo=None: (128, "", "boom")):
            self.assertFalse(ip._is_ignored(".mutations", REPO_ROOT))

    def test_prose_fragments_are_not_paths(self):
        body = "`detached HEAD`, `head-name`, `case`-arms, `\"errors\": \"0\"`, `|`"
        self.assertEqual(ip.candidates(body), [])

    def test_globs_and_placeholders_are_not_paths(self):
        body = "`app/src/main/res/values*/strings.xml`, `scripts/tests/test_*.py`, `<path>`"
        self.assertEqual(ip.candidates(body), [])

    def test_directory_with_trailing_slash_is_checked_as_tree(self):
        found = ip.candidates("шукай у `docs/adr/` і в `docs/agents/`")
        self.assertEqual([c.path for c in found], ["docs/adr", "docs/agents"])

    def test_new_marker_is_read_inside_the_span(self):
        found = ip.candidates("`NEW:docs/agents/premise.md` з'явиться")
        self.assertEqual([(c.path, c.marked_new) for c in found], [("docs/agents/premise.md", True)])

    def test_new_marker_outside_the_span_is_not_read(self):
        # Позначка поза інтервалом не діє: вона мусить бути невідривною від шляху.
        found = ip.candidates("NEW: `docs/agents/premise.md` з'явиться")
        self.assertEqual([(c.path, c.marked_new) for c in found], [("docs/agents/premise.md", False)])


class JudgeTest(unittest.TestCase):
    def test_missing_path_without_marker_is_refused(self):
        code, verdicts, _ = judge_body("читай `CODING_STANDARDS.md`", {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertEqual(verdicts[0].candidate.path, "CODING_STANDARDS.md")
        self.assertIn("премиси немає", verdicts[0].detail)
        self.assertIn("`NEW:CODING_STANDARDS.md`", verdicts[0].detail)

    def test_existing_path_passes(self):
        code, verdicts, _ = judge_body("читай `AGENTS.md`", {"AGENTS.md"})
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].code, 0)

    def test_bare_basename_resolves_to_the_one_file_in_main(self):
        # `ci.yml` у тілі — це `.github/workflows/ci.yml` у дереві; валити його
        # означало б шум, який швидко вимкнуть.
        code, verdicts, tree = judge_body("не чіпати `ci.yml`", {".github/workflows/ci.yml"})
        self.assertEqual(code, 0)
        self.assertIn(".github/workflows/ci.yml", verdicts[0].detail)
        self.assertIn("suffix:ci.yml", tree.calls)

    def test_suffix_shorthand_resolves_to_the_one_file_in_main(self):
        # Тікет #1223 назвав файл як `ui/adaptive/WindowLayout.kt`, а в дереві
        # він лежить під `app/src/main/java/com/slukhayka/audiobooks/`. Хвіст
        # на межі сегмента — та сама коротка форма, що `ci.yml`.
        code, verdicts, _ = judge_body(
            "число живе в `ui/adaptive/WindowLayout.kt`",
            {"app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt"},
        )
        self.assertEqual(code, 0)
        self.assertIn("app/src/main/java/", verdicts[0].detail)

    def test_suffix_shorthand_does_not_match_across_segments(self):
        # `adaptive/WindowLayout.kt` — хвіст на межі сегмента; `aptive/…` — ні.
        code, _, _ = judge_body("`aptive/WindowLayout.kt`", {"ui/adaptive/WindowLayout.kt"})
        self.assertEqual(code, 1)

    def test_exact_path_wins_over_a_suffix_match(self):
        code, verdicts, _ = judge_body(
            "`docs/voice.md`", {"docs/voice.md", "vendor/docs/voice.md"}
        )
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].detail, "є в origin/main")

    def test_marked_new_path_that_main_lacks_passes(self):
        code, verdicts, _ = judge_body("`NEW:docs/agents/premise.md`", {"AGENTS.md"})
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].code, 0)

    def test_marked_new_path_that_main_already_has_is_refused(self):
        # Друге плече тієї самої перевірки: позначка «новий» на наявний файл —
        # теж неправда про `main`, і тим самим спосіб промовчати закритий.
        code, verdicts, _ = judge_body("`NEW:AGENTS.md`", {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertIn("уже є", verdicts[0].detail)

    def test_bare_name_that_main_lacks_is_refused(self):
        code, verdicts, _ = judge_body("читай `CODING_STANDARDS.md`", {"docs/other.md"})
        self.assertEqual(code, 1)
        self.assertIn("ні за повним шляхом, ні за хвостом", verdicts[0].detail)

    def test_ambiguous_bare_name_is_unknown_not_refusal(self):
        code, verdicts, _ = judge_body(
            "кличе `tool.sh`", {"scripts/tool.sh", "fixtures/a/scripts/tool.sh"}
        )
        self.assertEqual(code, 2)
        self.assertIn("неоднозначне", verdicts[0].detail)

    def test_ambiguous_bare_name_marked_new_is_refused(self):
        code, _, _ = judge_body("`NEW:tool.sh`", {"scripts/tool.sh"})
        self.assertEqual(code, 1)

    def test_one_refusal_decides_the_whole_ticket(self):
        body = "`AGENTS.md` є, а `CODING_STANDARDS.md` немає"
        code, verdicts, _ = judge_body(body, {"AGENTS.md"})
        self.assertEqual(code, 1)
        self.assertEqual([v.code for v in verdicts], [0, 1])

    def test_body_without_any_path_is_unknown_not_green(self):
        code, verdicts, _ = judge_body("`detached HEAD` і `head-name`", {"AGENTS.md"})
        self.assertEqual(code, 2)
        self.assertIn("перевіряти нічого", verdicts[0].detail)

    def test_directory_path_is_judged_as_tree(self):
        code, _, tree = judge_body("шукай у `docs/agents/`", {"docs/agents/issue-tracker.md"})
        self.assertEqual(code, 1)
        self.assertIn("docs/agents", tree.calls)


class TreeEvidenceTest(unittest.TestCase):
    """#1281: коротка форма, що знайшла ТЕКУ, не доводить «той самий файл».

    Твердження «одне входження = той самий файл» істинне для блоба й хибне для
    дерева: односегментне ім'я без розширення (`main`, `tab_settings`) майже
    завжди є тегом, символом чи ключем, а тека з таким ім'ям на довільній
    глибині — збіг імені, не доказ. Правило вузьке навмисно: воно не чіпає ні
    точний шлях, ні теку, знайдену за хвостом БАГАТОСЕГМЕНТНОГО імені.
    """

    def test_a_bare_name_that_matches_only_a_tree_is_ambiguous(self):
        # Живий приклад тікета: #982 пише «тега `tab_settings` у `main/` немає»,
        # а ґард знаходив `app/src/main` — теку на довільній глибині — і казав
        # «премиса є». Хибний бік був безпечний (пропуск, не відмова), але
        # твердження «це той самий файл» про теку хибне.
        code, verdicts, tree = judge_body(
            "тега `tab_settings` у `main/` немає",
            {"app/src/main", "app/src/main/AndroidManifest.xml"},
            trees={"app/src/main"},
        )
        self.assertEqual(code, 2)
        self.assertEqual(verdicts[0].candidate.path, "main")
        self.assertEqual(verdicts[0].code, 2)
        self.assertIn("неоднозначне", verdicts[0].detail)
        self.assertIn("app/src/main", verdicts[0].detail)
        self.assertIn("suffix:main", tree.calls)

    def test_the_982_body_is_ambiguous_where_it_used_to_say_the_premise_exists(self):
        # Той самий вхід, що в шапці механізму: справжнє тіло #982 і дерево, у
        # якому `main` — тека `app/src/main`. Прогін по справжньому #982 —
        # `--issue 982` — дає той самий вердикт.
        code, verdicts, _ = judge_body(ISSUE_982_BODY, TREE_OF_982, trees=TREE_OF_982)
        self.assertEqual(code, 2)
        self.assertEqual([v.candidate.path for v in verdicts], ["main"])
        self.assertIn("неоднозначне", verdicts[0].detail)
        self.assertIn("app/src/main", verdicts[0].detail)

    def test_a_bare_name_that_matches_one_blob_still_passes(self):
        # Те саме односегментне ім'я без розширення, але знайдений **блоб**:
        # «одне входження = той самий файл» — саме те, що коротка форма й
        # обіцяє, і правило про теку цього не відбирає.
        code, verdicts, _ = judge_body("клич `setup-env/`", {"scripts/setup-env"})
        self.assertEqual(code, 0)
        self.assertIn("scripts/setup-env", verdicts[0].detail)

    def test_a_multi_segment_tree_found_by_suffix_still_passes(self):
        # Свідоме рішення огляду PR #1268 лишається: тека, знайдена за хвостом
        # багатосегментного імені (`ui/components`), — премиса, а не
        # неоднозначність. Звузити до неї правило й означало б зламати те, що
        # вже працює.
        code, verdicts, _ = judge_body(
            "тека `ui/components` тут",
            {"app/src/main/java/com/slukhayka/audiobooks/ui/components"},
            trees={"app/src/main/java/com/slukhayka/audiobooks/ui/components"},
        )
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].code, 0)
        self.assertIn("(тека)", verdicts[0].detail)

    def test_an_exact_tree_path_is_still_the_premise(self):
        # Точний шлях питається першим і короткої форми не потребує: тікет, який
        # назвав теку повним шляхом, премису має — і вердикт це каже без
        # здогадів. Межа правила проходить тут, і проходить явно.
        code, verdicts, _ = judge_body(
            "тека `main/` у корені", {"main", "main/app.kt"}, trees={"main"}
        )
        self.assertEqual(code, 0)
        self.assertEqual(verdicts[0].detail, "є в origin/main")

    def test_a_bare_name_marked_new_that_matches_a_tree_is_still_refused(self):
        # Друге плече позначки не слабшає: `NEW:` на ім'я, яке вже є текою в
        # `main`, лишається відмовою (код 1), а не стає невідомістю. Це межа
        # правила, названа в коді: питається «чи є щось із таким ім'ям», а не
        # «чи це той самий файл».
        code, verdicts, _ = judge_body(
            "`NEW:main/` створить теку", {"app/src/main"}, trees={"app/src/main"}
        )
        self.assertEqual(code, 1)
        self.assertIn("уже є", verdicts[0].detail)

    def test_the_two_kinds_are_named_in_the_verdict(self):
        # «Одне входження» для блоба й для теки — різні твердження, тож вердикт
        # мусить називати, ЩО саме знайдено. Інакше оглядач не відрізнить
        # премису-файл від премиси-теки, а саме на цьому спіткнувся #982.
        blob_code, blob_verdicts, _ = judge_body("клич `ci.yml`", {".github/workflows/ci.yml"})
        tree_code, tree_verdicts, _ = judge_body(
            "клич `ui/components`", {"web/src/ui/components"}, trees={"web/src/ui/components"}
        )
        self.assertEqual((blob_code, tree_code), (0, 0))
        self.assertNotIn("(тека)", blob_verdicts[0].detail)
        self.assertIn("web/src/ui/components (тека)", tree_verdicts[0].detail)

    def test_the_same_trailing_slash_is_premise_at_the_root_and_ambiguous_deep(self):
        # #1301: форма та сама — `docs/` і `drawable/` — а вердикт різний.
        # Дискримінатор не глибина, а ТОЧНИЙ запис дерева: `docs` знаходить
        # `main_has_path`, `drawable` — ні, і коротка форма лишає код 2. Звідси
        # й доказ, що «прибрати асиметрію» формою неможливо: те саме розширення
        # робить премисою й `main/` із #982, тіло якого пише його З ТИМ САМИМ
        # хвостовим слешем.
        root_code, root_verdicts, _ = judge_body(
            "тека `docs/` у корені", {"docs", "docs/voice.md"}, trees={"docs"}
        )
        self.assertEqual(root_code, 0)
        self.assertEqual(root_verdicts[0].detail, "є в origin/main")

        deep_code, deep_verdicts, _ = judge_body(
            "тека `drawable/` тут",
            {"app/src/main/res/drawable", "app/src/main/res/drawable/img.jpg"},
            trees={"app/src/main/res/drawable"},
        )
        self.assertEqual(deep_code, 2)
        self.assertIn("app/src/main/res/drawable", deep_verdicts[0].detail)

        self.assertIn("`main/`", ISSUE_982_BODY)
        code, _, _ = judge_body(ISSUE_982_BODY, TREE_OF_982, trees=TREE_OF_982)
        self.assertEqual(code, 2)

        # Розширення, яке «прибирає» асиметрію, відкриває #982 назад: без
        # правила про теку те саме тіло дає 0 — тобто хибну премису там, де
        # тікет казав про тег, а не про теку.
        with mock.patch.object(ip, "_is_bare_name", lambda _path: False):
            widened, widened_verdicts, _ = judge_body(
                ISSUE_982_BODY, TREE_OF_982, trees=TREE_OF_982
            )
        self.assertEqual(widened, 0)
        self.assertIn("app/src/main", widened_verdicts[0].detail)


class BareTreeMeasureTest(unittest.TestCase):
    """Ціна правила про теку (#1301) — числом, яке виводить сам механізм.

    Клас — не «теки взагалі», а рівно те, що `judge` переносить із 0 у 2:
    односегментне ім'я без розширення, не кореневе, чия коротка форма дає рівно
    один збіг, і це ДЕРЕВО. Тест жене документовану команду на ВЛАСНОМУ
    репозиторії (у CI `origin/main` не існує — раннер фетчить merge-ref, тож
    чекаут тут питати не можна) і звіряє її вивід із вердиктом `check_body`:
    вимір і вердикт мусять міряти те саме правило, а не два схожі.
    """

    def test_the_documented_command_counts_the_same_class_the_verdict_makes_ambiguous(self):
        base = Path(tempfile.mkdtemp(prefix="issue-premise-bare-trees-"))
        self.addCleanup(shutil.rmtree, base, True)
        repo = build_repo(
            base,
            {
                "docs/voice.md": "voice\n",
                "assets/drawable/img.jpg": "jpg\n",
                "libs/twins/a.kt": "a\n",
                "web/twins/b.kt": "b\n",
                "scripts/marker": "блоб, не тека\n",
                "notes.txt": "файл\n",
            },
        )
        proc = subprocess.run(
            [
                sys.executable, str(REPO_ROOT / "scripts" / "issue_premise.py"),
                "--measure-bare-trees", "--ref", "origin/main", "--repo", str(repo),
            ],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        printed = [
            line.strip() for line in proc.stdout.splitlines() if line.startswith("    ")
        ]
        # Рівно те, що вердикт робить неоднозначним: глибока тека `drawable` —
        # так; кореневе `docs` — ні (точний шлях знаходить його першим);
        # `twins` — ні (два збіги, воно було неоднозначним і до правила);
        # `marker` — ні (блоб, а не дерево); `notes.txt` — ні (розширення).
        self.assertEqual(printed, ["drawable"])
        # Ті самі предикати, інші фільтри — і режим називає числа сам, а не лише
        # ціну: `twins` входить у «будь-який збіг-дерево», кореневі (`assets`,
        # `docs`, `libs`, `scripts`, `web`) — у «разом із кореневими», і саме їх
        # п'ять і є ціною симетрії вниз.
        for line in (
            "єдина коротка форма — дерево): 1",
            "без крапки в імені, не кореневі, єдина коротка форма — дерево: 1",
            "разом із кореневими, єдина коротка форма — дерево: 6",
            "без крапки в імені, разом із кореневими, єдина коротка форма — дерево: 6",
            "не кореневі, будь-який збіг — дерево: 2",
            "без крапки в імені, не кореневі, будь-який збіг — дерево: 2",
            "стільки імен перейде 0 → 2: 5",
        ):
            self.assertIn(line, proc.stdout)

        for name in printed:
            # Слеш обов'язковий: голе ім'я без розширення кандидатом не стає
            # взагалі (`_is_path_like`), тож у тілі тікета така тека пишеться
            # саме як `drawable/`.
            code, verdicts = ip.check_body(f"тека `{name}/`", "origin/main", repo)
            self.assertEqual(code, 2)
            self.assertIn("неоднозначне", verdicts[0].detail)
        # Кореневий кінець не платить — і це не «симетрія», а точний запис.
        root_code, root_verdicts = ip.check_body("тека `docs/`", "origin/main", repo)
        self.assertEqual(root_code, 0)
        self.assertEqual(root_verdicts[0].detail, "є в origin/main")

        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "nope")):
            self.assertEqual(ip.measure_bare_trees("origin/main"), 2)


class PriceRecordTest(unittest.TestCase):
    """Реєстр ціни (#1308): числа шапок — дані, і їх відтворює названа команда.

    Гейт (`doc_numbers.py`) читає РЕЄСТР `PRICES`, а не git: копія дерева, у
    якій біжить кожен сторож, `.git` не містить, а в CI чекаут не має
    `origin/main` (раннер фетчить merge-ref) — джерело, яке кличе git, дало б
    там нуль і почервонило б усе, що на нього спирається. Тому правдивість
    реєстру доводить прогоном ЦЕЙ тест — там, де ref читається. Де не читається,
    він каже це вголос, а не видає неперевірене за зелене.
    """

    def test_the_recorded_prices_are_what_the_documented_command_prints(self):
        ok, _ = ip.resolve_main_ref(ip.MAIN_REF, REPO_ROOT)
        if not ok:
            self.skipTest("ref %s не читається — реєстр тут не перевірено" % ip.MAIN_REF)
        proc = subprocess.run(
            [
                sys.executable, str(REPO_ROOT / "scripts" / "issue_premise.py"),
                "--measure-bare-trees",
                "--ref", ip.MAIN_REF, "--repo", str(REPO_ROOT),
            ],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, proc.returncode, proc.stderr)
        printed = {}
        for line in proc.stdout.splitlines():
            entry = line.strip()
            if not entry.startswith("— "):
                continue
            key, _, value = entry[2:].rpartition(": ")
            printed[key.strip("«»")] = int(value)
        self.assertEqual(
            dict(ip.PRICES), printed,
            "шапки звіряються з реєстром, а реєстр — із прогоном команди",
        )

    def test_the_record_names_the_revision_it_was_measured_on(self):
        """Реєстр без ревізії — число, якому нема з чим звіритися."""
        self.assertTrue(ip.PRICE_REF, "реєстр мусить називати ревізію")
        self.assertTrue(ip.PRICE_DATE, "реєстр мусить називати дату")
        self.assertIn("--measure-bare-trees", ip.PRICE_COMMAND)

    def test_the_record_is_about_the_main_ref_the_guard_judges(self):
        """Реєстр — про той самий ref, який судить ґард: інакше він не про те."""
        self.assertEqual(ip.MAIN_REF, "origin/main")

    def test_a_class_without_a_record_is_an_error_not_a_silent_truncation(self):
        """Реєстр і вимір звіряються по позиції — і розійтися за довжиною не сміють."""
        with mock.patch.object(ip, "PRICES", ip.PRICES[:-1]):
            with self.assertRaises(ValueError):
                ip.measured_prices(ip.MAIN_REF)


class RealIssueTest(unittest.TestCase):
    """Обидва прогони вимоги 1 — на справжньому тілі #1261 і на валідній премисі."""

    def test_refuses_the_1261_body(self):
        code, verdicts, _ = judge_body(ISSUE_1261_BODY, {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md"})
        self.assertEqual(code, 1)
        refused = [v for v in verdicts if v.code == 1]
        self.assertEqual([v.candidate.path for v in refused], ["CODING_STANDARDS.md"])

    def test_passes_a_body_whose_premise_is_in_main(self):
        code, verdicts, _ = judge_body(
            VALID_PREMISE_BODY,
            {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md", "docs/voice.md"},
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})

    def test_the_two_bodies_differ_only_in_their_premise(self):
        # Той самий ґард, той самий ref — різний вердикт. Це і є доказ, що
        # рішення ухвалює дерево `main`, а не форма тіла.
        tree = {"AGENTS.md", "CONTRIBUTING.md", "docs/agents/work-coordination.md", "docs/voice.md"}
        refused, _, _ = judge_body(ISSUE_1261_BODY, tree)
        passed, _, _ = judge_body(VALID_PREMISE_BODY, tree)
        self.assertEqual(refused, 1)
        self.assertEqual(passed, 0)


class MainRefTest(unittest.TestCase):
    def test_unreadable_ref_is_unknown_not_a_pass(self):
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            code, verdicts = ip.check_body("`AGENTS.md`", "origin/main")
        self.assertEqual(code, 2)
        self.assertIn("git fetch origin main", verdicts[0].detail)

    def test_unreadable_local_ref_does_not_advise_fetching(self):
        # `git fetch origin main` лікує лише віддалений ref; для локального це
        # був би шум (NIT огляду PR #1268).
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            code, verdicts = ip.check_body("`AGENTS.md`", "my-local-ref")
        self.assertEqual(code, 2)
        self.assertIn("`my-local-ref` не читається", verdicts[0].detail)
        self.assertNotIn("git fetch", verdicts[0].detail)

    def test_scan_refuses_to_measure_an_unreadable_ref(self):
        # Числа шуму — не твердження: `--scan` міряє той самий ref, про який
        # ухвалює вердикт, і не міряє нічого, якщо ref не читається.
        with mock.patch.object(ip, "resolve_main_ref", return_value=(False, "not found")):
            self.assertEqual(ip.scan(5, "origin/main"), 2)

    def test_default_ref_is_origin_main(self):
        parser = ip.argparse.ArgumentParser()
        self.assertEqual(ip.MAIN_REF, "origin/main")

    def test_suffix_lookup_uses_ls_tree_of_the_ref(self):
        calls = []

        def fake_git(args, repo=None):
            calls.append(args)
            return 0, "100644 blob aaa\ta/b/ci.yml\n100644 blob bbb\tc/ci.yml\n", ""

        with mock.patch.object(ip, "git_output", fake_git):
            matches = ip.main_matches_suffix("origin/main", "ci.yml")
        self.assertEqual(
            [(match.path, match.kind) for match in matches],
            [("a/b/ci.yml", "blob"), ("c/ci.yml", "blob")],
        )
        # `-t` обов'язковий: без нього каталог у `main` не знаходився ніколи
        # (знахідка огляду PR #1268). `--name-only` прибрано навмисно: він
        # відкидає тип, а без типу вердикт не відрізняє блоб від дерева (#1281).
        self.assertEqual(calls[0][:3], ["ls-tree", "-r", "-t"])
        self.assertNotIn("--name-only", calls[0])
        self.assertEqual(calls[0][3], "origin/main")

    def test_the_suffix_lookup_reads_the_object_type(self):
        # Тип береться з поля об'єкта (`<mode> <type> <sha>\t<path>`), а не
        # вгадується з імені: тека `ci.yml` — теж тека, хоч і виглядає файлом.
        def fake_git(args, repo=None):
            return 0, ("100644 blob aaa\tsrc/ci.yml\n"
                       "040000 tree bbb\tfixtures/ci.yml\n"), ""

        with mock.patch.object(ip, "git_output", fake_git):
            matches = ip.main_matches_suffix("origin/main", "ci.yml")
        self.assertEqual(
            [(match.path, match.kind) for match in matches],
            [("fixtures/ci.yml", "tree"), ("src/ci.yml", "blob")],
        )

    def test_a_ls_tree_line_without_a_type_is_not_a_match(self):
        # Незрозумілий рядок не сміє вдати блоб: «премиса є» з нього було б
        # тихіше за відмову. Рядок, якого механізм не розібрав, не стає збігом
        # — тож вердикт іде в бік відмови, а не в бік пропуску.
        def fake_git(args, repo=None):
            return 0, "a/b/ci.yml\n", ""

        with mock.patch.object(ip, "git_output", fake_git):
            self.assertEqual(ip.main_matches_suffix("origin/main", "ci.yml"), [])

    def test_suffix_lookup_sees_directories_not_only_blobs(self):
        # Справжній приклад: `ui/components` у дереві є, але лише як теки.
        code, verdicts, _ = judge_body(
            "`ui/components` і `ui/adaptive`",
            {
                "app/src/main/java/com/slukhayka/audiobooks/ui/components",
                "app/src/main/java/com/slukhayka/audiobooks/ui/adaptive",
            },
            trees={
                "app/src/main/java/com/slukhayka/audiobooks/ui/components",
                "app/src/main/java/com/slukhayka/audiobooks/ui/adaptive",
            },
        )
        self.assertEqual(code, 0)
        self.assertEqual({v.code for v in verdicts}, {0})


class CliTest(unittest.TestCase):
    def test_body_file_dash_reads_stdin(self):
        with mock.patch.object(sys, "stdin", io.StringIO("читай `AGENTS.md`")):
            body = ip.read_body(ip.argparse.Namespace(body_file="-", issue=None))
        self.assertEqual(body, "читай `AGENTS.md`")

    def test_quiet_prints_only_non_zero_verdicts(self):
        code, verdicts, _ = judge_body("`AGENTS.md` і `CODING_STANDARDS.md`", {"AGENTS.md"})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            for verdict in verdicts:
                if verdict.code != 0:
                    print(f"[{verdict.code}] {verdict.display()} — {verdict.detail}")
        self.assertIn("CODING_STANDARDS.md", out.getvalue())
        self.assertNotIn("`AGENTS.md`", out.getvalue())
        self.assertEqual(code, 1)

    def test_report_names_the_ref_and_counts(self):
        _, verdicts, _ = judge_body("`AGENTS.md` і `CODING_STANDARDS.md`", {"AGENTS.md"})
        report = ip.format_report("origin/main", 1, verdicts)
        self.assertIn("origin/main", report)
        self.assertIn("шляхів перевірено: 2", report)
        self.assertIn("є в origin/main: 1", report)
        self.assertIn("відмов: 1", report)

    def test_report_counts_a_new_path_as_new_not_as_present(self):
        # Інакше підсумок казав би «є в origin/main: 3» про шлях, якого там
        # немає, — а це та сама неправда про `main`, яку ґард і ловить.
        _, verdicts, _ = judge_body("`NEW:docs/agents/premise.md` і `AGENTS.md`", {"AGENTS.md"})
        report = ip.format_report("origin/main", 0, verdicts)
        self.assertIn("є в origin/main: 1", report)
        self.assertIn("позначено `NEW:`: 1", report)


class RealGitTest(unittest.TestCase):
    """Справжній `git` — але у **своєму** репозиторії, не в чекауті.

    Це відповідь на падіння CI PR #1268: тест, який питав `origin/main`
    репозиторію, у якому біжить, залежав від того, що завантажив раннер
    (`actions/checkout` фетчить merge-ref, і `origin/main` там немає — тож
    дерево порожнє, і тека читалася як проза). Тут репозиторій піднімається
    сам, зі своїм `origin/main`, тож перевіряється справжній git-шлях
    (`cat-file`, `ls-tree`, `check-ignore`), а не підставка — і жодного
    питання до стану чекауту.
    """

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="issue-premise-git-")
        cls.addClassCleanup(cls._tmp.cleanup)
        cls.repo = build_repo(
            Path(cls._tmp.name),
            {
                "AGENTS.md": "# Agent instructions\n",
                "docs/voice.md": "voice\n",
                "docs/agents/issue-tracker.md": "tracker\n",
                "ui/components/PlayerPane.kt": "package ui.components\n",
            },
            ".mutations/\n/build\n",
        )

    def test_ref_reads_as_a_commit(self):
        ok, revision = ip.resolve_main_ref("origin/main", self.repo)
        self.assertTrue(ok)
        self.assertRegex(revision, r"^[0-9a-f]{40}$")

    def test_file_in_main_is_found(self):
        self.assertTrue(ip.main_has_path("origin/main", "AGENTS.md", self.repo))

    def test_untracked_file_in_the_worktree_is_invisible_to_main(self):
        # Головна межа ґарда: він дивиться в дерево `origin/main`, а не в
        # робочу копію. Цей файл є в дереві й не є в ref-і.
        (self.repo / "UNCOMMITTED.md").write_text("ще не в main\n", encoding="utf-8")
        self.assertTrue((self.repo / "UNCOMMITTED.md").exists())
        self.assertFalse(ip.main_has_path("origin/main", "UNCOMMITTED.md", self.repo))

    def test_unborn_path_in_main_is_not_found(self):
        self.assertFalse(ip.main_has_path("origin/main", "CODING_STANDARDS.md", self.repo))
        self.assertTrue(ip.main_has_path("origin/main", "AGENTS.md", self.repo))

    def test_suffix_lookup_sees_directories_through_real_git(self):
        # `-t` на справжньому дереві: тека є лише деревом, не блобом.
        matches = ip.main_matches_suffix("origin/main", "ui/components", self.repo)
        self.assertEqual([(match.path, match.kind) for match in matches],
                         [("ui/components", "tree")])

    def test_a_bare_directory_name_is_ambiguous_through_real_git(self):
        # Той самий ланцюг, що #982, але справжнім git: `components/` —
        # односегментне ім'я без розширення, і єдина коротка форма — ТЕКА.
        code, verdicts = ip.check_body(
            "тега `x` у `components/` немає", "origin/main", self.repo
        )
        self.assertEqual(code, 2)
        self.assertEqual(verdicts[0].candidate.path, "components")
        self.assertIn("неоднозначне", verdicts[0].detail)
        self.assertIn("ui/components", verdicts[0].detail)

    def test_ignored_path_goes_through_the_repository_gitignore(self):
        self.assertTrue(ip._is_ignored(".mutations", self.repo))
        self.assertFalse(ip._is_ignored("AGENTS.md", self.repo))

    def test_body_is_judged_against_the_repos_own_ref(self):
        # Той самий шлях у тілі, але ref-и різні: вердикт дає дерево ref-а.
        code, _ = ip.check_body("читай `AGENTS.md`", "origin/main", self.repo)
        self.assertEqual(code, 0)

    def test_prose_decision_goes_through_real_git(self):
        # Найважливіший пін: саме цей ланцюг упав у CI PR #1268 —
        # `_is_prose_fragment` питає дерево ref-а, і на порожньому дереві
        # `docs/agents` читався як проза. Тут дерево справжнє, тож `try/catch`
        # (проза) і тека (не проза) розходяться **вердиктом**, а не моком.
        prose, prose_verdicts = ip.check_body("код `try/catch`", "origin/main", self.repo)
        self.assertEqual(prose, 0)
        self.assertIn("проза", prose_verdicts[0].detail)

        missing, missing_verdicts = ip.check_body("тека `docs/nope`", "origin/main", self.repo)
        self.assertEqual(missing, 1)
        self.assertIn("премиси немає", missing_verdicts[0].detail)

        # А `docs/agents` — тека в дереві ref-а, і вона не проза.
        tracked, tracked_verdicts = ip.check_body("тека `docs/agents`", "origin/main", self.repo)
        self.assertEqual(tracked, 0)
        self.assertEqual(tracked_verdicts[0].code, 0)

    def test_missing_ref_is_unknown_not_a_pass(self):
        code, verdicts = ip.check_body("читай `AGENTS.md`", "origin/nonexistent", self.repo)
        self.assertEqual(code, 2)
        self.assertIn("не читається", verdicts[0].detail)

    def test_measure_head_refuses_to_measure_an_unreadable_ref(self):
        # На нечитабельному ref-і дерево порожнє, і команда надрукувала б
        # «верхня тека `docs` у дереві: немає» — твердження про дерево, якого
        # ніхто не бачив. У CI `origin/main` немає, і це рівно той випадок.
        #
        # `gh` тут заборонений: якщо охорона ref-а зникне, тест мусить упасти
        # сам, а не випадково вгадати код 2 через невдалий мережевий виклик.
        # `git` лишається справжнім — саме він і каже, що ref не читається.
        real_run = subprocess.run

        def git_only(args, *rest, **kwargs):
            if list(args)[:1] != ["git"]:
                raise AssertionError(f"gh на нечитабельному ref-і кликати нічого: {args}")
            return real_run(args, *rest, **kwargs)

        printed, errors = io.StringIO(), io.StringIO()
        with mock.patch.object(ip.subprocess, "run", git_only), \
                contextlib.redirect_stdout(printed), contextlib.redirect_stderr(errors):
            code = ip.measure_head("docs", 200, "origin/nonexistent", self.repo)
        self.assertEqual(code, 2)
        self.assertIn("не читається", errors.getvalue())
        self.assertNotIn("у дереві", printed.getvalue())

    def test_git_output_reports_failure_for_a_missing_path(self):
        code, _, _ = ip.git_output(
            ["cat-file", "-e", "origin/main:definitely/not/here.md"], self.repo
        )
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
