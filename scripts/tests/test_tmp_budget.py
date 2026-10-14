"""Бюджет тимчасових дерев (#1271): місце перевіряється ДО копіювання.

Головне тут — не «функція повертає число», а те, що механізм **відмовляє
голосно**, коли місця немає, і робить це **перед** копіюванням. Тому кожна
відмова піниться разом із числами: «скільки треба» і «скільки є» — це те, за чим
оператор вирішує, що робити, і без них відмова неотличима від зламаного гейта.

Друга половина — застарілі дерева. За минулу ціль саме вони лишалися після
`EDQUOT` і добивали наступний прогін, тож «прибрати» мусить бути перевірено на
всіх чотирьох станах теки: мертвий власник (прибираємо), живий власник (не
чіпаємо), свіжа тека без маркера (не чіпаємо — це сусідній прогін за мілісекунду
до запису маркера) і стара тека без маркера (прибираємо).

Третя — маленький tmpfs у RAM: відмова, а не тихе перенесення. Мовчазне
перенаправлення не приймається, тож кожне перенесення мусить бути назване вголос,
і це теж піниться.
"""

import contextlib
import io
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
import sys

sys.path.insert(0, str(REPO_ROOT / "scripts"))

import tmp_budget as tb  # noqa: E402

# Таблиця монтування як у Linux: tmpfs на /tmp і диск на /home.
TABLE = (
    "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\n"
    "tmpfs /tmp tmpfs rw,nosuid,nodev,size=8121980k 0 0\n"
    "/dev/mapper/root /home btrfs rw,relatime 0 0\n"
    "/dev/mapper/root / btrfs rw,relatime 0 0\n"
    "tmpfs /mnt/with\\040space tmpfs rw,size=1024k 0 0\n"
)


class MountTest(unittest.TestCase):
    """Тип ФС береться з `mounts`, і найдовший збіг — це те, що бачить ядро."""

    def test_the_longest_mount_wins(self):
        self.assertEqual(("/tmp", "tmpfs"), tb.mount_of(Path("/tmp/x"), TABLE))
        self.assertEqual(("/home", "btrfs"), tb.mount_of(Path("/home/a/b"), TABLE))
        self.assertEqual(("/", "btrfs"), tb.mount_of(Path("/etc/hosts"), TABLE))

    def test_an_escaped_space_in_a_mount_point_is_decoded(self):
        self.assertEqual(("/mnt/with space", "tmpfs"),
                         tb.mount_of(Path("/mnt/with space/x"), TABLE))

    def test_ram_backed_is_tmpfs_and_ramfs_only(self):
        self.assertTrue(tb.ram_backed(Path("/tmp/x"), TABLE))
        self.assertFalse(tb.ram_backed(Path("/home/x"), TABLE))

    def test_without_a_mount_table_a_filesystem_is_not_called_ram(self):
        """Сказати «це tmpfs» без таблиці не можна; вгадувати тут — відмовляти навмання."""
        self.assertFalse(tb.ram_backed(Path("/tmp/x"), ""))


class CopySetTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def test_the_measure_is_the_copy_not_the_tree(self):
        """Копіюється лише назване піддерево: решта — симлінки, і вони не байти."""
        (self.root / "scripts").mkdir()
        (self.root / "scripts" / "tool.py").write_text("x" * 100, encoding="utf-8")
        (self.root / "app").mkdir()
        (self.root / "app" / "huge.bin").write_bytes(b"y" * 10000)

        self.assertEqual(100, tb.tree_bytes(self.root / "scripts"))
        self.assertEqual(10100, tb.tree_bytes(self.root))

    def test_ignored_names_do_not_count(self):
        (self.root / "scripts" / "__pycache__").mkdir(parents=True)
        (self.root / "scripts" / "__pycache__" / "tool.pyc").write_bytes(b"z" * 5000)
        (self.root / "scripts" / "stale.pyc").write_bytes(b"z" * 5000)
        (self.root / "scripts" / "tool.py").write_text("x" * 100, encoding="utf-8")

        self.assertEqual(
            100,
            tb.tree_bytes(self.root / "scripts", ("__pycache__", "*.pyc")),
        )

    def test_a_symlinked_directory_is_counted_like_copytree_copies_it(self):
        """Міра й копіювання не мають права розійтися: на мірі стоїть відмова.

        `copytree` без `symlinks=True` у симлінк-ТЕКУ заходить і копіює вміст, а
        міра, яка цього не робить, нарахувала б 10 Б там, де копіювання пише
        100 010 Б (огляд #1275) — тобто охороняла б не той обсяг.
        """
        (self.root / "scripts").mkdir()
        (self.root / "app").mkdir()
        (self.root / "app" / "payload.bin").write_bytes(b"y" * 100000)
        (self.root / "scripts" / "app").symlink_to(self.root / "app",
                                                   target_is_directory=True)

        measured = tb.tree_bytes(self.root / "scripts")
        copied = self.root / "copy"
        shutil.copytree(self.root / "scripts", copied)
        written = sum(entry.stat().st_size
                      for entry in copied.rglob("*") if entry.is_file())

        self.assertEqual(100000, measured)
        self.assertEqual(written, measured)

    def test_a_twice_linked_directory_is_counted_twice(self):
        """Повтор — не цикл: `copytree` копіює обидва симлінки, тож і міра рахує двічі.

        Глобальна множина відвіданих inode глушила б другий симлінк і давала
        недооцінку вдвічі (огляд #1275 міряв 100 500 Б там, де копіювання пише
        201 000 Б) — тобто помилку в небезпечний бік. Ланцюг предків ловить
        лише справжній цикл.
        """
        (self.root / "scripts").mkdir()
        (self.root / "shared").mkdir()
        (self.root / "shared" / "payload.bin").write_bytes(b"y" * 100000)
        for name in ("first", "second"):
            (self.root / "scripts" / name).symlink_to(self.root / "shared",
                                                      target_is_directory=True)

        measured = tb.tree_bytes(self.root / "scripts")
        copied = self.root / "copy"
        shutil.copytree(self.root / "scripts", copied)
        written = sum(entry.stat().st_size
                      for entry in copied.rglob("*") if entry.is_file())

        self.assertEqual(200000, measured)
        self.assertEqual(written, measured)

    def test_a_symlink_loop_does_not_hang_or_double_count(self):
        """Справжній цикл спиняє ланцюг предків: `copytree` на ньому падає, міра — ні."""
        (self.root / "scripts").mkdir()
        (self.root / "scripts" / "tool.py").write_text("x" * 100, encoding="utf-8")
        (self.root / "scripts" / "loop").symlink_to(self.root / "scripts",
                                                    target_is_directory=True)

        self.assertEqual(100, tb.tree_bytes(self.root / "scripts"))

    def test_the_requirement_is_the_copy_times_the_headroom_plus_the_floor(self):
        self.assertEqual(tb.requirement(0), tb.SCRATCH_FLOOR_BYTES)
        self.assertEqual(
            2 * tb.COPY_HEADROOM + tb.SCRATCH_FLOOR_BYTES, tb.requirement(2)
        )


class RoomTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def ram_table(self, mount_point: Path | None = None) -> str:
        """Таблиця, у якій тимчасова тека — це точка монтування tmpfs.

        Сама тека, а не `/tmp`: у CI тимчасова тека лежить деінде, і таблиця
        мусить описувати її, а не машину, на якій тест випадково біжить.
        """
        return "tmpfs %s tmpfs rw,size=64M 0 0\n" % (mount_point or self.root)

    def test_enough_room_passes_quietly(self):
        tb.ensure_room(self.root, 1024, free=tb.requirement(1024) + 1)

    def test_no_room_is_refused_with_both_numbers(self):
        """Відмова без чисел — це «щось зламалося», а не причина."""
        with self.assertRaises(tb.BudgetError) as caught:
            tb.ensure_room(self.root, 2 * 1048576, free=1024)

        message = str(caught.exception)
        self.assertIn(tb.mib(tb.requirement(2 * 1048576)), message)
        self.assertIn("2.0 MiB", message)
        self.assertIn(tb.mib(1024), message)
        self.assertIn(str(self.root), message)
        self.assertIn(tb.RELOCATE_VARIABLE, message)

    def test_a_small_ram_filesystem_is_refused_even_with_room(self):
        """Місця може й вистачати — але RAM-ФС на 64 MiB усе одно не місце для дерев."""
        with self.assertRaises(tb.BudgetError) as caught:
            tb.guard(self.root, 1024, free=10 ** 9, table=self.ram_table(),
                     total=64 * 1048576)

        message = str(caught.exception)
        self.assertIn("tmpfs", message)
        self.assertIn("64.0 MiB", message)
        self.assertIn(tb.mib(tb.RAM_TMPFS_MIN_BYTES), message)
        self.assertIn(tb.RELOCATE_VARIABLE, message)

    def test_a_large_ram_filesystem_passes(self):
        tb.guard(self.root, 1024, free=10 ** 9, table=self.ram_table(),
                 total=tb.RAM_TMPFS_MIN_BYTES)

    def test_a_disk_filesystem_is_not_judged_by_size(self):
        """2 GiB на диску — це просто 2 GiB; відмовляти тут нема чого."""
        disk = "tmpfs /tmp tmpfs rw 0 0\n/dev/sda1 /home ext4 rw 0 0\n"
        tb.guard(Path("/home/x"), 1024, free=10 ** 9, table=disk, total=64 * 1048576)


