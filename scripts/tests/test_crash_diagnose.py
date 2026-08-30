import dataclasses
import json
import subprocess
import unittest

from scripts.crash_diagnose import diagnose
from scripts.crash_tracer import normalize_group


def group():
    return dataclasses.asdict(normalize_group({
        "event_type": "fatal", "app_version": "1.3.0", "affected_install_count": 2, "event_count": 3,
        "exception": {"type": "java.lang.IllegalStateException", "frames": []},
        "context": {"app_visibility": "foreground", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
    }))


class CrashDiagnoseTest(unittest.TestCase):
    def test_only_verified_contract_becomes_ready(self):
        model_contract = {
            "red_command": "python3 -m unittest ExampleTest",
            "red_exit_code": 1,
            "reproduction": "Open a minimal fixture.",
            "hypotheses": ["A", "B", "C"],
            "evidence": ["The selected test failed."],
            "regression_test": "ExampleTest",
            "cleanup_plan": "Remove temporary instrumentation.",
        }
        invocations = []

        def run(args, **kwargs):
            invocations.append(args)
            if args[0] == "opencode":
                return subprocess.CompletedProcess(args, 0, stdout=json.dumps(model_contract), stderr="")
            return subprocess.CompletedProcess(args, 1, stdout="", stderr="")

        projection, verdict = diagnose(group(), run=run)

        self.assertEqual(group()["fingerprint"], projection["fingerprint"])
        self.assertEqual("ready-for-agent", verdict.status)
        self.assertEqual("opencode", invocations[0][0])
        self.assertNotIn("shell", invocations[0])

    def test_model_failure_is_triaged_without_preserving_output(self):
        def run(args, **kwargs):
            return subprocess.CompletedProcess(args, 1, stdout="untrusted", stderr="untrusted")

        projection, verdict = diagnose(group(), run=run)

        self.assertEqual(group()["fingerprint"], projection["fingerprint"])
        self.assertEqual("needs-triage", verdict.status)


if __name__ == "__main__":
    unittest.main()
