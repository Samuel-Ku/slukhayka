#!/usr/bin/env python3
"""Тимчасове дерево мутацій: місце перевіряється ДО копіювання, а не після (#1271).

Механізми мутацій (`verdict_field_mutants.py`, `empty_shape_mutants.py`,
`guard_witnesses.py`) будують на кожен мутант копію дерева в тимчасовій теці. Коли
тимчасової ФС бракує, вони
не просто падають самі: вони заповнюють ФС, а її вичерпання валить **кожен**
запуск процесу в середовищі — `bash` не стартує навіть на `true`, `ripgrep` не
запускається, і робота виглядає як «зламалися інструменти», а не як вичерпана
квота. Саме так це вбило три сесії за одну ціль.

Тому правило тут одне: **краще відмовити, ніж убити середовище**. Перевірка
стоїть ПЕРЕД копіюванням, відмова голосна (код 2 у механізмів) і називає числа —
скільки треба й скільки є. Заповнити ФС і впасти на `ENOSPC` посередині — те, що
цей модуль прибирає: після такого падіння лишається недороблене дерево, воно
добиває наступний прогін, і так по колу.

Три речі, які модуль робить, крім самої перевірки:

 - **застарілі дерева** попередніх падінь прибираються на старті. Теку без
   маркера власника (`owner.pid`) вважають застарілою лише після пільгового часу
   (`STALE_GRACE_SECONDS`): свіжу теку міг створити сусідній прогін, який ще не
   встиг покласти маркер. Теку з живим PID не чіпають ніколи — це прогін у
   польоті. Прибирання голосне: називає теку й повернуті нею байти;
 - **маленький tmpfs у RAM** — окрема відмова, а не тихе перенаправлення.
   Вичерпати RAM-ФС означає покласти всю сесію, тож маленький tmpfs механізм не
   використовує взагалі: він називає розмір, потребу й те, куди вказати `TMPDIR`.
   Явне перенесення (`SLUKHAYKA_MUTANTS_TMPDIR`) дозволене, але воно голосне й
   не вимикає жодної перевірки — це не вимикач, а адреса;
 - **підлога на scratch свідків**: копія дерева — не єдина річ, яку пише прогін
   (свідки ведуть власні тимчасові файли), тож потреба рахується як копія ×
   запас + підлога, а не як «рівно копія».

Межі, названі прямо:

 - запас і підлога — це числа, а не вимірювання кожного свідка: `COPY_HEADROOM`
   покриває 4 воркери і те, що між перевіркою та записом місце встигне
   зменшитися, `SCRATCH_FLOOR_BYTES` — власний scratch свідків. Занижені вони
   можуть лише проґавити відмову, завищені — відмовити там, де місце є; друге
   обрано навмисно;
 - тип ФС читається з `/proc/mounts` (Linux). Якщо таблиці немає, ФС уважається
   не-RAM-овою, і працює лише перевірка місця — сказати «це tmpfs» без таблиці
   не можна, а вгадувати тут означало б відмовляти навмання;
 - вимір копії робить рівно те, що робить `copytree(symlinks=False)`: симлінк-файл
   рахується як його ціль, а в симлінк-ТЕКУ міра заходить так само, як
   копіювання. Це пінить тест (`test_a_symlinked_directory_is_counted_like_`
   `copytree_copies_it`), а не обіцянка: на вимірі стоїть уся відмова, і міра,
   яка розходиться з копіюванням, охороняла б не той обсяг (огляд #1275);
   цикл симлінків міра не розкручує — ланцюг предків ловить лише справжній цикл,
   а повтор (два симлінки на ту саму теку) рахується двічі, як і копіюється;
   `copytree` на циклі падає, і невідомість приходить від нього;
 - прибирання судить теку без маркера за ЧАСОМ життя (пільгові 15 хвилин — це
   5–10 прогонів механізму). Обидва механізми маркер пишуть — `Runner` у полях і
   `tree()` у порожній формі, — і це пінять тести: без маркера правило вбивало б
   їхні ж живі прогони, довші за пільгу. У порожній формі дерево базового прогону
   живе крізь усі прогони — базовий і мутантний на два модулі-свідки по 240 с
   таймауту, тобто 4 × 240 с = 960 с. Тож за часом судяться лише теки інших
   версій інструмента; живий прогін такої версії, що триває понад пільговий час,
   може втратити своє дерево й упасти голосно. Це обрано навмисно: тихо лишати
   сміття, яке добиває наступний прогін, гірше за гучне падіння одного прогону.

Вимикача немає: жодного `--skip`, жодної змінної, яка дозволяє. `TMPDIR` і
`SLUKHAYKA_MUTANTS_TMPDIR` лише називають місце — перевірки в ньому ті самі.
"""

