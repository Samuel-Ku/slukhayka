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
            payload["partitions"]["room-robolectric"],
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
            {"pure-jvm", "room-robolectric"}, set(payload["partitions"])
        )

    def test_shared_or_unknown_change_falls_back_to_full_suite(self):
        self.seed_three_partitions()

        for changed_file in ("app/build.gradle.kts", "app/schemas/16.json", "README.md"):
            with self.subTest(changed_file=changed_file):
                result = self.run_cli("select", "--changed-file", changed_file)
                self.assertTrue(json.loads(result.stdout)["fullSuite"])

    def test_no_changed_files_falls_back_to_full_suite(self):
        self.seed_three_partitions()

        result = self.run_cli("select")

        self.assertTrue(json.loads(result.stdout)["fullSuite"])


if __name__ == "__main__":
    unittest.main()
