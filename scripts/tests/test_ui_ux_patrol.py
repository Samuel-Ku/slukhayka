"""Що пінить нічний UI/UX-патруль (#1466).

  * `FocusRotationTest` — тема дня детермінована й за сім днів проходить усі
    теми; інакше агент або повторює той самий поверхневий прохід, або тема
    залежить від години запуску;
  * `PromptTest` — промпт справді несе контракт: тему, стелю тікетів, відомі
    ключі, обовʼязковий формат «Доказ» із `file:line`, заборону правок,
    контрактний рядок результату; dry-run забороняє `gh issue create`;
  * `MarkerTest` — дедуп-маркери читаються з тіл issues, включно з кількома в
    одному тілі; звіт-issue відрізняється від тікета;
  * `ValidationTest` — контракт прогону відмовляє на тікеті без маркера,
    мітки, доказу `file:line`, на перевищенні стелі й на повторі ключа;
  * `TimeTest` — `createdAt` з `Z` та зсувом розбирається, сміття — ні;
  * `ResultTest` — контрактний рядок агента читається з останнього входження;
  * `ContractRefsTest` — з контракту беруться номери створених тікетів і звіту,
    а чужі issues (без маркера) не рахуються тікетами патруля;
  * `UnitsTest` — systemd-юніт бере скрипт із `origin/main` (а не з робочого
    дерева), таймер щоденний, env-файл не містить токена, якщо його не дали;
  * `SafetyTest` — копія патруля живе у фіксованому `.worktrees/ui-ux-patrol`,
    а не в шляху, який можна випадково навести на чуже дерево;
  * `LogsTest` — старі логи прибираються, свіжі лишаються.

Мережі, git і systemd у тестах немає: усе, що робить зовнішні виклики, —
окремі функції, які тести не викликають. Межа названа прямо: сам прогін
агента тут не перевіряється (для нього потрібні dsh, ключ моделі й `gh`), а
`dry-run` на hub — окрема приймальна перевірка тікета.
"""

import importlib.util
import os
import tempfile
import textwrap
import unittest
from unittest import mock
from contextlib import redirect_stdout
import io
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "ui-ux-patrol.py"

spec = importlib.util.spec_from_file_location("ui_ux_patrol", SCRIPT)
patrol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patrol)

NOW = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)


def issue(
    number,
    *,
    body=None,
    labels=("ready-for-agent", "ui-ux-patrol"),
    created_at="2026-10-26T03:30:00Z",
    title="t",
):
    return {
        "number": number,
        "title": title,
        "body": body if body is not None else valid_body("listen", f"x{number}"),
        "labels": [{"name": name} for name in labels],
        "createdAt": created_at,
        "url": f"https://example.invalid/{number}",
    }


def valid_body(focus, slug, *, evidence="app/src/main/java/X.kt:12", marker=True):
    text = textwrap.dedent(
        f"""\
        ## Що треба
        Дефект.
        ## Доказ
        - `{evidence}` — видно
        - `docs/adr/0044-touch-target-floor-24dp.md` — правило
        ## Що зробити
        1. зробити
        ## Приймальні критерії
        - тест
        ## Межі
        - не чіпати
        """
    )
    if marker:
        text += f"<!-- ui-ux-patrol: key={focus}/{slug} -->"
    return text


class FocusRotationTest(unittest.TestCase):
    def test_same_day_gives_same_focus(self):
        self.assertEqual(
            patrol.focus_for(NOW)["key"], patrol.focus_for(NOW)["key"]
        )

    def test_seven_consecutive_days_cover_every_focus(self):
        keys = {
            patrol.focus_for(datetime(2026, 10, day, tzinfo=timezone.utc))["key"]
            for day in range(20, 27)
        }
        self.assertEqual(len(keys), len(patrol.FOCUSES))

    def test_every_focus_has_brief_and_title(self):
        for focus in patrol.FOCUSES:
            self.assertTrue(focus["key"])
            self.assertTrue(focus["title"])
            self.assertTrue(focus["brief"])


