#!/usr/bin/env bash
# Spec-40 #283 — повна матриця: два прогони + фінальний звіт.
set -euo pipefail
cd "$(dirname "$0")"
rm -f /tmp/hub-spec40-matrix.json results-as-is.json results-open.json

# A) правила як в репозиторії
cp ../../firestore.rules firestore.rules
RULES_GATE=as-is RULES_MODE=run OUT_JSON=results-as-is.json \
  npx -y firebase-tools@14 emulators:exec --only firestore,auth --project spec40-matrix -- ./run-matrix.sh

# B) гейт відкритий ЛИШЕ в локальній копії для емулятора
cp ../../firestore.rules firestore.rules
RULES_GATE=open RULES_MODE=run RUN_ANDROID_STORE_TEST="${RUN_ANDROID_STORE_TEST:-1}" OUT_JSON=results-open.json \
  npx -y firebase-tools@14 emulators:exec --only firestore,auth --project spec40-matrix -- ./run-matrix.sh

# відновлюємо чисту копію й будуємо звіт
cp ../../firestore.rules firestore.rules
RULES_MODE=merge node matrix.test.mjs "${1:-/tmp/slukhayka-rules-matrix.md}"
