import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WIDE_WINDOW = REPO_ROOT / "scripts" / "wide-window.sh"


class DensityArithmeticTest(unittest.TestCase):
    """`density_for(width_px, target_dp)` — the pure part of the script.

    No adb, no device: `--density-for` runs exactly the function the device
    path runs, so the arithmetic is answered by a test rather than by a phone
    somebody has to hold.
    """

    def density_for(self, width_px: int, target_dp: int) -> int:
        result = subprocess.run(
            ["bash", str(WIDE_WINDOW), "--density-for", str(width_px), str(target_dp)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return int(result.stdout.strip())

    @staticmethod
    def achieved_dp(width_px: int, density: int) -> int:
        return 160 * width_px // density

    def test_every_derived_density_reaches_its_target(self):
        for width_px in (720, 1080, 1440, 2400, 3168):
            for target_dp in (411, 600, 840, 1024, 1280):
                with self.subTest(width_px=width_px, target_dp=target_dp):
                    density = self.density_for(width_px, target_dp)
                    self.assertGreaterEqual(
                        self.achieved_dp(width_px, density),
                        target_dp,
                        f"{width_px} px at {density} dpi is under {target_dp} dp",
                    )
                    # The floor is what makes the guarantee hold: rounding the
                    # density up would land below the target.
                    self.assertLessEqual(density * target_dp, 160 * width_px)

    def test_the_old_magic_density_stops_below_the_840_line(self):
        """The checklist used to say `wm density 280`; on a 1440 px panel that
        is 822 dp — under the 840 dp player-pane line it was cited for."""
        self.assertEqual(822, self.achieved_dp(1440, 280))
        self.assertLess(self.achieved_dp(1440, 280), 840)
        self.assertEqual(274, self.density_for(1440, 840))
        self.assertGreaterEqual(self.achieved_dp(1440, 274), 840)

    def test_holds_the_shared_width_lines(self):
        # 600 dp — the wide layout; 840 dp — the #1205 player pane.
        self.assertEqual(384, self.density_for(1440, 600))
        self.assertEqual(600, self.achieved_dp(1440, self.density_for(1440, 600)))
        self.assertEqual(274, self.density_for(1440, 840))

    def test_an_exact_division_stays_exact(self):
        density = self.density_for(1440, 288)
        self.assertEqual(800, density)
        self.assertEqual(288, self.achieved_dp(1440, density))

    def test_rejects_a_target_that_is_not_a_positive_whole_number(self):
        for arguments in (["0"], ["-1"], ["eight"], ["--density-for", "1440", "0"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    ["bash", str(WIDE_WINDOW), *arguments],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(2, result.returncode)
                self.assertIn("positive", result.stderr)


class DeviceRunTest(unittest.TestCase):
    """The device path, driven by a stub `adb`.

    These prove that the script reads the device's own resolution, applies the
    density derived from it and says what it did. They do not prove anything
    about a real phone — that needs a real phone.
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
            'printf \'%s\\n\' "$*" >> "$ADB_LOG"\n'
            'args=("$@")\n'
            'if [[ ${args[0]:-} == -s ]]; then args=("${args[@]:2}"); fi\n'
            'case "${args[*]}" in\n'
            "  devices) cat \"$ADB_FIXTURES/devices\" ;;\n"
            "  'shell wm size') cat \"$ADB_FIXTURES/size\" ;;\n"
            "  'shell wm density')\n"
            "    printf 'Physical density: %s\\n' \"$(cat \"$ADB_FIXTURES/density\")\"\n"
            "    if [[ -f \"$ADB_STATE/override\" ]]; then\n"
            "      printf 'Override density: %s\\n' \"$(cat \"$ADB_STATE/override\")\"\n"
            "    fi\n"
            "    ;;\n"
            "  'shell wm density '*)\n"
            "    requested=${args[3]}\n"
            "    if [[ -f \"$ADB_FIXTURES/applied\" ]]; then\n"
            "      applied=$(cat \"$ADB_FIXTURES/applied\")\n"
            "    else\n"
            "      applied=$requested\n"
            "    fi\n"
            "    printf '%s' \"$applied\" > \"$ADB_STATE/override\"\n"
            "    printf 'Physical density: %s\\n' \"$(cat \"$ADB_FIXTURES/density\")\"\n"
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

    def run_script(self, *arguments: str, adb: str | None = None):
        environment = os.environ.copy()
        environment.update(
            {
                "SLUKHAYKA_ADB": adb if adb is not None else str(self.adb),
                "ADB_LOG": str(self.log),
                "ADB_FIXTURES": str(self.fixtures),
                "ADB_STATE": str(self.state),
            }
        )
        return subprocess.run(
            ["bash", str(WIDE_WINDOW), *arguments],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def invocations(self) -> list[str]:
        if not self.log.exists():
            return []
        return self.log.read_text(encoding="utf-8").splitlines()

    def test_derives_the_density_from_the_device_and_reports_the_width(self):
        result = self.run_script("840")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s SERIAL1 shell wm density 274", self.invocations())
        self.assertIn("1440 px / 274 dpi ≈ 840.8 dp", result.stdout)
        self.assertIn("adb -s SERIAL1 shell wm density reset", result.stdout)

    def test_an_override_size_is_the_resolution_that_counts(self):
        self.write_fixture("size", "Physical size: 1440x3168\nOverride size: 2400x3168\n")

        result = self.run_script("840")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s SERIAL1 shell wm density 457", self.invocations())

    def test_the_narrow_side_is_the_width_of_a_portrait_window(self):
        self.write_fixture("size", "Physical size: 3168x1440\n")

        result = self.run_script("840")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s SERIAL1 shell wm density 274", self.invocations())

    def test_a_density_the_device_replaced_is_reported_as_missing_the_target(self):
        self.write_fixture("applied", "400\n")

        result = self.run_script("840")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("below 840 dp", result.stderr)
        self.assertIn("1440 px / 400 dpi ≈ 576.0 dp", result.stdout)

    def test_a_density_already_in_place_is_not_applied_again(self):
        (self.state / "override").write_text("274", encoding="utf-8")

        result = self.run_script("840")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("already the device's override", result.stdout)
        self.assertNotIn("-s SERIAL1 shell wm density 274", self.invocations())
        self.assertIn("adb -s SERIAL1 shell wm density reset", result.stdout)

    def test_the_device_own_density_can_already_be_wide_enough(self):
        result = self.run_script("411")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("already gives at least 411 dp", result.stdout)
        self.assertNotIn("-s SERIAL1 shell wm density 560", self.invocations())
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

        result = self.run_script("840", "--serial", "SECOND")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-s SECOND shell wm size", self.invocations())
        self.assertIn("-s SECOND shell wm density 274", self.invocations())
        self.assertNotIn("-s SERIAL1 shell wm size", self.invocations())

    def test_a_missing_adb_is_reported_with_the_device_free_route(self):
        result = self.run_script("840", adb=str(self.root / "no-such-adb"))

        self.assertEqual(3, result.returncode)
        self.assertIn("adb shell wm size", result.stderr)
        self.assertIn("--density-for <width-px> 840", result.stderr)


if __name__ == "__main__":
    unittest.main()
