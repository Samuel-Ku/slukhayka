"""`scripts/wide-window.sh`: the density arithmetic and the device path.

The two width lines the app draws its layout at are READ out of the Kotlin
source (`width_line`), not restated as literals here: a copy would stay green on
the day Android moves the line, which is the class of defect #1223 charged a PR
for one layer up (`web/src/ui/adaptiveLayout.test.tsx` reads the same file for
the same reason).
"""

import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WIDE_WINDOW = REPO_ROOT / "scripts" / "wide-window.sh"
WINDOW_LAYOUT = (
    REPO_ROOT / "app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt"
)


def width_line(name: str) -> int:
    """A width line of the app, read out of `WindowLayout.kt`.

    The constants belong to the app: `ExpandedMinWidthDp` is the wide line and
    `PlayerPaneMinWidthDp` is the second one the wide-window recipe is asked to
    cross. Comments are stripped and the match is anchored to a line start, so a
    commented-out declaration cannot satisfy it, and a moved file or a renamed
    constant fails loudly instead of quietly checking nothing.
    """
    try:
        source = WINDOW_LAYOUT.read_text(encoding="utf-8")
    except OSError as error:
        raise AssertionError(
            f"source not found: {WINDOW_LAYOUT} ({error}) — if the file moved, "
            "update the path here, otherwise nobody compares the line this "
            "script is pointed at"
        )
    code = re.sub(r"/\*[\s\S]*?\*/", "", source)
    code = re.sub(r"//[^\n]*", "", code)
    match = re.search(
        rf"^const val {re.escape(name)}: Int = (\d+)\s*$", code, re.MULTILINE
    )
    if match is None:
        raise AssertionError(f"constant not found: {name} in {WINDOW_LAYOUT}")
    return int(match.group(1))


def tool_environment(**overrides) -> dict:
    """Оточення `scripts/wide-window.sh` — своє, а не успадковане (#1265).

    CI-раннер приносить у процес власні змінні, і серед них може бути
    експортована функція (`BASH_FUNC_printf%%`, `BASH_FUNC_cat%%`), яка затіняє
    команду ВСЕРЕДИНІ скрипта: він тоді друкує порожньо й виходить із кодом 2 —
    при незмінному файлі. Саме так фікстура #1260 і почервоніла в CI, хоч
    локально була зелена.

    `scripts/tests/test_mutate.py` задає оточення так само — свіжим словником, а
    не `os.environ`.
    """
    base = {"PATH": "/usr/bin:/bin", "HOME": str(REPO_ROOT), "LC_ALL": "C"}
    base.update(overrides)
    return base


def density_for(width_px: int, target_dp: int) -> int:
    """The script's own pure answer — not a second implementation of it."""
    result = subprocess.run(
        ["bash", str(WIDE_WINDOW), "--density-for", str(width_px), str(target_dp)],
        check=False,
        capture_output=True,
        text=True,
        env=tool_environment(),
    )
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    return int(result.stdout.strip())


def achieved_dp(width_px: int, density: int) -> int:
    return 160 * width_px // density


def dp_text(width_px: int, density: int) -> str:
    """The width the script prints, to one decimal: `1600 * px / dpi`, floored."""
    tenths = 1600 * width_px // density
    return f"{tenths // 10}.{tenths % 10}"