class MarkerTest(unittest.TestCase):
    def keys(self, issues):
        opened = [{**one, "state": "OPEN"} for one in issues]
        return patrol.split_known_keys(opened)[0]

    def test_reads_keys_from_bodies(self):
        issues = [
            issue(1, body=valid_body("listen", "a")),
            issue(2, body=valid_body("web", "b")),
        ]
        self.assertEqual(self.keys(issues), {"listen/a": 1, "web/b": 2})

    def test_reads_several_markers_in_one_body(self):
        body = (
            "<!-- ui-ux-patrol: key=listen/a -->\n"
            "<!-- ui-ux-patrol: key=listen/b -->"
        )
        self.assertEqual(
            self.keys([issue(3, body=body)]),
            {"listen/a": 3, "listen/b": 3},
        )

    def test_first_issue_wins_a_duplicated_key(self):
        body = "<!-- ui-ux-patrol: key=web/x -->"
        self.assertEqual(
            self.keys([issue(4, body=body), issue(5, body=body)]),
            {"web/x": 4},
        )

    def test_report_issue_is_recognised_and_has_no_ticket_key(self):
        report = issue(9, body=f"{patrol.REPORT_MARKER}\n# звіт")
        self.assertTrue(patrol.is_report_issue(report))
        self.assertEqual(self.keys([report]), {})
        self.assertFalse(patrol.is_report_issue(issue(1)))


class ClosedKeyTest(unittest.TestCase):
    """Закритий ключ не блокує новий тікет — це обіцяє runbook (#1466)."""

    def test_closed_and_open_keys_are_split(self):
        open_issue = issue(1, body=valid_body("listen", "a"))
        open_issue["state"] = "OPEN"
        closed_issue = issue(2, body=valid_body("listen", "b"))
        closed_issue["state"] = "CLOSED"
        open_keys, closed_keys = patrol.split_known_keys([open_issue, closed_issue])
        self.assertEqual(open_keys, {"listen/a": 1})
        self.assertEqual(closed_keys, {"listen/b": 2})

    def test_regression_with_a_closed_key_is_not_blocked(self):
        closed_issue = issue(1, body=valid_body("listen", "a"))
        closed_issue["state"] = "CLOSED"
        open_keys, _ = patrol.split_known_keys([closed_issue])
        regression = issue(7, body=valid_body("listen", "a"))
        self.assertEqual(
            patrol.validate_created_issues(
                [regression], existing_keys=open_keys, max_tickets=patrol.MAX_TICKETS
            ),
            [],
        )

    def test_open_key_still_blocks(self):
        open_issue = issue(1, body=valid_body("listen", "a"))
        open_issue["state"] = "OPEN"
        open_keys, _ = patrol.split_known_keys([open_issue])
        duplicate = issue(7, body=valid_body("listen", "a"))
        problems = patrol.validate_created_issues(
            [duplicate], existing_keys=open_keys, max_tickets=patrol.MAX_TICKETS
        )
        self.assertTrue(any("ключ" in problem for problem in problems))


