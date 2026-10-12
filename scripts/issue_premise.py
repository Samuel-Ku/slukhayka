#!/usr/bin/env python3
"""Ґард премиси тікета: шляхи з тіла issue мусять існувати в `main` (#1264).

Тікет #1261 стверджував, що `AGENTS.md` посилається на `CODING_STANDARDS.md`.
У `main` того посилання немає (`git grep -l CODING_STANDARDS origin/main` →
порожньо), а сам файл і посилання з'явилися разом із **незакоміченою** правкою
іншої машини — тікет народився зі стану робочої копії, не зі стану `main`, і
його довелося закрити як недійсний. Це клас «премиса тікета не існує в `main`».

Механізм ловить його детерміновано: кожен шлях, названий у тілі issue, або
**є** в `origin/main`, або **позначений** як такий, що цей тікет створить.
Третього немає — і саме третій стан (шлях, якого в `main` немає й ніхто не
обіцяв створити) дає відмову.

    python3 scripts/issue_premise.py --body-file .scratch/1264.md   # чернетка
    python3 scripts/issue_premise.py --body-file -                   # з stdin
    python3 scripts/issue_premise.py --issue 1261                    # уже заведений

Форма позначки — `NEW:` **усередині** того самого інтервалу в бектиках, перед
шляхом::

    - [ ] `NEW:docs/agents/premise.md` отримує правило.

Чому саме так — і чому це не «перевіряйте уважніше»:

* **Детермінованість.** Вердикт дає `git`, а не судження. Питання до кожного
  шляху одне: чи є дерево з такою назвою в `origin/main` (`git cat-file -e`,
  для повного шляху — байт-у-байт, без робочої копії, без `checkout`). Однакове
  тіло issue дає однаковий вердикт на кожній машині з тим самим `origin/main`.
* **Тихість.** Тікет, який згадує файл, що **буде** створений цим же тікетом,
  не валиться: позначка `NEW:` знімає вимогу «є в `main`» — і натомість
  вимагає протилежного: такого шляху в `main` бути **не повинно**. Тобто
  позначка не лазівка, а друге плече тієї самої перевірки: вона ловить і
  «оголосив новим те, що вже є».
* **Ціна хибного пропуску.** Замовчати можна лише одним способом — поставити
  `NEW:` там, де шлях не створюється. Це не забудькуватість, а письмове
  твердження в тілі тікета, яке видно оглядові й автору; тихе незнання
  неможливе, бо невиконаний шлях без позначки — відмова.

Пошук шляху, коли повного шляху немає: `ci.yml` замість
`.github/workflows/ci.yml` — звичайна річ у тілах issue, і валити її означало б
шум. Тому ім'я без слеша шукається за базовим іменем у дереві `main`: одне
входження — проходить (це той самий файл), кілька — невідомість (код 2) з
підказкою назвати повний шлях.

Межі, названі прямо:

* Перевіряються **шляхи**, не твердження про їхній вміст. «`AGENTS.md` обіцяє
  X» цей ґард не перевірить; #1261 упав на іншому — файлу, на який тікет
  посилався, не існувало. Твердження про вміст ловить огляд, і саме тому
  ґард не вдає, що замінює його.
* Вердикт — про **локальний** `origin/main`. Якщо ref не оновлено, ґард
  судить старе дерево; ліки — `git fetch origin main` перед прогоном. Сам
  ґард не робить мережевих запитів і не підтягує ref потай.
* Пропущений шлях у бектиках невидимий: шлях без бектиків, у посиланні
  Markdown чи в коментарі до issue — не перевіряється. Це межа **вибору
  премиси**, а не перевірки: що автор назвав шляхом, те й перевірено.
* Винятки з відбору кандидатів (рядки-команди `gh …`, Gradle-цілі
  `:app:lintDebug`) названі в `candidates()` і покриті тестами — щоб ґард не
  падав на тексті, який шляхом не є.

Вихідні коди: 0 — кожен шлях або є в `main`, або позначений `NEW:` і його там
немає; 1 — премиси немає (шлях без позначки, якого в `main` немає, або `NEW:`
на шлях, який у `main` уже є); 2 — невідомість (ref не читається, ім'я
неоднозначне, порожнє тіло).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MAIN_REF = "origin/main"

# Позначка «цей тікет створить шлях» — усередині того самого інтервалу в
# бектиках, перед шляхом. Саме в інтервалі, а не окремим абзацом: тоді вона
# невідривна від шляху й не може «загубитися» при перекладі тікета.
NEW_MARKER = "NEW:"

SPAN_RE = re.compile(r"`([^`\n]+)`")
# Сегмент шляху як його пишуть у тілах issue: літери, цифри, `_`, `.`, `-`,
# `*` (глоб) і `@` (плейсхолдер версії).
SEGMENT_RE = r"[A-Za-z0-9_.\-*@]+"
PATH_RE = re.compile(rf"^{SEGMENT_RE}(/{SEGMENT_RE})*/?$")
# Gradle-ціль: `:app:lintDebug`, `:app:testPureJvm`. Шляхом не є, хоч і містить
# двокрапки; відрізняється від `app/lint-baseline.xml` відсутністю слеша й
# наявністю двокрапки.
GRADLE_TASK_RE = re.compile(r"^:?[A-Za-z][\w.\-]*(:[A-Za-z][\w.\-]*)+$")
# Git-ref: `origin/main`, `release/1.3.9`, `HEAD`. Формально це `шлях` для
# PATH_RE, але файлу з такою назвою в дереві не буває й бути не мусить — тож
# це не премиса про наявність файлу, і ґард на ньому не падає.
GIT_REF_RE = re.compile(r"^(?:[A-Za-z0-9_.\-]+/)*[A-Za-z0-9_.\-]+$")
KNOWN_REF_HEADS = frozenset({"origin", "upstream", "HEAD", "refs", "remotes", "tags"})

# Розширення, за якими шлях упізнається без слеша (`ci.yml`, `mutate.sh`,
# `AGENTS.md`) і зі слешем у короткій формі (`docs/voice.md`).
PATH_EXTENSIONS = frozenset(
    {
        ".md", ".py", ".sh", ".kt", ".kts", ".java", ".xml", ".json", ".yml",
        ".yaml", ".toml", ".properties", ".gradle", ".ts", ".tsx", ".js",
        ".mjs", ".html", ".css", ".txt", ".rules", ".sql", ".png", ".jpg",
        ".svg", ".csv", ".lock", ".cfg", ".ini", ".pro", ".gitignore",
    }
)


@dataclass(frozen=True)
class Candidate:
    """Шлях, названий у тілі issue, і місце, де він названий."""

    path: str
    line_no: int
    marked_new: bool

    def display(self) -> str:
        return f"`{NEW_MARKER}{self.path}`" if self.marked_new else f"`{self.path}`"


@dataclass(frozen=True)
class Verdict:
    """Що ґард каже про один шлях."""

    candidate: Candidate
    code: int  # 0 — премиса є, 1 — відмова, 2 — невідомість
    detail: str

    def display(self) -> str:
        return self.candidate.display()


def extract_spans(body: str) -> list[tuple[str, int]]:
    """Інтервали в бектиках разом із номером рядка (1-based)."""
    spans: list[tuple[str, int]] = []
    for line_no, line in enumerate(body.splitlines(), start=1):
        for match in SPAN_RE.finditer(line):
            spans.append((match.group(1), line_no))
    return spans


def _is_command_span(span: str) -> bool:
    """Рядок-команда: `gh issue view <n> --comments`, `python3 x.py --flag`."""
    tokens = span.split()
    if not tokens:
        return True
    if tokens[0] in {"gh", "git", "python", "python3", "sh", "bash", "npm", "pnpm", "./gradlew", "gradlew", "curl"}:
        return True
    # Опція чи ціль Gradle як перший токен: `--tests`, `:app:lintDebug`.
    return tokens[0].startswith("-") or GRADLE_TASK_RE.match(tokens[0]) is not None


def _is_git_ref(span: str) -> bool:
    """Чи це git-ref, а не файл: перший сегмент — відома голова ref-а.

    `origin/main`, `upstream/main`, `refs/heads/main` — ref. `docs/voice.md` —
    ні, хоч форма й та сама; саме тому потрібна голова, а не загальна форма.
    """
    if span in KNOWN_REF_HEADS:
        return True
    if not GIT_REF_RE.match(span):
        return False
    head = span.split("/", 1)[0]
    return head in KNOWN_REF_HEADS


def _is_path_like(span: str) -> bool:
    if " " in span or "\t" in span:
        return False
    if span.startswith(("./", "/", "~", "-")):
        return False
    if span.startswith("$") or "<" in span or ">" in span or "{" in span:
        return False
    if span.endswith("(") or span.endswith(")"):
        return False
    if not PATH_RE.match(span):
        return False
    if GRADLE_TASK_RE.match(span):
        return False
    if _is_git_ref(span):
        return False
    if "/" in span:
        return True
    # Без слеша: або відоме розширення, або крапка в імені (`CONTEXT.md`).
    return Path(span).suffix.lower() in PATH_EXTENSIONS


def candidates(body: str) -> list[Candidate]:
    """Шляхи, названі в тілі issue, у порядку появи.

    Винятки, які не є премисою про наявний файл: рядки-команди, Gradle-цілі,
    глоби каталогів із зірочкою в останньому сегменті (вони не називають
    конкретного файлу) — усе це покрито тестами, щоб ґард не падав на тексті,
    який шляхом не є.
    """
    found: list[Candidate] = []
    seen: set[str] = set()
    for span, line_no in extract_spans(body):
        marked_new = span.startswith(NEW_MARKER)
        path = span[len(NEW_MARKER):] if marked_new else span
        if _is_command_span(path):
            continue
        if not _is_path_like(path):
            continue
        if path.endswith("/"):
            # Каталог: перевіряється як дерево, тож хвостовий слеш прибираємо.
            path = path.rstrip("/")
            if not path:
                continue
        if any("*" in segment for segment in path.split("/")):
            # Глоб називає множину, а не премису про один шлях:
            # `app/src/main/res/values*/strings.xml`, `scripts/tests/test_*.py`.
            continue
        if marked_new and not PATH_RE.match(path):
            continue
        if path in seen:
            continue
        seen.add(path)
        found.append(Candidate(path=path, line_no=line_no, marked_new=marked_new))
    return found


def git_output(args: list[str]) -> tuple[int, str, str]:
    proc = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return proc.returncode, proc.stdout, proc.stderr


def resolve_main_ref(ref: str) -> tuple[bool, str]:
    """Чи читається ref. Невідомість, а не мовчазний відкат на HEAD."""
    code, out, err = git_output(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"])
    if code != 0:
        return False, (err.strip() or f"{ref} не читається")
    return True, out.strip()


def main_has_path(ref: str, path: str) -> bool:
    """Чи є в дереві ref запис із такою назвою — файл або каталог."""
    code, _, _ = git_output(["cat-file", "-e", f"{ref}:{path}"])
    return code == 0


def main_matches_basename(ref: str, name: str) -> list[str]:
    """Шляхи в дереві ref, чий базовий імені дорівнює `name`.

    Потрібне для короткої форми: тіло issue пише `ci.yml`, а файл лежить у
    `.github/workflows/ci.yml`. Одне входження — це той самий файл; кілька —
    невідомість, бо вибір між ними був би здогадом.
    """
    code, out, _ = git_output(["ls-tree", "-r", "--name-only", ref])
    if code != 0:
        return []
    return sorted(line for line in out.splitlines() if line.rsplit("/", 1)[-1] == name)


def judge(candidate: Candidate, ref: str) -> Verdict:
    if "/" in candidate.path:
        exists = main_has_path(ref, candidate.path)
        if candidate.marked_new:
            if exists:
                return Verdict(
                    candidate,
                    1,
                    f"позначений `{NEW_MARKER}`, але в {ref} уже є — позначку зняти",
                )
            return Verdict(candidate, 0, f"немає в {ref} — позначка `{NEW_MARKER}` доречна")
        if exists:
            return Verdict(candidate, 0, f"є в {ref}")
        return Verdict(
            candidate,
            1,
            f"немає в {ref} — премиси немає; якщо тікет його створює, познач "
            f"`{NEW_MARKER}{candidate.path}`",
        )

    matches = main_matches_basename(ref, candidate.path)
    if candidate.marked_new:
        if matches:
            return Verdict(
                candidate,
                1,
                f"позначений `{NEW_MARKER}`, але в {ref} уже є: {matches[0]} — позначку зняти",
            )
        return Verdict(candidate, 0, f"немає в {ref} — позначка `{NEW_MARKER}` доречна")
    if len(matches) == 1:
        return Verdict(candidate, 0, f"є в {ref}: {matches[0]}")
    if len(matches) > 1:
        return Verdict(
            candidate,
            2,
            f"ім'я неоднозначне в {ref} ({len(matches)} входжень: "
            f"{', '.join(matches[:4])}{'…' if len(matches) > 4 else ''}) — назви повний шлях",
        )
    return Verdict(
        candidate,
        1,
        f"немає в {ref} ні за повним шляхом, ні за базовим іменем — премиси немає; "
        f"якщо тікет його створює, познач `{NEW_MARKER}{candidate.path}`",
    )


def check_body(body: str, ref: str) -> tuple[int, list[Verdict]]:
    ok, detail = resolve_main_ref(ref)
    if not ok:
        return 2, [
            Verdict(
                Candidate(path=ref, line_no=0, marked_new=False),
                2,
                f"{ref} не читається ({detail}) — зроби `git fetch origin main`; "
                "вердикт про невідомий ref був би здогадом",
            )
        ]
    found = candidates(body)
    if not found:
        return 2, [
            Verdict(
                Candidate(path="", line_no=0, marked_new=False),
                2,
                "у тілі не знайдено жодного шляху в бектиках — перевіряти нічого; "
                "це невідомість, а не зелене світло",
            )
        ]
    verdicts = [judge(candidate, ref) for candidate in found]
    worst = max((v.code for v in verdicts), default=0)
    return worst, verdicts


OUTCOME = {
    0: "ПРЕМИСА Є",
    1: "ВІДМОВА: премиси немає",
    2: "НЕВІДОМІСТЬ",
}


def format_report(ref: str, code: int, verdicts: list[Verdict]) -> str:
    lines = [f"issue_premise: ref {ref} — {OUTCOME[code]}", ""]
    for verdict in verdicts:
        where = f"рядок {verdict.candidate.line_no}: " if verdict.candidate.line_no else ""
        lines.append(f"  [{verdict.code}] {where}{verdict.candidate.display()} — {verdict.detail}")
    counts = {c: sum(1 for v in verdicts if v.code == c) for c in (0, 1, 2)}
    lines.append("")
    if any(v.candidate.path for v in verdicts):
        lines.append(
            f"шляхів перевірено: {len(verdicts)} — є в {ref}: {counts[0]}, "
            f"відмов: {counts[1]}, невідомість: {counts[2]}"
        )
    return "\n".join(lines)


def read_body(args: argparse.Namespace) -> str:
    if args.issue is not None:
        proc = subprocess.run(
            ["gh", "issue", "view", str(args.issue), "--json", "body", "--jq", ".body"],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SystemExit(
                f"issue_premise: не вдалося прочитати #{args.issue}: {proc.stderr.strip() or proc.returncode}"
            )
        return proc.stdout
    if args.body_file == "-":
        return sys.stdin.read()
    return Path(args.body_file).read_text(encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="issue_premise.py",
        description="Перевіряє, що кожен шлях із тіла issue існує в origin/main (або позначений NEW:).",
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--body-file", help="файл із тілом issue, або `-` для stdin")
    source.add_argument("--issue", type=int, help="номер issue: тіло читається через `gh issue view`")
    parser.add_argument(
        "--ref",
        default=MAIN_REF,
        help=f"ref, стан якого вважається станом main (типово {MAIN_REF})",
    )
    parser.add_argument("--quiet", action="store_true", help="друкувати лише підсумок і відмови")
    args = parser.parse_args(argv)

    body = read_body(args)
    code, verdicts = check_body(body, args.ref)
    if args.quiet:
        for verdict in verdicts:
            if verdict.code != 0:
                print(f"[{verdict.code}] {verdict.display()} — {verdict.detail}")
        print(f"issue_premise: {OUTCOME[code]} (ref {args.ref})")
    else:
        print(format_report(args.ref, code, verdicts))
    return code


if __name__ == "__main__":
    sys.exit(main())
