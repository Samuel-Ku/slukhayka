import unittest

from scripts.crash_collect import CollectError, event_to_group


def event(**overrides):
    value = {
        "platform": "ANDROID",
        "issue": {"errorType": "FATAL"},
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

    def test_missing_required_shape_or_non_app_frame_fails_closed(self):
        with self.assertRaises(CollectError):
            event_to_group(event(issue={"errorType": "NON_FATAL"}))
        with self.assertRaises(CollectError):
            event_to_group(event(exceptions=[{"type": "java.lang.IllegalStateException", "frames": [{
                "owner": "DEVELOPER", "symbol": "okhttp.RealCall.run", "file": "RealCall.kt", "line": "1"
            }]}]))
        with self.assertRaises(CollectError):
            event_to_group(event(customKeys={"app_visibility": "background"}))


if __name__ == "__main__":
    unittest.main()