class PromptTest(unittest.TestCase):
    def render(self, **kwargs):
        params = dict(
            repo=Path("/repo/.worktrees/ui-ux-patrol"),
            now=NOW,
            dry_run=False,
            max_tickets=patrol.MAX_TICKETS,
            known_keys={},
        )
        params.update(kwargs)
        return patrol.render_prompt(**params)

    def test_contains_contract_core(self):
        prompt = self.render()
        focus = patrol.focus_for(NOW)
        self.assertIn(focus["title"], prompt)
        self.assertIn(f"key={focus['key']}/<slug>", prompt)
        self.assertIn("/repo/.worktrees/ui-ux-patrol", prompt)
        self.assertIn("ready-for-agent", prompt)
        self.assertIn("## Доказ", prompt)
        self.assertIn("file:line", prompt)
        self.assertIn("UI-UX-PATROL-RESULT:", prompt)
        self.assertIn("created=<#12,#13|none>", prompt)
        self.assertIn(str(patrol.MAX_TICKETS), prompt)
        self.assertIn("gh issue create", prompt)
        self.assertIn("Не змінюй жодного файлу", prompt)
        self.assertIn(patrol.REPORT_MARKER, prompt)

    def test_known_keys_are_listed(self):
        prompt = self.render(known_keys={"listen/a": 12, "web/b": 34})
        self.assertIn("- `listen/a` — #12", prompt)
        self.assertIn("- `web/b` — #34", prompt)

    def test_closed_keys_are_listed_separately(self):
        prompt = self.render(
            known_keys={"listen/a": 12},
            closed_keys={"listen/b": 34},
        )
        self.assertIn("Відкриті — не дублюй:", prompt)
        self.assertIn("- `listen/a` — #12", prompt)
        self.assertIn("Закриті", prompt)
        self.assertIn("- `listen/b` — #34", prompt)

    def test_dry_run_forbids_issue_creation(self):
        prompt = self.render(dry_run=True)
        self.assertIn("DRY-RUN", prompt)
        self.assertIn("не виконуй `gh issue create`", prompt)
        self.assertIn("dry-run", prompt)

    def test_battle_prompt_has_no_dry_run_directive(self):
        prompt = self.render(dry_run=False)
        self.assertNotIn("DRY-RUN", prompt)
        self.assertNotIn("не виконуй `gh issue create`", prompt)

    def test_no_unsubstituted_placeholders(self):
        for dry_run in (False, True):
            prompt = self.render(dry_run=dry_run)
            self.assertNotIn("$", prompt)

    def test_coding_standards_is_optional_because_absent_from_main(self):
        prompt = self.render()
        self.assertIn("якщо в дереві є `CODING_STANDARDS.md`", prompt)

    def test_required_reading_list_is_present(self):
        prompt = self.render()
        for name in (
            "AGENTS.md",
            "CONTRIBUTING.md",
            "docs/adr/0033",
            "docs/adr/0044",
            "docs/adr/0018",
            "docs/adr/0049",
            "docs/adr/0058",
            "docs/accessibility/talkback-release-checklist.md",
            "docs/audits/",
        ):
            self.assertIn(name, prompt)

    def test_web_focus_points_at_web_rules(self):
        web_day = next(
            day
            for day in range(20, 28)
            if patrol.focus_for(
                datetime(2026, 10, day, tzinfo=timezone.utc)
            )["key"]
            == "web"
        )
        prompt = self.render(
            now=datetime(2026, 10, web_day, tzinfo=timezone.utc)
        )
        self.assertIn("0034-web-client-parity-baseline.md", prompt)
        self.assertIn("web/`", prompt)


class ValidationTest(unittest.TestCase):
    def validate(self, created, *, known=None, max_tickets=patrol.MAX_TICKETS):
        return patrol.validate_created_issues(
            created,
            existing_keys=known or {},
            max_tickets=max_tickets,
        )

    def test_clean_ticket_passes(self):
        self.assertEqual(self.validate([issue(1)]), [])

    def test_missing_marker_is_a_violation(self):
        problems = self.validate([issue(1, body=valid_body("listen", "a", marker=False))])
        self.assertTrue(any("маркера" in problem for problem in problems))

    def test_missing_ready_label_is_a_violation(self):
        problems = self.validate([issue(1, labels=("ui-ux-patrol",))])
        self.assertTrue(any("ready-for-agent" in problem for problem in problems))

    def test_missing_patrol_label_is_a_violation(self):
        problems = self.validate([issue(1, labels=("ready-for-agent",))])
        self.assertTrue(any(patrol.LABEL in problem for problem in problems))

    def test_missing_evidence_section_is_a_violation(self):
        body = "## Що треба\nнемає доказу\n<!-- ui-ux-patrol: key=listen/a -->"
        problems = self.validate([issue(1, body=body)])
        self.assertTrue(any("Доказ" in problem for problem in problems))

    def test_missing_file_line_is_a_violation(self):
        problems = self.validate(
            [issue(1, body=valid_body("listen", "a", evidence="десь у коді"))]
        )
        self.assertTrue(any("file:line" in problem for problem in problems))

    def test_over_the_ceiling_is_a_violation(self):
        created = [issue(number) for number in range(1, patrol.MAX_TICKETS + 2)]
        problems = self.validate(created)
        self.assertTrue(any("стеля" in problem for problem in problems))

    def test_report_issue_does_not_count_as_ticket(self):
        report = issue(
            99,
            body=f"{patrol.REPORT_MARKER}\n# звіт",
            labels=("ui-ux-patrol",),
            created_at="2026-10-26T03:30:00Z",
        )
        created = [issue(number) for number in range(1, patrol.MAX_TICKETS + 1)]
        self.assertEqual(self.validate(created + [report]), [])

    def test_existing_open_key_blocks_refiling(self):
        problems = self.validate([issue(7, body=valid_body("listen", "a"))], known={"listen/a": 3})
        self.assertTrue(any("#7" in problem and "ключ" in problem for problem in problems))

    def test_duplicated_key_inside_one_run_is_a_violation(self):
        first = issue(7, body=valid_body("listen", "same"))
        second = issue(8, body=valid_body("listen", "same"))
        problems = self.validate([first, second])
        self.assertTrue(any("повторює" in problem for problem in problems))


