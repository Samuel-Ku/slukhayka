import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_partition_coverage import verify


class VerifyPartitionCoverageTest(unittest.TestCase):
    def write_report(self, methods: dict[str, int]) -> Path:
        report = Path(self.temp_dir.name) / "report.xml"
        method_xml = "".join(
            f'<method name="{name}"><counter type="INSTRUCTION" missed="0" covered="{covered}"/></method>'
            for name, covered in methods.items()
        )
        report.write_text(
            '<report><package><class name="com/slukhayka/audiobooks/testing/PartitionCoverageProbe">'
            f"{method_xml}</class></package></report>",
            encoding="utf-8",
        )
        return report

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_accepts_coverage_from_all_three_partitions(self):
        report = self.write_report(
            {"pureJvm": 1, "roomRobolectric": 1, "composeRoborazzi": 1}
        )

        verify(report)

    def test_rejects_a_missing_partition_contribution(self):
        report = self.write_report(
            {"pureJvm": 1, "roomRobolectric": 0, "composeRoborazzi": 1}
        )

        with self.assertRaisesRegex(ValueError, "roomRobolectric"):
            verify(report)


if __name__ == "__main__":
    unittest.main()
