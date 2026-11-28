import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'device_ci.py'
spec = importlib.util.spec_from_file_location('device_ci', MODULE)
device_ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device_ci)


class DeviceCiTest(unittest.TestCase):
    def test_another_device_job_is_refused_without_waiting(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict('os.environ', {'XDG_RUNTIME_DIR': directory}):
                with device_ci.device_lock():
                    with self.assertRaisesRegex(device_ci.DeviceError, 'reserved'):
                        with device_ci.device_lock():
                            self.fail('second device job entered the lock')

    def test_wrong_model_stops_before_input_or_install(self):
        calls = []

        def adb(*args, **kwargs):
            calls.append(args)
            return {'get-state': 'device', 'ro.product.model': 'IN2023',
                    'ro.product.device': 'alioth', 'sys.boot_completed': '1',
                    'KEYCODE_UNKNOWN': ''}[args[-1]]

        device = device_ci.Device('reserved-poco')
        with patch.object(device, 'adb', side_effect=adb):
            with self.assertRaisesRegex(device_ci.DeviceError, 'model'):
                device.preflight()
        self.assertEqual(calls, [('get-state',), ('shell', 'getprop', 'ro.product.model')])

    def test_miui_injection_denial_is_not_ready(self):
        device = device_ci.Device('poco')
        replies = ['device', 'M2012K11AG', 'alioth', '1',
                   'java.lang.SecurityException: Injecting input events requires INJECT_EVENTS permission']
        with patch.object(device, 'adb', side_effect=replies):
            with self.assertRaisesRegex(device_ci.DeviceError, 'INJECT_EVENTS'):
                device.preflight()

    def test_ready_preflight_targets_every_command_to_one_serial(self):
        replies = ['device', 'M2012K11AG', 'alioth', '1', '']
        completed = [subprocess.CompletedProcess([], 0, text, '') for text in replies]
        with patch.object(subprocess, 'run', side_effect=completed) as run:
            facts = device_ci.Device('poco').preflight()
        self.assertEqual(facts['model'], 'M2012K11AG')
        self.assertEqual(facts['serial'], 'poco')
        for call in run.call_args_list:
            self.assertEqual(call.args[0][:3], ['adb', '-s', 'poco'])
        self.assertEqual(run.call_args.args[0][-3:], ['input', 'keyevent', 'KEYCODE_UNKNOWN'])

    def test_adb_nonzero_is_a_failure(self):
        result = subprocess.CompletedProcess([], 1, '', 'device offline')
        with patch.object(subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(device_ci.DeviceError, 'device offline'):
                device_ci.Device('poco').adb('get-state')

    def test_timeout_has_explicit_infrastructure_result(self):
        with patch.object(subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 20)):
            with self.assertRaisesRegex(device_ci.DeviceError, 'timed out'):
                device_ci.Device('poco').adb('get-state')

    def test_instrumentation_transport_exit_zero_does_not_mean_tests_passed(self):
        failed = 'INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n'
        with self.assertRaisesRegex(device_ci.DeviceError, 'test verdict'):
            device_ci.require_test_success(failed)

    def test_zero_test_run_is_not_pass(self):
        with self.assertRaises(device_ci.DeviceError):
            device_ci.require_test_success('OK (0 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_runner_crash_is_not_pass(self):
        with self.assertRaises(device_ci.DeviceError):
            device_ci.require_test_success('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n')

    def test_complete_positive_test_verdict_is_accepted(self):
        device_ci.require_test_success('INSTRUMENTATION_RESULT: stream=\nTime: 4\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_partial_output_is_not_a_finished_run(self):
        with self.assertRaises(device_ci.DeviceError):
            device_ci.require_test_success('OK (2 tests)\n')

    def test_artifact_hash_mismatch_stops_before_adb(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in device_ci.APK_NAMES:
                (root / name).write_bytes(b'apk')
            (root / 'manifest.json').write_text(json.dumps({
                'sourceSha': 'a' * 40,
                'applicationId': device_ci.APP_ID,
                'sha256': {name: '0' * 64 for name in device_ci.APK_NAMES},
            }))
            with self.assertRaisesRegex(device_ci.DeviceError, 'hash'):
                device_ci.verify_artifacts(root)


class WorkflowAdmissionTest(unittest.TestCase):
    def setUp(self):
        import textwrap
        workflow = MODULE.parents[1] / '.github/workflows/poco-device.yml'
        raw = workflow.read_text().split('        run: |\n', 1)[1].split('\n\n', 1)[0]
        self.guard_script = textwrap.dedent(raw)

    def guard(self, ref, enabled):
        import os
        return subprocess.run(['bash', '-c', self.guard_script], capture_output=True, text=True,
                              env={**os.environ, 'GITHUB_REF': ref, 'DEVICE_ENABLED': enabled})

    def test_non_main_is_refused_before_any_checkout(self):
        result = self.guard('refs/heads/feature', 'true')
        self.assertEqual(result.returncode, 1)
        self.assertIn('only runs the reviewed main', result.stdout)

    def test_reviewed_enabled_main_is_admitted(self):
        self.assertEqual(self.guard('refs/heads/main', 'true').returncode, 0)


if __name__ == '__main__':
    unittest.main()