class TimeTest(unittest.TestCase):
    def test_z_timestamp_is_after_start(self):
        start = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)
        self.assertTrue(patrol.is_created_after(issue(1), start))

    def test_offset_timestamp_is_compared_as_instant(self):
        start = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)
        old = issue(1, created_at="2026-10-26T05:00:00+02:00")  # 03:00 UTC
        self.assertFalse(patrol.is_created_after(old, start))

    def test_garbage_timestamp_is_not_after(self):
        start = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)
        self.assertFalse(patrol.is_created_after(issue(1, created_at="не дата"), start))


class ResultTest(unittest.TestCase):
    def test_reads_last_result_line(self):
        text = (
            "UI-UX-PATROL-RESULT: blocked — перша спроба\n"
            "багато тексту\n"
            "UI-UX-PATROL-RESULT: ok created=2 report=#12 — готово\n"
        )
        self.assertEqual(
            patrol.parse_result(text)[0], "ok"
        )
        self.assertIn("created=2", patrol.parse_result(text)[1])

    def test_backticked_result_line_is_read(self):
        text = (
            "готово\n\n"
            "`UI-UX-PATROL-RESULT: ok created=0 report=none — dry-run`"
        )
        self.assertEqual(patrol.parse_result(text)[0], "ok")
        self.assertIn("created=0", patrol.parse_result(text)[1])
        self.assertNotIn("`", patrol.parse_result(text)[1])

    def test_result_in_a_bullet_or_quote_is_read(self):
        for prefix in ("- ", "> ", "* "):
            text = f"{prefix}UI-UX-PATROL-RESULT: ok-partial — частково"
            self.assertEqual(patrol.parse_result(text)[0], "ok-partial")

    def test_missing_result_is_none(self):
        self.assertEqual(patrol.parse_result("просто текст"), (None, ""))

    def test_ok_partial_is_accepted_as_verdict(self):
        self.assertEqual(
            patrol.parse_result("UI-UX-PATROL-RESULT: ok-partial — частково")[0],
            "ok-partial",
        )


class ContractRefsTest(unittest.TestCase):
    def test_created_and_report_numbers_are_read(self):
        created, report = patrol.parse_contract_refs(
            "created=#12,#13 report=#5 — готово"
        )
        self.assertEqual(created, [12, 13])
        self.assertEqual(report, 5)

    def test_none_is_an_explicit_zero(self):
        self.assertEqual(patrol.parse_contract_refs("created=none report=none"), ([], None))
        self.assertEqual(patrol.parse_contract_refs("created=0 report=none"), ([], None))

    def test_spaced_and_conjoined_lists_are_read(self):
        for tail in (
            "created=#12, #13 report=#5 — ok",
            "created=#12,#13 report=#5 — ok",
            "created=#12 і #13 report=#5 — ok",
            "created=#12, #13, report=#5 — ok",
        ):
            with self.subTest(tail=tail):
                created, report = patrol.parse_contract_refs(tail)
                self.assertEqual(created, [12, 13])
                self.assertEqual(report, 5)

    def test_missing_created_field_is_not_zero(self):
        created, report = patrol.parse_contract_refs("report=#5 — готово")
        self.assertIsNone(created)
        self.assertEqual(report, 5)

    def test_foreign_issues_are_not_counted_as_undeclared(self):
        started = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)
        marked = issue(12, body=valid_body("listen", "new"))
        foreign = issue(999, body="## Що треба\nчужий issue\n", labels=())
        foreign["createdAt"] = "2026-10-26T03:40:00Z"
        marked["createdAt"] = "2026-10-26T03:41:00Z"
        self.assertEqual(
            patrol.undeclared_marked_issues(
                [foreign, marked], declared=[12], started=started
            ),
            [],
        )

    def test_undeclared_marked_ticket_is_named(self):
        started = datetime(2026, 10, 26, 3, 20, tzinfo=timezone.utc)
        marked = issue(13, body=valid_body("listen", "new"))
        marked["createdAt"] = "2026-10-26T03:41:00Z"
        self.assertEqual(
            patrol.undeclared_marked_issues([marked], declared=[], started=started),
            [13],
        )


