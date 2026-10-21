"""Тести scripts/whereami.sh — з ІЗОЛЯЦІЄЮ, яка не дає торкнутись справжнього репо.

ІСТОРІЯ, ЯКУ ЦЕЙ ФАЙЛ ЗОБОВ'ЯЗАНИЙ ПАМ'ЯТАТИ.

Перша версія цих тестів робила `git init`, `git checkout`, `git commit` і
`git push` у тимчасовій теці — і `push` пішов у СПРАВЖНІЙ `origin`, бо жоден із
викликів не був ізольований від дерева, у якому бігли тести. Три сміттєві
коміти лягли в `refs/heads/main` на GitHub; `main` довелось відновлювати
`--force-with-lease` на `4caded19`, знайшовши справжнє значення в reflog.

Тому тут три незалежні замки, і кожен — обов'язковий:

1. КОЖЕН виклик git іде з ЯВНИМИ `GIT_DIR` і `GIT_WORK_TREE`, що вказують у
   тимчасовий репозиторій. Немає жодного «просто git» успадкованого з оточення.
2. `setUp` ВІДМОВЛЯЄТЬСЯ працювати, якщо шлях не під `mkdtemp`: тест не має
   права торкнутись нічого, крім теки, яку сам створив.
3. **Жодного `push` тут немає взагалі.** Віддалений `origin` — ЛОКАЛЬНИЙ bare
   репозиторій у тій самій тимчасовій теці, і це перевіряється явно: якщо
   `origin` вказує на GitHub або на будь-що поза `mkdtemp`, тест падає.

Якщо хтось колись додасть сюди `push` — він піде в локальний bare, а не в
мережу, бо іншого `origin` тут не існує.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "whereami.sh"


class IsolationViolation(AssertionError):
    """Порушення ізоляції — це помилка тесту, а не невдача продукту."""


class Repo:
    """Тимчасовий git-репозиторій, який НЕ МОЖЕ бути справжнім.

    Кожен виклик git отримує явні `GIT_DIR`/`GIT_WORK_TREE`. `push` не
    викликається ніде. `origin` — локальний bare у цій же теці.
    """

    def __init__(self, root: Path):
        root = Path(root).resolve()
        # Замок 2: шлях мусить бути під mkdtemp — інакше відмова.
        if tempfile.gettempdir() not in str(root) and "/tmp/" not in str(root):
            raise IsolationViolation(
                "ти не під mkdtemp — тест відмовляється працювати (%s)" % root
            )
        self.root = root
        self.work = root / "work"
        self.gitdir = root / "work" / ".git"
        self.bare = root / "origin.git"
        self.work.mkdir(parents=True)

        self._git("init", "-q", "--initial-branch=main", "--bare", str(self.bare),
                  cwd=str(root), work=None)
        self._git("init", "-q", "--initial-branch=main", str(self.work),
                  cwd=str(root), work=self.work)
        # Замок 3: origin — ЛОКАЛЬНИЙ шлях, і це перевіряється.
        self._git("remote", "add", "origin", str(self.bare))
        origin_url = self._git("remote", "get-url", "origin").stdout.strip()
        if "github.com" in origin_url or not origin_url.startswith(str(root)):
            raise IsolationViolation("origin не локальний: %s" % origin_url)

    def _env(self):
        env = dict(os.environ)
        # Замок 1: явні GIT_DIR і GIT_WORK_TREE. Жодного успадкованого.
        env["GIT_DIR"] = str(self.gitdir)
        env["GIT_WORK_TREE"] = str(self.work)
        env["GIT_CONFIG_GLOBAL"] = "/dev/null"
        env["GIT_CONFIG_SYSTEM"] = "/dev/null"
        env.pop("GIT_INDEX_FILE", None)
        return env

    def _git(self, *args, cwd=None, work="SENTINEL"):
        if work == "SENTINEL":
            work = self.work
        env = self._env()
        if work is None:
            env.pop("GIT_WORK_TREE", None)
        return subprocess.run(
            ["git", *args], capture_output=True, text=True,
            cwd=str(cwd or self.work), env=env, check=False,
        )

    def commit(self, message, files=None):
        for name, body in (files or {"f.txt": "x"}).items():
            (self.work / name).write_text(body, encoding="utf-8")
        self._git("add", "-A")
        self._git("-c", "user.name=T", "-c", "user.email=t@example.invalid",
                  "commit", "-q", "-m", message)
        return self._git("rev-parse", "HEAD").stdout.strip()

    def whereami(self, *args, cwd=None):
        """Запускає whereami.sh з ізольованим оточенням git."""
        return subprocess.run(
            ["bash", str(SCRIPT), *args], capture_output=True, text=True,
            cwd=str(cwd or self.work), env=self._env(), check=False,
        )


class WhereamiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="whereami-test-"))
        self.repo = Repo(self.tmp)

    def tearDown(self):
        # Прибираємо ЛИШЕ те, що самі створили під mkdtemp.
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_a_matching_tree_reports_zero_difference(self):
        """Гілка збігається з origin/main за набором файлів → код 0."""
        self.repo.commit("перший")
        # main у bare ще порожній, тож кладемо туди той самий коміт БЕЗ push:
        # update-ref у ЛОКАЛЬНОМУ bare — це не мережева операція.
        head = self.repo._git("rev-parse", "HEAD").stdout.strip()
        # Локальний ref у ЦЬОМУ репо — жодної мережі, жодного push.
        self.repo._git("update-ref", "refs/remotes/origin/main", head)
        result = self.repo.whereami()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("файлів лише в main: 0", result.stdout)

    def test_a_file_missing_here_is_reported(self):
        """Файл є в origin/main і немає тут → код 1, число > 0."""
        head = self.repo.commit("перший")
        self.repo._git("rm", "-q", "--cached", "f.txt")
        (self.repo.work / "f.txt").unlink()
        (self.repo.work / "g.txt").write_text("y", encoding="utf-8")
        self.repo._git("add", "-A")
        self.repo._git("-c", "user.name=T", "-c", "user.email=t@example.invalid",
                       "commit", "-q", "-m", "другий")
        self.repo._git("update-ref", "refs/remotes/origin/main", head)
        result = self.repo.whereami()
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("файлів лише тут:    1", result.stdout)

    def test_a_missing_origin_main_is_an_error(self):
        """Немає origin/main → код 2, а не тихе «все гаразд»."""
        self.repo.commit("перший")
        self.repo._git("update-ref", "-d", "refs/remotes/origin/main")
        result = self.repo.whereami()
        self.assertEqual(2, result.returncode)
        self.assertIn("недоступне", result.stderr)

    def test_quiet_prints_one_line(self):
        """--quiet — рівно один рядок вердикту."""
        head = self.repo.commit("перший")
        self.repo._git("update-ref", "refs/remotes/origin/main", head)
        result = self.repo.whereami("--quiet")
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(result.stdout.strip().splitlines()))

    def test_the_test_itself_refuses_a_non_temp_path(self):
        """Замок 2 перевіряється: Repo відмовляється поза mkdtemp."""
        with self.assertRaises(IsolationViolation):
            Repo(Path("/home/stealth/Projects/slukhayka"))

    def test_origin_is_never_the_real_github(self):
        """Замок 3 перевіряється: origin тимчасового репо — локальний."""
        url = self.repo._git("remote", "get-url", "origin").stdout.strip()
        self.assertNotIn("github.com", url)
        self.assertTrue(url.startswith(str(self.tmp)))

    def test_no_push_call_exists_in_this_file(self):
        """Сторож проти повернення інциденту: у тесті НЕМАЄ ВИКЛИКУ push.

        Перевіряється саме ВИКЛИК, а не слово: `push` згадується в докстрінгу —
        і мусить згадуватись, бо докстрінг пояснює інцидент. Наївний пошук
        підрядка ловив би пояснення замість небезпеки, тому тут AST.
        """
        import ast
        tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
        offenders = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                for arg in node.args:
                    if isinstance(arg, ast.Constant) and arg.value == "push":
                        offenders.append(node.lineno)
        self.assertEqual([], offenders, "у тесті є виклик git push — це інцидент")


if __name__ == "__main__":
    unittest.main()
