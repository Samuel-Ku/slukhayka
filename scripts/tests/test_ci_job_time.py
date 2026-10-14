"""Час джоби міряється там, де ліміт діє (#1299) — і тут це доводиться прогоном.

`scripts/ci_job_time.py` читає ліміт, імʼя джоби й команду з `ci.yml`, а час — з
`gh api`. Мережі в тестах немає, і це не компроміс: підставний `gh` (той самий
прийом, що підставний `adb` у `test_wide_window.py`) віддає фікстуру
`workflow_runs` і `jobs`, тож міряється САМЕ ЧИТАННЯ двох полів API, а не те, що
GitHub сьогодні відповідає. Справжнє дерево перевіряється лише там, де воно й
потрібне: ліміт мусить бути 300 с із `ci.yml`, а кожен зарахований рядок у
документах — виводитися.

Що тут піниться:

  * `LimitTest` — ліміт, імʼя й команда джоби ВИВОДЯТЬСЯ з блоку `script-tests`,
    а не написані в механізмі: підставлене дерево з іншим `timeout-minutes`
    рухає число, не чіпаючи код;
  * `MeasurementTest` — секунди, запас і відношення; вікно бере рівно завершені
    прогони, а підлога запасу червоніє на повільному прогоні;
  * `RefusalTest` — невідомість коштує код 2, а не «нуль»: немає `gh`, немає
    завершених прогонів, джоби немає в прогоні, `gh` упав, дві джоби з тим імʼям,
    ліміту немає;
  * `ClaimTest` — числа в документах: ліміт приймається, локальне число — лише
    ПОЗНАЧЕНЕ разом із навантаженням машини, а заморожене число джоби — знахідка;
  * `RepositoryTest` — справжнє дерево: ліміт 300 с, і жодного числа, яке не
    виводиться.

Зарахований у `scripts/guard_witnesses.py` — один сторож на кожну половину
механізму (`LimitTest`, `MeasurementTest`, `RefusalTest`, `ClaimTest`); решта
перевіряють сам механізм і в реєстрі не стоять. Реєстр покриває НАЗВАНИХ, а не
всіх.
"""

import contextlib
import io
import os
import shutil
import stat
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "ci_job_time.py"
sys.path.insert(0, str(MECHANISM.parent))

import ci_job_time as c  # noqa: E402

# Друге джерело правди: вікно, підлога й зараховані рядки — записані тут
# буквально. Звузити їх мовчки не можна: це видно як правку тесту.
EXPECTED_CLAIMS = (
    ("docs/agents/local-environment.md", "is capped by"),
    ("docs/agents/local-environment.md", "shared machine under load"),
    ("docs/agents/local-environment.md", "locally under load"),
    ("CONTRIBUTING.md", "ліміт тієї ж джоби"),
)
EXPECTED_WINDOW = 5
EXPECTED_RATIO = 2
EXPECTED_LIMIT = 300
EXPECTED_COMMAND = ("python3", "-m", "unittest", "discover", "-s", "scripts/tests",
                    "-p", "test_*.py")

# Рядки фікстури: перший несе ліміт, другий — локальний вимір із навантаженням.
# Обидва зараховані в `CLAIMS`, тож фікстура мусить мати обидва якорі.
LIMIT_LINE = "`script-tests` is capped by `timeout-minutes: 5` — 300 s.\n"
LOCAL_LINE = "A local run on this shared machine under load 5.4 took 176 s.\n"
HISTORIC_LINE = ("The series measured 159–239 s locally under load 5.4 on this "
                 "shared machine.\n")
REST_LINES = LOCAL_LINE + HISTORIC_LINE
CONTRIBUTING_LINE = "І ліміт тієї ж джоби — 300 с (`timeout-minutes` із `ci.yml`).\n"


