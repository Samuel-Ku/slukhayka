"""Тести scripts/new-lane.sh: диспетчеризація смуги — командою, а не трьома руками.

Стиль той самий, що в scripts/tests/test_merge_pr.py: скрипт запускається як
процес, робоча копія — справжній git-репозиторій у тимчасовому каталозі
(NEW_LANE_REPO), і жоден тест не чіпає справжній репозиторій і не ходить у
мережу: `origin` — локальний bare-репозиторій.

Копія смуги містить ЛИШЕ ті скрипти, які new-lane.sh справді кличе з неї
(`guard_witnesses.py --count`, `ci_job_time.py --list` і
`issue_premise.py --issue` — премиса тікета, #1324) та воркфлоу, з якого
`ci_job_time` читає ліміт. Фікстура не вдає, що це все дерево: якщо список
викликів виросте, тест почервоніє, а не піде тихо в інший бік.

`gh` у тестах — фікстура на `PATH` (`FAKE_GH`): тікет читає справжній
`issue_premise.py`, а тіло тікета приходить із файлу, названого в
`NEW_LANE_TEST_ISSUE_BODY`. Порожнє ім'я — «gh не відповів»; саме так
перевіряється невідомість (код 2), яку не можна видати за зелене світло.
Мережі не торкається жоден тест.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
NEW_LANE = REPO_ROOT / "scripts" / "new-lane.sh"
LANE_SCRIPTS = ("guard_witnesses.py", "tmp_budget.py", "ci_job_time.py",
                "issue_premise.py")
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
GITIGNORE = REPO_ROOT / ".gitignore"

# Фікстура `gh`: тіло тікета — з файлу, названого в `NEW_LANE_TEST_ISSUE_BODY`,
# а сам виклик дописується в `NEW_LANE_TEST_GH_LOG` (щоб тест бачив, про ЯКИЙ
# тікет спитали). Порожнє ім'я файлу — «gh не відповів», і тоді вихід 1.
FAKE_GH = """#!/bin/sh
# Фікстура `gh issue view <номер> --json body --jq .body` для тестів new-lane.
if [ -n "${NEW_LANE_TEST_GH_LOG:-}" ]; then
  printf '%s\\n' "$*" >> "$NEW_LANE_TEST_GH_LOG"
fi
body="${NEW_LANE_TEST_ISSUE_BODY:-}"
if [ -n "$body" ] && cat -- "$body" 2>/dev/null; then
  exit 0
