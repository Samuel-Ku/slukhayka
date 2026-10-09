#!/usr/bin/env python3
"""#620: run the real Android adapter across independent instrumentation processes.

Build first with -PacceptancePersistence=true; start the isolated demo Firestore
emulator using scripts/acceptance/firebase.json. No production project or device
is accepted. Raw outputs belong in the caller's directory outside the checkout.
"""
import argparse
import datetime
import json
import pathlib
import subprocess
import urllib.request
import uuid

PROJECT = "demo-slukhayka-acceptance"
PACKAGE = "com.slukhayka.audiobooks.debug"
RUNNER = PACKAGE + ".test/com.slukhayka.audiobooks.acceptance.PersistenceAcceptanceRunner"
CLASS = "com.slukhayka.audiobooks.acceptance.ListenerReviewPersistenceTest"


def device_command(serial, *arguments):
    return ["adb", "-s", serial, *arguments]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", type=pathlib.Path, required=True)
    parser.add_argument("--test-apk", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--case", choices=("ack", "rejection"), default="ack")
    parser.add_argument("--reconcile", choices=("manual", "automatic"), default="manual")
    parser.add_argument("--mutation", choices=("creation", "edit"), default="creation")
    args = parser.parse_args()
    if args.mutation == "edit" and args.case == "rejection" and args.reconcile != "automatic":
        parser.error("EDIT rejection requires automatic reconciliation and its exact FAILED event")
    if not args.serial.startswith("emulator-"):
        parser.error("Only a dedicated disposable Android emulator is accepted")
    root = pathlib.Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if output == root or root in output.parents:
        parser.error("Raw evidence must stay outside the public checkout")
    if output.exists():
        parser.error("Use a fresh output directory; earlier evidence is immutable")
    for apk in (args.app_apk, args.test_apk):
        if not apk.is_file():
            parser.error(f"Missing APK: {apk}")
    emulator = subprocess.run(device_command(args.serial, "shell", "getprop", "ro.kernel.qemu"), check=True, text=True, capture_output=True)
    if emulator.stdout.strip() != "1":
        parser.error("The selected surface is not an Android emulator")
    # A demo project cannot reach a real Firebase project, even with credentials.
    with urllib.request.urlopen(f"http://127.0.0.1:8089/v1/projects/{PROJECT}/databases/(default)/documents/book_reviews", timeout=5) as response:
        if response.status != 200:
            parser.error("Demo Firestore emulator is not ready")
    output.mkdir(parents=True)
    run = uuid.uuid4().hex
    records = []
    for apk in (args.app_apk, args.test_apk):
        subprocess.run(device_command(args.serial, "install", "-r", str(apk.resolve())), check=True)
    for phase in ("seed", "queue", "restart-offline", "reconnect"):
        # Host force-stop is real process death; no recreated ViewModel stands in.
        stopped = subprocess.run(device_command(args.serial, "shell", "am", "force-stop", PACKAGE), check=True, text=True, capture_output=True)
        command = device_command(args.serial, "shell", "am", "instrument", "-w", "-r",
                                 "-e", "class", CLASS, "-e", "acceptanceProject", PROJECT,
                                 "-e", "acceptanceRun", run, "-e", "acceptanceCase", args.case,
                                 "-e", "acceptanceReconcile", args.reconcile,
                                 "-e", "acceptanceMutation", args.mutation,
                                 "-e", "acceptancePhase", phase, RUNNER)
        completed = subprocess.run(command, text=True, capture_output=True, timeout=150)
        log = completed.stdout + completed.stderr
        (output / f"{phase}.log").write_text(log)
        passed = completed.returncode == 0 and "OK (1 test)" in log and "FAILURES!!!" not in log
        records.append({"phase": phase, "passed": passed, "returnCode": completed.returncode,
                        "forceStopReturnCode": stopped.returncode})
        (output / "result.json").write_text(json.dumps({"run": run, "project": PROJECT,
                "case": args.case, "reconcile": args.reconcile, "mutation": args.mutation, "serial": args.serial,
                "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "phases": records}, indent=2) + "\n")
        print(f"{phase}: {'PASS' if passed else 'FAIL'}", flush=True)
        if not passed:
            raise SystemExit(1)
    print("All persistence phases passed. This does not prove production security or audio.")


if __name__ == "__main__":
    main()
