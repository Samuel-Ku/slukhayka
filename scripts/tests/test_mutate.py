"""#1221: behaviour of scripts/mutate.sh, proved against a throwaway Git repo.

Every test builds a real (tiny) repository in a temporary directory: real Git, a
fake `gradlew` that writes a real JUnit XML, and the real script. That is the
only way to prove the promises that matter here — refusal on a dirty worktree,
byte-for-byte rollback on every exit path including a signal, and a record that
can tell a mutation artifact from a real one.
"""

import json
import os
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
# The sensitivity tests point these at deliberately broken copies, so "the suite
# dies on a mutation" is proved by running the suite, not by asserting that a
# mutation exists.
SCRIPT_UNDER_TEST = Path(
    os.environ.get("SLUKHAYKA_MUTATE_SCRIPT", REPO_ROOT / "scripts" / "mutate.sh")
)
TRACE_UNDER_TEST = Path(
    os.environ.get("SLUKHAYKA_MUTATE_TRACE", REPO_ROOT / "scripts" / "mutation_trace.py")
)

SAMPLE = "app/src/main/java/com/slukhayka/fixture/Sample.kt"
SAMPLE_TEST = "app/src/test/java/com/slukhayka/fixture/SampleTest.kt"
OTHER_TEST = "app/src/test/java/com/slukhayka/fixture/OtherTest.kt"
SAMPLE_CLASS = "com.slukhayka.fixture.SampleTest"
OTHER_CLASS = "com.slukhayka.fixture.OtherTest"
RESULTS = "app/build/test-results/testDebugUnitTest"


def artifact_of(test_class: str) -> str:
    return f"{RESULTS}/TEST-{test_class}.xml"


