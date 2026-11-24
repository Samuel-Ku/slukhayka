#!/usr/bin/env bash
# Підставний інструмент фікстури #1260: та сама ланка, що в справжньому
# scripts/mutate.sh — артефакт → лічильники → вердикт → запис, — але без Gradle,
# без відкату й без самих мутацій.
#
# Артефакт пише свідок, а не інструмент: у JVM-половині його пише Gradle, якого
# в дереві немає, і саме тому продюсера тут теж немає — фікстура повторює ту
# половину, а не вигадує зручнішу.
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
trace_module=$repo_root/scripts/mutation_trace.py
artifact_path=$repo_root/.artifacts/TEST-gate.xml
record_path=$repo_root/.artifacts/runs.jsonl

tests="" failures="" errors="" skipped=""
artifact_sha=""
if [[ -f $artifact_path ]]; then
  counts=$(python3 - "$artifact_path" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
print(
    root.get("tests", "0"),
    root.get("failures", "0"),
    root.get("errors", "0"),
    root.get("skipped", "0"),
)
PY
  )
  read -r tests failures errors skipped <<< "$counts"
  artifact_sha=$(sha256sum "$artifact_path" | awk '{print $1}')
fi

if [[ -z $tests ]]; then
  outcome=no-verdict
elif [[ $failures -gt 0 || $errors -gt 0 ]]; then
  outcome=killed
elif [[ $skipped == "$tests" ]]; then
  outcome=no-verdict
else
  outcome=survived
fi

# Свій argv — у stderr: коли інструмент падає, причина мусить бути видна одразу
# (порожній шлях, чуже оточення, інший інтерпретатор). Єдина змінна, яку цей
# інструмент читає з оточення, — наша й із префіксом; решта імен — його власні,
# тож колізія з оточенням неможлива.
if [[ -n ${VERDICT_FIXTURE_ECHO:-} ]]; then
  printf 'fixture argv: python3 %s record --log %s outcome=%s tests=%s failures=%s errors=%s artifactSha256=%s\n' \
    "$trace_module" "$record_path" "$outcome" "$tests" "$failures" "$errors" "$artifact_sha" >&2
fi

python3 "$trace_module" record --log "$record_path" \
  "outcome=$outcome" "tests=$tests" "failures=$failures" "errors=$errors" \
  "artifactSha256=$artifact_sha"
