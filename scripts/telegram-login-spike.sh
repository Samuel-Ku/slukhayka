#!/usr/bin/env bash
# #829 — запуск спайку входу в Telegram на підʼєднаному пристрої.
#
# Значення НЕ передаються в командному рядку й не друкуються: скрипт читає їх із
# ~/.config/slukhayka/telegram-app.json (поза git) і передає як
# instrumentation-аргументи. У репозиторії й в історії шелу не лишається нічого.
#
# Використання:
#   scripts/telegram-login-spike.sh                 # попросити Telegram надіслати код
#   scripts/telegram-login-spike.sh <code>          # ввести код із Telegram
#   scripts/telegram-login-spike.sh <code> <2FA>    # якщо ввімкнена двоетапна
set -euo pipefail
CREDS="${HOME}/.config/slukhayka/telegram-app.json"
[ -f "$CREDS" ] || { echo "немає $CREDS"; exit 1; }
read -r API_ID API_HASH PHONE < <(python3 - "$CREDS" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(d.get("api_id", ""), d.get("api_hash", ""), d.get("phone", ""))
PY
)
[ -n "$PHONE" ] || { echo "у $CREDS немає поля \"phone\" — додайте номер у форматі +380…"; exit 1; }

ARGS=(
  "-Pandroid.testInstrumentationRunnerArguments.class=com.slukhayka.audiobooks.telegram.TelegramLoginSpikeTest"
  "-Pandroid.testInstrumentationRunnerArguments.api_id=${API_ID}"
  "-Pandroid.testInstrumentationRunnerArguments.api_hash=${API_HASH}"
  "-Pandroid.testInstrumentationRunnerArguments.phone=${PHONE}"
)
[ $# -ge 1 ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.code=$1")
[ $# -ge 2 ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.password=$2")

cd "$(dirname "$0")/.."
echo "Запускаю спайк входу (значення не друкуються)…"
./gradlew :app:connectedDebugAndroidTest "${ARGS[@]}" --max-workers=1 --console=plain \
  | grep -E "SPIKE tdlib|FAILED|BUILD|Tests on" || true
