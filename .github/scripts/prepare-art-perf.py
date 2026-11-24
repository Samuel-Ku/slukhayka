#!/usr/bin/env python3
"""Prepare a temporary combined candidate and the committed comparison probes."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

BASE = "85c4423b6c608b18162f440ff3044d47e76d6451"
MAIN = "app/src/main/java/com/slukhayka/audiobooks/"
TEST = "app/src/test/java/com/slukhayka/audiobooks/"
ANDROID = "app/src/androidTest/java/"
PRODUCTION = {
    "data/merge/MergeKey.kt": "d24345f1dcf4e0996035954d0aab39181b4d0ff2",
    "data/source/GlobalSearch.kt": "af2d6e1dde852ba347adfd8efd7032a9648d0b80",
    "data/collections/CollectionMatcher.kt": "9e04c69673d061d49ef0b52c5c42a70fde69a14f",
    "data/EditionId.kt": "0171e50d07c0f0d1e4fded0def3c90b84d9758de",
    "data/Hashing.kt": "0171e50d07c0f0d1e4fded0def3c90b84d9758de",
    "data/source/SourceParsing.kt": "6ecdc3af6edcfa5a00b4636d00346232ecdf239f",
    "data/metadata/MetadataAssertions.kt": "947d721b96cbd178937cd7c969f35137253e8be2",
}
# The first five objects were subsequently moved unchanged into mapped JUnit files.
PROBES = [
    ("50f70313e436dbc090a5ff5efb50676f2d2660c9", "data/merge/RunMergeKeyBenchmark.kt", None),
    ("f1fe6afb6315cfcb72f5d3d147ffb48590df88af", "data/source/RunSourceIdBenchmark.kt", None),
    ("cb11cce9e70ad963f0c36aa588b1f555a4ddc6f6", "data/collections/RunCollectionMatcherBenchmark.kt", None),
    ("564e38b6558c715d07d9f956102716c7e017bbf0", "data/RunEditionIdBenchmark.kt", None),
    ("4cbf4cf78d823ca0ecd433c6e58dee9a796d2d62", "data/source/RunStripTagsBenchmark.kt", None),
    ("6ecdc3af6edcfa5a00b4636d00346232ecdf239f", "data/source/SourceParsingTest.kt", "/**\n * Spec-35 T1"),
    ("947d721b96cbd178937cd7c969f35137253e8be2", "data/metadata/MetadataAssertionsTest.kt", "/**\n * Pure JVM seam"),
]


def git(*args):
    return subprocess.check_output(["git", *args], text=True)


def blob(commit, path):
    return git("show", commit + ":" + path)


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def write(root, path, text):
    destination = root / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8")


def main():
    assert git("rev-parse", "HEAD").strip() == BASE, "checkout the declared base before preparing"
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    manifest = {"base": BASE, "workflowCommit": os.environ.get("GITHUB_SHA"),
                "scope": "combined production candidates; comparative helper timings on Android ART, not UI or physical ARM",
                "production": [], "probes": [], "fixtures": []}
    for path, commit in PRODUCTION.items():
        text = blob(commit, MAIN + path)
        origins = [commit]
        if path == "data/source/SourceParsing.kt":
            old = '    Regex("""<[^>]+>""").replace(input, replacement)'
            new = '    HTML_TAG.replace(input, replacement)\n\nprivate val HTML_TAG = Regex("""<[^>]+>""")'
            strip = "533488984dcd06f6f881ba579281823c9bfab305"
            assert old in text and new in blob(strip, MAIN + path)
            assert text.count(old) == 1
            text = text.replace(old, new)
            origins.append(strip)
        write(root, MAIN + path, text)
        manifest["production"].append({"path": MAIN + path, "commits": origins, "sha256": digest(text)})
    for commit, path, end in PROBES:
        text = blob(commit, TEST + path)
        if end:
            assert text.count(end) == 1
            text = text[:text.index(end)]
        package = next(line.split()[1] for line in text.splitlines() if line.startswith("package "))
        name = next(line.split()[1] for line in text.splitlines() if line.startswith("object Run"))
        destination = ANDROID + package.replace(".", "/") + "/" + name + ".kt"
        write(root, destination, text)
        manifest["probes"].append({"commit": commit, "origin": TEST + path, "destination": destination, "sha256": digest(text)})
    names = git("ls-tree", "-r", "--name-only", BASE, "app/src/test/resources/fixtures").splitlines()
    for path in sorted(name for name in names if name.endswith(".html")):
        text = blob(BASE, path)
        destination = "app/src/androidTest/assets/perf-fixtures/" + Path(path).name
        write(root, destination, text)
        manifest["fixtures"].append({"origin": path, "destination": destination, "sha256": digest(text)})
    assert len(manifest["fixtures"]) == 6
    write(root, ANDROID + "com/slukhayka/audiobooks/performance/PerformanceLoopBenchmarkTest.kt", HARNESS)
    manifest["harnessSha256"] = digest(HARNESS)
    write(root, ".mutations/evidence/art-perf/source-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"productionFiles": len(manifest["production"]), "probes": len(manifest["probes"]), "fixtures": len(manifest["fixtures"])}))


HARNESS = """package com.slukhayka.audiobooks.performance

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.data.RunEditionIdBenchmark
import com.slukhayka.audiobooks.data.collections.RunCollectionMatcherBenchmark
import com.slukhayka.audiobooks.data.merge.RunMergeKeyBenchmark
import com.slukhayka.audiobooks.data.metadata.RunClaimedTextBenchmark
import com.slukhayka.audiobooks.data.source.RunEntityDecoderBenchmark
import com.slukhayka.audiobooks.data.source.RunSourceIdBenchmark
import com.slukhayka.audiobooks.data.source.RunStripTagsBenchmark
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceLoopBenchmarkTest {
    @Test
    fun compareCommittedReferenceAndCandidateHelpers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "perf-loop")
        check(!root.exists() || root.deleteRecursively())
        check(root.mkdirs())
        copyAssets(instrumentation.targetContext.assets, "collections", File(root, "app/src/main/assets/collections"), ".json")
        check(copyAssets(instrumentation.context.assets, "perf-fixtures", File(root, "app/src/test/resources/fixtures"), ".html") == 6)
        Log.i("PerfLoop", "ENV sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.toList()} vm=${System.getProperty("java.vm.version")} fingerprint=${Build.FINGERPRINT}")
        val probes: List<Pair<String, () -> Unit>> = listOf(
            "merge-key" to { RunMergeKeyBenchmark.main(arrayOf(root.path)) },
            "source-hosts" to { RunSourceIdBenchmark.main(emptyArray()) },
            "collection-membership" to { RunCollectionMatcherBenchmark.main(arrayOf(root.path)) },
            "edition-id" to { RunEditionIdBenchmark.main(emptyArray()) },
            "strip-tags" to { RunStripTagsBenchmark.main(arrayOf(root.path)) },
            "entity-decoder" to { RunEntityDecoderBenchmark.main(emptyArray()) },
            "claimed-text" to { RunClaimedTextBenchmark.main(emptyArray()) }
        )
        for ((name, probe) in probes) {
            Log.i("PerfLoop", "BEGIN $name")
            probe()
            Log.i("PerfLoop", "END $name")
        }
        Log.i("PerfLoop", "ALL_BENCHMARKS_DONE")
    }

    private fun copyAssets(assets: AssetManager, directory: String, destination: File, extension: String): Int {
        check(destination.mkdirs())
        val names = assets.list(directory).orEmpty().filter { it.endsWith(extension) }
        check(names.isNotEmpty())
        for (name in names) {
            assets.open("$directory/$name").use { input ->
                File(destination, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return names.size
    }
}
"""

if __name__ == "__main__":
    main()
