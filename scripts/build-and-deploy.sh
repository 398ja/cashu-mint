#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN_CMD=${MVN_CMD:-mvn}

declare -a REPOS=(
  "../cashu-lib:cashu-lib-common cashu-lib-entities cashu-lib-crypto"
  "../cashu-vault:cashu-vault-api cashu-vault-jpa"
  "../cashu-gateway:cashu-gateway-common cashu-gateway-dummy cashu-gateway-phoenixd"
  "${ROOT_DIR}:cashu-mint-tools cashu-mint-protocol cashu-mint-rest"
)

echo "Using Maven command: ${MVN_CMD}" >&2

for entry in "${REPOS[@]}"; do
  repo_path="${entry%%:*}"
  modules_string="${entry#*:}"

  if [[ ! -d "${repo_path}" ]]; then
    echo "\n*** Skipping ${repo_path} (directory not found)" >&2
    continue
  fi

  pushd "${repo_path}" >/dev/null
  echo "\n### Building repository: ${repo_path}" >&2

  for module in ${modules_string}; do
    echo "\n=== Building and deploying ${module} ===" >&2
    "${MVN_CMD}" -pl "${module}" -am clean deploy "$@"
  done

  popd >/dev/null
done

echo "\nAll requested modules processed." >&2
