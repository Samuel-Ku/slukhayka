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


class StableSignerTest(unittest.TestCase):
    def test_two_jobs_reuse_one_key_and_sign_both_apks_with_it(self):
        import tempfile
        from unittest.mock import Mock
        calls = []

        def tool(*args):
            calls.append(args)
            if args[0] == 'keytool':
                Path(args[args.index('-keystore') + 1]).write_bytes(b'fixed key')
            elif args[1] == 'sign':
                Path(args[args.index('--out') + 1]).write_bytes(b'signed APK')
            else:
                return 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
            return ''

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            signer = root / 'signer'
            device = Mock()
            device.ci_package_installed.return_value = False
            with patch.object(poco_ci_worker, 'signing_command', side_effect=tool):
                for number in range(2):
                    artifacts = root / str(number)
                    artifacts.mkdir()
                    _, cert = poco_ci_worker.sign_apks(device, artifacts, {}, signer)
                    self.assertEqual(cert, 'a' * 64)
            self.assertEqual(sum(c[0] == 'keytool' for c in calls), 1)
            signs = [c for c in calls if c[1] == 'sign']
            self.assertEqual(len(signs), 4)
            self.assertEqual({c[c.index('--ks') + 1] for c in signs}, {str(signer / 'debug.keystore')})
            device.ci_package_installed.assert_called_once_with()

    def test_existing_ci_app_with_lost_key_is_refused_without_key_generation(self):
        import tempfile
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            device = Mock()
            device.ci_package_installed.return_value = True
            with patch.object(poco_ci_worker, 'signing_command') as tool:
                with self.assertRaisesRegex(device_ci.DeviceError, 'signer is missing'):
                    poco_ci_worker.sign_apks(device, Path(directory), {}, Path(directory) / 'signer')
                tool.assert_not_called()


if __name__ == '__main__':
    unittest.main()
