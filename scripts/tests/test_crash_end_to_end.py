"""#418 synthetic smoke: no external service or raw crash payload is needed."""
import dataclasses
import unittest

from scripts.crash_diagnose_batch import diagnose_batch
from scripts.crash_implementation import gate_contract
from scripts.crash_publish import publish_queue
from scripts.crash_regression import decide
from scripts.crash_tracer import FakeIssuePublisher, normalize_group


class CrashEndToEndTest(unittest.TestCase):
    def test_consented_signal_to_one_ready_issue_and_reviewable_branch(self):
        # This is the only shape an app signal may have after the Android
        # consent boundary: bounded enums and aggregates, never media/user data.
        group = dataclasses.asdict(normalize_group({
            "event_type": "unexpected_playback_exit", "app_version": "1.3.8",
            "affected_install_count": 2, "event_count": 3,
            "exit": {"reason": "LOW_MEMORY", "status": 0, "importance": "CACHED", "rss_kb": 10, "pss_kb": 5, "android_api": 35},
            "context": {"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
        }))
        contract = {
            "red_command": "python3 -m unittest ExampleTest", "red_exit_code": 1,
            "reproduction": "Use the minimal exit fixture.", "hypotheses": ["A", "B", "C"],
            "evidence": ["The named test was red."], "regression_test": "ExampleTest", "cleanup_plan": "Remove temporary instrumentation.",
        }

        def proven_worker(value):
            return value, type("Verdict", (), {"status": "ready-for-agent", "reason": "verified"})(), contract

        diagnoses = diagnose_batch({"diagnose": [group], "retained": []}, proven_worker)
        publisher = FakeIssuePublisher()
        publish_queue({"diagnose": [group], "retained": []}, publisher, diagnoses)
        red = lambda args, **kwargs: type("Result", (), {"returncode": 1})()
        implementation = gate_contract(42, contract, False, red)

        self.assertEqual(["ready-for-agent"], publisher.issues[0].labels)
        self.assertNotIn("raw", publisher.issues[0].body.lower())
        self.assertEqual("codex/crash-42", implementation.branch)
        self.assertTrue(decide("1.3.9", "1.3.8", True).reopen)
        self.assertFalse(decide("1.3.8", "1.3.8", True).reopen)

    def test_blocked_diagnosis_never_gets_a_branch_or_ready_label(self):
        group = dataclasses.asdict(normalize_group({
            "event_type": "fatal", "app_version": "1.3.8", "affected_install_count": 1, "event_count": 1,
            "exception": {"type": "java.lang.IllegalStateException", "frames": []},
            "context": {"app_visibility": "foreground", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
        }))

        def blocked_worker(value):
            return value, type("Verdict", (), {"status": "needs-triage", "reason": "red not reproduced"})(), None

        diagnoses = diagnose_batch({"diagnose": [group], "retained": []}, blocked_worker)
        publisher = FakeIssuePublisher()
        publish_queue({"diagnose": [group], "retained": []}, publisher, diagnoses)

        self.assertEqual(["needs-triage"], publisher.issues[0].labels)
        self.assertFalse(gate_contract(42, {"bad": "contract"}, False, lambda *args, **kwargs: None).allowed)


if __name__ == "__main__":
    unittest.main()
