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


def environment() -> dict:
    """Оточення гейта — своє, а не успадковане.

    CI-раннер приносить у процес власні змінні, і серед них може бути будь-що:
    експортована функція (`BASH_FUNC_printf%%`, `BASH_FUNC_cat%%`) затіняє
    команду ВСЕРЕДИНІ гейта, відмова друкує не те й виходить не тим кодом — при
    незмінному файлі гейта. Так само в CI почервоніла фікстура #1260, хоч
    локально була зелена; #1265 поширює лік на решту фікстур.

    `scripts/tests/test_mutate.py` задає оточення так само — свіжим словником, а
    не `os.environ`, — і саме тому його фікстура від раннера не залежить.
    """
    return {
        "PATH": "/usr/bin:/bin",
        "HOME": str(REPO_ROOT),
        "LC_ALL": "C",
    }


class GateTest(unittest.TestCase):
    def gate(self, head_name):
        """Запускає гейт на маркері з (не)записаним `head-name`."""
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "rebase-apply"
            marker.mkdir()
            if head_name is not None:
                (marker / "head-name").write_text(head_name + "\n", encoding="utf-8")
            command = ["bash", str(GATE), str(marker)]
            result = subprocess.run(command, check=False, capture_output=True,
                                    text=True, env=environment())
            if result.returncode not in (0, 1):
                # Поза 0/1 гейт не дійшов до рішення — це поломка, не «інша
                # гілка». Причина мусить бути в тексті, а не за кодом виходу.
                raise AssertionError(
                    "гейт вийшов із кодом %d\n--- argv ---\n%s\n--- env ---\n%s\n"
                    "--- stdout ---\n%s\n--- stderr ---\n%s"
                    % (result.returncode, command, sorted(environment()),
                       result.stdout[-500:], result.stderr[-500:])
                )
            return result

    def test_refuses_a_rebase_whose_branch_is_not_named(self):
        """`head-name` = detached HEAD: гілку не названо — відмова, не WARN."""
        result = self.gate("detached HEAD")

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("не названо", result.stderr)

    def test_refuses_a_stopped_git_am_that_names_no_branch(self):
        """`git am` не лишає `head-name` зовсім — і це теж відмова.

        Не літерал у фікстурі, а порожня форма: файлу немає, `cat` не дає
        нічого. Цей тест — те, чого не було в #1254.
        """
        result = self.gate(None)

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("не названо", result.stderr)
        self.assertNotIn("чуж", result.stderr)


if __name__ == "__main__":
    unittest.main()
