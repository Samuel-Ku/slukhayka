"""#1255: the Python half of scripts/mutate.sh, proved against a throwaway repo.

`test_mutate.py` owns the JVM half and its 25 tests are untouched by this file.
The promises here are the same ones, restated for `scripts/**`: a mutation is
applied, one unittest target runs, the mutation is rolled back on every exit path
with the sha256 of every touched file in the record, and the artifact the record
names is the run's own JUnit XML — written where no other run writes, so `status`
answers for a Python target in the same words it answers for a Kotlin class.

The last class is the one that keeps the rest honest: it edits a copy of the tool
until one of its promises is gone and requires a test from this file to go red.
A promise nobody's test notices is a promise that will quietly rot.
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
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
RUNNER_UNDER_TEST = Path(
    os.environ.get(
        "SLUKHAYKA_MUTATE_PYTHON_RUNNER",
        REPO_ROOT / "scripts" / "mutation_python_runner.py",
    )
)

SUBJECT = "scripts/subject.py"
TEST_FILE = "scripts/tests/test_subject.py"
SAMPLE_TARGET = "scripts/tests/test_subject.py::SampleTest"
OTHER_TARGET = "scripts/tests/test_subject.py::OtherTest"
SAMPLE_KEY = "test_subject.SampleTest"
OTHER_KEY = "test_subject.OtherTest"
ERROR_TARGET = "scripts/tests/test_subject.py::ErrorTest"
SKIPPED_TARGET = "scripts/tests/test_subject.py::SkippedTest"
ARTIFACT_DIR = ".mutations/artifacts"

SUBJECT_SOURCE = '''"""The fixture's subject: the file a mutation edits."""


def answer():
    return 42
'''

TEST_SOURCE = '''"""The fixture's witness: one test that pins the answer, one that does not."""

import os
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import subject


class SampleTest(unittest.TestCase):
    def test_answer(self):
        self.assertEqual(42, subject.answer())


class OtherTest(unittest.TestCase):
    def test_answer_is_positive(self):
        self.assertGreater(subject.answer(), 0)


class EmptyTest(unittest.TestCase):
    """No test methods: a target that would "run green" having run nothing."""


class ErrorTest(unittest.TestCase):
    """Notices a mutation by raising: unittest counts that in errors, not failures."""

    def test_answer(self):
        self.assertEqual(42, subject.answer())


class SkippedTest(unittest.TestCase):
    @unittest.skip("the fixture skips its only test")
    def test_never_runs(self):
        self.fail("a skipped test must not run")


class SlowTest(unittest.TestCase):
    def test_sleeps_when_asked(self):
        marker = os.environ.get("MUTATE_PYTHON_FIXTURE_MARKER")
        if not marker:
            return
        Path(marker).write_text(f"{os.getpid()}\\n", encoding="utf-8")
        time.sleep(float(os.environ.get("MUTATE_PYTHON_FIXTURE_SLEEP", "0")))