class UnitsTest(unittest.TestCase):
    def test_service_runs_script_from_origin_main(self):
        unit = patrol.render_service_unit(Path("/home/u/Projects/slukhayka"))
        self.assertIn("git show origin/main:scripts/ui-ux-patrol.py", unit)
        self.assertIn("refs/remotes/origin/main", unit)
        self.assertIn(patrol.REPO_URL, unit)
        self.assertIn("run --repo /home/u/Projects/slukhayka", unit)
        self.assertIn("EnvironmentFile=-%h/.config/slukhayka/ui-ux-patrol.env", unit)
        self.assertIn("TimeoutStartSec=", unit)

    def test_timer_is_daily_and_persistent(self):
        unit = patrol.render_timer_unit()
        self.assertIn("OnCalendar=*-*-* 03:20:00", unit)
        self.assertIn("Persistent=true", unit)
        self.assertIn("WantedBy=timers.target", unit)

    def test_env_file_has_path_and_optional_token(self):
        text = patrol.render_env_file(["/a/bin", "/b/bin", "/a/bin"], None)
        self.assertIn("PATH=/a/bin:/b/bin", text)
        self.assertNotIn("GH_TOKEN=", text)
        with_token = patrol.render_env_file(["/a/bin"], "gho_example")
        self.assertIn("GH_TOKEN=gho_example", with_token)

    def test_env_path_starts_with_upgrade_proof_dirs(self):
        # #1657 — таймер падав три ночі, бо PATH тримав версійний каталог gh
        # (`…/gh/latest/gh_2.102.0…/bin`), який зникає після mise upgrade.
        # Стабільні каталоги мусять стояти ПЕРШИМИ, щоб активна версія
        # перемагала мертвий шлях.
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            local_bin = home / ".local" / "bin"
            shims = home / ".local" / "share" / "mise" / "shims"
            local_bin.mkdir(parents=True)
            shims.mkdir(parents=True)

            dirs = patrol.patrol_path_dirs(
                home, which=lambda _name: None, python="/usr/bin/python3"
            )

            self.assertEqual(str(local_bin), dirs[0])
            self.assertEqual(str(shims), dirs[1])
            text = patrol.render_env_file(dirs, None)
            self.assertIn("PATH=" + ":".join(dirs), text)

    def test_env_path_skips_absent_upgrade_proof_dirs(self):
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            self.assertEqual([], patrol.stable_tool_dirs(home))
            dirs = patrol.patrol_path_dirs(
                home, which=lambda _name: None, python="/usr/bin/python3"
            )
            self.assertNotIn(str(home / ".local" / "bin"), dirs)
            self.assertNotIn(str(home / ".local" / "share" / "mise" / "shims"), dirs)

    def test_stale_versioned_tool_dir_is_only_a_fallback(self):
        # Найгірший сценарій #1657: `which` віддає мертвий версійний каталог, а
        # стабільні лишились. Мертвий шлях може бути в PATH, але ПОСЛЯ shims —
        # тоді таймер усе одно знаходить робочий gh.
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            shims = home / ".local" / "share" / "mise" / "shims"
            shims.mkdir(parents=True)
            dead = "/dead/gh/2.102.0_linux_amd64/bin"
            dirs = patrol.patrol_path_dirs(
                home, which=lambda _name: dead + "/gh", python="/usr/bin/python3"
            )
            self.assertLess(dirs.index(str(shims)), dirs.index(dead))

    def test_env_file_is_marked_as_generated(self):
        self.assertIn(
            patrol.PROFILE_MARKER,
            patrol.render_env_file(["/a/bin"], None),
        )


