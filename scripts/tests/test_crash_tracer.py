import unittest
import dataclasses

from scripts.crash_tracer import (
    FakeIssuePublisher,
    SanitizationError,
    normalize_group,
    group_from_projection,
    publish_group,
)


def fatal_payload(**overrides):
    payload = {
        "event_type": "fatal",
        "app_version": "1.3.8",
        "affected_install_count": 4,
        "event_count": 7,
        "exception": {
            "type": "java.lang.IllegalStateException",
            "frames": [
                "com.slukhayka.audiobooks.player.PlaybackService.stop(PlaybackService.kt:123)"
            ],
        },
        "context": {
            "app_visibility": "background",
            "playback_state": "playing",
            "playback_service": "started",
            "audio_origin": "remote",
            "cast_active": False,
        },
    }
    payload.update(overrides)
    return payload


class CrashTracerTest(unittest.TestCase):
    def test_normalizes_only_safe_fields_and_renders_hidden_dedupe_marker(self):
        group = normalize_group(fatal_payload())

        self.assertEqual("fatal", group.event_type)
        self.assertEqual(4, group.affected_install_count)
        self.assertIn("<!-- crash-group:", group.issue_body)
        self.assertIn("PlaybackService.kt:123", group.issue_body)
        self.assertNotIn("book title", group.issue_body.lower())

    def test_unknown_or_personal_field_fails_closed(self):
        with self.assertRaises(SanitizationError):
            normalize_group(fatal_payload(listener_uid="private"))
        with self.assertRaises(SanitizationError):
            normalize_group(fatal_payload(exception={"type": "java.lang.IllegalStateException", "message": "book title", "frames": []}))

    def test_third_party_frame_and_unbounded_context_are_rejected(self):
        with self.assertRaises(SanitizationError):
            normalize_group(fatal_payload(exception={"type": "java.lang.IllegalStateException", "frames": ["okhttp.RealCall.run(RealCall.kt:1)"]}))
        with self.assertRaises(SanitizationError):
            normalize_group(fatal_payload(context={"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "https://private.example", "cast_active": False}))

    def test_repeated_group_updates_one_needs_triage_issue(self):
        publisher = FakeIssuePublisher()
        first = publish_group(normalize_group(fatal_payload()), publisher)
        second = publish_group(normalize_group(fatal_payload(event_count=9)), publisher)

        self.assertEqual("created", first.action)
        self.assertEqual("updated", second.action)
        self.assertEqual(1, len(publisher.issues))
        self.assertEqual(["needs-triage"], publisher.issues[0].labels)
        self.assertIn("9", publisher.issues[0].body)

    def test_only_a_newer_build_after_a_verified_merged_fix_reopens(self):
        publisher = FakeIssuePublisher()
        original = normalize_group(fatal_payload(app_version="1.3.7"))
        publish_group(original, publisher)
        issue = publisher.issues[0]
        issue.state = "CLOSED"
        issue.fixed_version = "1.3.7"
        issue.fix_was_merged = True
        issue.prior_pr_url = "https://github.com/Samuel-Ku/slukhayka/pull/99"

        same = publish_group(normalize_group(fatal_payload(app_version="1.3.7", event_count=8)), publisher)
        self.assertEqual("updated", same.action)
        self.assertEqual("CLOSED", issue.state)

        reopened = publish_group(normalize_group(fatal_payload(app_version="1.3.8", event_count=9)), publisher)
        self.assertEqual("reopened", reopened.action)
        self.assertEqual("OPEN", issue.state)
        self.assertEqual(["needs-triage"], issue.labels)
        self.assertIn("https://github.com/Samuel-Ku/slukhayka/pull/99", issue.body)

    def test_unexpected_exit_uses_typed_event_without_raw_trace(self):
        group = normalize_group({
            "event_type": "unexpected_playback_exit",
            "app_version": "1.3.8",
            "affected_install_count": 1,
            "event_count": 1,
            "exit": {"reason": "LOW_MEMORY", "status": 0, "importance": "FOREGROUND_SERVICE", "rss_kb": 20, "pss_kb": 10, "android_api": 36},
            "context": {"app_visibility": "background", "playback_state": "playing", "playback_service": "started", "audio_origin": "remote", "cast_active": False},
        })
        self.assertIn("LOW_MEMORY", group.issue_body)
        self.assertNotIn("trace", group.issue_body.lower())

    def test_downstream_rechecks_the_sanitized_artifact_and_fingerprint(self):
        projection = dataclasses.asdict(normalize_group(fatal_payload()))

        rebuilt = group_from_projection(projection)

        self.assertEqual(projection["fingerprint"], rebuilt.fingerprint)
        self.assertEqual("background", rebuilt.context["app_visibility"])
        projection["context"]["app_visibility"] = "secret"
        with self.assertRaises(SanitizationError):
            group_from_projection(projection)


if __name__ == "__main__":
    unittest.main()
