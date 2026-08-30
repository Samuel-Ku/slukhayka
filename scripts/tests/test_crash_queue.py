import dataclasses
import unittest
from scripts.crash_queue import CrashQueueItem, prioritize, select_groups
from scripts.crash_tracer import SanitizationError, normalize_group


class CrashQueueTest(unittest.TestCase):
    def test_priority_and_cap_keep_remaining_groups_visible(self):
        selected, queued = prioritize([
            CrashQueueItem("fatal-high", "fatal", 100),
            CrashQueueItem("playback", "unexpected_playback_exit", 1),
            CrashQueueItem("anr-new", "anr", 8, True),
            CrashQueueItem("fatal-low", "fatal", 1),
        ])
        self.assertEqual(["playback", "fatal-high", "anr-new"], [item.key for item in selected])
        self.assertEqual(["fatal-low"], [item.key for item in queued])

    def test_selects_at_most_three_and_keeps_validated_remainder_visible(self):
        def group(event_type, installs, fresh=False):
            payload = {
                "event_type": event_type, "app_version": "1.3.8",
                "affected_install_count": installs, "event_count": installs,
                "context": {"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
            }
            if event_type == "fatal":
                payload["exception"] = {"type": f"java.lang.IllegalStateException{installs}", "frames": []}
            else:
                payload["exit"] = {"reason": "LOW_MEMORY", "status": 0, "importance": "CACHED", "rss_kb": 1, "pss_kb": 1, "android_api": 36}
            return dataclasses.replace(normalize_group(payload), is_new_or_regressed=fresh)

        groups = [group("fatal", 1), group("fatal", 8), group("unexpected_playback_exit", 1), group("fatal", 2, True)]
        selected, retained = select_groups({"groups": [dataclasses.asdict(group) for group in groups], "rejected": 0})

        self.assertEqual(3, len(selected))
        self.assertEqual(1, len(retained))
        self.assertEqual("unexpected_playback_exit", selected[0].event_type)

    def test_rejects_a_tampered_or_duplicate_collector_artifact(self):
        group = normalize_group({
            "event_type": "fatal", "app_version": "1.3.8", "affected_install_count": 1, "event_count": 1,
            "exception": {"type": "java.lang.IllegalStateException", "frames": []},
            "context": {"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
        })
        projection = dataclasses.asdict(group)
        with self.assertRaises(SanitizationError):
            select_groups({"groups": [projection, projection], "rejected": 0})


if __name__ == "__main__":
    unittest.main()
