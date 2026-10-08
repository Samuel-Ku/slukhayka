#!/usr/bin/env python3
"""#533: capture independence and request-budget evidence on two QA emulators.

This is a preflight/trace tool, not an automatic playback verdict. Run only on
an integrated #524/#525 build. Human audio confirmation remains a separate AC.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess

PACKAGE = "com.slukhayka.audiobooks.debug"


def adb(serial, *arguments):
    return subprocess.run(["adb", "-s", serial, *arguments], check=True,
                          capture_output=True, text=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--a", required=True)
    parser.add_argument("--b", required=True)
    parser.add_argument("--head", required=True, help="Coordinator-approved integrated commit")
    parser.add_argument("--app-apk", required=True, type=pathlib.Path, help="Exact integrated APK installed on both emulators")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--action", choices=("preflight", "before", "after", "delta"), required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if root == output or root in output.parents:
        parser.error("Raw QA evidence belongs outside the public checkout")
    if args.a == args.b or not all(s.startswith("emulator-") for s in (args.a, args.b)):
        parser.error("Two different dedicated emulator serials are required")
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                          text=True, capture_output=True).stdout.strip()
    if head != args.head:
        parser.error("Current source is not the coordinator-approved integrated commit")
    if not args.app_apk.is_file():
        parser.error("The integrated APK is missing")
    with args.app_apk.open("rb") as apk:
        apk_sha = hashlib.file_digest(apk, "sha256").hexdigest()
    installations = []
    for role, serial in (("A", args.a), ("B", args.b)):
        if adb(serial, "shell", "getprop", "ro.kernel.qemu") != "1":
            parser.error("QA emulators only; no personal device")
        android_id = adb(serial, "shell", "settings", "get", "secure", "android_id")
        if not android_id or android_id == "null":
            parser.error("Independent Android instance identity is unavailable")
        package = adb(serial, "shell", "pm", "path", PACKAGE)
        if not package.startswith("package:") or "\n" in package:
            parser.error(f"One QA base APK is required on installation {role}")
        installed_apk = package.removeprefix("package:")
        if not installed_apk.startswith("/data/app/"):
            parser.error("Unexpected installed APK path")
        installed_sha = adb(serial, "shell", "sha256sum", installed_apk).split()[0]
        if installed_sha != apk_sha:
            parser.error(f"Installation {role} does not contain the exact integrated APK")
        data_path = adb(serial, "shell", "run-as", PACKAGE, "pwd")
        installations.append({"role": role, "serial": serial,
                              "instanceHash": hashlib.sha256(android_id.encode()).hexdigest(),
                              "appDataPath": data_path, "apkSha256": installed_sha})
    if installations[0]["instanceHash"] == installations[1]["instanceHash"]:
        parser.error("Both surfaces report the same Android instance identity")
    if args.action == "preflight":
        output.mkdir(parents=True, exist_ok=True)
        evidence = output / "installations.json"
        if evidence.exists():
            parser.error("Preserve previous evidence; choose a fresh output directory")
        evidence.write_text(json.dumps({"head": head, "apkSha256": apk_sha, "installations": installations,
            "sharedDelta": "NOT RUN", "selectedResolve": "NOT RUN",
            "sameEditionFallback": "NOT RUN", "humanSound": "NOT CONFIRMED"}, indent=2) + "\n")
    else:
        evidence = json.loads((output / "installations.json").read_text())
        if evidence["head"] != head or evidence["installations"] != installations:
            parser.error("Build or installation changed since preflight")
        for role, serial in (("A", args.a), ("B", args.b)):
            environment = dict(os.environ, ANDROID_SERIAL=serial, PKG=PACKAGE, DIR=str(output / role))
            subprocess.run(["bash", str(root / "scripts/source-budget-snapshot.sh"), args.action],
                           check=True, env=environment)
    print("Evidence captured. Shared delta, resolve, fallback and sound require their actual run.")


if __name__ == "__main__":
    main()
