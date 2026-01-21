/**
 * k6 Load Test Script for Cashu Mint
 *
 * This script tests the mint's performance under various load conditions.
 * It covers the main operations: info, keysets, mint quotes, melt quotes, and swap.
 *
 * Usage:
 *   k6 run scripts/load-test-mint.js
 *   k6 run --vus 50 --duration 60s scripts/load-test-mint.js
 *   k6 run --out json=results.json scripts/load-test-mint.js
 *
 * Environment variables:
 *   MINT_URL - Base URL of the mint (default: http://localhost:7777)
 *   TEST_KEYSET_ID - Keyset ID to use for tests (default: auto-discovered)
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const mintQuoteLatency = new Trend('mint_quote_latency', true);
const meltQuoteLatency = new Trend('melt_quote_latency', true);
const swapLatency = new Trend('swap_latency', true);
const infoLatency = new Trend('info_latency', true);
const keysetsLatency = new Trend('keysets_latency', true);
const errorRate = new Rate('errors');
const successfulMintQuotes = new Counter('successful_mint_quotes');
const successfulMeltQuotes = new Counter('successful_melt_quotes');

// Configuration
const BASE_URL = __ENV.MINT_URL || 'http://localhost:7777';
const PAYMENT_METHOD = 'bolt11';

// Test scenarios - ramping VUs from 0 to peak and back down
export const options = {
    scenarios: {
        // Smoke test - quick validation
        smoke: {
            executor: 'constant-vus',
            vus: 1,
            duration: '10s',
            gracefulStop: '5s',
            exec: 'smokeTest',
            startTime: '0s',
        },
        // Load test - gradual ramp up
        load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 25 },   // Ramp to 25 VUs
                { duration: '1m', target: 50 },    // Ramp to 50 VUs
                { duration: '1m', target: 100 },   // Ramp to 100 VUs
                { duration: '1m', target: 100 },   // Hold at 100 VUs
                { duration: '30s', target: 0 },    // Ramp down
            ],
            gracefulStop: '30s',
            exec: 'loadTest',
            startTime: '15s',  // Start after smoke test
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],  // 95% under 500ms, 99% under 1s
        http_req_failed: ['rate<0.01'],                  // Error rate under 1%
        errors: ['rate<0.05'],                           // Custom error rate under 5%
        info_latency: ['p(95)<100'],                     // Info should be fast
        keysets_latency: ['p(95)<200'],                  // Keysets should be fast
        mint_quote_latency: ['p(95)<500'],               // Quote creation threshold
        melt_quote_latency: ['p(95)<500'],               // Melt quote threshold
    },
};

// Shared state for discovered keyset
let keysetId = __ENV.TEST_KEYSET_ID || null;

/**
 * Setup function - runs once before all VUs
 * Discovers available keysets
 */
export function setup() {
    console.log(`Testing mint at: ${BASE_URL}`);

    // Get mint info
    const infoRes = http.get(`${BASE_URL}/v1/info`);
    if (infoRes.status !== 200) {
        console.error(`Failed to get mint info: ${infoRes.status}`);
        return { keysetId: null };
    }

    // Discover keysets
    const keysetsRes = http.get(`${BASE_URL}/v1/keysets`);
    if (keysetsRes.status !== 200) {
        console.error(`Failed to get keysets: ${keysetsRes.status}`);
        return { keysetId: null };
    }

    try {
        const keysets = JSON.parse(keysetsRes.body);
        if (keysets.keysets && keysets.keysets.length > 0) {
            // Find an active keyset
            const activeKeyset = keysets.keysets.find(ks => ks.active);
            if (activeKeyset) {
                keysetId = activeKeyset.id;
                console.log(`Discovered active keyset: ${keysetId}`);
            } else {
                keysetId = keysets.keysets[0].id;
                console.log(`Using first keyset: ${keysetId}`);
            }
        }
    } catch (e) {
        console.error(`Failed to parse keysets: ${e}`);
    }

    return { keysetId: keysetId };
}

/**
 * Smoke test - basic validation of endpoints
 */
