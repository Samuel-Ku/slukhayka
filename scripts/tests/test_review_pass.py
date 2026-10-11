"""Тести scripts/review-pass.sh: подія вердикту створюється завжди.

Стиль той самий, що в scripts/tests/test_merge_pr.py: скрипт запускається як
процес, а зовнішній інструмент підмінюється змінною (REVIEW_PASS_GH вказує на
підставний gh із FAKE_GH_DIR). Жоден тест не ходить у мережу і не чіпає
справжній репозиторій.

Підставний gh тут зі СТАНОМ, і це головне: він тримає мітки PR і таймлайн у
каталозі фікстур, а додавання мітки, яка вже стоїть, НЕ створює події `labeled`
— рівно та тиша, що коштувала координатору кола роботи (#1259). Тому тест на
вже позначеному PR — не «мітка на місці й добре»: він вимагає, щоб у таймлайні
побільшало `labeled`-подій і щоб надрукована позначка часу була НОВОЮ.

Два стани PR перевіряються явно: без мітки взагалі (перший вердикт; зняття
мітки віддає 404) і з міткою, яка вже стоїть (перепроставлення — саме воно й
ламалося).

Інтеграційний тест жене обидва скрипти на одному стані: гейт відмовляє на
старому вердикті («після вердикту огляду з'явилися нові коміти»), скрипт
перепроставляє вердикт, гейт зливає. Це те саме коло, яке #1259 коштував
координатору, — тільки пройдене до кінця.
"""

import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REVIEW_PASS = REPO_ROOT / "scripts" / "review-pass.sh"
MERGE_PR = REPO_ROOT / "scripts" / "merge-pr.sh"

HEAD = "735771adafeb47de3a990f881fc75c1cf84839a6"
HEAD_SHORT = HEAD[:7]
BRANCH = "t1205-player-rail"
LABEL = "review:pass"
# Старий вердикт: до нового коміта й до кінця прогонів CI (19:12:05).
STALE_VERDICT_AT = "2026-10-10T19:00:00Z"
# Час, який підставний gh ставить новим подіям: свіжий щодо всього нижче.
NOW = "2026-10-10T19:50:00Z"
# Новий коміт, що приїхав ПІСЛЯ старого вердикту — причина, з якої гейт відмовляє.
COMMIT_AT = "2026-10-10T19:30:00Z"
HEAD_HEADLINE = "test(snapshots): #1205 кадри межі 840 dp і смуги під рейлом"

GREEN_CHECKS = (
    ("Pure JVM", "SUCCESS", "2026-10-10T19:05:49Z", "2026-10-10T19:09:11Z"),
    ("Android Lint", "SUCCESS", "2026-10-10T19:05:41Z", "2026-10-10T19:12:05Z"),
)

