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

# Дві «локалі» з тим самим basename: саме на них видно, що порядок lint — не за
# шляхом (values-en < values-pl), а за basename і рядком.
PL_STRINGS = "src/main/res/values-pl/strings.xml"
EN_STRINGS = "src/main/res/values-en/strings.xml"
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


class EscapeTest(unittest.TestCase):
    def test_escapes_like_the_file_not_like_the_library(self):
        # `>` у baseline лишається сирим: у файлі 1163 такі атрибути проти 9
        # рукописних `&gt;`. Екранувати його означало б переписати чуже.
        self.assertEqual(lb.escape_attr("a > b"), "a > b")
        self.assertEqual(lb.escape_attr('x = "1"'), "x = &quot;1&quot;")
        self.assertEqual(lb.escape_attr("<string>"), "&lt;string>")
        self.assertEqual(lb.escape_attr("a && b"), "a &amp;&amp; b")
        self.assertEqual(lb.escape_attr("it's"), "it&apos;s")
        self.assertEqual(lb.escape_attr("a\nb"), "a&#xA;b")

    def test_ampersand_is_escaped_before_others(self):
        # `&` мусить екрануватися першим, інакше з `&lt;` вийшло б `&amp;lt;`.
        self.assertEqual(lb.escape_attr("<"), "&lt;")


class SerializeTest(unittest.TestCase):
    def test_entry_with_two_error_lines_and_location(self):
        subject = lb.Entry(
            issue_id="MissingTranslation",
            message='"app_name" is not translated in "pl" (Polish)',
            error_lines=('    <string name="app_name">Слухайка</string>', "            ~~~~~~~~~~~~~~~"),
            locations=(lb.Location(file="src/main/res/values/strings.xml", line=2, column=13),),
        )
        self.assertEqual(
            lb.serialize_entry(subject),
            '    <issue\n'
            '        id="MissingTranslation"\n'
            '        message="&quot;app_name&quot; is not translated in &quot;pl&quot; (Polish)"\n'
            '        errorLine1="    &lt;string name=&quot;app_name&quot;>Слухайка&lt;/string>"\n'
            '        errorLine2="            ~~~~~~~~~~~~~~~">\n'
            '        <location\n'
            '            file="src/main/res/values/strings.xml"\n'
            '            line="2"\n'
            '            column="13"/>\n'
            '    </issue>\n',
        )

    def test_entry_without_error_lines_and_location_metadata(self):
        subject = lb.Entry(
            issue_id="UnusedResources",
            message="The resource `R.drawable.x` appears to be unused",
            error_lines=(),
            locations=(lb.Location(file="src/main/res/drawable/x.webp"),),
        )
        self.assertEqual(
            lb.serialize_entry(subject),
            '    <issue\n'
            '        id="UnusedResources"\n'
            '        message="The resource `R.drawable.x` appears to be unused">\n'
            '        <location\n'
            '            file="src/main/res/drawable/x.webp"/>\n'
            '    </issue>\n',
        )

    def test_multiple_locations_are_siblings(self):
        subject = lb.Entry(
            issue_id="IconDuplicates",
            message="identical contents",
            error_lines=(),
            locations=(
                lb.Location(file="src/main/res/mipmap-hdpi/a.webp"),
                lb.Location(file="src/main/res/mipmap-hdpi/b.webp"),
            ),
        )
        self.assertEqual(lb.serialize_entry(subject).count("        <location\n"), 2)

    def test_round_trip_keeps_every_attribute(self):
        subject = entry("UseKtx", message="Use `edit`?", error_line="    prefs.edit()")
        self.assertEqual(lb.entry_from_xml(lb.ET.fromstring(lb.serialize_entry(subject))), subject)


