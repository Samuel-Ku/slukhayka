#!/usr/bin/env python3
"""Час джоби міряється ТАМ, ДЕ ЛІМІТ ДІЄ, і відтворюється командою (#1299).

Що було не так. Серія міряла час набору `scripts/tests` ЛОКАЛЬНО й діставала
159–239 с проти ліміту джоби `timeout-minutes: 5` (300 с). Звідси «запас 61 с
(20 %)» і розмови про підняття ліміту. Справжній час тієї самої джоби
(`Script unit tests`) на CI — 128–137 с на п'яти прогонах 2026-10-13/14, тобто
запас ≈2.2×, а не 20 %. Локальні числа не «трохи інші»: вони міряні на машині,
яка ділить RAM і CPU із сусідніми смугами (13/15 ГБ, load 5.4), тож міряють
навантаження машини, а не джобу. Запас міряли не на тій машині — і кожна смуга
серії чесно писала «час із тікета не відтворюється», не знаючи, чому.

Механізм робить дві речі, і обидві — дані, а не проза:

  * **міряє** джобу там, де ліміт діє. `gh api` віддає останні N завершених
    прогонів воркфлоу `WORKFLOW`, а для кожного — `started_at` і `completed_at`
    джоби, названої в `ci.yml`. Ліміт — не константа тут: `timeout-minutes`
    читається з ТОГО САМОГО блоку `ci.yml`, і звідти ж беруться імʼя джоби та
    команда, яку вона виконує. Число, яке не виводиться (немає `gh`, немає
    прогонів, джоби немає в прогоні, коду виходу `gh` не розібрано), —
    невідомість (2), а не «нуль»;
  * **тримає числа доку**. `CLAIMS` — рядки в дереві, які щось кажуть про час
    цієї джоби. Кожне число-секунда на такому рядку мусить бути ЛИБО лімітом,
    виведеним із `ci.yml`, ЛИБО частиною локального виміру, ПОЗНАЧЕНОГО разом
    із навантаженням машини. Замороженого числа джоби в доку бути не може: воно
    розійдеться з CI на наступному ж прогоні, і не почервоніє ніщо. Те, що не
    дрейфує, — ВІДНОШЕННЯ (`HEADROOM_RATIO`), і його міряє прогін.

Чому саме CI-число, а не локальне. `timeout-minutes` застосовується до джоби на
раннері, а `started_at`/`completed_at` з API — це ті самі дві точки, між якими
ліміт і діє. Локальний `python3 -m unittest` міряє інше: ту саму роботу ПЛЮС
конкуренцію за машину. Тому локальне число не сміє видаватися за час джоби:
`--local` друкує його РАЗОМ із навантаженням і прямо каже, що це не час джоби, а
правило `CLAIMS` приймає локальне число лише з цим позначенням. Межа — в коді:
непоміченого локального числа не приймає ні CLI, ні перевірка.

Межі названі тут, у коді, а не лише в звіті:

  * **вікно — останні `WINDOW` завершених прогонів, і це не «завжди».** Історія
    прогонів у GitHub обмежена, а API віддає вікно, а не всю історію; тому
    механізм питає `per_page` із запасом (`FETCH_SLACK`) і бере перші `WINDOW`
    ЗАВЕРШЕНИХ. Прогін, який ще біжить, у вікно не входить — інакше число
    джоби залежало б від того, коли його спитали. Чому саме пʼять: пʼяти
    вистачає, щоб побачити розкид, і задосить мало, щоб зелений день не
    виглядав як зелений тиждень. «Останні N» — не «завжди», і `--list` друкує
    N разом із причиною;
  * **міряється те, що записав GitHub.** Число — це `completed_at − started_at`
    тієї джоби, яку API назвав цим імʼям. Воно НЕ включає чергу до призначення
    раннера (її в цих двох точках немає) і не включає час ретраю: ретрай — це
    другий запис джоби з тим самим імʼям, і кілька таких записів механізм
    відмовляється вгадувати (невідомість, а не «взяли перший»);
  * **`gh` — це вимір, а не істина.** Механізм не має власної думки про те, що
    сталося на CI: він читає два поля. Тому «джоба зелена» тут не означає
    нічого — міряється ЧАС, а не вердикт прогону, і прогін, що впав на 130-й
    секунді, дасть те саме число, що й зелений;
  * **зарахування рядка — руками.** Жодне статичне правило не відрізняє
    «рядок, який щось стверджує про час джоби» від «рядка, де слово `с` — не
    секунда»; тому `CLAIMS` явний, як `FIGURES` і `GUARDS`, і питання огляду
    звужується до одного — «де його запис?». Незарахований рядок механізм не
    бачить, і цього не вдає;
  * **ліміт читається, а не правиться.** `ci.yml` механізм не чіпає: підняти
    `timeout-minutes` — рішення власника, а не ліки від червоного. Якщо ліміт
    змінити, числа в документах розійдуться з ним, і `--check-docs` це назве.

Коди виходу: 0 — час джоби міряно на N прогонах, відношення тримається, і кожне
число в документах виводиться; 1 — знахідка (прогін дійшов до ліміту або
перевищив його, відношення більше не тримається, число в доку не виводиться й
не позначене); 2 — перевірити не вдалося (немає `gh` або він не відповів,
немає завершених прогонів, джоби немає в прогоні, кілька джоб із тим імʼям,
`ci.yml` не розібрано, немає `timeout-minutes`, немає `origin`, файлу немає,
якір не один, рядок більше не несе числа, `/proc` не прочитано).
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Джоба, чий час міряється. Ключ — те, що стоїть у `ci.yml` перед двокрапкою;
# імʼя, ліміт і команду механізм читає з її ж блоку, а не пише вдруге.
WORKFLOW = ".github/workflows/ci.yml"
JOB_KEY = "script-tests"

# Скільки завершених прогонів входить у вікно і скільки зайвих питати в API,
# щоб незавершені прогони не зʼїли вікно. Обидва числа — межа, названа в шапці.
WINDOW = 5
FETCH_SLACK = 3

# Відношення, яке тримає доку: ліміт мусить бути не меншим за це число, помножене
# на найповільніший виміряний прогін. Це ПІДЛОГА, не прогноз: раннер, який
# уповільниться вдвічі, почервонить механізм — і це буде правдою про ліміт.
HEADROOM_RATIO = 2

# Рядки, які щось стверджують про час цієї джоби: (файл, якір рядка). Якір
# мусить траплятися у файлі рівно раз. Розширюють руками, як `FIGURES`.
CLAIMS = (
    ("docs/agents/local-environment.md",
     "is capped by"),
    ("CONTRIBUTING.md",
     "ліміт тієї ж джоби"),
)

# Число-секунда: `300 s`, `176 с`. Межі `(?<![\w.,])` не дають зійти за число
# хвосту десяткового дробу; `×` сюди не входить навмисно — відношення це не час.
SECONDS = re.compile(r"(?<![\w.,])(\d+(?:\.\d+)?)\s*(?:s|с)\b")

# Позначки локального виміру. Локальне число приймається лише тоді, коли на тому
# самому рядку названо І те, що воно локальне, І навантаження машини: без цього
# воно виглядає як час джоби — а це рівно те, за що заплатила серія #1299.
LOCAL_MARK = ("local", "локальн")
LOAD_MARK = ("load", "навантаженн")

# Те, що `--local` виконує: команда береться з блоку джоби в `ci.yml`. Якщо її
# там немає або вона не одна — локальний вимір неможливий (невідомість), а не
# «схожа команда».
LOCAL_TIMEOUT_SECONDS = 1800


class JobTimeError(Exception):
    """Те, що механізм не розібрав. Невідомість, а не «час джоби є»."""


class Measurement:
    """Один виміряний прогін: номер, коли почався, секунди джоби."""

    def __init__(self, run_id: str, created_at: str, seconds: int):
        self.run_id = run_id
        self.created_at = created_at
        self.seconds = seconds


# --------------------------------------------------------------------------
# ci.yml: імʼя джоби, ліміт і команда — з одного блоку
# --------------------------------------------------------------------------


def job_block(text: str, key: str = JOB_KEY) -> list[str]:
    """Рядки блоку джоби `key` у воркфлоу.

    Ключі джобів стоять на двох пробілах, їхні поля — на чотирьох, тому кінець
    блоку — перший непорожній рядок, відступ якого менший за чотири пробіли.
    Коментарі на рівні ключів (їх у `ci.yml` багато) блоком не вважаються.
    """
    lines = text.splitlines()
    hits = [index for index, line in enumerate(lines) if line.rstrip() == "  %s:" % key]
    if len(hits) != 1:
        raise JobTimeError(
            "джоба %r трапляється у воркфлоу %d раз(и), а мусить рівно раз"
            % (key, len(hits))
        )
    block: list[str] = []
    for line in lines[hits[0] + 1:]:
        if line.strip() and not line.startswith("  #") and not line.startswith("    "):
            break
        block.append(line)
    return block


def job_field(block: list[str], name: str) -> str:
    """Поле джоби, оголошене рівно на чотирьох пробілах (не крок і не матриця)."""
    pattern = re.compile(r"^    %s:\s*(\S.*?)\s*$" % re.escape(name))
    found = [match.group(1) for line in block if (match := pattern.match(line))]
    if len(found) != 1:
        raise JobTimeError(
            "поле %r оголошене %d раз(и), а мусить рівно раз" % (name, len(found))
        )
    return found[0]


def limit_seconds(root: Path = ROOT, workflow: str = WORKFLOW,
                  key: str = JOB_KEY) -> int:
    """Ліміт джоби в секундах — із `timeout-minutes`, а не з константи тут."""
    path = root / workflow
    if not path.is_file():
        raise JobTimeError("немає %s — ліміту не взяти" % workflow)
    try:
        minutes = int(job_field(job_block(path.read_text(encoding="utf-8"), key),
                                "timeout-minutes"))
    except ValueError:
        raise JobTimeError("timeout-minutes у блоці %s — не ціле число" % key)
    if minutes <= 0:
        raise JobTimeError("timeout-minutes у блоці %s — %d" % (key, minutes))
    return minutes * 60


def job_name(root: Path = ROOT, workflow: str = WORKFLOW, key: str = JOB_KEY) -> str:
    """Імʼя джоби, яким її називає API, — теж із блоку, а не з прози."""
    return job_field(job_block((root / workflow).read_text(encoding="utf-8"), key), "name")


def local_command(root: Path = ROOT, workflow: str = WORKFLOW,
                  key: str = JOB_KEY) -> tuple[str, ...]:
    """Команда, яку виконує джоба, — з `run:` її блоку.

    Кілька `run:` — невідомість: джоба з двома кроками міряється не однією
    командою, і взяти першу означало б міряти не те, що міряє CI.
    """
    block = job_block((root / workflow).read_text(encoding="utf-8"), key)
    runs = [line.strip()[len("run:"):].strip() for line in block
            if re.match(r"^\s*run:\s*\S", line)]
    if len(runs) != 1:
        raise JobTimeError(
            "кроків `run:` у блоці %s — %d, а локальний вимір мусить знати рівно "
            "один" % (key, len(runs))
        )
    return tuple(shlex.split(runs[0]))


# --------------------------------------------------------------------------
# Вимір: gh api, останні N завершених прогонів
# --------------------------------------------------------------------------


def gh_executable() -> str:
    """Шлях до `gh`; його відсутність — невідомість, а не «нуль прогонів»."""
    found = shutil.which("gh")
    if found is None:
        raise JobTimeError(
            "немає `gh` — час джоби міряється на CI, і взяти його більше нізвідки"
        )
    return found


def repository(root: Path = ROOT) -> str:
    """`owner/name` із `origin` цього дерева — щоб не питати не той репозиторій."""
    git = shutil.which("git")
    if git is None:
        raise JobTimeError("немає `git` — репозиторій із `origin` не взяти")
    result = subprocess.run(
        [git, "-C", str(root), "remote", "get-url", "origin"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise JobTimeError("немає `origin`: %s" % result.stderr.strip())
    match = re.search(r"github\.com[:/]([\w.-]+/[\w.-]+?)(?:\.git)?$", result.stdout.strip())
    if match is None:
        raise JobTimeError("`origin` не з github.com: %r" % result.stdout.strip())
    return match.group(1)


def gh_api(endpoint: str, gh: str | None = None) -> dict:
    """Один виклик `gh api` — і чесна відмова, коли він не віддав JSON."""
    executable = gh or gh_executable()
    try:
        result = subprocess.run(
            [executable, "api", endpoint], capture_output=True, text=True, check=False,
        )
    except OSError as error:
        raise JobTimeError("`gh api %s` не вдався: %s" % (endpoint, error))
    if result.returncode != 0:
        raise JobTimeError(
            "`gh api %s` вийшов із кодом %d: %s"
            % (endpoint, result.returncode, "\n".join(result.stderr.strip().splitlines()[-3:]))
        )
    try:
        return json.loads(result.stdout)
    except ValueError as error:
        raise JobTimeError("`gh api %s` віддав не JSON: %s" % (endpoint, error))


def timestamp(value: object, where: str) -> datetime.datetime:
    if not isinstance(value, str) or not value:
        raise JobTimeError("%s: часу немає (%r)" % (where, value))
    try:
        return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise JobTimeError("%s: час не розібрано (%r)" % (where, value))


def completed_runs(repo: str, window: int, gh: str | None = None) -> list[dict]:
    """Останні завершені прогони воркфлоу — не більше `window`, і не менше."""
    workflow = Path(WORKFLOW).name
    payload = gh_api(
        "repos/%s/actions/workflows/%s/runs?per_page=%d"
        % (repo, workflow, window * (1 + FETCH_SLACK)),
        gh,
    )
    runs = payload.get("workflow_runs")
    if not isinstance(runs, list):
        raise JobTimeError("відповідь API не має `workflow_runs`")
    done = [run for run in runs if run.get("status") == "completed"]
    if len(done) < window:
        raise JobTimeError(
            "завершених прогонів у вікні — %d, а треба %d: число джоби взяти нема з чого"
            % (len(done), window)
        )
    return done[:window]


def measured(entry: dict, name: str, repo: str, gh: str | None = None) -> Measurement:
    """Час однієї джоби в одному прогоні: `completed_at − started_at`."""
    run_id = str(entry.get("id"))
    payload = gh_api("repos/%s/actions/runs/%s/jobs?per_page=100" % (repo, run_id), gh)
    jobs = payload.get("jobs")
    if not isinstance(jobs, list):
        raise JobTimeError("прогін %s: відповідь API не має `jobs`" % run_id)
    found = [job for job in jobs if job.get("name") == name]
    if not found:
        raise JobTimeError(
            "прогін %s: джоби %r немає — час не взяти" % (run_id, name)
        )
    if len(found) > 1:
        raise JobTimeError(
            "прогін %s: джоб із імʼям %r — %d (ретрай або матриця); яку міряти, "
            "механізм не вгадує" % (run_id, name, len(found))
        )
    job = found[0]
    started = timestamp(job.get("started_at"), "прогін %s: started_at" % run_id)
    finished = timestamp(job.get("completed_at"), "прогін %s: completed_at" % run_id)
    seconds = int((finished - started).total_seconds())
    if seconds < 0:
        raise JobTimeError("прогін %s: completed_at раніше за started_at" % run_id)
    return Measurement(run_id, str(entry.get("created_at") or ""), seconds)


# --------------------------------------------------------------------------
# Числа в документах: ліміт або позначений локальний вимір
# --------------------------------------------------------------------------


def line_with_anchor(text: str, anchor: str, where: str) -> tuple[int, str]:
    """Рядок, у якому якір трапляється рівно один раз (як у `doc_numbers`)."""
    if text.count(anchor) != 1:
        raise JobTimeError(
            "%s: якір мусить траплятися рівно раз, а трапляється %d: %r"
            % (where, text.count(anchor), anchor)
        )
    number = text.count("\n", 0, text.index(anchor)) + 1
    return number, text.splitlines()[number - 1]


def labelled_local(line: str) -> bool:
    """Чи позначений рядок як локальний вимір — і чи названо навантаження."""
    low = line.lower()
    return (any(mark in low for mark in LOCAL_MARK)
            and any(mark in low for mark in LOAD_MARK))


def claim_findings(root: Path = ROOT, claims=CLAIMS,
                   limit: int | None = None) -> tuple[list[str], list[str]]:
    """Що механізм побачив у зарахованих рядках: (знахідки, невідомості).

    Кожне число-секунда на рядку мусить бути або лімітом із `ci.yml`, або
    частиною позначеного локального виміру. Це не «причіпка до цифри»: число
    джоби, заморожене в доку, розходиться з CI на наступному прогоні, і ніщо не
    червоніє — саме тому в доку живе ліміт (він з дерева) і відношення (його
    міряє прогін), а не секунди.
    """
    findings: list[str] = []
    unknowns: list[str] = []
    for relative, anchor in claims:
        path = root / relative
        if not path.is_file():
            unknowns.append("немає файлу: %s" % relative)
            continue
        try:
            number, line = line_with_anchor(path.read_text(encoding="utf-8"), anchor, relative)
        except JobTimeError as error:
            unknowns.append(str(error))
            continue
        figures = SECONDS.findall(line)
        if not figures:
            unknowns.append(
                "%s:%d: рядок більше не несе числа-секунди — запис застарів" % (relative, number)
            )
            continue
        if limit is None:
            try:
                limit = limit_seconds(root)
            except JobTimeError as error:
                unknowns.append(str(error))
                continue
        for figure in figures:
            if int(float(figure)) == limit or labelled_local(line):
                continue
            findings.append(
                "%s:%d — рядок каже %s с, а це ні ліміт джоби (%d с із %s), ні "
                "позначений локальний вимір (немає названого навантаження): число "
                "джоби в доку розійдеться з CI, і не почервоніє ніщо. Лиши ліміт "
                "або познач локальне число навантаженням машини"
                % (relative, number, figure, limit, WORKFLOW)
            )
    return findings, unknowns


# --------------------------------------------------------------------------
# Локальний вимір: число, яке НЕ сміє видаватися за час джоби
# --------------------------------------------------------------------------


def machine_facts(proc: Path = Path("/proc")) -> tuple[float, float, float]:
    """Навантаження й памʼять машини — те, чим локальний вимір мусить бути названий."""
    try:
        load = float((proc / "loadavg").read_text(encoding="utf-8").split()[0])
        memory = (proc / "meminfo").read_text(encoding="utf-8")
    except (OSError, ValueError, IndexError) as error:
        raise JobTimeError("навантаження машини не прочитано: %s" % error)
    total = available = 0.0
    for line in memory.splitlines():
        if line.startswith("MemTotal:"):
            total = int(line.split()[1]) / 1048576
        elif line.startswith("MemAvailable:"):
            available = int(line.split()[1]) / 1048576
    if total <= 0:
        raise JobTimeError("MemTotal не прочитано — локальний вимір не назвати")
    return load, available, total


def local_label(seconds: float, load: float, available_gb: float, total_gb: float) -> str:
    """Локальний вимір РАЗОМ із навантаженням — і з прямою відмовою зватися часом джоби.

    Цей рядок — не прикраса: `labelled_local` приймає рівно таку форму, тож
    єдиний спосіб провести локальне число в доку — провести його разом із
    навантаженням. Форма позначки — дані (`LOCAL_MARK`, `LOAD_MARK`), а не
    побажання до автора.
    """
    return (
        "ЛОКАЛЬНО %.1f с на цій машині під навантаженням %.2f "
        "(RAM %.1f/%.1f ГБ) — це НЕ час джоби: ліміт діє на раннері, і міряє його "
        "`python3 scripts/ci_job_time.py`" % (seconds, load, available_gb, total_gb)
    )


def local_measurement(command: tuple[str, ...], runner=subprocess.run,
                      now=time.monotonic) -> float:
    """Скільки триває команда джоби ТУТ. Число без позначки назовні не виходить."""
    started = now()
    try:
        result = runner(list(command), capture_output=True, text=True,
                        check=False, timeout=LOCAL_TIMEOUT_SECONDS)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise JobTimeError("локальний прогін не вдався: %s" % error)
    if result.returncode != 0:
        raise JobTimeError(
            "локальний прогін вийшов із кодом %d — міряти час невдалого прогону "
            "немає сенсу:\n%s" % (result.returncode, "\n".join(result.stderr.strip().splitlines()[-3:]))
        )
    return now() - started


# --------------------------------------------------------------------------
# Звіт
# --------------------------------------------------------------------------


def headroom(limit: int, seconds: int) -> tuple[int, float, float]:
    """Запас, його частка від ліміту й відношення ліміту до часу."""
    spare = limit - seconds
    return spare, spare * 100.0 / limit, (limit / seconds if seconds else float("inf"))


def measurement_lines(name: str, limit: int, runs: list[Measurement]) -> list[str]:
    """Рядок на прогін плюс підсумок: час, запас, відношення."""
    lines = []
    for run in runs:
        spare, percent, ratio = headroom(limit, run.seconds)
        lines.append(
            "  #%s  %s  %d с · запас %d с (%.0f %%) · %.2f×"
            % (run.run_id, run.created_at or "?", run.seconds, spare, percent, ratio)
        )
    times = sorted(run.seconds for run in runs)
    slowest = times[-1]
    median = times[len(times) // 2]
    spare, percent, ratio = headroom(limit, slowest)
    lines.append(
        "  разом: %d прогонів, найповільніший %d с, медіана %d с, найшвидший %d с"
        % (len(runs), slowest, median, times[0])
    )
    lines.append(
        "  запас найповільнішого: %d с (%.0f %%), %.2f× — підлога %.1f× %s"
        % (spare, percent, ratio, HEADROOM_RATIO,
           "тримається" if ratio >= HEADROOM_RATIO else "БІЛЬШЕ НЕ ТРИМАЄТЬСЯ")
    )
    return lines


def verdict(runs: list[Measurement], limit: int) -> list[str]:
    """Що з виміряного — знахідка, а не зелень."""
    findings = []
    for run in runs:
        if run.seconds >= limit:
            findings.append(
                "прогін #%s — %d с проти ліміту %d с: джоба дійшла до ліміту або "
                "перевищила його (запас %d с)"
                % (run.run_id, run.seconds, limit, limit - run.seconds)
            )
    slowest = max(run.seconds for run in runs)
    if limit < HEADROOM_RATIO * slowest:
        findings.append(
            "відношення, назване в документах, більше не тримається: ліміт %d с "
            "менший за %.1f× від найповільнішого прогону (%d с). Або число в доку "
            "переміряти, або ліміт підняти — і те, й те вирішує не механізм"
            % (limit, HEADROOM_RATIO, slowest)
        )
    return findings


def listing(root: Path = ROOT, window: int = WINDOW) -> list[str]:
    """Що механізм покриває — без `gh` і без мережі."""
    lines = [
        "джоба: %s (`%s`)" % (JOB_KEY, WORKFLOW),
        "вікно: останні %d завершених прогонів (не «завжди» — історія API "
        "обмежена, і `--runs` його рухає)" % window,
    ]
    for what, value in (("імʼя", lambda: job_name(root)),
                        ("ліміт", lambda: "%d с" % limit_seconds(root)),
                        ("команда", lambda: " ".join(local_command(root)))):
        try:
            lines.append("%s: %s" % (what, value()))
        except (JobTimeError, OSError) as error:
            lines.append("%s: НЕВІДОМО (%s)" % (what, error))
    lines.append("підлога запасу: %.1f× від найповільнішого прогону" % HEADROOM_RATIO)
    for relative, anchor in CLAIMS:
        lines.append("зарахований рядок: %s — «%s»" % (relative, anchor))
    return lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Час джоби `script-tests` там, де діє її ліміт (CI), і числа "
                    "про нього в документах.",
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="корінь дерева")
    parser.add_argument("--runs", type=int, default=WINDOW,
                        help="скільки останніх завершених прогонів міряти (типово %d)"
                             % WINDOW)
    parser.add_argument("--repo", default=None,
                        help="owner/name; типово — `origin` цього дерева")
    parser.add_argument("--check-docs", action="store_true",
                        help="лише числа в документах: без `gh` і без мережі")
    parser.add_argument("--local", action="store_true",
                        help="зміряти команду джоби ТУТ і надрукувати її "
                             "ПОЗНАЧЕНОЮ (навантаження й памʼять машини)")
    parser.add_argument("--list", action="store_true",
                        help="лише назвати джобу, ліміт, вікно й зараховані рядки")
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()

    if arguments.runs < 1:
        print("НЕВІДОМО: --runs %d — вікно мусить бути додатним" % arguments.runs,
              file=sys.stderr)
        return 2

    if arguments.list:
        for line in listing(root, arguments.runs):
            print(line)
        return 0

    findings, unknowns = claim_findings(root, CLAIMS)

    if arguments.local:
        try:
            command = local_command(root)
            seconds = local_measurement(command)
            load, available, total = machine_facts()
        except JobTimeError as error:
            print("НЕВІДОМО: %s" % error, file=sys.stderr)
            for finding in findings:
                print(finding)
            return 2
        print(local_label(seconds, load, available, total))

    elif not arguments.check_docs:
        try:
            repo = arguments.repo or repository(root)
            name = job_name(root)
            limit = limit_seconds(root)
            runs = [measured(entry, name, repo)
                    for entry in completed_runs(repo, arguments.runs)]
        except JobTimeError as error:
            print("НЕВІДОМО: %s" % error, file=sys.stderr)
            for finding in findings:
                print(finding)
            return 2
        print("джоба: %s (`%s` у %s)" % (name, JOB_KEY, WORKFLOW))
        print("ліміт: %d с" % limit)
        print("вікно: останні %d завершених прогонів" % arguments.runs)
        for line in measurement_lines(name, limit, runs):
            print(line)
        findings.extend(verdict(runs, limit))

    for finding in findings:
        print(finding, file=sys.stderr)
    for unknown in unknowns:
        print("НЕВІДОМО: %s" % unknown, file=sys.stderr)
    if unknowns:
        return 2
    if findings:
        return 1
    if arguments.check_docs:
        print("OK    числа про час джоби в документах виводяться: ліміт — із %s, "
              "локальні числа позначені навантаженням" % WORKFLOW)
    else:
        print("OK    час джоби міряно на %d прогонах; зараховані рядки виводяться"
              % arguments.runs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
