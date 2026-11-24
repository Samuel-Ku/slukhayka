"""Ліцензії постачаного графа: POM із репозиторію, а не «none» з порожнечі.

#1355: скрипт шукав POM-и лише в `caches/modules-2/files-2.1`, де Gradle 9
тримає jar/aar, тож усі координати ставали «без POM», а секція
`GPL-family` друкувала `none` про граф із NewPipeExtractor під GPL. Тут
перевіряються обидва боки межі:

  * ліцензія читається з POM, який віддає репозиторій (JitPack / Google
    Maven / Maven Central), з кешу або з батьківського POM;
  * координата, для якої ліцензію не встановлено, називається UNKNOWN, і
    `none` не друкується, доки хоч одна лишилася неперевіреною.

Мережа в тестах не потрібна: `inventory` приймає функцію запиту, тож кожен
тест відповідає рівно тими POM-ами, які перевіряє.
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
MECHANISM = REPO_ROOT / "scripts" / "app_dependency_licenses.py"
SHELL_SCRIPT = REPO_ROOT / "scripts" / "app-dependency-licenses.sh"
sys.path.insert(0, str(MECHANISM.parent))

import app_dependency_licenses as licenses  # noqa: E402


REPOSITORIES = {repository.name: repository for repository in licenses.REPOSITORIES}


def repository_url(group: str, artifact: str, version: str, name: str) -> str:
    return licenses.Coordinate(group, artifact, version).pom_url(REPOSITORIES[name])


def pom(
    group: str,
    artifact: str,
    version: str,
    *,
    license_name: str | None = None,
    parent: tuple[str, str, str] | None = None,
) -> str:
    license_block = ""
    if license_name is not None:
        license_block = (
            "<licenses><license><name>"
            f"{license_name}"
            "</name></license></licenses>"
        )
    parent_block = ""
    if parent is not None:
        parent_block = (
            "<parent>"
            f"<groupId>{parent[0]}</groupId>"
            f"<artifactId>{parent[1]}</artifactId>"
            f"<version>{parent[2]}</version>"
            "</parent>"
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        f"<groupId>{group}</groupId>"
        f"<artifactId>{artifact}</artifactId>"
        f"<version>{version}</version>"
        f"{parent_block}{license_block}"
        "</project>"
    )


def tree(*coordinates: str) -> str:
    return "".join(f"+--- {coordinate}\n" for coordinate in coordinates)


class FakeFetch:
    """URL → POM. A URL that is absent answers 404, like the repositories do."""

    def __init__(self, answers=None):
        self.answers = dict(answers or {})
        self.calls: list[str] = []

    def __call__(self, url: str) -> licenses.Fetch:
        self.calls.append(url)
        answer = self.answers.get(url)
        if answer is None:
            return licenses.Fetch(404)
        if isinstance(answer, Exception):
            return licenses.Fetch(None, error=str(answer))
        return licenses.Fetch(200, answer)


def write_cached_pom(cache: Path, group: str, artifact: str, version: str, body: str) -> None:
    directory = cache / group / artifact / version / "0123456789abcdef"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{artifact}-{version}.pom").write_text(body, encoding="utf-8")


def gpl_section(rendered: str) -> str:
    return rendered.split("--- GPL-family in the SHIPPED graph ---", 1)[1]


def has_none_line(section: str) -> bool:
    return any(line.strip() == "none" for line in section.splitlines())


class RepositoryPomTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="app-licenses-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache = self.root / "cache"

    def test_the_gpl_license_comes_from_the_jitpack_pom(self):
        coordinate = "com.github.teamnewpipe:NewPipeExtractor:v0.26.5"
        url = repository_url(
            "com.github.teamnewpipe", "NewPipeExtractor", "v0.26.5", "JitPack"
        )
        fetch = FakeFetch(
            {url: pom(
                "com.github.teamnewpipe",
                "NewPipeExtractor",
                "v0.26.5",
                license_name="GNU General Public License v3.0 or later",
            )}
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)
        rendered = licenses.render(report)

        self.assertEqual(1, len(report.known))
        self.assertEqual("JitPack", report.known[0].source)
        self.assertIn("GNU General Public License v3.0 or later", rendered)
        self.assertIn(coordinate, gpl_section(rendered))
        self.assertFalse(has_none_line(gpl_section(rendered)))

    def test_google_maven_supplies_an_androidx_pom(self):
        coordinate = "androidx.core:core:1.13.1"
        url = repository_url("androidx.core", "core", "1.13.1", "Google Maven")
        fetch = FakeFetch(
            {url: pom(
                "androidx.core",
                "core",
                "1.13.1",
                license_name="The Apache Software License, Version 2.0",
            )}
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)

        self.assertEqual(1, len(report.known))
        self.assertEqual("Google Maven", report.known[0].source)
        self.assertIn(url, fetch.calls)

    def test_a_cached_pom_is_read_before_the_network(self):
        coordinate = "org.example:cached:1.0.0"
        write_cached_pom(
            self.cache,
            "org.example",
            "cached",
            "1.0.0",
            pom("org.example", "cached", "1.0.0", license_name="MIT License"),
        )
        fetch = FakeFetch({url: RuntimeError("network must not be touched")
                           for url in (
                               repository_url(
                                   "org.example", "cached", "1.0.0", name
                               )
                               for name in REPOSITORIES
                           )})

        report = licenses.inventory(tree(coordinate), self.cache, fetch)

        self.assertEqual(1, len(report.known))
        self.assertEqual("cache", report.known[0].source)
        self.assertEqual([], fetch.calls)

    def test_a_parent_pom_license_is_inherited(self):
        coordinate = "org.example:child:1.0.0"
        parent = ("org.example", "parent", "7")
        child_url = repository_url(
            "org.example", "child", "1.0.0", "Maven Central"
        )
        parent_url = repository_url(
            "org.example", "parent", "7", "Maven Central"
        )
        fetch = FakeFetch(
            {
                child_url: pom(
                    "org.example", "child", "1.0.0", parent=parent
                ),
                parent_url: pom(
                    "org.example",
                    "parent",
                    "7",
                    license_name="The Apache Software License, Version 2.0",
                ),
            }
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)

        self.assertEqual(1, len(report.known))
        self.assertEqual(
            ("The Apache Software License, Version 2.0",),
            report.known[0].licenses,
        )
        self.assertEqual("Maven Central", report.known[0].source)

    def test_a_pom_without_licenses_is_unknown_and_says_why(self):
        coordinate = "org.example:bare:1.0.0"
        fetch = FakeFetch(
            {
                repository_url("org.example", "bare", "1.0.0", "Maven Central"):
                    pom("org.example", "bare", "1.0.0"),
            }
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)
        rendered = licenses.render(report)

        self.assertEqual(1, len(report.unknown))
        self.assertIn("no <licenses>", report.unknown[0].reason)
        self.assertIn("UNKNOWN", gpl_section(rendered))
        self.assertFalse(has_none_line(gpl_section(rendered)))

    def test_a_network_failure_is_unknown_not_an_empty_license(self):
        coordinate = "org.example:offline:1.0.0"
        fetch = FakeFetch(
            {
                repository_url("org.example", "offline", "1.0.0", "Maven Central"):
                    OSError("connection refused"),
            }
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)

        self.assertEqual(1, len(report.unknown))
        self.assertIn("connection refused", report.unknown[0].reason)


class UnknownTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="app-licenses-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache = self.root / "cache"

    def test_an_unverified_license_is_named_unknown_not_none(self):
        coordinates = ("org.example:one:1.0.0", "org.example:two:1.0.0")

        report = licenses.inventory(
            tree(*coordinates), self.cache, FakeFetch(), offline=True
        )
        rendered = licenses.render(report)
        section = gpl_section(rendered)

        self.assertEqual(2, len(report.unknown))
        self.assertIn("UNKNOWN", section)
        self.assertIn("2 з 2", section)
        self.assertFalse(has_none_line(section))
        self.assertIn("--- ліцензія не визначена ---", rendered)

    def test_none_is_printed_only_when_every_license_is_known(self):
        coordinate = "org.example:known:1.0.0"
        fetch = FakeFetch(
            {
                repository_url("org.example", "known", "1.0.0", "Maven Central"):
                    pom(
                        "org.example",
                        "known",
                        "1.0.0",
                        license_name="MIT License",
                    ),
            }
        )

        report = licenses.inventory(tree(coordinate), self.cache, fetch)
        section = gpl_section(licenses.render(report))

        self.assertEqual((), report.unknown)
        self.assertTrue(has_none_line(section))
        self.assertNotIn("UNKNOWN", section)

    def test_an_empty_graph_is_unknown_and_not_none(self):
        report = licenses.inventory("", self.cache, FakeFetch(), offline=True)
        section = gpl_section(licenses.render(report))

        self.assertIn("UNKNOWN", section)
        self.assertFalse(has_none_line(section))


class ShellTest(unittest.TestCase):
    """The shell entry point still resolves the tree and then calls the module."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="app-licenses-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.tree = self.root / "app-dependencies.txt"
        self.coordinate = "com.github.teamnewpipe:NewPipeExtractor:v0.26.5"
        self.tree.write_text(tree(self.coordinate), encoding="utf-8")
        write_cached_pom(
            self.root / "caches/modules-2/files-2.1",
            "com.github.teamnewpipe",
            "NewPipeExtractor",
            "v0.26.5",
            pom(
                "com.github.teamnewpipe",
                "NewPipeExtractor",
                "v0.26.5",
                license_name="GNU General Public License v3.0 or later",
            ),
        )

    def test_the_shell_script_reports_the_cached_gpl_license_without_gradle(self):
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": str(self.root),
            "LC_ALL": "C",
            "GRADLE_USER_HOME": str(self.root),
            "TREE": str(self.tree),
            "APP_LICENSE_OFFLINE": "1",
        }

        result = subprocess.run(
            ["bash", str(SHELL_SCRIPT)],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(self.coordinate, gpl_section(result.stdout))
        self.assertFalse(has_none_line(gpl_section(result.stdout)))


if __name__ == "__main__":
    unittest.main()