def run(argv):
    """`main` із перехопленим виводом: (код, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = c.main(argv)
    return code, out.getvalue(), err.getvalue()


def environment(**overrides):
    """Оточення прогону — своє, а не успадковане (#1265).

    CI-раннер приносить у процес власні змінні, і серед них може бути
    експортована функція (`BASH_FUNC_cat%%`), яка затіняє команду ВСЕРЕДИНІ
    підставного `gh`. Тому підставний `gh` знімає такі функції сам, а PATH тут
    задається явно: підставлена тека першою.
    """
    base = {"PATH": "/usr/bin:/bin", "HOME": str(REPO_ROOT), "LC_ALL": "C", "LANG": "C"}
    base.update(overrides)
    return base


def workflow_text(minutes: str | None = "5", name: str = "Script unit tests",
                  run: str | None = 'run: python3 -m unittest discover -s scripts/tests '
                                     '-p "test_*.py"') -> str:
    """Мінімальний воркфлоу з тим самим розкладом відступів, що `ci.yml`."""
    body = (
        "  script-tests:\n"
        "    name: %s\n"
        "    runs-on: ubuntu-latest\n"
        "%s"
        "\n"
        "    steps:\n"
        "      - name: Checkout\n"
        "        uses: actions/checkout@v5\n"
        "\n"
        "      - name: Run the script unit tests\n"
        "        %s\n"
        % (name, "    timeout-minutes: %s\n" % minutes if minutes else "",
           run or "uses: actions/checkout@v5")
    )
    return "name: CI\n\non: [push]\n\njobs:\n" + body + "\n  timing-start:\n    name: x\n"


class Fixture(unittest.TestCase):
    """Підставлене дерево й підставний `gh` — рівно те, що механізм читає."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ci-job-time-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tree = self.root / "tree"
        (self.tree / ".github" / "workflows").mkdir(parents=True)
        (self.tree / "docs" / "agents").mkdir(parents=True)
        self.workflow = self.tree / ".github" / "workflows" / "ci.yml"
        self.write_workflow()
        self.claim_file = "docs/agents/local-environment.md"
        self.write_claim()
        (self.tree / "CONTRIBUTING.md").write_text(CONTRIBUTING_LINE, encoding="utf-8")

        self.fixtures = self.root / "gh"
        self.fixtures.mkdir()
        self.log = self.root / "gh.log"
        self.empty = self.root / "empty"
        self.empty.mkdir()
        self.gh = self.root / "bin" / "gh"
        self.gh.parent.mkdir()
        self.write_gh()

    # -- дерево ------------------------------------------------------------

    def write_workflow(self, text: str | None = None) -> None:
        self.workflow.write_text(text if text is not None else workflow_text(),
                                 encoding="utf-8")

    def write_claim(self, limit: str = LIMIT_LINE, rest: str = REST_LINES) -> None:
        """Рядки зарахованого файлу: ліміт і два локальні — окремо від нього."""
        (self.tree / self.claim_file).write_text(limit + rest, encoding="utf-8")

    # -- підставний gh -----------------------------------------------------

    def write_gh(self) -> None:
        self.gh.write_text(
            "#!/usr/bin/env bash\n"
            # Експортовані функції раннера (`BASH_FUNC_cat%%`) затіняють команди
            # всередині підставного інструмента — цей рядок знімає саме їх.
            "unset -f cat printf 2>/dev/null || true\n"
            'printf \'%s\\n\' "$*" >> "$FAKE_GH_LOG"\n'
            'args=("$@")\n'
            'if [[ ${args[0]:-} == api ]]; then args=("${args[@]:1}"); fi\n'
            'endpoint="${args[*]}"\n'
            'case "$endpoint" in\n'
            "  */actions/workflows/*/runs*)\n"
            '    if [[ -f "$FAKE_GH_FIXTURES/fail" ]]; then\n'
            "      printf 'gh: Bad credentials\\n' >&2; exit 1\n"
            "    fi\n"
            '    cat "$FAKE_GH_FIXTURES/runs.json" ;;\n'
            "  */actions/runs/*/jobs*)\n"
            '    rest=${endpoint##*/actions/runs/}\n'
            '    id=${rest%%/*}\n'
            '    cat "$FAKE_GH_FIXTURES/jobs-$id.json" ;;\n'
            "  *) printf 'unexpected gh call: %s\\n' \"$endpoint\" >&2; exit 2 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        self.gh.chmod(self.gh.stat().st_mode | stat.S_IXUSR)

    def write_runs(self, *runs: tuple[str, str]) -> None:
        """Прогони фікстури: (id, status) — у тому порядку, як їх віддає API."""
        entries = ", ".join(
            '{"id": %s, "status": "%s", "created_at": "2026-10-14T0%s:00:00Z"}'
            % (run_id, status, index)
            for index, (run_id, status) in enumerate(runs)
        )
        (self.fixtures / "runs.json").write_text('{"workflow_runs": [%s]}' % entries,
                                                encoding="utf-8")

    def write_jobs(self, run_id: str, start: str, finish: str,
                   name: str = "Script unit tests", count: int = 1) -> None:
        jobs = ", ".join(
            '{"name": "%s", "started_at": "%s", "completed_at": "%s"}'
            % (name, start, finish)
            for _ in range(count)
        )
        (self.fixtures / ("jobs-%s.json" % run_id)).write_text(
            '{"jobs": [%s]}' % jobs, encoding="utf-8"
        )

    def patched(self, path: str):
        return unittest.mock.patch.dict(os.environ, environment(
            PATH=path, FAKE_GH_LOG=str(self.log), FAKE_GH_FIXTURES=str(self.fixtures),
        ))

    def check(self, *extra: str):
        """Прогін механізму на підставленому дереві з підставним `gh`."""
        with self.patched("%s:/usr/bin:/bin" % self.gh.parent):
            return run(["--root", str(self.tree), "--repo", "owner/name", *extra])

    def check_without_gh(self, *extra: str):
        """Той самий прогін, але `gh` у PATH немає зовсім."""
        with self.patched(str(self.empty)):
            return run(["--root", str(self.tree), "--repo", "owner/name", *extra])

    def calls(self) -> list[str]:
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()


# --------------------------------------------------------------------------
# Ліміт, імʼя й команда — із блоку джоби
# --------------------------------------------------------------------------


class LimitTest(Fixture):
    """Ліміт виводиться з `ci.yml`: підставлений ліміт рухає число."""

    def test_the_real_tree_reads_the_limit_the_name_and_the_command(self):
        """Справжнє дерево: 300 с — це `timeout-minutes: 5`, прочитані з `ci.yml`."""
        self.assertEqual(EXPECTED_LIMIT, c.limit_seconds(REPO_ROOT))
        self.assertEqual("Script unit tests", c.job_name(REPO_ROOT))
        self.assertEqual(EXPECTED_COMMAND, c.local_command(REPO_ROOT))

    def test_a_changed_limit_moves_the_number_without_touching_the_mechanism(self):
        """Сторож: ліміт читається, а не написаний у механізмі.

        Підставлене дерево з `timeout-minutes: 9` мусить дати 540 с. Механізм,
        який знає «300» сам, тут зелений не буде — і саме тому число в доку
        виводиться, а не тримається на слові.
        """
        self.write_workflow(workflow_text(minutes="9"))
        self.assertEqual(540, c.limit_seconds(self.tree))

    def test_a_block_without_a_timeout_is_an_unknown(self):
        self.write_workflow(workflow_text(minutes=None))
        with self.assertRaises(c.JobTimeError) as caught:
            c.limit_seconds(self.tree)
        self.assertIn("timeout-minutes", str(caught.exception))

    def test_a_job_that_is_not_there_is_an_unknown(self):
        self.write_workflow("name: CI\n\njobs:\n  web:\n    name: Web\n")
        with self.assertRaises(c.JobTimeError) as caught:
            c.limit_seconds(self.tree)
        self.assertIn("script-tests", str(caught.exception))

    def test_two_run_steps_are_an_unknown(self):
        """Джоба з двома командами не міряється однією — і механізм не вгадує."""
        self.write_workflow(workflow_text().replace(
            "        uses: actions/checkout@v5\n",
            "        uses: actions/checkout@v5\n"
            "        run: python3 -c pass\n",
        ))
        with self.assertRaises(c.JobTimeError) as caught:
            c.local_command(self.tree)
        self.assertIn("run:", str(caught.exception))

    def test_the_runner_side_step_is_not_read_as_the_jobs_own_name(self):
        """`- name: Checkout` — це крок, а не імʼя джоби (відступ інший)."""
        self.assertEqual("Script unit tests", c.job_name(self.tree))


# --------------------------------------------------------------------------
# Вимір: секунди, запас, відношення
# --------------------------------------------------------------------------


class MeasurementTest(Fixture):
    """Час джоби з двох полів API — і запас до ліміту, порахований тут."""

    def setUp(self):
        super().setUp()
        self.write_runs(("1", "completed"))
        self.write_jobs("1", "2026-10-14T04:03:39Z", "2026-10-14T04:05:55Z")

    def test_the_job_time_and_the_headroom_are_printed(self):
        code, out, err = self.check("--runs", "1")
        self.assertEqual(0, code, err)
        self.assertIn("136 с", out)
        self.assertIn("запас 164 с (55 %)", out)
        self.assertIn("2.21×", out)
        self.assertIn("Script unit tests", out)

    def test_the_window_takes_only_completed_runs_and_asks_for_slack(self):
        """Вікно — завершені прогони; незавершений не має зʼїдати місце."""
        self.write_runs(("9", "in_progress"), ("1", "completed"))
        code, out, err = self.check("--runs", "1")
        self.assertEqual(0, code, err)
        self.assertNotIn("#9", out)
        self.assertIn("#1", out)
        self.assertIn("per_page=%d" % (1 * (1 + c.FETCH_SLACK)), self.calls()[0])

    def test_fewer_completed_runs_than_the_window_is_an_unknown(self):
        self.write_runs(("9", "in_progress"))
        code, out, err = self.check("--runs", "2")
        self.assertEqual(2, code)
        self.assertIn("завершених прогонів", err)

    def test_the_relation_floor_reddens_on_the_slowest_run(self):
        """Сторож: підлога запасу — це перевірка, а не прикраса.

        Прогін на 200 с лишає відношення 1.5× — менше за названу підлогу 2×, і
        механізм мусить назвати це знахідкою, а не «зелено з числом».
        """
        self.write_jobs("1", "2026-10-14T04:00:00Z", "2026-10-14T04:03:20Z")
        code, out, err = self.check("--runs", "1")
        self.assertEqual(1, code)
        self.assertIn("БІЛЬШЕ НЕ ТРИМАЄТЬСЯ", out)
        self.assertIn("більше не тримається", err)

    def test_a_run_that_reaches_the_limit_is_a_finding(self):
        self.write_jobs("1", "2026-10-14T04:00:00Z", "2026-10-14T04:05:00Z")
        code, out, err = self.check("--runs", "1")
        self.assertEqual(1, code)
        self.assertIn("дійшла до ліміту або перевищила", err)

    def test_the_summary_names_the_slowest_and_the_median(self):
        self.write_runs(("1", "completed"), ("2", "completed"))
        self.write_jobs("1", "2026-10-14T04:00:00Z", "2026-10-14T04:02:10Z")
        self.write_jobs("2", "2026-10-14T04:00:00Z", "2026-10-14T04:01:20Z")
        code, out, err = self.check("--runs", "2")
        self.assertEqual(0, code, err)
        self.assertIn("разом: 2 · найповільніший 130 с · медіана 130 с · "
                      "найшвидший 80 с", out)


# --------------------------------------------------------------------------
# Невідомість — код 2, а не «нуль»
# --------------------------------------------------------------------------


class RefusalTest(Fixture):
    """Те, що механізм не розібрав, коштує відмову — а не нуль прогонів."""

    def test_a_missing_gh_is_an_unknown_not_a_zero(self):
        """Сторож: без `gh` часу джоби взяти нема звідки, і це код 2.

        Невідомість, яка повертає 0, — це «час джоби нуль секунд»; саме цього
        класу помилок і не сміє бути в механізмі, чия ціль — не вигадувати число.
        """
        code, out, err = self.check_without_gh()
        self.assertEqual(2, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("немає `gh`", err)

    def test_a_gh_that_fails_is_an_unknown_with_its_own_words(self):
        (self.fixtures / "fail").write_text("", encoding="utf-8")
        code, out, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("Bad credentials", err)

    def test_a_run_without_the_job_is_an_unknown(self):
        self.write_runs(("1", "completed"))
        self.write_jobs("1", "2026-10-14T04:00:00Z", "2026-10-14T04:01:00Z",
                        name="Web typecheck and vitest")
        code, out, err = self.check("--runs", "1")
        self.assertEqual(2, code)
        self.assertIn("немає", err)
        self.assertIn("Script unit tests", err)

    def test_two_jobs_with_that_name_are_an_unknown(self):
        """Ретрай лишає два записи з тим самим імʼям — який міряти, не вгадується."""
        self.write_runs(("1", "completed"))
        self.write_jobs("1", "2026-10-14T04:00:00Z", "2026-10-14T04:01:00Z", count=2)
        code, out, err = self.check("--runs", "1")
        self.assertEqual(2, code)
        self.assertIn("не вгадує", err)

    def test_a_missing_time_is_an_unknown(self):
        self.write_runs(("1", "completed"))
        (self.fixtures / "jobs-1.json").write_text(
            '{"jobs": [{"name": "Script unit tests", "started_at": null, '
            '"completed_at": null}]}', encoding="utf-8")
        code, out, err = self.check("--runs", "1")
        self.assertEqual(2, code)
        self.assertIn("started_at", err)

    def test_a_window_of_zero_is_an_unknown(self):
        code, out, err = self.check("--runs", "0")
        self.assertEqual(2, code)
        self.assertIn("--runs", err)

    def test_a_broken_workflow_is_an_unknown(self):
        self.write_workflow("name: CI\n")
        code, out, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("script-tests", err)

    def test_a_repository_without_origin_is_an_unknown(self):
        code, out, err = run(["--root", str(self.tree)])
        self.assertEqual(2, code)
        self.assertIn("origin", err)


# --------------------------------------------------------------------------
# Числа в документах: ліміт або позначений локальний вимір
# --------------------------------------------------------------------------


class ClaimTest(Fixture):
    """Рядок у доку або несе ліміт, або позначений навантаженням — інакше знахідка."""

    def test_the_limit_and_the_labelled_local_figure_are_accepted(self):
        findings, unknowns = c.claim_findings(self.tree, c.CLAIMS)
        self.assertEqual([], unknowns)
        self.assertEqual([], findings)

    def test_a_local_figure_without_a_named_load_is_a_finding(self):
        """Сторож: локальне число без навантаження не сміє зійти за час джоби.

        Живий приклад тікета: серія міряла 159–239 с локально й читала це як час
        джоби. Рядок, який каже «159 с» і не називає навантаження, мусить бути
        знахідкою, а не «зелено з числом».
        """
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s, "
                               "and the job takes 159 s here.\n")
        code, out, err = self.check("--check-docs")
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("159", err)
        self.assertIn("ні ліміт джоби", err)

    def test_a_frozen_ci_figure_is_a_finding_too(self):
        """Число з CI, заморожене в доку, розійдеться з наступним прогоном."""
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s; "
                               "the slowest of the last five runs was 137 s.\n")
        code, out, err = self.check("--check-docs")
        self.assertEqual(1, code)
        self.assertIn("137", err)

    def test_a_labelled_local_figure_with_the_load_passes(self):
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s, "
                               "and a local run here took 176 s under load 5.4.\n")
        code, out, err = self.check("--check-docs")
        self.assertEqual(0, code, err)

    def test_a_word_that_merely_contains_the_mark_is_not_a_label(self):
        """Підрядок — не позначка: знайшов огляд PR #1302 прогоном, не автор.

        `download` і `upload` містять `load`, але навантаження не називають, а
        рядок, який локальне число ЗАПЕРЕЧУЄ («this is not local»), теж не
        позначка. Обидва проходили, доки позначка читалася як підрядок.
        """
        self.assertFalse(c.labelled_local(
            "The local build downloads 240 s of artifacts"))
        self.assertFalse(c.labelled_local(
            "This is not local; the upload took 240 s"))
        self.assertTrue(c.labelled_local(c.local_label(176.0, 5.4, 2.1, 15.0)))
        self.write_claim(
            limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s; "
                  "the local build downloads 240 s of artifacts.\n")
        code, out, err = self.check("--check-docs")
        self.assertEqual(1, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("240", err)

    def test_the_label_this_mechanism_prints_is_accepted_by_its_own_rule(self):
        """Форма позначки — дані, а не побажання: `--local` друкує РІВНО ту, яку
        приймає перевірка (інакше механізм відмовляв би власному виміру)."""
        label = c.local_label(176.0, 5.4, 2.1, 15.0)
        self.assertTrue(c.labelled_local(label))
        self.assertIn("НЕ час джоби", label)
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s. "
                               "%s.\n" % label)
        code, out, err = self.check("--check-docs")
        self.assertEqual(0, code, err)

    def test_a_line_that_lost_its_seconds_figure_is_an_unknown(self):
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5`.\n", rest="")
        code, out, err = self.check("--check-docs")
        self.assertEqual(2, code)
        self.assertIn("не несе числа-секунди", err)

    def test_an_anchor_that_is_not_alone_is_an_unknown(self):
        self.write_claim(limit="`script-tests` is capped by `timeout-minutes: 5` — 300 s.\n"
                               "And `script-tests` is capped by it again.\n", rest="")
        code, out, err = self.check("--check-docs")
        self.assertEqual(2, code)
        self.assertIn("якір мусить траплятися рівно раз", err)

    def test_a_missing_claim_file_is_an_unknown(self):
        (self.tree / self.claim_file).unlink()
        code, out, err = self.check("--check-docs")
        self.assertEqual(2, code)
        self.assertIn("немає файлу", err)

    def test_the_check_docs_half_asks_nothing_of_the_network(self):
        """`--check-docs` — офлайн: жодного виклику `gh`, навіть коли він є."""
        code, out, err = self.check("--check-docs")
        self.assertEqual(0, code, err)
        self.assertEqual([], self.calls())

    def test_the_seconds_regex_does_not_read_a_ratio_or_a_minute_count(self):
        """`2.21×` і «пʼять хвилин» — не секунди, і за число джоби не сходять."""
        self.assertEqual([], c.SECONDS.findall("2.21× under load 5.4, five minutes"))
        self.assertEqual(["300"], c.SECONDS.findall("300 s"))
        self.assertEqual(["176.5"], c.SECONDS.findall("176.5 с"))


# --------------------------------------------------------------------------
# Справжнє дерево
# --------------------------------------------------------------------------


class RepositoryTest(unittest.TestCase):
    """Ліміт і зараховані рядки — на тому дереві, яке поїде в CI."""

    def test_the_limit_is_the_ci_limit(self):
        self.assertEqual(EXPECTED_LIMIT, c.limit_seconds(REPO_ROOT))

    def test_every_claimed_line_derives_on_the_real_tree(self):
        """Сторож: числа про час джоби в документах виводяться, а не вписані."""
        findings, unknowns = c.claim_findings(REPO_ROOT, c.CLAIMS)
        self.assertEqual([], unknowns, "невідомість — не «число виводиться»")
        self.assertEqual([], findings, "кожне число-секунда мусить виводитися")

    def test_the_claims_are_the_named_set(self):
        self.assertEqual(list(EXPECTED_CLAIMS), list(c.CLAIMS))
        self.assertEqual(EXPECTED_WINDOW, c.WINDOW)
        self.assertEqual(EXPECTED_RATIO, c.HEADROOM_RATIO)

    def test_the_cli_is_green_offline_on_the_real_tree(self):
        code, out, err = run(["--check-docs"])
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))

    def test_the_listing_names_the_job_the_window_and_the_floor(self):
        code, out, err = run(["--list"])
        self.assertEqual(0, code, err)
        self.assertIn("script-tests", out)
        self.assertIn("останні %d завершених прогонів" % EXPECTED_WINDOW, out)
        self.assertIn("300 с", out)
        for _, anchor in EXPECTED_CLAIMS:
            self.assertIn(anchor, out)


class LocalRunTest(Fixture):
    """`--local` міряє ТУТ — і не сміє казати, що міряв джобу.

    Прогін тут не запускає справжнього набору (це коштувало б хвилини й
    рекурсії): `local_measurement` і `machine_facts` підставлені, а міряється
    те, що механізм ДРУКУЄ — позначку й рядок благополуччя.
    """

    def test_the_local_mode_reports_a_local_measurement_and_not_the_job_time(self):
        """Огляд PR #1302 знайшов це прогоном: після позначки «НЕ час джоби» йшов
        рядок «час джоби міряно на 5 прогонах», хоч `gh` у цьому режимі не
        питали. Рядок благополуччя мусить бути СВІЙ у кожного режиму."""
        with unittest.mock.patch.object(c, "local_measurement", return_value=42.0), \
                unittest.mock.patch.object(c, "machine_facts",
                                           return_value=(5.4, 2.1, 15.5)):
            code, out, err = self.check("--local")
        self.assertEqual(0, code, "stdout=%r stderr=%r" % (out, err))
        self.assertIn("ЛОКАЛЬНО 42.0 с", out)
        self.assertIn("НЕ час джоби", out)
        self.assertIn("ЧАС ДЖОБИ тут не міряно", out)
        self.assertNotIn("час джоби міряно", out,
                         "локальний режим не міряє джобу — і не сміє цього казати")
        self.assertEqual([], self.calls(), "локальний вимір не питає `gh`")


class LocalLabelTest(unittest.TestCase):
    """Локальний вимір існує лише ПОЗНАЧЕНИЙ — і позначає його код."""

    def test_the_label_names_the_load_and_the_memory(self):
        label = c.local_label(176.0, 5.4, 2.1, 15.0)
        self.assertIn("176.0 с", label)
        self.assertIn("навантаженням 5.40", label)
        self.assertIn("RAM 2.1/15.0 ГБ", label)
        self.assertIn("НЕ час джоби", label)

    def test_a_failed_local_run_is_an_unknown_rather_than_a_time(self):
        """Час невдалого прогону — не час джоби: міряти його немає сенсу."""
        class Failed:
            returncode = 1
            stderr = "boom"
            stdout = ""

        with self.assertRaises(c.JobTimeError) as caught:
            c.local_measurement(("python3", "-c", "pass"),
                                runner=lambda *a, **k: Failed())
        self.assertIn("кодом 1", str(caught.exception))

    def test_the_local_measurement_is_the_wall_time_of_the_command(self):
        class Fine:
            returncode = 0
            stderr = ""
            stdout = ""

        ticks = iter([10.0, 13.5])
        seconds = c.local_measurement(("python3", "-c", "pass"),
                                      runner=lambda *a, **k: Fine(),
                                      now=lambda: next(ticks))
        self.assertEqual(3.5, seconds)

    def test_the_machine_facts_come_from_proc(self):
        proc = Path(tempfile.mkdtemp(prefix="proc-"))
        self.addCleanup(shutil.rmtree, proc, ignore_errors=True)
        (proc / "loadavg").write_text("5.40 5.10 4.80 3/100 12345\n", encoding="utf-8")
        (proc / "meminfo").write_text(
            "MemTotal:       15728640 kB\nMemAvailable:    2097152 kB\n", encoding="utf-8")
        self.assertEqual((5.4, 2.0, 15.0), c.machine_facts(proc))

    def test_a_proc_without_memory_is_an_unknown(self):
        proc = Path(tempfile.mkdtemp(prefix="proc-"))
        self.addCleanup(shutil.rmtree, proc, ignore_errors=True)
        (proc / "loadavg").write_text("1.00 1.00 1.00\n", encoding="utf-8")
        (proc / "meminfo").write_text("MemFree: 1 kB\n", encoding="utf-8")
        with self.assertRaises(c.JobTimeError):
            c.machine_facts(proc)


if __name__ == "__main__":
    unittest.main()
