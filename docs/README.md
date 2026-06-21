# Cashu Mint Documentation

> **Disclaimer:** This project is a work in progress and is not yet ready for production use. Use at your own risk.

This directory contains documentation organized using the [Diataxis framework](https://diataxis.fr/).

## Tutorials

- [Getting started](tutorials/getting-started.md) - Walk through starting a local Cashu mint.
- [Run with Docker Compose](tutorials/run-with-docker-compose.md) - Start the stack using Docker Compose.
- [WebSocket client example](tutorials/websocket-client-example.md) - Connect to the WebSocket endpoint and observe state changes.

## How-to guides

- [Development workflow](how-to/development-workflow.md) - Prepare dependencies, migrations, and tests.
- [Run tests](how-to/run-tests.md) - Unit, integration, and E2E test instructions.
- [Run E2E tests](how-to/run-e2e-tests.md) - End-to-end test infrastructure and execution.
- [Add a NUT implementation](how-to/add-a-nut.md) - Step-by-step guide for implementing a new NUT.
- [Publish a Docker image](how-to/publish-docker-image.md) - Build and push the REST image.
- [Configure the mint](how-to/configure-mint.md) - Override configuration properties.
- [Configure gateways](how-to/configure-gateways.md) - Map methods/units to gateway classes.
- [Develop a custom gateway adapter](how-to/develop-gateway-adapter.md) - Implement and register a new payment gateway.
- [Deploy to production](how-to/deploy-production.md) - Production deployment checklist with TLS, secrets, monitoring.
- [Install a staging mint with Docker](how-to/install-staging-docker.md) - Deploy the dev stack on a staging host with published images.
- [Enable observability](how-to/enable-observability.md) - Start Prometheus/Grafana/Jaeger and tune metrics/traces.
- [Enable the trace producer](how-to/enable-trace-producer.md) - Emit signed kind-9079 trace events to the cashu-ledger forensic ledger (spec 036).
- [Troubleshoot common issues](how-to/troubleshoot-common-issues.md) - Solutions for build, Docker, gateway, and test problems.

## Reference

- [Configuration](reference/configuration.md) - Application properties, prerequisites, and defaults.
- [Environment variables](reference/environment-variables.md) - Comprehensive environment variable reference.
- [REST API](reference/rest-api.md) - HTTP endpoints exposed by the mint.
- [Error codes](reference/error-codes.md) - REST API error codes, causes, and resolutions.
- [Glossary](reference/glossary.md) - Cashu and ecash terminology.
- [Tools](reference/tools.md) - Preload profiles and usage for generating test data.
- [Module layers](reference/module-layers.md) - Module boundaries, package layout, and wiring examples.
- [Artifact dependencies](reference/artifact-dependencies.md) - How the Cashu modules, vaults, gateways, and libraries relate.
- [Supported NUTs](reference/nuts.md) - Implemented Cashu protocol specs with implementation classes and status.
- [Metrics reference](../cashu-mint-observability/docs/metrics-reference.md) - Prometheus metrics exposed by the observability module.

## Explanations

- [Architecture overview](explanations/architecture-overview.md) - High-level component interactions and request flow.
- [Architecture and NUTs](explanations/architecture-and-nuts.md) - Module responsibilities and spec mapping.
- [Representing vouchers as structured secrets](explanations/voucher-structured-secrets.md) - Encode voucher metadata in custom secrets without changing the mint API.
- [Voucher mock payment and free splitting](explanations/voucher-mock-payment.md) - How voucher tokens skip payment verification and support arbitrary denominations.
- [Voucher mint quotes as percentage fees](explanations/voucher-mint-quote-percentage.md) - Configure voucher mint pricing as a percentage of the face value.
- [Payment webhook architecture](explanations/payment-webhook-architecture.md) - How payment notifications changed from polling to push-based webhooks in v0.8.0.
- [Security measures](explanations/security-measures.md) - Comprehensive security mechanisms: double-spend prevention, cryptographic verification, input validation, webhook security, and operational security.
- [Virtual thread adoption](explanations/virtual-thread-adoption.md) - Why virtual threads were adopted, audit results, pilot findings, and current status.

## Runbooks

- [Virtual thread issues](runbooks/virtual-thread-issues.md) - Troubleshooting lock contention, pinning, and memory issues with Virtual Threads.

## Archive

Historical documents preserved for reference. See [archive/README.md](archive/README.md) for the index.

---

This project is licensed under the MIT License. See [LICENSE.md](../LICENSE.md) for details.
