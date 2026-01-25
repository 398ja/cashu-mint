#!/bin/bash
#
# Heap Exhaustion Test for Virtual Threads
#
# This script tests the JVM's ability to handle high concurrent virtual thread counts
# without running out of memory. It's designed to validate heap sizing before
# deploying virtual threads to production.
#
# Prerequisites:
#   - Docker installed (for k6)
#   - jq installed
#   - cashu-mint-rest JAR built
#
# Usage:
#   ./scripts/heap-exhaustion-test.sh [--heap-size 2g] [--connections 5000] [--duration 300]
#

set -e

# Default configuration
HEAP_SIZE="${HEAP_SIZE:-2g}"
MAX_CONNECTIONS="${MAX_CONNECTIONS:-5000}"
DURATION_SECONDS="${DURATION_SECONDS:-300}"  # 5 minutes
MINT_PORT="${MINT_PORT:-7777}"
JAR_PATH="cashu-mint-rest/target/cashu-mint-rest-*-exec.jar"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --heap-size)
            HEAP_SIZE="$2"
            shift 2
            ;;
        --connections)
            MAX_CONNECTIONS="$2"
            shift 2
            ;;
        --duration)
            DURATION_SECONDS="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [--heap-size SIZE] [--connections NUM] [--duration SECONDS]"
            echo ""
            echo "Options:"
            echo "  --heap-size SIZE    JVM heap size (default: 2g)"
            echo "  --connections NUM   Max concurrent connections to simulate (default: 5000)"
            echo "  --duration SECONDS  Test duration in seconds (default: 300)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "=============================================="
echo "  Heap Exhaustion Test for Virtual Threads"
echo "=============================================="
echo ""
echo "Configuration:"
echo "  Heap Size:       $HEAP_SIZE"
echo "  Max Connections: $MAX_CONNECTIONS"
echo "  Duration:        ${DURATION_SECONDS}s"
echo "  Mint Port:       $MINT_PORT"
echo ""

# Find the JAR file
JAR_FILE=$(ls $JAR_PATH 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_PATH"
    echo "Please build the project first: mvn clean package -DskipTests"
    exit 1
fi

echo "Using JAR: $JAR_FILE"
echo ""

# Create results directory
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_DIR="results/heap-test-$TIMESTAMP"
mkdir -p "$RESULTS_DIR"

# Start the application with virtual threads enabled
echo "Starting mint with virtual threads enabled..."
java \
    -Xmx${HEAP_SIZE} \
    -Xms${HEAP_SIZE} \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="${RESULTS_DIR}/heapdump.hprof" \
    -XX:StartFlightRecording=filename="${RESULTS_DIR}/heap-test.jfr",duration=${DURATION_SECONDS}s,settings=profile \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9999 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dspring.threads.virtual.enabled=true \
    -Dserver.port=$MINT_PORT \
    -jar "$JAR_FILE" \
    > "${RESULTS_DIR}/app.log" 2>&1 &

APP_PID=$!
echo "Application started with PID: $APP_PID"

# Wait for application to start
echo "Waiting for application to start..."
for i in {1..60}; do
    if curl -s "http://localhost:$MINT_PORT/v1/info" > /dev/null 2>&1; then
        echo "Application is ready!"
        break
    fi
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo "Error: Application failed to start"
        cat "${RESULTS_DIR}/app.log"
        exit 1
    fi
    sleep 1
done

# Verify application is running
if ! curl -s "http://localhost:$MINT_PORT/v1/info" > /dev/null 2>&1; then
    echo "Error: Application did not start in time"
    kill $APP_PID 2>/dev/null || true
    exit 1
fi

# Function to capture heap info
capture_heap_info() {
    local label=$1
    echo "--- Heap Info ($label) ---" >> "${RESULTS_DIR}/heap-samples.txt"
    jcmd $APP_PID GC.heap_info >> "${RESULTS_DIR}/heap-samples.txt" 2>&1 || true
    echo "" >> "${RESULTS_DIR}/heap-samples.txt"
}

# Capture initial heap state
capture_heap_info "initial"

# Run the load test using k6 (via Docker if available)
echo ""
echo "Running load test with $MAX_CONNECTIONS concurrent connections..."
echo ""

# Calculate k6 parameters
RAMP_DURATION=$((DURATION_SECONDS / 4))
HOLD_DURATION=$((DURATION_SECONDS / 2))
RAMP_DOWN_DURATION=$((DURATION_SECONDS / 4))

# Create a simple k6 script for slow connections
cat > "${RESULTS_DIR}/slow-connections.js" << 'EOF'
import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
    scenarios: {
        slow_connections: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '__RAMP__s', target: __CONNECTIONS__ },
                { duration: '__HOLD__s', target: __CONNECTIONS__ },
                { duration: '__RAMP_DOWN__s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(99)<30000'],  // Allow slow responses
        http_req_failed: ['rate<0.5'],        // Allow high error rate (testing limits)
    },
};

