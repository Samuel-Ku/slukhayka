"""Стан, який гейт мусить ловити: порожню форму ВИРОБЛЯЄ тест, не літерал.

Той самий `gate.sh`, той самий літерал `detached HEAD` — і, на відміну від
`../unpinned`, тест на справжній `git am`: маркер без файлу `head-name`. Саме
ця пара (порожня форма + мутант, який її знімає) робить перевірку перевіркою.

Різниця між `unpinned` і `pinned` — лише в наборі-свідку; `gate.sh` в обох
коренях байт-у-байт той самий, і це пінить тест механізму.
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

    def test_refuses_a_stopped_git_am_that_names_no_branch(self):
        """`git am` не лишає `head-name` зовсім — і це теж відмова.

        Не літерал у фікстурі, а порожня форма: файлу немає, `cat` не дає
        нічого. Цей тест — те, чого не було в #1254.
        """
        result = self.gate(None)

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("не названо", result.stderr)
        self.assertNotIn("чуж", result.stderr)


if __name__ == "__main__":
    unittest.main()