from __future__ import annotations

import fnmatch
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

# Теки, які належать механізмам мутацій. Прибираються лише вони: тека іншого
# інструмента (`slukhayka-mutation.*` — scratch живого `mutate.sh`) може бути
# зайнята просто зараз, і видаляти її означало б зламати чужий прогін.
OWN_PREFIXES = ("slukhayka-field-", "slukhayka-shape-", "slukhayka-guard-")

# Маркер власника: у теці лежить PID процесу, який її створив. Без нього
# «застаріле» доводилося б угадувати за часом — а свіжу теку сусіднього прогону
# вимір за часом якраз і вбив би.
OWNER_MARKER = "owner.pid"

# Явне перенесення тимчасових дерев. Не вимикач: усі перевірки лишаються, і
# кожен прогін каже вголос, куди саме пішли дерева.
RELOCATE_VARIABLE = "SLUKHAYKA_MUTANTS_TMPDIR"

# Скільки копій дерева мусить уміститися. Воркерів типово 4; вісім — це запас на
# те, що між перевіркою й записом місце встигне зменшитися (сусідній прогін,
# Gradle, інший агент у тій самій сесії).
COPY_HEADROOM = 8

# Що саме пише `link_tree`: піддерево, з якого складається інструмент, і те, що в
# копію не йде — кеші байт-коду та теки, які не є кодом репозиторію (`.git`,
# кеші Gradle, `build`, чужі worktree з копіями того самого коду). Це типові
# значення ОДНІЄЇ функції копіювання: механізми, у яких набір інший, передають
# свій явно, а не тримають другу редакцію копіювання.
LINK_COPY_ENTRY = "scripts"
LINK_COPY_IGNORE = ("__pycache__", "*.pyc")
LINK_SKIP_DIRS = frozenset({
    ".git", ".gradle", ".gradle-home", ".kotlin", ".mutations", ".robolectric-jars",
    ".scratch", ".worktrees", "build", "node_modules",
})

# Підлога на власний scratch свідків: `mutate.sh` пише туди pycache, логи,
# аргументи Gradle, а вкладений прогін механізму — ще й свої дерева.
SCRATCH_FLOOR_BYTES = 64 * 1024 * 1024

# Маленький tmpfs у RAM механізм не використовує взагалі: його вичерпання валить
# не прогін, а середовище. 2 GiB — межа, нижче якої RAM-ФС уважається малою (на
# цій машині типовий `/tmp` — 7.8 GiB, і він проходить).
RAM_TMPFS_MIN_BYTES = 2 * 1024 ** 3

# Скільки чекати, перш ніж уважати теку без маркера застарілою. Свіжа тека без
# маркера — це, найімовірніше, сусідній прогін за мілісекунду до запису маркера.
STALE_GRACE_SECONDS = 900

# Файлові системи, які живуть у RAM.
RAM_FILESYSTEMS = ("tmpfs", "ramfs")


class BudgetError(Exception):
    """Місця немає або місце небезпечне. Відмова (код 2), а не «спробуємо»."""


def mib(value: int) -> str:
    return "%.1f MiB" % (value / 1048576)


def announce(message: str) -> None:
    """Голосно — у stderr: це не вердикт, а те, що механізм вирішив сам."""
    print("УВАГА: %s" % message, file=sys.stderr)


# --------------------------------------------------------------------------
# Файлова система
# --------------------------------------------------------------------------


