#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN_CMD=${MVN_CMD:-mvn}

# Modules must be deployed in dependency order: tools and protocol first, REST last.
MODULES=(
  cashu-mint-tools
  cashu-mint-protocol
  cashu-mint-rest
)

echo "Using Maven command: ${MVN_CMD}" >&2
echo "Working directory: ${ROOT_DIR}" >&2

pushd "${ROOT_DIR}" >/dev/null

for module in "${MODULES[@]}"; do
  echo "\n=== Building and deploying ${module} ===" >&2
  "${MVN_CMD}" -pl "${module}" -am clean deploy "$@"
done

popd >/dev/null

echo "\nAll modules deployed successfully." >&2