'''


def artifact_of(key: str) -> str:
    return f"{ARTIFACT_DIR}/TEST-{key}.xml"


class Fixture:
    """A one-commit repository that runs the real scripts/mutate.sh for real."""

    def __init__(self, root: Path, logs: Path, sources: dict[str, Path]):
        self.root = root
        self.logs = logs
        self.build(sources)

    def build(self, sources: dict[str, Path]) -> None:
        (self.root / "scripts" / "tests").mkdir(parents=True)
        (self.root / "scripts" / "mutate.sh").write_text(
            sources["script"].read_text(encoding="utf-8"), encoding="utf-8"
        )
        (self.root / "scripts" / "mutation_trace.py").write_text(
            sources["trace"].read_text(encoding="utf-8"), encoding="utf-8"
        )
        (self.root / "scripts" / "mutation_python_runner.py").write_text(
            sources["runner"].read_text(encoding="utf-8"), encoding="utf-8"
        )
        # The root ignore file keeps .mutations/ invisible: without it the tool's
        # own record and artifact would make the tree dirty for the next run.
        shutil.copy(REPO_ROOT / ".gitignore", self.root / ".gitignore")
        self.write(SUBJECT, SUBJECT_SOURCE)
        self.write(TEST_FILE, TEST_SOURCE)

        self.git("init", "-q", "-b", "main", ".")
        self.git("config", "user.email", "fixture@example.com")
        self.git("config", "user.name", "Fixture")
        self.git("config", "commit.gpgsign", "false")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "fixture")
        self.answer_patch = self.edit_patch("answer", SUBJECT, "return 42", "return 43")
        self.broken_import_patch = self.edit_patch(
            "broken-import",
            SUBJECT,
            "def answer():",
            "import slukhayka_missing_module\n\n\ndef answer():",
        )
        self.syntax_patch = self.edit_patch(
            "syntax-error", SUBJECT, "return 42", "return 42("
        )
        self.raises_patch = self.edit_patch(
            "answer-raises", SUBJECT, "return 42", 'raise RuntimeError("mutated")'
        )

    # -- repository plumbing -------------------------------------------------

    def environment(self, **overrides) -> dict:
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": str(self.logs / "gitconfig"),
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

    def pycache_directories(self) -> list[str]:
        return sorted(
            str(path.relative_to(self.root))
            for path in self.root.rglob("__pycache__")
        )

    def patch_directory(self) -> Path:
        directory = self.root / ".mutations" / "patches"
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def edit_patch(self, name: str, relative: str, old: str, new: str) -> Path:
        original = self.read(relative)
        if old not in original:
            raise AssertionError(f"{old!r} is not in {relative}")
        self.write(relative, original.replace(old, new))
        diff = self.git("diff").stdout
        self.write(relative, original)
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
            ["bash", str(self.root / "scripts" / "mutate.sh"), *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(**overrides),
            timeout=timeout,
        )

    def popen_script(self, *arguments: str, stdout=None, stderr=None, **overrides):
        return subprocess.Popen(
            ["bash", str(self.root / "scripts" / "mutate.sh"), *arguments],
            cwd=self.root,
            stdout=subprocess.PIPE if stdout is None else stdout,
            stderr=subprocess.PIPE if stderr is None else stderr,
            text=True,
            env=self.environment(**overrides),
        )

    def run_target(self, patch: Path, target: str = SAMPLE_TARGET, **overrides):
        return self.run_script("--patch", str(patch), "--python", target, **overrides)

    def run_runner_directly(self, target: str, *, artifact: str | None = None):
        """The runner on its own: its own contract, without mutate.sh in between.

        mutate.sh turns any non-zero runner exit that left no artifact into the
        same "no verdict", so what the runner's exit codes actually are is
        invisible through it — the module's contract needs its own test.
        """
        return subprocess.run(
            [
                "python3",
                "scripts/mutation_python_runner.py",
                "--tests-dir",
                "scripts/tests",
                "--target",
                target,
                "--artifact",
                artifact or artifact_of(target),
            ],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(),
        )

    def run_target_directly(self, target: str = SAMPLE_KEY):
        """A run outside mutate.sh: the same artifact path, written by the runner.

        This is the Python spelling of `gradlew` writing the XML behind the
        tool's back — the artifact a later reader could take for the mutation's.
        """
        return self.run_runner_directly(target)

    def status(self, target: str = SAMPLE_TARGET) -> dict:
        result = self.run_script("status", "--python", target)
        if result.returncode != 0:
            raise AssertionError(f"status failed: {result.stderr}")
        return json.loads(result.stdout)

    def records(self) -> list:
        log = self.root / ".mutations" / "runs.jsonl"
        if not log.is_file():
            return []
        return [
            json.loads(line)
            for line in log.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def artifact_sha(self, key: str = SAMPLE_KEY) -> str:
        return self.sha256(artifact_of(key))

    def artifact_timestamp(self, key: str = SAMPLE_KEY) -> str | None:
        root = ET.parse(self.root / artifact_of(key)).getroot()
        return root.get("timestamp")


class PythonFixtureTest(unittest.TestCase):
    """Shared fixture: a fresh repository per test, never the real one."""

    script_source = SCRIPT_UNDER_TEST
    trace_source = TRACE_UNDER_TEST
    runner_source = RUNNER_UNDER_TEST

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.logs = base / "logs"
        self.logs.mkdir()
        self.fixture = Fixture(
            base / "repo",
            self.logs,
            {
                "script": self.script_source,
                "trace": self.trace_source,
                "runner": self.runner_source,
            },
        )

    def tearDown(self):
        self.temporary.cleanup()

    def assertClean(self):
        self.assertEqual("", self.fixture.dirty_entries(), "the worktree is not clean")

    def last_record(self) -> dict:
        records = self.fixture.records()
        self.assertTrue(records, "no record was appended to .mutations/runs.jsonl")
        return records[-1]


class PythonMutationRunTest(PythonFixtureTest):
    def test_runs_one_python_class_and_records_a_caught_mutation(self):
        result = self.fixture.run_target(self.fixture.answer_patch)

        self.assertEqual(0, result.returncode, result.stderr)
        record = self.last_record()
        # The same record schema the JVM half writes: one answer for both worlds.
        self.assertEqual(1, record["schema"])
        self.assertEqual("mutation", record["kind"])
        self.assertEqual("killed", record["outcome"])
        self.assertEqual(SAMPLE_KEY, record["class"])
        self.assertEqual("python3 -m unittest test_subject.SampleTest", record["task"])
        self.assertEqual("script-tests", record["partition"])
        self.assertEqual("answer", record["mutation"])
        self.assertEqual(1, record["tests"])
        self.assertEqual(1, record["failures"])
        self.assertEqual(0, record["errors"])
        self.assertEqual(1, record["exitCode"])
        self.assertEqual(self.fixture.head(), record["head"])
        self.assertEqual("main", record["branch"])
        self.assertTrue(record["restored"])
        self.assertEqual([SUBJECT], [entry["path"] for entry in record["files"]])
        self.assertTrue(record["files"][0]["restored"])
        self.assertTrue(record["artifactFresh"])
        self.assertEqual(artifact_of(SAMPLE_KEY), record["artifact"])
        self.assertEqual(self.fixture.artifact_sha(), record["artifactSha256"])

        # stdout carries the same record, so a caller can read the verdict without
        # opening the log; stderr carries the proof and the verdict sentence.
        self.assertEqual(record, json.loads(result.stdout.strip().splitlines()[-1]))
        self.assertIn("verdict: caught", result.stderr)
        self.assertIn("sha256", result.stderr)
        self.assertClean()

    def test_records_a_surviving_python_mutation_and_exits_one(self):
        result = self.fixture.run_target(self.fixture.answer_patch, OTHER_TARGET)

        self.assertEqual(1, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("survived", record["outcome"])
        self.assertEqual(OTHER_KEY, record["class"])
        self.assertEqual(1, record["tests"])
        self.assertEqual(0, record["failures"])
        self.assertIn("survived", result.stderr)
        self.assertClean()

    def test_a_whole_file_target_runs_every_class_in_it(self):
        result = self.fixture.run_target(
            self.fixture.answer_patch, "scripts/tests/test_subject.py"
        )

        self.assertEqual(0, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("test_subject", record["class"])
        # SampleTest and ErrorTest both pin 42 and both fail; OtherTest passes;
        # SkippedTest is counted as run and skipped; the class without test
        # methods contributes nothing — the whole file ran.
        self.assertEqual(5, record["tests"])
        self.assertEqual(2, record["failures"])
        self.assertEqual("killed", record["outcome"])
        self.assertClean()

    def test_no_verdict_when_the_target_cannot_be_loaded(self):
        # Two ways a mutation can leave nothing to run: the module it edits stops
        # importing (unittest reports a placeholder test) and stops compiling (the
        # loader raises). Neither is a test noticing the mutation, so neither may
        # be recorded as "killed" — the JVM half's compile error, in Python.
        for name, patch in (
            ("import failure", self.fixture.broken_import_patch),
            ("syntax error", self.fixture.syntax_patch),
        ):
            with self.subTest(broken=name):
                result = self.fixture.run_target(patch)

                self.assertEqual(3, result.returncode, result.stderr)
                record = self.last_record()
                self.assertEqual("no-verdict", record["outcome"])
                self.assertFalse(record["artifactFresh"])
                self.assertIsNone(record["artifactSha256"])
                self.assertTrue(record["restored"])
                self.assertIn("did not load", record["detail"])
                self.assertFalse((self.fixture.root / artifact_of(SAMPLE_KEY)).exists())
                self.assertClean()

    def test_no_verdict_when_the_target_declares_no_tests(self):
        # An empty TestCase runs green having run nothing: `wasSuccessful` is
        # True with 0 tests. Recorded as a verdict it would be a false "survived",
        # which is the one answer this tool must never invent.
        result = self.fixture.run_target(
            self.fixture.answer_patch, "scripts/tests/test_subject.py::EmptyTest"
        )

        self.assertEqual(3, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("no-verdict", record["outcome"])
        self.assertIsNone(record["tests"])
        self.assertFalse(record["artifactFresh"])
        self.assertIn("declared no tests", record["detail"])
        self.assertFalse(
            (self.fixture.root / artifact_of("test_subject.EmptyTest")).exists()
        )
        self.assertClean()

    def test_a_stale_artifact_is_not_recorded_as_this_runs_artifact(self):
        unrecorded = self.fixture.run_target_directly(SAMPLE_KEY)
        self.assertEqual(0, unrecorded.returncode, unrecorded.stderr)
        unrecorded_sha = self.fixture.artifact_sha()

        result = self.fixture.run_target(self.fixture.broken_import_patch)

        self.assertEqual(3, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("no-verdict", record["outcome"])
        self.assertFalse(record["artifactFresh"])
        self.assertIsNone(record["artifactSha256"])
        self.assertEqual(unrecorded_sha, self.fixture.artifact_sha())
        self.assertEqual("genuine", self.fixture.status()["verdict"])
        self.assertClean()

    def test_records_an_error_as_a_caught_mutation(self):
        # A witness can notice a mutation by raising, not only by failing an
        # assertion, and unittest counts that in `errors`. The verdict reads
        # failures and errors alike, so both counts have to be carried into the
        # artifact and read back out of it.
        result = self.fixture.run_target(self.fixture.raises_patch, ERROR_TARGET)

        self.assertEqual(0, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("killed", record["outcome"])
        self.assertEqual(1, record["tests"])
        self.assertEqual(0, record["failures"])
        self.assertEqual(1, record["errors"])
        self.assertIn("errors=1", record["detail"])
        self.assertIn("verdict: caught", result.stderr)
        self.assertClean()

    def test_no_verdict_when_every_test_was_skipped(self):
        # tests=1 failures=0 errors=0 and a zero exit is exactly what a survivor
        # looks like — except that nothing executed. Recorded as survived it
        # would call a live mutation a survivor, so a run that skipped everything
        # is no verdict.
        result = self.fixture.run_target(self.fixture.answer_patch, SKIPPED_TARGET)

        self.assertEqual(3, result.returncode, result.stderr)
        record = self.last_record()
        self.assertEqual("no-verdict", record["outcome"])
        self.assertEqual(1, record["tests"])
        self.assertEqual(0, record["failures"])
        self.assertIn("every test was skipped", record["detail"])
        self.assertClean()

    def test_the_runner_names_a_target_it_cannot_load_with_exit_three(self):
        # The module's contract, not mutate.sh's translation of it: exit 3 and no
        # artifact at all, both for a name that does not resolve and for a class
        # that declares no tests.
        for target in ("test_subject.NopeTest", "test_subject.EmptyTest"):
            with self.subTest(target=target):
                artifact = artifact_of(target)

                result = self.fixture.run_runner_directly(target, artifact=artifact)

                self.assertEqual(3, result.returncode, result.stderr)
                self.assertFalse((self.fixture.root / artifact).exists())
                self.assertFalse((self.fixture.root / artifact).parent.exists())

    def test_a_second_run_of_the_same_target_is_fresh_not_stale(self):
        first = self.fixture.run_target(self.fixture.answer_patch)
        first_sha = self.fixture.artifact_sha()
        first_timestamp = self.fixture.artifact_timestamp()

        second = self.fixture.run_target(self.fixture.answer_patch)

        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(0, second.returncode, second.stderr)
        record = self.last_record()
        # An artifact that kept the previous run's bytes is a stale artifact, not
        # a verdict — so a fresh run must not be able to reproduce them. Gradle's
        # XML carries a timestamp for that; this XML has to carry its own.
        self.assertEqual("killed", record["outcome"])
        self.assertTrue(record["artifactFresh"])
        self.assertEqual(self.fixture.artifact_sha(), record["artifactSha256"])
        self.assertNotEqual(first_sha, self.fixture.artifact_sha())
        self.assertIsNotNone(first_timestamp)
        self.assertNotEqual(first_timestamp, self.fixture.artifact_timestamp())
        self.assertEqual(2, len(self.fixture.records()))
        self.assertClean()

    def test_refuses_a_dirty_worktree(self):
        dirty = self.fixture.read(SUBJECT) + "\n# a working edit\n"
        self.fixture.write(SUBJECT, dirty)

        result = self.fixture.run_target(self.fixture.answer_patch)

        self.assertEqual(2, result.returncode)
        self.assertIn("dirty", result.stderr)
        # The refusal must not touch the working edit, and must not run anything.
        self.assertEqual(dirty, self.fixture.read(SUBJECT))
        self.assertEqual([], self.fixture.records())

    def test_refuses_a_target_outside_the_discovered_test_files(self):
        for target in ("scripts/subject.py", "web/probe.test.ts", "scripts/tests/helper.py"):
            with self.subTest(target=target):
                result = self.fixture.run_target(self.fixture.answer_patch, target)

                self.assertEqual(2, result.returncode)
                self.assertIn("script-tests job discovers", result.stderr)
                self.assertEqual([], self.fixture.records())
                self.assertClean()

    def test_refuses_a_class_the_file_does_not_declare(self):
        result = self.fixture.run_target(
            self.fixture.answer_patch, "scripts/tests/test_subject.py::NopeTest"
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("no class NopeTest", result.stderr)
        self.assertEqual([], self.fixture.records())
        self.assertClean()

    def test_refuses_a_target_file_that_does_not_exist(self):
        result = self.fixture.run_target(
            self.fixture.answer_patch, "scripts/tests/test_absent.py"
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("no test file", result.stderr)
        self.assertEqual([], self.fixture.records())

    def test_refuses_class_and_python_together(self):
        result = self.fixture.run_script(
            "--patch",
            str(self.fixture.answer_patch),
            "--class",
            "com.slukhayka.fixture.SampleTest",
            "--python",
            SAMPLE_TARGET,
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("pass one", result.stderr)
        self.assertEqual([], self.fixture.records())


class PythonRollbackTest(PythonFixtureTest):
    def test_restores_the_mutated_script_byte_for_byte(self):
        before = self.fixture.sha256(SUBJECT)

        result = self.fixture.run_target(self.fixture.answer_patch)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.fixture.sha256(SUBJECT))
        entry = self.last_record()["files"][0]
        self.assertEqual(before, entry["pristineSha256"])
        self.assertEqual(before, entry["rolledBackSha256"])
        self.assertTrue(self.last_record()["restored"])
        self.assertIn(f"sha256 {before} before and after", result.stderr)
        self.assertClean()

    def test_removes_a_file_the_mutation_added(self):
        added = "scripts/added_subject.py"
        patch = self.fixture.added_file_patch(
            "add-file", added, '"""Added by a mutation."""\n'
        )

        result = self.fixture.run_target(patch)

        # The mutation is a no-op for the witness, so it survives — the tool does
        # not invent a verdict. What this test pins is the rollback: a file the
        # patch added is a file the rollback removes.
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertFalse((self.fixture.root / added).exists())
        entry = self.last_record()["files"][0]
        self.assertIsNone(entry["pristineSha256"])
        self.assertIsNone(entry["rolledBackSha256"])
        self.assertTrue(entry["restored"])
        self.assertTrue(self.last_record()["restored"])
        self.assertClean()

    def test_leaves_no_pycache_in_the_tree(self):
        result = self.fixture.run_target(self.fixture.answer_patch)

        self.assertEqual(0, result.returncode, result.stderr)
        # A .pyc is validated by the source's size and whole-second mtime, so a
        # same-size mutation applied in the same second as the previous compile
        # would be read from a stale cache: the suite would stay green and the run
        # would call a live mutation a survivor. Nothing of that cache may live in
        # the tree either — `git status` cannot see it (it is ignored), so the
        # check is on the directory, not on the porcelain.
        self.assertEqual([], self.fixture.pycache_directories())
        self.assertClean()

    def test_rolls_back_when_the_python_run_is_interrupted(self):
        before = self.fixture.sha256(SUBJECT)
        marker = self.logs / "runner.pid"
        stdout_path = self.logs / "interrupt.out"
        stderr_path = self.logs / "interrupt.err"
        # The child's output goes to files, not pipes: with pipes, waiting for the
        # process also waits for the runner's inherited descriptors, which would
        # hide a runner that was never stopped.
        with stdout_path.open("w", encoding="utf-8") as out, stderr_path.open(
            "w", encoding="utf-8"
        ) as err:
            process = self.fixture.popen_script(
                "--patch",
                str(self.fixture.answer_patch),
                "--python",
                "scripts/tests/test_subject.py::SlowTest",
                stdout=out,
                stderr=err,
                MUTATE_PYTHON_FIXTURE_MARKER=str(marker),
                MUTATE_PYTHON_FIXTURE_SLEEP="10",
            )
            try:
                # Wait until the target is running, so the signal lands while the
                # mutation is applied — the case a reader cares about.
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline and not marker.is_file():
                    time.sleep(0.05)
                self.assertTrue(marker.is_file(), "the runner never started")
                process.send_signal(signal.SIGTERM)
                process.wait(timeout=60)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

        runner = int(marker.read_text(encoding="utf-8").strip())
        # An interrupt has to stop the run, not just the shell that started it:
        # the runner's own process must be gone, or a stray interpreter holds the
        # target's resources until someone notices.
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

        stderr = stderr_path.read_text(encoding="utf-8")
        record = json.loads(stdout_path.read_text(encoding="utf-8").strip().splitlines()[-1])

        self.assertEqual(143, process.returncode, stderr)
        self.assertEqual(before, self.fixture.sha256(SUBJECT))
        self.assertEqual("interrupted", record["outcome"])
        self.assertTrue(record["restored"])
        self.assertFalse(record["artifactFresh"])
        self.assertIsNone(record["artifactSha256"])
        self.assertIn("interrupted", stderr)
        self.assertClean()


class PythonStatusTest(PythonFixtureTest):
    def test_the_artifact_on_disk_reads_as_the_mutation_runs_own(self):
        self.assertEqual(0, self.fixture.run_target(self.fixture.answer_patch).returncode)
        record = self.last_record()

        status = self.fixture.status()

        self.assertEqual("mutational", status["verdict"])
        self.assertEqual(SAMPLE_KEY, status["class"])
        self.assertEqual(record["runId"], status["artifactRunId"])
        self.assertEqual(self.fixture.artifact_sha(), status["artifactSha256"])
        self.assertEqual(record["runId"], status["newestRun"]["runId"])
        self.assertTrue(status["headMatches"])
        self.assertIn("mutational", status["summary"])

    def test_a_removed_artifact_reads_as_none_on_disk(self):
        self.assertEqual(0, self.fixture.run_target(self.fixture.answer_patch).returncode)

        (self.fixture.root / artifact_of(SAMPLE_KEY)).unlink()

        status = self.fixture.status()
        self.assertEqual("no-artifact", status["verdict"])
        self.assertIn("no XML", status["basis"])

    def test_before_any_run_there_is_no_artifact_and_no_record(self):
        status = self.fixture.status()

        self.assertEqual("no-artifact", status["verdict"])
        self.assertIsNone(status["newestRun"])
        self.assertEqual(SAMPLE_KEY, status["class"])


class PythonSensitivityTest(unittest.TestCase):
    """The suite must go red when the tool stops keeping one of its promises.

    Each case edits a copy of one script, then runs one real test from this file
    against the copies in a subprocess. `scripts/mutate.sh` itself is edited as a
    copy on purpose: a patch that rewrites the file the running shell is still
    reading proves nothing about the code that ran.
    """

    CASES = (
        (
            "the python load guard",
            "runner",
            "if unloadable:",
            "if False:  # mutated",
            "test_mutate_python.PythonMutationRunTest.test_no_verdict_when_the_target_cannot_be_loaded",
        ),
        (
            "the empty-suite guard",
            "runner",
            "    if not tests:",
            "    if False:  # mutated",
            "test_mutate_python.PythonMutationRunTest.test_no_verdict_when_the_target_declares_no_tests",
        ),
        (
            "the dirty-worktree refusal for --python",
            "script",
            'dirty=$(git -C "$repo_root" status --porcelain)',
            'dirty=""',
            "test_mutate_python.PythonMutationRunTest.test_refuses_a_dirty_worktree",
        ),
        (
            "the python failure counts",
            "runner",
            '"failures": str(len(result.failures) + len(result.unexpectedSuccesses)),',
            '"failures": "0",',
            "test_mutate_python.PythonMutationRunTest.test_runs_one_python_class_and_records_a_caught_mutation",
        ),
        (
            "the python error counts",
            "runner",
            '"errors": str(len(result.errors)),',
            '"errors": "0",',
            "test_mutate_python.PythonMutationRunTest.test_records_an_error_as_a_caught_mutation",
        ),
        (
            "the load-failure exit code",
            "runner",
            "LOAD_FAILURE = 3",
            "LOAD_FAILURE = 7",
            "test_mutate_python.PythonMutationRunTest.test_the_runner_names_a_target_it_cannot_load_with_exit_three",
        ),
        (
            "the all-skipped guard",
            "script",
            'elif [[ $skipped == "$tests" ]]; then',
            "elif false; then",
            "test_mutate_python.PythonMutationRunTest.test_no_verdict_when_every_test_was_skipped",
        ),
        (
            "the artifact timestamp",
            "runner",
            'datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]',
            '"2026-01-01T00:00:00.000"',
            "test_mutate_python.PythonMutationRunTest.test_a_second_run_of_the_same_target_is_fresh_not_stale",
        ),
        (
            "the python target validation",
            "script",
            "[[ $python_file =~ ^scripts/tests/test_[A-Za-z0-9_]+\\.py$ ]] ||",
            "[[ -n $python_file ]] ||",
            "test_mutate_python.PythonMutationRunTest.test_refuses_a_target_outside_the_discovered_test_files",
        ),
        (
            "the pycache isolation",
            "script",
            'PYTHONPYCACHEPREFIX=$scratch/pycache python3 "$python_runner" \\',
            'python3 "$python_runner" \\',
            "test_mutate_python.PythonRollbackTest.test_leaves_no_pycache_in_the_tree",
        ),
        (
            "the python artifact path",
            "script",
            "  artifact_relative=$python_results_relative",
            "  artifact_relative=$results_relative",
            "test_mutate_python.PythonMutationRunTest.test_runs_one_python_class_and_records_a_caught_mutation",
        ),
    )

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.mutants = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_one_test(self, target: str, mutant: Path | None):
        environment = dict(os.environ)
        names = (
            "SLUKHAYKA_MUTATE_SCRIPT",
            "SLUKHAYKA_MUTATE_TRACE",
            "SLUKHAYKA_MUTATE_PYTHON_RUNNER",
        )
        for name in names:
            environment.pop(name, None)
        if mutant is not None:
            environment["SLUKHAYKA_MUTATE_SCRIPT"] = str(mutant / "mutate.sh")
            environment["SLUKHAYKA_MUTATE_TRACE"] = str(mutant / "mutation_trace.py")
            environment["SLUKHAYKA_MUTATE_PYTHON_RUNNER"] = str(
                mutant / "mutation_python_runner.py"
            )
        return subprocess.run(
            [sys.executable, "-m", "unittest", target],
            cwd=REPO_ROOT / "scripts" / "tests",
            check=False,
            capture_output=True,
            text=True,
            env=environment,
            timeout=600,
        )

    def mutant(self, needle: str, replacement: str, edited: str, index: int) -> Path:
        """One edited file beside pristine copies of the others."""
        directory = self.mutants / f"mutant-{index}"
        directory.mkdir()
        sources = {
            "script": SCRIPT_UNDER_TEST,
            "trace": TRACE_UNDER_TEST,
            "runner": RUNNER_UNDER_TEST,
        }
        for name, source in sources.items():
            text = source.read_text(encoding="utf-8")
            if name == edited:
                if needle not in text:
                    raise AssertionError(f"the {needle!r} mutation no longer applies")
                text = text.replace(needle, replacement)
            target = directory / ("mutate.sh" if name == "script" else source.name)
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
