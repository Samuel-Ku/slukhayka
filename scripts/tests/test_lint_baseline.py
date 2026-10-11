import contextlib
import io
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lint_baseline as lb  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE = REPO_ROOT / "app" / "lint-baseline.xml"
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"

DEFAULT_STRINGS = "src/main/res/values/strings.xml"


def entry(issue_id, file=DEFAULT_STRINGS, line=10, column=13, message="msg", error_line=None):
    error_lines = (error_line,) if error_line else ()
    return lb.Entry(
        issue_id=issue_id,
        message=message,
        error_lines=error_lines,
        locations=(lb.Location(file=file, line=line, column=column),),
    )


def raw_entry(issue_id, file=DEFAULT_STRINGS, line=10, column=13, message="msg") -> str:
    """Запис у сирій формі: тестам ґарда форматер не потрібен."""
    return (
        f'    <issue\n        id="{issue_id}"\n        message="{message}">\n'
        f'        <location\n            file="{file}"\n            line="{line}"\n            column="{column}"/>\n'
        "    </issue>\n"
    )


def synthetic_baseline(blocks: list[str]) -> str:
    head = '<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 9.1.1" type="baseline">\n\n'
    return head + "\n".join(blocks) + "\n</issues>\n"


class BaselineParsingTest(unittest.TestCase):
    def test_repository_baseline_is_understood_entry_by_entry(self):
        baseline = lb.parse_baseline_text(BASELINE.read_text(encoding="utf-8"), str(BASELINE))
        self.assertGreater(len(baseline.entries), 1000)
        self.assertEqual(sum(lb.count_by_rule(baseline.entries).values()), len(baseline.entries))

    def test_render_reproduces_the_file(self):
        raw = synthetic_baseline([raw_entry("UseKtx"), raw_entry("UseKtx", line=20)])
        baseline = lb.parse_baseline_text(raw, "synthetic")
        self.assertEqual(baseline.render(), raw)

    def test_irregular_gaps_survive_parse_and_render(self):
        # У справжньому файлі три такі місця (2×4 і 1×31 нових рядків).
        raw = synthetic_baseline([raw_entry("UseKtx")]).replace(
            "    </issue>\n\n</issues>", "    </issue>\n\n\n\n</issues>"
        )
        self.assertEqual(lb.parse_baseline_text(raw, "synthetic").render(), raw)

    def test_refuses_a_file_whose_blocks_do_not_match_the_xml(self):
        raw = synthetic_baseline([raw_entry("UseKtx")])
        with self.assertRaises(lb.BaselineError):
            lb.parse_baseline_text(raw.replace("    <issue\n", "  <issue\n"), "broken")

    def test_refuses_a_file_without_closing_tag(self):
        raw = synthetic_baseline([raw_entry("UseKtx")]).replace("</issues>\n", "")
        with self.assertRaises(lb.BaselineError):
            lb.parse_baseline_text(raw, "broken")


