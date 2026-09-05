import dataclasses
import json
import subprocess
import unittest
from scripts.crash_diagnosis import diagnosis_prompt, parse_model_diagnosis, validate_diagnosis, verify_diagnosis_red_loop
from scripts.crash_tracer import normalize_group


def safe_group():
    return dataclasses.asdict(normalize_group({
        "event_type": "fatal", "app_version": "1.3.0", "affected_install_count": 2, "event_count": 3,
        "exception": {"type": "java.lang.IllegalStateException", "frames": []},
        "context": {"app_visibility": "foreground", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
    }))


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

    def test_prompt_revalidates_the_allowlisted_group_and_never_accepts_prose(self):
        group = safe_group()
        prompt = diagnosis_prompt(group)

        self.assertIn('"fingerprint": "' + group["fingerprint"], prompt)
        self.assertNotIn("https://", prompt)
        self.assertEqual(proven(), parse_model_diagnosis(json.dumps(proven())))
        with self.assertRaises(ValueError):
            parse_model_diagnosis("Here is the result: " + json.dumps(proven()))
        with self.assertRaises(ValueError):
            diagnosis_prompt({"raw_payload": "no"})

    def test_contract_rejects_potential_pii_or_secret_like_model_text(self):
        self.assertEqual("needs-triage", validate_diagnosis(proven(evidence=["email a@b.example"])).status)
        self.assertEqual("needs-triage", validate_diagnosis(proven(reproduction="https://private.example")).status)


if __name__ == "__main__":
    unittest.main()
