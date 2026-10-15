"""Тести scripts/new-lane.sh: диспетчеризація смуги — командою, а не трьома руками.

Стиль той самий, що в scripts/tests/test_merge_pr.py: скрипт запускається як
процес, робоча копія — справжній git-репозиторій у тимчасовому каталозі
(NEW_LANE_REPO), і жоден тест не чіпає справжній репозиторій і не ходить у
мережу: `origin` — локальний bare-репозиторій.

Копія смуги містить ЛИШЕ ті скрипти, які new-lane.sh справді кличе з неї
(`guard_witnesses.py --count` і `ci_job_time.py --list`) та воркфлоу, з якого
`ci_job_time` читає ліміт. Фікстура не вдає, що це все дерево: якщо список
викликів виросте, тест почервоніє, а не піде тихо в інший бік.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
NEW_LANE = REPO_ROOT / "scripts" / "new-lane.sh"
LANE_SCRIPTS = ("guard_witnesses.py", "tmp_budget.py", "ci_job_time.py")
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
GITIGNORE = REPO_ROOT / ".gitignore"

sys.path.insert(0, str(REPO_ROOT / "scripts"))

import guard_witnesses  # noqa: E402

ISSUE = "1321"
SLUG = "new-lane"
OTHER_SLUG = "other-band"


class LaneTest(unittest.TestCase):
    """Справжній git-репозиторій із локальним `origin` — і жодного дотику до свого."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="new-lane-")
        self.addCleanup(self._tmp.cleanup)
        self.base = Path(self._tmp.name)
        self.origin = self.base / "origin.git"
        self.repo = self.base / "repo"

        self.git("init", "--bare", "-q", "-b", "main", str(self.origin), cwd=self.base)
        self.git("init", "-q", "-b", "main", str(self.repo), cwd=self.base)
        (self.repo / "scripts").mkdir()
        for name in LANE_SCRIPTS:
            shutil.copy(REPO_ROOT / "scripts" / name, self.repo / "scripts" / name)
        (self.repo / ".github" / "workflows").mkdir(parents=True)
        shutil.copy(WORKFLOW, self.repo / ".github" / "workflows" / "ci.yml")
        # `.gitignore` — не прикраса: `guard_witnesses.py --count` лишає в копії
        # `scripts/__pycache__`, і без ігнорування копія виглядала б брудною.
        # Це та сама межа, що в репозиторії: збирати факти можна в копії.
        shutil.copy(GITIGNORE, self.repo / ".gitignore")
        self.git("add", "-A")
        self.commit("база смуги")
        self.git("remote", "add", "origin", str(self.origin))
        self.git("push", "-q", "origin", "main")
        self.main_sha = self.rev("origin/main")

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
        self.git("-c", "user.name=Лейн", "-c", "user.email=lane@example.invalid",
                 "commit", "-q", "-m", message)

    def rev(self, *args, cwd=None):
        return self.git("rev-parse", *args, cwd=cwd).stdout.strip()

    def lane_path(self, issue=ISSUE):
        return self.repo / ".worktrees" / ("w%s" % issue)

    def claim_path(self, issue=ISSUE):
        return self.repo / ".git" / "agent-work-claims" / ("%s.json" % issue)

    def lane(self, *args, owner="test-thread", expect=0, env=None):
        environment = {
            **os.environ,
            "NEW_LANE_REPO": str(self.repo),
            "NEW_LANE_OWNER": owner,
        }
        if env:
            environment.update(env)
        result = subprocess.run(
            [str(NEW_LANE), *args], cwd=str(self.repo),
            capture_output=True, text=True, env=environment, check=False,
        )
        self.assertEqual(
            expect, result.returncode,
            "new-lane %s\n--- stdout ---\n%s\n--- stderr ---\n%s"
            % (" ".join(args), result.stdout, result.stderr),
        )
        return result

    def create_lane(self, *args, expect=0, env=None):
        return self.lane(ISSUE, SLUG, *args, expect=expect, env=env)


