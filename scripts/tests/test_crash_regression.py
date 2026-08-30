import unittest
from scripts.crash_regression import decide


class CrashRegressionTest(unittest.TestCase):
    def test_only_newer_version_after_merged_fix_reopens(self):
        self.assertFalse(decide("1.3.7", "1.3.7", True).reopen)
        self.assertFalse(decide("1.3.6", "1.3.7", True).reopen)
        reopened = decide("1.3.8", "1.3.7", True)
        self.assertTrue(reopened.reopen)
        self.assertEqual("needs-triage", reopened.label)


if __name__ == "__main__":
    unittest.main()
