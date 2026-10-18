import json
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
BUILD_PROMPT = REPO_ROOT / "scripts" / "swarm" / "build-prompt.sh"

ISSUE = {
    "number": 4242,
    "title": "Проба сварму: контракт задачі",
    "body": "Тіло тікета з деталями.\nДругий рядок.",
    "labels": [{"name": "ready-for-agent"}],
    "state": "OPEN",
}

BASE = "d5e8a9b2bc31177037b4037632b7cc2c6395e3bb"


def build(args, payload=None, text=True):
    return subprocess.run(
        ["bash", str(BUILD_PROMPT), *args],
        input=(json.dumps(payload) if payload is not None else ""),
        capture_output=True,
        text=text,
        cwd=REPO_ROOT,
    )


class SwarmPromptContractTest(unittest.TestCase):
    def test_prompt_carries_ticket_base_and_branch(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe",
             "--base", BASE, "--owner", "swarm-hub", "--timeout-min", "90"],
            ISSUE,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        prompt = result.stdout

        # Тікет і база мусять бути в тексті, а не «згадані» деінде.
        self.assertIn("ТІКЕТ #4242 — Проба сварму: контракт задачі", prompt)
        self.assertIn("Тіло тікета з деталями.", prompt)
        self.assertIn("origin/main d5e8a9b2bc31", prompt)
        self.assertIn("worker-a", prompt)

    def test_prompt_names_the_lane_command_and_the_only_branch_to_push(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("NEW_LANE_OWNER=swarm-hub scripts/new-lane.sh 4242 probe", prompt)
        self.assertIn("git push -u origin t4242-probe", prompt)
        self.assertIn(".worktrees/w4242", prompt)

    def test_verification_gate_is_mandatory_and_shared_files_are_named(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("scripts/test-changed.sh", prompt)
        # Спільний файл у промпті — не порада: його зміна переводить вибір тестів
        # у повний набір, а трьом смугам там нема чого робити.
        for shared in ("build.gradle.kts", "gradle/", "scripts/", ".github/", "app/schemas/"):
            self.assertIn(shared, prompt)
        self.assertIn("Емулятор і instrumented-тести тут недоступні", prompt)

    def test_opening_the_pr_is_reserved_for_the_hub(self):
        # У пілоті воркер відкрив PR сам (#1345) — річ корисна, але це другий
        # опис тієї самої роботи, якого координатор не перевіряв. Контракт має
        # це називати, а не лишати на здогад.
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        self.assertIn("НЕ відкривай PR", result.stdout)

    def test_prompt_names_only_files_that_exist_in_the_repo(self):
        # На #1286 воркер шукав GLOSSARY.md і CODING_STANDARDS.md, яких у
        # origin/main немає (реальні — CONTEXT.md і CONTRIBUTING.md), і витратив
        # на це час. Контракт має називати наявні файли і попереджати про решту.
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("CONTEXT.md", prompt)
        self.assertIn("CONTRIBUTING.md", prompt)
        self.assertIn("НЕМАЄ", prompt)

    def test_autonomy_rule_is_stated_because_there_is_no_answerer(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("ask_user_question", prompt)
        self.assertIn("розв'язуєш сам", prompt)

    def test_result_marker_and_timeout_are_machine_readable(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE,
             "--timeout-min", "45"],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("SWARM-RESULT: ok | ok-partial | blocked", prompt)
        self.assertIn("45 хв", prompt)

    def test_repo_guards_are_named_for_snapshot_and_string_diffs(self):
        # На #1163 звужений локальний прогін був зелений, а CI упав на
        # SnapshotGoldenBytesGuardTest: сторож пінить байти дев'яти еталонів і в
        # звужений вибір не потрапляє. Контракт має називати його прямо.
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn("SnapshotGoldenBytesGuardTest", prompt)
        self.assertIn("app/src/test/snapshots/**", prompt)
        self.assertIn("класи-сторожі", prompt)

    def test_slot_wrapper_gap_is_named_instead_of_improvised(self):
        # `.worktrees/gt` — машино-локальна обгортка, якої немає в свіжому клоні
        # воркера; без названого обходу агент вигадує власний слот або біжить
        # голим ./gradlew (у репо це заборонено: гонка за демон і OOM).
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            ISSUE,
        )
        prompt = result.stdout
        self.assertIn(".worktrees/gt", prompt)
        self.assertIn("scripts/build-slot-guard.py --floor-mb 2000", prompt)
        self.assertIn("flock -w 7200 .worktrees/.build-slot", prompt)

    def test_worker_key_and_github_key_are_separate(self):
        # Новий ключ `stealth@omarchy` не зареєстрований на GitHub: підмінити ним
        # git означало б зламати fetch/push. Тому раннер мусить тримати ДВА
        # ключі — свій для воркера і ключ акаунта для GitHub.
        script = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text()
        self.assertIn("SWARM_SSH_KEY", script)
        self.assertIn("SWARM_GIT_SSH_KEY", script)
        self.assertIn("id_ed25519_omarchy", script)

    def test_runner_has_a_backstop_that_kills_the_remote_group(self):
        # Дослід на воркері: локальний кеп гасить лише ssh, а віддалений `dsh`
        # і його діти живуть далі й палять токени. Тому раннер мусить (1) лишати
        # на воркері PID-файл свого запуску, (2) робити `exec`, щоб цей PID був
        # PID-ом групи, і (3) гасити групу явно після запуску. Це перевіряється
        # структурно: без мережі, gh і ssh.
        script = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text()
        self.assertIn(".swarm-jobs/", script)
        self.assertIn("echo $$ >", script)
        self.assertIn("exec nice", script)
        self.assertIn("kill -TERM -", script)
        self.assertIn("kill -KILL -", script)
        self.assertIn("backstop", script)
        # Прибирання не має чіпати чужий процес, якщо PID устиг перевикористатись.
        self.assertIn("/proc/", script)
        self.assertIn("не наш", script)

    def test_missing_required_option_refuses(self):
        result = build(["--issue", "4242", "--worker", "worker-a", "--base", BASE], ISSUE)
        self.assertEqual(result.returncode, 2)
        self.assertIn("missing", result.stderr)

    def test_stdin_without_title_refuses(self):
        result = build(
            ["--issue", "4242", "--worker", "worker-a", "--slug", "probe", "--base", BASE],
            {"number": 4242},
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("not an issue JSON", result.stderr)


if __name__ == "__main__":
    unittest.main()
