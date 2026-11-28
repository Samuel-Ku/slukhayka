import copy
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import device_ci
import poco_ci_worker


class TrustedRunTest(unittest.TestCase):
    def setUp(self):
        self.run = {'head_sha': 'a' * 40, 'workflow_id': 17,
                    'event': 'workflow_dispatch', 'head_branch': 'main',
                    'status': 'completed', 'conclusion': 'success',
                    'head_repository': {'full_name': poco_ci_worker.REPO}}

    def test_successful_completed_main_build_is_accepted(self):
        self.assertEqual(poco_ci_worker.trusted_run(self.run, 17, {'status': 'identical'}), 'a' * 40)
        self.assertEqual(poco_ci_worker.trusted_run(self.run, 17, {'status': 'ahead'}), 'a' * 40)

    def test_pull_request_and_other_workflow_artifacts_are_refused(self):
        for key, value in [('event', 'pull_request'), ('workflow_id', 18),
                           ('head_branch', 'feature'), ('status', 'in_progress'),
                           ('conclusion', 'failure'), ('head_sha', '../main'),
                           ('head_repository', {'full_name': 'fork/slukhayka'}),
                           ('head_repository', None)]:
            with self.subTest(field=key, value=value):
                run = copy.deepcopy(self.run)
                run[key] = value
                with self.assertRaises(device_ci.DeviceError):
                    poco_ci_worker.trusted_run(run, 17, {'status': 'identical'})

    def test_main_ref_text_without_actual_ancestry_is_refused(self):
        for state in ['behind', 'diverged', None]:
            with self.subTest(state=state):
                with self.assertRaises(device_ci.DeviceError):
                    poco_ci_worker.trusted_run(self.run, 17, {'status': state})

    def test_unready_device_stops_before_any_github_call(self):
        args = SimpleNamespace(serial='poco')
        with patch.object(poco_ci_worker.device_ci.Device, 'preflight',
                          side_effect=device_ci.DeviceError('INJECT_EVENTS')):
            with patch.object(poco_ci_worker, 'gh') as github:
                with self.assertRaisesRegex(device_ci.DeviceError, 'INJECT_EVENTS'):
                    poco_ci_worker.execute(args)
                github.assert_not_called()


if __name__ == '__main__':
    unittest.main()
