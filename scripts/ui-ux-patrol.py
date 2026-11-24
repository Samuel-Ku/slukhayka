#!/usr/bin/env python3
"""Нічний UI/UX-патруль: агент-аудит, який щодня створює готові до роботи тікети.

Навіщо. У репозиторії вже є нічний сторож порядку в гілках
(`scripts/branch-hygiene.py`) і ручні UI-аудити (`docs/audits/2026-09-06-*`).
Але знахідки про інтерфейс і логіку екранів народжувались лише в сесіях
людини: між ними ніхто не перевіряв, чи екран досі тримає власні ADR
(ADR-0018/0033/0044/0049/0058), чи порожній/помилковий стан не зник, чи
web-клієнт не розійшовся з Android (ADR-0034). Патруль закриває саме цю
прогалину — **аудит, а не реалізація**: він не править код, він оформлює
знахідки так, щоб їх могла взяти смуга сварму (`ready-for-agent`).

Обмеження, названі прямо:

  * запуск — локальний `systemd --user` таймер на hub, а не GitHub Actions:
    агентові потрібен справжній репозиторій, `gh` і модель, а не лише
    статичні факти;
  * патруль не змінює `app/`, `web/`, `docs/` і не створює PR — після прогону
    робоче дерево мусить бути чистим, інакше прогін — порушення контракту;
  * ≤ `MAX_TICKETS` нових тікетів за прогін, решта знахідок — у звіті;
  * один rolling звіт-issue (маркер `<!-- ui-ux-patrol-report -->`), а не новий
    issue щоночі;
  * дедуп — за маркером `<!-- ui-ux-patrol: key=<тема>/<slug> -->` у тілі
    тікета; ключі з відкритих тікетів передаються агенту, щоб він не відкривав
    те саме вдруге;
  * ротація теми дня: сім тем по колу, щоб агент щоночі йшов углиб, а не
    повторював той самий поверхневий прохід.

Підкоманди:

    install   профіль headless, мітка, env-файл, systemd --user юніти, smoke
    run       нічний прогін (таймер викликає саме його)
    dry-run   той самий прогін, але агенту заборонено створювати тікети
    status    що налаштовано, коли наступний прогін, останній результат

Скрипт самодостатній (лише stdlib): systemd виконує його як
`git show origin/main:scripts/ui-ux-patrol.py | python3 - run --repo <шлях>`,
тож головне дерево мейнтейнера може стояти на будь-якій гілці, а таймер бере
код із `origin/main`. Промпт агента живе тут-таки, щоб не розходитися з
механізмом, який його виконує.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import textwrap
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from string import Template

REPO_URL = "https://github.com/Samuel-Ku/slukhayka.git"
LABEL = "ui-ux-patrol"
REPORT_MARKER = "<!-- ui-ux-patrol-report -->"
TICKET_MARKER_RE = re.compile(r"<!--\s*ui-ux-patrol:\s*key=([^\s]+)\s*-->")
# Агент часто форматує контрактний рядок як `code` або маркер списку — це не
# привід валити ніч: рядок усе одно машиночитний, тож дозволяємо обгортку.
RESULT_RE = re.compile(
    r"^[ \t]*(?:[`>*+-][ \t]*)*UI-UX-PATROL-RESULT:\s*"
    r"(ok-partial|ok|blocked)\b(.*)$",
    re.MULTILINE,
)
# `created=` читається до `report=` або кінця рядка, а не до першого пробілу:
# `#12, #13` — такий самий валідний список, як `#12,#13`, і формат рядка не
# привід валити ніч (той самий клас, що backtick-обгортка).
CREATED_REF_RE = re.compile(r"\bcreated=(.*?)(?=[,\s]+report=|\s*$)", re.MULTILINE)
REPORT_REF_RE = re.compile(r"\breport=(#[0-9]+|none)\b")
ISSUE_NUMBER_RE = re.compile(r"#?(\d+)")
MAX_TICKETS = 5
DEFAULT_TIMEOUT_MIN = 60
KEEP_LOGS = 14
ON_CALENDAR = "*-*-* 03:20:00"
WORKTREE_NAME = "ui-ux-patrol"
PROFILE_MARKER = "# Керується scripts/ui-ux-patrol.py"

# Порядок ротації: день за порядковим номером дати, тож кожна тема випадає
# приблизно раз на тиждень і прогін не залежить від дня тижня.
FOCUSES = (
    {
        "key": "listen",
        "title": "«Слухати»: вісім полішок, порядок, mini-player",
        "brief": (
            "Екран «Слухати»: вісім полішок користувача (ADR-0015), «Керувати "
            "полицями», заголовки блоків, порожні полиці, mini-player chrome, "
            "взаємодія з рейлом/нижньою навігацією."
        ),
    },
    {
        "key": "library",
        "title": "Медіатека: картки, чипси, пошук, порожні стани",
        "brief": (
            "Медіатека: PosterCard/BookRow/MetadataChip (ADR-0033), фільтри й "
            "чипси, пошук, групування локальної папки (ADR-0057), порожні та "
            "помилкові стани, доступність джерела (ADR-0042)."
        ),
    },
    {
        "key": "book-player",
        "title": "Сторінка книги, плеєр, розділи, Smart Rewind",
        "brief": (
            "Сторінка книги й плеєр: розділи, Smart Rewind (ADR-0003), стан "
            "play/pause відносно рушія, mini-player, продовження з позиції, "
            "таймери, семантика транспорту."
        ),
    },
    {
        "key": "discovery",
        "title": "Огляд, каталог, добірки, пошук, серії",
        "brief": (
            "Огляд і каталог: добірки (ADR-0012/0013), пошук по всіх джерелах "
            "(ADR-0040), рекомендації, рядки AllBooks, серії та всесвіти "
            "(ADR-0025), порожні стани й чесність часткових результатів."
        ),
    },
    {
        "key": "settings-a11y",
        "title": "Налаштування, доступність, локалі, навігація",
        "brief": (
            "Налаштування й доступність: Talkback-чеклист "
            "(`docs/accessibility/talkback-release-checklist.md`), touch-target "
            "24/48 dp (ADR-0044), локалі інтерфейсу й мови контенту (ADR-0029), "
            "сповіщення (ADR-0052), системне «назад» і клавіатура (ADR-0058), "
            "чотири корені та шестерня (ADR-0049)."
        ),
    },
    {
        "key": "social-community",
        "title": "Відгуки, оцінки, досягнення, надсилання, спільнота",
        "brief": (
            "Відгуки й оцінки (ADR-0022/0023), досягнення (ADR-0060), "
            "надсилання YouTube/Telegram (ADR-0028/0035), спільні добірки "
            "слухачів (ADR-0012), модерація й чесні стани очікування."
        ),
    },
    {
        "key": "web",
        "title": "Web-клієнт: UI, доступність, паритет із Android",
        "brief": (
            "Web-клієнт (`web/`): паритет із Android (ADR-0034), доступність "
            "(aria, focus, клавіатура), i18n, адаптивність, веб-плеєр володіє "
            "позицією (ADR-0059), синхронізація прогресу (ADR-0051)."
        ),
    },
)

PROMPT_TEMPLATE = Template(
    """\
