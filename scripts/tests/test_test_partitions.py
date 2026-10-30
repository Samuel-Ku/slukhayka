import json
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CLI = REPO_ROOT / "scripts" / "test_partitions.py"


class TestPartitionsCliTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_test(self, package: str, name: str, body: str = "") -> None:
        package_path = Path(*package.split("."))
        target = self.root / "app/src/test/java" / package_path / f"{name}.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(f"package {package}\n{body}\nclass {name}\n", encoding="utf-8")

    def run_cli(self, *args: str, expected_status: int = 0):
        result = subprocess.run(
            ["python3", str(CLI), "--root", str(self.root), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(expected_status, result.returncode, result.stderr)
        return result

    def seed_three_partitions(self) -> None:
        self.write_test("com.slukhayka.audiobooks.player", "SmartRewindTest")
        self.write_test(
            "com.slukhayka.audiobooks.data.db",
            "LibraryRoomTest",
            "import org.robolectric.RobolectricTestRunner\nimport androidx.room.Room\n"
            "import org.robolectric.annotation.Config\n@Config(sdk = [35])",
        )
        self.write_test(
            "com.slukhayka.audiobooks.ui.snapshots",
            "LibrarySnapshotTest",
            "import org.robolectric.RobolectricTestRunner\nimport com.github.takahirom.roborazzi.captureRoboImage",
        )

    def seed_ui_tree(self) -> None:
        """The shape the #1219 report describes: a component whose package has
        tests, a subpackage of it that has tests, the ancestor `ui` package
        (where `WidePlayerPaneTest` lives), the module root, and a sibling
        package the change must not select."""
        self.write_test("com.slukhayka.audiobooks.ui.components", "MetadataChipTest")
        self.write_test("com.slukhayka.audiobooks.ui.components.chip", "ChipContrastTest")
        self.write_test("com.slukhayka.audiobooks.ui", "WidePlayerPaneTest")
        self.write_test("com.slukhayka.audiobooks.ui", "AdaptiveNavigationLayoutTest")
        self.write_test("com.slukhayka.audiobooks", "RootContractTest")
        self.write_test("com.slukhayka.audiobooks.player", "SmartRewindTest")

    def test_validate_reports_an_exhaustive_disjoint_partition(self):
        self.seed_three_partitions()

        result = self.run_cli("validate")

        self.assertEqual(
            {
                "compose-roborazzi": 1,
                "pure-jvm": 1,
                "room-native": 1,
                "room-robolectric": 1,
                "total": 3,
            },
            json.loads(result.stdout),
        )

    def test_validate_rejects_a_test_matching_two_partitions(self):
        self.write_test(
            "com.slukhayka.audiobooks.ui.snapshots",
            "RoomSnapshotTest",
            "import org.robolectric.RobolectricTestRunner\n"
            "import androidx.room.Room\n"
            "import com.github.takahirom.roborazzi.captureRoboImage",
        )

        result = self.run_cli("validate", expected_status=2)

        self.assertIn("matched ['compose-roborazzi', 'room-robolectric']", result.stderr)

    def test_list_emits_fully_qualified_test_classes(self):
        self.seed_three_partitions()

        result = self.run_cli("list", "--partition", "pure-jvm")

        self.assertEqual("com.slukhayka.audiobooks.player.SmartRewindTest\n", result.stdout)

    def write_multi_class_test(self, package: str, name: str, classes: list[str]) -> str:
        """A *Test.kt file that declares several test classes (#1634)."""
        package_path = Path(*package.split("."))
        target = self.root / "app/src/test/java" / package_path / f"{name}.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        body = (
            "import org.robolectric.RobolectricTestRunner\n"
            + "\n".join(f"class {class_name}" for class_name in classes)
        )
        target.write_text(f"package {package}\n{body}\n", encoding="utf-8")
        return f"app/src/test/java/{package_path.as_posix()}/{name}.kt"

    def test_list_includes_every_top_level_test_class_in_a_file(self):
        # #1634 — CI runs the partition list; a second class in the same file
        # must not be silently absent from it.
        self.write_multi_class_test(
            "com.slukhayka.audiobooks.ui",
            "CanonicalStateFacadesTest",
            ["CanonicalStateFacadesGuardTest", "CanonicalStateFacadesBehaviorTest"],
        )

        listed = self.run_cli("list", "--partition", "room-robolectric").stdout.split()

        self.assertEqual(
            [
                "com.slukhayka.audiobooks.ui.CanonicalStateFacadesBehaviorTest",
                "com.slukhayka.audiobooks.ui.CanonicalStateFacadesGuardTest",
            ],
            sorted(listed),
        )

    def test_declared_class_name_wins_over_the_file_stem(self):
        # #1634 — the stem is only a fallback for a file that declares no
        # *Test class at all; it must never add a phantom class the source
        # does not contain.
        target = self.root / "app/src/test/java/com/slukhayka/audiobooks/ui/RenamedGateTest.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            "package com.slukhayka.audiobooks.ui\n"
            "import org.robolectric.RobolectricTestRunner\n"
            "class RealGateTest\n",
            encoding="utf-8",
        )

        listed = self.run_cli("list", "--partition", "room-robolectric").stdout.split()

        self.assertEqual(["com.slukhayka.audiobooks.ui.RealGateTest"], listed)

    def test_helper_classes_do_not_join_the_test_list(self):
        # #1636 — the «ends with Test» filter is what keeps fakes, abstract bases
        # and data holders out of the partition list. Without it a mutation on a
        # real repository adds ~20 helpers to the list CI runs — and every one of
        # them is not a test.
        target = self.root / "app/src/test/java/com/slukhayka/audiobooks/ui/FacadeTest.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            "package com.slukhayka.audiobooks.ui\n"
            "import org.robolectric.RobolectricTestRunner\n"
            "abstract class AbstractFacade\n"
            "class Helper\n"
            "class TestDataFactory\n"
            "class FacadeTest\n",
            encoding="utf-8",
        )

        listed = self.run_cli("list", "--partition", "room-robolectric").stdout.split()

        self.assertEqual(["com.slukhayka.audiobooks.ui.FacadeTest"], listed)

    def test_changed_test_file_selects_every_class_it_declares(self):
        # #1634 — `select --changed-file <the file>` used to look up only the
        # file stem, so a co-located class made the whole file "unknown" and
        # fell back to the full suite instead of its two classes.
        changed = self.write_multi_class_test(
            "com.slukhayka.audiobooks.ui",
            "CanonicalStateFacadesTest",
            ["CanonicalStateFacadesGuardTest", "CanonicalStateFacadesBehaviorTest"],
        )

        payload = json.loads(self.run_cli("select", "--changed-file", changed).stdout)

        self.assertFalse(payload["fullSuite"])
        self.assertEqual(
            {
                "room-robolectric-only": [
                    "com.slukhayka.audiobooks.ui.CanonicalStateFacadesBehaviorTest",
                    "com.slukhayka.audiobooks.ui.CanonicalStateFacadesGuardTest",
                ]
            },
            payload["partitions"],
        )

    def test_changed_production_package_selects_its_tests(self):
        self.seed_three_partitions()

        result = self.run_cli(
            "select",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
        )

        self.assertEqual(
            {
                "fullSuite": False,
                "partitions": {
                    "pure-jvm": ["com.slukhayka.audiobooks.player.SmartRewindTest"]
                },
                "reason": "matched changed logical modules",
            },
            json.loads(result.stdout),
        )

    def test_changed_production_package_selects_the_tests_of_every_ancestor_package(self):
        self.seed_ui_tree()

        result = self.run_cli(
            "select",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/ui/components/PlayerPane.kt",
        )

        payload = json.loads(result.stdout)
        self.assertFalse(payload["fullSuite"])
        # The ancestors above `ui.components` contribute their own tests —
        # `ui` is where `WidePlayerPaneTest` and `AdaptiveNavigationLayoutTest`
        # live — while the changed package keeps its whole subtree and the
        # sibling `player` package stays out.
        self.assertEqual(
            {
                "pure-jvm": [
                    "com.slukhayka.audiobooks.RootContractTest",
                    "com.slukhayka.audiobooks.ui.AdaptiveNavigationLayoutTest",
                    "com.slukhayka.audiobooks.ui.WidePlayerPaneTest",
                    "com.slukhayka.audiobooks.ui.components.MetadataChipTest",
                    "com.slukhayka.audiobooks.ui.components.chip.ChipContrastTest",
                ]
            },
            payload["partitions"],
        )

    def test_changed_web_path_selects_the_web_check_not_the_full_suite(self):
        self.seed_three_partitions()

        for changed_file in (
            "web/src/ui/adaptiveLayout.ts",
            "web/package-lock.json",
        ):
            with self.subTest(changed_file=changed_file):
                result = self.run_cli("select", "--changed-file", changed_file)

                payload = json.loads(result.stdout)
                self.assertFalse(payload["fullSuite"])
                self.assertEqual({}, payload["partitions"])
                self.assertEqual(["web"], payload["checks"])
                # The payload names the check's three steps, so a caller
                # routing it never has to guess that web costs an npm install.
                for step in ("npm ci", "tsc --noEmit", "vitest run"):
                    self.assertIn(step, payload["reason"])

    def test_changed_web_and_production_paths_select_both_surfaces(self):
        self.seed_three_partitions()

        result = self.run_cli(
            "select",
            "--changed-file",
            "web/src/ui/adaptiveLayout.ts",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
        )

        payload = json.loads(result.stdout)
        self.assertFalse(payload["fullSuite"])
        self.assertEqual(["web"], payload["checks"])
        self.assertEqual(
            {"pure-jvm": ["com.slukhayka.audiobooks.player.SmartRewindTest"]},
            payload["partitions"],
        )

    def test_changed_test_selects_that_exact_class(self):
        self.seed_three_partitions()

        result = self.run_cli(
            "select",
            "--changed-file",
            "app/src/test/java/com/slukhayka/audiobooks/data/db/LibraryRoomTest.kt",
        )

        payload = json.loads(result.stdout)
        self.assertFalse(payload["fullSuite"])
        self.assertEqual(
            ["com.slukhayka.audiobooks.data.db.LibraryRoomTest"],
            payload["partitions"]["room-native-sdk35"],
        )

    def test_changes_in_multiple_logical_modules_select_each_modules_tests(self):
        self.seed_three_partitions()

        result = self.run_cli(
            "select",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/data/db/Library.kt",
        )

        payload = json.loads(result.stdout)
        self.assertFalse(payload["fullSuite"])
        self.assertEqual(
            {"pure-jvm", "room-native-sdk35"}, set(payload["partitions"])
        )

    def test_validate_rejects_an_unknown_native_room_sdk_cohort(self):
        self.write_test(
            "com.slukhayka.audiobooks.data.db",
            "FutureRoomTest",
            "import org.robolectric.RobolectricTestRunner\n"
            "import androidx.room.Room\n"
            "@org.robolectric.annotation.Config(sdk = [37])",
        )

        result = self.run_cli("validate", expected_status=2)

        self.assertIn("unsupported SDK cohort", result.stderr)

    def test_documentation_only_change_selects_nothing(self):
        self.seed_three_partitions()

        for changed_files in (
            ("docs/plans/2026-10-27-retro-agent-environment.md",),
            ("AGENTS.md", "CONTRIBUTING.md"),
            ("README.md",),
            ("docs/adr/0039-source-request-gate.md", "docs/agents/swarm.md"),
            # A non-markdown file under docs/ is still documentation: the
            # prefix branch, not the suffix one (#1493, review of #1494 — the
            # prefix alone was unpinned, a mutation removing it survived).
            ("docs/screenshots/01-listen.webp",),
            ("docs/agents/fixtures/report.xml", "docs/adr/0001-recording.md"),
            # A markdown file under a code tree is documentation too: it cannot
            # change the web build, and the web check is reserved for the tree.
            ("web/README.md",),
        ):
            with self.subTest(changed_files=changed_files):
                arguments = ["select"]
                for changed_file in changed_files:
                    arguments += ["--changed-file", changed_file]
                result = self.run_cli(*arguments)
                payload = json.loads(result.stdout)
                self.assertFalse(payload["fullSuite"])
                self.assertEqual({}, payload["partitions"])
                self.assertEqual("documentation only", payload["reason"])

    def test_documentation_mixed_with_code_maps_the_code(self):
        self.seed_three_partitions()

        result = self.run_cli(
            "select",
            "--changed-file",
            "docs/plans/2026-10-27-retro-agent-environment.md",
            "--changed-file",
            "app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt",
        )

        payload = json.loads(result.stdout)
        self.assertFalse(payload["fullSuite"])
        self.assertEqual({"pure-jvm"}, set(payload["partitions"]))

    def test_shared_or_unknown_change_falls_back_to_full_suite(self):
        self.seed_three_partitions()

        # A documentation file is no longer part of this loop: it selects
        # nothing (#1493), and test_documentation_only_change_selects_nothing
        # pins that separately.
        for changed_file in ("app/build.gradle.kts", "app/schemas/16.json"):
            with self.subTest(changed_file=changed_file):
                result = self.run_cli("select", "--changed-file", changed_file)
                self.assertTrue(json.loads(result.stdout)["fullSuite"])

    def test_no_changed_files_falls_back_to_full_suite(self):
        self.seed_three_partitions()

        result = self.run_cli("select")

        self.assertTrue(json.loads(result.stdout)["fullSuite"])


if __name__ == "__main__":
    unittest.main()