class FormattingOrderingTest(unittest.TestCase):
    def test_order_is_by_basename_then_line_not_by_path(self):
        # Саме так стоїть справжній блок PrivateResource: values-pl/6 перед
        # values-en/10, хоч «values-en» лексикографічно менший за «values-pl».
        pl_six = entry("PrivateResource", file=PL_STRINGS, line=6)
        en_ten = entry("PrivateResource", file=EN_STRINGS, line=10)
        self.assertLess(lb.canonical_key(pl_six), lb.canonical_key(en_ten))

    def test_same_basename_and_line_falls_back_to_message_then_path(self):
        first = entry("PrivateResource", file=PL_STRINGS, line=10, message="A")
        second = entry("PrivateResource", file=PL_STRINGS, line=10, message="B")
        self.assertLess(lb.canonical_key(first), lb.canonical_key(second))

    def test_missing_line_sorts_before_numbered_lines(self):
        no_line = lb.Entry("UnusedResources", "m", (), (lb.Location(file="src/main/res/drawable/a.webp"),))
        numbered = entry("UnusedResources", file="src/main/res/drawable/b.webp", line=3)
        self.assertLess(lb.canonical_key(no_line), lb.canonical_key(numbered))

    def test_insert_anchor_is_the_last_entry_not_greater(self):
        entries = [
            entry("MissingTranslation", line=2),
            entry("MissingTranslation", line=700),
            entry("MissingTranslation", line=800),
            entry("UseKtx", line=1),
        ]
        anchor, has_block = lb.insert_anchor(entries, entry("MissingTranslation", line=750))
        self.assertTrue(has_block)
        self.assertEqual(anchor, 1)  # після 700, перед 800 — і блок UseKtx не чіпаємо

    def test_insert_anchor_before_the_block_when_smaller_than_everything(self):
        entries = [entry("UseKtx", line=1), entry("WebViewApiAvailability", line=1), entry("WebViewApiAvailability", line=2)]
        anchor, has_block = lb.insert_anchor(entries, entry("WebViewApiAvailability", line=0))
        self.assertTrue(has_block)
        self.assertEqual(anchor, 0)  # після UseKtx, тобто на початок блоку

    def test_insert_anchor_for_an_unknown_rule(self):
        entries = [entry("UseKtx", line=1)]
        self.assertEqual(lb.insert_anchor(entries, entry("BrandNewRule")), (-1, False))


