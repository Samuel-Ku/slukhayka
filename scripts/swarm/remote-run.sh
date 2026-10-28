#!/usr/bin/env bash
# Ad-hoc remote run: the user-facing name, the logic and tests in remote_run.py.
# Kept as a one-line wrapper so the documented command matches the issue and the
# `.sh` convention of this directory; all quoting/PID/signal logic stays in the
# tested Python module.
set -euo pipefail
exec python3 "$(dirname "${BASH_SOURCE[0]}")/remote_run.py" "$@"