Ти — нічний UI/UX-патруль репозиторію «Слухайка». Працюєш автономно, без
людини поруч: `ask_user_question` недоступний, рішення ухвалюй сам і записуй
їх у звіті. Твоя робота — **знайти й оформити** дефекти та місця для
покращення, а не виправити їх.

# Контекст прогону

- Дата: $DATE (UTC)
- Тема дня: **$FOCUS_TITLE**
- Репозиторій: `$REPO` (detached `origin/main`, свіжий; це робоча копія патруля)
- Стеля нових тікетів: **$MAX_TICKETS**
- Режим: $MODE
$EXTRA_CONTEXT

# Що читати обов'язково (перед будь-якою знахідкою)

1. `AGENTS.md`, `CONTRIBUTING.md`; якщо в дереві є `CODING_STANDARDS.md` — і його.
2. `GLOSSARY.md` або `CONTEXT.md` — домовність (Work/Edition/Source/Library
   Entry тощо); назви в тікеті мусять бути глосарними.
3. `docs/adr/0014-ux-rules-from-the-four-review-synthesis.md`,
   `docs/adr/0018-ui-component-taxonomy.md`,
   `docs/adr/0033-v14-consistency-baseline-components-ship-as-written.md`,
   `docs/adr/0044-touch-target-floor-24dp.md`,
   `docs/adr/0049-four-root-sections-settings-behind-the-gear.md`,
   `docs/adr/0058-system-back-and-keyboard-priority.md`; для теми `web` —
   `docs/adr/0034-web-client-parity-baseline.md`,
   `docs/adr/0051-web-progress-sync-after-deliberate-linking.md`,
   `docs/adr/0059-web-media-owns-position.md`.
4. Тематичні ADR із теми дня (наприклад `0015`, `0012/0013`, `0003`, `0029`,
   `0052`, `0060`) і дотичні `docs/specs/*`.
5. `docs/accessibility/talkback-release-checklist.md`.
6. `docs/audits/*` — там уже проведені ручні аудити; не перевідкривай
   зафіксовані там рішення й відомі межі (`ADR-0017`: видимі дельти, не
   рішення-документи).

# Що шукати

Спершу — тема дня, потім універсальні інваріанти. Темою дня не обмежуйся,
якщо поруч видно грубший дефект.

## Тема дня: $FOCUS_TITLE

$FOCUS_BRIEF

## Універсальні інваріанти (перевіряй щоночі)

- **Один словник компонентів.** ADR-0018/0033: `PosterCard`, `CycleCard`,
  `BookRow`, `MetadataChip`, дворівневі заголовки, канонічні порожні/завантажні
  стани. Шукай паралельні реалізації (`fun .*Card`, `fun .*Row`, `fun .*Header`,
  `fun .*Chip`), п'ять стилів заголовків, дублікати чипсів — і назви, які
  розійшлися з каноном.
- **Токени, не магічні числа.** Кольори поза `ui/theme` (`Color(0x…`), розміри
  з повітря замість `AppDimens`, відступи `120.dp` замість
  `SpaceAboveMiniPlayer`; для web — CSS-токени замість hardcoded значень.
- **Touch-target.** ADR-0044: підлога 24 dp для щільного хрому, 48 dp для
  транспорту/навігації; `LocalMinimumInteractiveComponentSize` без свідомого
  рішення; клікабельні елементи без мінімальної площі.
- **Доступність.** `contentDescription` для іконкових дій (null — лише
  декоративне поруч із текстом), семантика станів, порядок фокуса, контраст,
  Talkback-чеклист; у web — aria, role, focus-visible, клавіатурна навігація.
- **Стани екранів.** Кожен екран, що читає дані, має завантаження / порожньо /
  помилку; знайди екран без одного з них або з шістьма різними варіантами.
