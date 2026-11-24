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


if __name__ == "__main__":
    unittest.main()
