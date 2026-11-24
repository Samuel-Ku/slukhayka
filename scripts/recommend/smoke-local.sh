#!/usr/bin/env bash
# Portable manual CPU trial for macOS/Linux. Does not publish or schedule anything.
set -euo pipefail
if [[ ${1:-} == --help ]]; then
  printf '%s\n' 'Usage: scripts/recommend/smoke-local.sh [output-directory]' \
    'Needs Python 3.10–3.13, internet access and roughly 2–3 GB free disk.' \
    'Creates an isolated venv, downloads pinned Model2Vec weights, runs cold/warm builds.'
  exit 0
fi
repo_root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
trial_python=''
for candidate in python3.12 python3.13 python3.11 python3.10 python3; do
  if command -v "$candidate" >/dev/null && "$candidate" -c 'import sys; raise SystemExit(not ((3,10) <= sys.version_info[:2] < (3,14)))'; then
    trial_python=$candidate
    break
  fi
done
if [[ -z $trial_python ]]; then
  printf '%s\n' 'Install Python 3.12, then run this command again.' >&2
  exit 2
fi
trial_dir=${1:-$(mktemp -d "${TMPDIR:-/tmp}/slukhayka-recommend.XXXXXX")}
mkdir -p "$trial_dir"
trial_dir=$(cd "$trial_dir" && pwd)
printf 'Trial directory: %s\n' "$trial_dir"
"$trial_python" -m venv "$trial_dir/venv"
"$trial_dir/venv/bin/python" -m pip install -r "$repo_root/scripts/recommend/requirements.txt"
# HF_HUB_CACHE scopes the model download to this trial, including on macOS.
export HF_HUB_CACHE="$trial_dir/model-cache"
export HF_HUB_DISABLE_XET=1
for stage in cold warm; do
  "$trial_dir/venv/bin/python" "$repo_root/scripts/recommend/build_graph.py" \
    --catalog "$repo_root/scripts/recommend/smoke-catalog.json" \
    --cache "$trial_dir/vectors.sqlite" --output "$trial_dir/graph.json" \
    --revision 73908c3438cf03b6a01bcb9611d62b23d0726f08 > "$trial_dir/$stage.json"
done
"$trial_dir/venv/bin/python" - "$trial_dir" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
cold = json.loads((path / 'cold.json').read_text())
warm = json.loads((path / 'warm.json').read_text())
assert cold['computed'] == 4, cold
assert warm['computed'] == 0, warm
print(json.dumps({'cold': cold, 'warm': warm}, ensure_ascii=False, indent=2))
print('PASS: model ran; unchanged descriptions reused their vectors. This is not a quality evaluation.')
PY
printf 'Results and isolated environment: %s\n' "$trial_dir"
