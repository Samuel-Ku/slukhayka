import subprocess
import unittest
from scripts.crash_diagnosis import validate_diagnosis, verify_diagnosis_red_loop


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
        self.assertEqual("needs-triage", validate_diagnosis(proven(red_exit_code="1")).status)
        self.assertEqual("needs-triage", validate_diagnosis({"red_command": "x"}).status)

    def test_verified_runner_must_observe_the_recorded_regression_fail(self):
        received = []

        def red_run(args, **kwargs):
            received.append((args, kwargs))
            return subprocess.CompletedProcess(args, returncode=1)

        verdict = verify_diagnosis_red_loop(proven(), run=red_run)

        self.assertEqual("ready-for-agent", verdict.status)
        self.assertEqual(["./gradlew", ":app:testDebugUnitTest", "--tests", "ExampleTest"], received[0][0])
        self.assertFalse(received[0][1]["shell"])

    def test_green_or_unsafe_command_never_becomes_ready(self):
        green = lambda args, **kwargs: subprocess.CompletedProcess(args, returncode=0)
        self.assertEqual("needs-triage", verify_diagnosis_red_loop(proven(), run=green).status)
        self.assertEqual("needs-triage", verify_diagnosis_red_loop(proven(red_command="rm -rf x"), run=green).status)


if __name__ == "__main__":
    unittest.main()
