import unittest
from scripts.crash_implementation import gate


class CrashImplementationTest(unittest.TestCase):
    def test_all_guards_must_pass_before_branch_proposal(self):
        self.assertEqual("codex/crash-42", gate(42, "ready-for-agent", 1, False).branch)
        self.assertFalse(gate(42, "needs-triage", 1, False).allowed)
        self.assertFalse(gate(42, "ready-for-agent", 0, False).allowed)
        self.assertFalse(gate(42, "ready-for-agent", 1, True).allowed)


if __name__ == "__main__":
    unittest.main()
