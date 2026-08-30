import dataclasses
import unittest

from scripts.crash_publish import publish_queue
from scripts.crash_tracer import FakeIssuePublisher, normalize_group


def group(event_type, marker):
    payload = {
        "event_type": event_type, "app_version": "1.3.8",
        "affected_install_count": 1, "event_count": 1,
        "context": {"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
    }
    if event_type == "fatal":
        payload["exception"] = {"type": f"java.lang.IllegalStateException{marker}", "frames": []}
    else:
        payload["exit"] = {"reason": "LOW_MEMORY", "status": 0, "importance": "CACHED", "rss_kb": 1, "pss_kb": 1, "android_api": 36}
    return dataclasses.asdict(normalize_group(payload))


class CrashPublishTest(unittest.TestCase):
    def test_publishes_selected_and_retained_groups_without_hiding_the_queue(self):
        publisher = FakeIssuePublisher()
        result = publish_queue({"diagnose": [group("fatal", "One")], "retained": [group("fatal", "Two")]}, publisher)

        self.assertEqual(["created", "created"], [item.action for item in result])
        self.assertEqual(2, len(publisher.issues))
        self.assertTrue(all(issue.labels == ["needs-triage"] for issue in publisher.issues))

    def test_repeat_updates_existing_marker_instead_of_creating_a_duplicate(self):
        publisher = FakeIssuePublisher()
        queue = {"diagnose": [group("fatal", "One")], "retained": []}
        publish_queue(queue, publisher)
        result = publish_queue(queue, publisher)

        self.assertEqual("updated", result[0].action)
        self.assertEqual(1, len(publisher.issues))


if __name__ == "__main__":
    unittest.main()