class ModelKeyTest(unittest.TestCase):
    """#1659 — ключ моделі headless-профілю мусить доходити до env таймера."""

    def test_profile_api_key_env_is_read_from_patch(self):
        with tempfile.TemporaryDirectory() as raw:
            patch = Path(raw) / "cordis.patch.yml"
            patch.write_text(
                "providers:\n  opencode-go:\n    apiKeyEnv: OPENCODE_API_KEY\n",
                encoding="utf-8",
            )
            self.assertEqual("OPENCODE_API_KEY", patrol.profile_api_key_env(patch))
            self.assertIsNone(patrol.profile_api_key_env(Path(raw) / "missing.yml"))

    def test_credential_refs_parser_reads_only_the_refs_block(self):
        with tempfile.TemporaryDirectory() as raw:
            credentials = Path(raw) / ".credentials.yaml"
            credentials.write_text(
                textwrap.dedent(
                    """\
                    version: 1
                    records:
                      client-connection/browser-session:
                        kind: grant
                    refs:
                      # коментар усередині блоку
                      OPENCODE_API_KEY: oc_sk_example
                      DEEPSEEK_API_KEY: "sk-quoted"
                    other_key: value
                    """
                ),
                encoding="utf-8",
            )
            refs = patrol.credential_refs(credentials)
            self.assertEqual(
                {
                    "OPENCODE_API_KEY": "oc_sk_example",
                    "DEEPSEEK_API_KEY": "sk-quoted",
                },
                refs,
            )

    def test_credential_refs_missing_or_broken_file_is_empty(self):
        self.assertEqual({}, patrol.credential_refs(Path("/nonexistent/creds.yaml")))
        with tempfile.TemporaryDirectory() as raw:
            broken = Path(raw) / ".credentials.yaml"
            broken.write_text("refs:\n  NO_COLON_HERE\n", encoding="utf-8")
            self.assertEqual({}, patrol.credential_refs(broken))
            non_utf8 = Path(raw) / ".credentials.bin"
            non_utf8.write_bytes(b"refs:\n  OPENCODE_API_KEY: \xff\xfe\n")
            self.assertEqual({}, patrol.credential_refs(non_utf8))

    def test_resolve_model_key_prefers_environment(self):
        with mock.patch.dict(os.environ, {"OPENCODE_API_KEY": "from-env"}):
            self.assertEqual(
                "from-env", patrol.resolve_model_key("OPENCODE_API_KEY", home=Path("/nowhere"))
            )
        self.assertIsNone(patrol.resolve_model_key(None))

    def test_resolve_model_key_falls_back_to_credentials_file(self):
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            (home / ".dsh").mkdir()
            (home / ".dsh" / ".credentials.yaml").write_text(
                "refs:\n  OPENCODE_API_KEY: oc_sk_from_file\n", encoding="utf-8"
            )
            with mock.patch.dict(os.environ, {}, clear=False):
                os.environ.pop("OPENCODE_API_KEY", None)
                self.assertEqual(
                    "oc_sk_from_file",
                    patrol.resolve_model_key("OPENCODE_API_KEY", home=home),
                )

    def test_env_file_carries_the_model_key_but_not_without_it(self):
        with_key = patrol.render_env_file(
            ["/a/bin"], None, {"OPENCODE_API_KEY": "oc_sk_test"}
        )
        self.assertIn("OPENCODE_API_KEY=oc_sk_test", with_key)
        self.assertIn("не комітити й не копіювати в репо", with_key)
        without_key = patrol.render_env_file(["/a/bin"], None)
        self.assertNotIn("OPENCODE_API_KEY=", without_key)

    def test_render_env_file_rejects_newline_in_values(self):
        # #1662 — без екранування новий рядок у значенні дозволив би інʼєкцію
        # довільних змінних у файл таймера.
        with self.assertRaises(patrol.PatrolError):
            patrol.render_env_file(
                ["/a/bin"], None, {"OPENCODE_API_KEY": "ok\nevil=1"}
            )

    def test_write_env_file_does_not_print_the_key(self):
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            buffer = io.StringIO()
            with mock.patch.dict(os.environ, {"HOME": str(home)}):
                with redirect_stdout(buffer):
                    patrol.write_env_file(
                        write_token=False,
                        model_env={"OPENCODE_API_KEY": "oc_sk_secret_value"},
                    )
            self.assertNotIn("oc_sk_secret_value", buffer.getvalue())
            env_path = home / ".config" / "slukhayka" / "ui-ux-patrol.env"
            env_text = env_path.read_text(encoding="utf-8")
            self.assertIn("OPENCODE_API_KEY=oc_sk_secret_value", env_text)
            self.assertNotIn("oc_sk_secret_value", buffer.getvalue())

    def test_write_env_file_writes_key_with_0600(self):
        with tempfile.TemporaryDirectory() as raw:
            home = Path(raw)
            with mock.patch.dict(os.environ, {"HOME": str(home)}):
                path = patrol.write_env_file(
                    write_token=False, model_env={"OPENCODE_API_KEY": "oc_sk_written"}
                )
            text = path.read_text(encoding="utf-8")
            self.assertIn("OPENCODE_API_KEY=oc_sk_written", text)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)


