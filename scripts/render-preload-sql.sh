#!/usr/bin/env bash
set -euo pipefail

JSON_PATH=${1:-scripts/preload-test-data.json}
SQL_PATH=${2:-scripts/preload-test-data.sql}

if [[ ! -f "$JSON_PATH" ]]; then
  echo "JSON preload file not found: $JSON_PATH" >&2
  exit 1
fi

./mvnw -q -pl cashu-mint-protocol initialize exec:java@mint-preload-sql \
  -Dmint.preload.renderer.args="$JSON_PATH $SQL_PATH"