class SweepTest(unittest.TestCase):
    """Чотири стани теки — і жодного «приберемо на всяк випадок»."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def tree(self, name: str, *, pid: int | None = None, payload: int = 0) -> Path:
        path = self.root / name
        path.mkdir()
        if pid is not None:
            (path / tb.OWNER_MARKER).write_text("%d\n" % pid, encoding="utf-8")
        (path / "payload").write_bytes(b"p" * payload)
        return path

    def test_a_dead_owner_is_reclaimed(self):
        dead = self.tree("slukhayka-field-dead", pid=4242, payload=700)

        freed = tb.sweep_stale(self.root, alive=lambda pid: False)

        self.assertEqual([(dead, 700 + len("4242\n"))], freed)
        self.assertFalse(dead.exists())

    def test_a_live_owner_is_left_alone(self):
        live = self.tree("slukhayka-field-live", pid=os.getpid())

        freed = tb.sweep_stale(self.root)

        self.assertEqual([], freed)
        self.assertTrue(live.exists())

    def test_a_fresh_tree_without_a_marker_is_left_alone(self):
        """Сусідній прогін за мілісекунду до запису маркера — не сміття."""
        fresh = self.tree("slukhayka-field-fresh")

        freed = tb.sweep_stale(self.root, grace=900)

        self.assertEqual([], freed)
        self.assertTrue(fresh.exists())

    def test_an_old_tree_without_a_marker_is_reclaimed(self):
        """Так виглядають дерева, що лишилися від падіння до цієї правки."""
        old = self.tree("slukhayka-shape-old", payload=300)
        os.utime(old, (1, 1))

        freed = tb.sweep_stale(self.root, grace=900)

        self.assertEqual([(old, 300)], freed)
        self.assertFalse(old.exists())

    def test_a_prefix_nobody_sweeps_is_never_touched(self):
        """`slukhayka-tests.*` — тека прогону ПОВНОГО набору, і вона не наш суд.

        Ця межа названа в коді (`tmp_budget.SCRATCH_PREFIXES`) і має причину, а
        не «так історично склалося»: `test-all.sh` не пише маркера власника, тож
        прибирання за часом могло б знести теку прогону, довшого за пільгу, — а
        повний набір і є найдовшим прогоном у дереві. Узяти його сюди можна лише
        разом із маркером у самому `test-all.sh`.
        """
        foreign = self.tree("slukhayka-tests-x", pid=4242)
        os.utime(foreign, (1, 1))

        freed = tb.sweep_stale(self.root, alive=lambda pid: False)

        self.assertEqual([], freed)
        self.assertTrue(foreign.exists())

    def test_a_tree_that_cannot_be_removed_is_named_not_fatal(self):
        stuck = self.tree("slukhayka-field-stuck", pid=4242)

        def refuse(path):
            raise OSError("device busy")

        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            freed = tb.sweep_stale(self.root, alive=lambda pid: False, remove=refuse)

        self.assertEqual([], freed)
        self.assertIn("прибрати не вдалося", out.getvalue())
        self.assertTrue(stuck.exists())
        self.assertIn(str(stuck), out.getvalue())

    def test_a_marker_with_garbage_is_treated_as_no_marker(self):
        """Маркер без числа — не власник; таку теку судять за часом, як стару."""
        broken = self.tree("slukhayka-field-broken")
        (broken / tb.OWNER_MARKER).write_text("не число\n", encoding="utf-8")
        os.utime(broken, (1, 1))

        freed = tb.sweep_stale(self.root, grace=900)

        self.assertEqual([broken], [path for path, _size in freed])


class ScratchTest(unittest.TestCase):
    """Тека прогону скрипта-обгортки: прибиральник на СТАРТІ наступного прогону.

    Прибирання цієї теки живе в EXIT-пастці скрипта, а SIGKILL пастку не
    виконує фізично (#1300) — теку не прибирає НІХТО, і вона лишається назавжди.
    Тому прибирання переїхало на старт наступного прогону, і тут піняться всі
    три стани, від яких залежить, чи тека зникне: мертвий власник (зникає
    одразу, без пільги), живий власник (не чіпають НІКОЛИ — інакше прибиральник
    убивав би живі прогони) і стара тека без маркера (зникає за часом — рівно
    такі 95 тек `slukhayka-mutation.*` і 65 `slukhayka-changed-tests.*` лежали в
    `/tmp` на момент #1300).

    Межа, названа тут, а не в звіті: маркер пише той, хто в теці ПРАЦЮВАТИМЕ, а
    не той, хто її створив. `bash` кличе короткоживучий
    `python3 tmp_budget.py claim`, і маркер на хелпері зробив би живу теку
    «застарілою» за мілісекунди після створення.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def scratch(self, name: str, *, pid: int | None = None, payload: int = 0) -> Path:
        path = self.root / name
        path.mkdir()
        if pid is not None:
            (path / tb.OWNER_MARKER).write_text("%d\n" % pid, encoding="utf-8")
        (path / "payload").write_bytes(b"p" * payload)
        return path

    def test_an_old_scratch_without_a_marker_is_reaped_for_both_prefixes(self):
        """Так виглядають теки, лишені SIGKILL'ом до цієї правки: маркера немає."""
        stale = [self.scratch(prefix + "old", payload=90)
                 for prefix in ("slukhayka-mutation.", "slukhayka-changed-tests.")]
        for path in stale:
            os.utime(path, (1, 1))

        freed = []
        for prefix in ("slukhayka-mutation.", "slukhayka-changed-tests."):
            freed += tb.sweep_scratch(prefix, base=self.root)

        self.assertEqual(sorted(stale), sorted(path for path, size in freed if size == 90))
        self.assertEqual([], [path for path in stale if path.exists()])

    def test_the_reaping_is_announced_with_the_bytes(self):
        """Прибирання голосне: інакше «зникло» не відрізнити від «не було»."""
        old = self.scratch("slukhayka-mutation.old", payload=512)
        os.utime(old, (1, 1))

        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            tb.sweep_scratch("slukhayka-mutation.", base=self.root)

        self.assertIn(str(old), out.getvalue())
        self.assertIn(tb.mib(512), out.getvalue())

    def test_a_sibling_prefix_is_not_our_business(self):
        """`mutate.sh` не прибирає теку `test-changed.sh` — і навпаки."""
        sibling = self.scratch("slukhayka-changed-tests.sibling")
        os.utime(sibling, (1, 1))

        freed = tb.sweep_scratch("slukhayka-mutation.", base=self.root)

        self.assertEqual([], freed)
        self.assertTrue(sibling.exists())

    def test_the_marker_names_the_wrapper_and_not_the_helper(self):
        """Маркер називає того, хто в теці працюватиме, — і мертвий знімається.

        Власник тут — справжній живий процес (не цей тест), і вбивають його
        справжнім SIGKILL: саме так виглядає прогін, який знесла ескалація
        `build-slot-guard.py`. Живий власник захищає теку, мертвий знімає її
        одразу — без пільги за часом, бо час тут нічого не значить.
        """
        holder = subprocess.Popen(["sleep", "30"])
        self.addCleanup(holder.wait)
        self.addCleanup(holder.kill)

        scratch = tb.claim_scratch("slukhayka-mutation.", base=self.root, pid=holder.pid)

        self.assertEqual(str(holder.pid), (scratch / tb.OWNER_MARKER).read_text().strip())
        self.assertEqual([], tb.sweep_scratch("slukhayka-mutation.", base=self.root))
        self.assertTrue(scratch.exists())

        holder.kill()
        holder.wait()

        freed = tb.sweep_scratch("slukhayka-mutation.", base=self.root)

        self.assertEqual([scratch], [path for path, _size in freed])
        self.assertFalse(scratch.exists())

    def test_a_fresh_scratch_without_a_marker_is_left_alone(self):
        """Сусідній прогін за мілісекунду до запису маркера — не сміття."""
        fresh = self.scratch("slukhayka-mutation.fresh")

        freed = tb.sweep_scratch("slukhayka-mutation.", base=self.root, grace=900)

        self.assertEqual([], freed)
        self.assertTrue(fresh.exists())

    def test_the_default_sweep_reaches_the_wrapper_scratch_too(self):
        """Префікси в спільному списку — те, чому тека зникає і без свого скрипта.

        Список тут буквально той, що в скриптах (`--prefix`): `mktemp` у них
        більше не кличеться, і розходження між константою та скриптом мусить
        червонити саме цей рядок, а не лишати теку в `/tmp`.
        """
        stale = [self.scratch(prefix + "old") for prefix in tb.SCRATCH_PREFIXES]
        for path in stale:
            os.utime(path, (1, 1))

        freed = tb.sweep_stale(self.root, grace=900)

        self.assertEqual(sorted(stale), sorted(path for path, _size in freed))

    def test_the_prefixes_the_scripts_claim_are_the_reaped_ones(self):
        """Константа й скрипти мусять називати ОДНІ префікси — інакше тека тече.

        Це не «перевірка рядка»: префікс — це те, за чим прибиральник упізнає
        залишок. Розійшлися — і тека прогону лишається назавжди, рівно як до
        цієї правки, при зеленому наборі.
        """
        for script, prefix in (("mutate.sh", "slukhayka-mutation."),
                               ("test-changed.sh", "slukhayka-changed-tests.")):
            text = (REPO_ROOT / "scripts" / script).read_text(encoding="utf-8")
            self.assertIn("--prefix %s" % prefix, text)
            self.assertIn(prefix, tb.SCRATCH_PREFIXES)
        self.assertIn("slukhayka-tests.", tb.SWEPT_BY_NOBODY_PREFIXES)

    def test_claim_reaps_before_it_creates(self):
        """Спершу прибрати, тоді створити — інакше місце не повертається."""
        old = self.scratch("slukhayka-mutation.old")
        os.utime(old, (1, 1))

        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            fresh = tb.claim_scratch("slukhayka-mutation.", base=self.root, pid=os.getpid())

        self.assertFalse(old.exists())
        self.assertTrue(fresh.is_dir())
        self.assertIn(str(old), out.getvalue())

    def test_a_missing_scratch_root_is_refused_with_the_path(self):
        missing = self.root / "nope"

        with self.assertRaises(tb.BudgetError) as caught:
            tb.claim_scratch("slukhayka-mutation.", base=missing)

        self.assertIn(str(missing), str(caught.exception))

    def test_the_entry_point_creates_a_marked_scratch_and_names_it(self):
        """Той самий вхід, яким користується `bash`, — і той самий маркер."""
        out = io.StringIO()
        with mock.patch.dict(os.environ, {"TMPDIR": str(self.root)}), \
                contextlib.redirect_stdout(out):
            code = tb.main(["claim", "--prefix", "slukhayka-mutation.", "--owner-pid", "4242"])

        self.assertEqual(0, code, out.getvalue())
        created = Path(out.getvalue().strip())
        self.assertTrue(created.is_dir())
        self.assertEqual("4242", (created / tb.OWNER_MARKER).read_text().strip())

    def test_the_entry_point_refuses_loudly_when_there_is_nowhere_to_create(self):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, {"TMPDIR": str(self.root / "nope")}), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tb.main(["claim", "--prefix", "slukhayka-mutation."])

        self.assertEqual(2, code)
        self.assertEqual("", out.getvalue())
        self.assertIn(str(self.root / "nope"), err.getvalue())


class TempRootTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def test_the_default_is_tmpdir(self):
        with mock.patch.dict(os.environ, {"TMPDIR": str(self.root)}):
            self.assertEqual(self.root, tb.temp_root())

    def test_an_explicit_move_is_announced(self):
        """Мовчазне перенаправлення не приймається: куди пішли дерева — названо."""
        target = self.root / "elsewhere"
        target.mkdir()

        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            chosen = tb.temp_root(self.root, relocate=str(target))

        self.assertEqual(target, chosen)
        self.assertIn(str(target), out.getvalue())
        self.assertIn(tb.RELOCATE_VARIABLE, out.getvalue())
        self.assertIn(str(self.root), out.getvalue())

    def test_a_move_to_a_missing_directory_is_refused(self):
        """Друкарська помилка в адресі мусить бути відмовою, а не тихою /tmp."""
        with self.assertRaises(tb.BudgetError) as caught:
            tb.temp_root(self.root, relocate=str(self.root / "nope"))

        self.assertIn("не тека", str(caught.exception))

    def test_a_missing_tmpdir_is_refused_not_silently_replaced(self):
        with mock.patch.dict(os.environ, {"TMPDIR": str(self.root / "gone")}):
            with self.assertRaises(tb.BudgetError) as caught:
                tb.prepare(base=None, relocate="")

        self.assertIn("немає", str(caught.exception))

    def test_prepare_reclaims_before_it_measures(self):
        """Спершу прибрати, потім міряти — інакше механізм відмовляє там, де сам звільнив."""
        old = self.root / "slukhayka-field-old"
        old.mkdir()
        (old / "payload").write_bytes(b"p" * 10)
        os.utime(old, (1, 1))
        seen = []

        def recorder(directory, copy_bytes):
            seen.append((directory, copy_bytes, old.exists()))

        out = io.StringIO()
        with mock.patch.object(tb, "guard", recorder), contextlib.redirect_stderr(out):
            base = tb.prepare(base=self.root, copy_bytes=1024, relocate="")

        self.assertEqual(self.root, base)
        # Вимір місця бачив теку вже прибраною — інакше повернуті нею байти не
        # зарахувалися б, і механізм відмовив би там, де сам щойно звільнив.
        self.assertEqual([(self.root, 1024, False)], seen)
        self.assertFalse(old.exists())
        self.assertIn("прибрано застаріле тимчасове дерево", out.getvalue())
        self.assertIn(str(old), out.getvalue())

    def test_claim_marks_the_tree_with_its_owner(self):
        created = tb.claim(self.root, "slukhayka-field-")

        self.assertTrue(created.name.startswith("slukhayka-field-"))
        self.assertEqual(os.getpid(), tb.owner_pid(created))
        self.assertFalse(tb.stale(created))

    def test_a_marker_that_cannot_be_written_leaves_no_carrier(self):
        """Маркер — частина «завести»: тека без нього для сусіда не власність, а сміття.

        `ENOSPC`/`EDQUOT`/`EROFS` трапляються саме на записі маркера (огляд #1275),
        і тека-носій без маркера — це рівно те, що наступний прогін прибирає за
        часом. Хелпер, який її лишає, сам робив би те, проти чого заведений.
        """
        with mock.patch.object(tb, "mark", side_effect=OSError("no space left")):
            with self.assertRaises(OSError):
                tb.claim(self.root, "slukhayka-field-")

        self.assertEqual([], sorted(path.name for path in self.root.iterdir()),
                         "невдалий маркер не має лишати теку-носій без власника")


class ProcessTest(unittest.TestCase):
    def test_this_process_is_alive_and_an_out_of_range_pid_is_not(self):
        self.assertTrue(tb.process_alive(os.getpid()))

        try:
            limit = int(Path("/proc/sys/kernel/pid_max").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.skipTest("no /proc/sys/kernel/pid_max")
        if tb.process_alive(limit):
            self.skipTest("pid %d is in use" % limit)

        self.assertFalse(tb.process_alive(limit))


if __name__ == "__main__":
    unittest.main()