export default function () {
    // Make a request and hold the connection
    const res = http.get('http://host.docker.internal:__PORT__/v1/info', {
        timeout: '30s',
    });

    // Simulate slow client - hold connection
    sleep(5 + Math.random() * 5);  // 5-10 seconds
}
EOF

# Replace placeholders
sed -i "s/__RAMP__/$RAMP_DURATION/g" "${RESULTS_DIR}/slow-connections.js"
sed -i "s/__HOLD__/$HOLD_DURATION/g" "${RESULTS_DIR}/slow-connections.js"
sed -i "s/__RAMP_DOWN__/$RAMP_DOWN_DURATION/g" "${RESULTS_DIR}/slow-connections.js"
sed -i "s/__CONNECTIONS__/$MAX_CONNECTIONS/g" "${RESULTS_DIR}/slow-connections.js"
sed -i "s/__PORT__/$MINT_PORT/g" "${RESULTS_DIR}/slow-connections.js"

# Run k6 via Docker
if command -v docker &> /dev/null; then
    echo "Running k6 via Docker..."
    docker run --rm \
        --add-host=host.docker.internal:host-gateway \
        -v "${PWD}/${RESULTS_DIR}:/results" \
        grafana/k6 run \
        --out json=/results/k6-results.json \
        /results/slow-connections.js &
    K6_PID=$!
else
    echo "Docker not available. Please install Docker to run k6."
    echo "Alternatively, run k6 manually:"
    echo "  k6 run ${RESULTS_DIR}/slow-connections.js"
    K6_PID=""
fi

# Monitor heap during test
echo "Monitoring heap usage during test..."
SAMPLE_INTERVAL=30
SAMPLES=$((DURATION_SECONDS / SAMPLE_INTERVAL))

for i in $(seq 1 $SAMPLES); do
    sleep $SAMPLE_INTERVAL
    capture_heap_info "sample-$i (${i}/${SAMPLES})"

    # Check if app is still running
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo "WARNING: Application crashed!"
        break
    fi

    # Print progress
    ELAPSED=$((i * SAMPLE_INTERVAL))
    echo "Progress: ${ELAPSED}s / ${DURATION_SECONDS}s"
done

# Wait for k6 to finish
if [ -n "$K6_PID" ]; then
    echo "Waiting for k6 to finish..."
    wait $K6_PID 2>/dev/null || true
fi

# Capture final heap state
capture_heap_info "final"

# Check for OOM in logs
echo ""
echo "Checking for OOM errors..."
if grep -i "OutOfMemory" "${RESULTS_DIR}/app.log"; then
    echo "FAIL: OutOfMemoryError detected!"
    RESULT="FAIL"
else
    echo "PASS: No OutOfMemoryError detected"
    RESULT="PASS"
fi

# Gracefully stop the application
echo ""
echo "Stopping application..."
kill -TERM $APP_PID 2>/dev/null || true
sleep 5
kill -9 $APP_PID 2>/dev/null || true

# Generate summary
echo ""
echo "=============================================="
echo "  Test Results Summary"
echo "=============================================="
echo ""
echo "Result: $RESULT"
echo ""
echo "Artifacts saved to: ${RESULTS_DIR}/"
echo "  - app.log: Application logs"
echo "  - heap-samples.txt: Heap info samples"
echo "  - heap-test.jfr: JFR recording"
echo "  - k6-results.json: Load test results (if k6 ran)"
if [ -f "${RESULTS_DIR}/heapdump.hprof" ]; then
    echo "  - heapdump.hprof: Heap dump (OOM occurred)"
fi
echo ""

# Memory sizing guidance
echo "Memory Sizing Guidance:"
echo "  1,000 VTs  -> 512MB heap"
echo "  5,000 VTs  -> 1GB heap"
echo "  10,000 VTs -> 2GB heap"
echo "  50,000 VTs -> 4GB+ heap"
echo ""

if [ "$RESULT" = "FAIL" ]; then
    exit 1
fi