class Fixture:
    """A one-commit repository that runs scripts/mutate.sh for real."""

    def __init__(self, root: Path, logs: Path, script_source: Path, trace_source: Path):
        self.root = root
        self.logs = logs
        self.script = root / "scripts" / "mutate.sh"
        self.build(script_source, trace_source)

    def build(self, script_source: Path, trace_source: Path) -> None:
        (self.root / "app/src/main/java/com/slukhayka/fixture").mkdir(parents=True)
        (self.root / "app/src/test/java/com/slukhayka/fixture").mkdir(parents=True)
        (self.root / "scripts").mkdir()
        # Both ignore files matter: the root one keeps .mutations/ invisible (or
        # the second run of the tool would refuse its own records), and app's one
        # keeps the JUnit XML out of `git status`.
        shutil.copy(REPO_ROOT / ".gitignore", self.root / ".gitignore")
        shutil.copy(REPO_ROOT / "app/.gitignore", self.root / "app/.gitignore")
        for name in ("test_partitions.py", "test-all.sh"):
            shutil.copy(REPO_ROOT / "scripts" / name, self.root / "scripts" / name)
        shutil.copy(trace_source, self.root / "scripts" / "mutation_trace.py")
        shutil.copy(script_source, self.script)
        self.script.chmod(self.script.stat().st_mode | stat.S_IXUSR)

        self.write(SAMPLE, "package com.slukhayka.fixture\n\nobject Sample {\n    fun answer(): Int = 42\n}\n")
        self.write(SAMPLE_TEST, "package com.slukhayka.fixture\n\nclass SampleTest\n")
        self.write(OTHER_TEST, "package com.slukhayka.fixture\n\nclass OtherTest\n")

        self.jdk = self.root / "jdk-21"
        java = self.jdk / "bin/java"
        java.parent.mkdir(parents=True)
        java.write_text(
            "#!/usr/bin/env bash\necho 'openjdk version \"21.0.8\"' >&2\n", encoding="utf-8"
        )
        java.chmod(java.stat().st_mode | stat.S_IXUSR)

        gradle = self.root / "gradlew"
        gradle.write_text(GRADLEW, encoding="utf-8")
        gradle.chmod(gradle.stat().st_mode | stat.S_IXUSR)

        self.git("init", "-q", "-b", "main", ".")
        self.git("config", "user.email", "fixture@example.com")
        self.git("config", "user.name", "Fixture")
        self.git("config", "commit.gpgsign", "false")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "fixture")
        self.answer_patch = self.modify_patch("answer", "= 42", "= 43")

    # -- repository plumbing -------------------------------------------------

    def environment(self, **overrides) -> dict:
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": str(self.logs / "gitconfig"),
            "SLUKHAYKA_JAVA_HOME": str(self.jdk),
            "SLUKHAYKA_GRADLEW": str(self.root / "gradlew"),
            "MUTATE_TEST_GRADLE_LOG": str(self.logs / "gradle.log"),
        }
        environment.update(overrides)
        return environment

    def write(self, relative: str, content: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def read(self, relative: str) -> str:
        return (self.root / relative).read_text(encoding="utf-8")

    def git(self, *arguments: str) -> subprocess.CompletedProcess:
        result = subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(),
        )
        if result.returncode != 0:
            raise AssertionError(f"git {' '.join(arguments)} failed: {result.stderr}")
        return result

    def sha256(self, relative: str) -> str:
        return subprocess.run(
            ["sha256sum", relative],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.split()[0]

    def head(self) -> str:
        return self.git("rev-parse", "HEAD").stdout.strip()

    def dirty_entries(self) -> str:
        return self.git("status", "--porcelain", "-uall").stdout

    def patch_directory(self) -> Path:
        directory = self.root / ".mutations" / "patches"
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def modify_patch(self, name: str, old: str, new: str) -> Path:
        original = self.read(SAMPLE)
        if old not in original:
            raise AssertionError(f"{old} is not in {SAMPLE}")
        self.write(SAMPLE, original.replace(old, new))
        diff = self.git("diff").stdout
        self.write(SAMPLE, original)
        patch = self.patch_directory() / f"{name}.patch"
        patch.write_text(diff, encoding="utf-8")
        return patch

    def added_file_patch(self, name: str, relative: str, content: str) -> Path:
        self.write(relative, content)
        self.git("add", "-N", relative)
        diff = self.git("diff", "--", relative).stdout
        self.git("rm", "--cached", "-q", "--force", "--", relative)
        (self.root / relative).unlink()
        patch = self.patch_directory() / f"{name}.patch"
        patch.write_text(diff, encoding="utf-8")
        return patch

    # -- running the script --------------------------------------------------

    def run_script(self, *arguments: str, timeout: int = 120, **overrides):
        return subprocess.run(
            ["bash", str(self.script), *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(**overrides),
            timeout=timeout,
        )

    def popen_script(self, *arguments: str, stdout=None, stderr=None, **overrides):
        return subprocess.Popen(
            ["bash", str(self.script), *arguments],
            cwd=self.root,
            stdout=subprocess.PIPE if stdout is None else stdout,
            stderr=subprocess.PIPE if stderr is None else stderr,
            text=True,
            env=self.environment(**overrides),
        )

    def run_class(self, patch: Path, test_class: str = SAMPLE_CLASS, *, tests=4, failures=0, exit_status=None, **overrides):
        """Run the mutation the way mutate.sh does: the runner writes the XML."""
        if exit_status is None:
            exit_status = 1 if failures else 0
        return self.run_script(
            "--patch",
            str(patch),
            "--class",
            test_class,
            MUTATE_TEST_XML_CLASS=test_class,
            MUTATE_TEST_TESTS=str(tests),
            MUTATE_TEST_FAILURES=str(failures),
            MUTATE_TEST_EXIT=str(exit_status),
            **overrides,
        )

    def simulate_real_run(self, test_class: str, *, tests=7) -> None:
        """A run outside mutate.sh: same XML path, different bytes."""
        subprocess.run(
            ["bash", str(self.root / "gradlew"), ":app:testPureJvm"],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
            env=self.environment(
                MUTATE_TEST_XML_CLASS=test_class,
                MUTATE_TEST_TESTS=str(tests),
                MUTATE_TEST_FAILURES="0",
                MUTATE_TEST_EXIT="0",
            ),
        )
        time.sleep(0.01)

    def gradle_invocations(self) -> str:
        log = self.logs / "gradle.log"
        return log.read_text(encoding="utf-8") if log.is_file() else ""

    def records(self) -> list:
        log = self.root / ".mutations" / "runs.jsonl"
        if not log.is_file():
            return []
        return [
            json.loads(line)
            for line in log.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def status(self, test_class: str = SAMPLE_CLASS) -> dict:
        result = self.run_script("status", "--class", test_class)
        if result.returncode != 0:
            raise AssertionError(f"status failed: {result.stderr}")
        return json.loads(result.stdout)

    def artifact_sha(self, test_class: str = SAMPLE_CLASS) -> str:
        return self.sha256(artifact_of(test_class))


GRADLEW = """#!/usr/bin/env bash
# The stand-in for Gradle: it logs the invocation and writes the JUnit XML the
# real test task would write, so mutate.sh reads a file shaped like the real one.
set -u
printf '%s\\n' "$*" >> "$MUTATE_TEST_GRADLE_LOG"
if [[ -n ${MUTATE_TEST_PID_LOG:-} ]]; then
  printf '%s\\n' "$$" > "$MUTATE_TEST_PID_LOG"
fi
if [[ -n ${MUTATE_TEST_SLEEP:-} && -z ${MUTATE_TEST_XML_FIRST:-} ]]; then
  sleep "$MUTATE_TEST_SLEEP"
fi
if [[ -n ${MUTATE_TEST_XML_CLASS:-} ]]; then
  mkdir -p app/build/test-results/testDebugUnitTest
  printf '%s\\n' \\
    '<?xml version="1.0" encoding="UTF-8"?>' \\
    "<testsuite name=\\"$MUTATE_TEST_XML_CLASS\\" tests=\\"${MUTATE_TEST_TESTS:-1}\\" skipped=\\"0\\" failures=\\"${MUTATE_TEST_FAILURES:-0}\\" errors=\\"${MUTATE_TEST_ERRORS:-0}\\" timestamp=\\"$(date -u +%Y-%m-%dT%H:%M:%S.%N.000Z)\\" hostname=\\"fixture\\" time=\\"0.01\\">" \\
    "  <testcase name=\\"case\\" classname=\\"$MUTATE_TEST_XML_CLASS\\" time=\\"0.01\\"/>" \\
    '</testsuite>' > "app/build/test-results/testDebugUnitTest/TEST-$MUTATE_TEST_XML_CLASS.xml"
fi
if [[ -n ${MUTATE_TEST_SLEEP:-} && -n ${MUTATE_TEST_XML_FIRST:-} ]]; then
  sleep "$MUTATE_TEST_SLEEP"
fi
exit "${MUTATE_TEST_EXIT:-0}"
"""


class MutateFixtureTest(unittest.TestCase):
    """Shared fixture: a fresh repository per test, never the real one."""

    script_source = SCRIPT_UNDER_TEST
    trace_source = TRACE_UNDER_TEST

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.logs = base / "logs"
        self.logs.mkdir()
        self.fixture = Fixture(
            base / "repo", self.logs, self.script_source, self.trace_source
        )

    def tearDown(self):
        self.temporary.cleanup()

    # Short hands for the two things every test checks.
    def assertClean(self):
        self.assertEqual("", self.fixture.dirty_entries(), "the worktree is not clean")

    def last_record(self) -> dict:
        records = self.fixture.records()
        self.assertTrue(records, "no record was appended to .mutations/runs.jsonl")
        return records[-1]


class MutationRunTest(MutateFixtureTest):
    def test_runs_the_named_class_and_records_a_caught_mutation(self):
        result = self.fixture.run_class(self.fixture.answer_patch, tests=4, failures=1)

        self.assertEqual(0, result.returncode, result.stderr)
        invocations = self.fixture.gradle_invocations()
        self.assertIn(":app:testPureJvm", invocations)
        self.assertIn(f"-Ptest.selectedClasses={SAMPLE_CLASS}", invocations)
        self.assertIn(":app:validateTestPartitions", invocations)

        record = self.last_record()
        self.assertEqual("killed", record["outcome"])
        self.assertEqual(SAMPLE_CLASS, record["class"])
        self.assertEqual(":app:testPureJvm", record["task"])
        self.assertEqual("pure-jvm", record["partition"])
        self.assertEqual("answer", record["mutation"])
        self.assertEqual(4, record["tests"])
        self.assertEqual(1, record["failures"])
        self.assertEqual(1, record["exitCode"])
        self.assertEqual(self.fixture.head(), record["head"])
        self.assertEqual("main", record["branch"])
        self.assertTrue(record["restored"])
        self.assertEqual([SAMPLE], [entry["path"] for entry in record["files"]])
        self.assertTrue(record["files"][0]["restored"])
        self.assertTrue(record["artifactFresh"])
        self.assertEqual(self.fixture.artifact_sha(), record["artifactSha256"])
        self.assertEqual(artifact_of(SAMPLE_CLASS), record["artifact"])

        # stdout carries the same record, so a caller can read the verdict without
        # opening the log.
        self.assertEqual(record, json.loads(result.stdout.strip().splitlines()[-1]))
        self.assertIn("verdict: caught", result.stderr)
        self.assertIn("sha256", result.stderr)
        self.assertClean()

    def test_records_a_surviving_mutation_and_exits_one(self):
        result = self.fixture.run_class(self.fixture.answer_patch, tests=3, failures=0)

        self.assertEqual(1, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("survived", record["outcome"])
        self.assertEqual(3, record["tests"])
        self.assertEqual(0, record["failures"])
        self.assertIn("survived", result.stderr)
        self.assertClean()

    def test_reports_no_verdict_when_the_class_leaves_no_artifact(self):
        result = self.fixture.run_script(
            "--patch", str(self.fixture.answer_patch), "--class", SAMPLE_CLASS
        )

        self.assertEqual(3, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("no-verdict", record["outcome"])
        self.assertFalse(record["artifactFresh"])
        self.assertIsNone(record["artifactSha256"])
        self.assertTrue(record["restored"])
        self.assertClean()

    def test_a_stale_artifact_is_not_recorded_as_this_runs_artifact(self):
        self.fixture.simulate_real_run(SAMPLE_CLASS, tests=7)
        real_sha = self.fixture.artifact_sha()

        # Exit 0 with no XML written: the run says nothing, and the older XML on
        # disk must not be re-labelled as the mutation's own output.
        result = self.fixture.run_script(
            "--patch", str(self.fixture.answer_patch), "--class", SAMPLE_CLASS
        )

        self.assertEqual(3, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("no-verdict", record["outcome"])
        self.assertFalse(record["artifactFresh"])
        self.assertIsNone(record["artifactSha256"])
        self.assertEqual(real_sha, self.fixture.artifact_sha())
        self.assertEqual("genuine", self.fixture.status()["verdict"])
        self.assertClean()

    def test_refuses_a_dirty_worktree(self):
        dirty = self.fixture.read(SAMPLE) + "\n// a working edit\n"
        self.fixture.write(SAMPLE, dirty)

        result = self.fixture.run_class(self.fixture.answer_patch)

        self.assertEqual(2, result.returncode)
        self.assertIn("dirty", result.stderr)
        # The refusal must not touch the working edit, and must not run anything.
        self.assertEqual(dirty, self.fixture.read(SAMPLE))
        self.assertEqual("", self.fixture.gradle_invocations())
        self.assertEqual([], self.fixture.records())

    def test_refuses_an_unknown_class(self):
        result = self.fixture.run_script(
            "--patch", str(self.fixture.answer_patch), "--class", "com.slukhayka.fixture.NopeTest"
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("no test source", result.stderr)
        self.assertEqual("", self.fixture.gradle_invocations())
        self.assertEqual([], self.fixture.records())
        self.assertClean()

    def test_refuses_a_patch_that_does_not_apply(self):
        broken = self.logs / "broken.patch"
        broken.write_text(
            "diff --git a/nowhere b/nowhere\n--- a/nowhere\n+++ b/nowhere\n"
            "@@ -1 +1 @@\n-was\n+is\n",
            encoding="utf-8",
        )

        result = self.fixture.run_script(
            "--patch", str(broken), "--class", SAMPLE_CLASS
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("does not apply cleanly", result.stderr)
        self.assertEqual("", self.fixture.gradle_invocations())
        self.assertEqual([], self.fixture.records())
        self.assertClean()

    def test_requires_a_patch_and_a_class(self):
        for arguments in ([], ["--patch", str(self.fixture.answer_patch)], ["--class", SAMPLE_CLASS]):
            with self.subTest(arguments=arguments):
                result = self.fixture.run_script(*arguments)
                self.assertEqual(2, result.returncode)
                self.assertIn("--patch", result.stderr)

    def test_a_second_run_is_not_blocked_by_the_first_runs_trace(self):
        first = self.fixture.run_class(self.fixture.answer_patch, failures=1)
        self.assertEqual(0, first.returncode, first.stderr)

        second = self.fixture.run_class(self.fixture.answer_patch, failures=1)

        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(2, len(self.fixture.records()))
        self.assertClean()


class MutationRollbackTest(MutateFixtureTest):
    def test_restores_the_file_byte_for_byte_after_a_failing_run(self):
        before = self.fixture.sha256(SAMPLE)

        result = self.fixture.run_class(self.fixture.answer_patch, failures=1)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.fixture.sha256(SAMPLE))
        record = self.last_record()
        entry = record["files"][0]
        self.assertEqual(before, entry["pristineSha256"])
        self.assertEqual(before, entry["rolledBackSha256"])
        self.assertTrue(record["restored"])
        self.assertClean()

    def test_removes_a_file_the_mutation_added(self):
        added = "app/src/main/java/com/slukhayka/fixture/Added.kt"
        patch = self.fixture.added_file_patch(
            "add-file", added, "package com.slukhayka.fixture\n\nobject Added\n"
        )

        result = self.fixture.run_class(patch, failures=1)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.fixture.root / added).exists())
        self.assertTrue(self.last_record()["restored"])
        self.assertClean()

    def test_rolls_back_when_the_run_is_interrupted(self):
        before = self.fixture.sha256(SAMPLE)
        stdout_path = self.logs / "interrupt.out"
        stderr_path = self.logs / "interrupt.err"
        pid_log = self.logs / "runner.pid"
        # The child's output goes to files, not pipes: with pipes, waiting for the
        # process also waits for the runner's inherited descriptors, which would
        # hide a runner that was never stopped.
        with stdout_path.open("w", encoding="utf-8") as out, stderr_path.open(
            "w", encoding="utf-8"
        ) as err:
            process = self.fixture.popen_script(
                "--patch",
                str(self.fixture.answer_patch),
                "--class",
                SAMPLE_CLASS,
                stdout=out,
                stderr=err,
                MUTATE_TEST_SLEEP="10",
                MUTATE_TEST_PID_LOG=str(pid_log),
                MUTATE_TEST_XML_CLASS=SAMPLE_CLASS,
                MUTATE_TEST_FAILURES="1",
                MUTATE_TEST_EXIT="1",
            )
            try:
                # Wait until the runner is actually running, so the signal lands
                # while the mutation is applied — the case a reader cares about.
                log = self.logs / "gradle.log"
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline and not (
                    log.is_file() and log.stat().st_size and pid_log.is_file()
                ):
                    time.sleep(0.05)
                self.assertTrue(
                    log.is_file() and log.stat().st_size, "the runner never started"
                )
                process.send_signal(signal.SIGTERM)
                process.wait(timeout=60)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

        runner = int(pid_log.read_text(encoding="utf-8").strip())
        # An interrupt has to stop the run, not just the shell that started it: the
        # runner's own process must be gone, or a stray JVM holds the slot (and the
        # memory) until the next lane dies of OOM.
        gone = False
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.kill(runner, 0)
            except ProcessLookupError:
                gone = True
                break
            time.sleep(0.05)
        if not gone:
            try:
                os.kill(runner, signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.assertTrue(gone, f"the runner {runner} survived the interrupt")

        stdout = stdout_path.read_text(encoding="utf-8")
        stderr = stderr_path.read_text(encoding="utf-8")

        self.assertEqual(143, process.returncode, stderr)
        self.assertEqual(before, self.fixture.sha256(SAMPLE))
        record = json.loads(stdout.strip().splitlines()[-1])
        self.assertEqual("interrupted", record["outcome"])
        self.assertTrue(record["restored"])
        self.assertFalse(record["artifactFresh"])
        self.assertIsNone(record["artifactSha256"])
        self.assertIn("interrupted", stderr)
        self.assertClean()


    def test_names_the_artifact_an_interrupted_run_left_behind(self):
        process = self.fixture.popen_script(
            "--patch",
            str(self.fixture.answer_patch),
            "--class",
            SAMPLE_CLASS,
            MUTATE_TEST_SLEEP="30",
            MUTATE_TEST_XML_FIRST="1",
            MUTATE_TEST_XML_CLASS=SAMPLE_CLASS,
            MUTATE_TEST_FAILURES="1",
            MUTATE_TEST_EXIT="1",
        )
        try:
            artifact = self.fixture.root / artifact_of(SAMPLE_CLASS)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and not artifact.is_file():
                time.sleep(0.05)
            self.assertTrue(artifact.is_file(), "the runner never wrote the XML")
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=60)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

        self.assertEqual(143, process.returncode, stderr)
        record = json.loads(stdout.strip().splitlines()[-1])
        self.assertEqual("interrupted", record["outcome"])
        self.assertTrue(record["artifactFresh"])
        self.assertEqual(self.fixture.artifact_sha(), record["artifactSha256"])

        # The XML on disk is that interrupted mutation run's, and saying so is
        # the whole point: it must not read as a run of HEAD.
        status = self.fixture.status()
        self.assertEqual("mutational", status["verdict"])
        self.assertEqual(record["runId"], status["artifactRunId"])
        self.assertClean()


class MutationStatusTest(MutateFixtureTest):
    def test_reports_the_mutation_artifact_while_it_is_on_disk(self):
        self.assertEqual(0, self.fixture.run_class(self.fixture.answer_patch, failures=1).returncode)
        record = self.last_record()

        status = self.fixture.status()

        self.assertEqual("mutational", status["verdict"])
        self.assertEqual(record["runId"], status["artifactRunId"])
        self.assertEqual(self.fixture.artifact_sha(), status["artifactSha256"])
        self.assertEqual(record["runId"], status["newestRun"]["runId"])
        self.assertTrue(status["headMatches"])
        self.assertIn("mutational", status["summary"])

    def test_reports_a_real_run_that_replaced_the_mutation_artifact(self):
        self.assertEqual(0, self.fixture.run_class(self.fixture.answer_patch, failures=1).returncode)
        mutation_sha = self.fixture.artifact_sha()

        self.fixture.simulate_real_run(SAMPLE_CLASS, tests=7)

        status = self.fixture.status()
        self.assertEqual("genuine", status["verdict"])
        self.assertNotEqual(mutation_sha, status["artifactSha256"])
        self.assertIn("genuine", status["summary"])

    def test_reports_no_artifact_when_nothing_is_on_disk(self):
        status = self.fixture.status()

        self.assertEqual("no-artifact", status["verdict"])
        self.assertIsNone(status["newestRun"])
        self.assertIn("no-artifact", status["summary"])

    def test_reports_unknown_when_the_xml_exists_but_no_log_does(self):
        self.fixture.simulate_real_run(SAMPLE_CLASS)

        status = self.fixture.status()

        self.assertEqual("unknown", status["verdict"])
        self.assertIn("runs.jsonl", status["basis"])

    def test_reports_genuine_for_a_class_with_no_record(self):
        self.assertEqual(0, self.fixture.run_class(self.fixture.answer_patch, failures=1).returncode)
        self.fixture.simulate_real_run(OTHER_CLASS)

        status = self.fixture.status(OTHER_CLASS)

        self.assertEqual("genuine", status["verdict"])
        self.assertIn("no mutation run", status["basis"])

    def test_warns_when_head_moved_after_the_mutation_run(self):
        self.assertEqual(0, self.fixture.run_class(self.fixture.answer_patch, failures=1).returncode)
        self.fixture.write("NOTES.md", "a later commit\n")
        self.fixture.git("add", "-A")
        self.fixture.git("commit", "-q", "-m", "later")

        status = self.fixture.status()

        self.assertEqual("mutational", status["verdict"])
        self.assertFalse(status["headMatches"])
        self.assertIn("not the recorded run's", status["summary"])


class MutationTraceQueryTest(unittest.TestCase):
    """Verdict edges that a real run cannot reach: torn records, old writers."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.log = self.root / ".mutations" / "runs.jsonl"
        self.log.parent.mkdir(parents=True)
        self.artifact = self.root / artifact_of(SAMPLE_CLASS)
        self.artifact.parent.mkdir(parents=True)

    def tearDown(self):
        self.temporary.cleanup()

    def write_record(self, **fields) -> dict:
        record = {
            "schema": 1,
            "kind": "mutation",
            "runId": "20260101T000000Z-1",
            "mutation": "answer",
            "class": SAMPLE_CLASS,
            "task": ":app:testPureJvm",
            "partition": "pure-jvm",
            "artifact": artifact_of(SAMPLE_CLASS),
            "artifactFresh": True,
            "artifactSha256": None,
            "worktree": str(self.root),
            "head": "a" * 40,
            "outcome": "killed",
            "finishedAt": "2026-01-01T00:01:00Z",
            "restored": True,
        }
        record.update(fields)
        with self.log.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
        return record

    def write_artifact(self, content: str) -> str:
        self.artifact.write_text(content, encoding="utf-8")
        return subprocess.run(
            ["sha256sum", str(self.artifact)], check=True, capture_output=True, text=True
        ).stdout.split()[0]

    def status(self, *arguments: str) -> dict:
        result = subprocess.run(
            [
                sys.executable,
                str(TRACE_UNDER_TEST),
                "status",
                "--root",
                str(self.root),
                "--log",
                str(self.log),
                *arguments,
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"status failed: {result.stderr}")
        return json.loads(result.stdout)

    def test_a_torn_append_is_counted_and_named(self):
        self.write_record(runId="first")
        self.write_artifact("<testsuite tests=\"1\" failures=\"1\"/>")
        with self.log.open("a", encoding="utf-8") as handle:
            handle.write('{"schema": 1, "runId": "torn"')

        status = self.status("--class", SAMPLE_CLASS)

        self.assertEqual(1, status["malformedLines"])
        self.assertEqual("first", status["newestRun"]["runId"])

    def test_without_a_class_it_answers_for_the_newest_run(self):
        digest = self.write_artifact("<testsuite tests=\"1\" failures=\"1\"/>")
        self.write_record(artifactSha256=digest)

        status = self.status()

        self.assertEqual("mutational", status["verdict"])
        self.assertEqual(SAMPLE_CLASS, status["class"])

    def test_an_older_run_that_wrote_the_xml_is_named(self):
        digest = self.write_artifact("<testsuite tests=\"1\" failures=\"1\"/>")
        self.write_record(runId="wrote-the-xml", artifactSha256=digest)
        # A later run that left no artifact must not claim the XML on disk.
        self.write_record(runId="built-nothing", artifactSha256=None, outcome="no-verdict")

        status = self.status("--class", SAMPLE_CLASS)

        self.assertEqual("mutational", status["verdict"])
        self.assertEqual("wrote-the-xml", status["artifactRunId"])
        self.assertEqual("built-nothing", status["newestRun"]["runId"])

    def test_a_moved_worktree_still_finds_the_xml_under_this_root(self):
        # `git worktree move` leaves records pointing at a path that is gone; the
        # bytes are what the verdict rests on, and they are still here.
        digest = self.write_artifact('<testsuite tests="1" failures="1"/>')
        self.write_record(worktree=str(self.root / "gone"), artifactSha256=digest)

        status = self.status("--class", SAMPLE_CLASS)

        self.assertEqual("mutational", status["verdict"])
        self.assertEqual(str(self.artifact), status["artifact"])
        self.assertIn("is gone", status["note"])

    def test_a_record_from_another_worktree_says_so(self):
        self.write_record(worktree=str(self.root / "elsewhere"))

        status = self.status("--class", SAMPLE_CLASS)

        self.assertEqual("no-artifact", status["verdict"])
        self.assertIn("elsewhere", status["note"])


class MutationSensitivityTest(unittest.TestCase):
    """The suite must go red when the script stops keeping one of its promises.

    Each case edits the script's source, then runs one real test from this file
    against the edited copy in a subprocess. A promise nobody's test notices is a
    promise that will quietly rot.
    """

    CASES = (
        (
            "the record append",
            "trace",
            'with args.log.open("a", encoding="utf-8") as handle:',
            'with open("/dev/null", "a", encoding="utf-8") as handle:',
            "test_mutate.MutationRunTest.test_runs_the_named_class_and_records_a_caught_mutation",
        ),
        (
            "the dirty-worktree refusal",
            "script",
            'dirty=$(git -C "$repo_root" status --porcelain)',
            'dirty=""',
            "test_mutate.MutationRunTest.test_refuses_a_dirty_worktree",
        ),
        (
            "the interrupt kill",
            "script",
            "if [[ -n $child_pid ]]; then",
            "if false; then",
            "test_mutate.MutationRollbackTest.test_rolls_back_when_the_run_is_interrupted",
        ),
        (
            "the rollback trap",
            "script",
            "trap finalize EXIT",
            ": # the rollback would have happened here",
            "test_mutate.MutationRollbackTest.test_restores_the_file_byte_for_byte_after_a_failing_run",
        ),
    )

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.mutants = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_one_test(self, target: str, mutant: Path | None):
        environment = dict(os.environ)
        if mutant is None:
            environment.pop("SLUKHAYKA_MUTATE_SCRIPT", None)
            environment.pop("SLUKHAYKA_MUTATE_TRACE", None)
        else:
            environment["SLUKHAYKA_MUTATE_SCRIPT"] = str(mutant / "mutate.sh")
            environment["SLUKHAYKA_MUTATE_TRACE"] = str(mutant / "mutation_trace.py")
        return subprocess.run(
            [sys.executable, "-m", "unittest", target],
            cwd=REPO_ROOT / "scripts" / "tests",
            check=False,
            capture_output=True,
            text=True,
            env=environment,
            timeout=300,
        )

    def mutant(self, needle: str, replacement: str, edited: str, index: int) -> Path:
        """One edited file beside a pristine copy of the other one."""
        directory = self.mutants / f"mutant-{index}"
        directory.mkdir()
        sources = {"script": SCRIPT_UNDER_TEST, "trace": TRACE_UNDER_TEST}
        for name, source in sources.items():
            text = source.read_text(encoding="utf-8")
            if name == edited:
                if needle not in text:
                    raise AssertionError(f"the {needle!r} mutation no longer applies")
                text = text.replace(needle, replacement)
            target = directory / ("mutate.sh" if name == "script" else "mutation_trace.py")
            target.write_text(text, encoding="utf-8")
        return directory

    def test_the_suite_dies_when_a_promise_is_mutated(self):
        for index, (name, edited, needle, replacement, target) in enumerate(self.CASES):
            with self.subTest(mutation=name):
                mutant = self.mutant(needle, replacement, edited, index)

                # The nested run has to work at all, or "it failed" means nothing.
                intact = self.run_one_test(target, None)
                self.assertEqual(0, intact.returncode, intact.stderr)
                self.assertIn("Ran 1 test", intact.stderr)

                mutated = self.run_one_test(target, mutant)
                self.assertNotEqual(
                    0,
                    mutated.returncode,
                    f"the {name} mutation survived: no test noticed it",
                )
                self.assertIn("Ran 1 test", mutated.stderr)
                self.assertIn("AssertionError", mutated.stderr)


if __name__ == "__main__":
    unittest.main()