def mount_table(text: str | None = None) -> str:
    """`/proc/mounts` як текст. Порожній рядок — таблиці немає (не Linux)."""
    if text is not None:
        return text
    try:
        return Path("/proc/mounts").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def mount_of(path: Path, table: str | None = None) -> tuple[str, str]:
    """Точка монтування й тип ФС для шляху: найдовший збіг, як робить ядро."""
    resolved = str(Path(path).resolve())
    best = ("", "")
    for line in mount_table(table).splitlines():
        fields = line.split()
        if len(fields) < 3:
            continue
        point = fields[1].replace("\\040", " ")
        if resolved == point or resolved.startswith(point.rstrip("/") + "/"):
            if len(point) >= len(best[0]):
                best = (point, fields[2])
    return best


def statvfs(path: Path) -> os.statvfs_result:
    return os.statvfs(str(path))


def disk_free_bytes(path: Path, stats: os.statvfs_result | None = None) -> int:
    """Скільки байтів ФС віддасть цьому користувачу (f_bavail, не f_bfree)."""
    stats = statvfs(path) if stats is None else stats
    return stats.f_bavail * stats.f_frsize


def filesystem_total_bytes(path: Path, stats: os.statvfs_result | None = None) -> int:
    stats = statvfs(path) if stats is None else stats
    return stats.f_blocks * stats.f_frsize


def ram_backed(path: Path, table: str | None = None) -> bool:
    return mount_of(path, table)[1] in RAM_FILESYSTEMS


# --------------------------------------------------------------------------
# Обсяг копії
# --------------------------------------------------------------------------


def _ignored(name: str, ignore) -> bool:
    return any(fnmatch.fnmatch(name, pattern) for pattern in ignore)


def tree_bytes(directory: Path, ignore=()) -> int:
    """Байти, які займе копія цього піддерева — рівно те, що копіює `copytree`.

    `copytree` без `symlinks=True` симлінк-файл копіює як його ціль, а в
    симлінк-ТЕКУ заходить і копіює вміст. Міра робить так само — інакше «вимір і
    копіювання не можуть розійтися» було б обіцянкою, а на цьому вимірі стоїть
    уся відмова (огляд #1275: міра, яка не заходить у симлінк-теку, нарахувала
    10 Б там, де копіювання пише 100 010 Б).

    Цикл симлінків міра не розкручує: ланцюг предків спиняє справжній цикл (теку,
    яка сама собі предок, `copytree` не скопіює взагалі). Повтор — два симлінки на
    ту саму теку — не цикл: `copytree` копіює обидва, тож і міра рахує двічі.
    Глобальна множина відвіданих inode глушила б другий симлінк і давала
    недооцінку вдвічі (огляд #1275) — тобто помилку в небезпечний бік. Міра лише
    не вигадує чисел, яких копіювання не напише: на справжньому циклі падає
    `copytree`, і невідомість приходить від нього.
    """
    total = 0
    pending = [(directory, frozenset())]
    while pending:
        base, ancestors = pending.pop()
        try:
            info = os.stat(base)
            entries = list(os.scandir(base))
        except OSError:
            continue
        key = (info.st_dev, info.st_ino)
        if key in ancestors:
            continue
        below = ancestors | {key}
        for entry in entries:
            if _ignored(entry.name, ignore):
                continue
            try:
                if entry.is_dir(follow_symlinks=True):
                    pending.append((Path(entry.path), below))
                elif entry.is_file(follow_symlinks=True):
                    total += entry.stat(follow_symlinks=True).st_size
            except OSError:
                continue
    return total


def requirement(copy_bytes: int) -> int:
    """Скільки місця механізм вимагає на ФС, щоб почати копіювати."""
    return copy_bytes * COPY_HEADROOM + SCRATCH_FLOOR_BYTES


