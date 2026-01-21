#!/bin/bash
#
# Compare Virtual Thread Performance
#
# Compares k6 JSON results between baseline and virtual thread enabled runs.
#
# Usage:
#   ./scripts/compare-vt-performance.sh baseline-results.json vt-results.json
#

set -e

BASELINE_FILE=${1:-results/baseline-results.json}
VT_FILE=${2:-results/vt-results.json}

if [ ! -f "$BASELINE_FILE" ]; then
    echo "Error: Baseline file not found: $BASELINE_FILE"
    exit 1
fi

if [ ! -f "$VT_FILE" ]; then
    echo "Error: VT results file not found: $VT_FILE"
    exit 1
fi

echo "=============================================="
echo "  Virtual Thread Performance Comparison"
echo "=============================================="
echo ""
echo "Baseline: $BASELINE_FILE"
echo "Virtual:  $VT_FILE"
echo ""

# Extract metrics using jq
# Note: k6 JSON output structure varies by version; adjust paths as needed

echo "=== Request Throughput (req/s) ==="
echo ""
BASELINE_RATE=$(jq -r '.metrics.http_reqs.values.rate // "N/A"' "$BASELINE_FILE" 2>/dev/null || echo "N/A")
VT_RATE=$(jq -r '.metrics.http_reqs.values.rate // "N/A"' "$VT_FILE" 2>/dev/null || echo "N/A")
echo "  Baseline: $BASELINE_RATE"
echo "  Virtual:  $VT_RATE"

if [ "$BASELINE_RATE" != "N/A" ] && [ "$VT_RATE" != "N/A" ]; then
    CHANGE=$(echo "scale=2; (($VT_RATE - $BASELINE_RATE) / $BASELINE_RATE) * 100" | bc 2>/dev/null || echo "N/A")
    echo "  Change:   ${CHANGE}%"
fi
echo ""

echo "=== Latency p50 (ms) ==="
echo ""
BASELINE_P50=$(jq -r '.metrics.http_req_duration.values.p50 // "N/A"' "$BASELINE_FILE" 2>/dev/null || echo "N/A")
VT_P50=$(jq -r '.metrics.http_req_duration.values.p50 // "N/A"' "$VT_FILE" 2>/dev/null || echo "N/A")
echo "  Baseline: $BASELINE_P50"
echo "  Virtual:  $VT_P50"
echo ""

echo "=== Latency p95 (ms) ==="
echo ""
BASELINE_P95=$(jq -r '.metrics.http_req_duration.values.p95 // "N/A"' "$BASELINE_FILE" 2>/dev/null || echo "N/A")
VT_P95=$(jq -r '.metrics.http_req_duration.values.p95 // "N/A"' "$VT_FILE" 2>/dev/null || echo "N/A")
echo "  Baseline: $BASELINE_P95"
echo "  Virtual:  $VT_P95"

if [ "$BASELINE_P95" != "N/A" ] && [ "$VT_P95" != "N/A" ]; then
    CHANGE=$(echo "scale=2; (($VT_P95 - $BASELINE_P95) / $BASELINE_P95) * 100" | bc 2>/dev/null || echo "N/A")
    echo "  Change:   ${CHANGE}%"
fi
echo ""

echo "=== Latency p99 (ms) ==="
echo ""
BASELINE_P99=$(jq -r '.metrics.http_req_duration.values.p99 // "N/A"' "$BASELINE_FILE" 2>/dev/null || echo "N/A")
VT_P99=$(jq -r '.metrics.http_req_duration.values.p99 // "N/A"' "$VT_FILE" 2>/dev/null || echo "N/A")
echo "  Baseline: $BASELINE_P99"
echo "  Virtual:  $VT_P99"

if [ "$BASELINE_P99" != "N/A" ] && [ "$VT_P99" != "N/A" ]; then
    CHANGE=$(echo "scale=2; (($VT_P99 - $BASELINE_P99) / $BASELINE_P99) * 100" | bc 2>/dev/null || echo "N/A")
    echo "  Change:   ${CHANGE}%"
fi
echo ""

echo "=== Error Rate ==="
echo ""
BASELINE_ERR=$(jq -r '.metrics.http_req_failed.values.rate // "N/A"' "$BASELINE_FILE" 2>/dev/null || echo "N/A")
VT_ERR=$(jq -r '.metrics.http_req_failed.values.rate // "N/A"' "$VT_FILE" 2>/dev/null || echo "N/A")
echo "  Baseline: $BASELINE_ERR"
echo "  Virtual:  $VT_ERR"
echo ""

echo "=============================================="
echo "  Summary"
echo "=============================================="
echo ""

# Determine go/no-go based on thresholds
GO_DECISION="GO"
NOTES=""

# Check for regression > 10% in throughput
if [ "$BASELINE_RATE" != "N/A" ] && [ "$VT_RATE" != "N/A" ]; then
    REGRESSION=$(echo "$VT_RATE < ($BASELINE_RATE * 0.9)" | bc 2>/dev/null || echo "0")
    if [ "$REGRESSION" = "1" ]; then
        GO_DECISION="NO-GO"
        NOTES="Throughput regression > 10%"
    fi
fi

# Check for regression > 10% in p99 latency
if [ "$BASELINE_P99" != "N/A" ] && [ "$VT_P99" != "N/A" ]; then
    REGRESSION=$(echo "$VT_P99 > ($BASELINE_P99 * 1.1)" | bc 2>/dev/null || echo "0")
    if [ "$REGRESSION" = "1" ]; then
        GO_DECISION="NO-GO"
        NOTES="${NOTES:+$NOTES; }p99 latency regression > 10%"
    fi
fi

echo "  Decision: $GO_DECISION"
if [ -n "$NOTES" ]; then
    echo "  Notes:    $NOTES"
fi
echo ""
echo "=============================================="