class GrowthDecisionTest(unittest.TestCase):
    def test_missing_translation_growth_is_reported_separately(self):
        base = lb.Counter({"MissingTranslation": 1083, "UseKtx": 64})
        head = lb.Counter({"MissingTranslation": 1086, "UseKtx": 64})
        over, allowed, shrunk = lb.growth_rows(base, head, {})
        self.assertEqual([row[0] for row in over], ["MissingTranslation"])
        self.assertEqual(over[0][3], 3)
        self.assertEqual((allowed, shrunk), ([], []))

    def test_growth_of_any_rule_fails(self):
        base = lb.Counter({"MissingTranslation": 1083, "UseKtx": 64})
        head = lb.Counter({"MissingTranslation": 1083, "UseKtx": 65})
        over, _, _ = lb.growth_rows(base, head, {})
        self.assertEqual([row[0] for row in over], ["UseKtx"])

    def test_swapping_rules_is_not_hidden_by_the_total(self):
        # −5 UnusedResources і +5 MissingTranslation: те саме число на файл, але
        # приріст перекладів мусить лишитися видимим.
        base = lb.Counter({"UnusedResources": 45, "MissingTranslation": 1083})
        head = lb.Counter({"UnusedResources": 40, "MissingTranslation": 1088})
        over, _, shrunk = lb.growth_rows(base, head, {})
        self.assertEqual([row[0] for row in over], ["MissingTranslation"])
        self.assertEqual([row[0] for row in shrunk], ["UnusedResources"])

    def test_allowance_covers_exactly_its_rule(self):
        base = lb.Counter({"MissingTranslation": 1083})
        head = lb.Counter({"MissingTranslation": 1084})
        over, allowed, _ = lb.growth_rows(base, head, {"MissingTranslation": 1})
        self.assertEqual(over, [])
        self.assertEqual(allowed[0][3], 1)
        over, _, _ = lb.growth_rows(base, head, {"MissingTranslation": 0})
        self.assertEqual(len(over), 1)

    def test_shrinking_is_never_a_failure(self):
        base = lb.Counter({"MissingTranslation": 1083})
        head = lb.Counter({"MissingTranslation": 1000})
        over, allowed, shrunk = lb.growth_rows(base, head, {})
        self.assertEqual((over, allowed), ([], []))
        self.assertEqual(shrunk[0][3], -83)

    def test_allowance_parsing_accepts_plus_and_spaces(self):
        allowance = lb.parse_allowance(" MissingTranslation=+3 , UseKtx=1 ", {"MissingTranslation", "UseKtx"})
        self.assertEqual(allowance, {"MissingTranslation": 3, "UseKtx": 1})

    def test_allowance_parsing_refuses_a_typo_instead_of_ignoring_it(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslatio=1", {"MissingTranslation"})

    def test_allowance_parsing_refuses_a_non_number(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslation=three", {"MissingTranslation"})

    def test_allowance_parsing_refuses_a_missing_equals(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslation 3", {"MissingTranslation"})


class CheckGrowthCommandTest(unittest.TestCase):
    """Ґард на справжньому git: base-ref читається через `git show`."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "test"], cwd=self.root, check=True)
        self.app = self.root / "app"
        self.app.mkdir()
        self.baseline = self.app / "lint-baseline.xml"
        self.write([entry("MissingTranslation", line=1), entry("UseKtx", file="src/main/java/App.kt", line=5)])
        subprocess.run(["git", "add", "-A"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "base"], cwd=self.root, check=True)
        self.base = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.root, check=True, capture_output=True, text=True
        ).stdout.strip()

    def tearDown(self):
        self.temp_dir.cleanup()

    def write(self, entries) -> None:
        self.baseline.write_text(synthetic_baseline([raw_entry(e.issue_id, e.file, e.line) for e in entries]), encoding="utf-8")

    def commit(self, message: str) -> None:
        subprocess.run(["git", "add", "-A"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", message], cwd=self.root, check=True)

    def run_guard(self, *refs, allowance=None):
        environment = dict(os.environ)
        if allowance is None:
            environment.pop("LINT_BASELINE_GROWTH_ALLOWANCE", None)
        else:
            environment["LINT_BASELINE_GROWTH_ALLOWANCE"] = allowance
        output = io.StringIO()
        with mock.patch.object(lb, "REPO_ROOT", self.root), mock.patch.dict(os.environ, environment, clear=True):
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                code = lb.main(["--baseline", "app/lint-baseline.xml", "check-growth", *refs])
        return code, output.getvalue()

    def test_same_ref_has_no_growth(self):
        code, output = self.run_guard(self.base, self.base)
        self.assertEqual(code, 0)
        self.assertIn("приросту немає", output)

    def test_grown_missing_translation_fails_and_names_the_rule(self):
        self.write(
            [
                entry("MissingTranslation", line=1),
                entry("UseKtx", file="src/main/java/App.kt", line=5),
                entry("MissingTranslation", line=2),
            ]
        )
        self.commit("+1 MissingTranslation")
        code, output = self.run_guard(self.base, "HEAD")
        self.assertEqual(code, 1)
        self.assertIn("MissingTranslation: приріст +1 (1 → 2)", output)
        self.assertIn("решта правил", output)

    def test_grown_other_rule_fails_too(self):
        self.write(
            [
                entry("MissingTranslation", line=1),
                entry("UseKtx", file="src/main/java/App.kt", line=5),
                entry("UseKtx", file="src/main/java/Other.kt", line=6),
            ]
        )
        self.commit("+1 UseKtx")
        code, output = self.run_guard(self.base, "HEAD")
        self.assertEqual(code, 1)
        self.assertIn("UseKtx: приріст +1", output)

    def test_allowance_lets_a_declared_growth_through(self):
        self.write(
            [
                entry("MissingTranslation", line=1),
                entry("UseKtx", file="src/main/java/App.kt", line=5),
                entry("MissingTranslation", line=2),
            ]
        )
        self.commit("+1 MissingTranslation")
        code, output = self.run_guard(self.base, "HEAD", allowance="MissingTranslation=+1")
        self.assertEqual(code, 0)
        self.assertIn("приріст лише в межах дозволу", output)

    def test_worktree_flag_reads_the_uncommitted_file(self):
        self.write(
            [
                entry("MissingTranslation", line=1),
                entry("UseKtx", file="src/main/java/App.kt", line=5),
                entry("MissingTranslation", line=2),
            ]
        )  # без коміту
        code, output = self.run_guard(self.base, "--worktree")
        self.assertEqual(code, 1)
        self.assertIn("робоче дерево", output)

    def test_unresolvable_base_ref_fails_loudly(self):
        code, output = self.run_guard("no-such-ref", "HEAD")
        self.assertEqual(code, 2)
        self.assertIn("не вдався", output)


class MachinePathGuardTest(unittest.TestCase):
    """#1196 не послаблено: ґард у CI лишився тим самим і ловить ті самі шляхи."""

    def machine_path_grep(self) -> str:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        match = re.search(r"grep -qE '([^']+)' app/lint-baseline\.xml", workflow)
        self.assertIsNotNone(match, "у ci.yml більше немає ґарда машинних шляхів")
        return match.group(1)

    def test_guard_still_refuses_every_machine_path(self):
        pattern = self.machine_path_grep()
        for machine_path in (".worktrees/w1222/app/x.xml", "$HOME/checkout/app/x.xml", "$GRADLE_USER_HOME/caches/x.xml"):
            line = f'            file="{machine_path}"'
            self.assertIsNotNone(re.search(pattern, line), f"ґард пропустив {machine_path}")

    def test_guard_lets_a_repository_relative_path_through(self):
        pattern = self.machine_path_grep()
        self.assertIsNone(re.search(pattern, '            file="src/main/res/values/strings.xml"'))

    def test_guard_accepts_the_checked_in_baseline(self):
        pattern = self.machine_path_grep()
        self.assertIsNone(re.search(pattern, BASELINE.read_text(encoding="utf-8")))

if __name__ == "__main__":
    unittest.main()