class ProfilePatchTest(unittest.TestCase):
    def test_empty_template_is_replaceable(self):
        template = "# comments\n# more\n[]\n"
        self.assertTrue(patrol.is_replaceable_profile_patch(template))

    def test_blank_file_is_replaceable(self):
        self.assertTrue(patrol.is_replaceable_profile_patch("\n# only a comment\n"))

    def test_our_own_patch_is_replaceable(self):
        self.assertTrue(
            patrol.is_replaceable_profile_patch(
                f"{patrol.PROFILE_MARKER}\n- id: x\n"
            )
        )

    def test_foreign_configuration_is_not_replaceable(self):
        self.assertFalse(
            patrol.is_replaceable_profile_patch("- id: agent-default-model\n  config: {}\n")
        )


class CommittedUnitsTest(unittest.TestCase):
    """Комічені юніти не мусять розійтися з тим, що ставить `install`."""

    def test_timer_file_matches_render(self):
        committed = (REPO_ROOT / "scripts" / "systemd" / "ui-ux-patrol.timer").read_text(
            encoding="utf-8"
        )
        self.assertEqual(committed, patrol.render_timer_unit())

    def test_service_execstart_matches_render(self):
        committed = (
            REPO_ROOT / "scripts" / "systemd" / "ui-ux-patrol.service"
        ).read_text(encoding="utf-8")
        rendered = patrol.render_service_unit(Path("%h/Projects/slukhayka"))
        self.assertEqual(
            [line for line in rendered.splitlines() if line.startswith("ExecStart=")],
            [line for line in committed.splitlines() if line.startswith("ExecStart=")],
        )


class SafetyTest(unittest.TestCase):
    def test_worktree_path_is_fixed_inside_repo(self):
        repo = Path("/repo")
        path = patrol.patrol_worktree_path(repo)
        self.assertEqual(path, Path("/repo/.worktrees/ui-ux-patrol"))

    def test_worktree_name_does_not_depend_on_ticket(self):
        self.assertNotIn("#", patrol.WORKTREE_NAME)
        self.assertEqual(patrol.WORKTREE_NAME, "ui-ux-patrol")


class LogsTest(unittest.TestCase):
    def test_old_logs_are_removed_and_fresh_kept(self):
        import time as time_module

        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp)
            old = logs / "old.log"
            fresh = logs / "fresh.log"
            old.write_text("old", encoding="utf-8")
            fresh.write_text("fresh", encoding="utf-8")
            stamp = time_module.time() - 30 * 24 * 3600
            os.utime(old, (stamp, stamp))
            patrol.cleanup_old_logs(logs, patrol.KEEP_LOGS)
            self.assertFalse(old.exists())
            self.assertTrue(fresh.exists())


if __name__ == "__main__":
    unittest.main()
