"""Стан PR #1254: піниться ЛІТЕРАЛ, який видно в лозі, — і більше нічого.

`head-name` = `detached HEAD` — та форма, яку автор тесту бачив на власному
ребазі. Порожньої форми (`git am` не лишає `head-name` зовсім) не виробляє
жоден тест цього модуля: саме тому гейт тихо зливав, хоч шапка скрипта й
документ обіцяли відмову.

Фікстура мусить лишатися ЧЕРВОНОЮ для механізму
(`scripts/empty_shape_mutants.py`): якщо вона позеленіє, механізм перестав
ловити клас, а не фікстура стала кращою.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GATE = REPO_ROOT / "scripts" / "gate.sh"


class GateTest(unittest.TestCase):
    def gate(self, head_name):
        """Запускає гейт на маркері з (не)записаним `head-name`."""
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "rebase-apply"
            marker.mkdir()
            if head_name is not None:
                (marker / "head-name").write_text(head_name + "\n", encoding="utf-8")
            return subprocess.run(
                ["bash", str(GATE), str(marker)],
                check=False, capture_output=True, text=True,
            )

    def test_refuses_a_rebase_whose_branch_is_not_named(self):
        """`head-name` = detached HEAD: гілку не названо — відмова, не WARN."""
        result = self.gate("detached HEAD")

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("не названо", result.stderr)


if __name__ == "__main__":
    unittest.main()
