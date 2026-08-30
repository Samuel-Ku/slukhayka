import unittest
from scripts.crash_queue import CrashQueueItem, prioritize


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


if __name__ == "__main__":
    unittest.main()
