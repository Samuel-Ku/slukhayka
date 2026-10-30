"""#1666 — `bootstrap-worker.sh --check` не має падати на `pgrep -c`.

`pgrep -c` друкує «0» І виходить з 1, коли збігів немає. Старий скрипт робив
`|| echo 0`, додавав ДРУГИЙ нуль, і `[ "0\\n0" -eq 0 ]` друкував
«мало бути використано ціле значення», тобто перевірка «немає активних dsh»
фактично не виконувалась (лише не валила --check). Тест ставить фейковий
`pgrep`, який поводиться саме так, і вимагає: без bash-помилки в stderr і з
рядком «немає активних dsh» у stdout.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "swarm" / "bootstrap-worker.sh"


class BootstrapCheckTest(unittest.TestCase):
    def test_check_survives_pgrep_exit_one_with_zero(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            home = root / "home"
            swarm = home / "swarm"
            repo = swarm / "slukhayka"
            sdk = root / "android-sdk"
            bin_dir = root / "fake-bin"
            for path in (home / ".dsh", swarm / "bin", repo / ".git", sdk / "platforms" / "android-36", sdk / "build-tools" / "36.0.0", bin_dir):
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
            # Справжній pgrep прибираємо з PATH, щоб фейк переміг.
            env = {
                "HOME": str(home),
                "PATH": f"{bin_dir}:/usr/bin:/bin",
                "SWARM_ROOT": str(swarm),
                "REPO_DIR": str(repo),
                "SDK_DIR": str(sdk),
            }
            proc = subprocess.run(
                ["/bin/bash", str(SCRIPT), "--check"],
                capture_output=True,
                text=True,
                env=env,
                timeout=60,
                check=False,
            )

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


if __name__ == "__main__":
    unittest.main()
