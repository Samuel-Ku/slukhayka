# План: #1659 — install передає ключ моделі headless-профілю в env таймера

## Симптом (виміряний)

Після фіксу PATH (#1657) нічний патруль усе одно не працює. `install` (димовий
прогін) і `dry-run`:

```
dsh: MISSING_CREDENTIAL: llm-pi-ai: no credential for provider route
"opencode-go"; its profile resolves OPENCODE_API_KEY, which is not set …
агент: код 1, вердикт: немає
[patrol] ПОРУШЕННЯ контракту: агент не надрукував контрактний рядок результату
```

`docs/runbooks/ui-ux-patrol.md` каже, що ключ моделі є в
`~/.dsh/.credentials.yaml`, і саме його радить перевіряти. Перевірено:
`refs.OPENCODE_API_KEY` там є (значення не друкуємо), headless-профіль має
`apiKeyEnv: OPENCODE_API_KEY`, а env-файл таймера містить лише `PATH` +
`GH_TOKEN`. Прямий прогін `OPENCODE_API_KEY=… dsh --profile headless "Reply…"`
дає `READY` — ключ робочий, просто не доходить до systemd.

## Рішення

1. `profile_api_key_env(patch)` — читає `apiKeyEnv` з headless-профілю.
2. `credential_refs(path)` — мінімальний **stdlib**-парсер `refs:` у
   `~/.dsh/.credentials.yaml` (PyYAML заборонений: скрипт самодостатній; блок
   дворівневий, інші ключі ігноруються, значення не друкуються).
3. `resolve_model_key(api_key_env)` — спершу оточення, потім credentials-файл.
4. `render_env_file(..., model_env)` / `write_env_file(..., model_env)` —
   дописують ключ у `~/.config/slukhayka/ui-ux-patrol.env` (0600), з
   коментарем «не комітити»; значення у вивід не потрапляє.
5. `install` передає ключ у димовий прогін (`smoke_headless(model_env=…)`),
   тож smoke тепер справді перевіряє ключ, а `--no-model-key` дозволяє
   відмовитись (напр., коли ключ віддає keyring).
6. Якщо ключа не знайдено — `install` не падає мовчки: друкує в stderr, якого
   `apiKeyEnv` бракує, і лишає env без нього.

## Перевірка

- `python3 -m unittest scripts/tests/test_ui_ux_patrol.py` → **66 тестів**
  (+7 нових: парсер `refs` зі звичайними/порожніми/битими рядками, читання
  `apiKeyEnv`, пріоритет env, fallback на файл, env-файл з ключем і без,
  `write_env_file` пише 0600).
- Hub-приймання: `install` → «димовий прогін: ключ працює»; env-файл містить
  `OPENCODE_API_KEY` (значення не друкується), права 0600; `dry-run` під
  очищеним env-файлом (як таймер) має пройти контракт без створення тікетів.
- CI: script unit tests (єдина нога для `scripts/`).

## Межі

- `install` читає ключ із внутрішнього формату `~/.dsh/.credentials.yaml`;
  якщо dsh змінить формат — helper поверне порожньо й `install` назве це
  вголос (не мовчазний пропуск). Значення ключа не логується.
- Це не «продовження терміну ключа»: протермінований ключ так само приведе до
  `MISSING_CREDENTIAL`/`READY`-провалу — тоді оновлюй у Models (web).
- Таймер/сам прогін агента не змінюються.
