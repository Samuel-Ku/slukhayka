import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
READY_SCRIPT = REPO_ROOT / "scripts" / "wait-for-android-package-service.sh"
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"


class AndroidEmulatorReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.state = self.root / "package-check-count"
        self.fake_adb = self.root / "adb"
        self.fake_adb.write_text(
            "#!/usr/bin/env bash\n"
            "set -u\n"
            "if [[ $* == 'wait-for-device' ]]; then sleep \"${FAKE_ADB_WAIT_SECONDS:-0}\"; exit 0; fi\n"
            "if [[ $* == 'shell getprop sys.boot_completed' ]]; then echo 1; exit 0; fi\n"
            "if [[ $* == 'shell service check package' ]]; then\n"
            "  count=0\n"
            "  [[ -f $FAKE_ADB_STATE ]] && count=$(<\"$FAKE_ADB_STATE\")\n"
            "  count=$((count + 1))\n"
            "  printf '%s' \"$count\" > \"$FAKE_ADB_STATE\"\n"
            "  if (( count >= FAKE_ADB_READY_AFTER )); then\n"
            "    echo 'Service package: found'; exit 0\n"
            "  fi\n"
            "  echo 'Service package: not found'; exit 0\n"
            "fi\n"
            "if [[ $* == 'shell pm path android' ]]; then\n"
            "  count=0\n"
            "  [[ -f $FAKE_ADB_STATE ]] && count=$(<\"$FAKE_ADB_STATE\")\n"
            "  (( count >= FAKE_ADB_READY_AFTER )) && { echo package:/system/framework/framework-res.apk; exit 0; }\n"
            "  exit 1\n"
            "fi\n"
            "if [[ $* == 'devices -l' ]]; then echo 'emulator-5554 device'; exit 0; fi\n"
            "if [[ $* == 'shell getprop' ]]; then echo '[sys.boot_completed]: [0]'; exit 0; fi\n"
            "exit 1\n",
            encoding="utf-8",
        )
        self.fake_adb.chmod(self.fake_adb.stat().st_mode | stat.S_IXUSR)

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_readiness(
        self,
        *,
        ready_after: int,
        attempts: int,
        wait_seconds: str = "0",
        device_timeout_seconds: str = "2",
    ):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.root}:/usr/bin:/bin",
                "FAKE_ADB_STATE": str(self.state),
                "FAKE_ADB_READY_AFTER": str(ready_after),
                "FAKE_ADB_WAIT_SECONDS": wait_seconds,
                "ANDROID_DEVICE_READY_TIMEOUT_SECONDS": device_timeout_seconds,
                "ANDROID_PACKAGE_READY_ATTEMPTS": str(attempts),
                "ANDROID_PACKAGE_READY_SLEEP_SECONDS": "0",
            }
        )
        return subprocess.run(
            ["bash", str(READY_SCRIPT)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def test_waits_until_android_package_service_is_usable(self):
        result = self.run_readiness(ready_after=3, attempts=4)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("3", self.state.read_text(encoding="utf-8"))
        self.assertIn("Android package service is ready", result.stdout)

    def test_fails_with_diagnostics_when_package_service_never_appears(self):
        result = self.run_readiness(ready_after=99, attempts=2)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Android package service did not become ready", result.stderr)
        self.assertIn("emulator-5554 device", result.stderr)

    def test_fails_with_diagnostics_when_device_never_becomes_available(self):
        result = self.run_readiness(
            ready_after=1,
            attempts=1,
            wait_seconds="2",
            device_timeout_seconds="1",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Android device did not become available within 1s", result.stderr)
        self.assertIn("emulator-5554 device", result.stderr)

    def test_workflow_builds_apks_before_starting_the_emulator(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        kvm = workflow.index("Enable KVM access")
        prebuild = workflow.index("Prebuild accessibility APKs")
        emulator = workflow.index("reactivecircus/android-emulator-runner@v2")

        self.assertLess(kvm, emulator)
        self.assertLess(prebuild, emulator)
        self.assertIn('sudo chmod 0666 /dev/kvm', workflow)
        self.assertIn(":app:assembleDebug :app:assembleDebugAndroidTest", workflow)
        self.assertIn("scripts/wait-for-android-package-service.sh", workflow)
        self.assertIn("target: google_atd", workflow)

    def test_workflow_persists_test_status_between_runner_shells(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertNotIn("test_status=", workflow)
        self.assertIn("accessibility-test-exit-status", workflow)
        self.assertIn(
            'test ! -f app/build/reports/androidTests/accessibility-test-exit-status || exit "$(cat app/build/reports/androidTests/accessibility-test-exit-status)"',
            workflow,
        )


if __name__ == "__main__":
    unittest.main()
