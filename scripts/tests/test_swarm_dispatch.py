import contextlib
import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time
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
        # Пінимо САМ РЯДОК читання, а не наявність підрядків деінде: огляд #1351
        # показав, що самосуперечний контракт («прочитай CONTEXT.md» + «прочитай
        # GLOSSARY.md», поруч із попередженням) проходив зеленим. Рядок, який
        # виконавець читає першим, не має називати файлів, яких у репо немає.
        reading = [l for l in prompt.splitlines() if l.strip().startswith("2. Прочитай")]
        self.assertEqual(1, len(reading), prompt)
        self.assertIn("CONTEXT.md", reading[0])
        self.assertNotIn("GLOSSARY.md", reading[0])
        self.assertNotIn("CODING_STANDARDS.md", reading[0])

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
        self.assertIn("scripts/build-slot-guard.py", prompt)
        self.assertIn("--floor-mb 3500", prompt)
        self.assertIn("$REPO_DIR/.worktrees/.build-slot", prompt)
        # cwd мусить бути КОПІЯ СМУГИ: `scripts/test-changed.sh` визначає корінь
        # репо від власного шляху, тож копія з клона аналізувала б незмінений
        # main і «зелений цільовий прогін» нічого не доводив (огляд #1351, п.2).
        self.assertIn("cd \"$REPO_DIR/.worktrees/w4242\"", prompt)

    def test_worker_key_and_github_key_are_separate(self):
        # Новий ключ `stealth@omarchy` не зареєстрований на GitHub: підмінити ним
        # git означало б зламати fetch/push. Тому раннер мусить тримати ДВА
        # ключі — свій для воркера і ключ акаунта для GitHub. Перевіряємо саме
        # ПРОВОДКУ: обмін ключів місцями має валити тест, а не проходити зеленим.
        script = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text()
        self.assertIn('-i "$SWARM_SSH_KEY"', script)
        self.assertIn("-i $SWARM_GIT_SSH_KEY", script)
        self.assertIn("id_ed25519_omarchy", script)
        # SSH_BASE — масив для ssh до воркера; GIT_SSH_COMMAND — рядок для git.
        ssh_base = script.split("SSH_BASE=(", 1)[1].split(")", 1)[0]
        self.assertNotIn("GIT_SSH_KEY", ssh_base)
        git_line = [l for l in script.splitlines() if l.startswith("export GIT_SSH_COMMAND")][0]
        self.assertNotIn("SWARM_SSH_KEY", git_line)

    def test_a_failed_dispatch_releases_its_reservation(self):
        # Резервування, яке ніщо не знімає, назавжди блокує тікет: у пілоті
        # невдала диспетчеризація #1286 лишила запис, і повторний запуск упав на
        # власній перевірці «уже зарезервований». Тому невдача мусить звільняти
        # запис — і ЛИШЕ свій.
        script = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text()
        self.assertIn("release_claim()", script)
        self.assertIn('.ownerThread == $o and .reservation == $r', script)
        # Звільнення висить на EXIT-пастці, бо hub, убитий сигналом посеред
        # диспетчу, до хвоста скрипта не дійде (огляд #1351, п.1). Змінні на той
        # момент можуть бути не встановлені — тому `${RC:-1}` і `${BRANCH_PRESENT:-0}`.
        trap = script.split('trap \'kill "$HB_PID"', 1)[1].split("' EXIT", 1)[0]
        self.assertIn("release_claim", trap)
        self.assertIn("${RC:-1}", trap)
        self.assertIn("${BRANCH_PRESENT:-0}", trap)
        # Адреса реєстру — спільний каталог через git, а не $REPO_ROOT/.git:
        # у звʼязаній смузі .git це файл (огляд #1351, п.4).
        self.assertIn("--path-format=absolute --git-common-dir", script)
        # `--plan` не передається: бриф не існує в origin/main, і запис мусить
        # лишатись `null` (огляд #1351, п.7 — цю мутацію ніщо не ловило).
        self.assertNotIn('--plan "$PROMPT"', script)
        self.assertNotIn("--plan ", script.split("agent-work-claim.py acquire", 1)[1].split("\n", 2)[1])

    def test_the_runner_actually_invokes_the_backstop(self):
        # Слабший варіант («рядок на верхньому рівні») не ловив навіть власну
        # мутацію: `if false; then` не зсуває рядок. Тому ВИКОНУЄМО хвіст
        # раннера (від кроку backstop до кінця) зі stub-`ssh`, який лишає слід, і
        # перевіряємо, що виклик СТАВСЯ. Загорнутий в умову — сліду не буде.
        script = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text()
        tail = "# --- 4a. backstop" + script.split("# --- 4a. backstop", 1)[1]
        harness = "\n".join([
            "set -u",
            "SSH_BASE=(-F /dev/null); WORKER=stub; REMOTE_STOP='echo ok'",
            "ISSUE=4242; SLUG=probe; LOG=/dev/null; RC=0; RESULT_LINE='SWARM-RESULT: ok'",
            "BRANCH_INFO=x; BRANCH_PRESENT=1; HB_PID=0",
            "say(){ :; }; release_claim(){ :; }; gh(){ :; }; git(){ return 1; }",
            'ssh(){ printf "%s\\n" "$*" >> "$MARKER"; }',
            tail,
        ])
        with tempfile.TemporaryDirectory() as tmp:
            marker = pathlib.Path(tmp) / "ssh-called"
            subprocess.run(["bash", "-s"], input=harness, text=True, check=False,
                           env=dict(os.environ, MARKER=str(marker)),
                           capture_output=True)
            self.assertTrue(marker.exists(),
                            "раннер не викликав backstop (вимкнено умовою?)")
            # stub друкує РОЗКРИТІ аргументи: значення REMOTE_STOP — 'echo ok'.
            self.assertIn("echo ok", marker.read_text())

    def test_backstop_really_kills_its_group_and_spares_a_foreign_one(self):
        # Стрічкові перевірки тут нічого не доводять: мутація «обгорнути backstop
        # у `if false`» лишала ВЕСЬ набір зеленим (перевірено оглядом #1351).
        # Тому виконуємо сам фрагмент `REMOTE_STOP`, як його друкує dry-run, на
        # справжній групі процесів — і перевіряємо обидва боки: свою групу
        # гасить, чужу (cmdline без `dsh`) не чіпає.
        # Фрагмент беремо З ДЖЕРЕЛА, а не з `--dry-run`: dry-run у CI вимагав би
        # мережі та `gh`, а цей набір мусить бігти офлайн. Виконуємо те саме
        # присвоєння, яке робить раннер, із підставленим тегом.
        lines = (REPO_ROOT / "scripts" / "swarm" / "run-issue.sh").read_text().splitlines()
        start = next(i for i, l in enumerate(lines) if l.startswith("REMOTE_STOP="))
        end = start
        while not lines[end].rstrip().endswith("fi'"):
            end += 1
        block = "\n".join(lines[start:end + 1])
        snippet = subprocess.run(
            ["bash", "-c", 'JOB_TAG=4242-worker-a\n%s\nprintf %%s "$REMOTE_STOP"' % block],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertIn("kill -TERM", snippet)

        def decoy(argv0):
            proc = subprocess.Popen(
                ["bash", "-c", 'exec -a %s sleep 300' % argv0],
                start_new_session=True,  # PGID == PID, як у `exec` віддаленого запуску
            )
            return proc

        with tempfile.TemporaryDirectory() as tmp:
            home = pathlib.Path(tmp) / "home"
            (home / ".swarm-jobs").mkdir(parents=True)
            env = dict(os.environ, HOME=str(home))

            ours = decoy("dsh")
            foreign = decoy("notdsh")
            (home / ".swarm-jobs" / "4242-worker-a.pid").write_text(str(ours.pid) + "\n")
            try:
                proc = subprocess.run(["bash", "-c", snippet], env=env,
                                      capture_output=True, text=True, check=False)
                self.assertIn("swarm-stop", proc.stdout + proc.stderr)
                # Наша група мусить зникнути.
                deadline = time.time() + 5
                while time.time() < deadline and ours.poll() is None:
                    time.sleep(0.1)
                self.assertIsNotNone(ours.poll(), "backstop не погасив СВОЮ групу")
                # Чужа — мусить лишитись життєздатною (перевірка «не наш»).
                self.assertIsNone(foreign.poll(), "backstop зачепив чужий процес")
            finally:
                for p in (ours, foreign):
                    with contextlib.suppress(ProcessLookupError, PermissionError):
                        os.killpg(os.getpgid(p.pid), signal.SIGKILL)
                    with contextlib.suppress(subprocess.TimeoutExpired):
                        p.wait(timeout=5)

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
