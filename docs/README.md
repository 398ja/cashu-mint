# Cashu Mint Documentation

This directory contains documentation organized using the Diátaxis framework.

## Tutorials
- [Getting started](tutorials/getting-started.md) - Walk through starting a local Cashu mint.
- [Run with Docker Compose](tutorials/run-with-docker-compose.md) - Start the stack using Docker Compose.

## How-to guides
- [Development workflow](how-to/development-workflow.md) - Prepare dependencies, migrations, and tests.
- [Run tests](how-to/run-tests.md) - Step-by-step instructions for executing the test suite.
- [Publish a Docker image](how-to/publish-docker-image.md) - Build and push the REST image.
- [Configure the mint](how-to/configure-mint.md) - Override configuration properties.
- [Configure gateways](how-to/configure-gateways.md) - Map methods/units to gateway classes.
- [Install a staging mint with Docker](how-to/install-staging-docker.md) - Deploy the dev stack on a staging host with published images.
- [Enable observability](how-to/enable-observability.md) - Start Prometheus/Grafana/Jaeger and tune metrics/traces.

## Reference
- [Configuration](reference/configuration.md) - Application properties and defaults.
- [REST API](reference/rest-api.md) - HTTP endpoints exposed by the mint.
- [Tools](reference/tools.md) - Preload profiles and usage for generating test data.
- [Module layers](reference/module-layers.md) - Module boundaries, package layout, and wiring examples.
- [Artifact dependencies](reference/artifact-dependencies.md) - How the Cashu modules, vaults, gateways, and libraries relate.
- [Supported NUTs](reference/nuts.md) - Implemented Cashu protocol specs.
- [Java version](reference/java-version.md) - Required JDK for building.
- [License](reference/license.md) - Licensing information.
- [Metrics reference](../cashu-mint-observability/docs/metrics-reference.md) - Prometheus metrics exposed by the observability module.

## Explanations
- [Architecture overview](explanations/architecture-overview.md) - High-level component interactions.
- [Architecture and NUTs](explanations/architecture-and-nuts.md) - Module responsibilities and spec mapping.
- [Disclaimer](explanations/disclaimer.md) - Project maturity notice.
- [Representing vouchers as structured secrets](explanations/voucher-structured-secrets.md) - Encode voucher metadata in custom secrets without changing the mint API.
- [Voucher mock payment and free splitting](explanations/voucher-mock-payment.md) - How voucher tokens skip payment verification and support arbitrary denominations.
- [Voucher mint quotes as percentage fees](explanations/voucher-mint-quote-percentage.md) - Configure voucher mint pricing as a percentage of the face value.
- [Voucher mint quote percentage fees (overview)](explanations/voucher-mint-quote-overview.md) - Quick reference for how percentage-based voucher pricing works.
