import unittest
from scripts.crash_diagnosis import validate_diagnosis


def proven(**overrides):
    value = {
        "red_command": "./gradlew :app:testDebugUnitTest --tests ExampleTest",
        "red_exit_code": 1,
        "reproduction": "Open the minimal synthetic fixture.",
        "hypotheses": ["A", "B", "C"],
        "evidence": ["test failed before fix"],
        "regression_test": "ExampleTest",
        "cleanup_plan": "Remove temporary instrumentation.",
    }
    value.update(overrides)
    return value


class CrashDiagnosisTest(unittest.TestCase):
    def test_proven_contract_is_ready(self):
        self.assertEqual("ready-for-agent", validate_diagnosis(proven()).status)

    def test_no_red_loop_and_malformed_output_are_triaged(self):
        self.assertEqual("needs-triage", validate_diagnosis(proven(red_exit_code=0)).status)
        self.assertEqual("needs-triage", validate_diagnosis({"red_command": "x"}).status)


if __name__ == "__main__":
    unittest.main()
