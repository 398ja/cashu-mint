# Cashu Mint Documentation

This directory contains documentation organized using the Diátaxis framework.

## Tutorials
- [Getting started](tutorials/getting-started.md) - Walk through starting a local Cashu mint.
- [Run with Docker Compose](tutorials/run-with-docker-compose.md) - Start the stack using Docker Compose.
- [Administer a mint from the CLI](tutorials/administer-mint-from-cli.md) - Practice the admin workflows with stub data.

## How-to guides
- [Development workflow](how-to/development-workflow.md) - Prepare dependencies, migrations, and tests.
- [Run tests](how-to/run-tests.md) - Step-by-step instructions for executing the test suite.
- [Publish a Docker image](how-to/publish-docker-image.md) - Build and push the REST image.
- [Configure the mint](how-to/configure-mint.md) - Override configuration properties.
- [Configure gateways](how-to/configure-gateways.md) - Map methods/units to gateway classes.
- [Configure mint admin persistence](how-to/configure-mint-admin-persistence.md) - Wire databases and migrations for the admin module.
- [Connect the admin CLI to the REST service](how-to/connect-admin-cli-to-rest.md) - Replace the stub ports with REST-backed adapters.
- [Manage the mint lifecycle from the CLI](how-to/manage-mint-lifecycle-cli.md) - Drive create, update, pause, resume, and retire commands.
- [Manage the mint lifecycle via the REST API](how-to/manage-mint-lifecycle-api.md) - Exercise lifecycle endpoints with HTTP requests.

## Reference
- [Configuration](reference/configuration.md) - Application properties and defaults.
- [REST API](reference/rest-api.md) - HTTP endpoints exposed by the mint.
- [Module layers](reference/module-layers.md) - Module boundaries, package layout, and wiring examples.
- [Supported NUTs](reference/nuts.md) - Implemented Cashu protocol specs.
- [Java version](reference/java-version.md) - Required JDK for building.
- [License](reference/license.md) - Licensing information.
- [Mint admin CLI](reference/mint-admin-cli.md) - Commands, payloads, and renderer options for the admin tool.
- [Administrative lifecycle audit schema](reference/admin-lifecycle-audit-schema.md) - Tables backing lifecycle projections,
  approvals, and audit linkage.

## Explanations
- [Architecture overview](explanations/architecture-overview.md) - High-level component interactions.
- [Architecture and NUTs](explanations/architecture-and-nuts.md) - Module responsibilities and spec mapping.
- [Disclaimer](explanations/disclaimer.md) - Project maturity notice.
- [Cashu admin phase 2 technical analysis](../cashu-mint-admin/project/Phase2/phase2-technical-analysis.md) - Technical interpretation of the phase 2 web interface scope.