class DensityArithmeticTest(unittest.TestCase):
    """`density_for(width_px, target_dp)` — the pure part of the script.

    No adb, no device: `--density-for` runs exactly the function the device path
    runs, so the arithmetic is answered by a test rather than by a phone
    somebody has to hold.
    """

    def test_every_derived_density_reaches_its_target(self):
        targets = (
            411,
            width_line("ExpandedMinWidthDp"),
            width_line("PlayerPaneMinWidthDp"),
            1024,
            1280,
        )
        for width_px in (720, 1080, 1440, 2400, 3168):
            for target_dp in targets:
                with self.subTest(width_px=width_px, target_dp=target_dp):
                    density = density_for(width_px, target_dp)
                    self.assertGreaterEqual(
                        achieved_dp(width_px, density),
                        target_dp,
                        f"{width_px} px at {density} dpi is under {target_dp} dp",
                    )
                    # The floor is what makes the guarantee hold: rounding the
                    # density up would land below the target.
                    self.assertLessEqual(density * target_dp, 160 * width_px)

    def test_the_old_magic_density_stops_below_the_player_pane_line(self):
        """The checklist said `wm density 280`; on a 1440 px panel that is 822 dp
        — over the wide line, under the player-pane line it was cited for."""
        wide_dp = width_line("ExpandedMinWidthDp")
        player_pane_dp = width_line("PlayerPaneMinWidthDp")

        self.assertEqual(822, achieved_dp(1440, 280))
        self.assertGreaterEqual(achieved_dp(1440, 280), wide_dp)
        self.assertLess(achieved_dp(1440, 280), player_pane_dp)
        self.assertGreaterEqual(
            achieved_dp(1440, density_for(1440, player_pane_dp)), player_pane_dp
        )

    def test_holds_the_width_lines_the_app_draws(self):
        for name in ("ExpandedMinWidthDp", "PlayerPaneMinWidthDp"):
            with self.subTest(line=name):
                target_dp = width_line(name)
                density = density_for(1440, target_dp)
                self.assertGreaterEqual(achieved_dp(1440, density), target_dp)

    def test_the_player_pane_line_is_the_second_line(self):
        """What makes the recipe's target interesting is that it is the app's
        SECOND width line, above the wide one. If that order ever inverted, the
        document and this guard would be talking about a line the app lost."""
        wide_dp = width_line("ExpandedMinWidthDp")
        player_pane_dp = width_line("PlayerPaneMinWidthDp")

        self.assertGreater(player_pane_dp, wide_dp)

    def test_an_exact_division_stays_exact(self):
        # An arbitrary pair, chosen because it divides exactly: 1440 × 160 / 288
        # is 800 dpi, and the floor must not cost a dp here.
        density = density_for(1440, 288)
        self.assertEqual(800, density)
        self.assertEqual(288, achieved_dp(1440, density))

    def test_rejects_a_target_that_is_not_a_positive_whole_number(self):
        for arguments in (["0"], ["-1"], ["eight"], ["--density-for", "1440", "0"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    ["bash", str(WIDE_WINDOW), *arguments],
                    check=False,
                    capture_output=True,
                    text=True,
                    env=tool_environment(),
                )
                self.assertEqual(2, result.returncode)
                self.assertIn("positive", result.stderr)


class DeviceRunTest(unittest.TestCase):
    """The device path, driven by a stub `adb`.

    These prove that the script reads the device's own resolution, applies the
    density the pure function derived from it and says what it did. They do not
    prove anything about a real phone — that needs a real phone.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.log = self.root / "adb.log"
        self.fixtures = self.root / "fixtures"
        self.fixtures.mkdir()
        self.state = self.root / "state"
        self.state.mkdir()
        self.adb = self.root / "adb"
        self.adb.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "$FAKE_ADB_LOG"\n'
            'args=("$@")\n'
            'if [[ ${args[0]:-} == -s ]]; then args=("${args[@]:2}"); fi\n'
            'case "${args[*]}" in\n'
            "  devices) cat \"$FAKE_ADB_FIXTURES/devices\" ;;\n"
            "  'shell wm size') cat \"$FAKE_ADB_FIXTURES/size\" ;;\n"
            "  'shell wm density')\n"
            "    printf 'Physical density: %s\\n' \"$(cat \"$FAKE_ADB_FIXTURES/density\")\"\n"
            "    if [[ -f \"$FAKE_ADB_STATE/override\" ]]; then\n"
            "      printf 'Override density: %s\\n' \"$(cat \"$FAKE_ADB_STATE/override\")\"\n"
            "    fi\n"
            "    ;;\n"
            "  'shell wm density '*)\n"
            "    requested=${args[3]}\n"
            "    if [[ -f \"$FAKE_ADB_FIXTURES/applied\" ]]; then\n"
            "      applied=$(cat \"$FAKE_ADB_FIXTURES/applied\")\n"
            "    else\n"
            "      applied=$requested\n"
            "    fi\n"
            "    printf '%s' \"$applied\" > \"$FAKE_ADB_STATE/override\"\n"
            "    printf 'Physical density: %s\\n' \"$(cat \"$FAKE_ADB_FIXTURES/density\")\"\n"
            "    printf 'Override density: %s\\n' \"$applied\"\n"
            "    ;;\n"
            "  *) printf 'unexpected adb call: %s\\n' \"$*\" >&2; exit 2 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IXUSR)
        self.devices("SERIAL1\tdevice\n")
        self.write_fixture("size", "Physical size: 1440x3168\n")
        self.write_fixture("density", "560\n")

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_fixture(self, name: str, content: str) -> None:
        (self.fixtures / name).write_text(content, encoding="utf-8")

    def devices(self, content: str) -> None:
        self.write_fixture("devices", "List of devices attached\n" + content)

    def environment(self, adb: str | None = None) -> dict:
        """Оточення підставного adb — своє, а не успадковане (#1265).

        CI-раннер приносить у процес власні змінні, і серед них може бути
        експортована функція (`BASH_FUNC_cat%%`, `BASH_FUNC_printf%%`), яка
        затіняє команду ВСЕРЕДИНІ підставного adb: він тоді не віддає фікстуру
        й виходить із кодом 2 — при незмінних файлах фікстури. Саме так фікстура
        #1260 і почервоніла в CI, хоч локально була зелена.

        `scripts/tests/test_mutate.py` задає оточення так само — свіжим
        словником, а не `os.environ`. Імена тут — лише свої, з префіксом
        `FAKE_ADB_`: `ADB_LOG` міг би прочитати й справжній adb, а `SLUKHAYKA_ADB`
        — це вже вхід самого `scripts/wide-window.sh`.
        """
        return tool_environment(
            SLUKHAYKA_ADB=adb if adb is not None else str(self.adb),
            FAKE_ADB_LOG=str(self.log),
            FAKE_ADB_FIXTURES=str(self.fixtures),
            FAKE_ADB_STATE=str(self.state),
        )

    def run_script(self, *arguments: str, adb: str | None = None):
        return subprocess.run(
            ["bash", str(WIDE_WINDOW), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=self.environment(adb),
        )

    def invocations(self) -> list[str]:
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()

    def applied_density(self) -> int:
        """The density the stub was told to set, out of the invocation log."""
        for line in self.invocations():
            match = re.search(r"shell wm density (\d+)$", line)
            if match:
                return int(match.group(1))
        self.fail(f"no density was applied; adb calls: {self.invocations()}")

    def test_derives_the_density_from_the_device_and_reports_the_width(self):
        target_dp = width_line("PlayerPaneMinWidthDp")

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        density = self.applied_density()
        self.assertEqual(density_for(1440, target_dp), density)
        self.assertIn(
            f"1440 px / {density} dpi ≈ {dp_text(1440, density)} dp", result.stdout
        )
        self.assertGreaterEqual(achieved_dp(1440, density), target_dp)
        self.assertIn("adb -s SERIAL1 shell wm density reset", result.stdout)

    def test_an_override_size_is_the_resolution_that_counts(self):
        self.write_fixture("size", "Physical size: 1440x3168\nOverride size: 2400x3168\n")
        target_dp = width_line("PlayerPaneMinWidthDp")

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(density_for(2400, target_dp), self.applied_density())

    def test_the_narrow_side_is_the_width_of_a_portrait_window(self):
        self.write_fixture("size", "Physical size: 3168x1440\n")
        target_dp = width_line("PlayerPaneMinWidthDp")

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(density_for(1440, target_dp), self.applied_density())

    def test_a_density_the_device_replaced_is_reported_as_missing_the_target(self):
        self.write_fixture("applied", "400\n")
        target_dp = width_line("PlayerPaneMinWidthDp")

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"below {target_dp} dp", result.stderr)
        self.assertIn(f"1440 px / 400 dpi ≈ {dp_text(1440, 400)} dp", result.stdout)

    def test_a_density_already_in_place_is_not_applied_again(self):
        target_dp = width_line("PlayerPaneMinWidthDp")
        density = density_for(1440, target_dp)
        (self.state / "override").write_text(str(density), encoding="utf-8")

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("already the device's override", result.stdout)
        self.assertNotIn(f"wm density {density}\n", "\n".join(self.invocations()) + "\n")
        self.assertIn("adb -s SERIAL1 shell wm density reset", result.stdout)

    def test_the_device_own_density_can_already_be_wide_enough(self):
        # 1440 px at the fixture's physical 560 dpi is 411 dp: asking for that
        # needs no override, and the script must say so instead of applying one.
        target_dp = achieved_dp(1440, 560)

        result = self.run_script(str(target_dp))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"already gives at least {target_dp} dp", result.stdout)
        self.assertIn("-s SERIAL1 shell wm size", self.invocations())
        applied = [
            line for line in self.invocations() if re.search(r"wm density \d+$", line)
        ]
        self.assertEqual([], applied, f"no density was to be applied: {applied}")
        self.assertIn("nothing to reset", result.stdout)

    def test_no_device_names_wm_size_instead_of_failing_silently(self):
        self.devices("")

        result = self.run_script("840")

        self.assertEqual(3, result.returncode)
        self.assertIn("adb shell wm size", result.stderr)
        self.assertIn("--density-for <width-px> 840", result.stderr)
        self.assertEqual(["devices"], self.invocations())

    def test_an_unauthorized_device_is_not_used(self):
        self.devices("SERIAL1\tunauthorized\n")

        result = self.run_script("840")

        self.assertEqual(3, result.returncode)
        self.assertIn("unauthorized", result.stderr)
        self.assertEqual(["devices"], self.invocations())

    def test_two_devices_without_a_serial_are_refused(self):
        self.devices("SERIAL1\tdevice\nSECOND\tdevice\n")

        result = self.run_script("840")

        self.assertEqual(3, result.returncode)
        self.assertIn("SERIAL1,SECOND", result.stderr)
        self.assertIn("--serial", result.stderr)
        self.assertEqual(["devices"], self.invocations())

    def test_serial_picks_one_of_two_devices(self):
        self.devices("SERIAL1\tdevice\nSECOND\tdevice\n")
        target_dp = width_line("PlayerPaneMinWidthDp")

        result = self.run_script(str(target_dp), "--serial", "SECOND")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s SECOND shell wm size", self.invocations())
        self.assertEqual(density_for(1440, target_dp), self.applied_density())
        self.assertNotIn("-s SERIAL1 shell wm size", self.invocations())

    def test_a_missing_adb_is_reported_with_the_device_free_route(self):
        result = self.run_script("840", adb=str(self.root / "no-such-adb"))

        self.assertEqual(3, result.returncode)
        self.assertIn("adb shell wm size", result.stderr)
        self.assertIn("--density-for <width-px> 840", result.stderr)


if __name__ == "__main__":
    unittest.main()
