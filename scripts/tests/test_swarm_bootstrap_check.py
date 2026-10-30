"""#1666/#1668 — `bootstrap-worker.sh --check` переживає підставні інструменти
й неіснуючий клон, а не падає на першій же складовій.

Два дефекти, знайдені прогоном на hub/воркері:

* #1666 — `pgrep -c` друкує «0» І виходить з 1, коли збігів немає; старий
  `|| echo 0` додавав ДРУГИЙ нуль, і `[ "0\n0" -eq 0 ]` друкував «мало бути
  використано ціле значення», тобто перевірка «немає активних dsh» фактично не
  виконувалась (лише не валила --check);
* #1668 — `set -o pipefail` робив `df` на неіснуючому `$REPO_DIR` фатальним
  для всього --check: скрипт умирав на рядку диска, не назвавши решту
  складових.

Тест ставить фейкові `gh` (успіх) і `pgrep` («0» + код 1), неіснуючий
`$REPO_DIR` і вимагає: порожній stderr, рядок «немає активних dsh» і те, що
`--check` дійшов до складових після диска.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "swarm" / "bootstrap-worker.sh"


class BootstrapCheckTest(unittest.TestCase):
    def _run_check(self, raw: str) -> subprocess.CompletedProcess:
        root = Path(raw)
        home = root / "home"
        swarm = home / "swarm"
        sdk = root / "android-sdk"
        bin_dir = root / "fake-bin"
        for path in (
            home / ".dsh",
            swarm / "bin",
            sdk / "platforms" / "android-36",
            sdk / "build-tools" / "36.0.0",
            bin_dir,
        ):
            path.mkdir(parents=True, exist_ok=True)
        (home / ".dsh" / ".credentials.yaml").write_text("refs: {}\n", encoding="utf-8")
        # env.sh порожній: dsh у PATH немає, --check назве це FAIL — не важливо.
        (swarm / "env.sh").write_text("", encoding="utf-8")
        # `gh auth status` фейковий: --check не має залежати від справжнього gh.
        gh = swarm / "bin" / "gh"
        gh.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        gh.chmod(0o755)
        # pgrep -c: «0» у stdout і код 1 — точно як справжній pgrep без збігів.
        pgrep = bin_dir / "pgrep"
        pgrep.write_text("#!/bin/sh\necho 0\nexit 1\n", encoding="utf-8")
        pgrep.chmod(0o755)
        env = {
            "HOME": str(home),
            "PATH": f"{bin_dir}:/usr/bin:/bin",
            "SWARM_ROOT": str(swarm),
            # $REPO_DIR навмисно НЕ створюється: існування клону — окремий
            # пункт --check, і він має назвати FAIL, а не вбити прогін.
            "REPO_DIR": str(swarm / "slukhayka"),
            "SDK_DIR": str(sdk),
        }
        return subprocess.run(
            ["/bin/bash", str(SCRIPT), "--check"],
            capture_output=True,
            text=True,
            env=env,
            timeout=60,
            check=False,
        )

    def test_check_survives_pgrep_exit_one_with_zero(self):
        with tempfile.TemporaryDirectory() as raw:
            proc = self._run_check(raw)

        # #1667 (огляд) — локалізований рядок залежить від LANG, тож
        # перевіряємо ПОВНУ тишу: на старому коді bash пише помилку в stderr
        # (українською або англійською — байдуже).
        self.assertEqual(
            "",
            proc.stderr.strip(),
            f"--check упав на pgrep; stderr:\n{proc.stderr}",
        )
        self.assertIn("немає активних dsh", proc.stdout)
        # Код --check — сума справжніх FAIL-ів (тут JDK/SDK/dsh), не 2 від bash.
        self.assertIn(proc.returncode, (0, 1), proc.stderr)

    def test_check_reports_all_lines_when_repo_dir_is_missing(self):
        # #1668 — df на неіснуючому $REPO_DIR + `set -o pipefail` раніше
        # вбивали весь --check: після рядка диска жодна перевірка не
        # друкувалась, stderr лишався порожнім, а код був 1.
        with tempfile.TemporaryDirectory() as raw:
            proc = self._run_check(raw)

        self.assertEqual("", proc.stderr.strip(), proc.stderr)
        self.assertEqual(1, proc.returncode, proc.stdout)
        self.assertIn("диск (", proc.stdout)
        self.assertIn("клон репозиторію", proc.stdout)
        # Головне: --check дійшов до складових ПІСЛЯ диска, а не вмер на df.
        self.assertIn("немає активних dsh", proc.stdout)


if __name__ == "__main__":
    unittest.main()
