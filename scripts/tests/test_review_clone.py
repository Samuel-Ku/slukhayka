"""Тести scripts/review-clone.sh: копія для огляду й НАЗВАНА база дифу (#1326).

Стиль той самий, що в scripts/tests/test_new_lane.py: скрипт запускається як
процес, а робоча копія — справжній git-репозиторій у тимчасовому каталозі
(`REVIEW_CLONE_REPO`). Мережі не торкається жоден тест: `origin` — локальний
bare-репозиторій, а «мережі немає» відтворюється тим, що `origin` вказує на
неіснуючий шлях.

ДВА тести тут — ЧЕРВОНІ на старій поведінці, і кожен показує її СИРИМ виводом,
а не переказом; третій тримає ціну кроку:

  * `test_the_printed_diff_is_the_branch_and_not_the_working_tree` — робоче
    дерево стоїть на іншій гілці (#1323). Стара команда
    `git diff origin/main...HEAD` дає ЧУЖИЙ диф; надрукована скриптом — рівно
    диф гілки;
  * `test_a_shallow_repo_is_deepened_before_the_base_is_named` — репо shallow
    (#1326; приклади тікета не відтворені — межа названа в шапці скрипта).
    Стара команда в ньому падає на «no merge base» — базу назвати нічим;
    скрипт спершу поглиблює історію;
  * `test_the_normal_case_stays_quiet_and_touches_no_other_ref` — крок не
    галасливий: шість рядків виводу, порожній stderr і жодного ref'а, крім двох
    названих (ні `git fetch origin`, ні `--unshallow` на повному репо).
"""

import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REVIEW_CLONE = REPO_ROOT / "scripts" / "review-clone.sh"

BRANCH = "feat/x"
OTHER = "other"
BRANCH_FILES = ("feat-one.txt", "feat-two.txt")
FOREIGN_FILES = ("foreign-one.txt", "foreign-two.txt", "foreign-three.txt")

# Скільки рядків друкує нормальний випадок. Це число — навмисна межа
# («нормальний випадок — мінімум виводу»), тож новий рядок у виводі мусить
# прийти разом із правкою тут, а не прослизнути.
NORMAL_LINES = 6


