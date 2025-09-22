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
  - Admin docs moved to the separate admin project at `../cashu-mint-admin`.
- Admin CLI lifecycle guide moved to the separate admin project at `../cashu-mint-admin`.
- [Manage the mint lifecycle via the REST API](how-to/manage-mint-lifecycle-api.md) - Exercise lifecycle endpoints with HTTP requests.

## Reference
- [Configuration](reference/configuration.md) - Application properties and defaults.
- [REST API](reference/rest-api.md) - HTTP endpoints exposed by the mint.
- [Tools](reference/tools.md) - Preload profiles and usage for generating test data.
- [Module layers](reference/module-layers.md) - Module boundaries, package layout, and wiring examples.
- [Supported NUTs](reference/nuts.md) - Implemented Cashu protocol specs.
- [Java version](reference/java-version.md) - Required JDK for building.
- [License](reference/license.md) - Licensing information.
- Admin docs moved to the separate admin project at `../cashu-mint-admin`.
  approvals, and audit linkage.

## Explanations
- [Architecture overview](explanations/architecture-overview.md) - High-level component interactions.
- [Architecture and NUTs](explanations/architecture-and-nuts.md) - Module responsibilities and spec mapping.
- [Disclaimer](explanations/disclaimer.md) - Project maturity notice.