def link_tree(root: Path, tmp: Path, *, copy_entry: str = LINK_COPY_ENTRY,
              ignore: tuple[str, ...] = LINK_COPY_IGNORE,
              skip_dirs: frozenset[str] | set[str] = LINK_SKIP_DIRS) -> Path:
    """Копія дерева, де `copy_entry` справжня, а решта top-level записів — симлінки.

    Мутант живе лише в копії, тож дерево репозиторію не змінюється, а свідок
    бачить той самий корінь (`__file__` у копії). Повна копія коштує десятки MiB
    і секунди на кожного мутанта — звідси важіль: копіюється лише піддерево, з
    якого складається інструмент (`scripts/`), а незмінна частина дерева
    (`.git`, кеші Gradle, `build`, чужі worktree) лишається посиланням.

    Це ЄДИНЕ місце, де будується така копія (#1270): `verdict_field_mutants`,
    `empty_shape_mutants` і `guard_witnesses` звуть його, а не тримають кожен
    свою редакцію — копія, яка розійшлася з виміром `tree_bytes`, охороняла б не
    той обсяг, а копія, яка розійшлася з копією, — не той корінь.

    `copy_entry` і `skip_dirs` лишаються параметрами: набір тек, у які механізм не
    дивиться, — це факт про конкретний механізм (і його пінять його власні
    тести), а не про копіювання.
    """
    work = tmp / "root"
    work.mkdir()
    shutil.copytree(
        root / copy_entry,
        work / copy_entry,
        ignore=shutil.ignore_patterns(*ignore),
    )
    for entry in root.iterdir():
        if entry.name in {copy_entry} | set(skip_dirs):
            continue
        (work / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
    return work


# --------------------------------------------------------------------------
# Відмови
# --------------------------------------------------------------------------


def ensure_room(directory: Path, copy_bytes: int, free: int | None = None) -> None:
    """Вільне місце ПЕРЕД копіюванням — з числами в причині відмови."""
    needed = requirement(copy_bytes)
    available = disk_free_bytes(directory) if free is None else free
    if available >= needed:
        return
    raise BudgetError(
        "тимчасовій ФС бракує місця для копії дерева: треба %s (копія %s × %d "
        "+ підлога на scratch свідків %s), вільно %s у %s.\n"
        "      Копіювання не почато: заповнена ФС валить не цей прогін, а кожен "
        "запуск процесу в середовищі (bash, ripgrep).\n"
        "      Далі: звільни місце або вкажи TMPDIR (чи %s) на теку із запасом."
        % (mib(needed), mib(copy_bytes), COPY_HEADROOM, mib(SCRATCH_FLOOR_BYTES),
           mib(available), directory, RELOCATE_VARIABLE)
    )


def refuse_small_ram_tmpfs(directory: Path, copy_bytes: int,
                           table: str | None = None,
                           total: int | None = None) -> None:
    """Маленький tmpfs у RAM — відмова, а не тихе перенаправлення."""
    if not ram_backed(directory, table):
        return
    size = filesystem_total_bytes(directory) if total is None else total
    if size >= RAM_TMPFS_MIN_BYTES:
        return
    needed = requirement(copy_bytes)
    raise BudgetError(
        "тимчасова тека %s лежить на tmpfs у RAM розміром %s — це менше за %s, "
        "нижче якої RAM-ФС уважається малою.\n"
        "      Вичерпати RAM-ФС означає покласти всю сесію: `bash` перестає "
        "стартувати навіть на `true`, і робота виглядає як «зламалися інструменти».\n"
        "      Далі: вкажи TMPDIR на дискову теку (напр. TMPDIR=$HOME/.dsh-tmp) "
        "або %s=<тека на диску> — механізм скаже вголос, куди пішли дерева "
        "(треба йому %s)."
        % (directory, mib(size), mib(RAM_TMPFS_MIN_BYTES), RELOCATE_VARIABLE, mib(needed))
    )


def guard(directory: Path, copy_bytes: int, free: int | None = None,
          table: str | None = None, total: int | None = None) -> None:
    """Обидві відмови в одному місці — саме це кличе кожне копіювання."""
    refuse_small_ram_tmpfs(directory, copy_bytes, table, total)
    ensure_room(directory, copy_bytes, free)


# --------------------------------------------------------------------------
# Застарілі дерева
# --------------------------------------------------------------------------


def process_alive(pid: int) -> bool:
    """Чи живий процес. Невідомість — «живий»: чужу теку не чіпають."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except OSError:
        return True
    return True


def owner_pid(directory: Path) -> int | None:
    try:
        return int((directory / OWNER_MARKER).read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def mark(directory: Path) -> Path:
    """Позначити теку власником, щоб сусідній прогін не вважав її застарілою."""
    (directory / OWNER_MARKER).write_text("%d\n" % os.getpid(), encoding="utf-8")
    return directory


def stale(directory: Path, *, grace: int = STALE_GRACE_SECONDS,
          now: float | None = None, alive=process_alive) -> bool:
    """Чи можна прибирати цю теку: мертвий власник або стара тека без маркера."""
    pid = owner_pid(directory)
    if pid is not None:
        return not alive(pid)
    try:
        changed = directory.stat().st_mtime
    except OSError:
        return False
    return ((time.time() if now is None else now) - changed) > grace


def sweep_stale(directory: Path, prefixes=OWN_PREFIXES, *, grace: int = STALE_GRACE_SECONDS,
                now: float | None = None, alive=process_alive, remove=shutil.rmtree):
    """Прибирає застарілі дерева СВОЇХ префіксів. Повертає (шлях, байти)."""
    freed: list[tuple[Path, int]] = []
    try:
        entries = sorted(directory.iterdir())
    except OSError:
        return freed
    for entry in entries:
        if not any(entry.name.startswith(prefix) for prefix in prefixes):
            continue
        if entry.is_symlink() or not entry.is_dir():
            continue
        if not stale(entry, grace=grace, now=now, alive=alive):
            continue
        size = tree_bytes(entry)
        try:
            remove(entry)
        except OSError as error:
            announce("застаріле тимчасове дерево %s прибрати не вдалося (%s) — "
                     "місце лишається зайнятим" % (entry, error))
            continue
        freed.append((entry, size))
    return freed


# --------------------------------------------------------------------------
# Тимчасовий корінь
# --------------------------------------------------------------------------


def temp_root(base: Path | None = None, *, relocate: str | None = None) -> Path:
    """Де живуть тимчасові дерева: `TMPDIR`, або явне перенесення — голосно.

    `TMPDIR` читається з оточення, а не з `tempfile.gettempdir()`: останній
    кешує перший відповідь, а ще мовчки падає на `/tmp`, коли `TMPDIR` зламаний.
    Зламаний `TMPDIR` — це відмова, а не привід вибрати інше місце за оператора.
    """
    base = Path(os.environ.get("TMPDIR") or tempfile.gettempdir()) if base is None else Path(base)
    relocate = os.environ.get(RELOCATE_VARIABLE) if relocate is None else relocate
    if not relocate:
        return base
    target = Path(relocate)
    if not target.is_dir():
        raise BudgetError(
            "%s=%s — не тека: перенесення мусить називати наявну теку, бо мовчазне "
            "створення шляху з друкарською помилкою сховало б помилку."
            % (RELOCATE_VARIABLE, relocate)
        )
    if target != base:
        announce("тимчасові дерева підуть у %s (%s), а не в TMPDIR=%s — це явне "
                 "перенесення; перевірки місця в ньому ті самі."
                 % (target, RELOCATE_VARIABLE, base))
    return target


def claim(directory: Path, prefix: str) -> Path:
    """Нова тека з маркером власника: сусідній прогін її не прибере.

    Створення й маркер — одна операція, а не дві (#1275): тека без маркера для
    сусіднього прогону не власність, а сміття, яке приберуть за часом. Тому
    невдалий маркер прибирає щойно створену теку — інакше хелпер сам лишав би
    по собі те, проти чого заведений.
    """
    created = Path(tempfile.mkdtemp(prefix=prefix, dir=str(directory)))
    try:
        return mark(created)
    except OSError:
        shutil.rmtree(created, ignore_errors=True)
        raise


def prepare(prefixes=OWN_PREFIXES, copy_bytes: int | None = None,
            base: Path | None = None, *, relocate: str | None = None) -> Path:
    """Тимчасовий корінь, готовий до копіювання: спершу прибрати, потім міряти.

    Саме в цьому порядку: прибирання повертає місце, і перевірка місця мусить
    бачити вже повернуте, інакше механізм відмовляв би там, де сам же щойно
    звільнив достатньо.
    """
    base = temp_root(base, relocate=relocate)
    if not base.is_dir():
        raise BudgetError("тимчасового кореня %s немає — копіювати дерево нема куди." % base)
    for path, size in sweep_stale(base, prefixes):
        announce("прибрано застаріле тимчасове дерево %s (%s) — лишилося після падіння "
                 "й добивало б наступний прогін." % (path, mib(size)))
    if copy_bytes is not None:
        guard(base, copy_bytes)
    return base