# Підставний gh: стан PR у FAKE_GH_DIR, поведінка — справжнього GitHub там, де
# це важить для #1259. Режими відмов названі в docstring.
FAKE_GH = r'''#!/usr/bin/env python3
"""Підставний gh: тримає стан PR у каталозі FAKE_GH_DIR.

Додавання мітки, яка вже стоїть, НЕ створює події `labeled` — саме ця тиша й
коштувала координатору кола роботи. Зняття мітки створює `unlabeled`, а
додавання після зняття — `labeled` із часом FAKE_GH_NOW (типово — UTC зараз).

Режими для тестів відмов:
  FAKE_GH_LABEL_MODE=error — проба мітки в репозиторії падає не-404 помилкою;
  FAKE_GH_PR_MODE=empty    — `pr view` віддає порожньо;
  FAKE_GH_PR_MODE=garbage  — `pr view` віддає не-JSON;
  FAKE_GH_ADD_MODE=noop    — додавання мітки «вдається», але події не створює;
  FAKE_GH_ADD_MODE=error   — додавання мітки падає.
"""

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

DIRECTORY = Path(os.environ["FAKE_GH_DIR"])


def load(name, default):
    path = DIRECTORY / name
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def save(name, value):
    (DIRECTORY / name).write_text(json.dumps(value), encoding="utf-8")


def now():
    return os.environ.get("FAKE_GH_NOW") or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def fail(message):
    print("gh: %s" % message, file=sys.stderr)
    sys.exit(1)


def stamp(kind, name):
    return {"event": kind, "created_at": now(), "label": {"name": name}}


def add_label(name):
    labels = load("labels.json", [])
    if name in labels:
        # Тиша: справжній GitHub не створює `labeled`, коли мітка вже стоїть.
        return
    labels.append(name)
    save("labels.json", labels)
    events = load("timeline.json", [])
    events.append(stamp("labeled", name))
    save("timeline.json", events)


def remove_label(name):
    labels = load("labels.json", [])
    if name not in labels:
        fail("Not Found (HTTP 404)")
    labels.remove(name)
    save("labels.json", labels)
    events = load("timeline.json", [])
    events.append(stamp("unlabeled", name))
    save("timeline.json", events)


arguments = sys.argv[1:]
log = Path(os.environ["FAKE_GH_LOG"])
log.parent.mkdir(parents=True, exist_ok=True)
with log.open("a", encoding="utf-8") as handle:
    handle.write(" ".join(arguments) + "\n")

if arguments[:2] == ["pr", "view"]:
    mode = os.environ.get("FAKE_GH_PR_MODE", "")
    if mode == "empty":
        sys.exit(0)
    if mode == "garbage":
        print("це не JSON")
        sys.exit(0)
    payload = load("pr.json", {})
    payload["labels"] = [{"name": name} for name in load("labels.json", [])]
    print(json.dumps(payload))
    sys.exit(0)

if arguments[:2] == ["pr", "merge"]:
    print("✓ Squashed and merged pull request")
    sys.exit(0)

if arguments[:1] == ["api"]:
    method = "GET"
    path = ""
    added = ""
    previous = ""
    for argument in arguments:
        if argument.startswith("repos/"):
            path = argument
        if previous == "-X":
            method = argument
        if previous == "-f" and argument.startswith("labels[]="):
            added = argument[len("labels[]="):]
        previous = argument

    if path.endswith("/timeline"):
        print(json.dumps([load("timeline.json", [])]))
        sys.exit(0)
    if "check-runs" in path:
        print(json.dumps(load("check-runs.json", [])))
        sys.exit(0)
    if "/pulls/" in path and path.endswith("/commits"):
        print(json.dumps(load("commits.json", [])))
        sys.exit(0)
    if method == "POST" and path.endswith("/labels"):
        mode = os.environ.get("FAKE_GH_ADD_MODE", "")
        if mode == "error":
            fail("Validation Failed (HTTP 422)")
        if mode != "noop":
            add_label(added)
        print(json.dumps([{"name": name} for name in load("labels.json", [])]))
        sys.exit(0)
    if "/issues/" in path and "/labels/" in path:
        name = path.rsplit("/labels/", 1)[1]
        if method == "DELETE":
            remove_label(name)
            print("[]")
            sys.exit(0)
        fail("Not Found (HTTP 404)")
    if "/labels" in path:
        if os.environ.get("FAKE_GH_LABEL_MODE") == "error":
            fail("error connecting to api.github.com")
        if not (DIRECTORY / "label.json").exists():
            fail("Not Found (HTTP 404)")
        print(json.dumps(load("label.json", {})))
        sys.exit(0)
    print(json.dumps(load("repo.json", {})))
    sys.exit(0)

print("fake gh: несподіваний виклик: %s" % " ".join(arguments), file=sys.stderr)
sys.exit(3)
'''


def check(name, conclusion="SUCCESS", started="2026-10-10T19:05:41Z",
          completed="2026-10-10T19:12:05Z"):
    return {
        "__typename": "CheckRun",
        "name": name,
        "conclusion": conclusion,
        "status": "COMPLETED",
        "startedAt": started,
        "completedAt": completed,
        "detailsUrl": "https://example.invalid/checks/%s" % name.replace(" ", "-").lower(),
    }


def run(name, conclusion="success", started="2026-10-10T19:05:41Z",
        completed="2026-10-10T19:12:05Z"):
    return {
        "name": name,
        "status": "completed",
        "conclusion": conclusion,
        "started_at": started,
        "completed_at": completed,
        "html_url": "https://example.invalid/runs/%s" % name.replace(" ", "-").lower(),
    }


