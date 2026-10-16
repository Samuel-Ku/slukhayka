#!/usr/bin/env python3
"""Два рішення під одним номером ADR не конфліктують у git — тому їх ловить гейт (#1227).

Живий приклад, і він не гіпотетичний. PR #1186 додає
`docs/adr/0061-local-folder-source-is-a-reference.md`, а PR #1139 — інше рішення
під тим самим номером, `docs/adr/0061-real-catalog-recommendation-eval.md`; той
самий #1139 додає `docs/adr/0062-real-catalog-recommendation-eval.md`, а в `main`
номер 0062 уже несе `docs/adr/0062-one-heavy-build-slot.md`. `git` цього не
бачить і не побачить: імена файлів РІЗНІ, тож злиття чисте (показано прогоном —
`test_adr_numbers.OldWorldTest`), і дубль номера входить у `main` текстово
чисто. Тому гейт стоїть на РЕЗУЛЬТАТІ злиття, а не на конфлікті: він читає
дерево пробного злиття.

Що механізм робить:
  * номер ADR — це перші чотири цифри імені файлу в `docs/adr/` (`NNNN-*.md`), і
    більше ніщо;
  * `--base REF` (з `--head`, типово `HEAD`) бере дерево ПРОБНОГО ЗЛИТТЯ —
    `git merge-tree --write-tree`: обʼєктна база, а робоче дерево й індекс не
    змінюються. Це той самий спосіб, що в `check-base-sync.sh` / `base-sync`;
  * без `--base` читається дерево робочої копії (`--root`) або названої ревізії
    (`--head REF`);
  * `--open-prs` (або `--pr N`) — ЛИШЕ ЧИТАННЯ `gh`: один і той самий номер, який
    додають два відкриті PR, два рішення під одним номером усередині одного PR, і
    номер, який PR додає там, де він уже в `main`. Читаються `number`,
    `headRefOid`, `files` (`changeType`), і нічого не пишеться — ні issue, ні PR,
    ні файли дерева: цей режим нікого не перенумеровує.

Коди виходу: 0 — жоден номер не несуть два рішення; 1 — знахідка (номер несуть
два рішення; номер додають два відкриті PR; PR додає номер, який уже в `main`), і
кожне конфліктне рішення назване своїм шляхом; 2 — перевірити не вдалося (немає
теки, немає `git` або він не назвав дерева, конфліктне пробне злиття, немає `gh`
або він не відповів, дивне імʼя — кожне назване окремо).

Межі названі тут, у коді, а не лише в звіті:

  * номер читається ЛИШЕ з імені файлу. Два рішення, які в ТЕКСТІ звуть себе
    одним номером і лежать під різними іменами, гейт не бачить — і це не
    рідкість: у `main` таких файлів десятки
    (`grep -lE '\\b00[0-9][0-9]\\b' docs/adr/*.md | wc -l`). Розрізнити
    «посилання на сусідній ADR» і «це рішення номер N» структурно нема чим:
    шапка файлу — проза, а прозу цей механізм не читає;
  * ADR, доданий ПОВЗ PR (прямий push у `main`, або злиття, яке вже сталося),
    не ловить ніщо: джоба в CI стоїть на `pull_request`, бо на push немає з чим
    порівнювати. Дубль, що вже в `main`, гейт назве лише тоді, коли його
    торкнеться наступний PR;
  * база — РУХОМИЙ ref. Джоба бере `origin/$BASE_REF` у момент прогону: PR, що
    злився в `main` після цього, у перевіреному дереві не стоїть, і наступний
    прогін це побачить, а цей — ні. Так само `--base` читає рівно той ref, який
    назвали;
  * `--open-prs` читає щонайбільше `--limit` відкритих PR (типово
    `OPEN_PR_LIMIT`): PR за вікном не перевірений, і тихий нуль про нього не
    каже. Через `gh` цей режим працює ЛИШЕ локально: у CI джоба токена не має, і
    джоба гейта його не кличе — кроспі-PR половина в CI не біжить, і це названо
    в `ci.yml`;
  * номер — рівно чотири цифри перед першим дефісом. `10061-x.md` або
    `61-x.md` — дивне імʼя, і воно не ігнорується: кожне таке імʼя назване
    окремо й коштує код 2. Список цифр не «схоже на номер» — розширювати його
    на пʼять цифр означало б тихо змінити межу, а не полагодити її;
  * `changeType` у `gh` — перелічена множина (`CLAIMING_CHANGES` /
    `NON_CLAIMING_CHANGES`). Невідоме значення — не «не додає номера», а
    невідомість: файл названий, код виходу 2. Мовчазна згода тут була б
    згодою про форму, якої механізм не бачив;
  * гейт ЛИШЕ ВІДМОВЛЯЄ і нікого не перенумеровує: він не править файли, не
    чіпає issue і не чіпає PR. Який із двох номерів міняти — рішення автора
    (AC тікета), і механізм цього рішення не ухвалює;
  * хибних відмов на реальному дереві немає, і це вимір, а не обіцянка:
    `git ls-tree -r --name-only origin/main -- docs/adr/ | ...` дає 59 ADR і
    жодного повторного номера, тож «два файли з одним номером» — рівно та
    форма, яку гейт шукає, а не суміжна. Форма, у якій два файли ЗАКОННО ділять
    номер (частина 1 / частина 2), у дереві не зустрічається; якщо зʼявиться,
    гейт почервонить, і це буде видно як знахідку, а не як тиху згоду.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Тека й форма імені. Форма — стовпчик: NNNN, дефіс, непорожній хвіст, `.md`.
# Це та сама форма, якою названі файли в дереві, а не вигадана тут.
ADR_DIR = "docs/adr"
ADR_NAME = re.compile(r"^(?P<number>\d{4})-(?P<slug>.+\.md)$")

# Ревізія дерева, з якою `--open-prs` звіряє нові номери: та сама, що в
# `issue_premise.py` і `ci_job_time.py` (питання до git, а не до мережі).
MAIN_REF = "origin/main"

# Скільки відкритих PR входить у читання `--open-prs`. Межа названа в шапці: PR
# за вікном не перевірений, і тихий нуль про нього не каже.
OPEN_PR_LIMIT = 100

# Що `gh` каже про файл у PR, і що з цього виводиться.
# `ADDED`/`RENAMED` — імʼя в дереві PR нове, тож номер, який воно несе, PR
# додає; `MODIFIED`/`DELETED` — імʼя вже було, нового номера не додає.
CLAIMING_CHANGES = frozenset({"ADDED", "RENAMED"})
NON_CLAIMING_CHANGES = frozenset({"MODIFIED", "DELETED"})

# Дерево пробного злиття `git merge-tree --write-tree` друкує першим рядком.
TREE_OID = re.compile(r"^[0-9a-f]{40,64}$")

# Коди виходу — ті самі, що в сусідніх механізмах (`doc_numbers`,
# `named_boundaries`): 0 — чисто, 1 — знахідка, 2 — перевірити не вдалося.
EXIT_OK = 0
EXIT_FINDING = 1
EXIT_UNKNOWN = 2


class AdrUnknown(Exception):
    """Те, чого механізм не зміг прочитати. Невідомість, а не «колізій немає»."""


@dataclasses.dataclass(frozen=True)
class Adr:
    """Номер і шлях, яким він названий у дереві."""

    number: str
    path: str


# --------------------------------------------------------------------------
# Імена файлів
# --------------------------------------------------------------------------


def parse_names(names: list[str], where: str) -> tuple[list[Adr], list[str]]:
    """Розібрати шляхи під `docs/adr/` на (номери, дивні імена).

    Дивне імʼя — не «не ADR, отже повз»: воно назване окремо, і кожне з них
    коштує код 2. Інакше файл, який лежить у теці ADR і не має форми
    `NNNN-*.md`, був би невидимий рівно там, де його шукають.
    """
    adrs: list[Adr] = []
    odd: list[str] = []
    prefix = ADR_DIR + "/"
    for name in names:
        leaf = name[len(prefix):] if name.startswith(prefix) else name
        match = ADR_NAME.match(leaf)
        if match is None:
            odd.append(
                "%s: «%s» не має форми %s/NNNN-*.md — номер із такого імені не "
                "прочитати" % (where, name, ADR_DIR)
            )
            continue
        adrs.append(Adr(match.group("number"), name))
    return adrs, odd


def collision_findings(adrs: list[Adr], where: str) -> list[str]:
    """Номери, які несуть два різні рішення, — по одному рядку на номер.

    Рішенням вважається ШЛЯХ: два однакові шляхи неможливі в одному дереві, тож
    група з двох — це завжди два різні файли, тобто два рішення.
    """
    by_number: dict[str, list[Adr]] = {}
    for adr in adrs:
        by_number.setdefault(adr.number, []).append(adr)
    findings: list[str] = []
    for number in sorted(by_number):
        group = by_number[number]
        if len(group) < 2:
            continue
        findings.append(
            "%s — номер %s несуть %d рішення: %s"
            % (where, number, len(group), ", ".join(sorted(adr.path for adr in group)))
        )
    return findings


# --------------------------------------------------------------------------
# git
# --------------------------------------------------------------------------


def git_executable() -> str:
    """Шлях до `git`; його відсутність — невідомість, а не стек.

    Той самий спосіб, що `git_executable` у `issue_premise.py` і
    `ci_job_time.py`: питання ставить `shutil.which`, і відповідь «немає» стає
    названою причиною до того, як `subprocess` устигне кинути
    `FileNotFoundError`.
    """
    found = shutil.which("git")
    if found is None:
        raise AdrUnknown(
            "немає `git` — ні дерева пробного злиття, ні списку файлів ревізії "
            "взяти нізвідки"
        )
    return found


def git(root: Path, arguments: list[str]) -> subprocess.CompletedProcess:
    """Один виклик `git` у корені дерева. Невдача запуску — невідомість."""
    try:
        return subprocess.run(
            [git_executable(), *arguments], cwd=str(root),
            capture_output=True, text=True,
        )
    except OSError as error:
        raise AdrUnknown("`git %s` не вдався: %s" % (" ".join(arguments), error))


def detail(result: subprocess.CompletedProcess) -> str:
    """Останній непорожній рядок діагностики — для названої причини."""
    for stream in (result.stderr, result.stdout):
        lines = [line for line in stream.splitlines() if line.strip()]
        if lines:
            return lines[-1].strip()
    return "без подробиць"


def tree_names(root: Path, tree: str) -> list[str]:
    """Шляхи під `docs/adr/` у названому дереві. Порожньо — теки немає."""
    result = git(root, ["ls-tree", "-r", "--name-only", tree, "--", ADR_DIR + "/"])
    if result.returncode != 0:
        raise AdrUnknown(
            "`git ls-tree %s` не дав списку файлів: %s" % (tree, detail(result))
        )
    return [line for line in result.stdout.splitlines() if line.strip()]


def trial_merge_names(root: Path, base: str, head: str) -> list[str]:
    """Шляхи під `docs/adr/` у ДЕРЕВІ ПРОБНОГО ЗЛИТТЯ `base..head`.

    `git merge-tree --write-tree` пише дерево в обʼєктну базу, а робоче дерево й
    індекс не чіпає — це той самий спосіб, що `base-sync`. Конфлікт (код 1) не
    «колізій немає»: дерева, яке можна перевірити, не існує, тож це
    невідомість.
    """
    result = git(root, ["merge-tree", "--write-tree", base, head])
    if result.returncode != 0:
        if "CONFLICT" in result.stdout or "conflict" in result.stdout:
            # Рядки стадій — це `ls-tree`-рядки конфлікту; для причини досить
            # ІМЕН файлів, і кожен з них названий (та сама межа, що в дереві).
            names: list[str] = []
            for line in result.stdout.splitlines()[1:]:
                stripped = line.strip()
                if not stripped:
                    continue
                parts = stripped.split("\t")
                candidate = parts[-1].strip() if len(parts) > 1 else stripped.split()[-1]
                if candidate and candidate not in names:
                    names.append(candidate)
            raise AdrUnknown(
                "пробне злиття %s..%s конфліктне — дерева для перевірки немає, і "
                "«колізій немає» сказати нема про що (%s)"
                % (base, head, ", ".join(names[:3]) or "конфліктні файли не названі")
            )
        raise AdrUnknown(
            "`git merge-tree %s %s` не назвав дерева: %s" % (base, head, detail(result))
        )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not lines or not TREE_OID.match(lines[0]):
        raise AdrUnknown(
            "`git merge-tree %s %s` не назвав дерева пробного злиття (перший рядок: %r)"
            % (base, head, lines[0] if lines else "")
        )
    return tree_names(root, lines[0])


# --------------------------------------------------------------------------
# gh
# --------------------------------------------------------------------------


def gh_json(arguments: list[str]) -> object:
    """Один READ-ONLY виклик `gh`, розібраний як JSON.

    Нічого не пише: жодного `gh issue edit`, `gh pr edit` чи `gh api -X`. Це
    межа тікета — перевірник не редагує issues і PR, лише читає.
    """
    found = shutil.which("gh")
    if found is None:
        raise AdrUnknown(
            "немає `gh` — відкриті PR не прочитати, і «номерів ніхто не додає» "
            "сказати нема про що"
        )
    try:
        result = subprocess.run(
            [found, *arguments], capture_output=True, text=True,
        )
    except OSError as error:
        raise AdrUnknown("`gh %s` не вдався: %s" % (" ".join(arguments), error))
    if result.returncode != 0:
        raise AdrUnknown(
            "`gh %s` вийшов із кодом %d: %s"
            % (" ".join(arguments), result.returncode, detail(result))
        )
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise AdrUnknown("`gh %s` не дав JSON: %s" % (" ".join(arguments), error))


def pull_requests(prs: list[int], limit: int, gh) -> list[dict]:
    """Відкриті PR — або названі номери, або вікно `--limit`."""
    if prs:
        payload = [
            gh(["pr", "view", str(number), "--json", "number,headRefOid,files"])
            for number in prs
        ]
    else:
        payload = gh([
            "pr", "list", "--state", "open", "--limit", str(limit),
            "--json", "number,headRefOid,files",
        ])
    if not isinstance(payload, list):
        raise AdrUnknown("`gh` назвав не список PR: %r" % (payload,))
    for entry in payload:
        if not isinstance(entry, dict):
            raise AdrUnknown("`gh` назвав PR не записом: %r" % (entry,))
    return payload


def claim_of(pr: dict) -> tuple[int, str, list[tuple[str, str]]]:
    """Що PR додає: (номер, HEAD, [(номер ADR, шлях)]).

    `changeType`, якого механізм не знає, — невідомість, а не «не додає»:
    мовчазна згода про форму, якої не бачили, була б згодою ні про що.
    """
    number = pr.get("number")
    head = pr.get("headRefOid")
    if not isinstance(number, int):
        raise AdrUnknown("`gh` не назвав номера PR: %r" % (pr.get("number"),))
    if not isinstance(head, str) or not head:
        raise AdrUnknown("PR #%s: `gh` не назвав HEAD (`headRefOid`)" % number)
    files = pr.get("files")
    if not isinstance(files, list):
        raise AdrUnknown("PR #%s: `gh` не назвав файлів" % number)
    claims: list[tuple[str, str]] = []
    for entry in files:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise AdrUnknown("PR #%s: `gh` назвав файл не записом: %r" % (number, entry))
        path = entry["path"]
        number_text = entry.get("changeType")
        if number_text in NON_CLAIMING_CHANGES:
            continue
        if number_text not in CLAIMING_CHANGES:
            raise AdrUnknown(
                "PR #%s, %s: невідомий `changeType` %r — не сказати, чи додає це "
                "імʼя номер" % (number, path, number_text)
            )
        if not path.startswith(ADR_DIR + "/"):
            continue
        match = ADR_NAME.match(path[len(ADR_DIR) + 1:])
        if match is None:
            # Дивне імʼя в PR — та сама межа, що в дереві: код 2, а не повз.
            raise AdrUnknown(
                "PR #%s додає «%s», і це імʼя не має форми %s/NNNN-*.md"
                % (number, path, ADR_DIR)
            )
        claims.append((match.group("number"), path))
    return number, head, claims


def open_pr_findings(root: Path, main_ref: str, prs: list[int], limit: int, gh):
    """(знахідки, перевірені, рядок покриття) для read-only режиму `gh`."""
    payload = pull_requests(prs, limit, gh)
    main_names = tree_names(root, main_ref)
    if not main_names:
        raise AdrUnknown("у %s немає теки %s — з чим звіряти нові номери" % (main_ref, ADR_DIR))
    main_adrs, _ = parse_names(main_names, main_ref)
    main_by_number: dict[str, list[str]] = {}
    for adr in main_adrs:
        main_by_number.setdefault(adr.number, []).append(adr.path)

    by_number: dict[str, list[tuple[int, str, str]]] = {}
    claims_by_pr: list[tuple[int, str]] = []
    for pr in payload:
        number, head, claims = claim_of(pr)
        claims_by_pr.append((number, head))
        for adr_number, path in claims:
            by_number.setdefault(adr_number, []).append((number, head, path))

    findings: list[str] = []
    for adr_number in sorted(by_number):
        entries = by_number[adr_number]
        # Два РІЗНІ шляхи під одним номером — колізія, і не важливо, чи вони з
        # різних PR, чи з одного: `git` обидва зливає чисто. Один і той самий
        # шлях, названий двічі, колізією не є.
        if len({entry[2] for entry in entries}) > 1:
            prs_named = sorted({entry[0] for entry in entries})
            if len(prs_named) > 1:
                findings.append(
                    "номер %s додають два відкриті PR: %s"
                    % (
                        adr_number,
                        "; ".join(
                            "PR #%d (HEAD %s) — %s" % (number, head, path)
                            for number, head, path in sorted(entries)
                        ),
                    )
                )
            else:
                findings.append(
                    "PR #%d (HEAD %s) додає два рішення під одним номером %s: %s"
                    % (
                        prs_named[0], next(entry[1] for entry in entries),
                        adr_number,
                        ", ".join(sorted({entry[2] for entry in entries})),
                    )
                )
        if adr_number in main_by_number:
            for number, head, path in sorted(entries):
                findings.append(
                    "PR #%d (HEAD %s) додає %s, а номер %s уже несе %s: %s"
                    % (number, head, path, adr_number, main_ref,
                       ", ".join(sorted(main_by_number[adr_number])))
                )
    coverage = (
        "перевірено PR: %s; основа %s: %d ADR, жодного повторного номера"
        % (
            ", ".join("#%d (HEAD %s)" % entry for entry in sorted(claims_by_pr))
            or "жодного",
            main_ref, len(main_adrs),
        )
    )
    return findings, claims_by_pr, coverage


# --------------------------------------------------------------------------
# Перевірка
# --------------------------------------------------------------------------


def scan(root: Path, base: str | None, head: str) -> tuple[list[Adr], list[str], str]:
    """Прочитати номери: (ADR, дивні імена, де читали)."""
    if base is not None:
        where = "пробне злиття %s..%s" % (base, head)
        names = trial_merge_names(root, base, head)
        if not names:
            raise AdrUnknown(
                "у дереві пробного злиття %s..%s немає теки %s — перевіряти нема "
                "чого" % (base, head, ADR_DIR)
            )
        adrs, odd = parse_names(names, where)
        return adrs, odd, where
    if head != "HEAD":
        names = tree_names(root, head)
        if not names:
            raise AdrUnknown("у %s немає теки %s" % (head, ADR_DIR))
        adrs, odd = parse_names(names, head)
        return adrs, odd, head
    directory = root / ADR_DIR
    if not directory.is_dir():
        raise AdrUnknown("немає теки %s у %s" % (ADR_DIR, root))
    entries = sorted(directory.iterdir(), key=lambda entry: entry.name)
    if not entries:
        raise AdrUnknown(
            "%s порожня — жодного ADR не знайдено, тож і дубля нема про що "
            "сказати" % (root / ADR_DIR)
        )
    names = [
        ADR_DIR + "/" + entry.name + ("/" if entry.is_dir() else "")
        for entry in entries
    ]
    adrs, odd = parse_names(names, "робоче дерево")
    return adrs, odd, "робоче дерево"


def check(root: Path, base: str | None = None, head: str = "HEAD",
          open_prs: bool = False, prs: list[int] | None = None,
          main_ref: str = MAIN_REF, limit: int = OPEN_PR_LIMIT,
          gh=None) -> tuple[list[str], list[str]]:
    """Що механізм побачив: (знахідки, невідомості). Код виходу ухвалює `main`.

    Невідомість не ховає знахідок: якщо є і те, й те, видно обидва, а код
    виходу — 2 (та сама межа, що в `doc_numbers`).
    """
    prs = list(prs or ())
    if (open_prs or prs) and base is not None:
        return [], [
            "названо і `--base`, і читання відкритих PR — це два різні дерева, і "
            "змішати їх означало б не сказати, яке з них перевірене"
        ]
    if open_prs or prs:
        runner = gh_json if gh is None else gh
        try:
            findings, checked, coverage = open_pr_findings(root, main_ref, prs, limit, runner)
        except AdrUnknown as error:
            return [], [str(error)]
        print("ПРОЧИТАНО: %s" % coverage)
        return findings, []
    try:
        adrs, odd, where = scan(root, base, head)
    except AdrUnknown as error:
        return [], [str(error)]
    findings = collision_findings(adrs, where)
    print(
        "ПРОЧИТАНО: %s — %d ADR, %d різних номерів"
        % (where, len(adrs), len({adr.number for adr in adrs}))
    )
    return findings, odd


# --------------------------------------------------------------------------
# Виклик
# --------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Два рішення під одним номером ADR — знахідка, а не чисте злиття.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева")
    parser.add_argument("--base", default=None,
                        help="ref бази: читати дерево ПРОБНОГО злиття `--base..--head`")
    parser.add_argument("--head", default="HEAD",
                        help="ref голови (типово HEAD); без `--base` — дерево цієї ревізії")
    parser.add_argument("--open-prs", action="store_true",
                        help="READ-ONLY: номери, які додають відкриті PR (`gh`)")
    parser.add_argument("--pr", type=int, action="append", default=[],
                        help="обмежитись названими PR; можна кілька разів")
    parser.add_argument("--main-ref", default=MAIN_REF,
                        help="реф основної гілки для звірки нових номерів (типово %s)" % MAIN_REF)
    parser.add_argument("--limit", type=int, default=OPEN_PR_LIMIT,
                        help="скільки відкритих PR читати (типово %d)" % OPEN_PR_LIMIT)
    options = parser.parse_args(argv)
    if options.limit <= 0:
        print("НЕВІДОМО: --limit %d — вікно без PR не перевіряє нічого" % options.limit,
              file=sys.stderr)
        return EXIT_UNKNOWN
    findings, unknowns = check(
        options.root.resolve(), options.base, options.head,
        options.open_prs, options.pr, options.main_ref, options.limit,
    )
    for finding in findings:
        print("FAIL  %s" % finding)
    for unknown in unknowns:
        print("НЕВІДОМО: %s" % unknown, file=sys.stderr)
    if unknowns:
        return EXIT_UNKNOWN
    if findings:
        return EXIT_FINDING
    print("OK    жоден номер ADR не несуть два рішення")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