class CloneTest(unittest.TestCase):
    """Справжній git-репозиторій із локальним `origin` — і жодного дотику до свого."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="review-clone-")
        self.addCleanup(self._tmp.cleanup)
        self.base = Path(self._tmp.name)
        self.origin = self.base / "origin.git"
        self.repo = self.base / "repo"
        self.copy = self.base / "copy"

        self.git("init", "--bare", "-q", "-b", "main", str(self.origin), cwd=self.base)
        self.git("init", "-q", "-b", "main", str(self.repo), cwd=self.base)
        (self.repo / "base.txt").write_text("база\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("C1 — спільний предок")
        self.git("remote", "add", "origin", str(self.origin))
        self.git("push", "-q", "origin", "main")
        self.ancestor = self.rev("HEAD")

        # Гілка огляду: два свої коміти поверх C1.
        self.git("checkout", "-q", "-b", BRANCH)
        for name in BRANCH_FILES:
            (self.repo / name).write_text("гілка\n", encoding="utf-8")
            self.git("add", "-A")
            self.commit("B — %s" % name)
        self.git("push", "-q", "origin", BRANCH)
        self.tip = self.rev("HEAD")

        # main поїхав далі: база огляду — C1, а не верхівка main.
        self.git("checkout", "-q", "main")
        (self.repo / "main-moved.txt").write_text("main поїхав\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("M2 — main рушив")
        self.git("push", "-q", "origin", "main")
        self.main_tip = self.rev("HEAD")

        # Координатор стояв на ІНШІЙ гілці — саме так #1323 дав чужий диф.
        self.git("checkout", "-q", "-b", OTHER)
        for name in FOREIGN_FILES:
            (self.repo / name).write_text("чуже\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("O1 — чужа гілка")
        self.git("checkout", "-q", OTHER)
        self.head_elsewhere = self.rev("HEAD")

    # --- інструменти -------------------------------------------------------
    def git(self, *args, cwd=None, check=True):
        result = subprocess.run(
            ["git", *args], cwd=str(cwd or self.repo),
            capture_output=True, text=True, check=False,
        )
        if check and result.returncode != 0:
            self.fail("git %s: %s" % (" ".join(args), result.stderr or result.stdout))
        return result

    def commit(self, message):
        self.git("-c", "user.name=Огляд", "-c", "user.email=review@example.invalid",
                 "commit", "-q", "-m", message)

    def rev(self, *args, cwd=None):
        return self.git("rev-parse", *args, cwd=cwd).stdout.strip()

    def review(self, *args, expect=0, repo=None, cwd=None):
        environment = {**os.environ, "REVIEW_CLONE_REPO": str(repo or self.repo)}
        result = subprocess.run(
            [str(REVIEW_CLONE), *args], cwd=str(cwd or self.repo),
            capture_output=True, text=True, env=environment, check=False,
        )
        self.assertEqual(
            expect, result.returncode,
            "review-clone %s\n--- stdout ---\n%s\n--- stderr ---\n%s"
            % (" ".join(args), result.stdout, result.stderr),
        )
        return result

    def clone(self, *args, expect=0, **kwargs):
        return self.review(BRANCH, "--dir", str(self.copy), *args, expect=expect, **kwargs)

    def printed_diff(self, stdout):
        """Надрукована команда дифу — рівно той рядок, який побачить оглядач."""
        lines = [line for line in stdout.splitlines() if line.startswith("диф: ")]
        self.assertEqual(1, len(lines), stdout)
        return lines[0][len("диф: "):]

    def run_diff(self, command, cwd):
        """Виконує надруковану команду ТУТ, де її виконає оглядач.

        Команда запускається рівно як надрукована (патч, не `--name-only`), а
        імена файлів беруться з її ж заголовків `diff --git`: так перевіряється
        не «схожа команда», а сама вона.
        """
        parts = shlex.split(command)
        self.assertEqual("git", parts[0], command)
        result = self.git(*parts[1:], cwd=cwd)
        paths = []
        for line in result.stdout.splitlines():
            if line.startswith("diff --git "):
                _, _, path = line[len("diff --git "):].rpartition(" b/")
                paths.append(path)
        return sorted(paths)

    def printed_base(self, stdout):
        lines = [line for line in stdout.splitlines() if line.startswith("база: ")]
        self.assertEqual(1, len(lines), stdout)
        index = lines[0].index("(")
        return lines[0][index + 1:lines[0].index(")")]


class NormalCaseTest(CloneTest):
    """Нормальний випадок: копія на правильній ревізії, база названа, тихо."""

    def test_the_copy_and_the_base_are_named_for_the_pushed_branch(self):
        result = self.clone()

        self.assertTrue(self.copy.is_dir(), result.stdout)
        self.assertEqual(self.tip, self.rev("HEAD", cwd=self.copy))
        self.assertEqual("HEAD", self.rev("--abbrev-ref", "HEAD", cwd=self.copy),
                         "копія мусить бути detached: гілку огляду вона не створює")
        # База — merge-base, а не верхівка main і не HEAD.
        self.assertEqual(self.ancestor, self.printed_base(result.stdout))
        self.assertIn(self.ancestor, result.stdout)
        self.assertIn("merge-base origin/main...origin/%s" % BRANCH, result.stdout)
        self.assertIn("коміти: попереду 2, позаду 1 (origin/main)", result.stdout)
        self.assertIn("shallow: ні", result.stdout)

    def test_the_printed_diff_is_the_branch_and_not_the_working_tree(self):
        """СИРИЙ вивід обох команд: надрукована й стара `…HEAD`.

        Робоче дерево координатора стоїть на ЧУЖІЙ гілці (#1323). Стара команда
        дає чужий диф; надрукована — рівно два файли гілки.
        """
        old = self.git("diff", "--name-only", "origin/main...HEAD", cwd=self.repo)
        result = self.clone()
        command = self.printed_diff(result.stdout)
        new = self.run_diff(command, cwd=self.repo)

        self.assertEqual(sorted(FOREIGN_FILES), sorted(old.stdout.split()),
                         "стара команда мусить давати чужий диф — інакше тест "
                         "нічого не доводить:\n%s" % old.stdout)
        self.assertEqual(list(BRANCH_FILES), new,
                         "надрукована команда мусить давати диф ГІЛКИ:\n"
                         "команда: %s\nсирий вивід:\n%s" % (command, "\n".join(new)))
        self.assertNotIn(FOREIGN_FILES[0], new)
        self.assertEqual(self.ancestor, self.printed_base(result.stdout))
        self.assertEqual("git diff %s...origin/%s" % (self.ancestor, BRANCH),
                         command, "команда мусить називати БАЗУ Й origin/<гілку>, "
                                  "не HEAD")

    def test_the_normal_case_stays_quiet_and_touches_no_other_ref(self):
        """Мінімум виводу й жодного ref'а, крім двох названих."""
        # `side` існує на origin, але в цій копії його ref'а немає: якби скрипт
        # робив `git fetch origin`, ref з'явився б.
        self.git("branch", "side", self.ancestor, cwd=self.origin)
        before = self.git("rev-parse", "--verify", "origin/side", cwd=self.repo, check=False)
        self.assertNotEqual(0, before.returncode, before.stdout)

        result = self.clone()

        after = self.git("rev-parse", "--verify", "origin/side", cwd=self.repo, check=False)
        self.assertNotEqual(0, after.returncode,
                            "fetch мусить брати ЛИШЕ названі refspec'и, не всі гілки")
        self.assertEqual("", result.stderr, "нормальний випадок не має шуміти")
        self.assertEqual(NORMAL_LINES, len(result.stdout.strip().splitlines()),
                         result.stdout)
        self.assertIn("shallow: ні", result.stdout)
        self.assertNotIn("Preparing worktree", result.stdout)

    def test_the_default_directory_names_the_branch_and_not_a_lane(self):
        result = self.review(BRANCH, cwd=self.base)

        expected = self.repo / ".worktrees" / "review-feat-x"
        self.assertIn("копія: %s " % expected, result.stdout)
        self.assertTrue(expected.is_dir(), result.stdout)

    def test_an_origin_prefixed_branch_name_is_accepted(self):
        result = self.review("origin/%s" % BRANCH, "--dir", str(self.copy))

        self.assertIn("origin/%s" % BRANCH, result.stdout)
        self.assertEqual(self.tip, self.rev("HEAD", cwd=self.copy))


class ShallowTest(CloneTest):
    """Репо shallow: база непевна, поки історію не поглиблено (#1326)."""

    def shallow_clone(self):
        """Shallow-копія з ОБОМА refs — і доведенням, що бази в ній немає."""
        shallow = self.base / "shallow"
        self.git("clone", "-q", "--depth", "1", "--branch", BRANCH,
                 "file://%s" % self.origin, str(shallow), cwd=self.base)
        self.git("fetch", "-q", "--depth", "1", "origin",
                 "+refs/heads/main:refs/remotes/origin/main", cwd=shallow)
        self.assertEqual("true", self.rev("--is-shallow-repository", cwd=shallow))
        return shallow

    def test_a_shallow_repo_is_deepened_before_the_base_is_named(self):
        """ЧЕРВОНИЙ на старій поведінці: у shallow диф не будується взагалі.

        Стара команда — та сама, якою #1320 уперся в «no merge base»; її сирий
        вивід тут і показано. Скрипт мусить спершу поглибити історію, а вже
        потім назвати базу.
        """
        shallow = self.shallow_clone()
        old = self.git("diff", "--name-only",
                       "origin/main...origin/%s" % BRANCH, cwd=shallow, check=False)

        result = self.clone(repo=shallow)

        self.assertNotEqual(0, old.returncode,
                            "стара команда мусить падати — інакше тест нічого не "
                            "доводить:\n%s" % old.stdout)
        self.assertIn("no merge base", old.stdout + old.stderr,
                      "сирий вивід старої команди: %s" % (old.stdout + old.stderr))
        self.assertIn("shallow: так", result.stdout)
        self.assertIn("--unshallow", result.stdout)
        self.assertEqual(self.ancestor, self.printed_base(result.stdout),
                         "база мусить бути СПРАВЖНІМ merge-base, не обрізаним коренем")
        self.assertEqual("false", self.rev("--is-shallow-repository", cwd=shallow))
        self.assertEqual(self.tip, self.rev("HEAD", cwd=self.copy))
        self.assertEqual(list(BRANCH_FILES),
                         self.run_diff(self.printed_diff(result.stdout), cwd=shallow))


class UnknownTest(CloneTest):
    """Невідомість — код 2, КОЖЕН випадок названий окремо, і копії не лишається."""

    def test_a_missing_branch_is_named(self):
        result = self.review("нема-такої", "--dir", str(self.copy), expect=2)

        self.assertIn("немає гілки", result.stderr)
        self.assertIn("нема-такої", result.stderr)
        self.assertFalse(self.copy.exists(), result.stdout)

    def test_a_remote_without_main_is_named(self):
        self.git("branch", "-m", "main", "trunk", cwd=self.origin)

        result = self.clone(expect=2)

        self.assertIn("немає origin/main", result.stderr)
        self.assertFalse(self.copy.exists(), result.stdout)

    def test_an_unreachable_origin_is_named_as_no_network(self):
        self.git("remote", "set-url", "origin", str(self.base / "не-існує.git"))

        result = self.clone(expect=2)

        self.assertIn("немає мережі", result.stderr)
        self.assertNotIn("немає гілки", result.stderr)
        self.assertFalse(self.copy.exists(), result.stdout)

    def test_a_branch_without_shared_history_is_named(self):
        """Гілка з іншого кореня: fetch удається, а merge-base — ні."""
        self.git("checkout", "-q", "--orphan", "orphan")
        (self.repo / "orphan.txt").write_text("інший корінь\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("O — інший корінь")
        self.git("push", "-q", "origin", "orphan")
        self.git("checkout", "-q", OTHER)
        self.assertEqual(0, self.git("fetch", "-q", "origin", "orphan").returncode)

        result = self.review("orphan", "--dir", str(self.copy), expect=2)

        self.assertIn("немає merge-base", result.stderr)
        self.assertFalse(self.copy.exists(), result.stdout)

    def test_an_occupied_directory_is_refused_and_left_alone(self):
        self.copy.mkdir()
        keepsake = self.copy / "чиєсь.txt"
        keepsake.write_text("невідоме\n", encoding="utf-8")

        result = self.clone(expect=2)

        self.assertIn("вже існує", result.stderr)
        self.assertTrue(keepsake.is_file())

    def test_a_failed_worktree_add_leaves_no_half_copy(self):
        """Відкат прибирає РІВНО те, що створив сам.

        Підставний `post-checkout` валить `git worktree add` ПІСЛЯ реєстрації
        worktree (перевірено: запис лишається, тека порожня). Напівкопію треба
        прибрати, інакше наступний виклик упаде на «тека вже існує» від
        власного невдалого прогону.
        """
        hook = self.repo / ".git" / "hooks" / "post-checkout"
        hook.parent.mkdir(parents=True, exist_ok=True)
        hook.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        hook.chmod(0o755)

        result = self.clone(expect=2)

        self.assertIn("напівкопію прибрано", result.stderr)
        self.assertFalse(self.copy.exists(), result.stdout)
        self.assertNotIn(str(self.copy),
                         self.git("worktree", "list", "--porcelain").stdout)

    def test_the_usage_is_named_and_bad_arguments_refuse(self):
        self.assertEqual(0, self.review("--help", expect=0).returncode)
        for arguments in ([], ["--dir"], [BRANCH, "--dir", str(self.copy), "зайвий"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [str(REVIEW_CLONE), *arguments], cwd=str(self.repo),
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse(self.copy.exists())


if __name__ == "__main__":
    unittest.main()