def pr_payload(*, head=HEAD, head_ref=BRANCH, state="OPEN"):
    """Те, що `gh pr view --json …` віддає merge-pr.sh (і те, що читає цей скрипт)."""
    return {
        "number": 1215,
        "state": state,
        "isDraft": False,
        "mergeable": "MERGEABLE",
        "mergeStateStatus": "CLEAN",
        "baseRefName": "main",
        "headRefName": head_ref,
        "headRefOid": head,
        "isCrossRepository": False,
        "url": "https://github.com/Samuel-Ku/slukhayka/pull/1215",
        "labels": [],
        "statusCheckRollup": [
            check(name, conclusion, started, completed)
            for name, conclusion, started, completed in GREEN_CHECKS
        ],
    }


def rest_commit(sha=HEAD, when=COMMIT_AT, headline=HEAD_HEADLINE):
    return {
        "sha": sha,
        "commit": {
            "message": headline + "\n\nтіло",
            "committer": {"date": when},
            "author": {"name": "Samuel-Ku", "email": "shtopor02@gmail.com"},
        },
        "author": {"login": "Samuel-Ku"},
    }


def labeled(when=STALE_VERDICT_AT, label=LABEL):
    return {"event": "labeled", "created_at": when, "label": {"name": label}}


def check_runs_page(*runs):
    return [{"total_count": len(runs), "check_runs": list(runs)}]


class ReviewPassTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.fixtures = self.root / "fixtures"
        self.fixtures.mkdir()
        self.log = self.root / "gh.log"
        self.gh = self.root / "gh"
        self.gh.write_text(FAKE_GH, encoding="utf-8")
        self.gh.chmod(self.gh.stat().st_mode | stat.S_IXUSR)

    def tearDown(self):
        self._tmp.cleanup()

    # --- фікстури -----------------------------------------------------------
    def write_fixtures(self, *, labels=(LABEL,), timeline=None, label_in_repo=True,
                       pr=None, commits=None):
        """Стан PR на диску: мітки, таймлайн і те, що читає гейт.

        Типово — PR #1215 із міткою, старою подією і зеленим CI: саме той стан,
        у якому перепроставлення й ламалося.
        """
        fixtures = {
            "pr.json": pr_payload() if pr is None else pr,
            "labels.json": list(labels),
            "timeline.json": [labeled()] if timeline is None else timeline,
            "commits.json": [[rest_commit()]] if commits is None else commits,
            "check-runs.json": check_runs_page(
                *[run(name, conclusion.lower(), started, completed)
                  for name, conclusion, started, completed in GREEN_CHECKS]),
            "repo.json": {"delete_branch_on_merge": False},
        }
        for name, value in fixtures.items():
            (self.fixtures / name).write_text(json.dumps(value), encoding="utf-8")
        label_file = self.fixtures / "label.json"
        if label_in_repo:
            label_file.write_text(json.dumps({"name": LABEL}), encoding="utf-8")
        else:
            label_file.unlink(missing_ok=True)

    def environment(self, extra=None):
        environment = os.environ.copy()
        environment.pop("REVIEW_PASS_LABEL", None)
        environment.pop("MERGE_PR_LABEL", None)
        environment.update({
            "REVIEW_PASS_GH": str(self.gh),
            "MERGE_PR_GH": str(self.gh),
            "FAKE_GH_DIR": str(self.fixtures),
            "FAKE_GH_LOG": str(self.log),
            "FAKE_GH_NOW": NOW,
        })
        environment.update(extra or {})
        return environment

    def review_pass(self, *args, env_extra=None):
        return subprocess.run(
            ["bash", str(REVIEW_PASS), *args], check=False, capture_output=True,
            text=True, env=self.environment(env_extra), cwd=str(REPO_ROOT),
        )

    def merge_pr(self, *args, repo):
        extra = {"MERGE_PR_REPO": str(repo)}
        return subprocess.run(
            ["bash", str(MERGE_PR), *args], check=False, capture_output=True,
            text=True, env=self.environment(extra), cwd=str(REPO_ROOT),
        )

    # --- читання стану ------------------------------------------------------
    def labels_on_pr(self):
        return json.loads((self.fixtures / "labels.json").read_text(encoding="utf-8"))

    def timeline(self):
        return json.loads((self.fixtures / "timeline.json").read_text(encoding="utf-8"))

    def labeled_events(self):
        return [event for event in self.timeline() if event.get("event") == "labeled"]

    def invocations(self):
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()

    def mutating_calls(self):
        return [line for line in self.invocations()
                if "-X DELETE" in line or "-X POST" in line]

    def merge_calls(self):
        return [line for line in self.invocations() if line.startswith("pr merge")]

    def make_git_repo(self):
        """Робоча копія для гейта: гілки PR у ній немає — це законний WARN."""
        repo = self.root / "repo"
        repo.mkdir()
        environment = os.environ.copy()
        environment.update({
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_AUTHOR_NAME": "Samuel-Ku",
            "GIT_AUTHOR_EMAIL": "shtopor02@gmail.com",
            "GIT_COMMITTER_NAME": "Samuel-Ku",
            "GIT_COMMITTER_EMAIL": "shtopor02@gmail.com",
        })

        def git(*args):
            return subprocess.run(["git", *args], cwd=str(repo), check=True,
                                  capture_output=True, text=True, env=environment)

        git("init", "-q", "-b", "main")
        (repo / "README.md").write_text("base\n", encoding="utf-8")
        git("add", "-A")
        git("commit", "-q", "-m", "chore: основа")
        return repo

    # --- головний стан: PR уже має мітку ------------------------------------
    def test_reissues_the_verdict_on_a_pr_that_already_has_the_label(self):
        """Той самий стан, що коштував кола: мітка стоїть, подія — стара.

        `gh pr edit --add-label` тут — тиха ні-операція. Скрипт мусить створити
        НОВУ подію: у таймлайні labeled-подій стає на одну більше, а надрукована
        позначка часу — не стара.
        """
        self.write_fixtures(labels=(LABEL,), timeline=[labeled(STALE_VERDICT_AT)])

        result = self.review_pass("1215")

        self.assertEqual(0, result.returncode, result.stderr)
        events = self.labeled_events()
        self.assertEqual(2, len(events), events)
        self.assertEqual(STALE_VERDICT_AT, events[0]["created_at"])
        self.assertEqual(NOW, events[-1]["created_at"])
        self.assertIn(NOW, result.stdout)  # нова позначка часу, а не стара
        self.assertNotIn(STALE_VERDICT_AT, result.stdout)
        self.assertIn("HEAD %s" % HEAD_SHORT, result.stdout)
        self.assertIn(BRANCH, result.stdout)
        self.assertIn(LABEL, self.labels_on_pr())
        # Порядок — частина механізму: подія створюється лише після зняття.
        calls = self.invocations()
        delete = [index for index, line in enumerate(calls) if "-X DELETE" in line]
        post = [index for index, line in enumerate(calls) if "-X POST" in line]
        self.assertEqual(1, len(delete), calls)
        self.assertEqual(1, len(post), calls)
        self.assertLess(delete[0], post[0])

    # --- другий стан: PR без мітки ------------------------------------------
    def test_sets_the_verdict_on_a_pr_that_has_no_label(self):
        """Перший вердикт: зняття мітки віддає 404 — це не помилка."""
        self.write_fixtures(labels=(), timeline=[])

        result = self.review_pass("1215")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([LABEL], self.labels_on_pr())
        events = self.labeled_events()
        self.assertEqual(1, len(events), events)
        self.assertEqual(NOW, events[0]["created_at"])
        self.assertIn(NOW, result.stdout)
        self.assertIn("HEAD %s" % HEAD_SHORT, result.stdout)

    # --- відмови ------------------------------------------------------------
    def test_refuses_when_the_label_is_missing_from_the_repository(self):
        self.write_fixtures(label_in_repo=False)

        result = self.review_pass("1215")

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("немає в репозиторії", result.stderr)
        self.assertIn("gh label create 'review:pass'", result.stderr)
        self.assertEqual([], self.mutating_calls())  # PR не змінено

    def test_fails_closed_when_the_label_probe_fails(self):
        """Не-404 відмова проби — невідомість, а не «мітки немає»."""
        self.write_fixtures()

        result = self.review_pass("1215", env_extra={"FAKE_GH_LABEL_MODE": "error"})

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("не вдалося перевірити, чи існує мітка", result.stderr)
        self.assertNotIn("немає в репозиторії", result.stderr)
        self.assertEqual([], self.mutating_calls())

    def test_refuses_when_the_add_creates_no_event(self):
        """Тиша, заради якої скрипт і написано: мітка є, події немає.

        Підставний gh тут поводиться як GitHub на вже позначеному PR: POST
        «вдається», а `labeled` не створюється. Скрипт мусить це ПОМІТИТИ й
        відмовити, а не надрукувати стару позначку часу як свіжу.
        """
        self.write_fixtures(labels=(LABEL,), timeline=[labeled(STALE_VERDICT_AT)])

        result = self.review_pass("1215", env_extra={"FAKE_GH_ADD_MODE": "noop"})

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("labeled-подій не побільшало", result.stderr)
        self.assertIn("1 → 1", result.stderr)
        self.assertNotIn("перепроставлено", result.stdout)

    def test_fails_closed_when_the_add_fails_after_the_remove(self):
        """Зняли й не поставили — PR лишився без вердикту, і це сказано прямо."""
        self.write_fixtures(labels=(LABEL,), timeline=[labeled(STALE_VERDICT_AT)])

        result = self.review_pass("1215", env_extra={"FAKE_GH_ADD_MODE": "error"})

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("лишився БЕЗ вердикту", result.stderr)
        self.assertNotIn(LABEL, self.labels_on_pr())

    def test_fails_closed_on_an_empty_or_unparsable_pr_response(self):
        """Без HEAD вердикт засвідчував би невідомо що — код 2, не «зроблено»."""
        for mode, expected in (("empty", "порожня відповідь"), ("garbage", "розібрати відповідь")):
            with self.subTest(mode=mode):
                self.write_fixtures()
                self.log.unlink(missing_ok=True)

                result = self.review_pass("1215", env_extra={"FAKE_GH_PR_MODE": mode})

                self.assertEqual(2, result.returncode, result.stdout)
                self.assertIn(expected, result.stderr)
                self.assertEqual([], self.mutating_calls())

    def test_fails_closed_on_an_unexpected_timeline_page(self):
        """Сторінка-об'єкт у таймлайні — не «подій немає», а форма, якої API не віддає.

        Ковтнути її означало б порахувати нуль і на цьому «довести» тишу —
        рівно та діра, що вже була в merge-pr.sh (#1233).
        """
        self.write_fixtures(timeline={"events": []})

        result = self.review_pass("1215")

        self.assertEqual(2, result.returncode, result.stdout)
        self.assertIn("не вдалося розібрати таймлайн", result.stderr)
        self.assertEqual([], self.mutating_calls())

    def test_fails_closed_when_gh_is_missing(self):
        result = self.review_pass("1215", env_extra={"REVIEW_PASS_GH": str(self.root / "немає")})

        self.assertEqual(2, result.returncode)
        self.assertIn("gh не знайдено", result.stderr)

    def test_rejects_bad_usage(self):
        cases = (
            ({}, []),
            ({}, ["abc"]),
            ({}, ["12", "13"]),
            ({}, ["12", "--bogus"]),
            # Мітка йде в шлях REST-запиту: складне ім'я не підтримуємо свідомо.
            ({"REVIEW_PASS_LABEL": "review pass"}, ["12"]),
        )
        for extra, args in cases:
            with self.subTest(args=args, extra=extra):
                result = self.review_pass(*args, env_extra=extra)
                self.assertEqual(2, result.returncode, result.stdout)
                self.assertEqual([], self.invocations())

    def test_help_exits_zero_without_calling_gh(self):
        result = self.review_pass("--help")

        self.assertEqual(0, result.returncode)
        self.assertIn("usage: scripts/review-pass.sh", result.stdout)
        self.assertEqual([], self.invocations())

    # --- інтеграція з гейтом ------------------------------------------------
    def test_the_merge_gate_accepts_the_reissued_verdict(self):
        """Коло #1259 до кінця: гейт відмовляє — скрипт перепроставляє — гейт зливає."""
        self.write_fixtures(labels=(LABEL,), timeline=[labeled(STALE_VERDICT_AT)],
                            commits=[[rest_commit(when=COMMIT_AT)]])
        repo = self.make_git_repo()

        refused = self.merge_pr("1215", repo=repo)

        self.assertEqual(1, refused.returncode, refused.stdout)
        self.assertIn("нові коміти", refused.stderr)
        self.assertEqual([], self.merge_calls())

        reissued = self.review_pass("1215")

        self.assertEqual(0, reissued.returncode, reissued.stderr)

        merged = self.merge_pr("1215", repo=repo)

        self.assertEqual(0, merged.returncode, merged.stderr)
        self.assertEqual(
            ["pr merge 1215 --squash --match-head-commit %s" % HEAD], self.merge_calls())


if __name__ == "__main__":
    unittest.main()