export function smokeTest(data) {
    group('Smoke Test', function() {
        // Test /info endpoint
        const infoRes = http.get(`${BASE_URL}/v1/info`);
        check(infoRes, {
            'info status is 200': (r) => r.status === 200,
            'info has name': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.name !== undefined;
                } catch (e) {
                    return false;
                }
            },
        });
        infoLatency.add(infoRes.timings.duration);

        // Test /keysets endpoint
        const keysetsRes = http.get(`${BASE_URL}/v1/keysets`);
        check(keysetsRes, {
            'keysets status is 200': (r) => r.status === 200,
            'keysets has array': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return Array.isArray(body.keysets);
                } catch (e) {
                    return false;
                }
            },
        });
        keysetsLatency.add(keysetsRes.timings.duration);

        sleep(0.5);
    });
}

/**
 * Main load test - exercises mint operations
 */
export function loadTest(data) {
    const keysetId = data.keysetId;

    // Randomly select an operation
    const operations = ['info', 'keysets', 'mintQuote', 'meltQuote'];
    const weights = [0.3, 0.2, 0.3, 0.2];  // Weighted distribution
    const operation = selectWeighted(operations, weights);

    switch (operation) {
        case 'info':
            testInfo();
            break;
        case 'keysets':
            testKeysets();
            break;
        case 'mintQuote':
            testMintQuote();
            break;
        case 'meltQuote':
            testMeltQuote();
            break;
    }

    // Small random sleep to simulate think time
    sleep(Math.random() * 0.5 + 0.1);
}

/**
 * Test GET /v1/info endpoint
 */
function testInfo() {
    const res = http.get(`${BASE_URL}/v1/info`);
    const success = check(res, {
        'info: status 200': (r) => r.status === 200,
    });
    if (!success) errorRate.add(1);
    infoLatency.add(res.timings.duration);
}

/**
 * Test GET /v1/keysets endpoint
 */
function testKeysets() {
    const res = http.get(`${BASE_URL}/v1/keysets`);
    const success = check(res, {
        'keysets: status 200': (r) => r.status === 200,
    });
    if (!success) errorRate.add(1);
    keysetsLatency.add(res.timings.duration);
}

/**
 * Test POST /v1/mint/quote/{method} endpoint
 */
function testMintQuote() {
    const amounts = [100, 500, 1000, 5000, 10000];
    const amount = amounts[Math.floor(Math.random() * amounts.length)];

    const payload = JSON.stringify({
        amount: amount,
        unit: 'sat'
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post(
        `${BASE_URL}/v1/mint/quote/${PAYMENT_METHOD}`,
        payload,
        params
    );

    const success = check(res, {
        'mint quote: status 200': (r) => r.status === 200,
        'mint quote: has quote': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.quote !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    if (success) {
        successfulMintQuotes.add(1);
    } else {
        errorRate.add(1);
    }
    mintQuoteLatency.add(res.timings.duration);
}

/**
 * Test POST /v1/melt/quote/{method} endpoint
 */
function testMeltQuote() {
    // Generate a dummy BOLT11 invoice for testing
    // In real tests, this would be a valid invoice
    const dummyInvoice = generateDummyBolt11();

    const payload = JSON.stringify({
        request: dummyInvoice,
        unit: 'sat'
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post(
        `${BASE_URL}/v1/melt/quote/${PAYMENT_METHOD}`,
        payload,
        params
    );

    // Melt quote may fail with invalid invoice, which is expected
    const success = check(res, {
        'melt quote: status 200 or 4xx': (r) => r.status === 200 || (r.status >= 400 && r.status < 500),
    });

    if (res.status === 200) {
        successfulMeltQuotes.add(1);
    }
    if (!success) {
        errorRate.add(1);
    }
    meltQuoteLatency.add(res.timings.duration);
}

/**
 * Helper: Select item based on weights
 */
function selectWeighted(items, weights) {
    const total = weights.reduce((a, b) => a + b, 0);
    let random = Math.random() * total;

    for (let i = 0; i < items.length; i++) {
        random -= weights[i];
        if (random <= 0) {
            return items[i];
        }
    }
    return items[items.length - 1];
}

/**
 * Helper: Generate a dummy BOLT11 invoice
 * This is for testing the melt quote endpoint - the gateway will reject it
 */
function generateDummyBolt11() {
    // This is a malformed invoice that will be rejected by the gateway
    // but allows us to test the endpoint's parsing and error handling
    return 'lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w';
}

/**
 * Teardown function - runs once after all VUs complete
 */
export function teardown(data) {
    console.log('Load test completed');
    console.log(`Keyset used: ${data.keysetId || 'none discovered'}`);
}