class BaselineParsingTest(unittest.TestCase):
    def test_repository_baseline_is_understood_entry_by_entry(self):
        baseline = lb.load_baseline(BASELINE)
        broken, cosmetic = lb.verify_entries(baseline, str(BASELINE))
        self.assertEqual(broken, [])
        # Тривожний дзвінок, а не заморожування стилю: якщо форматер перестане
        # відтворювати більшість файлу, генератор більше не «пише як lint».
        self.assertGreater(len(baseline.entries) - len(cosmetic), len(baseline.entries) // 2)
        self.assertEqual(sum(lb.count_by_rule(baseline.entries).values()), len(baseline.entries))

    def test_order_deviation_is_small_and_measured_on_canonical_keys(self):
        # Коментар `insert_anchor` називає міру й число («17 із 1083»). Тест
        # тримає саме міру: позиції, де файл відрізняється від канонічно
        # відсортованого списку — не «переміщення» і не «застарілі рядки».
        baseline = lb.load_baseline(BASELINE)
        misplaced, stale = lb.order_deviation(baseline.entries)
        self.assertEqual(misplaced, 17)
        self.assertLess(misplaced, 25, "порядок baseline зіпсувався — перевір insert_anchor і коментар до нього")
        self.assertGreater(stale, 0)
        # Застарілі рядки — окрема річ і на порядок не впливають: саме тому
        # insert_anchor якорить за «останній ≤ нового», а не за рядок.
        self.assertNotEqual(misplaced, stale)

    def test_order_deviation_names_the_block_that_deviates(self):
        # Коментар каже «17 із 1083 його записів (решта 26 блоків канонічні)» —
        # тест тримає саме це: відхилення живе в одному блоці, і в тому самому.
        baseline = lb.load_baseline(BASELINE)
        by_rule = lb.order_deviation_by_rule(baseline.entries)
        deviating = {rule: count for rule, (count, _size) in by_rule.items() if count}
        self.assertEqual(deviating, {"MissingTranslation": 17})
        self.assertGreater(len(by_rule), 20, "блоків правил побільшало/поменшало — перевір, що міра ще говорить про один блок")

    def test_order_deviation_of_a_canonical_block_is_zero(self):
        entries = [
            entry("UseKtx", file="src/main/java/App.kt", line=1),
            entry("UseKtx", file="src/main/java/App.kt", line=2),
            entry("UseKtx", file="src/main/java/B.kt", line=1),
        ]
        misplaced, _ = lb.order_deviation(entries)
        self.assertEqual(misplaced, 0)

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

    def test_allowance_parsing_keeps_the_issue_it_belongs_to(self):
        allowance = lb.parse_allowance(
            " MissingTranslation=+3@1222 , UseKtx=1@1234 ", {"MissingTranslation", "UseKtx"}
        )
        self.assertEqual(allowance["MissingTranslation"], lb.Allowance(3, "1222"))
        self.assertEqual(allowance["UseKtx"], lb.Allowance(1, "1234"))
        self.assertEqual(lb.allowance_counts(allowance), {"MissingTranslation": 3, "UseKtx": 1})

    def test_allowance_parsing_refuses_a_typo_instead_of_ignoring_it(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslatio=1@1222", {"MissingTranslation"})

    def test_allowance_parsing_refuses_a_non_number(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslation=three@1222", {"MissingTranslation"})

    def test_allowance_parsing_refuses_a_missing_equals(self):
        with self.assertRaises(lb.BaselineError):
            lb.parse_allowance("MissingTranslation 3", {"MissingTranslation"})

    def test_allowance_parsing_refuses_a_grant_without_an_issue(self):
        # Вада, яку це закриває: змінна живе поза git і без терміну, тож без
        # назви тікета забутий дозвіл тихо пропускає приріст у кожному PR.
        with self.assertRaises(lb.BaselineError) as caught:
            lb.parse_allowance("MissingTranslation=+3", {"MissingTranslation"})
        self.assertIn("@тікета", str(caught.exception))

    def test_allowance_lines_state_usage_and_issue_even_when_unused(self):
        base = lb.Counter({"MissingTranslation": 1083})
        head = lb.Counter({"MissingTranslation": 1083})
        allowance = {"MissingTranslation": lb.Allowance(5, "1222")}
        line = lb.allowance_lines(base, head, allowance)[0]
        self.assertIn("0 із 5", line)
        self.assertIn("НЕ витрачено", line)
        self.assertIn("1222", line)

    def test_allowance_lines_show_a_spent_grant(self):
        base = lb.Counter({"MissingTranslation": 1083})
        head = lb.Counter({"MissingTranslation": 1085})
        allowance = {"MissingTranslation": lb.Allowance(5, "1222")}
        line = lb.allowance_lines(base, head, allowance)[0]
        self.assertIn("2 із 5", line)
        self.assertIn("витрачено", line)
        self.assertNotIn("НЕ витрачено", line)


class ReportSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.app = self.root / "app"
        (self.app / "src" / "main" / "res" / "values").mkdir(parents=True)
        self.report = self.root / "lint-results-debug.xml"

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_report(self, issues: str) -> None:
        self.report.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 9.1.1">\n'
            + issues
            + "\n</issues>\n",
            encoding="utf-8",
        )

    def issue(self, issue_id, severity="Error", file=None, line=9999):
        file = file or f"{self.app}/src/main/res/values/strings.xml"
        return (
            f'    <issue\n        id="{issue_id}"\n        severity="{severity}"\n'
            f'        message="msg {issue_id}"\n        errorLine1="код"\n        errorLine2="~~~">\n'
            f'        <location\n            file="{file}"\n            line="{line}"\n            column="13"/>\n'
            f"    </issue>\n"
        )

    def select(self, existing=(), severities=("fatal", "error"), only_ids=()):
        return lb.collect_report_entries(
            self.report, self.app, set(existing), set(only_ids), set(severities)
        )

    def test_paths_become_module_relative(self):
        self.write_report(self.issue("MissingTranslation"))
        selected, _ = self.select()
        self.assertEqual(selected[0].file, "src/main/res/values/strings.xml")

    def test_meta_entries_and_out_of_module_files_are_skipped(self):
        self.write_report(
            self.issue("LintBaseline", severity="Hint")
            + self.issue("GradleDependency", severity="Warning", file=f"{self.root}/gradle/libs.versions.toml")
        )
        selected, skipped = self.select(severities=("fatal", "error", "warning", "hint"))
        self.assertEqual(selected, [])
        self.assertEqual(len(skipped), 2)

    def test_warnings_are_not_baselined_by_default(self):
        self.write_report(self.issue("GradleDependency", severity="Warning", file=f"{self.app}/src/main/res/values/strings.xml"))
        selected, skipped = self.select()
        self.assertEqual(selected, [])
        self.assertIn("severity", skipped[0])

    def test_already_baselined_issue_is_not_added_twice(self):
        self.write_report(self.issue("MissingTranslation"))
        existing = {("MissingTranslation", "src/main/res/values/strings.xml", "msg MissingTranslation")}
        selected, skipped = self.select(existing=existing)
        self.assertEqual(selected, [])
        self.assertIn("уже в baseline", skipped[0])

    def test_nested_locations_are_flattened_into_siblings(self):
        self.write_report(
            '    <issue\n        id="IconDuplicates"\n        severity="Error"\n'
            '        message="identical">\n'
            f'        <location\n            file="{self.app}/src/main/res/mipmap/a.webp">\n'
            f'            <location\n                file="{self.app}/src/main/res/mipmap/b.webp"/>\n'
            "        </location>\n    </issue>\n"
        )
        selected, _ = self.select()
        self.assertEqual(
            [location.file for location in selected[0].locations],
            ["src/main/res/mipmap/a.webp", "src/main/res/mipmap/b.webp"],
        )


class AddCommandTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.app = self.root / "app"
        self.app.mkdir()
        self.baseline = self.app / "lint-baseline.xml"
        self.report = self.root / "lint-results-debug.xml"
        self.baseline.write_text(
            synthetic_baseline(
                [
                    lb.serialize_entry(entry("MissingTranslation", line=100, message="a")),
                    lb.serialize_entry(entry("MissingTranslation", line=200, message="b")),
                    lb.serialize_entry(entry("UseKtx", file="src/main/java/App.kt", line=5, message="ktx")),
                ]
            ),
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_report(self, issues: str) -> None:
        self.report.write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 9.1.1">\n' + issues + "\n</issues>\n",
            encoding="utf-8",
        )

    def run_add(self, *extra):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = lb.main(["--baseline", str(self.baseline), "add", "--report", str(self.report), *extra])
        return code, output.getvalue()

    def mt_issue(self, message="new", file=None, line=150):
        file = file or f"{self.app}/src/main/res/values/strings.xml"
        return (
            f'    <issue\n        id="MissingTranslation"\n        severity="Error"\n'
            f'        message="{message}"\n        errorLine1="код"\n        errorLine2="~~~">\n'
            f'        <location\n            file="{file}"\n            line="{line}"\n            column="13"/>\n'
            f"    </issue>\n"
        )

    def test_inserts_between_neighbours_with_exact_metadata(self):
        self.write_report(self.mt_issue(message="probe", line=150))
        code, output = self.run_add()
        self.assertEqual(code, 0)
        text = self.baseline.read_text(encoding="utf-8")
        baseline = lb.parse_baseline_text(text, "test")
        self.assertEqual([e.message for e in baseline.entries], ["a", "probe", "b", "ktx"])
        self.assertIn('line="150"', text)
        # Один порожній рядок між записами — і жодного зайвого.
        self.assertNotIn("    </issue>\n\n\n", text)
        self.assertIn("3 → 4 записів", output)

    def test_second_run_changes_nothing(self):
        self.write_report(self.mt_issue())
        self.run_add()
        after_first = self.baseline.read_text(encoding="utf-8")
        code, _ = self.run_add()
        self.assertEqual(code, 0)
        self.assertEqual(self.baseline.read_text(encoding="utf-8"), after_first)

    def test_dry_run_leaves_the_file_untouched(self):
        self.write_report(self.mt_issue())
        before = self.baseline.read_text(encoding="utf-8")
        code, output = self.run_add("--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("--dry-run", output)
        self.assertEqual(self.baseline.read_text(encoding="utf-8"), before)

    def test_unknown_rule_is_appended_at_the_end(self):
        self.write_report(
            '    <issue\n        id="BrandNewRule"\n        severity="Error"\n'
            '        message="probe"\n        errorLine1="код"\n        errorLine2="~~~">\n'
            f'        <location\n            file="{self.app}/src/main/java/App.kt"\n'
            '            line="9"\n            column="1"/>\n    </issue>\n'
        )
        self.run_add()
        baseline = lb.load_baseline(self.baseline)
        self.assertEqual(baseline.entries[-1].issue_id, "BrandNewRule")
        self.assertEqual(lb.count_by_rule(baseline.entries)["BrandNewRule"], 1)

    def test_machine_path_is_refused_and_nothing_is_written(self):
        self.write_report(self.mt_issue(file=".worktrees/w1222/app/src/main/res/values/strings.xml"))
        before = self.baseline.read_text(encoding="utf-8")
        code, output = self.run_add()
        self.assertEqual(code, 2)
        self.assertIn("машинний шлях", output)
        self.assertEqual(self.baseline.read_text(encoding="utf-8"), before)

    def test_dry_run_refuses_a_machine_path_too(self):
        # Огляд #1234: сухий прогін виходив ДО ґарда машинних шляхів і друкував
        # текст, який реальний прогін відкидає — тобто обіцяв те, чого не буде.
        self.write_report(self.mt_issue(file=".worktrees/w1222/app/src/main/res/values/strings.xml"))
        before = self.baseline.read_text(encoding="utf-8")
        code, output = self.run_add("--dry-run")
        self.assertEqual(code, 2)
        self.assertIn("машинний шлях", output)
        self.assertNotIn("Текст, який було б вставлено", output)
        self.assertEqual(self.baseline.read_text(encoding="utf-8"), before)

    def test_require_changes_turns_nothing_to_add_into_a_failure(self):
        self.write_report(self.mt_issue(message="a"))  # «a» уже в baseline
        code, _ = self.run_add("--require-changes")
        self.assertEqual(code, 1)


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

    def run_guard(self, *refs, allowance=None, summary_path=None):
        environment = dict(os.environ)
        if allowance is None:
            environment.pop("LINT_BASELINE_GROWTH_ALLOWANCE", None)
        else:
            environment["LINT_BASELINE_GROWTH_ALLOWANCE"] = allowance
        if summary_path is None:
            environment.pop("GITHUB_STEP_SUMMARY", None)
        else:
            environment["GITHUB_STEP_SUMMARY"] = str(summary_path)
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
        code, output = self.run_guard(self.base, "HEAD", allowance="MissingTranslation=+1@1222")
        self.assertEqual(code, 0)
        self.assertIn("приріст лише в межах дозволу", output)
        self.assertIn("1 із 1 — витрачено (1222)", output)

    def test_an_unspent_allowance_is_still_announced_without_any_growth(self):
        # Вада огляду #1234: «✅ без приросту» мовчало про виставлену змінну, а
        # вона живе поза git і без терміну — забутий дозвіл тихо пропускав би
        # до +N у кожному наступному PR.
        summary = self.root / "summary.md"
        code, output = self.run_guard(
            self.base,
            self.base,
            allowance="MissingTranslation=+5@1222,UseKtx=+2@1234",
            summary_path=summary,
        )
        self.assertEqual(code, 0)
        self.assertIn("Дозвіл LINT_BASELINE_GROWTH_ALLOWANCE (активний", output)
        self.assertIn("MissingTranslation: 0 із 5 — НЕ витрачено (1222)", output)
        self.assertIn("UseKtx: 0 із 2 — НЕ витрачено (1234)", output)
        self.assertIn("прибери змінну", output)
        summary_text = summary.read_text(encoding="utf-8")
        self.assertIn("LINT_BASELINE_GROWTH_ALLOWANCE", summary_text)
        self.assertIn("0 із 5", summary_text)

    def test_a_grant_without_an_issue_is_refused_by_the_guard_step(self):
        code, output = self.run_guard(self.base, self.base, allowance="MissingTranslation=+5")
        self.assertEqual(code, 2)
        self.assertIn("@тікета", output)

    def test_a_failing_run_still_prints_the_active_allowance(self):
        self.write(
            [
                entry("MissingTranslation", line=1),
                entry("UseKtx", file="src/main/java/App.kt", line=5),
                entry("MissingTranslation", line=2),
                entry("MissingTranslation", line=3),
            ]
        )
        self.commit("+2 MissingTranslation")
        code, output = self.run_guard(self.base, "HEAD", allowance="MissingTranslation=+1@1222")
        self.assertEqual(code, 1)
        self.assertIn("дозвіл 1 вичерпано", output)
        self.assertIn("2 із 1 — витрачено (1222)", output)

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

    def test_generator_pattern_is_byte_for_byte_the_ci_pattern(self):
        # Не «ті самі три підрядки», а той самий патерн: генератор і CI мусять
        # ловити рівно однаковий набір рядків, інакше `--dry-run` обіцяє одне,
        # а джоба робить інше.
        self.assertEqual(self.machine_path_grep(), lb.MACHINE_PATH_RE.pattern)

    def test_generator_and_ci_agree_on_every_sample(self):
        pattern = self.machine_path_grep()
        samples = (
            'file="src/main/res/values/strings.xml"',
            'file=".worktrees/w1222/app/src/main/res/values/strings.xml"',
            'file="$HOME/checkout/app/src/main/res/values/strings.xml"',
            'file="$GRADLE_USER_HOME/caches/transforms/x/strings.xml"',
            'file="../outside/strings.xml"',
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertEqual(
                    bool(re.search(pattern, sample)),
                    bool(lb.MACHINE_PATH_RE.search(sample)),
                    f"ґард CI і патерн генератора розійшлися на {sample}",
                )


if __name__ == "__main__":
    unittest.main()
