import unittest
import subprocess
from scripts.crash_implementation import gate, gate_contract
from scripts.crash_implementation import select_contract
from scripts.crash_tracer import normalize_group
import dataclasses


class CrashImplementationTest(unittest.TestCase):
    def test_all_guards_must_pass_before_branch_proposal(self):
        self.assertEqual("codex/crash-42", gate(42, "ready-for-agent", 1, False).branch)
        self.assertFalse(gate(42, "needs-triage", 1, False).allowed)
        self.assertFalse(gate(42, "ready-for-agent", 0, False).allowed)
        self.assertFalse(gate(42, "ready-for-agent", 1, True).allowed)

    def test_verified_contract_must_be_red_again_before_edit_access(self):
        contract = {
            "red_command": "python3 -m unittest ExampleTest", "red_exit_code": 1,
            "reproduction": "Minimal fixture.", "hypotheses": ["A", "B", "C"],
            "evidence": ["Observed red test."], "regression_test": "ExampleTest", "cleanup_plan": "Remove instrumentation.",
        }
        red = lambda args, **kwargs: subprocess.CompletedProcess(args, 1)
        green = lambda args, **kwargs: subprocess.CompletedProcess(args, 0)

        self.assertTrue(gate_contract(42, contract, False, red).allowed)
        self.assertFalse(gate_contract(42, contract, False, green).allowed)
        self.assertFalse(gate_contract(42, {"bad": "contract"}, False, red).allowed)

    def test_only_requested_ready_issue_gets_a_contract(self):
        group = dataclasses.asdict(normalize_group({
            "event_type": "fatal", "app_version": "1.3.0", "affected_install_count": 1, "event_count": 1,
            "exception": {"type": "java.lang.IllegalStateException", "frames": []},
            "context": {"app_visibility": "foreground", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
        }))
        contract = {
            "red_command": "python3 -m unittest ExampleTest", "red_exit_code": 1, "reproduction": "Fixture.",
            "hypotheses": ["A", "B", "C"], "evidence": ["Red."], "regression_test": "ExampleTest", "cleanup_plan": "Clean up.",
        }
        plan = select_contract(
            {"diagnose": [group], "retained": []},
            {"diagnoses": [{"fingerprint": group["fingerprint"], "status": "ready-for-agent", "reason": "verified", "contract": contract}]},
            {"issues": [{"fingerprint": group["fingerprint"], "event_type": "fatal", "issue_number": 42, "action": "created"}]},
            42,
        )
        self.assertEqual(contract, plan["contract"])
        self.assertIsNone(select_contract({"diagnose": [group], "retained": []}, {"diagnoses": []}, {"issues": []}, 42))


if __name__ == "__main__":
    unittest.main()