- **Логіка, а не лише вигляд.** Неузгоджений стан (кнопка «грає» після паузи —
  приклад #1413), застарілий стан після навігації, `LaunchedEffect` без
  потрібного ключа, проковтнуті помилки (`catch (_: Exception) {}`), зашиті
  ліміти/таймери замість констант, гонки видимі з коду.
- **i18n.** Захардкоджені рядки в Android UI замість `strings.xml` (ADR-0033:
  EN-рядки — частина v1.4), неповні `values-en`/`values-pl`; у web — рядки поза
  i18n. `app/lint-baseline.xml` рости не мусить.
- **Паритет web/Android.** ADR-0034: ті самі поняття й підписи; розбіжність
  без ADR — знахідка.
- **Правдивість даних.** ADR-0014: не вигадані значення; частковий результат —
  чесний стан, а не привід для «фіксу».

# Як перевіряти знахідку (обов'язково)

1. **Доказ із коду:** точний `file:line` і що саме там видно. Без `file:line`
   знахідка не існує.
2. **Правило:** який ADR/спека/чеклист це порушує (посилання на файл).
3. **Ефект для слухача:** що людина побачить/втратить; для логіки — сценарій
   кроків, які ведуть до дефекту.
4. **Перевірка на дубль:** `gh issue list --state all --search "<ключові слова>"`
   і `gh issue list --label ui-ux-patrol --state all --json number,title,body`.
   Знайдені ключі вже відкритих тікетів передані нижче — не відкривай те саме.
5. **Перевірка на «вже вирішено»:** `docs/audits/`, `docs/specs/`, ADR
   `Rejected alternatives` — якщо це свідоме рішення, не відкривай тікет,
   запиши в звіт «перевірено, свідомо».
6. **Перевірка преміси:** якщо код уже виправлено в `origin/main` (ти працюєш
   на ньому) — знахідки немає. Не покладайся на пам'ять і старі issue.

# Формат тікета (створи через `gh issue create`)

Заголовок — українською, конкретний, без «покращити UI»:

```
<що не так> — <де/для кого>
```

Тіло — рівно ці розділи:

```markdown
## Що треба

<1–2 речення: дефект або покращення і для кого>

## Доказ

- `path/to/File.kt:123` — <що видно>
- `docs/adr/00XX-….md` — <яке правило порушено>
- <для логіки: кроки/стан, які ведуть до дефекту>

## Що зробити

1. <конкретна дія>
2. <конкретна дія>

## Приймальні критерії

- <як перевірити, що готово>

## Межі

- <чого не чіпати>

<!-- ui-ux-patrol: key=$FOCUS_KEY/<slug> -->
```

Мітки: `--label ready-for-agent --label ui-ux-patrol` і одна з `bug`
(дефект) або `enhancement` (покращення). `ready-for-agent` означає «спека
повна, агент може взяти без питань» — якщо не впевнений, не став цю мітку й
запиши кандидата у звіт.

# Звіт-issue (rolling, один на всі ночі)

Знайди відкритий issue з маркером `<!-- ui-ux-patrol-report -->`:

```sh
gh issue list --label ui-ux-patrol --state open --json number,title,body
```

- якщо є — **перепиши тіло** через `gh issue edit <номер> --body-file`,
  зберігши маркер першим рядком;
- якщо немає — створи `gh issue create --label ui-ux-patrol` із заголовком
  `UI/UX-патруль — нічний звіт`.

Тіло звіту:

```markdown
<!-- ui-ux-patrol-report -->
# UI/UX-патруль — нічний звіт

Оновлено: $DATE · тема: $FOCUS_TITLE · режим: $MODE

## Що перевірено

- <файли/екрани/спеки, які справді прочитано>

## Створені тікети

- #<номер> — <назва> (або «немає»)

## Кандидати без тікета

- <знахідка + чому не тікет: мало доказів / вже відомо / свідоме рішення>

## Залишкові питання

- <що лишилося неперевіреним і чому>
```

# Заборони

- Не змінюй жодного файлу в репозиторії: ні `app/`, ні `web/`, ні `docs/`.
  Робоче дерево після тебе мусить бути чистим (`git status --porcelain`).
- Не створюй гілок, комітів, PR; не роби `git checkout`, `reset`, `clean`.
- Не закривай, не редагуй і не перепризначай чужі тікети.
- Не запускай важкі Gradle-збірки, емулятор та instrumented-тести. Дозволено:
  читання, `grep`/`rg`, `gh`, швидкі `npx vitest run` для web (≤2 хв).
- Не вигадуй фактів: якщо не перевірив — пиши «не перевірено».
- Не більше $MAX_TICKETS нових тікетів. Менше — нормально, нуль — теж
  нормальний результат, якщо знахідок немає.

# Бюджет часу

Ціль — 30–45 хвилин. Якщо після 45 хвилин лишились лише сумнівні кандидати —
завершуй: краще нуль тікетів і чесний звіт, ніж шум. Закінчи рядком рівно
такого вигляду (його читає раннер):

`UI-UX-PATROL-RESULT: ok|ok-partial|blocked created=<#12,#13|none> report=<#5|none> — <одне речення>`

У `created` переліч **номери** всіх створених тікетів, а не лише кількість:
раннер перевіряє саме ці issues, тож чужі issues, створені в ту саму годину,
не впливають на вердикт. `report` — номер звіту, який ти оновив (або `none`).

# Уже відомі ключі

Відкриті — не дублюй:

$KNOWN_KEYS

Закриті — можна відкрити знову лише як явну регресію (назви це в тілі й
постав новий slug; ключ може збігтися):

$CLOSED_KEYS
"""
)


class PatrolError(RuntimeError):
    """Помилка налаштування або прогону, яку раннер показує людиною."""


# ---------------------------------------------------------------------------
# Дрібні чисті функції: їх пінять тести без мережі, git і systemd
# ---------------------------------------------------------------------------


def focus_for(day: datetime) -> dict:
    """Тема дня: детермінована ротація за порядковим номером дати."""
    return FOCUSES[day.toordinal() % len(FOCUSES)]


def split_known_keys(issues: list[dict]) -> tuple[dict[str, int], dict[str, int]]:
    """Ключі відкритих і закритих тікетів окремо (межа з runbook).

    Відкритий ключ блокує новий тікет: це той самий дефект, який уже в роботі.
    Закритий не блокує: регресія після фіксу — законна нова робота, інакше
    патруль або валить прогін, або змушений вигадувати новий ключ для того
    самого дефекту.
    """
    open_keys: dict[str, int] = {}
    closed_keys: dict[str, int] = {}
    for issue in issues:
        state = str(issue.get("state") or "").upper()
        number = int(issue.get("number") or 0)
        for match in TICKET_MARKER_RE.finditer(issue.get("body") or ""):
            bucket = open_keys if state == "OPEN" else closed_keys
            bucket.setdefault(match.group(1), number)
    return open_keys, closed_keys


def is_report_issue(issue: dict) -> bool:
    return REPORT_MARKER in (issue.get("body") or "")


def is_created_after(issue: dict, start: datetime) -> bool:
    raw = issue.get("createdAt")
    if not raw:
        return False
    try:
        created = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return False
    return created >= start


def validate_created_issues(
    created: list[dict],
    *,
    existing_keys: dict[str, int],
    max_tickets: int,
) -> list[str]:
    """Порушення контракту прогону. Порожній список — усе гаразд.

    Тікети не видаляються й не виправляються автоматично: нічна робота не має
    права прибирати те, що вже опубліковано. Порушення називаються й валять
    прогін, щоб наступний ранок почався з розбору, а не з тихого накопичення.
    """
    problems: list[str] = []
    tickets = [issue for issue in created if not is_report_issue(issue)]
    if len(tickets) > max_tickets:
        problems.append(
            f"створено {len(tickets)} тікетів, стеля — {max_tickets}"
        )

    seen: dict[str, int] = {}
    for issue in tickets:
        number = int(issue.get("number") or 0)
        body = issue.get("body") or ""
        labels = {label.get("name") for label in issue.get("labels") or []}
        if "ready-for-agent" not in labels:
            problems.append(f"#{number}: немає мітки ready-for-agent")
        if LABEL not in labels:
            problems.append(f"#{number}: немає мітки {LABEL}")
        if "## Доказ" not in body:
            problems.append(f"#{number}: немає розділу «Доказ»")
        if not re.search(r"[\w./-]+\.\w+:\d+", body):
            problems.append(f"#{number}: немає жодного доказу file:line")
        keys = [match.group(1) for match in TICKET_MARKER_RE.finditer(body)]
        if not keys:
            problems.append(f"#{number}: немає маркера ui-ux-patrol: key=")
        for key in keys:
            if key in existing_keys and existing_keys[key] != number:
                problems.append(
                    f"#{number}: ключ {key} уже зайнятий #{existing_keys[key]}"
                )
            if key in seen and seen[key] != number:
                problems.append(f"#{number}: ключ {key} повторює #{seen[key]}")
            seen.setdefault(key, number)
    return problems


def parse_result(text: str) -> tuple[str | None, str]:
    """Останній рядок контракту агента → (вердикт, хвіст)."""
    matches = RESULT_RE.findall(text or "")
    if not matches:
        return None, ""
    verdict, tail = matches[-1]
    return verdict, tail.strip().strip("`").strip()


def parse_contract_refs(tail: str) -> tuple[list[int] | None, int | None]:
    """Номери з контрактного рядка агента: (`created`, `report`).

    `created=None` означає «поле не названо» — це порушення контракту, а не
    «нуль створених». `created=[]` — агент явно сказав `none`/`0`. Така
    різниця важлива: інакше мовчазний агент виглядав би як чесний нуль.
    """
    created: list[int] | None = None
    match = CREATED_REF_RE.search(tail or "")
    if match:
        raw = match.group(1).strip("`.,;")
        if raw.lower() in {"none", "0", "-"}:
            created = []
        else:
            created = [int(number) for number in ISSUE_NUMBER_RE.findall(raw)]

    report: int | None = None
    report_match = REPORT_REF_RE.search(tail or "")
    if report_match and report_match.group(1).startswith("#"):
        report = int(report_match.group(1)[1:])
    return created, report


def undeclared_marked_issues(
    recent: list[dict], *, declared: list[int], started: datetime
) -> list[int]:
    """Тікети з маркером патруля, створені за прогін, але не задекларовані.

    Чужі issues (без маркера) сюди не потрапляють — саме це відрізняє
    «агент створив тікет і не назвав його» від «хтось інший створив issue в ту
    саму годину».
    """
    declared_set = set(declared)
    return [
        int(issue.get("number") or 0)
        for issue in recent
        if is_created_after(issue, started)
        and TICKET_MARKER_RE.search(issue.get("body") or "")
        and int(issue.get("number") or 0) not in declared_set
    ]


def render_prompt(
    *,
    repo: Path,
    now: datetime,
    dry_run: bool,
    max_tickets: int,
    known_keys: dict[str, int],
    closed_keys: dict[str, int] | None = None,
) -> str:
    """Промпт агента. Чиста функція — тести пінять його без dsh і мережі."""
    focus = focus_for(now)
    known = "\n".join(
        f"- `{key}` — #{number}" for key, number in sorted(known_keys.items())
    ) or "- (відкритих тікетів патруля немає)"
    closed = "\n".join(
        f"- `{key}` — #{number}" for key, number in sorted((closed_keys or {}).items())
    ) or "- (немає)"
    mode = (
        "dry-run — НІЧОГО не створюй і не редагуй; лише знайди й опиши у відповіді"
        if dry_run
        else "бойовий — створюй тікети й онови звіт за правилами нижче"
    )
    extra = (
        "\n**DRY-RUN:** не виконуй `gh issue create` / `gh issue edit`; "
        "запропоновані тікети надрукуй у відповіді.\n"
        if dry_run
        else ""
    )
    return PROMPT_TEMPLATE.substitute(
        DATE=now.strftime("%Y-%m-%d"),
        REPO=str(repo),
        FOCUS_TITLE=focus["title"],
        FOCUS_BRIEF=focus["brief"],
        FOCUS_KEY=focus["key"],
        MAX_TICKETS=str(max_tickets),
        MODE=mode,
        EXTRA_CONTEXT=extra,
        KNOWN_KEYS=known,
        CLOSED_KEYS=closed,
    )


def render_service_unit(repo: Path) -> str:
    """Юніт, який виконує скрипт із `origin/main`, а не з робочого дерева."""
    repo_path = str(repo)
    exec_start = (
        "/usr/bin/env bash -c 'cd "
        f"{repo_path} && GIT_TERMINAL_PROMPT=0 git fetch --prune --force "
        f"{REPO_URL} refs/heads/main:refs/remotes/origin/main && "
        f"git show origin/main:scripts/ui-ux-patrol.py | python3 - run "
        f"--repo {repo_path}'"
    )
    return textwrap.dedent(
        f"""\
        [Unit]
        Description=Нічний UI/UX-патруль: аудит і ready-for-agent тікети
        Documentation=file:{repo_path}/docs/runbooks/ui-ux-patrol.md
        After=network-online.target
        Wants=network-online.target

        [Service]
        Type=oneshot
        WorkingDirectory={repo_path}
        EnvironmentFile=-%h/.config/slukhayka/ui-ux-patrol.env
        ExecStart={exec_start}
        Nice=10
        IOSchedulingClass=idle
        NoNewPrivileges=true
        TimeoutStartSec=75min
        """
    )


def render_timer_unit() -> str:
    return textwrap.dedent(
        f"""\
        [Unit]
        Description=Нічний UI/UX-патруль (таймер)
        Documentation=file:%h/Projects/slukhayka/docs/runbooks/ui-ux-patrol.md

        [Timer]
        # 03:20 — після нічного CI й branch-hygiene (02:40), до ранку.
        # Persistent=true доганяє пропущений запуск, якщо машина спала.
        OnCalendar={ON_CALENDAR}
        Persistent=true
        RandomizedDelaySec=600

        [Install]
        WantedBy=timers.target
        """
    )


def render_env_file(
    path_dirs: list[str],
    gh_token: str | None,
    model_env: dict[str, str] | None = None,
) -> str:
    lines = [
        PROFILE_MARKER + " install",
        "# PATH мусить містити dsh, gh, git, python3 — таймер не читає ~/.bashrc.",
        "PATH=" + ":".join(dict.fromkeys(path_dirs)),
    ]
    if gh_token:
        lines.append("# GH_TOKEN: файл 0600; не комітити й не копіювати в репо.")
        lines.append("GH_TOKEN=" + gh_token)
    if model_env:
        # #1659 — headless-агент резолвить ключ моделі з ENV (`apiKeyEnv` у
        # профілі), а таймер не читає ні ~/.bashrc, ні keyring: без цього
        # рядка кожен нічний прогін падає з MISSING_CREDENTIAL.
        lines.append(
            "# Ключ моделі headless-агента (apiKeyEnv профілю): файл 0600;"
            " не комітити й не копіювати в репо."
        )
        for name, value in model_env.items():
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                raise PatrolError(f"непридатне імʼя змінної для env-файла: {name!r}")
            if "\n" in value or "\r" in value:
                # #1662 — значення пишуться в env без екранування; новий рядок
                # дозволив би ін'єкцію довільних змінних у файл таймера.
                raise PatrolError(
                    f"значення {name} містить новий рядок — не пишу env-файл"
                )
            lines.append(f"{name}={value}")
    return "\n".join(lines) + "\n"


PROFILE_API_KEY_ENV_RE = re.compile(r"^\s*apiKeyEnv:\s*([A-Za-z_][A-Za-z0-9_]*)\s*$", re.M)


def profile_api_key_env(profile_patch: Path) -> str | None:
    """Імʼя змінної, яку headless-профіль резолвить як ключ моделі (#1659)."""
    try:
        text = profile_patch.read_text(encoding="utf-8")
    except OSError:
        return None
    match = PROFILE_API_KEY_ENV_RE.search(text)
    return match.group(1) if match else None


def credential_refs(credentials_path: Path) -> dict[str, str]:
    """`refs:` з `~/.dsh/.credentials.yaml` мінімальним stdlib-парсером.

    #1659 — ключ моделі живе там (0600), але не експортований ні в
    systemd-оточення, ні в ~/.bashrc таймера. PyYAML заборонений: скрипт
    самодостатній (лише stdlib). Парсер читає рівно дворівневий блок
    `refs:` → `NAME: value`; інші ключі ігнорує. Значення не друкуються.
    """
    try:
        text = credentials_path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        # #1662 — битий/не-UTF8 файл не має валити install: це той самий
        # «ключа не знайдено», який install назве вголос.
        return {}
    refs: dict[str, str] = {}
    in_refs = False
    for raw in text.splitlines():
        line = raw.rstrip()
        if re.match(r"^refs:\s*(#.*)?$", line):
            in_refs = True
            continue
        if not in_refs:
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith((" ", "\t")):
            break  # наступний top-level ключ
        match = re.match(
            r"^\s{2,}([A-Za-z_][A-Za-z0-9_]*):\s*(\S+?)\s*$", line
        )
        if match:
            refs[match.group(1)] = match.group(2).strip("'\"")
    return refs


def resolve_model_key(api_key_env: str | None, home: Path | None = None) -> str | None:
    """Ключ моделі: спершу оточення, потім `~/.dsh/.credentials.yaml` (#1659)."""
    if not api_key_env:
        return None
    from_env = os.environ.get(api_key_env)
    if from_env:
        return from_env
    home = home or Path.home()
    return credential_refs(home / ".dsh" / ".credentials.yaml").get(api_key_env)


# ---------------------------------------------------------------------------
# Запуск процесів
# ---------------------------------------------------------------------------


def run(
    args: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout: int | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(
            args,
            cwd=str(cwd) if cwd else None,
            env=merged,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise PatrolError(f"не знайдено виконуваний файл: {args[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise PatrolError(f"таймаут: {' '.join(args)}") from exc
    if check and proc.returncode != 0:
        raise PatrolError(
            f"команда '{' '.join(args)}' завершилась кодом "
            f"{proc.returncode}: {(proc.stderr or proc.stdout).strip()[:500]}"
        )
    return proc


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return run(["git", "-C", str(repo), *args], check=check)


def git_common_dir(repo: Path) -> Path:
    out = git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")
    return Path(out.stdout.strip())


def ensure_origin_main(repo: Path) -> str:
    """Свіжий `origin/main` в обхід локальної SSH-конфігурації (див. runbook)."""
    run(
        [
            "git",
            "-C",
            str(repo),
            "fetch",
            "--prune",
            "--force",
            REPO_URL,
            "refs/heads/main:refs/remotes/origin/main",
        ]
    )
    return git(repo, "rev-parse", "origin/main").stdout.strip()


def patrol_worktree_path(repo: Path) -> Path:
    """Фіксований шлях копії патруля — не налаштовується, щоб не зламати чужу."""
    return repo / ".worktrees" / WORKTREE_NAME


def ensure_patrol_worktree(repo: Path) -> Path:
    """Виділена копія `origin/main`; чужу/зламану копію не чіпаємо."""
    path = patrol_worktree_path(repo)
    if path.exists():
        top = run(
            ["git", "-C", str(path), "rev-parse", "--show-toplevel"], check=False
        )
        if top.returncode != 0 or Path(top.stdout.strip()).resolve() != path.resolve():
            raise PatrolError(
                f"{path} існує, але не є окремою копією патруля — не чіпаю; "
                "прибери її вручну"
            )
        git(path, "reset", "--hard", "origin/main")
        git(path, "clean", "-fdx")
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    git(repo, "worktree", "prune", check=False)
    git(repo, "worktree", "add", "--detach", str(path), "origin/main")
    return path


def gh_json(args: list[str], *, cwd: Path, timeout: int = 60) -> list[dict]:
    out = run(["gh", *args], cwd=cwd, timeout=timeout)
    try:
        data = json.loads(out.stdout or "[]")
    except json.JSONDecodeError as exc:
        raise PatrolError(f"gh віддав не-JSON: {exc}") from exc
    if not isinstance(data, list):
        raise PatrolError("gh віддав не список issues")
    return data


def collect_labeled_issues(repo: Path, *, state: str) -> list[dict]:
    return gh_json(
        [
            "issue",
            "list",
            "--label",
            LABEL,
            "--state",
            state,
            "--limit",
            "300",
            "--json",
            "number,title,body,state,createdAt,labels,url",
        ],
        cwd=repo,
        timeout=90,
    )


def collect_recent_issues(repo: Path) -> list[dict]:
    """Останні issues БЕЗ фільтра мітки — щоб побачити й тікет без `ui-ux-patrol`.

    Перевірка «кожен створений тікет має мітки» мусить бачити і порушника, інакше
    вона недосяжна: тікет без мітки не потрапив би ні в стелю, ні в лог.
    Сортування `gh issue list` — від новіших, тож 100 останніх покривають будь-який
    нічний прогін із запасом.
    """
    return gh_json(
        [
            "issue",
            "list",
            "--state",
            "all",
            "--limit",
            "100",
            "--json",
            "number,title,body,state,createdAt,labels,url",
        ],
        cwd=repo,
        timeout=90,
    )


def fetch_issue(repo: Path, number: int) -> dict:
    """Один issue за номером — незалежно від вікна `gh issue list`."""
    out = run(
        [
            "gh",
            "issue",
            "view",
            str(number),
            "--json",
            "number,title,body,state,createdAt,labels,url",
        ],
        cwd=repo,
        timeout=60,
    )
    try:
        data = json.loads(out.stdout or "{}")
    except json.JSONDecodeError as exc:
        raise PatrolError(f"gh віддав не-JSON для #{number}: {exc}") from exc
    if not isinstance(data, dict):
        raise PatrolError(f"gh віддав не issue для #{number}")
    return data


class AgentRun:
    """Прогін dsh із потоковим логом; таймаут ріже групу процесів, не лише PID."""

    def __init__(self, *, log_path: Path, timeout_min: int) -> None:
        self.log_path = log_path
        self.timeout = timeout_min * 60
        self.tail = ""

    def execute(self, prompt: str, *, cwd: Path, dsh: str) -> tuple[int, str]:
        env = dict(os.environ)
        env["DSH_PERMISSION_MODE"] = "danger-full-access"
        with open(self.log_path, "a", encoding="utf-8") as log_file:
            log_file.write(f"\n===== dsh {time.strftime('%Y-%m-%d %H:%M:%S')} =====\n")
            log_file.flush()
            proc = subprocess.Popen(
                [dsh, "--profile", "headless", "-"],
                cwd=str(cwd),
                env=env,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
            chunks: list[str] = []

            def pump() -> None:
                assert proc.stdout is not None
                for line in proc.stdout:
                    log_file.write(line)
                    log_file.flush()
                    chunks.append(line)
                    if len(chunks) > 4000:
                        del chunks[:2000]

            reader = threading.Thread(target=pump, daemon=True)
            reader.start()
            try:
                assert proc.stdin is not None
                proc.stdin.write(prompt)
                proc.stdin.close()
            except BrokenPipeError:
                pass
            try:
                code = proc.wait(timeout=self.timeout)
            except subprocess.TimeoutExpired:
                log_file.write(
                    f"\n[patrol] таймаут {self.timeout}s — гашу групу процесів\n"
                )
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    proc.wait()
                code = 124
            reader.join(timeout=10)
            self.tail = "".join(chunks)[-200_000:]
        return code, self.tail


# ---------------------------------------------------------------------------
# Підкоманди
# ---------------------------------------------------------------------------


def cmd_install(args: argparse.Namespace) -> int:
    repo = args.repo.resolve()
    if not (repo / ".git").exists():
        raise PatrolError(f"{repo} — не git-репозиторій")
    dsh = shutil.which("dsh")
    if not dsh:
        raise PatrolError("dsh не знайдено в PATH — постав @deepseek-ai/dsh")
    if not shutil.which("gh"):
        raise PatrolError("gh не знайдено в PATH")
    run(["gh", "auth", "status"])

    profile_dir = Path.home() / ".dsh" / "profiles" / "headless"
    ensure_headless_profile(dsh, profile_dir)

    # #1659 — headless-профіль резолвить ключ моделі з ENV (`apiKeyEnv`), а
    # systemd-таймер не читає ~/.bashrc/keyring. Беремо ключ з оточення або з
    # ~/.dsh/.credentials.yaml і кладьмо у env-файл таймера; без нього кожен
    # нічний прогін падає з MISSING_CREDENTIAL.
    api_key_env = None
    model_env: dict[str, str] | None = None
    if not args.no_model_key:
        api_key_env = profile_api_key_env(profile_dir / "cordis.patch.yml")
        model_key = resolve_model_key(api_key_env)
        if api_key_env and model_key:
            model_env = {api_key_env: model_key}
        elif api_key_env:
            print(
                f"УВАГА: ключ {api_key_env} не знайдено ні в оточенні, ні в "
                "~/.dsh/.credentials.yaml — таймер отримає MISSING_CREDENTIAL; "
                "додай ключ у Models (web) або в env вручну",
                file=sys.stderr,
            )

    ensure_label(repo)
    env_path = write_env_file(
        write_token=not args.no_gh_token,
        model_env=model_env,
    )
    if model_env:
        print(
            "env: ключ моделі " + ", ".join(model_env) + " записано (значення не друкується)"
        )

    # #1662 — env-файл пишеться ДО димового прогону: якщо ключа немає, install
    # усе одно лишає оновлений env (PATH+GH_TOKEN, без ключа) і падає гучним
    # smoke-провалом, а не мовчки й без файла.
    if not args.skip_smoke:
        smoke_headless(dsh, model_env=model_env)
    units_dir = Path.home() / ".config" / "systemd" / "user"
    units_dir.mkdir(parents=True, exist_ok=True)
    (units_dir / "ui-ux-patrol.service").write_text(
        render_service_unit(repo), encoding="utf-8"
    )
    (units_dir / "ui-ux-patrol.timer").write_text(
        render_timer_unit(), encoding="utf-8"
    )
    print(f"юніти: {units_dir}/ui-ux-patrol.{{service,timer}}")
    print(f"env:   {env_path}")

    if args.no_enable:
        print("таймер не ввімкнено (--no-enable); команда:")
        print("  systemctl --user daemon-reload && systemctl --user enable --now ui-ux-patrol.timer")
        return 0
    try:
        run(["systemctl", "--user", "daemon-reload"], timeout=30)
        run(
            ["systemctl", "--user", "enable", "--now", "ui-ux-patrol.timer"],
            timeout=30,
        )
    except PatrolError as exc:
        print(f"УВАГА: systemd --user не ввімкнув таймер: {exc}", file=sys.stderr)
        print("виконай вручну на hub:")
        print("  systemctl --user daemon-reload")
        print("  systemctl --user enable --now ui-ux-patrol.timer")
        return 2
    print("таймер ввімкнено. Перевірка: systemctl --user list-timers ui-ux-patrol.timer")
    return 0


def is_replaceable_profile_patch(text: str) -> bool:
    """Чи це наш файл або порожній шаблон, який `dsh` створив сам.

    `initProfile` пише `cordis.patch.yml` із коментарем і `[]`. Перезаписувати
    чужий налаштований шар не можна, але порожній шаблон — не чужа праця.
    """
    if PROFILE_MARKER in text:
        return True
    body = [
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    return body in ([], ["[]"])


def ensure_headless_profile(dsh: str, profile_dir: Path) -> None:
    if not (profile_dir / "package.json").exists():
        print("розгортаю профіль headless (--dump-config, без витрат токенів)…")
        proc = run([dsh, "--profile", "headless", "--dump-config"], check=False)
        if not (profile_dir / "package.json").exists():
            raise PatrolError(
                "профіль headless не створився: "
                + (proc.stderr or proc.stdout).strip()[:500]
            )
    patch = profile_dir / "cordis.patch.yml"
    if patch.exists() and not is_replaceable_profile_patch(
        patch.read_text(encoding="utf-8")
    ):
        raise PatrolError(
            f"{patch} містить чужу конфігурацію без маркера {PROFILE_MARKER!r} — "
            "не перезаписую; злий вручну або прибери файл"
        )
    patch.write_text(
        PROFILE_MARKER
        + "\n"
        + textwrap.dedent(
            """\
            - id: agent-default-model
              name: '@deepseek-ai/dsh-agent-default-model'
              config:
                provider: opencode-go
                model: deepseek-v4.1-flash
                reasoningEffort: high
            - id: llm-pi-ai
              name: '@deepseek-ai/dsh-llm-pi-ai'
              config:
                providers:
                  opencode-go:
                    apiKeyEnv: OPENCODE_API_KEY
                    models:
                      - id: deepseek-v4.1-flash
                        name: DeepSeek V4.1 Flash
                        contextWindow: 1000000
                        maxTokens: 384000
            """
        ),
        encoding="utf-8",
    )


def smoke_headless(dsh: str, *, model_env: dict[str, str] | None = None) -> None:
    print("димовий прогін headless (ключ моделі)…")
    env = {"DSH_PERMISSION_MODE": "danger-full-access"}
    if model_env:
        env.update(model_env)
    proc = run(
        [dsh, "--profile", "headless", "Reply with exactly: READY"],
        env=env,
        timeout=240,
        check=False,
    )
    if "READY" not in (proc.stdout or ""):
        raise PatrolError(
            "димовий прогін не дав READY — перевір ~/.dsh/.credentials.yaml: "
            + (proc.stdout or proc.stderr).strip()[-500:]
        )
    print("димовий прогін: ключ працює")


def ensure_label(repo: Path) -> None:
    proc = run(
        [
            "gh",
            "label",
            "create",
            LABEL,
            "--description",
            "Знайдено нічним UI/UX-патрулем",
            "--color",
            "5319E7",
        ],
        cwd=repo,
        check=False,
    )
    if proc.returncode != 0 and "already exists" not in (
        proc.stderr or proc.stdout
    ).lower():
        raise PatrolError(f"не вдалося створити мітку {LABEL}: {proc.stderr.strip()}")
    print(f"мітка {LABEL}: є")


def stable_tool_dirs(home: Path) -> list[Path]:
    """Каталоги, які переживають `mise upgrade` інструментів.

    #1657 — `shutil.which("gh")` віддає ВЕРСІЙНИЙ каталог
    (`…/installs/gh/latest/gh_2.102.0_linux_amd64/bin`). Після `mise upgrade`
    symlink `latest` веде на нову версію, старий шлях зникає, і env-файл
    таймера (який генерує ця функція) лишається з мертвим PATH: патруль падає
    три ночі поспіль із «не знайдено виконуваний файл: gh». Стабільні
    каталоги — `~/.local/bin` (симлінк gh) і `mise shims` — ведуть на активну
    версію завжди.
    """
    candidates = [home / ".local" / "bin", home / ".local" / "share" / "mise" / "shims"]
    return [path for path in candidates if path.is_dir()]


def patrol_path_dirs(
    home: Path | None = None,
    *,
    which=shutil.which,
    python: str = sys.executable,
) -> list[str]:
    """PATH для env-файла таймера: стабільні каталоги спершу, версійні — потім."""
    home = home or Path.home()
    dirs = [str(path) for path in stable_tool_dirs(home)]
    dirs.extend(
        [
            str(Path(dsh).parent) if (dsh := which("dsh")) else "/usr/local/bin",
            str(Path(gh).parent) if (gh := which("gh")) else "/usr/bin",
            str(Path(python).parent),
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
        ]
    )
    return list(dict.fromkeys(dirs))


def write_env_file(
    *,
    write_token: bool,
    model_env: dict[str, str] | None = None,
) -> Path:
    env_dir = Path.home() / ".config" / "slukhayka"
    env_dir.mkdir(parents=True, exist_ok=True)
    path = env_dir / "ui-ux-patrol.env"
    dirs = patrol_path_dirs()
    token = None
    if write_token:
        proc = run(["gh", "auth", "token"], check=False, timeout=30)
        if proc.returncode == 0:
            token = proc.stdout.strip() or None
    # #1662 — файл містить секрети: створюємо одразу з 0600, щоб не лишати
    # вікна з umask-правами між write_text і chmod.
    payload = render_env_file(dirs, token, model_env)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        # #1662 — fchmod одразу: для вже наявного файла з 0644 create-права не
        # діють, і без цього секрет ліг би у світ до chmod після запису.
        os.fchmod(fd, 0o600)
        os.write(fd, payload.encode("utf-8"))
    finally:
        os.close(fd)
    path.chmod(0o600)
    if token:
        print(f"GH_TOKEN записано у {path} (0600) — щоб таймер працював без keyring")
    else:
        print(
            f"GH_TOKEN не записано; якщо таймер не бачить автентифікації gh, "
            f"додай GH_TOKEN=… у {path}"
        )
    return path


def cmd_run(args: argparse.Namespace) -> int:
    repo = args.repo.resolve()
    common = git_common_dir(repo)
    state_dir = common / "ui-ux-patrol"
    logs_dir = state_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    cleanup_old_logs(logs_dir, KEEP_LOGS)

    lock_path = state_dir / "lock"
    lock_file = open(lock_path, "w", encoding="utf-8")
    try:
        import fcntl

        try:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("патруль уже виконується — цей прогін пропущено", file=sys.stderr)
            return 3
        return _run_locked(args, repo, logs_dir)
    finally:
        lock_file.close()


def _run_locked(args: argparse.Namespace, repo: Path, logs_dir: Path) -> int:
    now = datetime.now(timezone.utc)
    log_path = logs_dir / f"{now:%Y-%m-%d}.log"
    started = now

    print(f"UI/UX-патруль: {now:%Y-%m-%d %H:%M} UTC, тема: {focus_for(now)['title']}")
    print(f"лог: {log_path}")
    with open(log_path, "a", encoding="utf-8") as log:
        log.write(
            f"\n===== прогін {now.isoformat()} "
            f"(dry-run={args.dry_run}) =====\n"
        )

    ensure_origin_main(repo)
    worktree = ensure_patrol_worktree(repo)

    try:
        known_issues = collect_labeled_issues(repo, state="all")
    except PatrolError as exc:
        print(f"УВАГА: не прочитав наявні тікети патруля: {exc}", file=sys.stderr)
        known_issues = []
    open_keys, closed_keys = split_known_keys(known_issues)
    main_status_before = git(repo, "status", "--porcelain", check=False).stdout

    prompt = render_prompt(
        repo=worktree,
        now=now,
        dry_run=args.dry_run,
        max_tickets=MAX_TICKETS,
        known_keys=open_keys,
        closed_keys=closed_keys,
    )
    prompt_path = logs_dir / f"prompt-{now:%Y-%m-%d}.md"
    prompt_path.write_text(prompt, encoding="utf-8")

    dsh = shutil.which("dsh")
    if not dsh:
        raise PatrolError("dsh не знайдено в PATH")
    agent = AgentRun(log_path=log_path, timeout_min=args.timeout_min)
    code, output = agent.execute(prompt, cwd=worktree, dsh=dsh)
    verdict, tail = parse_result(output)
    print(f"агент: код {code}, вердикт: {verdict or 'немає'} {tail}")

    problems: list[str] = []
    if code == 124:
        problems.append("агент перевищив таймаут")
    if verdict is None:
        problems.append("агент не надрукував контрактний рядок результату")
    elif verdict == "blocked":
        problems.append("агент повідомив blocked")
    elif verdict == "ok-partial":
        print("агент повідомив ok-partial — дивись звіт")

    dirty = git(worktree, "status", "--porcelain", check=False).stdout.strip()
    if dirty:
        problems.append("агент змінив файли в робочій копії — контракт порушено")
        git(worktree, "reset", "--hard", "origin/main", check=False)
        git(worktree, "clean", "-fdx", check=False)

    main_status_after = git(repo, "status", "--porcelain", check=False).stdout
    if main_status_after != main_status_before:
        problems.append(
            "головне дерево hub змінилося під час прогону — авторство не "
            "встановлюється (агент має danger-full-access, але це може бути й "
            "паралельна робота); перевір очима, нічого не скидаю"
        )

    declared_created, declared_report = (None, None)
    if verdict is not None:
        declared_created, declared_report = parse_contract_refs(tail)

    created: list[dict] = []
    if declared_created is None:
        if code != 124:
            problems.append(
                "агент не назвав номери створених тікетів (created=<#…|none>)"
            )
    else:
        for number in declared_created:
            try:
                created.append(fetch_issue(repo, number))
            except PatrolError as exc:
                problems.append(f"не прочитав задекларований тікет #{number}: {exc}")

    report_issue: dict | None = None
    if declared_report:
        try:
            report_issue = fetch_issue(repo, declared_report)
            if not is_report_issue(report_issue):
                problems.append(
                    f"звіт #{declared_report} не має маркера {REPORT_MARKER}"
                )
        except PatrolError as exc:
            problems.append(f"не прочитав звіт #{declared_report}: {exc}")
    else:
        # Стабільний пошук за міткою, а не «останні 100 issues»: звіт лише
        # редагується, тож із вікна він випадає через ~два тижні.
        try:
            report_issue = next(
                (i for i in collect_labeled_issues(repo, state="open") if is_report_issue(i)),
                None,
            )
        except PatrolError:
            report_issue = None

    # Друга лінія: тікет із маркером патруля, якого агент не задекларував.
    # Чужі issues без маркера сюди не потрапляють і прогін не валять.
    try:
        recent = collect_recent_issues(repo)
    except PatrolError as exc:
        print(
            f"УВАГА: не прочитав останні issues — друга лінія контракту "
            f"(незадекларовані тікети) недоступна: {exc}",
            file=sys.stderr,
        )
        recent = []
    undeclared = undeclared_marked_issues(
        recent, declared=declared_created or [], started=started
    )
    if undeclared:
        problems.append(
            "незадекларовані тікети патруля: "
            + ", ".join(f"#{number}" for number in undeclared)
        )

    if args.dry_run:
        if created:
            problems.append(
                f"dry-run задекларував {len(created)} тікетів — контракт порушено"
            )
    else:
        problems.extend(
            validate_created_issues(
                created,
                existing_keys=open_keys,
                max_tickets=MAX_TICKETS,
            )
        )

    tickets = [i for i in created if not is_report_issue(i)]
    numbers = ", ".join(f"#{i['number']}" for i in tickets) or "немає"
    with open(log_path, "a", encoding="utf-8") as log:
        log.write(f"\n[patrol] нових тікетів: {len(tickets)} ({numbers})\n")
        log.write(f"[patrol] звіт: {('#%s' % report_issue['number']) if report_issue else 'не оновлено'}\n")
        for problem in problems:
            log.write(f"[patrol] ПОРУШЕННЯ: {problem}\n")
        log.write(f"[patrol] вердикт агента: {verdict or 'немає'}; код: {code}\n")

    if problems:
        print("ПОРУШЕННЯ контракту:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 2
    print(f"готово: нових тікетів {len(tickets)} ({numbers})")
    return 0


def cleanup_old_logs(logs_dir: Path, keep: int) -> None:
    cutoff = time.time() - keep * 24 * 3600
    for pattern in ("*.log", "prompt-*.md"):
        for path in logs_dir.glob(pattern):
            try:
                if path.stat().st_mtime < cutoff:
                    path.unlink()
            except OSError:
                continue


def cmd_status(args: argparse.Namespace) -> int:
    repo = args.repo.resolve()
    profile = Path.home() / ".dsh" / "profiles" / "headless" / "package.json"
    print(f"репозиторій: {repo}")
    print(f"профіль headless: {'є' if profile.exists() else 'НЕМАЄ'}")
    gh = run(["gh", "auth", "status"], check=False)
    print("gh: " + ("автентифіковано" if gh.returncode == 0 else "НЕ автентифіковано"))
    timer = run(
        ["systemctl", "--user", "list-timers", "ui-ux-patrol.timer", "--no-pager"],
        check=False,
    )
    print("таймер:")
    print(timer.stdout.strip() or f"  не бачу таймера (код {timer.returncode})")
    common = git_common_dir(repo)
    logs = sorted((common / "ui-ux-patrol" / "logs").glob("*.log"))
    if logs:
        latest = logs[-1]
        print(f"останній лог: {latest}")
        tail = latest.read_text(encoding="utf-8", errors="replace").splitlines()[-5:]
        for line in tail:
            print(f"  {line}")
    else:
        print("логів ще немає")
    try:
        issues = collect_labeled_issues(repo, state="open")
        print(f"відкритих тікетів патруля: {len(issues)}")
        for issue in issues[-8:]:
            print(f"  #{issue['number']} {issue['title']}")
    except PatrolError as exc:
        print(f"тікети не прочитано: {exc}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Нічний UI/UX-патруль: аудит і ready-for-agent тікети",
    )

    def with_repo(subparser: argparse.ArgumentParser) -> argparse.ArgumentParser:
        # `--repo` стоїть саме на підкоманді: юніт викликає
        # `... ui-ux-patrol.py run --repo <шлях>`, і аргумент після підкоманди
        # мусить працювати так само, як у ручних командах із runbook.
        subparser.add_argument(
            "--repo", type=Path, default=Path.cwd(), help="робоча копія репозиторію"
        )
        return subparser

    sub = parser.add_subparsers(dest="command", required=True)

    install = with_repo(
        sub.add_parser("install", help="налаштувати профіль, мітку й таймер")
    )
    install.add_argument("--skip-smoke", action="store_true")
    install.add_argument("--no-enable", action="store_true")
    install.add_argument("--no-gh-token", action="store_true")
    install.add_argument(
        "--no-model-key",
        action="store_true",
        help="не переносити ключ моделі headless-профілю в env таймера (#1659)",
    )
    install.set_defaults(func=cmd_install)

    run_cmd = with_repo(sub.add_parser("run", help="нічний прогін"))
    run_cmd.add_argument("--timeout-min", type=int, default=DEFAULT_TIMEOUT_MIN)
    run_cmd.add_argument("--dry-run", action="store_true", help=argparse.SUPPRESS)
    run_cmd.set_defaults(func=cmd_run)

    dry = with_repo(sub.add_parser("dry-run", help="прогін без створення тікетів"))
    dry.add_argument("--timeout-min", type=int, default=DEFAULT_TIMEOUT_MIN)
    dry.set_defaults(func=cmd_run, dry_run=True)

    status = with_repo(
        sub.add_parser("status", help="стан налаштування й останній прогін")
    )
    status.set_defaults(func=cmd_status)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except PatrolError as exc:
        print(f"ПАТРУЛЬ: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("перервано", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
