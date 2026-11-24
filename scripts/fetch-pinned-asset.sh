#!/usr/bin/env bash
# Download atomically and verify cached files as well as new downloads.
set -euo pipefail
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo 'Usage: fetch-pinned-asset.sh HTTPS_URL SHA256 TARGET [TIMEOUT_SECONDS]' >&2
  exit 2
fi
asset_url=$1
expected_hash=$2
asset_target=$3
asset_timeout=${4:-1200}
[[ "$asset_url" == https://* && "$expected_hash" =~ ^[0-9a-f]{64}$ && "$asset_timeout" =~ ^[0-9]+$ ]] || exit 2

verify_asset() {
  local actual_hash
  actual_hash=$(shasum -a 256 "$1")
  actual_hash=${actual_hash%% *}
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    echo "Asset checksum mismatch: $asset_target" >&2
    return 1
  fi
}
if [[ -f "$asset_target" ]]; then
  verify_asset "$asset_target"
  echo "Cached asset verified: $asset_target"
  exit 0
fi
mkdir -p "$(dirname "$asset_target")"
asset_temp=$(mktemp "${asset_target}.download.XXXXXX")
trap 'rm -f "$asset_temp"' EXIT
curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
  --max-time "$asset_timeout" --output "$asset_temp" "$asset_url"
verify_asset "$asset_temp"
mv "$asset_temp" "$asset_target"
echo "Downloaded asset verified: $asset_target"
