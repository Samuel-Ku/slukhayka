import io
import json
import unittest
from unittest.mock import patch

from scripts.crash_collect import CollectError, CrashlyticsV1AlphaReader, event_to_group


def event(**overrides):
    value = {
        "platform": "ANDROID",
        "issue": {"errorType": "FATAL", "signals": []},
        "version": {"displayVersion": "1.3.8"},
        "customKeys": {
            "app_visibility": "background",
            "playback_state": "playing",
            "playback_service": "started",
            "audio_origin": "remote",
            "cast_active": "false",
        },
        "exceptions": [{
            "type": "java.lang.IllegalStateException",
            "frames": [{
                "owner": "DEVELOPER",
                "symbol": "com.slukhayka.audiobooks.player.PlaybackService.stop",
                "file": "PlaybackService.kt",
                "line": "123",
            }],
        }],
        # Fields from the official response which are deliberately never
        # copied across the trust boundary.
        "installationUuid": "must-not-leave-collector",
        "sessionId": "must-not-leave-collector",
        "logs": [{"content": "book title"}],
    }
    value.update(overrides)
    return value


class CrashCollectTest(unittest.TestCase):
    def test_projects_official_event_to_the_existing_sanitized_contract(self):
        group = event_to_group(event())

        self.assertEqual("fatal", group.event_type)
        self.assertEqual("1.3.8", group.app_version)
        self.assertEqual(["com.slukhayka.audiobooks.player.PlaybackService.stop(PlaybackService.kt:123)"], group.details["frames"])
        self.assertNotIn("must-not-leave", group.issue_body)
        self.assertNotIn("book title", group.issue_body)

    def test_uses_only_official_fresh_or_regressed_signal_for_queue_priority(self):
        group = event_to_group(event(issue={"errorType": "FATAL", "signals": [{"signal": "SIGNAL_REGRESSED"}]}))
        self.assertTrue(group.is_new_or_regressed)

    def test_missing_required_shape_or_non_app_frame_fails_closed(self):
        with self.assertRaises(CollectError):
            event_to_group(event(issue={"errorType": "NON_FATAL"}))
        with self.assertRaises(CollectError):
            event_to_group(event(exceptions=[{"type": "java.lang.IllegalStateException", "frames": [{
                "owner": "DEVELOPER", "symbol": "okhttp.RealCall.run", "file": "RealCall.kt", "line": "1"
            }]}]))
        with self.assertRaises(CollectError):
            event_to_group(event(customKeys={"app_visibility": "background"}))

    def test_projects_only_the_bounded_unexpected_playback_exit(self):
        keys = dict(event()["customKeys"], **{
            "exit_reason": "LOW_MEMORY", "exit_status": "0",
            "process_importance": "FOREGROUND_SERVICE", "rss_kb": "24",
            "pss_kb": "12", "app_version_code": "10308", "android_api": "36",
        })
        group = event_to_group(event(
            issue={"errorType": "NON_FATAL"}, customKeys=keys,
            exceptions=[{"type": "com.slukhayka.audiobooks.data.diagnostics.UnexpectedPlaybackExit", "frames": []}],
        ))

        self.assertEqual("unexpected_playback_exit", group.event_type)
        self.assertEqual("LOW_MEMORY", group.details["reason"])
        self.assertEqual("FOREGROUND_SERVICE", group.details["importance"])

    def test_reads_every_official_page_or_fails_closed_on_bad_token(self):
        responses = [
            {"events": [{"one": 1}], "nextPageToken": "page-two"},
            {"events": [{"two": 2}]},
        ]

        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self, *args): return None

        def open_page(request, timeout):
            return Response(json.dumps(responses.pop(0)).encode())

        with patch("scripts.crash_collect.urllib.request.urlopen", open_page):
            events = CrashlyticsV1AlphaReader("project", "app", "token").open_events()

        self.assertEqual([{"one": 1}, {"two": 2}], events)


if __name__ == "__main__":
    unittest.main()
