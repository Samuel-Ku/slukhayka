#!/usr/bin/env python3
"""Supervise one explicit macOS attached-App composition run, or its owned packager."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLASS = "com.slukhayka.audiobooks.data.collective.ControlledAttachedAppRelatedCompositionTest"
METHOD = "actual attached App public import persists observed related cards for its public overview reader"
PREFIX = "com/slukhayka/audiobooks/"
PRIMARY = [PREFIX + "data/collective/ControlledAttachedAppRelatedCompositionTest.class",
           PREFIX + "data/collective/ControlledAppFixtureRunner.class"]
MAIN_CLASSES = [PREFIX + "App.class", PREFIX + "data/imports/LibraryImport.class",
                PREFIX + "data/db/AudiobookDao.class", PREFIX + "data/db/AudiobookDao_Impl.class",
                PREFIX + "data/imports/LibraryImport$importBookFromSource$2.class"]
DEFAULT_CASE = "related-import"
CASES = {
    DEFAULT_CASE: {"class": CLASS, "method": METHOD, "primary": PRIMARY, "main": MAIN_CLASSES},
    "overview-late-room": {
        "class": "com.slukhayka.audiobooks.data.collective.ControlledAttachedAppLateRoomOverviewTest",
        "method": "actual attached App live overview publishes a later same Room block without another refresh",
        "primary": [PREFIX + "data/collective/ControlledAttachedAppLateRoomOverviewTest.class",
                    PREFIX + "data/collective/ControlledAppFixtureRunner.class"],
        "main": ['com/slukhayka/audiobooks/App.class', 'com/slukhayka/audiobooks/ui/MainViewModel.class', 'com/slukhayka/audiobooks/ui/MainViewModel$refreshCollectiveBlocks$1.class', 'com/slukhayka/audiobooks/ui/MainViewModel$fillBlockCovers$1.class', 'com/slukhayka/audiobooks/data/db/AudiobookDatabase.class', 'com/slukhayka/audiobooks/data/db/AudiobookDao.class', 'com/slukhayka/audiobooks/data/db/AudiobookDao_Impl.class', 'com/slukhayka/audiobooks/data/collective/CollectiveOverviewBlocks.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore$activate$2.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore$active$2.class', 'com/slukhayka/audiobooks/data/collective/CollectiveFeedBlockCodec.class'],
        "source": "app/src/test/java/com/slukhayka/audiobooks/data/collective/ControlledAttachedAppLateRoomOverviewTest.kt",
        "sourceSHA256": "07e20d01eddab9bd6872370d8b94cdc8aadbe2656a82496f7716bd4c9ca1288f",
    },
    "arrivals-live-feed": {
        "class": 'com.slukhayka.audiobooks.data.collective.ControlledAttachedAppArrivalsCompositionTest',
        "method": 'actual attached App public live feed persists received arrivals for its public overview reader',
        "primary": [PREFIX + "data/collective/ControlledAttachedAppArrivalsCompositionTest.class",
                    PREFIX + "data/collective/ControlledAppFixtureRunner.class"],
        "main": ['com/slukhayka/audiobooks/App.class', 'com/slukhayka/audiobooks/App$sourceCatalog$2$1.class', 'com/slukhayka/audiobooks/App$sourceCatalog$2$2.class', 'com/slukhayka/audiobooks/App$observeSourceArrivals$3.class', 'com/slukhayka/audiobooks/data/catalog/SourceCatalog.class', 'com/slukhayka/audiobooks/data/catalog/SourceCatalog$refreshSourceFeeds$2.class', 'com/slukhayka/audiobooks/data/catalog/SourceCatalog$newFeedFor$outcome$1.class', 'com/slukhayka/audiobooks/data/catalog/FeedSnapshotRefresh.class', 'com/slukhayka/audiobooks/data/catalog/FeedSnapshotStore.class', 'com/slukhayka/audiobooks/data/catalog/WorkIndexStore.class', 'com/slukhayka/audiobooks/data/catalog/WorkIndexRefresher.class', 'com/slukhayka/audiobooks/data/collective/CollectiveFeedRefresh.class', 'com/slukhayka/audiobooks/data/collective/CollectiveOverviewBlocks.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore$activateIfUnchanged$2.class', 'com/slukhayka/audiobooks/data/collective/RoomCollectiveFeedBlockStore$active$2.class', 'com/slukhayka/audiobooks/data/collective/CollectiveFeedBlockCodec.class', 'com/slukhayka/audiobooks/data/source/SluhayuaAdapter.class', 'com/slukhayka/audiobooks/data/source/HttpFetcher.class', 'com/slukhayka/audiobooks/data/source/SourceRequestGate.class', 'com/slukhayka/audiobooks/data/db/AudiobookDatabase.class', 'com/slukhayka/audiobooks/data/db/AudiobookDao.class', 'com/slukhayka/audiobooks/data/db/AudiobookDao_Impl.class'],
        "source": 'app/src/test/java/com/slukhayka/audiobooks/data/collective/ControlledAttachedAppArrivalsCompositionTest.kt',
        "sourceSHA256": '8eb5d62cfea5e565aa54446764625b1ef3253078872639986d16406b59beea34',
    },
}


def selection(receipt):
    case = receipt.get("case")
    require(case in CASES, "Unknown or missing controlled case")
    selected = CASES[case]
    require(receipt.get("class") == selected["class"] and receipt.get("method") == selected["method"],
            "Controlled case/class/method receipt mismatch")
    return case, selected


ORACLES = {
    "app/src/test/java/com/slukhayka/audiobooks/data/collective/ControlledAttachedAppRelatedCompositionTest.kt": "8b2880a2dec3b734efe55f6b03c47cc9e806de8812cab8f4c41914539046f751",
    "app/src/test/java/com/slukhayka/audiobooks/data/collective/ControlledAppFixtureRunner.kt": "f84b3c09ecaa46ef37ab1a4aef8231ba445e4d271be6a8a5d9f33881a71d608b",
    "app/src/test/resources/s2-app-composition-serdeshna.html": "342d39bdc1520e9caabebe6d3318a2b9d7925c3a8048d40ed7b8729649155a35",
    "scripts/fixtures/controlled-app-AndroidManifest.xml": "77e467080fda4826ce15c2243d2fcda8f3b86e975c10d5e0ed7dd1887172bca1",
    "app/src/main/assets/catalog_seed.json": "9502be0c57dcd7fa001f875cd3eba38f18fc6b08484e7702f7246efdc8b2a10d",
}
HTML = "s2-app-composition-serdeshna.html"
FB = {
    "com/google/firebase/FirebaseOptions.class": "b69d64b0610b6ec1fe56114eedfbb0397112af870c61e902f4d6d00c41aa4194",
    "com/google/firebase/FirebaseApp.class": "c374feeff525d02d0ac740b70b5ee52323cf8d40af54a7730cec7e9bd0e628b3",
}
FB_ARTIFACTS = {
    "4230b31a3143521c6a5e1827bb3de5c198ee79161f27eb3eb83e7fead9ffad59",
    "250f53eff8bb9db292ec9d8c98392324e878caa80159ca2fe5fd7ace9b17e940",
}
TOOLS = {
    "build-tools/35.0.0/aapt2": "729a6a8deba828992c50cb448d4ef3947003ec5a0d1deead7534897c8fc3486f",
    "platforms/android-35/android.jar": "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b",
}
ROBOLECTRIC = {
    "robolectric-4.16.1.jar": "ebfd48a0255689b0e58a3bbc6c794dc0c0d7b48ce31006fe2613cf7548cba229",
    "shadows-framework-4.16.1.jar": "a59802cffec783aa0e7df1489b0c2ce1126ef7cdedb940ffc378eb61e86958c8",
}
SDK_NAME = "android-all-instrumented-16-robolectric-13921718-i7.jar"
SDK_SHA = "16f1f751643d1d3d5592008846bbdfc1e57cff15e6ec303d26584de3b6ac25ec"
FORBIDDEN = ["google_app_id", "google_api_key", "gcm_defaultSenderId", "default_web_client_id"]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    return sha_bytes(Path(path).read_bytes())


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path = Path(path)
    require(not path.exists(), f"One-shot receipt exists: {path}")
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def negative():
    configs = list((ROOT / "app").rglob("google-services.json"))
    generated = ROOT / "app/build/generated"
    resources = [p for p in generated.rglob("google-services*")] if generated.exists() else []
    require(not configs and not resources,
            "Controlled App needs an unconfigured Firebase checkout; source/generated Firebase config is present. No credentials are deleted. Use a separate checkout.")


def literal_inputs(selected=None):
    for rel, expected in ORACLES.items():
        require(sha(ROOT / rel) == expected, f"Controlled literal input changed: {rel}")
    if selected and "source" in selected:
        require(sha(ROOT / selected["source"]) == selected["sourceSHA256"], "Selected controlled test literal bytes changed")
    html = (ROOT / "app/src/test/resources" / HTML).read_bytes()
    require(len(html) == 51588 and html.count(b"\r\n") == 3, "Raw captured HTML bytes changed")


def process_table():
    rows = {}
    output = subprocess.check_output(["/bin/ps", "-axo", "pid=,ppid=,lstart=,stat=,command="], text=True, timeout=10)
    for line in output.splitlines():
        parts = line.strip().split(None, 8)
        if len(parts) == 9:
            pid = int(parts[0])
            rows[pid] = {"pid": pid, "ppid": int(parts[1]), "birth": " ".join(parts[2:7]), "state": parts[7], "command": parts[8]}
    return rows


def canonical_run(path):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink() and path.is_dir(), "Canonical owned run directory required")
    require(path == path.resolve() and path.parent.samefile(ROOT / "build/controlled-app"), "Run directory must belong to this repository build/controlled-app")
    return path


def owned_supervisor(run):
    run = canonical_run(run)
    supervisor = read(run / "supervisor.json")
    row = process_table().get(supervisor["pid"])
    require(row and all(row[key] == supervisor["identity"][key] for key in ["pid", "birth", "command"]), "Supervisor process birth/command identity changed")
    require(Path(supervisor["root"]).samefile(ROOT) and Path(supervisor["runDirectory"]).samefile(run), "Supervisor belongs to another checkout")
    require(supervisor["token"] == os.environ.get("SLUKHAYKA_CONTROLLED_APP_TOKEN"), "Missing owned supervisor token")
    require(supervisor["state"] == "one-supervised-gradle-attempt" and not (run / "process-terminal.json").exists(), "Supervisor attempt is already terminal")
    current = os.getpid()
    rows = process_table()
    visited = set()
    while current != supervisor["pid"] and current in rows and current not in visited:
        visited.add(current)
        current = rows[current]["ppid"]
    require(current == supervisor["pid"], "Packager must be an actual supervisor descendant")
    return supervisor


def member(entry, name):
    entry = Path(entry)
    if entry.is_dir():
        path = entry / name
        return path.read_bytes() if path.is_file() else None
    if entry.is_file() and entry.suffix == ".jar":
        with zipfile.ZipFile(entry) as archive:
            matches = [item for item in archive.infolist() if item.filename == name]
            require(len(matches) <= 1, f"Duplicate classpath member: {entry}:{name}")
            return archive.read(matches[0]) if matches else None
    return None


def definitions(classpath, name):
    return [(str(Path(entry).resolve()), data) for entry in classpath if (data := member(entry, name)) is not None]


def directory_members(root):
    root = Path(root)
    return [{"path": p.relative_to(root).as_posix(), "SHA256": sha(p)}
            for p in sorted(root.rglob("*")) if p.is_file()]


def classpath_projection(inputs):
    """Preserve declared order; admit only successful public producer outputs."""
    cp = inputs["classpath"]
    require(isinstance(cp, list) and cp and all(isinstance(p, str) and Path(p).is_absolute()
            and "*" not in p and str(Path(p).resolve()) == p for p in cp),
            "Declared classpath must be canonical ordinary absolute entries; wildcards unsupported")
    require(len(cp) == len(set(cp)), "Duplicate declared Test classpath entry")
    proof = inputs.get("classpathOutputProvenance", {})
    require(proof.get("gradleVersion") == "9.3.1" and proof.get("selectedTestTask") == inputs["task"] == ":app:testDebugUnitTest",
            "Public output provenance must belong to the actual pinned Gradle/Test task")
    build = str((ROOT / "app/build").resolve())
    require(proof.get("projectBuildDirectory") == build, "Output provenance belongs to another build directory")
    ksp_task = ":app:kspDebugUnitTestKotlin"
    java_task = ":app:compileDebugUnitTestJavaWithJavac"
    producers = proof.get("producers", [])
    require(len(producers) == 2 and {row.get("task") for row in producers} == {ksp_task, java_task},
            "Exactly the two reviewed output producers are required")
    by_task = {row["task"]: row for row in producers}
    roots = proof.get("classpathBuildDependencyRoots", [])
    edges = proof.get("dependencyEdges", [])
    require(roots and len(roots) == len(set(roots)) and all(isinstance(p, str) and p.startswith(":") for p in roots),
            "Actual Test classpath build dependency roots missing")
    require(all(isinstance(edge, list) and len(edge) == 2 and all(isinstance(p, str) and p.startswith(":") for p in edge) for edge in edges)
            and len(edges) == len({tuple(edge) for edge in edges}), "Invalid/duplicate dependency witness edges")
    witnesses = {path: [path] for path in roots}
    pending = list(roots)
    while pending:
        parent = pending.pop(0)
        for start, child in edges:
            if start == parent and child not in witnesses:
                witnesses[child] = witnesses[parent] + [child]
                pending.append(child)
    for task, producer in by_task.items():
        state = producer.get("state", {})
        require(producer.get("selected") is True and producer.get("className") and task in witnesses
                and state.get("executed") is True and state.get("hasFailure") is False,
                "Output producer lacks selected/completed/public classpath dependency provenance")
        require(isinstance(producer.get("outputs"), list) and len(producer["outputs"]) == len(set(producer["outputs"])),
                "Output producer registered outputs missing/duplicated")
    java = by_task[java_task]
    java_destination = str(Path(build) / "intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes")
    require(java.get("destinationDirectory") == java_destination and isinstance(java.get("sourceFiles"), list)
            and java.get("sourceEmpty") is (not java["sourceFiles"]), "Public JavaCompile destination/source provenance invalid")
    expected = {
        str(Path(build) / "generated/ksp/debugUnitTest/resources"): (ksp_task, "ksp-resources"),
        str(Path(build) / "generated/ksp/debugUnitTest/classes"): (ksp_task, "ksp-classes"),
        java_destination: (java_task, "java-classes"),
    }
    optional = proof.get("optionalOutputs", [])
    require(len(optional) == 3 and len({row.get("path") for row in optional}) == 3
            and {row.get("path"): (row.get("producerTask"), row.get("role")) for row in optional} == expected,
            "Only the three reviewed producer outputs may be optional")
    for path, (task, _) in expected.items():
        require(path in by_task[task]["outputs"] and path in cp, "Optional output is not an actual registered producer output/declared classpath member")
    absent = []
    effective = []
    for index, path in enumerate(cp):
        if Path(path).exists():
            effective.append(path)
            continue
        require(path in expected, "Unexpected missing declared classpath artifact")
        task, role = expected[path]
        state = by_task[task]["state"]
        if task == java_task:
            require(state.get("noSource") is True and java["sourceEmpty"] is True,
                    "Absent Java output requires actual public noSource and empty source")
        else:
            require(state.get("noSource") is False and (state.get("upToDate") is True or state.get("didWork") is True),
                    "Absent KSP output requires successful completed public producer state")
        absent.append({"index": index, "path": path, "producerTask": task, "role": role,
                       "dependencyWitness": witnesses[task], "producerState": state})
    return {"declaredClasspath": cp, "expectedWorkerClasspath": effective, "provenAbsentRows": absent,
            "outputProvenanceSHA256": sha_bytes(json.dumps(proof, sort_keys=True).encode())}


def verify_worker_classpath(inputs, receipt, actual_cp):
    projection = classpath_projection(inputs)
    require(all(receipt.get(key) == value for key, value in projection.items()),
            "Declared/effective classpath or proven output absence changed after packaging")
    require([(row.get("index"), row.get("path")) for row in receipt["entries"]] == list(enumerate(inputs["classpath"])),
            "Declared classpath receipt lost/reordered entries")
    expected_cp = projection["expectedWorkerClasspath"]
    require(len(actual_cp) == len(expected_cp) + 1 and Path(actual_cp[0]).name == "gradle-worker.jar"
            and Path(actual_cp[0]).is_file(),
            "Actual worker classpath differs from proven ordered effective classpath")
    for index, (expected, actual) in enumerate(zip(expected_cp, actual_cp[1:])):
        try:
            same_origin = Path(expected).samefile(actual)
        except OSError:
            same_origin = False
        require(same_origin,
                f"Actual worker classpath differs from proven ordered effective classpath at application index {index}")


def capture_runtime(run, inputs, selected):
    cp = inputs["classpath"]
    projection = classpath_projection(inputs)
    absent_by_index = {row["index"]: row for row in projection["provenAbsentRows"]}
    rows = []
    fb = []
    for index, path in enumerate(cp):
        entry = Path(path)
        if index in absent_by_index:
            rows.append({**absent_by_index[index], "kind": "absent-proven-output"})
            continue
        row = {"index": index, "path": path, "kind": "file" if entry.is_file() else "directory"}
        if entry.is_file():
            row["SHA256"] = sha(entry)
        else:
            members = directory_members(entry)
            row["members"] = members
            row["contentSHA256"] = sha_bytes(json.dumps(members, sort_keys=True).encode())
        rows.append(row)
        for name, expected in FB.items():
            data = member(entry, name)
            if data is not None:
                require(entry.is_file() and row["SHA256"] in FB_ARTIFACTS and sha_bytes(data) == expected,
                        "Actual Firebase 22.1 runtime definition/artifact mismatch")
                fb.append({"index": index, "path": path, "artifactSHA256": row["SHA256"], "class": name, "classSHA256": sha_bytes(data)})
    require(len(fb) == 4 and {(row["artifactSHA256"], row["class"]) for row in fb} == {(artifact, name) for artifact in FB_ARTIFACTS for name in FB},
            "Exactly the four reviewed Firebase 22.1 definitions are required")
    for name, expected in ROBOLECTRIC.items():
        paths = [Path(path) for path in cp if Path(path).name == name]
        require(len(paths) == 1 and sha(paths[0]) == expected, f"Pinned Robolectric runtime missing: {name}")
    sdk = Path(inputs["robolectricSdk"])
    require(sdk.name == SDK_NAME and sha(sdk) == SDK_SHA, "Resolved SDK 36 artifact bytes differ")
    config = definitions(cp, "com/android/tools/test_config.properties")
    require(len(config) == 1 and config[0][0] == inputs["testConfigOrigin"] and sha_bytes(config[0][1]) == inputs["testConfigSHA256"], "Actual parsed JDK Properties definition changed")
    html = definitions(cp, HTML)
    require(len(html) == 1 and sha_bytes(html[0][1]) == ORACLES["app/src/test/resources/" + HTML], "Processed runtime HTML bytes differ")
    start = read(run / "compile-start.json")
    end = read(run / "compile-end.json")
    require(start["task"] == end["task"] == ":app:compileDebugUnitTestKotlin" and start["incremental"] is False, "Actual targeted compiler not configured/executed")
    require(start["destinationDirectory"] == inputs["unitDestinationDirectory"], "Public compiler output changed")
    require(start["compilerCodeSourceSHA256"] == "bd6b97bd9e563bb57db55a98eaad4306e3f0b39c8a0f9be76e476b22ad51f3b8", "Actual KGP 2.2.10 implementation changed")
    baseline = read(run / "main-class-baseline.json")
    require(selection(baseline)[0] == inputs["case"] and baseline["destinationDirectory"] == inputs["mainDestinationDirectory"], "Actual main compiler baseline/case changed")
    main_state = inputs["mainCompilerState"]
    require(baseline["task"] == main_state["task"] == ":app:compileDebugKotlin" and main_state["executed"] is True
            and main_state["hasFailure"] is False, "Selected public main compiler did not complete successfully")
    before_main = {row["class"]: row for row in baseline["classes"]}
    require(len(before_main) == len(baseline["classes"]) and set(before_main) == set(selected["main"]), "Required actual main origins differ from reviewed case registry")
    class_rows = []
    primary = selected["primary"]
    for name in primary + selected["main"]:
        found = definitions(cp, name)
        require(len(found) == 1, f"Required class must have one actual definition: {name}")
        output = Path(inputs["unitDestinationDirectory"] if name in primary else inputs["mainDestinationDirectory"]) / name
        require(output.is_file() and sha(output) == sha_bytes(found[0][1]), f"Actual classpath definition differs from public compiler output: {name}")
        if name in primary:
            require((Path(found[0][0]) / name).samefile(output), "Unit primary class is not actual public compiler output")
            require(start["timeMillis"] <= output.stat().st_mtime_ns // 1000000 <= end["timeMillis"], "Unit primary class was not freshly compiled")
            require(any(row["path"] == str(output.resolve()) and row["SHA256"] == sha(output) for row in end["classes"]), "Compiler end receipt missing primary")
        provenance = "fresh-unit"
        if name not in primary:
            prior = before_main[name]
            unchanged = prior["exists"] and prior["SHA256"] == sha(output) and prior["mtimeMillis"] == output.stat().st_mtime_ns // 1000000
            fresh = baseline["timeMillis"] <= output.stat().st_mtime_ns // 1000000 <= inputs["timeMillis"]
            require(unchanged or fresh, "Main class is neither fresh nor the exact observed prior compiler output: " + name)
            provenance = "unchanged-prior-main" if unchanged else "fresh-main"
        class_rows.append({"class": name, "origin": found[0][0], "output": str(output), "SHA256": sha(output), "provenance": provenance})
    write(run / "actual-runtime-classpath.json", {**projection, "entries": rows, "Firebase22_1Definitions": fb, "classes": class_rows, "SDK36": {"path": str(sdk), "SHA256": sha(sdk)}, "processedHTMLOrigin": html[0][0]})


def package(run):
    require(platform.system() == "Darwin", "Controlled App pins support macOS only")
    supervisor = owned_supervisor(run)
    case, selected = selection(supervisor)
    negative()
    literal_inputs(selected)
    inputs = read(run / "runtime-input.json")
    for filename in ["gradle-configured.json", "compile-start.json", "compile-end.json", "runtime-input.json"]:
        require(selection(read(run / filename))[0] == case, "Case changed across actual startup/compiler/runtime receipts")
    require(inputs["task"] == ":app:testDebugUnitTest" and Path(inputs["workingDirectory"]).samefile(ROOT / "app"), "Wrong actual Test task/working directory")
    capture_runtime(run, inputs, selected)
    sdk = Path(inputs["sdkDirectory"])
    for relative, expected in TOOLS.items():
        require(sha(sdk / relative) == expected, f"Reviewed SDK tool mismatch: {relative}")
    original = Path(inputs["originalApk"])
    require(original.suffix == ".ap_" and original.is_file() and original.resolve().is_relative_to((ROOT / "app/build").resolve()), "Only this AGP unsigned local-test resource archive is supported")
    require(b"APK Sig Block 42" not in original.read_bytes(), "Signed APK block is unsupported")
    fixture = run / "fixture"
    require(not fixture.exists(), "Fixture already packaged; no retry")
    fixture.mkdir()
    manifest = ROOT / "scripts/fixtures/controlled-app-AndroidManifest.xml"
    tree = ET.parse(manifest).getroot()
    app = tree.find("application")
    android = "{http://schemas.android.com/apk/res/android}"
    require(tree.tag == "manifest" and tree.attrib == {"package": "com.slukhayka.audiobooks"}
            and [child.tag for child in tree] == ["uses-sdk", "application"]
            and app is not None and len(app) == 0 and app.attrib == {android + "name": "android.app.Application", android + "debuggable": "true"},
            "Fixture manifest must have a plain Application and no components")
    linked = fixture / "manifest-only.ap_"
    command = [str(sdk / "build-tools/35.0.0/aapt2"), "link", "-I", str(sdk / "platforms/android-35/android.jar"), "--manifest", str(manifest), "-o", str(linked)]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    (fixture / "aapt2-link.log").write_bytes(result.stdout)
    require(result.returncode == 0, "Provider-free manifest link failed")
    with zipfile.ZipFile(linked) as archive:
        binary_manifest = archive.read("AndroidManifest.xml")
    isolated = fixture / "isolated-local-test.ap_"
    entries = {}
    original_hash = sha(original)
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(isolated, "x") as target:
        names = source.namelist()
        require(len(names) == len(set(names)) and "resources.arsc" in names and "AndroidManifest.xml" in names, "Malformed local-test package")
        require(not any(name.upper().startswith("META-INF/") for name in names), "Signed package unsupported")
        table = source.read("resources.arsc")
        for name in FORBIDDEN:
            require(name.encode() not in table and name.encode("utf-16le") not in table, f"Firebase resource exists: {name}")
        for info in source.infolist():
            data = source.read(info)
            entries[info.filename] = sha_bytes(data)
            target.writestr(info, binary_manifest if info.filename == "AndroidManifest.xml" else data)
    with zipfile.ZipFile(isolated) as archive:
        require(archive.namelist() == list(entries), "Archive entry order/names changed")
        for name, expected in entries.items():
            require(sha_bytes(archive.read(name)) == (sha_bytes(binary_manifest) if name == "AndroidManifest.xml" else expected), f"Archive bytes changed: {name}")
        require(sha_bytes(archive.read("assets/catalog_seed.json")) == ORACLES["app/src/main/assets/catalog_seed.json"], "Actual seed asset changed")
    require(sha(original) == original_hash, "Original AGP archive changed during packaging")
    negative()
    literal_inputs(selected)
    require(selection(owned_supervisor(run))[0] == case, "Owned case changed during packaging")
    gate = {"originalApk": str(original), "originalApkSHA256": original_hash,
            "isolatedApk": str(isolated), "isolatedApkSHA256": sha(isolated),
            "isolatedManifest": str(manifest), "isolatedManifestSHA256": sha(manifest),
            "negativeFirebaseResourceGate": "PASS", "allOtherZipEntryBytesPreserved": "true"}
    # Existing helper uses Properties.load(Reader); preserve Unicode and escape its metacharacters.
    def escape(value):
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("=", "\\=").replace(":", "\\:").replace(" ", "\\ ")
    (fixture / "fixture.properties").write_text("".join(key + "=" + escape(value) + "\n" for key, value in gate.items()), encoding="utf-8")
    write(run / "prestartup-gate.json", {"case": case, "class": selected["class"], "method": selected["method"], "supervisorSHA256": sha(run / "supervisor.json"), "runtimeInputSHA256": sha(run / "runtime-input.json"), "classpathReceiptSHA256": sha(run / "actual-runtime-classpath.json"), "compileStartSHA256": sha(run / "compile-start.json"), "compileEndSHA256": sha(run / "compile-end.json"), "mainBaselineSHA256": sha(run / "main-class-baseline.json"), "gradleConfiguredSHA256": sha(run / "gradle-configured.json"), "gate": gate, "gateSHA256": sha(fixture / "fixture.properties"), "originalEntries": entries, "onlyChangedEntry": "AndroidManifest.xml", "normalProductionProviderStartup": False})
    print("Controlled pre-worker provider/resource/Firebase/compiler gate PASS", flush=True)


def jdk21():
    candidate = os.environ.get("SLUKHAYKA_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if not candidate:
        candidate = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=10).strip()
    java = Path(candidate) / "bin/java"
    probe = subprocess.run([str(java), "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10)
    require(probe.returncode == 0 and re.search(r'version "21(?:\.|\")', probe.stdout), "JDK 21 required; set SLUKHAYKA_JAVA_HOME")
    return str(Path(candidate).resolve())


def source_snapshot():
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).split(b"\0")
    paths = {name.decode() for name in names if name}
    # Pending feature sources may not be in the Git index yet. Include them
    # too, while build outputs and downloaded binary models remain outside it.
    paths.update(p.relative_to(ROOT).as_posix() for p in (ROOT / "app/src").rglob("*")
                 if p.is_file() and p.suffix in {".kt", ".java", ".html", ".properties", ".json", ".xml"})
    paths.update(ORACLES)
    paths.update({"scripts/test-controlled-app.py", "scripts/test_partitions.py", "app/build.gradle.kts", "scripts/test-changed.sh", "scripts/test-all.sh", ".gitattributes", "CONTRIBUTING.md", "docs/runbooks/controlled-app-composition.md"})
    return {rel: sha(ROOT / rel) for rel in sorted(paths) if (ROOT / rel).is_file()}


def run(case=DEFAULT_CASE):
    require(case in CASES, "Unknown controlled case")
    selected = CASES[case]
    require(platform.system() == "Darwin", "Controlled App tools are reviewed for macOS only; Linux requires a separate pin review (no successful skip)")
    require(not any(os.environ.get(key) for key in ["GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SLUKHAYKA_GRADLE_ARGS_FILE", "SLUKHAYKA_GRADLEW"]), "Custom Gradle/JVM overrides are unsupported for this exact controlled target")
    negative()
    literal_inputs(selected)
    env = os.environ.copy()
    env["JAVA_HOME"] = jdk21()
    env["PATH"] = env["JAVA_HOME"] + "/bin:" + env.get("PATH", "")
    parent = ROOT / "build/controlled-app"
    parent.mkdir(parents=True, exist_ok=True)
    require(not parent.is_symlink() and parent.resolve() == parent, "Owned build output must be canonical and not a symlink")
    output = parent / uuid.uuid4().hex
    output.mkdir()
    pid = os.getpid()
    row = process_table()[pid]
    born = dt.datetime.strptime(row["birth"], "%a %b %d %H:%M:%S %Y").astimezone()
    token = uuid.uuid4().hex
    env["SLUKHAYKA_CONTROLLED_APP_TOKEN"] = token
    args = ["./gradlew", ":app:validateTestPartitions", ":app:testControlledAttachedApp", "-PcontrolledApp.runDirectory=" + str(output), "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--console=plain"]
    if case != DEFAULT_CASE:
        args.insert(4, "-PcontrolledApp.case=" + case)
    start = time.time_ns()
    pins = source_snapshot()
    write(output / "supervisor.json", {"case": case, "class": selected["class"], "method": selected["method"], "state": "one-supervised-gradle-attempt", "root": str(ROOT), "runDirectory": str(output), "pid": pid, "identity": row, "processStartEpochSecond": int(born.timestamp()), "startNs": start, "token": token, "argv": args, "sourcePins": pins})
    print(f"Controlled App output: {output}", flush=True)
    seen = {pid: row}
    first_live_identities = {pid: row.copy()}
    snapshots = []
    worker_argfiles = {}
    observation_errors = []
    cleanup_errors = []
    def snapshot(capture_args=True):
        rows = process_table()
        owned = {pid}
        while True:
            added = {key for key, value in rows.items() if value["ppid"] in owned and not value["command"].startswith("/bin/ps -axo")}
            if added <= owned:
                break
            owned |= added
        for key in owned:
            if key in rows and (key not in seen or rows[key]["birth"] == seen[key]["birth"]):
                # A shell can legitimately exec Java. Preserve its last FULL
                # live identity; a later '(java)' zombie is state, not evidence
                # that the worker was launched with a different command.
                if key not in seen or not rows[key]["state"].startswith("Z"):
                    seen[key] = rows[key].copy()
        alive = [value for key, value in rows.items() if key in seen and value["birth"] == seen[key]["birth"]
                 and (value["command"] == seen[key]["command"] or value["state"].startswith("Z"))]
        snapshots.append({"timeNs": time.time_ns(), "owned": alive})
        for value in alive:
            if capture_args and "Gradle Test Executor" in value["command"] and "GradleWorkerMain" in value["command"] and value["pid"] not in worker_argfiles:
                tokens = shlex.split(value["command"])
                files = [Path(token[1:]) for token in tokens if token.startswith("@")]
                require(len(files) == 1 and files[0].is_file(), "Actual live worker @argfile is missing")
                data = files[0].read_bytes()
                target = output / f"worker-{value['pid']}-args.txt"
                target.write_bytes(data)
                first_live_identities[value["pid"]] = value.copy()
                worker_argfiles[value["pid"]] = {"identity": value.copy(), "originalArgfile": str(files[0]), "capturedArgfile": str(target), "SHA256": sha_bytes(data)}
        return alive
    def observe(stage, capture_args=True):
        try:
            return snapshot(capture_args=capture_args)
        except BaseException as error:
            observation_errors.append({"stage": stage, "timeNs": time.time_ns(),
                                       "error": f"{type(error).__name__}: {error}"})
            return None
    def cleanup_owned():
        # The Popen handle belongs to this invocation even when ps is broken.
        # Never substitute an unverified numeric PID or a process-group kill.
        if process is not None:
            for operation in ["terminate", "kill"]:
                try:
                    if process.poll() is not None:
                        break
                    getattr(process, operation)()
                    process.wait(timeout=5)
                    break
                except BaseException as error:
                    cleanup_errors.append({"operation": "owned-wrapper-" + operation,
                                           "error": f"{type(error).__name__}: {error}"})
        for sig in [signal.SIGTERM, signal.SIGKILL]:
            alive = observe("cleanup-" + signal.Signals(sig).name, capture_args=False)
            if alive is not None:
                for value in reversed(alive):
                    if value["pid"] in {pid, process.pid if process else None}:
                        continue
                    try:
                        # Recheck each known identity immediately before signaling;
                        # a failed table read or PID reuse cannot authorize a kill.
                        current = process_table().get(value["pid"])
                        if current and current["birth"] == value["birth"] and (
                                current["command"] == value["command"] or current["state"].startswith("Z")):
                            os.kill(value["pid"], sig)
                    except ProcessLookupError:
                        pass
                    except BaseException as error:
                        detail = {"operation": "verified-child-" + signal.Signals(sig).name,
                                  "pid": value["pid"], "error": f"{type(error).__name__}: {error}"}
                        cleanup_errors.append(detail)
                        observation_errors.append({"stage": "cleanup-child-identity", "timeNs": time.time_ns(), **detail})
            time.sleep(1)
        if process is not None:
            try:
                return process.wait(timeout=5)
            except BaseException as error:
                cleanup_errors.append({"operation": "owned-wrapper-final-wait",
                                       "error": f"{type(error).__name__}: {error}"})
        return None
    def interrupted(signum, frame):
        raise RuntimeError(f"Supervisor interrupted by signal {signum}")
    previous = {sig: signal.signal(sig, interrupted) for sig in [signal.SIGINT, signal.SIGTERM, signal.SIGHUP]}
    timed_out = False
    forced = False
    failure = None
    process = None
    code = 125
    try:
        with (output / "raw.log").open("xb") as log:
            process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 720
            while process.poll() is None:
                require(observe("native-supervision") is not None, "Owned process observation failed")
                if time.monotonic() >= deadline:
                    timed_out = True
                    raise RuntimeError("Owned Gradle attempt exceeded 720 seconds")
                time.sleep(0.2)
            code = process.wait()
        deadline = time.monotonic() + 15
        while True:
            alive = observe("natural-terminal-grace")
            require(alive is not None, "Owned process observation failed after native terminal")
            children = [value for value in alive if value["pid"] != pid]
            if not children:
                break
            require(time.monotonic() < deadline, "Owned processes remain after natural terminal")
            time.sleep(0.2)
    except BaseException as error:
        failure = f"{type(error).__name__}: {error}"
        forced = True
        # A second interrupt must not abort bounded cleanup or its receipts.
        for sig in previous:
            signal.signal(sig, signal.SIG_IGN)
        code = cleanup_owned()
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    final_observation = observe("final-terminal-observation", capture_args=False)
    last_observed_children = None if final_observation is None else [value for value in final_observation if value["pid"] != pid]
    # Any gap can hide a descendant reparented during cleanup. A later empty
    # table cannot retroactively establish that no child escaped observation.
    remaining = None if observation_errors else last_observed_children
    if observation_errors or cleanup_errors:
        failure = failure or "Owned process observation/cleanup was incomplete"
    end = time.time_ns()
    write(output / "pid-snapshots.json", snapshots)
    write(output / "worker-argfiles.json", worker_argfiles)
    terminal = {"ownPIDs": sorted(seen), "supervisorPID": pid, "exitCode": code, "startNs": start, "endNs": end, "timedOut": timed_out, "forcedOwnedCleanup": forced, "remainingOwnedChildren": remaining, "ownedChildrenObservation": "UNKNOWN" if remaining is None else "OBSERVED", "observationErrors": observation_errors, "cleanupErrors": cleanup_errors, "lastObservedOwnedChildren": last_observed_children, "identityRecords": seen, "firstFullLiveIdentities": first_live_identities, "wrapperPID": process.pid if process else None, "failure": failure, "rawLogSHA256": sha(output / "raw.log") if (output / "raw.log").exists() else None}
    terminal["rawXML"] = None
    xml_source = ROOT / "app/build/test-results/testDebugUnitTest" / ("TEST-" + selected["class"] + ".xml")
    try:
        if xml_source.is_file():
            shutil.copy2(xml_source, output / "raw.xml")
            terminal["rawXML"] = {"SHA256": sha(output / "raw.xml"), "mtimeNs": xml_source.stat().st_mtime_ns}
    except BaseException as error:
        failure = failure or f"XML capture failed: {type(error).__name__}: {error}"
        terminal["failure"] = failure
    # Persist terminal/error receipts before the failure check or post-audit.
    write(output / "process-terminal.json", terminal)
    if failure or code != 0 or timed_out or forced or remaining is None or remaining:
        write(output / "supervision-failure.json", {"verdict": "FAIL", "failure": failure,
              "nativeTerminal": code, "ownedChildrenObservation": terminal["ownedChildrenObservation"],
              "observationErrors": observation_errors, "cleanupErrors": cleanup_errors})
        raise RuntimeError(f"Controlled attempt failed: {failure or code}; preserve {output}")
    try:
        accept(output, pins, terminal, worker_argfiles)
    except (RuntimeError, OSError, ValueError, zipfile.BadZipFile, ET.ParseError) as error:
        write(output / "postaudit-failure.json", {"verdict": "FAIL", "reason": str(error), "nativeTerminal": code})
        raise
    return 0


def accept(output, pins, terminal, argfiles):
    case, selected = selection(read(output / "supervisor.json"))
    negative()
    literal_inputs(selected)
    require(source_snapshot() == pins, "Repository inputs changed during the controlled run")
    require(len(argfiles) == 1, "Exactly one actual owned test worker required")
    worker_pid, captured = next(iter(argfiles.items()))
    args = shlex.split(Path(captured["capturedArgfile"]).read_text())
    require(len(args) == 2 and args[0] in ["-cp", "-classpath", "--class-path"], "Unexpected actual worker @argfile format")
    actual_cp = [str(Path(path).resolve()) for path in args[1].split(os.pathsep)]
    runtime = read(output / "runtime-input.json")
    cp_receipt = read(output / "actual-runtime-classpath.json")
    for filename in ["gradle-configured.json", "compile-start.json", "compile-end.json", "runtime-input.json", "prestartup-gate.json"]:
        require(selection(read(output / filename))[0] == case, "Case changed before actual worker acceptance")
    verify_worker_classpath(runtime, cp_receipt, actual_cp)
    for row in cp_receipt["classes"]:
        actual = definitions(actual_cp, row["class"])
        require(len(actual) == 1 and actual[0][0] == row["origin"] and sha_bytes(actual[0][1]) == row["SHA256"], "Actual worker used stale/conflicting class definition")
    require(sha(captured["capturedArgfile"]) == captured["SHA256"], "Captured actual worker arguments changed")
    worker_record = terminal["firstFullLiveIdentities"].get(worker_pid) or terminal["firstFullLiveIdentities"].get(str(worker_pid))
    require(worker_record and all(worker_record[key] == captured["identity"][key] for key in ["pid", "birth", "command"]), "Captured worker birth/command identity changed")
    gate = read(output / "prestartup-gate.json")
    for filename, field in [("actual-runtime-classpath.json", "classpathReceiptSHA256"), ("compile-start.json", "compileStartSHA256"), ("compile-end.json", "compileEndSHA256"), ("main-class-baseline.json", "mainBaselineSHA256"), ("gradle-configured.json", "gradleConfiguredSHA256")]:
        require(sha(output / filename) == gate[field], f"Pre-worker receipt changed: {filename}")
    for row in cp_receipt["entries"]:
        path = Path(row["path"])
        if row["kind"] == "absent-proven-output":
            require(not path.exists(), "Proven absent producer output appeared after packaging")
        elif row["kind"] == "file":
            require(sha(path) == row["SHA256"], "Actual runtime artifact bytes changed")
        else:
            members = directory_members(path)
            require(members == row["members"], "Actual runtime directory bytes changed")
    require(gate["runtimeInputSHA256"] == sha(output / "runtime-input.json") and gate["supervisorSHA256"] == sha(output / "supervisor.json"), "Prestartup receipt changed")
    for key in ["originalApk", "isolatedApk", "isolatedManifest"]:
        require(sha(gate["gate"][key]) == gate["gate"][key + "SHA256"], "Controlled resource package changed")
    require(gate["gateSHA256"] == sha(output / "fixture/fixture.properties"), "Fixture gate changed")
    raw = (output / "raw.log").read_text(errors="replace")
    for task in [":app:compileDebugUnitTestKotlin", ":app:testDebugUnitTest"]:
        headers = re.findall(r"^> Task " + re.escape(task) + r"([^\n]*)$", raw, re.MULTILINE)
        require(headers and {line.strip() for line in headers} == {""}, "Actual controlled task did not execute freshly")
    require("S2_FIXTURE_ORIGINAL_PHYSICAL_IDENTITY=true" in raw, "Public original package physical identity was not proved")
    fixture_pids = re.findall(r"S2_FIXTURE_WORKER_PID=(\d+); gateSHA256=([a-f0-9]{64})", raw)
    require(fixture_pids == [(str(worker_pid), gate["gateSHA256"])], "Actual fixture worker/gate identity mismatch")
    xml_path = output / "raw.xml"
    require(terminal["rawXML"] and terminal["rawXML"]["SHA256"] == sha(xml_path)
            and terminal["startNs"] <= terminal["rawXML"]["mtimeNs"] <= terminal["endNs"], "Stale controlled XML")
    xml = ET.parse(xml_path).getroot()
    require(xml.attrib.get("name") == selected["class"] and xml.attrib.get("tests") == "1"
            and all(xml.attrib.get(key) == "0" for key in ["failures", "errors", "skipped"]), "Full one-case App oracle failed")
    cases = xml.findall("testcase")
    require(len(cases) == 1 and cases[0].attrib.get("classname") == selected["class"] and cases[0].attrib.get("name") in [selected["method"], selected["method"] + "[36]"]
            and all(cases[0].find(key) is None for key in ["failure", "error", "skipped"]), "Wrong/partial controlled App case")
    xml_output = "".join(xml.findtext(tag, "") for tag in ["system-out", "system-err"])
    require(re.findall(r"S2_ACTUAL_TEST_WORKER_PID=(\d+)", xml_output) == [str(worker_pid)], "Actual @Test worker identity mismatch")
    write(output / "receipt.json", {"verdict": "PASS_CONTROLLED_ATTACHED_APP_ROOM_PUBLIC_OVERVIEW", "case": case, "class": selected["class"], "method": selected["method"], "testCases": 1, "relatedCardExpectations": 7 if case == DEFAULT_CASE else None, "lateRoomVMProjection": case == "overview-late-room",
          "workerPID": worker_pid, "naturalExit": 0, "nativeTimeout": False, "forcedCleanup": False, "sourceInputsUnchanged": True,
          "actualWorkerClasspathBound": True, "runtimeClasspathSHA256": sha(output / "actual-runtime-classpath.json"), "rawXMLSHA256": sha(output / "raw.xml"), "rawLogSHA256": sha(output / "raw.log"), "terminalSHA256": sha(output / "process-terminal.json"),
          "normalProductionProvidersHomeDeviceBackendPlayback": False})
    print(f"Controlled one-case App composition PASS; case={case}. Evidence: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run_parser = commands.add_parser("run")
    run_parser.add_argument("--case", choices=tuple(CASES), default=DEFAULT_CASE)
    pack = commands.add_parser("package")
    pack.add_argument("--run-directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "run":
            return run(args.case)
        package(canonical_run(args.run_directory))
        return 0
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"Controlled App verification failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