class CreateTest(LaneTest):
    """Смуга створюється від свіжого `origin/main`, а факти беруться командою."""

    def test_the_lane_is_created_from_origin_main_and_the_facts_are_printed(self):
        result = self.create_lane()

        lane = self.lane_path()
        self.assertTrue(lane.is_dir(), result.stdout)
        self.assertEqual("t%s-%s" % (ISSUE, SLUG),
                         self.rev("--abbrev-ref", "HEAD", cwd=lane))
        self.assertEqual(self.rev("origin/main"), self.rev("HEAD", cwd=lane))

        self.assertIn("смуга: t%s-%s" % (ISSUE, SLUG), result.stdout)
        self.assertIn("копія: %s" % lane, result.stdout)
        self.assertIn("база: ", result.stdout)
        self.assertIn(self.rev("origin/main"), result.stdout)
        # Число сторожів — із РЕЄСТРУ (`--count`), а не з рядків `--list`.
        self.assertIn("сторожів у реєстрі: %d" % len(guard_witnesses.GUARDS),
                      result.stdout)
        self.assertIn("ліміт джоби script-tests: 480 с", result.stdout)
        self.assertIn("команди смуги", result.stdout)
        self.assertIn("python3 scripts/guard_witnesses.py", result.stdout)
        self.assertIn("scripts/new-lane.sh --release %s" % ISSUE, result.stdout)
        self.assertTrue(self.claim_path().is_file(), "тікет мусить бути зарезервовано")

        # Гілка смуги не «відстежує» origin/main: інакше `git status` бреше про
        # базу — та сама хибна база, тільки тихіша.
        upstream = self.git("rev-parse", "--abbrev-ref", "--symbolic-full-name",
                            "@{upstream}", cwd=lane, check=False)
        self.assertNotEqual(0, upstream.returncode, upstream.stdout)

    def test_a_stale_checkout_still_gives_the_lane_the_fresh_origin_main(self):
        """База — свіжий `origin/main`, а не поточний HEAD координатора."""
        (self.repo / "later.txt").write_text("пізніше\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("новий main поїхав у remote")
        self.git("push", "-q", "origin", "main")
        fresh = self.rev("origin/main")
        self.git("reset", "-q", "--hard", "HEAD~1")  # координатор відстав

        self.create_lane()

        self.assertNotEqual(fresh, self.rev("HEAD"))
        self.assertEqual(fresh, self.rev("HEAD", cwd=self.lane_path()))

    def test_the_default_plan_path_names_the_ticket_and_the_slug(self):
        result = self.create_lane()

        self.assertIn("план: docs/plans/", result.stdout)
        self.assertIn("%s-%s.md" % (ISSUE, SLUG), result.stdout)

    def test_a_named_owner_is_the_one_written_into_the_reservation(self):
        self.create_lane(env={"NEW_LANE_OWNER": "dsh-web-w1321"})

        self.assertIn("dsh-web-w1321", self.claim_path().read_text(encoding="utf-8"))


class RefusalTest(LaneTest):
    """Відмова — код 2 і назва, і жодної створеної копії по собі."""

    def test_a_second_lane_in_the_same_copy_is_refused(self):
        first = self.create_lane()
        self.assertEqual(0, first.returncode)

        second = self.lane(ISSUE, OTHER_SLUG, expect=2)

        self.assertIn("копія вже існує", second.stderr)
        self.assertIn(str(self.lane_path()), second.stderr)
        # Перша смуга недоторкана: гілка та сама, копія на місці.
        self.assertEqual("t%s-%s" % (ISSUE, SLUG),
                         self.rev("--abbrev-ref", "HEAD", cwd=self.lane_path()))

    def test_an_occupied_ticket_refuses_without_creating_a_copy(self):
        claim = subprocess.run(
            [sys.executable, str(REPO_ROOT / "scripts" / "agent-work-claim.py"),
             "acquire", ISSUE, "--owner", "somebody-else",
             "--reservation", "уже зайнято", "--plan", "docs/plans/x.md"],
            cwd=str(self.repo), capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, claim.returncode, claim.stderr)

        result = self.lane(ISSUE, SLUG, expect=2)

        self.assertFalse(self.lane_path().exists(), result.stdout)
        self.assertIn("зарезервовано", result.stderr)
        self.assertIn("somebody-else", result.stderr)
        self.assertTrue(self.claim_path().is_file(), "чуже резервування не чіпається")

    def test_an_existing_branch_without_a_copy_is_refused(self):
        self.git("branch", "t%s-%s" % (ISSUE, SLUG), "origin/main")

        result = self.create_lane(expect=2)

        self.assertIn("гілка", result.stderr)
        self.assertFalse(self.lane_path().exists())

    def test_a_base_that_cannot_answer_leaves_no_copy_no_claim_and_no_branch(self):
        """Невідомість відкатується ПОВНІСТЮ — інакше наступна спроба впреться
        у «гілка вже існує» від власного невдалого прогону."""
        (self.repo / "scripts" / "guard_witnesses.py").write_text(
            'import sys\n\nif "--count" in sys.argv:\n    sys.exit(2)\nprint(0)\n',
            encoding="utf-8",
        )
        self.git("add", "-A")
        self.commit("стара база без --count")
        self.git("push", "-q", "origin", "main")

        result = self.create_lane(expect=2)

        self.assertIn("копію прибрано", result.stderr)
        self.assertFalse(self.lane_path().exists())
        self.assertFalse(self.claim_path().exists())
        self.assertNotEqual(
            0,
            self.git("show-ref", "--verify", "--quiet",
                     "refs/heads/t%s-%s" % (ISSUE, SLUG), check=False).returncode,
            "гілка невдалого прогону не сміє лишитися",
        )

    def test_a_base_that_answers_nothing_is_an_unknown(self):
        """Порожній рядок — теж невідомість, а не «нуль сторожів».

        База, яка на `--count` виходить нулем і не друкує НІЧОГО, мусить
        відмовити (код 2): інакше бриф отримав би порожнє число — те саме хибне
        число, тільки без цифри.
        """
        (self.repo / "scripts" / "guard_witnesses.py").write_text(
            'import sys\n\nif "--count" in sys.argv:\n    sys.exit(0)\nprint(0)\n',
            encoding="utf-8",
        )
        self.git("add", "-A")
        self.commit("база, яка мовчить на --count")
        self.git("push", "-q", "origin", "main")

        result = self.create_lane(expect=2)

        self.assertIn("не число", result.stderr)
        self.assertFalse(self.lane_path().exists())
        self.assertFalse(self.claim_path().exists())

    def test_the_owner_is_never_invented(self):
        result = self.create_lane(expect=2, env={"NEW_LANE_OWNER": "", "DSH_SESSION_ID": ""})

        self.assertIn("власника", result.stderr)
        self.assertFalse(self.lane_path().exists())
        self.assertFalse(self.claim_path().exists())

    def test_a_non_numeric_ticket_is_refused(self):
        result = self.lane("13x", SLUG, expect=2)

        self.assertIn("числом", result.stderr)
        self.assertFalse((self.repo / ".worktrees").exists())


class ReleaseTest(LaneTest):
    """Гігієна: копія прибирається, коли в ній нема чого втрачати."""

    def test_release_removes_a_clean_copy_and_keeps_the_branch_and_the_claim(self):
        self.create_lane()

        result = self.lane("--release", ISSUE)

        self.assertIn("копію прибрано", result.stdout)
        self.assertFalse(self.lane_path().exists())
        self.assertNotIn(str(self.lane_path()),
                         self.git("worktree", "list", "--porcelain").stdout)
        # Гілку не видаляє ніщо: та сама межа, що в merge-pr.sh.
        self.assertEqual(0, self.git("show-ref", "--verify", "--quiet",
                                     "refs/heads/t%s-%s" % (ISSUE, SLUG),
                                     check=False).returncode)
        # Резервування знімає координатор, а не прибирання копії.
        self.assertTrue(self.claim_path().is_file())

    def test_a_dirty_copy_is_not_removed_by_release(self):
        self.create_lane()
        scratch = self.lane_path() / "невідправлене.txt"
        scratch.write_text("правки огляду\n", encoding="utf-8")

        result = self.lane("--release", ISSUE, expect=1)

        self.assertIn("незакомічені", result.stderr)
        self.assertIn(str(self.lane_path()), result.stderr)
        self.assertTrue(self.lane_path().is_dir(), "копію втрачати не можна")
        self.assertTrue(scratch.is_file())

    def test_release_of_a_missing_lane_is_an_unknown(self):
        result = self.lane("--release", ISSUE, expect=2)

        self.assertIn("нема чого звільняти", result.stderr)


if __name__ == "__main__":
    unittest.main()
