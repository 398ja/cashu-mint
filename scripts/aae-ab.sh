#!/usr/bin/env bash
# Paired A/B runner (aae/nov27). Usage: aae-ab.sh <baseline-ref> [pairs=3]
# Builds the baseline ref's main sources in a scratch worktree, using THIS checkout's harness,
# then alternates A,B,B,A,... so machine drift hits both sides equally.
# Prints per-run geomeans and the median B/A ratio (below 1 means B is faster).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_REF="$1"; PAIRS="${2:-3}"
WT="$ROOT/../cashu-mint-aae-base"
if [ ! -d "$WT" ]; then git -C "$ROOT" worktree add -q --detach "$WT" "$BASE_REF"; fi
git -C "$WT" checkout -q --detach "$BASE_REF"
mkdir -p "$WT/cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/tasks"
cp "$ROOT/cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/tasks/SwapBench.java" \
   "$WT/cashu-mint-protocol/src/test/java/xyz/tcheeric/cashu/mint/proto/tasks/SwapBench.java"
CPDEPS="$(cat "$ROOT/cashu-mint-protocol/target/aae.cp")"
build() { (cd "$1/cashu-mint-protocol" && mvn -q -o compile test-compile >/dev/null); }
run() { (cd "$1/cashu-mint-protocol" && java -Xms1g -Xmx1g \
    -cp "target/classes:target/test-classes:$CPDEPS" xyz.tcheeric.cashu.mint.proto.tasks.SwapBench 2>/dev/null \
    | awk '/^swap_us_geomean:/{print $2}'); }
build "$WT"; build "$ROOT"
A=(); B=()
for ((i = 0; i < PAIRS; i++)); do
  if (( i % 2 == 0 )); then A+=("$(run "$WT")"); B+=("$(run "$ROOT")")
  else B+=("$(run "$ROOT")"); A+=("$(run "$WT")"); fi
  echo "pair $i: A=${A[i]} B=${B[i]}"
done
python3 - "${A[@]}" -- "${B[@]}" <<'PY'
import sys, statistics as st
a = list(map(float, sys.argv[1:sys.argv.index('--')])); b = list(map(float, sys.argv[sys.argv.index('--') + 1:]))
r = sorted(y / x for x, y in zip(a, b))
print(f"A_median: {st.median(a):.1f}\nB_median: {st.median(b):.1f}")
print(f"ratio_median: {st.median(r):.3f}  ratios: {' '.join(f'{x:.3f}' for x in r)}")
PY
