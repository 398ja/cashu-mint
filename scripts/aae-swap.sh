#!/usr/bin/env bash
# AAE runner (aae/nov27, read-only during the session). Prints size_*_us, swap_us_geomean, peak_heap_mb.
set -euo pipefail
cd "$(dirname "$0")/../cashu-mint-protocol"
mvn -q -o compile test-compile >/dev/null
CP="target/classes:target/test-classes:$(cat target/aae.cp)"
java -Xms1g -Xmx1g -cp "$CP" xyz.tcheeric.cashu.mint.proto.tasks.SwapBench 2>&1 | grep -E '^(size_|swap_us|peak_heap)'
