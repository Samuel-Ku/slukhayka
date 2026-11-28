#!/usr/bin/env python3
"""Run prebuilt, trusted CI APKs only on the dedicated Poco F3.

No Gradle, uninstall, ADB-server restart, device settings or permission bypass.
The default command is a preflight; --artifacts-dir explicitly opts into a run.
"""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

APP_ID = 'com.slukhayka.audiobooks.ci.pocof3'
APK_NAMES = ('app-debug.apk', 'app-debug-androidTest.apk')
SUITES = (
    'com.slukhayka.audiobooks.accessibility.MainActivityAccessibilityTest',
    'com.slukhayka.audiobooks.audio.AudioPlaybackEspressoTest',
    'com.slukhayka.audiobooks.accessibility.UiSurfaceAuditTest',
    'com.slukhayka.audiobooks.accessibility.SettingsNavigationTest',
    'com.slukhayka.audiobooks.accessibility.BottomBarLargeTextLayoutTest',
)


class DeviceError(Exception):
    pass


class Device:
    def __init__(self, serial):
        if not serial or serial.startswith('-') or any(c.isspace() for c in serial):
            raise DeviceError('an explicit ADB serial is required')
        self.serial = serial

    def adb(self, *args, timeout=20):
        try:
            result = subprocess.run(['adb', '-s', self.serial, *args], text=True,
                                    capture_output=True, timeout=timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise DeviceError(f'adb {args[0]} timed out after {timeout}s') from error
        except OSError as error:
            raise DeviceError(f'adb unavailable: {error}') from error
        output = (result.stdout + result.stderr).strip()
        if result.returncode:
            raise DeviceError(f'adb {args[0]} failed: {output}')
        return output

    def preflight(self):
        if self.adb('get-state') != 'device':
            raise DeviceError('Poco is not in ADB device state')
        model = self.adb('shell', 'getprop', 'ro.product.model')
        if model != 'M2012K11AG':
            raise DeviceError(f'wrong device model: {model}; expected Poco F3 M2012K11AG')
        product = self.adb('shell', 'getprop', 'ro.product.device')
        if product != 'alioth':
            raise DeviceError(f'wrong device product: {product}; expected alioth')
        if self.adb('shell', 'getprop', 'sys.boot_completed') != '1':
            raise DeviceError('Poco boot is incomplete')
        # UNKNOWN is a no-op, but still crosses Android's real input permission
        # boundary. MIUI may print SecurityException even when adb exits zero.
        probe = self.adb('shell', 'input', 'keyevent', 'KEYCODE_UNKNOWN')
        if probe:
            raise DeviceError(f'unattended input unavailable: {probe}')
        return {'serial': self.serial, 'model': model, 'product': product}


def verify_artifacts(directory):
    try:
        manifest = json.loads((directory / 'manifest.json').read_text())
        if manifest['applicationId'] != APP_ID or not re.fullmatch(r'[0-9a-f]{40}', manifest['sourceSha']):
            raise DeviceError('unexpected artifact applicationId or sourceSha')
        for name in APK_NAMES:
            with (directory / name).open('rb') as stream:
                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if actual != manifest['sha256'][name]:
                raise DeviceError(f'artifact hash mismatch: {name}')
        return manifest
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise DeviceError(f'invalid artifact manifest: {error}') from error


def require_test_success(output):
    verdict = re.search(r'^OK \(([1-9][0-9]*) tests?\)\s*$', output, re.MULTILINE)
    finished = re.search(r'^INSTRUMENTATION_CODE: -1\s*$', output, re.MULTILINE)
    if not verdict or not finished or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]', output):
        raise DeviceError('instrumentation has no successful non-empty test verdict')


def run_suites(device, directory, reports):
    manifest = verify_artifacts(directory)
    facts = device.preflight()
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'source.json').write_text(json.dumps({**facts, 'sourceSha': manifest['sourceSha']}, indent=2) + '\n')
    for name in APK_NAMES:
        output = device.adb('install', '-r', '-t', str(directory / name), timeout=45)
        if not re.search(r'^Success\s*$', output, re.MULTILINE):
            raise DeviceError(f'APK installation did not succeed: {output}')
    permission = device.adb('shell', 'pm', 'grant', APP_ID, 'android.permission.POST_NOTIFICATIONS')
    if permission:
        raise DeviceError(f'notification permission unavailable: {permission}')
    failed = []
    try:
        for suite in SUITES:
            log = reports / f'{suite.rsplit(".", 1)[-1]}.log'
            try:
                output = device.adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', suite,
                                    f'{APP_ID}.test/com.slukhayka.audiobooks.IsolatedDatabaseTestRunner', timeout=600)
                log.write_text(output + '\n')
                require_test_success(output)
                print(f'PASS {suite}', flush=True)
            except DeviceError as error:
                with log.open('a') as stream:
                    stream.write(f'\nERROR: {error}\n')
                failed.append(suite)
                print(f'FAIL {suite}: {error}', flush=True)
                # A client timeout must not leave the instrumentation alive.
                device.adb('shell', 'am', 'force-stop', APP_ID)
    finally:
        device.adb('shell', 'am', 'force-stop', APP_ID)
    if failed:
        raise DeviceError(f'{len(failed)} of {len(SUITES)} suites failed; reports: {reports}')


@contextmanager
def device_lock():
    lock_path = Path(os.environ.get('XDG_RUNTIME_DIR', '/tmp')) / 'slukhayka-poco-f3.lock'
    with os.fdopen(os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600), 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise DeviceError('Poco F3 is reserved by another device job') from error
        yield


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--artifacts-dir', type=Path)
    parser.add_argument('--reports-dir', type=Path, default=Path('device-ci-reports'))
    args = parser.parse_args()
    try:
        with device_lock():
            device = Device(args.serial)
            if args.artifacts_dir:
                run_suites(device, args.artifacts_dir.resolve(), args.reports_dir.resolve())
            else:
                print(json.dumps(device.preflight()))
        return 0
    except (DeviceError, OSError) as error:
        print(f'BLOCKED: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
