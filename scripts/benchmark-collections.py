#!/usr/bin/env python3
"""Benchmark Android collections or search merging without starting Gradle."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--search", action="store_true", help="Benchmark global-search merging instead of collections")
    options = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    classes = Path(os.environ.get(
        "SLUKHAYKA_APP_CLASSES",
        root / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    ))
    if not (classes / "com/slukhayka/audiobooks/data/source/GlobalSearchResult.class").is_file():
        raise SystemExit("Debug classes are missing. Build debug first, or set SLUKHAYKA_APP_CLASSES.")

    configured_lib = os.environ.get("SLUKHAYKA_KOTLIN_LIB")
    if configured_lib:
        kotlin_lib = Path(configured_lib)
    else:
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        candidates = sorted(gradle_home.glob("wrapper/dists/gradle-*/**/lib/kotlin-compiler-embeddable-*.jar"))
        if not candidates:
            raise SystemExit("Cached Kotlin compiler missing. Set SLUKHAYKA_KOTLIN_LIB to its lib directory.")
        kotlin_lib = candidates[-1].parent
    stdlib = sorted(kotlin_lib.glob("kotlin-stdlib-*.jar"))
    compiler = list(kotlin_lib.glob("kotlin-compiler-embeddable-*.jar"))
    if not stdlib or not compiler:
        raise SystemExit("Kotlin compiler and stdlib jars are required in SLUKHAYKA_KOTLIN_LIB.")
    java_home = os.environ.get("SLUKHAYKA_JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not java:
        raise SystemExit("Java 21 is required. Set SLUKHAYKA_JAVA_HOME.")
    classpath = os.pathsep.join(map(str, [classes, *stdlib, *kotlin_lib.glob("annotations-*.jar")]))
    production = root / "app/src/main/java/com/slukhayka/audiobooks"
    sources = [production / name for name in [
        "data/collections/CollectionMatcher.kt", "data/collections/CollectionList.kt",
        "data/collections/CollectionJson.kt", "data/collections/MiniJson.kt",
        "data/merge/MergeKey.kt", "data/metadata/MetadataAssertions.kt",
        "data/source/SourceParsing.kt",
    ]]
    if options.search:
        sources.extend(production / name for name in [
            "data/source/GlobalSearch.kt", "data/source/SourceAdapter.kt",
            "data/source/SourceRegistry.kt", "data/source/SourceAccessPolicy.kt",
            "data/source/DownloadPolicy.kt", "data/EditionId.kt", "data/Hashing.kt",
        ])
        runner = "data/source/RunGlobalSearchBenchmark"
    else:
        runner = "data/collections/RunCollectionMatcherBenchmark"
    sources.append(root / f"app/src/test/java/com/slukhayka/audiobooks/{runner}.kt")
    with tempfile.TemporaryDirectory(prefix="slukhayka-collections-bench-") as scratch:
        jar = Path(scratch) / "matcher.jar"
        subprocess.run([
            java, "-Xmx512m", "-cp", str(kotlin_lib / "*"),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "21", "-classpath", classpath, "-d", str(jar), *map(str, sources),
        ], check=True)
        subprocess.run([
            java, "-Xmx256m", "-cp", str(jar) + os.pathsep + classpath,
            "com.slukhayka.audiobooks." + runner.replace("/", "."), str(root),
        ], check=True)


if __name__ == "__main__":
    main()
