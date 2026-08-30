import dataclasses
import unittest

from scripts.crash_diagnose_batch import diagnose_batch, triage_batch
from scripts.crash_tracer import SanitizationError, normalize_group


def group(suffix):
    return dataclasses.asdict(normalize_group({
        "event_type": "fatal", "app_version": "1.3.0", "affected_install_count": suffix, "event_count": suffix,
        "exception": {"type": f"java.lang.IllegalStateException{suffix}", "frames": []},
        "context": {"app_visibility": "foreground", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
    }))


class CrashDiagnoseBatchTest(unittest.TestCase):
    def test_only_the_selected_three_cross_the_diagnosis_boundary(self):
        seen = []

        def worker(value):
            seen.append(value["fingerprint"])
            return value, type("Verdict", (), {"status": "needs-triage", "reason": "test"})(), None

        items = [group(1), group(2), group(3)]
        result = diagnose_batch({"diagnose": items, "retained": [group(4)]}, worker)

        self.assertEqual([item["fingerprint"] for item in items], seen)
        self.assertEqual(3, len(result["diagnoses"]))

    def test_more_than_three_or_a_tampered_result_fails_closed(self):
        with self.assertRaises(SanitizationError):
            diagnose_batch({"diagnose": [group(1), group(2), group(3), group(4)], "retained": []})

        def wrong_worker(value):
            return {"fingerprint": "wrong"}, type("Verdict", (), {"status": "needs-triage", "reason": "test"})(), None

        with self.assertRaises(SanitizationError):
            diagnose_batch({"diagnose": [group(1)], "retained": []}, wrong_worker)

    def test_unavailable_model_keeps_every_selected_group_in_needs_triage(self):
        items = [group(1), group(2)]
        result = triage_batch({"diagnose": items, "retained": []}, "diagnosis proxy unavailable")

        self.assertEqual([item["fingerprint"] for item in items], [item["fingerprint"] for item in result["diagnoses"]])
        self.assertTrue(all(item["status"] == "needs-triage" for item in result["diagnoses"]))


if __name__ == "__main__":
    unittest.main()