fi
echo "gh: тікет не читається (фікстура без тіла)" >&2
exit 1
"""

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

        # `gh` — фікстура: тікет читає справжній `issue_premise.py`, а тіло
        # приходить із файлу. Мережа в тестах не потрібна жодному з них.
        self.bin = self.base / "bin"
        self.bin.mkdir()
        fake_gh = self.bin / "gh"
        fake_gh.write_text(FAKE_GH, encoding="utf-8")
        fake_gh.chmod(0o755)
        self.gh_log_path = self.base / "gh.log"
        self.issue_body = self.base / "issue-body.md"
        # Типове тіло — свіже: шлях із нього справді є в `origin/main` копії.
        self.set_issue_body("Тікет посилається на `scripts/guard_witnesses.py`.\n")

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
            # Фікстура `gh` попереду справжнього: жоден тест не ходить у мережу
            # й не читає справжній тікет.
            "PATH": "%s%s%s" % (self.bin, os.pathsep, os.environ.get("PATH", "")),
            "NEW_LANE_TEST_ISSUE_BODY": str(self.issue_body),
            "NEW_LANE_TEST_GH_LOG": str(self.gh_log_path),
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

    def set_issue_body(self, text):
        """Тіло, яке фікстура `gh` віддає на `gh issue view <номер>`."""
        self.issue_body.write_text(text, encoding="utf-8")

    def gh_calls(self):
        """Рядки викликів фікстурованого `gh` — щоб бачити, про який тікет питали."""
        if not self.gh_log_path.exists():
            return ""
        return self.gh_log_path.read_text(encoding="utf-8")

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

    def test_the_reservation_does_not_invent_a_plan_path(self):
        """Без `--plan` у записі `null`, і факти це НАЗИВАЮТЬ (#1337).

        Червоний на СТАРІЙ поведінці: крок вигадував
        `docs/plans/<дата>-<номер>-<slug>.md`, якого не існувало — шість
        записів, шість шляхів, і жоден `docs/plans/…` не вів нікуди. Запис, що
        каже одне, а реальність — інше, і є той дефект.
        """
        result = self.create_lane()

        self.assertIn("план: не задано", result.stdout)
        self.assertIn("null", result.stdout)
        self.assertNotIn("docs/plans/", result.stdout,
                         "вигаданого шляху плану в фактах бути не може")
        recorded = json.loads(self.claim_path().read_text(encoding="utf-8"))
        self.assertIsNone(recorded["plan"],
                          "у записі мусить бути чесне null, а не вигаданий шлях")

    def test_a_named_plan_that_does_not_exist_is_refused(self):
        """`--plan` на неіснуючий шлях — відмова ДО резервування (#1337)."""
        result = self.create_lane("--plan", "docs/plans/gone.md", expect=2)

        self.assertIn("не існує в origin/main", result.stderr)
        self.assertIn("docs/plans/gone.md", result.stderr)
        self.assertFalse(self.lane_path().exists(), result.stdout)
        self.assertFalse(self.claim_path().exists(),
                         "резервування не сміє назвати неіснуючий план")

    def test_a_named_plan_that_exists_is_recorded_and_named(self):
        """Названий шлях, який справді є в базі, потрапляє в запис і у факти."""
        plan = "docs/plans/2026-01-02-existing-plan.md"
        (self.repo / "docs" / "plans").mkdir(parents=True, exist_ok=True)
        (self.repo / plan).write_text("# план\n", encoding="utf-8")
        self.git("add", "-A")
        self.commit("план існує в базі")
        self.git("push", "-q", "origin", "main")

        result = self.create_lane("--plan", plan)

        self.assertIn("план: %s (є в origin/main)" % plan, result.stdout)
        recorded = json.loads(self.claim_path().read_text(encoding="utf-8"))
        self.assertEqual(plan, recorded["plan"])

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


class LocalPropertiesTest(LaneTest):
    """Крок ручного рецепта, який загубився при автоматизації (#1332).

    `docs/agents/local-environment.md` описує створення смуги двома кроками:
    `git worktree add …` і `cp local.properties …/local.properties`.
    `new-lane.sh` (#1321) замінив рецепт — і не переніс другий крок. Файл у
    `.gitignore` (`.gitignore:19`), тож у копію сам не потрапляє, а без нього
    Android-збірка падає `SDK location not found` — смуга #1187 втратила через
    це прогін. Обидва боки перевіряються тут: файл є — перенесено й названо;
    файлу немає — не падає, і причина названа.
    """

    def test_an_existing_local_properties_is_carried_into_the_lane_and_named(self):
        contents = "sdk.dir=/opt/android-sdk\n"
        (self.repo / "local.properties").write_text(contents, encoding="utf-8")

        result = self.create_lane()

        self.assertEqual(
            contents,
            (self.lane_path() / "local.properties").read_text(encoding="utf-8"),
            result.stdout,
        )
        self.assertIn("local.properties: є — перенесено", result.stdout)
        self.assertIn(str(self.repo / "local.properties"), result.stdout)
        # Файл ігнорований, тож копія не виглядає брудною: `--release` зможе її
        # прибрати, а `git status` не бреше про неіснуючі правки.
        self.assertEqual("", self.git("status", "--porcelain",
                                      cwd=self.lane_path()).stdout)

    def test_a_copy_without_local_properties_names_why_android_cannot_build(self):
        """Файлу немає — НЕ відмова: названо, що збірка не працюватиме, і чому."""
        self.assertFalse((self.repo / "local.properties").exists())

        result = self.create_lane()

        self.assertIn("local.properties: НЕМА", result.stdout)
        self.assertIn(str(self.repo / "local.properties"), result.stdout)
        self.assertIn("SDK location not found", result.stdout)
        self.assertIn(".gitignore", result.stdout)
        self.assertTrue(self.lane_path().is_dir(),
                        "відсутній local.properties — не привід не створювати смугу")
        self.assertTrue(self.claim_path().is_file())
        self.assertFalse((self.lane_path() / "local.properties").exists())

    def test_a_lane_that_got_local_properties_is_still_releasable(self):
        """Ігнорований файл не робить копію «брудною»: `--release` її прибирає.

        `git worktree remove` без `--force` відмовляє на брудному дереві (це
        межа `--release`). Файл у `.gitignore` брудом не є — і ця перевірка
        тримає саме те: перенесений `local.properties` не робить копію
        вічною.
        """
        (self.repo / "local.properties").write_text("sdk.dir=/opt/android-sdk\n",
                                                    encoding="utf-8")
        self.create_lane()

        result = self.lane("--release", ISSUE)

        self.assertIn("копію прибрано", result.stdout)
        self.assertFalse(self.lane_path().exists())


class PremiseTest(LaneTest):
    """Премиса тікета перевіряється ДО роботи — і вердикт НАЗВАНО (#1324).

    Тікет старіє МІЖ написанням і створенням смуги (`empty_shape_mutants
    --list` у #1315 був 1, став 2; «`guard_witnesses` у main — 40» на базі було
    56). `issue_premise.py` це ловить — і до цього тікета його не кликав жоден
    крок. Тести нижче пінять саме крок: застарілий тікет мусить бути названий у
    фактах, а смуга — лишитися.
    """

    def test_a_fresh_ticket_is_named_as_the_premise_and_the_copy_is_made(self):
        result = self.create_lane()

        self.assertIn("премиса тікета: ПРЕМИСА Є", result.stdout)
        self.assertTrue(self.lane_path().is_dir(), result.stdout)
        self.assertTrue(self.claim_path().is_file())
        # Спитано наявним механізмом і саме про цей тікет.
        self.assertIn("issue view %s" % ISSUE, self.gh_calls())

    def test_a_stale_ticket_is_named_and_the_lane_is_still_created(self):
        """Червоний на СТАРІЙ поведінці: крок не кликав `issue_premise.py`.

        Шлях, названий у тілі, з `origin/main` зник (тікет застарів). Смуга
        мусить лишитися — застарілий тікет не привід не створювати її, — але
        названим мусить бути і вердикт, і сам шлях: інакше смуга починає роботу
        з хибного числа.
        """
        self.set_issue_body("Тікет посилається на `docs/gone-plan.md`.\n")

        result = self.create_lane()

        self.assertIn("премиса тікета: ВІДМОВА (код 1)", result.stdout)
        self.assertIn("docs/gone-plan.md", result.stdout)
        self.assertIn("issue_premise: ВІДМОВА", result.stdout)
        self.assertTrue(self.lane_path().is_dir(),
                        "смуга лишається: знахідка — попередження, а не відмова")
        self.assertTrue(self.claim_path().is_file())

    def test_a_ticket_without_paths_is_named_unknown_not_green(self):
        """«Нічого не перевірено» не дорівнює «все гаразд» — і це видно."""
        self.set_issue_body("Тікет про символи коду, без жодного шляху в бектиках.\n")

        result = self.create_lane()

        self.assertIn("премиса тікета: НЕВІДОМІСТЬ (код 2)", result.stdout)
        self.assertNotIn("премиса тікета: ПРЕМИСА Є", result.stdout)
        self.assertTrue(self.lane_path().is_dir())

    def test_an_unreadable_ticket_is_an_unknown_and_no_copy_is_made(self):
        """`gh` не відповів ⇒ код 2, і НІ копії, ні резервування, ні гілки.

        Порожнє ім'я тіла — це «тікет не читається»: вердикту немає взагалі, тож
        перевірити премису не вдалося. Це єдиний стан, у якому крок відмовляє:
        тут «не знаю» не можна прочитати на свою користь.
        """
        result = self.create_lane(expect=2, env={"NEW_LANE_TEST_ISSUE_BODY": ""})

        self.assertIn("не прочитано", result.stderr)
        self.assertIn("gh: тікет не читається", result.stderr,
                      "причина мусить бути ВИДНА, а не переказана")
        self.assertFalse(self.lane_path().exists(), result.stdout)
        self.assertFalse(self.claim_path().exists())
        self.assertNotEqual(
            0,
            self.git("show-ref", "--verify", "--quiet",
                     "refs/heads/t%s-%s" % (ISSUE, SLUG), check=False).returncode,
            "гілка невдалого прогону не сміє лишитися",
        )


class ReleaseTest(LaneTest):
    """Гігієна: копія прибирається, коли в ній нема чого втрачати."""

    def test_release_removes_an_empty_unregistered_directory(self):
        """Незареєстрована тека — не смуга: `git -C` у ній піднявся б до
        БАТЬКІВСЬКОГО репозиторію й прочитав дерево КООРДИНАТОРА (огляд #1321,
        F1). Тому така тека прибирається без жодного `git` у ній."""
        stray = self.repo / ".worktrees" / ("w%s" % ISSUE)
        stray.mkdir(parents=True)
        outsider = self.repo / "координаторське.txt"
        outsider.write_text("не чіпати\n", encoding="utf-8")

        result = self.lane("--release", ISSUE)

        self.assertIn("незареєстровану теку прибрано", result.stdout)
        self.assertFalse(stray.exists())
        self.assertTrue(outsider.is_file(),
                        "дерево координатора не можна прочитати як копію")
        self.assertNotIn("координаторське.txt", result.stdout + result.stderr)

    def test_release_clears_a_stale_worktree_record_under_a_recreated_directory(self):
        """Застарілий (`prunable`) запис + тека на тому ж шляху — залишок F1.

        `git worktree list` друкує такий запис як `prunable`, а `git -C` у теці
        на його шляху піднімається до БАТЬКІВСЬКОГО репозиторію. Якщо вірити
        запису, симптом F1 повертається: «копія має зміни» про дерево
        координатора й глухий кут для номера.
        """
        self.create_lane()
        shutil.rmtree(self.lane_path())
        self.lane_path().mkdir(parents=True)
        outsider = self.repo / "координаторське.txt"
        outsider.write_text("не чіпати\n", encoding="utf-8")

        result = self.lane("--release", ISSUE)

        self.assertIn("незареєстровану теку прибрано", result.stdout)
        self.assertFalse(self.lane_path().exists())
        self.assertNotIn(str(self.lane_path()),
                         self.git("worktree", "list", "--porcelain").stdout,
                         "застарілий запис мусить бути прибраний")
        self.assertTrue(outsider.is_file())
        self.assertNotIn("координаторське.txt", result.stdout + result.stderr)

    def test_release_refuses_a_non_empty_unregistered_directory(self):
        """Непорожню незареєстровану теку не чіпаємо: її вміст нам невідомий."""
        stray = self.repo / ".worktrees" / ("w%s" % ISSUE)
        stray.mkdir(parents=True)
        keepsake = stray / "чиєсь.txt"
        keepsake.write_text("невідоме\n", encoding="utf-8")

        result = self.lane("--release", ISSUE, expect=1)

        self.assertIn("не є чекаутом", result.stderr)
        self.assertTrue(keepsake.is_file())

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
