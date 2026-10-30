# План: #1662 — follow-up огляду #1661 (план/тести/runbook/secret-hardening)

Чотири не-блокуючі знахідки огляду PR #1661 (#1659), винесені окремо.

## 1. План обіцяв більше, ніж код

`docs/plans/2026-10-31-1659-patrol-model-key.md` казав «install … лишає env
без нього», але `write_env_file` виконувався ПІСЛЯ `smoke_headless`: без ключа
install падав на smoke (exit 2) до запису env.

Рішення: **порядок змінено** — `install` пише env-файл (PATH+GH_TOKEN+ключ,
якщо є) ДО димового прогону. Тепер контракт = факт: без ключа файл усе одно
оновлено (без ключа), а прогін падає гучним smoke-провалом.

## 2. Два spec-тести

- `credential_refs` на битих/не-UTF8 файлах: `UnicodeDecodeError` тепер
  ловиться і повертає `{}` (install назве «ключа не знайдено» вголос, а не
  впаде трейсбеком); рядки без `:`/зайві ігноруються.
- «ключ не потрапляє у stdout»: тест перехоплює stdout `write_env_file` і
  перевіряє, що значення немає у виводі, але є у файлі 0600.

## 3. Runbook, крок install

Крок 5 більше не каже «env = PATH+GH_TOKEN»: згадано ключ моделі (env →
`~/.dsh/.credentials.yaml` refs), `--no-model-key` і порядок «env до smoke».

## 4. Secret-hardening

- значення env-рядків валідуються: імʼя змінної — `[A-Za-z_][A-Za-z0-9_]*`,
  значення без `\n`/`\r` → інакше `PatrolError` (новий рядок дозволив би
  інʼєкцію довільних змінних у файл таймера);
- файл створюється через `os.open(..., 0o600)` (без вікна umask між
  `write_text` і `chmod`), `chmod 0600` лишається для наявного файла.

## Перевірка

- `python3 -m unittest scripts/tests/test_ui_ux_patrol.py` → **68 тестів**
  (+2: биті refs/не-UTF8, newline-rejection, no-value-in-stdout).
- Hub: `install --skip-smoke` з гілки перегенерує env; `status` зелений;
  таймер не зачеплений (next 03:24). Повний `install` зі smoke — окрема
  перевірка, що ключ резолвиться і з новим порядком.
- CI: script unit tests.

## Межі

- Це не змінює формат env-файла: ті самі `PATH`, `GH_TOKEN`, `KEY=value`,
  коментарі; змінюються лише порядок install, валідація і спосіб створення.
- Keyring-only сценарій (`--no-model-key` + ключ у keyring) лишається
  робочим: таймер тоді розраховує на keyring, як і до #1659.
